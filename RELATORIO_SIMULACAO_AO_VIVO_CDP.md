# 🔴 Simulação ao Vivo — Browser Harness + CDP Avançado (RedeCanaisAF)

**Data:** 2026-09-11 · **Ferramentas:** Chrome 152 real + Playwright CDP (`Network/DOM/Runtime.enable`),
Chrome paralelo em `remote-debugging-port=19222`, fase headed via Xvfb (`:99`) com clique de mouse real.
**Scripts (reprodutíveis):** `tools/live_cdp_sim.py` (fase 1), `tools/live_cdp_sim2.py` (fase 2 headed+clique),
`tools/live_repro_video.py` (passo 2: ponta-a-ponta do vídeo), `tools/live_cdp_webview_dump.py` (CDP no WebView do app),
`tools/step1_unlock_session.py` (passo 1: desbloqueio com perfil persistente).

## 0. Veredito da rodada 2026-09-11 (passo 1 → 2 → 3 → 4)
- **Passo 1 (sessão válida nova): BLOQUEADO com evidência.** 12+ polls headed + 3 cliques CDP
  `Input.dispatchMouseEvent` calibrados + re-poll — `chall=True, cards=0, cookies=[]` o tempo todo.
  O app no Redroid também falha junto (`[HOME_RAW_CARDS] found=0`, `SEARCH raw=0` agora às 00:38).
  Causa raiz: managed challenge exige sinais que este host/IP não emite; nonces `serverforms`
  são por-sessão, então replay de URL velha dá `520` (`__RC__`) / `403` (xn direto) — provado com curl.
- **Passo 2 (1 byte de vídeo fresco): BLOQUEADO pelo passo 1** — sem sessão não há `serverforms` novo.
  O caminho feliz continua provado pelo dump v250 (`__RC__` **206** + xn **206**).
- **Passo 3 (buracos do mapa): FECHADO sem rede nova** — `/tmp/rc-search-p4.html` (338 KB) contém o
  algoritmo de busca verbatim (`normalize` + `final_mapa*.txt` + `.listagem`); `/tmp/rc-home-real.html`
  trava o grid; `/tmp/dump_serverphp.html` trava o shell ofuscado. Novo teste
  `TestRedeCanaisAFLiveStructure` (7 casos) trava tudo offline.
- **Passo 4 (código): regressão real encontrada e corrigida** — o working tree havia removido o marcador
  `challenge-platform` de `isChallengeContent()`, quebrando `TestRedeCanaisAFV139`. Restaurado;
  suite **28/28 verde** + `:RedeCanaisAF:make` OK.

> Dumps denso-em-rede (cookies/tokens cf_clearance, RCSESS) ficam **locais e com modo restrito**
> (`/tmp/rc-live-sim/`, `/tmp/rc-live-sim2/`, `/tmp/rc-*.json` históricos). Este relatório publica
> só estrutura, contagens e URLs funcionais — nenhum valor de sessão.

---

## 1. Resultado executivo (o que o AO VIVO provou hoje)

| Alvo | Navegação direta (lab) | Rede observada | DOM |
|---|---|---|---|
| `GET /` | **403** (`cf-mitigated: challenge`, `Just a moment…`/`Um momento…`) | challenge-platform `orchestrate/chl_page` 200 → blob workers → `turnstile/api.js` → `POST …/fo/…` 200 → `pat/…` **401** + `hagen…/i/…` **204** | 0 cards, 0 iframes, `turnstile=True` |
| `/browse-filmes-videos-1-date.html` | **403** idem | mesma cadeia; `NET-KEY serverforms/redirect/__RC__/final_mapa = 0` | `challenge=true`, 0/0/0 |
| `/search.php?keywords=Batman` | **403** idem | idem | idem |
| `/a-sombra-do-batman-…_a8b6b81b2.html` | **403** idem | idem | probe clique: `btn=null, video=null, rcFn=undefined, forms=0` |
| Espelhos `www3.redecanais.vip`, `www16.redecanais.in` | **403** challenge | — | — |
| `redecanais.zip` | timeout (000) | — | — |
| `redecanais.fm` | 301 → bounce Google URL | — | — |

