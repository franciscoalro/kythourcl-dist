# Plano de Testes — RedeCanaisAF v226 — Cliente → Meio → Servidor

Objetivo: **otimizar extração e garantir dados consistentes** usando `mitmproxy:8888 + ZAP:8090 + redroid emulator-5554`. Cobre `getMainPage / search / load / loadLinks` com Cloudflare Turnstile Managed.

## 0. Premissas do Ambiente
- Plugin: `RedeCanaisAF.kt BUILD_VERSION=226`, `CloudflareSolver.kt stealthHeaders()`, `LocalImageProxy.kt`, `StreamResolver.kt` + `WebViewStreamProxy` (`127.0.0.1:<port>` 512KB chunk, 45s timeout)
- Intercept: `OkHttp .proxy(NO_PROXY)` comentado → respeita `http_proxy=172.17.0.3:8080` (mitmproxy). ZAP em `127.0.0.1:8090` standalone
- CAs instaladas no redroid: `mitmproxy-ca c8750f0d` + `zap-ca ea45faac` em `/system/etc/security/cacerts` e `apex/conscrypt`
- Servidor: `https://redecanais.af` → `player3/server.php?categoria=vod&server=RCFServer2&subfolder=ondemand&vid=AMRMNCSMNTO` → `__RC__/proxy?src=p12-common-sign...&url=https://s1...mp4` (TLS/JA3-BOUND só no WebView)
- Limites: Disco `38G 34G 2.1G 95%`, RAM 3.8G (OmniRoute 722M, ZAP 521M, redroid 479M, Gradle 212M). `omniroute.service` com `MemoryMax=1400M` + guard `*/2min` — **não parar sem aviso**

## 1. Fase 1 — Baseline Passivo (SEM ataque)
**Técnica: Passive Scan + HAR Dump**
- `mitmdump -w /tmp/redecanais-baseline.mitm --set ssl_insecure=true` + `mitmweb :8081` + ZAP `Passive Scan` ligado
- Roda `python tools/verify_plugin.py RedeCanaisAF` 3x (catalogo, busca "vingadores", load filme, loadLinks sem play)
- Captura: `FAST_GET dt`, `cf_clearance/__cf_bm/RCIP/RCSESS` no CookieManager, `challenge-platform` hits, `Sec-Ch-Ua*` enviados
- Saída: `baseline.har` + `flows.mitm` + logcat `RedeCanaisAF-Trace:V` (`FAST_GET_OK/MISS/ERR`, `IMG_PROXY`, `PLUGIN_VERSION`)
- **Critério:** catalog `200 OK` sem `challenge_started` em 2/3 runs (cache 12h + persist `redecanais_af_cf`). Documenta `cf-ray` e `cf-mitigated: challenge`

## 2. Fase 2 — Intercept & Breakpoint (validação stealth)
**Técnica: Intercept & Break**
- No mitmweb: `Intercept` → pausa `GET /browse-filmes-videos-1-date.html`
- Teste A: remove `Sec-Ch-Ua`/`Sec-Fetch-Site` → resume → espera `403` → prova que stealth é necessário
- Teste B: troca `Referer https://redecanais.af/` por `https://google.com/` → espera `403/challenge` → valida Referer-Bound
- Teste C: remove `Cookie: cf_clearance` → espera `cf-mitigated: challenge` → valida persist TTL 12h
- **Critério:** sem stealth = challenge; com stealth = `FAST_GET_OK dt < 2000ms`. Ajustar `stealthHeaders()` se falhar

## 3. Fase 3 — Replay Diferencial (OkHttp vs WebView)
**Técnica: Repeater / Replay**
- No mitmproxy extrai `__RC__/proxy?src=p12-...&container=videos&url=https://s1...mp4` capturado do `WebViewStreamProxy`
- Replay A: `curl -x 172.17.0.3:8080 -H "Cookie: $cf_clearance" "https://s1...mp4"` direto → espera `520 Web server is returning an unknown error`
- Replay B: mesmo URL via `WebViewStreamProxy` `http://127.0.0.1:<port>/proxy?url=...` → espera `206 Partial Content` com `CHUNK 512KB`
- **Critério:** prova JA3/TLS-BOUND. Se B falhar, aumentar `CAPTURE_TIMEOUT_MS 45000` ou fixar `protocols HTTP/2`

## 4. Fase 4 — Map Local (estabilizar extração)
**Técnica: Map Local / Fake Response**
- Salva fixtures: `browse-filmes.html`, `load-filme.html`, `server.php.html` com token fresco
- Script mitmproxy `map_local.py`: `if "server.php?vid=" in url: flow.response = make(200, open("server.php.html","rb").read())`
- Roda `verify_plugin.py` offline apontando pro `127.0.0.1` → valida parsers `StreamResolver` (`iframe .pm-video-watch-wrap`, `server.php?vid=`) sem flake de rede
- **Critério:** `getMainPage`/`search`/`load` parseiam 100% fixtures. Detecta regressão de seletor antes de ir pra rede

## 5. Fase 5 — Spider Mapping (cobertura)
**Técnica: Spider + Ajax Spider (ZAP)**
- ZAP API: `curl "http://127.0.0.1:8090/JSON/spider/action/scan/?url=https://redecanais.af&maxChildren=20"`
- Depois `ajaxSpider`: `curl "http://127.0.0.1:8090/JSON/ajaxSpider/action/scan/?url=https://redecanais.af"`
- Coleta URLs descobertas: `/browse-*`, `/topvideos.html`, `/player3/server.php`, `__RC__/proxy`
- **Critério:** compara com `CloudflareSolver.setCatalogUrls` (6 URLs). Se faltar endpoint, adiciona ao catalog e ao `homeCache` TTL 30min

