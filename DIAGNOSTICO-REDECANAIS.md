# RedeCanaisAF — Diagnóstico Cloudflare (2026-09-13)

## Veredito
Bloqueio é **ban de IP no edge (1106/1006)**, não o challenge em si.
O Turnstile checkbox passa com 1 click (Op1 provou), mas este site serve
**managed challenge automático** (sem checkbox) que só aprova com IP de boa
reputação. Falta IP limpo que sobreviva > 1 request.

## Infra de pé (atualizado 19:55)
| Serviço | Endereço | Estado |
|---|---|---|
| flare-alex (alexfozor `pr-1300`, FlareSolverr 3.3.21, Chromium 126) | 127.0.0.1:8192 (sessão `alex-tor2`) | up 50min |
| tor-proxy | 172.20.0.1:9050 host / 172.20.0.3:9050 interno (IP atual `185.220.101.49`) | healthy |
| CDP do fork (forwarder `/tmp/cdp-fwd2.py` via `docker exec -d`) | 172.20.0.4:37251 → Chromium interno :37250 (porta MUDA por request) | up |
| flaresolverr oficial 3.5.2 | **parado** (`docker stop` por RAM; `docker start flaresolverr` reativa :8191) | stopped |
| Chrome headed Op1 | morto p/ liberar RAM (perfil em `/tmp/rc-visible-profile`) | stopped |
| mitmdump | 0.0.0.0:8081, addon `frida/mitm_video.py` | up |
| redroid 14 | emulator-5554 / 127.0.0.1:5555, frida-server :27042 | up 36h |
| traefik/dvwa/cloudflared/xray | :80/:443/:8080 etc | up |

Host: 3.7Gi RAM (1.1Gi avail), swap 2.8/4.0Gi, disco 89%.
GradleDaemon morto. Não rodar 2 Flares + Chrome juntos (estoura swap).

## O que foi provado
1. **IP direto banido (v4+v6):** `curl -4/-6 redecanais.af` → 403 `error 1006/1106`.
   Browser headed resolve Turnstile-checkbox no click mas cai em `Access denied 1106`.
2. **Tor solve 1x por IP (oficial 3.5.2):** `sessions.create + proxy socks5` →
   `Challenge solved!` (~73-108s). Segunda URL = `500 IP banned`.
   Tor gruda no circuito (sem NEWNYM, control 9051 sem auth). `docker restart tor-proxy` = IP novo.
3. **HTML é loader ofuscado:** `var b533b7d` (1.1MB) = base64 + XOR k=112 + bytes reversos.
   Decode → `/tmp/decoded-browse.html` (838KB): só reloader + anti-debug + `/player3/bundle.js`.
   Catálogo real só após reloader+bundle+API — tudo atrás do edge.
4. **App morre com tap:** dialog Extensions auto-dismiss; tap 622,1124 mata a activity.
   Relançar SEM tap: `am start ...AccountSelectActivity`, aguardar MainActivity sozinho.
   UA mismatch: clearance Tor (Chrome/152 X11) não vale no WebView Redroid (Chrome/125 Pixel 7).
5. **Fork alexfozor `pr-1300-experimental-v2` (DrissionPage/Chromium 129): não sobe aqui.**
   Loop `Testing web browser → TimeoutError` com swap estourado. Removido.
6. **Fork alexfozor `pr-1300` (undetected-chromedriver/Chromium 126): passa do ban
   imediato, trava no managed challenge.** Oficial dizia `IP banned` em 1s; o fork
   chega a `Challenge detected.` e tenta 110s. `tabs_till_verify` 3/6 → 500
   `'NoneType' object is not subscriptable` (bug do fork nesse template `chl_page/v1`).
7. **Proxy do fork: usar IP interno.** `172.20.0.1:9050` NÃO roteia de dentro do container
   (`ERR_PROXY_CONNECTION_FAILED`, 110s contra proxy morto). Correto:
   `socks5://172.20.0.3:9050` (IP do tor-proxy na `automation_default`; fork = 172.20.0.4).
8. **Click no Turnstile do fork: sem alvo clicável.** CDP interno exposto via forwarder
   python (`/tmp/cdp-fwd2.py`, WS com `suppress_origin=True` — Chromium rejeita Origin
   externo com 403; 2º DrissionPage dá `BrowserConnectError`). Tabs `Just a moment...` +
   `.../turnstile/f/av0` visíveis, mas: zero IFRAME/shadow na página, `Page.getFrameTree`
   = só frame principal, widget 300x65 com body vazio = **managed automático, sem checkbox**.
   Com IP Tor de má reputação o managed nunca aprova. Muro = reputação de IP, não técnica.

## Arquivos
- `/tmp/flare-tor.json` — cf_clearance + UA da sessão oficial que solveu (amarrado a IP/UA)
- `/tmp/flare-response.html` — loader 1.1MB original
- `/tmp/decode-b53.py` → `/tmp/decoded-browse.html` — camada 1 decodificada (XOR k=112)
- `/tmp/op1-browse4.html` — página `Access denied 1106` (prova do ban direto)
- `/tmp/fork-final.html` — `ERR_PROXY_CONNECTION_FAILED` (fase proxy errado do fork)
- `/tmp/filter-m3u8.sh` — filtro consolidado (mitm + frida): **0 matches**
- `/tmp/inject3.js` — injeta cf_clearance+cookies via Frida (PID do `CloudStream Beta`)
- `/tmp/flare-chain*.py`, `/tmp/flare-browse30*.py`, `/tmp/flare-nav.py` — chains oficial
- `/tmp/alex-solve*.py`, `/tmp/alex-tabs.py` — solves + tabs_till_verify no fork
- `/tmp/cdp-fwd.py`, `/tmp/cdp-fwd2.py` — forwarder CDP (container→host; porta debug muda por request!)
- `/tmp/ts-click*.py`, `/tmp/click-drission*.py`, `/tmp/click-inside.py`, `/tmp/dump-fork.py` — CDP/Drission no fork
- `/tmp/frida-okhttp*.log`, `/tmp/frida-webview*.log`, `/tmp/mitm-8081.log` — logs
- `/tmp/rc-visible-profile` — perfil Chrome headed Op1 (cookies do solve)
- Plugin: `/root/cloudstream-plugins/RedeCanaisAF/.../CloudflareSolver.kt` (BUILD 272)

