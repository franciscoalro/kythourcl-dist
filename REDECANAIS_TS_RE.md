# Engenharia Reversa + Rede em TypeScript — RedeCanaisAF (`redecanais.af`)

Data: 2026-09-11. Harness: `tests-ts/` (stdlib Node 22 + `tsx`, zero deps).
Comando: `/opt/deepseek-harness/node_modules/.bin/tsx tests-ts/run.ts` → **28/28**.

Escopo estrito, sem espelhos: só `https://redecanais.af` (+ `challenges.cloudflare.com` como widget).

## 1. RE do shell `player3/server.php` (`re/shell-vm.test.ts`, 5 testes)

Fonte: `/tmp/dump_serverphp.html` (60 KB). Achados:
- Shell mínimo: `<script src="./bundle.js">` + **inline VM ofuscada** (`w3Du70…`, 59 KB).
- **VM com pilha**: registradores `GJ` (pilha) / `GR` (SP) / `GK` (PC), **~97 opcodes** (`case 0x…`), anti-tamper (`defineProperty`, `getOwnPropertySymbols`, `toPrimitive`, `new.target`).
- **Const-pool `k=[…]` com 194 itens** aparentemente Base64, mas decodificando dá blobs com **prefixo comum `\x05\xf5\x84…`** → stream-cipher com keystream (não Base64 puro). Criptoanálise por crib reservada (plaintext provável: `function`/`return`/`https`); **não necessária ao plugin**.
- **Zero IOCs em claro** no shell: nada de `serverforms`, `__RC__`, `captcha_button` literal — tudo montado em runtime pelo `bundle.js` + VM.
- Conclusão RE: o caminho feliz **não se extrai do shell estático** — vem do tráfego (v250). O shell serve como **sentinela de versão** (se a assinatura mudar, o site trocou de packer).

## 2. RE do algoritmo de busca (`re/search-algo.test.ts`, 5 testes)

Fonte: `/tmp/rc-search-p4.html` (338 KB, JS em claro). Espelhado verbatim:
- `normalize()`: lower + mapa `áàãâä→a` etc., `ç→c`, `ñ→n` — **sem NFD** (o teste trava isso).
- Índice: dois arquivos (`final_mapa.txt`, `final_mapafilmes.txt`), linha `TITULO<a href="URL"`, strip `<b>`, trim de `-` final, `sort` por `titulo_norm`, match por `includes`.
- Query via `?keywords=`, render em `.listagem`.
- Implicação plugin: nossa `normalize()` Kotlin deve **imitar esse mapa exato** (conferir NFD — se usarmos NFD damos match equivalente na maioria, mas `ñ→n` vs NFD `ñ→ñ` difere; checar `RedeCanaisAFText`).

## 3. Grafo do player (`net/player-graph.test.ts`, 5 testes)

Fonte: `/tmp/rc-frame-v250-all.json` (242 eventos CDP). Sequência canônica travada:
1. `serverforms.api?a6e91c4f=1&71b8d2=RCFServer3&e5c39f=ondemand&0a6d84=CAPTAMRC3LEG` (init, params do `server.php`)
2. `serverforms.api?3c91e7a4=<token opaco >40 chars>` (resolve, **nunca reutilizar**)
3. `jquery.videojs.4.5.2.api?rctoken=…` + stack `videojs.ima.js` (ads) + `.vtt` + poster `.jpg`
4. `__RC__/proxy?src=<p12-common-sign>` → **206** → `xn--…/tos-alisg-…/proxy` → **206**
- Negativos travados: sem `wp-json/dooplayer/superflix/megaembed/playerflix` no caminho feliz.

## 4. Fingerprint do challenge (`net/challenge-fingerprint.test.ts`, 4 testes)

Via `fetch` cru (sem browser), UA Chrome/152:
- `robots.txt` → **200**; `browse-*.html` e `search.php?keywords=` → **403 + `cf-mitigated: challenge`**, `server: cloudflare`.
- **DESCOBERTA (refino P0-1)**: `robots.txt` 200 vem **COM `cf-mitigated: challenge`**. O CF envia o header em toda resposta. Header sozinho ≠ challenge.
- Correção aplicada em `RedeCanaisAF.kt` (2 pontos): `mitigatedHeader` e `retryMitigated` agora exigem `code != 200`.

## 5. Binding TLS/JA3 (`net/tls-binding.test.ts`, 3 testes)

- ≥1 das 4 sessões P1 tem `cf_clearance` emitido (nomes, nunca valores) mas **0 cards** — clearance fora do TLS emissor é inútil.
- v250 prova o positivo: mesmo TLS → 206 encadeado.
- Controle negativo: fetch sem cookie → 403.

## 6. Cross-check Kotlin (`re/plugin-xcheck.test.ts`, 6 testes)

