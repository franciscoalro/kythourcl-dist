# Plano Técnico de Testes ao Vivo e Resolução de Problemas — RedeCanaisAF

Este documento foi estruturado com propósito educacional e prático para orientar o desenvolvedor nos **testes reais em tempo de execução (runtime)** do plugin **`RedeCanaisAF`** no CloudStream Android (via emulador Redroid ou dispositivo físico).

---

## 🏗️ 1. Arquitetura Interna do Plugin

Antes de iniciar os testes, é essencial compreender como cada camada do plugin opera:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        CloudStream 3 Core                              │
└───────┬────────────────────────────┬────────────────────────────┬──────┘
        │ (1) Requisições HTTP       │ (2) Parse HTML             │ (3) Playback
        ▼                            ▼                            ▼
┌──────────────────┐         ┌──────────────────┐         ┌────────────────────────┐
│ CloudflareSolver │         │  RedeCanaisAF    │         │     StreamResolver     │
│ - Mutex WebView  │ ◄────── │ - getMainPage()  │ ──────► │ - Parser de iframes    │
│ - cf_clearance   │         │ - search()       │         │ - Fallback de servidores│
│ - SharedPreferences│       │ - load()         │         └───────────┬────────────┘
└──────────────────┘         └────────┬─────────┘                     │
                                      │                               ▼
                                      ▼                   ┌────────────────────────┐
                             ┌──────────────────┐         │   WebViewStreamProxy   │
                             │ RedeCanaisAFText │         │ - Intercepta __RC__    │
                             │ - Normalização   │         │ - Servidor HTTP Local  │
                             │ - Regex Episódios│         │   (127.0.0.1:8080)     │
                             └──────────────────┘         └───────────┬────────────┘
                                                                      │ (4) Stream
                                                                      ▼
                                                          ┌────────────────────────┐
                                                          │   ExoPlayer / Video    │
                                                          └────────────────────────┘