## Retomada
```bash
# sessoes fork (:8192) / oficial (:8191, parado — docker start flaresolverr)
curl -s http://127.0.0.1:8192/v1 -H 'Content-Type: application/json' --data-raw '{"cmd":"sessions.list"}'
# IP tor atual (host) / interno p/ containers na automation_default = 172.20.0.3:9050
curl -s --max-time 10 --socks5-hostname 172.20.0.1:9050 https://check.torproject.org/api/ip
# IP novo do tor
docker restart tor-proxy
# sessão nova no fork (SEMPRE com IP interno!)
curl -s http://127.0.0.1:8192/v1 -H 'Content-Type: application/json' --data-raw '{"cmd":"sessions.create","session":"NOVA","proxy":{"url":"socks5://172.20.0.3:9050"}}'
# CDP interno do fork (descobrir porta atual, muda por request!)
docker exec flare-alex sh -c "ps aux 2>&1 | grep -oE 'remote-debugging-port=[0-9]+' | sort -u"
# forwarder: ajustar /tmp/cdp-fwd2.py (SRC=porta debug, DST=porta host) + docker exec -d flare-alex python3 /tmp/cdp-fwd2.py
# ler tabs: curl http://172.20.0.4:<DST>/json/list | python3 -m json.tool
# filtro videos
bash /tmp/filter-m3u8.sh
# app sem tap (DEV=127.0.0.1:5555 se emulator-5554 travar; adb disconnect+connect resolve)
adb -s 127.0.0.1:5555 shell "am start -n com.lagradost.cloudstream3.prerelease/com.lagradost.cloudstream3.ui.account.AccountSelectActivity"
# injetar clearance (PID = CloudStream Beta em frida-ps)
frida -D 127.0.0.1:5555 -p <PID> -l /tmp/inject3.js
```

## Próxima frente (requer IP limpo)
Opção A: egresso residencial/VPN fora deste host → repetir Op1 (click resolve) e crawlear na mesma sessão.
Opção B: IP Tor limpo + chain imediato solve→browse→detail SEM trocar de sessão/IP (janela de 1 uso).

## Frente celular (Termux + keep-proxy) — 2026-09-13, PENDENTE WiFi
- Tunnel: `ssh -R 10900:127.0.0.1:1080 root@167.233.60.72` (keep-proxy com auto-reconnect;
  `ServerAliveInterval=15/CountMax=2`, teste ponta a ponta a cada 60s, `termux-wake-lock`).
- Script Termux: `/tmp/keep-proxy.sh` (cópia de referência; original no `~/keep-proxy.sh` do celular).
  Chave `u0_a169@localhost` cadastrada em `/root/.ssh/authorized_keys` (4ª linha).
- SOCKS: `microsocks -p 1080` (single-thread — challenge CF com ~15 conns paralelas dá timeout);
  trocar por `pproxy -l socks://127.0.0.1:1080` (`pip install pproxy`, SEM `-y`).
- Forwarder p/ Docker: `/tmp/fwd10900.py` (`0.0.0.0:10901` → `127.0.0.1:10900`);
  FlareSolverr usa `socks5://172.17.0.1:10901` (container não alcança 127.0.0.1 do host).
  Sessão de teste: `cel1` no oficial :8191.
- Resultado no móvel (IPs `191.57.194.67` → `177.37.187.174`): proxy passa Google 1x (84KB),
  mas `redecanais.af` e `cloudflare.com` = timeout 28s (bloqueio/rota da operadora ou CGNAT);
  IP troca a cada handoff (1 solve queimado por tentativa); rajadas de perda derrubam tudo.
- **Retomar no WiFi:** keep-proxy reconecta sozinho; testar `google + redecanais` via
  `curl --socks5-hostname 127.0.0.1:10900`; se site responder, rodar `/tmp/cel-chain.py`
  (solve browse wait=20 + detail imediato na sessão `cel1`).

## 2026-09-17 — Ciclo gratuito final (Patchright + Camoufox + proxies free)

**Ferramentas instaladas:** Patchright 1.62.3, Camoufox 0.5.6 (+geoip), FlareSolverr fork (sessão anterior).
**v275 publicada:** WebView invisível (alpha 0.01), prefetch OkHttp, bloqueio navegação externa, diagnóstico player — live no app.

| Teste | Resultado |
|---|---|
| Patchright + Tor (8 IPs) | clearance emitido, Precursor re-desafia — 0 passes |
| Camoufox + Tor (2 IPs, geoip) | managed sem widget — 0 passes |
| Proxies free (20+ testados) | 19 mortos; 2 vivos (range 45.74.31.x) → challenge sem aprovação |
| Espelhos | .to=parking, .mx/.hd=522, .la/.gs/.nu/.vi/.si=mortos |

**Veredito:** eixo técnico esgotado com meios gratuitos. Falta IP residencial (trial Evomi sem cartão em my.evomi.com/register — requer cadastro do usuário; ou outra rede WiFi/4G) ou pausa 48h p/ bans expirarem.
