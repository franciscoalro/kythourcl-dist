# TomatoAnimes — Plano de Functionalização

> **Para Hermes:** Use o skill `subagent-driven-development` para executar este
> plano tarefa por tarefa, com revisão em duas etapas (conformidade com a
> especificação e depois qualidade do código).

**Objetivo:** Levar o plugin CloudStream3 TomatoAnimes a um estado em que o
usuário consegue navegar o catálogo, abrir um anime e reproduzir um episódio,
com falha de origem tratada de forma observável em vez de silenciosa.

**Arquitetura:** O plugin é um provider Kotlin (CloudStream3) empacotado em
`.cs3` (zip com `classes.dex` e manifest interno). O trabalho é diagnóstico
medido da origem, correção do comportamento sob falha e validação do artefato
publicado. A origem é externa e oscila; isso não é corrigível no plugin, mas o
comportamento sob a falha é.

**Stack:** Kotlin, Gradle (`:Tomato:makePlugins`), Android (CloudStream3),
Cloudflare WARP (SOCKS5 `127.0.0.1:40000`) como rota de teste, Python para
sondas, Git.

---

## Estado medido em 2026-09-27 (medição, não suposição)

Três medições feitas nesta sessão com WARP ativo (`warp-svc` active, saída
`104.28.207.215`):

| Medida | Sem WARP (IP 167.233.60.72) | Via WARP | Leitura |
|---|---|---|---|
| `GET /v2/animes/feed` | 500 | **200** | API viva; sem WARP é bloqueio por IP |
| `GET /v2/anime/1921` | 500 | **200** | idem |
| `GET /zzz-nao-existe-12345` | 500 | 403/500 | dispatch nunca roda sem WARP |
| `POST /season/215/episodes` p0/p1/p2 | 500 | **200/200/500** | paginação real, 22 eps em p0 |
| `POST /v2/content/search` | 500 | 500 | rota oscilante |
| `GET /v2/anime/episode/6412/stream` | 500 | 500 | idem |

**Conclusões que o plano assume:**

1. **O 500 sem WARP é bloqueio do WAF por IP, não origem morta.** Prova: a mesma
   rota que dava 500 sem proxy deu 200 via WARP. Com o token correto e o caminho
   certo, a origem responde. O comentário em `isOriginDead()` (linhas 873 a 893)
   afirma que 500 em rota inexistente é "origem morta de forma estrutural" — essa
   leitura está errada e a função corta o retry cedo demais, produzindo "nenhum
   link" durante janelas em que a API voltaria. Ver T0.
2. **O token Bearer é obrigatório.** Prova: mesma rota, mesma sessão WARP, 200 com
   token e 403 `authentication failed` sem ele. O token não pode ser removido, e o
   objetivo de publicar sem o header está descartado.
3. **A origem oscila por janela, não por rota.** `/feed` e `/anime/{id}`
   responderam 200 e depois 500 na mesma sessão, com segundos de intervalo.
4. **A season 215 tem exatamente 22 episódios.** Prova: `episodes: 22` como total
   declarado e página 1 com `data: []`. A season cabia em uma página, então a v163
   está correta e não há season truncada agora.

**O que não é bug do plugin:** o 500 sem WARP. O plugin roda no aparelho do
usuário, não no IP 167.233.60.72. Esse 500 é artefato do ambiente de teste do
servidor, não da origem. Este plano não tenta consertar o 500 sem WARP. Ver T0.

---

## Tarefa 0 — Diagnosticar e corrigir a detecção de origem morta

**Objetivo:** Fazer `isOriginDead()` distinguir bloqueio de WAF por IP de origem
realmente morta, porque hoje ela produz falso positivo e corta o retry cedo.

**Arquivos:**
- Modificar: `Tomato/src/main/kotlin/com/tomato/Tomato.kt:873-921` (comentário
  e lógica de `isOriginDead()` e `isOriginDown()`)

**Contexto medido:** via WARP, rota inexistente devolveu 403 e rota real devolveu
200. Sem WARP, ambas devolvem 500. Então 500 em rota inexistente **não** distingue
os dois casos: no IP bloqueado, o dispatch nunca roda porque o WAF responde antes
do backend, e o 500 é do WAF, não do backend. A função atual trata isso como
"origem morta" e retorna cedo.

