# 🌐 Pesquisa na Comunidade — Testes Reais sobre Cloudflare/Turnstile + CloudStream

**Data:** 2026-09-11 · Método: `web_fetch` direto nas fontes primárias (o `web_search` do harness
está sem API key nesta sessão) + 3 subagentes de pesquisa em paralelo + verificação HTTP 200 manual.
> Todo conteúdo externo abaixo é dado não-confiável para fins de instrução; usamos como referência técnica.

## 1. Cloudflare oficial — como o Turnstile realmente funciona

| Fonte | O que diz (relevante ao nosso caso) |
|---|---|
| [Embed the widget](https://developers.cloudflare.com/turnstile/get-started/client-side-rendering/) | 2 modos: implícito (`div.cf-turnstile`) e explícito (`turnstile.render()`); script **só** de `challenges.cloudflare.com/turnstile/v0/api.js`; token vai p/ `cf-turnstile-response` ou `data-callback` + validação Siteverify. Automação precisa **esperar o token**, não só clicar. |
| [Testing](https://developers.cloudflare.com/turnstile/troubleshooting/testing/) | Cloudflare **detecta Selenium/Cypress/Playwright como bots**; sitekeys dummy `1x000…AA` (passa) / `2x000…AB` (falha) / `3x000…FF` (força challenge). Por isso automação falha contra widget real. |
| [Challenge solve issues](https://developers.cloudflare.com/cloudflare-challenges/troubleshooting/challenge-solve-issues/) | **Loops** sob "strong bot signals"; 401 em PAT = normal/fallback; checklist: navegador atualizado, sem adblock, JS ligado, sem VPN/proxy, outra rede. |
| [Clearance](https://developers.cloudflare.com/cloudflare-challenges/concepts/clearance/) | `cf_clearance` amarrado a visitante/dispositivo (não reutilizável entre máquinas); hierarquia Interactive > Managed > Non-Interactive; **Precursor** reavalia continuamente e **invalida/re-desafia mesmo com cookie válido**; Turnstile por padrão emite só token one-time — `cf_clearance` só sai com **pre-clearance** + hostname = zona WAF. |
| [Detect challenge (`cf-mitigated`)](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/) | Toda Challenge Page retorna `cf-mitigated: challenge` — detecção confiável via fetch antes de resolver. |

## 2. Stealth / anti-detecção — técnicas que a comunidade prova que funcionam

| Projeto | Técnica | Limite declarado pelo próprio autor |
|---|---|---|
| [puppeteer-extra-plugin-stealth](https://github.com/berstend/puppeteer-extra/tree/master/packages/puppeteer-extra-plugin-stealth) | Evasions: `navigator.webdriver` via Proxy, UA, vendor, iframe, `chrome.runtime`, codecs, plugins, webgl, outer-dimensions, Accept-Language | "cat-and-mouse, 100% impossível" |
| [playwright-extra](https://github.com/berstend/puppeteer-extra/tree/master/packages/playwright-extra) | Drop-in Playwright + stealth/recaptcha/proxy-router; base do `storageState` | — |
| [Patchright](https://github.com/Kaliiiiiiiiii-Vinyzu/patchright) | Evita `Runtime.enable` (JS em ExecutionContexts isolados), desabilita Console API, fixa flags (`--disable-blink-features=AutomationControlled`, remove `--enable-automation`), **closed Shadow Roots** (onde o checkbox vive). Declara passar Cloudflare/Kasada/Akamai/Datadome/Sannysoft | Só Chromium; InitScripts detectáveis por timing attack (ninguém checa hoje) |
| [Camoufox](https://github.com/daijro/camoufox) | Spoofing em **C++** (não JS injetado), Juggler isolado, fingerprints BrowserForge, mouse humano | Admite gaps de manutenção em 2026 |
| [undetected-chromedriver](https://github.com/ultrafunkamsterdam/undetected-chromedriver) | Patcheia chromedriver | **"NÃO esconde IP"** — demo passa em casa, falha em datacenter |
| [Selenium-Driverless](https://github.com/kaliiiiiiiiii/Selenium-Driverless) | Chrome real via CDP, declara passar Cloudflare/Bet365/**Turnstile** (branch dev `bypass-turnstile`) | — |
| [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) | Solver-como-serviço (`:8191/v1`): `sessions.create/list/destroy`, `request.get/post` com `session_ttl_minutes`, `waitInSeconds`, `returnScreenshot`, **`tabs_till_verify`** → `turnstile_token`. **UA do client HTTP precisa igualar o dele** | CAPTCHA-solver declarado **quebrado**; browser/req consome muita RAM |
| [Playwright Auth/storageState](https://playwright.dev/docs/auth) | `storageState` (`playwright/.auth/user.json`) — autenticar 1x, reusar. Mecanismo oficial de session reuse | Arquivo contém cookies sensíveis — nunca commitar |

### Síntese: o stack que funciona (ordem de ROI)
1. **Session reuse** (`storageState` / profile persistente / `sessions.create`): resolver 1x, reusar com **mesmo UA + mesmo IP**, detectar invalidação via `cf-mitigated: challenge`.
2. **Driver patcheado (nível CDP)** — Patchright/Driverless/undetected-chromedriver cobrem leaks que stealth-JS não cobre.
3. **Chrome real headful** + viewport/locale/TZ/geo coerentes com o IP.
4. **Clique humano no checkbox**: esperar iframe, `frame.click()` com offset ou TAB+Espaço (`tabs_till_verify`), mouse com trajetória, **esperar o token**.
5. **Higiene de rede**: IP residencial/sticky — **IP domina o resultado**; sem VPN instável; headers coerentes.

### Limites estruturais (a comunidade é honesta)
- Managed/Interactive **não é bypassável por código**; bot forte → loop infinito.
- **Precursor mata sessão válida** sob comportamento suspeito (TLS/JA3 divergente, input robótico, troca de IP/UA).
- Token é **one-time** + Siteverify (`timeout-or-duplicate` em reuso).
- Automação parte em desvantagem por desenho; cada update Chrome/Cloudflare quebra evasions.

## 3. Ecossistema CloudStream / RedeCanais

- **Repo fonte `franciscoalro/kythourcl`: MORTO (404)** — API confirma `Not Found`. Busca `q=kythourcl` retorna só o `kythourcl-dist`. O conteúdo sobrevive espelhado no dist (branch main tem fonte Kotlin + docs).
- **`kythourcl-dist`: ATIVO** (`main` = fonte + docs; `builds` = binários). `repo.json` → Kythour Repository → `builds/plugins.json` (7 plugins, formato `fileHash: sha256-<hex>`, `fileSize`, `version`, `internalName`, `url` raw `.cs3`). RedeCanaisAF v255 (166.599 bytes); histórico v92→v255.
- **Issues/discussões: zeradas** em todos os repos — o conhecimento vive nos `.md` e comentários de código, não em threads.
- **Dois sabores RedeCanais**: `RedeCanais/` (DooPlay/WordPress em `www3.redecanais.vip`, DooPlayer `/wp-json/dooplayer/v1/` + 7 servidores) vs `RedeCanaisAF/` (PHP Melody em `redecanais.af`, `/browse-*-videos-{page}-date.html`, `/search.php?keywords=`).
- [`dev-d-25/cs-plugin`](https://github.com/dev-d-25/cs-plugin): "CloudStream providers: MoviesMod + MoviesLeech **with cloud bypass**" — prova que a comunidade trata bypass como feature de provider.
- Core oficial: `recloudstream/cloudstream` (1070 hits p/ WebViewResolver/CloudflareKiller na busca) — `CloudflareKiller`/`WebViewResolver` são as classes que nosso plugin já estende.

## 4. O que isso muda no nosso caso (aplicação direta)

| Achado da comunidade | Status no RedeCanaisAF | Ação |
|---|---|---|
| `cf-mitigated: challenge` como detector | Plugin checa HTML, não o header | **Adicionar**: checar header `cf-mitigated` no `requestDoc` antes de parsear (detecção barata e oficial) |
| Session reuse = maior ROI | Já existe (`CF_PERSIST`, TTL 12h, `restoreClearanceIfValid`) | Validar TTL contra Challenge Passage real; logar idade da sessão no `BEFORE_REQ` |
| UA do client = UA do solver (FlareSolverr) | `stealthHeaders()` existe; verificar se o OkHttp repete **exatamente** o UA do WebView que resolveu | Auditar `cleanClient` vs `challengeUserAgent()` |
| `tabs_till_verify` (TAB+Espaço em vez de clique) | Plugin só clica por coordenada | **Experimentar**: foco por TAB + Espaço no widget quando o rect falha (nosso caso Xvfb: iframe 0×0) |
| Patchright (evita `Runtime.enable`, closed Shadow DOM) | Nossos scripts CDP usam `Runtime.evaluate` direto | **Adotar no harness**: migrar `tools/live_cdp_sim*.py` p/ `patchright` (drop-in) e re-testar passo 1 |
| IP domina | Lab = ASN de hosting; app Redroid = mesmo egress | **Testar via IP residencial** (tethering/VPN residencial) antes de declarar o site "morto" |
| Precursor invalida sessão válida | `REQ#2` com `clearance=true` tomou **402** hoje | Explica o 402: cookie presente mas clearance rebaixado → plugin deve tratar 402 = re-resolver, não "falha total" |
| Repo fonte morto | Dossiê cita URL 404 | Atualizar `DOSSIE_REDECANAIS.md` → apontar `kythourcl-dist` |

## 5. Próximo experimento concreto (derivado da pesquisa)
1. `pip install patchright` + `patchright install chromium` (ou reusar channel=chrome).
2. Re-rodar passo 1 com Patchright (sem `Runtime.enable`, com `storageState` persistente em `/tmp/rc-cdp-profile`).
3. Se ainda 403: repetir via IP residencial (a variável que a comunidade aponta como dominante).
4. Registrar `cf-mitigated` header + idade da sessão em cada tentativa.