**Conclusão fase 1:** hoje o WAF exige Turnstile interativo em **todas** as rotas documentais no IP do lab.
Navegação sem sessão válida **não** entrega catálogo — o que valida o desenho do plugin
(`requestDoc` → `cleanClient` → fallback `CloudflareSolver.solve()` via WebView).

**Fase 2 (headed Xvfb + clique real no widget):** 12 polls × 5 s permaneceram em
`title=Um momento…, turnstile=True, cards=0, len≈28 KB`, sem auto-resolução e sem checkbox
clicável no DOM principal (o widget vive em iframe cross-origin `challenges.cloudflare.com`,
logo o seletor `iframe[src*=challenges…]` do documento pai não o expõe — exatamente o motivo do
`TURNSTILE_TAP_PROBE_JS` do plugin usar `getBoundingClientRect` + toque Android em vez de DOM).

---

## 2. Dump da cadeia do challenge (novo, desta sessão)

```
GET /browse-…html → 403 (cf-mitigated: challenge)
GET …/challenge-platform/h/g/orchestrate/chl_page/v1?ray=… → 200 (application/javascript)
GET blob:https://redecanais.af/<uuid> → 200 (worker do challenge)
GET …/turnstile/v0/g/…/api.js → 200
POST …/challenge-platform/h/g/fo/… (xhr) → 200 text/plain
GET …/turnstile/f/av0/rch/…/light/…/normal?lang=auto → 200 (iframe do widget)
GET hagen.challenges…/h/g/i/… (fetch) → 204
GET …/challenge-platform/h/g/pat/… (fetch) → 401 text/plain   ← atestado: sem token válido não avança
GET …/challenge-platform/h/g/ci/… → 200 image/png (imagem do widget)
```

`struct.json` de cada alvo: `scripts=[orchestrate/chl_page, turnstile/api.js]`, `cookies=""`,
`counts={pmGrid:0,…}` — fingerprint exato do challenge para `isChallengeContent()`.

---

## 3. Estrutura REAL do site (documentos históricos com sessão válida)

### 3.1 Catálogo `/browse-*-1-date.html` — `/tmp/rc-home-real.html` (139 KB, sessão válida)
- `<ul class="row pm-ul-browse-videos list-unstyled" id="pm-grid">` + `<li class="col-xs-6 col-sm-4 col-md-3">`
- Card: `.thumbnail > .pm-video-thumb > a[href=/<slug>_<9-10hex>.html][title] > img[src=/templates/echo/img/echo-lzld.png][data-echo=/imgs-videos/Filmes/<…>.jpg]` + `.caption > h3 > a.ellipsis`
- Título sujo real: `A Morte do Demônio: Em Chamas (Dublado) - 2026 - 1080p` → o `cleanMediaTitle()` do plugin remove exatamente esses sufixos. ✅
- Paginação real: `.pagination.pagination-sm.pagination-arrows` com links `/browse-filmes-videos-{2,3,4,5}.html` + `…-2398/2399-date.html` → o `getMainPage()` (regex `-\d+-(date|views|rating|title).html`) casa. ✅
- ✅ Cruzamento com `parseCard()` (`RedeCanaisAF.kt:542`): seletores `#pm-grid > li, li.col-xs-6, .pm-video-thumb, h3 a, img[data-echo]` **batem 1:1** com o DOM real.
- ⚠️ Observado: `img[src]` é **sempre placeholder** (`echo-lzld.png`); a URL real vive em `data-echo` — o plugin já lê `data-echo` primeiro. ✅
- Detalhe do slug: `_<hash>.html` tem **9–10 hex** (`_a707773a0`, `_576916138`) — o `buildServerVariants` já casa `_([0-9a-f]{9}).html` para extrair o id curto. ✅ (nota: `_576916138` tem 9 chars mistos — regex atual cobre.)
- `redirect.api?p=aHR0cHM6Ly9yZWRlY2FuYWlzdHYuYWY=` no menu → decodifica para `https://redecanaistv.af` (link "Canais De TV"). É o **único** `redirect.api` no catálogo — não confundir com o `redirect.api?p=<base64>` do fluxo do player.