**Por que isso é o bloqueio principal:** com a detecção errada, o usuário recebe
"nenhum link" em janelas em que a API responderia. O sintoma é indistinguível de
"o plugin está quebrado".

**Mudança proposta:** a detecção estrutural só é válida quando a rede já está
saindo por um caminho não bloqueado. Como o plugin roda no aparelho do usuário e
não tem WARP, o plugin **não** tem como saber se o próprio IP está bloqueado. A
correção honesta é não cortar o retry por uma heurística que se provou errada:
manter o retry, mas distinguir "500 em rota de conteúdo" de "500 em rota
inexistente" apenas como **log**, não como corte de retry. Registrar o motivo da
falha para diagnóstico.

Alternativa mais simples e mais honesta, se a Tarefa 1 mostrar que o stream
precisa de retry longo: manter `isOriginDown()` (403 = credencial, 500 = endpoint)
e usar apenas ela, descartando a detecção estrutural que se provou errada.

**Passos:**

1. Ler `Tomato.kt:894-921` e confirmar a lógica atual.
2. Escrever uma sonda que reproduza os dois estados: com WARP (rota inexistente
   403, rota real 200) e sem WARP (ambas 500).
3. Corrigir `isOriginDead()` para não cortar o retry com base na heurística
   estrutural, mantendo o log do sinal.
4. Atualizar o comentário das linhas 873 a 893, que hoje afirma a conclusão
   errada.
5. Compilar: `./gradlew :Tomato:makePlugins`
6. Validar: o log de falha deve distinguir os dois casos sem cortar cedo.
7. Commitar.

```bash
cd /root/kythourcl-dist
git add Tomato/src/main/kotlin/com/tomato/Tomato.kt
git commit -m "fix(Tomato): isOriginDead nao corta retry por heuristica refutada (WAF por IP)"
```

---

## Tarefa 1 — Medir a taxa de sucesso do stream

**Objetivo:** Saber se `/stream` está morto ou oscilante, porque sem essa
medição qualquer correção é chute.

**Arquivos:**
- Criar: `tools/probe_stream_rate.py`

**Contexto:** `/v2/anime/episode/6412/stream` deu 500 em 5 de 5 tentativas via
WARP. A doc de WAF registra que o stream oscila com 12 a 20 por cento de taxa de
sucesso, mas isso é de uma janela de medição antiga.

**Passos:**

1. Criar `tools/probe_stream_rate.py` que faz N requisições ao stream de
   episódios reais, com intervalo, e imprime código e taxa.
2. Executar com N=20 e intervalo de 3 segundos.
3. Registrar o resultado em `tools/` e no commit.
4. Se a taxa for 0 por cento em 20 tentativas, a origem está sem stream e o
   plugin não pode ser validado end-to-end nesta janela. Isso é achado, não
   falha de execução.
5. Commitar.

```bash
cd /root/kythourcl-dist
python3 tools/probe_stream_rate.py --n 20 --interval 3
git add tools/probe_stream_rate.py
git commit -m "test(Tomato): mede taxa de sucesso do stream em janela datada"
```

---

## Tarefa 2 — Validar o artefato e os manifests publicados

**Objetivo:** Confirmar que o que está no CDN é a v163 validada, não o que o
código local sugere.

**Arquivos:**
- Verificar: `builds/Tomato.cs3`, `plugins.json`, `repo-ok.json`

**Contexto medido:** o commit de publicação foi `4c7055c`, com 67583 bytes e
hash `d30f0b94`. Já validado nesta sessão. Esta tarefa é revalidação, não
descoberta.

**Passos:**

1. Comparar o hash local de `builds/Tomato.cs3` com o hash servido pelo CDN.
2. Confirmar que o manifest interno do `.cs3` diz versão 163 e
   `pluginClassName` `com.tomato.TomatoProvider`.
3. Confirmar que `plugins.json` aponta para a v163, com o mesmo tamanho e hash.
4. Se houver divergência, esperar propagação e repetir. Divergência entre blob e
   endpoint por commit já foi vista e era cache.
5. Commitar apenas se algo mudar.

```bash
cd /root/kythourcl-dist
sha256sum builds/Tomato.cs3
curl -s https://cdn.jsdelivr.net/gh/kythourcl/kythourcl-dist@main/builds/Tomato.cs3 -o /tmp/served.cs3
sha256sum /tmp/served.cs3
```