- 7 marcadores do v250 com cobertura no Kotlin (`serverforms`, `__RC__/proxy`, `tos-alisg`, `xn--l`, `videojs`, `captcha_button`, `server.php`).
- P0-1..P0-4 presentes; regex `__RC__/proxy?src=` casa a URL real do v250.
- Gating `code != 200` presente nos 2 pontos.

## 7. Validação

- TS: **28 passed, 0 failed, 6 files**.
- Kotlin: `testDebugUnitTest` **30 passed**; `:RedeCanaisAF:make` **BUILD SUCCESSFUL** (`.cs3` rebuildado).
- Arquivos: `tests-ts/run.ts`, `tests-ts/util.ts`, `tests-ts/re/{shell-vm,search-algo,plugin-xcheck}.test.ts`, `tests-ts/net/{player-graph,challenge-fingerprint,tls-binding}.test.ts`.

## 8. Próximos alvos RE (não bloqueantes)

1. Crib-attack no const-pool da VM (chave do stream-cipher) — só se o shell virar gargalo.
2. `bundle.js` (externo, não capturado): `dt.api` → `redirect.api?p=<base64>` (migração `redecanaistv.af` citada no Kotlin) — capturar quando houver sessão.
3. Conferir `RedeCanaisAFText.normalize` vs mapa verbatim (NFD vs tabela do site).

## 9. Paridade normalize (RE-04, 2026-09-11)

- Diferencial NFD (Kotlin) vs tabela verbatim do site em 18 casos: 16/18 idênticos; divergem só `ł/ž` (irrelevante PT-BR).
- Travado em `tests-ts/re/normalize-parity.test.ts` (3 testes) + `TestRedeCanaisAFUnit.TESTE 7` (espelho Kotlin).
- TS: **31/31** · Kotlin: **31/31** · `:RedeCanaisAF:make` BUILD SUCCESSFUL.

## 10. Scraping reverso ao vivo via Brisanet (2026-09-11, tarde)

- Circuito: `VPS :19053` ←ssh -R— celular (SOCKS Python direto, `tools/rc-tudo-v2.sh` + `socks5_direct.py`) ← Brisanet `177.37.187.174`.
- Correção de arquitetura: `ssh -D` no celular SAI PELA VPS (loop) — o SOCKS precisa conectar direto pela rede local.
- Novos: `tests-ts/net/socks.ts` (SOCKS5+TLS SNI, stdlib, timeout 25s), `tests-ts/net/live-brisa.test.ts` (4 LIVE, skip sem túnel).
- Resultado: browse/search/server.php → 403 `Just a moment` (Brisanet **não banida**, só desafiada); robots → 200.
- TS: **39/39** · Kotlin: **31/31**.

## 11. Gramática do proxy (RE-05)

- `serverforms.api` init = 4 params fixos + nonce `7e3a91` (48 chars b64url); resolve repete nonce com OUTRO valor (sessão).
- `__RC__/proxy?src=` → `tos-alisg-avt-*` (`container=videos`, `refresh=31536000`) → mp4 `neosoro.gq` (`?sv=57&<k>=<ts>-<sign>`).
- ACHADO: `url=` interno NÃO é encoded — `&` quebra parse query; player faz split custom (tudo após `url=`). Espelhado no teste; checar `StreamResolver` se usa query-parse nesse ponto.

## 12. Execução do JS do site em TS (RE-07) + parser extraível

- `tests-ts/re/site-js/index.ts`: réplicas executáveis verbatim de `rc-search-p4.html` (`siteNormalize`, `siteParseIndex`, `siteDecodeHtml`, `siteMergeIndexes`).
- `tests-ts/re/proxy_extract.ts`: parser do player (`rawExtract` com 7 regexes de `StreamResolver`, `parseProxyChain` com split `url=`, `decodeRedirectParam`).
- TS como linguagem de RE: mesma regex, mesmo `decodeURIComponent`, mesmo `localeCompare` — sem divergência Python/Kotlin.
- LIVE resiliente: `live-brisa` com `withRetry(2)` — túnel efêmero não quebra CI.

## 13. Interceptação do frame do vídeo (RE-06, 2026-09-11)

- v250: o "frame do vídeo" é **navegação `page` para `server.php`** (documentURL, não iframe embed). Antes: detalhe → server.php (targetType page) → serverforms init/resolve → __RC__/proxy (206) → xn--/tos-alisg (206, video/mp4).
- `tests-ts/re/frame-intercept.test.ts` (5 testes): trava targetType, depth 4, mime 206, regexes do Kotlin, documentURL correto (não detail vid curto — regressão v253).
- Bundle fresco (29638 B via Brisanet, 2026-09-11): obfuscator.io (`b0` 576 itens + `ab`/`ac` 97/83 calls), zero IOCs em claro — igual ao histórico. Estratégia: HAR de Chromium > static deobf.
- TS: **53/53** · Kotlin: **31/31**.