### 3.2 Busca `search.php?keywords=` — `/tmp/rc-search-p0.html` (950 KB **truncado** no meio do `<script>`)
- `<title>Buscar Conteúdo RedeCanais: A Captura</title>` — prova renderização com sessão válida.
- **Sem** `#search-input`, `.listagem`, `#pm-grid` no HTML bruto: o índice vive **dentro do JS ofuscado** (`var DXvD=["alNjNzc4…",…]` — 41 275 tokens base64 que decodificam para ids tipo `jSc77889771xsW`).
- ✅ Isso confirma o desenho do plugin: `isPendingSearchContent()` (aguardar `final_mapa*.txt` + `#search-input.value` + `data-cs-search-ready`) e o `CAPTURE_READY_HTML_JS` — captura crua do HTML **antes** do `Promise.all` dá shell vazio. O dump truncado (sem `</html>`/`<body>`) ainda prova que "HTML grande ≠ HTML pronto".
- ⚠️ Divergência de dossiê: `DOSSIE_REDECANAIS.md §3` documenta rota `/search/{slug}/`, mas o plugin usa `search.php?keywords=` e o dump real confirma `search.php` — o dossiê §3 está **desatualizado** nesse ponto.

### 3.3 Player `server.php` — `/tmp/dump_serverphp.html` (60 KB) + dumps CDP de rede
- HTML estático = shell ofuscado: `<title>Player</title>` + `<script src="./bundle.js">` + **1 script inline ofuscado (VM)**; **zero** `<form>`, `<iframe>`, `<video>`, `captcha_button`, `serverforms`, `__RC__` no estático → todo o DOM nasce via `bundle.js`. Por isso o plugin resolve o player via **WebView com clique/automação** (`WebViewStreamProxy.captureAndServe`), não via parse Jsoup. ✅
- Cadeia de rede real (CDP histórico, HTTP 200 em tudo):
  ```
  server.php?categoria=vod&server=RCFServer2&subfolder=ondemand&vid=ASMBRDBTMNEP01 → 200 Document
  bundle.js → rc-player/player/videofix-inlineoff.js, videojs.thumbnails.api?server=…, style-recp.css,
              ima3.js, jquery.js, chromecast, doubleclick ima_ppub_config → 200
  serverforms.api?a6e91c4f=1&71b8d2=<SERVER>&e5c39f=ondemand&0a6d84=<VID>&7e3a91=<nonce1> → 200 application/json (init)
  serverforms.api?3c91e7a4=<nonce2> → 200 application/json (resolve; hoje: {"2fa806d3":204,"e18b73c9":[]} = origem vazia)
  jquery.videojs.4.5.2.api?rctoken=… → 200 image/png (token visual do player)
  RedeCanaisVTTS/vtts/<SERVER>/<VID>.jpg + .vtt (thumbs/legendas)
  __RC__/proxy?src=https://xn--l---…null-null.shop/tos-alisg-avt-0068/proxy?container=videos&refresh=…&url=https://neosoro.gq/V/<SERVER>/ondemand/<VID>.mp4?sv=… → 206 application/octet-stream ✅ (MP4 real)
  direto xn--l---…/proxy?…url=https://neosoro.gq/…mp4 → 204 (preflight) + 206 (bytes)
  ```