---

## Tarefa 3 — Medir a paginação de seasons ao vivo

**Objetivo:** Validar ao vivo a lógica da v163, que só foi exercitada na página 0.

**Arquivos:**
- Nenhum arquivo alterado. Sonda.

**Contexto medido:** season 215, página 0 devolveu 22 episódios com total
declarado 22, e página 1 devolveu `data: []`. Ou seja, a season 215 não exercita
a paginação multi-página. Para exercitar, é preciso uma season com mais de 22
episódios.

**Passos:**

1. Via WARP, listar as seasons de um anime com many episodes, por exemplo o
   anime 1089 do episódio 6412.
2. Para cada season, ler o total declarado.
3. Escolher uma season com total maior que 22.
4. Paginar até o total e confirmar que o número de `ep_id` únicos bate com o
   total declarado.
5. Se a season escolhida não existir, registrar o que foi encontrado. A season 215
   é a única medida real, e ela é de uma página.

```bash
cd /root/kythourcl-dist
python3 tools/probe_season_pagination.py --season 215
git commit -m "test(Tomato): exercita paginacao de season com mais de uma pagina"
```

---

## Tarefa 4 — Decisão do token e do histórico Git

**Objetivo:** Fechar a exposição do token e do identificador de usuário.

**Contexto medido:** o token é obrigatório (200 com, 403 sem), então ele não pode
ser removido sem quebrar o plugin. Ele está no fonte e no artefato publicado desde
a v150. O identificador de usuário e o UUID estão em dois commits da branch
`builds`, o `a90be82` e o `9adac73`, ambos alcançáveis no histórico público.

**Passos:**

1. Documentar, no comentário do `BEARER_TOKEN`, que o token é obrigatório e que
   a remoção exige rotação server-side do lado da origem.
2. Registrar em `DOCUMENTACAO_MESTRE.md` a exposição conhecida e a saída real: rotação
   na origem, que depende da conta do usuário.
3. Decidir com o usuário se reescreve o histórico para eliminar o identificador,
   o que exige force-push e afeta clones existentes.
4. Nenhuma remoção de código de autenticação sem rotação server-side. Sem
   rotação, o plugin não funciona.

```bash
cd /root/kythourcl-dist
git commit -m "docs(Tomato): registra exposicao do token e do identificador"
```

---

## Tarefa 5 — Documentar o diagnóstico na doc de RE

**Objetivo:** Deixar registrado o achado que invalida a leitura anterior, para que
a próxima sessão não repita o erro de concluir "origem morta" a partir de 500.

**Arquivos:**
- Modificar: `DOCUMENTACAO_MESTRE.md`

**Contexto medido:** o 500 sem WARP é bloqueio do WAF por IP. Via WARP a mesma
rota devolve 200. Toda conclusão anterior de "origem morta" baseada em 500 sem
proxy foi um falso positivo.

**Passos:**

1. Adicionar seção de diagnóstico datada de 2026-09-27.
2. Registrar a prova de que o 500 sem WARP é bloqueio por IP, com o par 500 sem
   proxy e 200 via WARP.
3. Registrar que o token é obrigatório, com o par 200 com e 403 sem.
4. Registrar que a season 215 tem 22 episódios em uma página.
5. Commitar.

```bash
cd /root/kythourcl-dist
git commit -m "docs(Tomato): registra WAF por IP como causa do 500 sem proxy"
```

---

## Critérios de aceitação

- [ ] `isOriginDead()` não corta mais o retry por uma heurística que se provou
      errada; o log distingue bloqueio de WAF de origem morta.
- [ ] Taxa de sucesso do stream medida e registrada com data e janela.
- [ ] Hash local e hash servido do `Tomato.cs3` coincidem, versão 163.
- [ ] Paginação de season com mais de uma página validada ao vivo, ou a
      impossibilidade registrada.
- [ ] Exposição do token e do identificador documentada, com a saída real
      definida.
- [ ] Diagnóstico do WAF documentado na doc de RE.

## O que este plano não faz

- Não remove o token Bearer. Ele é obrigatório, comprovado em 2026-09-27.
- Não tenta consertar o 500 sem WARP. É bloqueio do IP do servidor de teste.
- Não declara o plugin funcional end-to-end sem um `/stream` com 200 medido.
- Não reescreve o histórico Git sem decisão explícita do usuário.
