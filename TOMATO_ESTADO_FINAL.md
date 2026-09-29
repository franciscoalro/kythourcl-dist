# TomatoAnimes — estado final do projeto

**Data de encerramento desta etapa:** 29/09/2026  
**Versão publicada:** `171`  
**Estado:** funcional e em manutenção; desenvolvimento ativo encerrado temporariamente.

## 1. Decisão de encerramento

O ciclo de desenvolvimento do plugin TomatoAnimes está encerrado por enquanto.
Na versão 171, o plugin cumpre a proposta definida para esta etapa: disponibilizar
no CloudStream navegação de catálogo, pesquisa, detalhes, temporadas, episódios e
reprodução das faixas fornecidas pela origem.

Isto não significa abandono definitivo. O projeto entra em **modo de manutenção**:
novas mudanças só são necessárias em caso de regressão, alteração da API oficial,
expiração/rotação da autenticação ou incompatibilidade futura com o CloudStream.

## 2. Funcionalidades entregues e validadas

- catálogo e pesquisa de animes;
- tela de detalhes;
- listagem de temporadas e episódios;
- identificação de faixas legendadas e dubladas;
- reprodução HLS;
- categorias da Home:
  - Novos episódios;
  - Em alta;
  - Recém adicionados;
  - Semanais;
  - Com dublagem;
  - Recomendados;
  - Os mais curtidos de hoje;
  - Aventura;
  - Comédia;
  - Romance;
  - Slice Of Life;
  - Talvez você goste;
- alternância automática entre `prod-api.tomatoanimes.com` e
  `edge.betomato.com`;
- fallback embutido para a Home quando o feed remoto não responde;
- cache do feed em memória por cinco minutos;
- coalescimento das solicitações simultâneas da Home com `Mutex`, evitando que
  cada categoria repita a mesma consulta e o mesmo parsing JSON.

## 3. Arquitetura e independência de infraestrutura

O plugin é executado localmente pelo CloudStream no aparelho Android. Ele se
comunica diretamente com a API e com os endereços de mídia fornecidos pela origem.

O funcionamento normal **não depende de VPS, Redroid, WARP, Tor, Xray, proxy ou
servidor intermediário mantido pelo projeto**. A VPS e o Redroid foram usados
somente como ambiente de desenvolvimento, medição, compilação e teste.

O GitHub hospeda apenas o repositório e o artefato `.cs3` usado para instalação e
atualização. Após instalado, o código roda no cliente CloudStream.

## 4. Limitações externas conhecidas

A API oficial é infraestrutura de terceiros e pode:

- oscilar entre respostas válidas e HTTP 5xx;
- bloquear determinados IPs no Cloudflare;
- alterar rotas ou formatos de resposta;
- exigir renovação do Bearer utilizado pelo aplicativo oficial;
- mudar ou remover URLs de mídia.

Esses eventos não são controláveis pelo plugin. As medições que distinguem falha
de origem de bloqueio por IP estão registradas em
[`MEDICOES_TOMATO_2026-09-28.md`](MEDICOES_TOMATO_2026-09-28.md).

O Bearer continua obrigatório: nas medições, a mesma rota respondeu com sucesso
quando autenticada e devolveu `403 authentication failed` sem autenticação. Uma
rotação feita pela origem exigirá atualização do plugin.

## 5. Otimização final da Home — v171

O CloudStream solicita cada categoria da Home separadamente. Como todas as
categorias do Tomato derivam do mesmo endpoint `/v2/animes/feed`, versões
anteriores podiam repetir todo o fluxo de rede, retry e parsing para cada linha.
Em uma abertura da Home, isso podia gerar até 12 fluxos equivalentes.

A versão 171 introduziu:

1. cache em memória do feed por cinco minutos;
2. `Mutex` com verificação dupla para evitar cache stampede;
3. reutilização de uma única árvore JSON entre as categorias;
4. reutilização do `ObjectMapper`;
5. failover do próprio feed entre os dois hosts oficiais.

Pesquisa, detalhes, temporadas e reprodução não usam esse cache do feed.

## 6. Evidências de validação e publicação

- build local concluído com sucesso;
- artefato instalado e carregado no CloudStream prerelease no Redroid;
- categorias da Home confirmadas por inspeção da interface;
- ausência de `NoClassDefFoundError`, `VerifyError` e exceção fatal durante a
  validação;
- workflow público de build concluído com sucesso;
- artefato público comparado byte a byte com o build testado.

Artefato publicado da v171:

- tamanho: `74543` bytes;
- SHA-256: `97c7d3d0a6ac8df41e9c4d06db3663190f11e9a583924ad15e35021bd3ae3cd4`.

Commits de referência:

- código e documentação da otimização:
  [`c8082b8`](https://github.com/franciscoalro/kythourcl-dist/commit/c8082b8d62301dd1691d3b49953ece1fba77c056);
- artefatos publicados pelo CI:
  [`6cc5db6`](https://github.com/franciscoalro/kythourcl-dist/commit/6cc5db6e9eb95d2b84dd01e9c407a2e3bf873366).

## 7. Política de manutenção

Antes de alterar o plugin, confirmar se o problema é reproduzível fora da VPS de
teste. Um HTTP 500 observado apenas no IP do servidor não prova que a API está
indisponível para usuários residenciais.

Uma nova versão deve ser aberta somente quando ocorrer pelo menos uma destas
condições:

- recurso atualmente validado deixar de funcionar para usuários;
- contrato da API mudar;
- autenticação expirar ou for rotacionada;
- CloudStream introduzir incompatibilidade relevante;
- surgir correção de segurança necessária;
- existir melhoria objetiva e mensurável sem regressão funcional.

Para qualquer nova publicação:

1. incrementar a versão;
2. compilar o `.cs3`;
3. testar instalação e carregamento no CloudStream;
4. validar Home, pesquisa, detalhes e ao menos um fluxo de reprodução;
5. conferir `fileHash`, `fileSize` e versão em `builds/plugins.json`;
6. confirmar que o artefato público é idêntico ao testado.

## 8. Conclusão

A versão 171 é a versão funcional de encerramento desta etapa. Não há tarefa de
desenvolvimento pendente conhecida dentro do escopo proposto. O código permanece
disponível para manutenção corretiva caso a origem ou o CloudStream mudem no
futuro.