- ✅ Cruzamento com `StreamResolver.kt`: padrões `serverforms.api`, `redirect.api?p=`, `__RC__/proxy?src=`, `tos-alisg`, `xn--l`, `container=videos`, `neosoro.gq`, `/ondemand/` **existem verbatim** em `isValidStreamUrl()`/`extractDirectStreamsFromHtml()` — o whitelist nasceu desses dumps.
- ✅ `LIVE_TEST_RESULTS.md` já documenta o estado atual: init/resolve respondem 200 mas com `204 + e18b73c9=[]` (array vazio) para `ASMBRDBTMNEP01`/`BTMNVSPRMNAODJT` em `RCFServer2`/`RCServer11` — origem morta no backend, não bug de clique (clique `tap:320:180` + `serverforms.api` 200 provados).
- ✅ Cadeia v250 prova o caminho feliz ainda vivo noutro conteúdo: `RCFServer3/CAPTAMRC3LEG` → `__RC__/proxy` **206** + `xn--…` **206** (bytes reais). O fluxo do plugin (recap → serverforms → proxy local 127.0.0.1 → ExoPlayer) espelha exatamente essa cadeia.
- Cookies: `ExtraInfo` do worker confirma `cf_clearance` (`.redecanais.af`, `SameSite=None`, `partitionKey=https://redecanais.af`), `RCIP`, `RCSESS` nas 8 chamadas `serverforms.api`; o `xn--…` final vai **sem cookies** (microssessão por URL assinada `sv=…&nu3zAQ…`). ✅ O `restoreClearanceIfValid()` + `stealthHeaders()` do plugin miram exatamente esses nomes/escopos.

## 4. Teste de clique/automação (o que foi exercido)

| Etapa | Método de hoje | Resultado |
|---|---|---|
| Widget Turnstile | mouse real headed (Xvfb) no rect do iframe | widget em iframe cross-origin — sem rect acessível no doc pai; `clicked=False`, sem regressão de sessão |
| Recap do player (`.captcha_button`/`#submit`) | probe CDP em detalhe com challenge | `btn=null` (challenge bloqueia antes do player) — esperado; com sessão válida o plugin usa `tap:320:180` + `video.play()`/`playing` (v250/v251) |
| `serverforms.api` init+resolve | replay passivo de dumps CDP | URLs/nonces são por-sessão (`7e3a91=…`, `3c91e7a4=p…` rotativos) — **nunca** hardcodar; o plugin deriva via bundle em runtime ✅ |
| `__RC__/proxy → MP4` | dump v250 | 206 real; confirma `MEDIA_PROBE_BYPASS`/proxy-local como único caminho (OkHttp direto = 520 TLS-bound) ✅ |

## 5. Divergências dossiê × realidade (para interpretar o plugin)

1. `DOSSIE_REDECANAIS.md §1`: `www3.redecanais.vip` como "espelho operacional 200" — **hoje 403 challenge** no lab. Tratar como contingente, não primário.
2. `§3`: rota `/search/{slug}/` — dumps + plugin provam `search.php?keywords=` + índice JS `final_mapa*.txt`. Atualizar dossiê.
3. `§5`: "7 servidores / DooPlayer REST `/wp-json/dooplayer/v1/`" — **nenhum** traço de `dooplayer`, `wp-json`, `superflixapi`, `megaembed`, `playerflix`, `fembed`, `embedplayer`, `embedplay`, `vsembed` em **nenhum** dump (home/player/rede). Arquitetura real observada: **PHP Melody (Echo template) + player próprio `player3/` (bundle.js → serverforms.api → __RC__/proxy → neosoro/xn--)**. O plugin já segue a realidade; o dossiê §5 precisa de revisão.
4. `TASKS_TESTES_AO_VIVO_REDECANAIS.md` Tasks 1–7 continuam válidas como roteiro; Task 5 (sequência `[PROXY] → 127.0.0.1 → ExoPlayer 200/206`) é o caminho feliz provado no v250.

## 6. Como reproduzir

```bash
# Fase 1 — todos os alvos, headless, dump rede+DOM+shots:
python3 tools/live_cdp_sim.py --dump-all-targets
# Fase 2 — headed com clique real no Turnstile (precisa Xvfb):
Xvfb :99 -screen 0 1280x900x24 & 
DISPLAY=:99 python3 tools/live_cdp_sim2.py --url "https://redecanais.af/browse-filmes-videos-1-date.html" --timeout 60
# Re-análise dos dumps históricos (sem rede):
python3 -c "import json; d=json.load(open('/tmp/rc-player-all.json')); …"
```

## 7. Arquivos desta simulação