## 6. Fase 6 — Fuzzer de Parâmetros (robustez)
**Técnica: Fuzzer**
- ZAP Fuzzer no `vid`, `src`, `url`, `RCSESS`, `RCIP` (listas pequenas, rate 5 req/s pra não tomar block)
- Payloads: `vid=AMRMNCSMNTO' OR 1=1`, `vid=../../../etc/passwd`, `src=null`, `url=https://evil.com/test.mp4` (SSRF check)
- **Critério:** servidor deve responder `403/520` consistente, nunca `500` com stack. Se `200` com conteúdo estranho → alerta de SSRF/Path Traversal pra reportar, mas plugin deve tratar como `loadLinks` vazio

## 7. Fase 7 — Active Scan Controlado (seguro)
**Técnica: Active Scan — só leitura, sem DoS**
- ZAP `ascan` com policy `Default Policy - Light` e `threadPerHost=2`, `delayInMs=500`, scope `https://redecanais.af/server.php.*` apenas
- Não escanear `__RC__/proxy` com payloads pesados (risco de IP ban)
- **Critério:** relatório HTML exportado `zap-report.html`. Zero `High` nos headers do plugin (ex: `X-Frame-Options`, `CSP`). Se achar `XSS` refletido em `?vid=`, sanitizar `URLEncoder.encode(vid)`

## 8. Fase 8 — Otimização de Extração (performance)
**Técnica: Timing + Cache**
- Usa `tools/benchmark_live_timing.py` e `tools/measure_real_timing.py` + `dt` já logado em `FAST_GET_OK` e `IMG_PROXY`
- Testes: A) `getMainPage` cold (sem cache) vs warm (cache 30min), B) `load` com `loadCache`, C) `LocalImageProxy` com/sem `directHttpClient` dt
- Varia `RESPONSE_CACHE_TTL_MS` (15m/30m/60m) e `PERSIST_TTL 12h` → mede `catalogo instantâneo` hit rate
- **Critério:** `getMainPage warm < 800ms`, `search < 1500ms`, `tryFastHttpGet < 2000ms`, `IMG_PROXY hit < 300ms`. Se `FAST_GET_MISS 520` > 20% → aumentar `retryOnConnectionFailure` ou manter WebView fallback

## 9. Fase 9 — Consistência / Regressão
**Técnica: Harness + WebSocket/HAR**
- `mitmdump -w` + `python tools/layered_test.py` (já existe) rodando matriz 10 filmes x 3 runs
- Valida: `homePage` retorna 6 categorias com `HomePageResponse` não vazio, `search` retorna `SearchResponse` com `posterUrl` via `LocalImageProxy`, `load` retorna `LoadResponse` com `plot/poster`, `loadLinks` retorna `ExtractorLink` com `referer` correto
- **Critério:** 95% de sucesso em 30 runs. Falhas só por `challenge_started` → `cf_clearance obtido!` deve auto-recuperar sem crash

## 10. Fase 10 — Automação e Chain Completa
**Técnica: Chain mitm→ZAP + ZAP API**
- Liga cadeia completa: `APP http_proxy=127.0.0.1:8090` direto no ZAP + ZAP `Connection > Use proxy 172.17.0.3:8888` (mitm como upstream) — hoje está paralelo; validar `curl -x 127.0.0.1:8090 https://httpbin.org/get` → 200 via mitm
- Automatiza via `ZAP API http://127.0.0.1:8090/JSON/` + `mitmproxy addons` python pra injetar `cf_clearance` fresco automaticamente
- Exporta `HAR` final e `flows.mitm` pro `builds/` e anexa no release `v226`
- CI: `verify_plugin.py` no `build.yml` com `mitmproxy` sidecar no Actions runner (opcional)

## Métricas de Sucesso (Antídoto a flake)
| Métrica | Alvo |
|---|---|
| `FAST_GET_OK` rate | >80% |
| `challenge` auto-resolvido | 100% sem toque manual após `cf_clearance` |
| `loadLinks` retorna link válido | >95% (WebView) |
| `IMG_PROXY` hit | >90% |
| Tempo `getMainPage` warm | <800ms |
| Disco pós-testes | >1.5G livre (limpa `mitm/*.mitm` >100M, `zap/*.log`) |

## Cronograma Sugerido (1 dia)
1. Fases 1-3 (manhã) → valida intercept e JA3-BOUND
2. Fases 4-6 (tarde) → fixtures + spider/fuzzer
3. Fases 7-9 (noite) → active scan leve + benchmark + regressão 30 runs
4. Fase 10 → chain completa + HAR pro GitHub

## Riscos e Mitigação
- **Disco 95%:** antes de cada fase `rm /tmp/*.mitm` antigos, `docker logs --tail 0`, manter só 5 `db_backups` (595M hoje)
- **RAM 95%:** pausa `omniroute` só se `free < 400M` e avisa; ZAP já com `--memory 900m`
- **IP Ban Cloudflare:** rate limit 5 req/s no fuzzer/ascan, usa `stealthHeaders` sempre

## Próximo Passo
Aprovar este plano → executo Fases 1-3 em sequência e te entrego `baseline.har` + `FAST_GET` timings + prova `520 vs 206`.