```

---

## ⚙️ 2. Preparação do Ambiente de Teste

### 2.1. Comandos Essenciais

| Ação | Comando |
| :--- | :--- |
| **Compilar Plugin** | `./gradlew :RedeCanaisAF:make` |
| **Compilar Todos os Plugins** | `./gradlew makePlugins` |
| **Executar Testes Unitários** | `./gradlew :RedeCanaisAF:testDebugUnitTest` |
| **Verificar Plugin no Emulador** | `python tools/verify_plugin.py RedeCanaisAF` |
| **Abrir o CloudStream no Redroid** | `docker exec redroid am start -n com.lagradost.cloudstream3.prerelease/com.lagradost.cloudstream3.ui.account.AccountSelectActivity` |
| **Acompanhar Logs em Tempo Real** | `adb -s emulator-5554 logcat -s RedeCanaisAF-Trace:V CloudStream:V ExoPlayer:V` |
| **Capturar Screenshot do Emulador** | `adb -s emulator-5554 exec-out screencap -p > /tmp/screen.png` |

---

## 📋 3. Matriz de Tasks Técnicas (Testes ao Vivo)

---

### 🔹 TASK 1: Validação do Bypass de Cloudflare (Cold Start e Expiração de Sessão)

**Objetivo:** Verificar se o `CloudflareSolver` obtém e injeta com sucesso os cookies `cf_clearance` e `__cf_bm`, e se a persistência de 12 horas evita travamentos na navegação.

#### Roteiro de Teste:
1. Limpe os dados do aplicativo ou force a expiração dos cookies no app.
2. Inicie o monitoramento do Logcat:
   ```bash
   adb -s emulator-5554 logcat -c && adb -s emulator-5554 logcat -s RedeCanaisAF-Trace:V
   ```
3. Abra a aba inicial do provider no CloudStream.
4. **Verificação de Logs:**
   - Deve exibir: `[REQ#1] Fetching url=https://redecanais.af/`
   - Se disparar o solver: `[SOLVER] Iniciando resolução Cloudflare...`
   - Ao resolver: `[SOLVER] Cloudflare superado com sucesso!`
   - Sincronização: `[COOKIE] clearance=true | __cf_bm=true`

#### O que pode falhar e como corrigir:
* **Problema:** Cloudflare apresenta Turnstile interativo que exige clique manual no checkbox.
* **Causa:** O WebView em segundo plano pode não renderizar se não houver contexto de UI anexado ou se as dimensões forem 0x0.
* **Solução Técnica:** O `CloudflareSolver.kt` já utiliza um `Dialog` com tamanho visível e touch injection automatizado via script JS (`turnstile-hack`). Se o Turnstile mudar de classe CSS ou iframe, atualize os seletores no `CloudflareSolver.kt`.

---

### 🔹 TASK 2: Validação de Catálogo e Paginação

**Objetivo:** Garantir que todas as seções da Home e suas respectivas paginações carreguem sem links quebrados ou itens duplicados.

#### Roteiro de Teste:
1. Abra a página inicial do plugin.
2. Navegue pelas seguintes categorias:
   - **Lançamentos** (`/browse-filmes-lançamentos-videos-1-date.html`)
   - **Filmes** (`/browse-filmes-videos-1-date.html`)
   - **Séries** (`/browse-series-videos-1-date.html`)
   - **Animes** (`/browse-animes-videos-1-date.html`)
   - **Desenhos** (`/browse-desenhos-videos-1-date.html`)
   - **Doramas** (`/browse-doramas-videos-1-date.html`)
3. Role até o final de cada seção para acionar o carregamento da **Página 2 e Página 3** (Infinite Scroll / Paginação).

#### O que pode falhar e como corrigir:
* **Problema:** Ao rolar, a página 2 não carrega ou repete a página 1.
* **Causa:** O padrão de URL de paginação pode usar hífen ou underscore diferente para certas categorias (ex: `browse-filmes-videos-2-date.html` vs `browse-series-2.html`).
* **Solução Técnica:** Inspecione a função `getMainPage()` em `RedeCanaisAF.kt` e confira a substituição de `page` no regex de URL de cada categoria.

---

### 🔹 TASK 3: Validação da Busca com Acentos e Caracteres Especiais

**Objetivo:** Garantir que buscas com palavras acentuadas em português, números ou pontuações retornem os resultados corretos sem erros de codificação (mojibake).

#### Roteiro de Teste:
1. No campo de busca do CloudStream, filtre pelo provedor RedeCanais e teste as seguintes queries:
   - **Acentuação:** `Coração`, `Ação`, `Pokémon`, `Dragão`
   - **Pontuação e Números:** `007`, `Homem-Aranha`, `Vingadores: Ultimato`
   - **Séries/Animes:** `Attack on Titan`, `Game of Thrones`, `One Piece`
2. Verifique se os resultados retornados exibem:
   - Capa nítida (sem placeholder cinza).
   - Título limpo (sem "Dublado", "Legendado", "720p", "1080p" poluindo o nome).
   - Tipo de mídia correto (Filme vs Série/Anime).

#### O que pode falhar e como corrigir:
* **Problema:** A busca não retorna nada para "Coração" mas retorna para "Coracao".
* **Causa:** `URLEncoder.encode(query, "UTF-8")` pode enviar `%C3%A7` enquanto o backend legada do site espera `ISO-8859-1` ou query sanitizada.
* **Solução Técnica:** O `RedeCanaisAFText.normalizePortugueseTitle()` e a função `search()` em `RedeCanaisAF.kt` sanitizam a query. Se necessário, envie a busca tanto com termo normalizado quanto com termo bruto.

---

### 🔹 TASK 4: Validação de Detalhes, Séries Multi-Temporadas e Animes Longos

**Objetivo:** Verificar se séries com dezenas de episódios e múltiplas temporadas são carregadas completamente sem omitir episódios.

#### Roteiro de Teste:
1. Selecione uma **Série Curta (1 Temporada)** (ex: Minissérie).
   - Verifique se todos os episódios aparecem na ordem correta (1, 2, 3...).
2. Selecione uma **Série Longa (Múltiplas Temporadas)** (ex: *The Walking Dead*, *Supernatural*, *Grey's Anatomy*).
   - Verifique se o seletor de Temporadas (Temporada 1, 2, 3...) é populado.
   - Verifique se os episódios de cada temporada correspondem à temporada selecionada.
3. Selecione um **Anime Longo** (ex: *Naruto Shippuden* ou *One Piece*).
   - Verifique se a listagem lida com paginação interna de episódios (ex: episódios 1-50, 51-100).

#### O que pode falhar e como corrigir:
* **Problema:** Apenas a Temporada 1 é exibida, ou todos os episódios caem na mesma temporada.
* **Causa:** O site do RedeCanais alterna entre tags `<select id="temporadas">`, tabelas `<table>` ou abas `<div class="tab-pane">` dependendo de quando a postagem foi criada.
* **Solução Técnica:** Verifique a função `extractEpisodesFromDocument()` em `RedeCanaisAF.kt`. Ela possui analisadores para `select`, links paginados e tabelas. Adicione um novo parser Jsoup caso surja uma estrutura HTML diferente.

---

### 🔹 TASK 5: Teste ao Vivo de Reprodução (ExoPlayer + WebViewStreamProxy)

**Objetivo:** Confirmar que o stream de vídeo é entregue ao ExoPlayer sem erros `520`, `403` ou reprodução travada.

#### Roteiro de Teste:
1. Escolha um filme recente e pressione **Play**.
2. Acompanhe os logs no terminal:
   ```bash
   adb -s emulator-5554 logcat -s RedeCanaisAF-Trace:V ExoPlayer:V
   ```
3. **Sequência esperada nos logs:**
   ```
   [LOADLINKS_START] data=https://redecanais.af/...
   [PROXY] Iniciando captura WebView para URL: ...
   [PROXY] Interceptada requisição __RC__/proxy: ...
   [PROXY] Servidor local iniciado em http://127.0.0.1:XXXXX
   [PROXY_LINK] emitindo proxy local: http://127.0.0.1:XXXXX/stream.mp4
   ExoPlayer: Response code: 200 / 206
   ```
4. O vídeo deve iniciar em menos de 5 segundos.
5. Avance a barra de reprodução (Seek) para o meio do vídeo para validar o suporte a `HTTP 206 Partial Content`.

#### O que pode falhar e como corrigir:
* **Problema:** ExoPlayer reporta `BehindLiveWindowException` ou `HttpDataSource$InvalidResponseCodeException: 520`.
* **Causa:** O link direto do RedeCanais (`p12-common-sign`) possui assinatura TLS ligada ao processo do WebView.
* **Solução Técnica:** O `WebViewStreamProxy.kt` funciona como proxy reverso local: ele repassa os bytes utilizando o mesmo contexto e cookies da sessão autenticada. Certifique-se de que a porta local (`127.0.0.1`) permaneça aberta durante o playback.

---

### 🔹 TASK 6: Teste de Servidores Alternativos e Fallback de Servidores Mortos

**Objetivo:** Validar o mecanismo de tolerância a falhas quando um servidor de vídeo do RedeCanais estiver fora do ar.

#### Roteiro de Teste:
1. Abra um título mais antigo (onde alguns servidores `RCServerXX` originais podem estar offline com `NXDOMAIN`).
2. Tente reproduzir.
3. Observe no log se o fallback automático é acionado:
   ```
   [PROXY_LINK] servidor original falhou — tentando RCFServer2/ondemand: ...
   ```
4. Verifique se o vídeo entra em reprodução mesmo com o servidor primário indisponível.

---

### 🔹 TASK 7: Diagnóstico da Função de Download (Offline Mode)

**Objetivo:** Entender e validar as limitações e comportamento ao tentar baixar mídias pelo CloudStream.

#### Roteiro de Teste:
1. Clique no botão de **Download** em um episódio ou filme.
2. Monitore o progresso na aba de Downloads do CloudStream.

#### Comportamento Esperado e Diagnóstico Técnico:
* **Se o download for via ExtractorLink direto (mp4/m3u8 externo):** O downloader do CloudStream baixa normalmente.
* **Se o download for via `WebViewStreamProxy` (127.0.0.1):** O download funcionará enquanto o app estiver em primeiro plano. Se o sistema operacional Android matar o serviço de background do WebView para poupar memória RAM, a porta 127.0.0.1 fecha e o download pausa.
* **Melhoria recomendada:** Injetar os headers `User-Agent` e `Cookie` no objeto `ExtractorLink` para tentar conexão direta quando o CDN permitir download sem binding estrito de TLS.

---

## 🛠️ 4. Fluxo de Depuração Passo a Passo para Novos Problemas

Caso um link ou página pare de funcionar no futuro:

```bash
# 1. Obter o HTML bruto como o plugin o enxerga
adb -s emulator-5554 logcat -d -s RedeCanaisAF-Trace:V | grep "HTML_DUMP"

# 2. Testar o parser de texto com a suíte de testes unitários
./gradlew :RedeCanaisAF:testDebugUnitTest --info

# 3. Inspecionar o estado dos cookies
adb -s emulator-5554 shell "run-as com.lagradost.cloudstream3.prerelease ls -la /data/data/com.lagradost.cloudstream3.prerelease/app_webview/"

# 4. Reconstruir e atualizar o repositório local de plugins
./gradlew makePlugins
```

---

## 🎓 Conclusão do Aprendizado

Com esta matriz de tarefas, você é capaz de:
1. Identificar exatamente em qual camada uma falha ocorre (Rede/Cloudflare, Scraping/HTML, ou Playback/Proxy).
2. Reproduzir cenários de teste reais no emulador Redroid.
3. Alterar e recompilar apenas os módulos isolados (`CloudflareSolver`, `RedeCanaisAFText`, `StreamResolver`, `WebViewStreamProxy`) com segurança e rapidez.