- `tools/live_cdp_sim.py`, `tools/live_cdp_sim2.py` (novos, commitados nesta sessão)
- Dumps: `/tmp/rc-live-sim/*.{struct,net,html,png,click-probe}.json`, `/tmp/rc-live-sim2/*`, `/tmp/rc-live-sim-run.log`, `/tmp/rc-live-sim2-run1.log`
- Evidências reutilizadas: `/tmp/rc-home-real.html` (extraído de `/tmp/rc-cache.json`), `/tmp/dump_serverphp.html`, `/tmp/rc-player-all.json` (230 ev), `/tmp/rc-popular-all.json` (1814 ev), `/tmp/rc-frame-v250-all.json` (proxy→206), `/tmp/rc-browser-unlocked-player-all.json`, `/tmp/rc-search-p0.html`

## 8. P1 — Patchright + TAB+Espaço + egresso alternativo (2026-09-11, SEM espelhos)

**Restrição respeitada:** só `https://redecanais.af` (+ `challenges.cloudflare.com` do widget).
Nenhum espelho foi tocado em nenhum comando desta seção.

### 8.1 Diagnóstico de egresso (a variável dominante, confirmada)
- Egresso lab: `167.233.60.72` — `static.72.60.233.167.clients.your-server.de`, **AS24940 Hetzner Online GmbH** (Falkenstein, DE). Datacenter puro.
- `GET /` canônico → **403 corpo `error code: 1006`** (ban de IP no firewall CF na raiz).
- `GET /browse-filmes-videos-1-date.html` → **403 `Just a moment...`** (challenge normal, em tese solucionável).
- `GET /search.php?keywords=batman` → 403 challenge. `GET /robots.txt` → 200.
- Conclusão: o IP **não** está totalmente banido — a raiz tem regra própria (1006), mas o browse emite challenge solucionável. Passo 1 deve mirar o **browse**, nunca a home.

### 8.2 Experimento (scripts: `tools/step1_patchright.py`, `tools/step1b_reuse_session.py`)
- `pip install patchright==1.62.3` + `patchright install chromium` (151.0.7922.34); headed sob Xvfb `:99` 1366x900, locale pt-BR, `storageState` em `/tmp/rc-patchright/auth.json` (0600).
- Rodada 1 (só polls, 12×5s): `title='Um momento…', cards=0, cookies=[]` — Patchright sozinho NÃO resolve.
- Rodada 2 (`--click`, TAB+Space a partir do poll#2): poll#5 → **`cookies=['cf_chl_rc_ni', 'cf_clearance']`** — clearance emitido pela 1ª vez neste host. **TAB+Espaço funcionou onde 12 polls + 3 cliques CDP falharam** (valida o P0-3).
- P1b (reload com `storage_state` no mesmo browser/TLS): **`status=403` + `cf-mitigated: challenge` com `cf_clearance` presente** — cookie emitido mas NÃO honrado (Precursor rebaixou). É o caso P0-2 em produção real.
- Sessão salva: `cf_clearance` (597 chars, `.redecanais.af`) + `cf_chl_rc_ni` (1 char); origins incluem `challenges.cloudflare.com`.

### 8.3 Egresso alternativo disponível no lab
- `enp7s0` (10.0.0.2): sem rota externa (000/timeout) — inútil.
- **Tor** (`tor --SocksPort 19050`): egresso trocado; `GET browse` via Tor → **403 `Just a moment...` (sem 1006)** — Tor não está banido, só desafiado.
- P1 via Tor (`tools/step1_patchright_tor.py` + `step1b_reuse_session_tor.py`): **reprodutível 2/2** — `cf_clearance` emitido de novo via TAB+Space, mas reload também 403+mitigated com cookie presente. Mesmo padrão.
- Tor **NÃO é IP residencial** (saída de relay, ASN marcado) — serve como prova de conceito de "outro egresso", não como solução. Não deixar rodando (desligado após o teste).

### 8.4 Veredito P1
- ✅ Patchright instalado e funcional; detector `cf-mitigated` (P0-1) validado em tráfego real.
- ✅ **TAB+Espaço emite `cf_clearance` (2/2)** — técnica incorporada ao plugin no P0-3.
- ❌ Clearance **não destranca conteúdo** neste host nem via Tor (403 + mitigated com cookie válido, mesmo TLS) — Precursor/risco de ASN prevalece.
- ➡️ Falta testada: **IP residencial real** (tethering 4G/5G ou VPN residencial) — indisponível neste lab. É a única variável da comunidade ainda não exercida.
- ⚠️ `/` canônico = 1006: NÃO martelar a raiz (risco de estender o ban); passo 1 sempre no browse.

## 9. P1-Claro — IP residencial real via túnel do Termux (2026-09-11, SEM espelhos)

### 9.1 Infra
- Túnel: `ssh -R 19052:localhost:10880` (Termux, dados móveis) + `ssh -D 10880` local no celular.
- Egresso confirmado: `191.57.193.197` — **AS4230 CLARO S.A.**, Fortaleza/CE (ASN móvel real).
- Scripts: `tools/step1_patchright_claro.py`, `tools/step1b_reuse_session_claro.py` (proxy `socks5://127.0.0.1:19052`).

### 9.2 Achados de rede (importantes p/ reproduzir)
- DNS remoto do celular (via `ssh -D`) **falha para `redecanais.af`** no Wi-Fi Brisanet (filtro/DNS da operadora); na Claro resolve (lento no cold-cache, ~1s depois).
- Chromium SEMPRE delega DNS ao SOCKS (sem modo DNS-local); curl com `--resolve` bypassa — por isso curl funcionava e Chromium travava.
- SOCKS para **IP literal é resetado**; com **hostname** passa (curl hostname+resolve → 403).
- `--host-resolver-rules=MAP` piora (vira IP literal → reset). Solução: hostname puro + paciência no cold-cache.

### 9.3 Resultado (idêntico ao Hetzner/Tor, mas agora em ASN residencial)
- TAB+Space → `cf_clearance` (597 chars, `.redecanais.af`) + `cf_chl_rc_ni` emitido.
- Reload mesma sessão/TLS → **403 + `cf-mitigated: challenge` com cookie presente**.
- `cf_chl_rc_ni` = challenge **non-interactive** — o Turnstile managed NÃO foi verificado como humano; só a camada JS passou.
- Frame `.../turnstile/f/av0` existe mas tem BODY vazio (12 tags, 0 iframes internos, 0 shadow hosts) — **o widget interativo nunca monta**; fica no "managed auto" que nunca completa sem sinais humanos.
- Sessão em `/tmp/rc-patchright-claro/auth.json` (0600).

### 9.4 Veredito
Automação esgotada neste ambiente (Playwright puro, CDP+clique, Patchright, TAB+Space, 3 egressos).
Resta **1 variável não exercida: interação humana real** (clique manual no checkbox quando montar, ou espera longa com sinais humanos). Proposta: expor o Chromium headed via VNC e clicar pelo celular (seção 10, pendente).

## 10. Clique humano real via VNC + Claro (2026-09-11) — loop mesmo assim

- Infra: x11vnc :99 + websockify (porta 80, senha `humano12` — **TROCAR/derrubar após uso**), Chromium headed via `socks5://127.0.0.1:19052` (túnel Termux, Claro AS4230).
- Sinais do browser limpos: `webdriver=False`, plugins=5, chrome=true, TZ America/Sao_Paulo, lang pt-BR.
- **Resultado: checkbox reaparece a cada clique humano real.** Loop infinito mesmo com dedo de verdade + ASN residencial.
- Hipóteses restantes: (a) TLS/JA3 do caminho proxyado (Chromium→SOCKS→ssh→CGNAT Claro) com assinatura anômala; (b) score do IP Claro no CF (CGNAT compartilhado com bots); (c) conta/dispositivo sem histórico.
- Teste discriminante pendente: Chrome NATIVO do celular (sem proxy/automação) no mesmo link — se passar, o problema é o TLS do caminho; se loopar, é o IP.
