#!/usr/bin/env python3
"""Simulação ao vivo: Chrome real + CDP avançado + dump de requisições + clique/automação.
Alvos: redecanais.af (challenge) e mirrors. Gera dumps JSON + relatório para cruzar com plugin.
Uso: python3 tools/live_cdp_sim.py [--url URL] [--headless] [--click-player]
"""
import argparse, json, os, re, subprocess, sys, tempfile, time, urllib.request
from datetime import datetime, timezone

CHROME = "/usr/bin/google-chrome"
PROFILE = "/tmp/rc-cdp-profile"
CDP_PORT = 19222
OUTDIR = "/tmp/rc-live-sim"

TARGETS = {
    "home_af": "https://redecanais.af/",
    "browse_filmes": "https://redecanais.af/browse-filmes-videos-1-date.html",
    "search_batman": "https://redecanais.af/search.php?keywords=Batman",
    "detail_batman": "https://redecanais.af/a-sombra-do-batman-episodio-01-cacados_a8b6b81b2.html",
}

def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=kw.pop("timeout", 60), **kw)

def ensure_outdir():
    os.makedirs(OUTDIR, exist_ok=True)
    os.makedirs(PROFILE, exist_ok=True)

def kill_old_chrome():
    sh(["pkill", "-f", f"remote-debugging-port={CDP_PORT}"])

def launch_chrome(headless=True):
    kill_old_chrome()
    time.sleep(1)
    args = [CHROME, f"--remote-debugging-port={CDP_PORT}",
            f"--user-data-dir={PROFILE}",
            "--no-sandbox", "--disable-dev-shm-usage",
            "--disable-blink-features=AutomationControlled",
            "--window-size=1280,800",
            "--lang=pt-BR",
            "about:blank"]
    if headless:
        args.insert(1, "--headless=new")
    else:
        # Xvfb fallback
        args.extend(["--display=:99"])
    log = open(f"{OUTDIR}/chrome.log", "w")
    p = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT)
    # espera CDP responder
    for i in range(40):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{CDP_PORT}/json/version", timeout=3) as r:
                info = json.loads(r.read().decode())
                print(f"[CDP] up after {i+1}s: {info.get('Browser','?')[:60]}")
                return p
        except Exception:
            time.sleep(0.5)
    raise RuntimeError("CDP não subiu")

def cdp_tabs():
    with urllib.request.urlopen(f"http://127.0.0.1:{CDP_PORT}/json/list", timeout=5) as r:
        return json.loads(r.read().decode())

def ws_send(ws_url, method, params=None, mid=1):
    # cliente websocket mínimo via websocket-client? fallback: usa CDP HTTP? -> usa websockets se disponível
    import socket, hashlib, base64, struct, json as j
    # implementação mínima: handshake + 1 msg + 1 resposta (sem fragmentação complexa)
    from urllib.parse import urlparse
    u = urlparse(ws_url.replace("ws://", "http://"))
    host, port, path = u.hostname, u.port, u.path or "/"
    if u.query: path += "?" + u.query
    key = base64.b64encode(os.urandom(16)).decode()
    s = socket.create_connection((host, port), timeout=10)
    req = (f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
    s.sendall(req.encode())
    hdr = b""
    while b"\r\n\r\n" not in hdr:
        hdr += s.recv(4096)
    if b"101" not in hdr:
        raise RuntimeError(f"WS handshake falhou: {hdr[:200]}")
    msg = j.dumps({"id": mid, "method": method, "params": params or {}})
    bmsg = msg.encode()
    frame = bytes([0x81])
    ln = len(bmsg)
    if ln < 126: frame += bytes([0x80 | ln])
    elif ln < 65536: frame += bytes([0x80 | 126]) + struct.pack(">H", ln)
    else: frame += bytes([0x80 | 127]) + struct.pack(">Q", ln)
    mask = os.urandom(4)
    frame += mask + bytes(b ^ mask[i % 4] for i, b in enumerate(bmsg))
    s.sendall(frame)
    # lê frames até achar id
    s.settimeout(25)
    buf = b""
    def read(n):
        d = b""
        while len(d) < n:
            c = s.recv(n - len(d))
            if not c: break
            d += c
        return d
    out = []
    start = time.time()
    while time.time() - start < 25:
        h = read(2)
        if len(h) < 2: break
        fin, mlen = h[0], h[1] & 0x7F
        if mlen == 126: mlen = struct.unpack(">H", read(2))[0]
        elif mlen == 127: mlen = struct.unpack(">Q", read(8))[0]
        payload = read(mlen) if mlen else b""
        try:
            data = j.loads(payload.decode("utf-8", "replace"))
        except Exception:
            continue
        if data.get("id") == mid:
            s.close()
            return data
        out.append(data)
    s.close()
    return {"id": mid, "events_during_wait": len(out), "note": "sem resposta id — ver eventos"}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=None)
    ap.add_argument("--headless", action="store_true", default=True)
    ap.add_argument("--no-headless", dest="headless", action="store_false")
    ap.add_argument("--dump-all-targets", action="store_true", default=False)
    args = ap.parse_args()
    ensure_outdir()
    print(f"[OUT] {OUTDIR}")
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("playwright python ausente"); sys.exit(2)

    # Mata chrome CDP antigo; playwright gerencia o próprio chromium com CDP interno,
    # mas também expomos um Chrome real na porta CDP para inspeção avançada paralela.
    chrome_proc = None
    try:
        chrome_proc = launch_chrome(headless=args.headless)
    except Exception as e:
        print(f"[CDP] chrome paralelo indisponível: {e} (segue só playwright)")

    urls = [args.url] if args.url else ([*TARGETS.values()] if args.dump_all_targets else [TARGETS["browse_filmes"], TARGETS["search_batman"]])
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=args.headless, channel="chrome",
            args=["--no-sandbox", "--disable-dev-shm-usage", "--disable-blink-features=AutomationControlled", "--lang=pt-BR"])
        ctx = browser.new_context(
            user_agent="Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/AP1A.240505.005) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36",
            locale="pt-BR", viewport={"width": 1280, "height": 800})
        page = ctx.new_page()
        netlog = []
        def on_req(r): netlog.append({"t": "req", "m": r.method, "u": r.url[:400], "rt": r.resource_type})
        def on_res(r):
            try: netlog.append({"t": "res", "code": r.status, "u": r.url[:400], "ct": (r.headers.get("content-type","")[:80])})
            except Exception: netlog.append({"t": "res", "u": r.url[:200]})
        page.on("request", on_req); page.on("response", on_res)
        cdp = ctx.new_cdp_session(page)
        try:
            cdp.send("Network.enable")
            cdp.send("DOM.enable"); cdp.send("Runtime.enable")
        except Exception as e:
            print(f"[CDP sess] enable parcial: {e}")

        for url in urls:
            slug = re.sub(r"[^a-z0-9]+", "_", url.split("://",1)[-1].lower())[:80]
            netlog.clear()
            print(f"\n===== NAV {url} =====")
            t0 = time.time()
            try:
                resp = page.goto(url, wait_until="domcontentloaded", timeout=45000)
                print(f"[NAV] status={resp.status if resp else '?'} dt={time.time()-t0:.1f}s title={(page.title() or '')[:100]}")
            except Exception as e:
                print(f"[NAV] ERRO: {str(e)[:300]}")
            # espera render paulatinamente (search.php tem índices assíncronos final_mapa*.txt)
            for wait_ms in (4000, 4000, 4000):
                time.sleep(wait_ms/1000)
                try:
                    st = page.evaluate("""() => ({ready: document.readyState,
                      searchInput: (document.querySelector('#search-input')||{}).value ?? null,
                      readyFlag: document.documentElement.getAttribute('data-cs-search-ready'),
                      cards: document.querySelectorAll('#pm-grid > li, li.pm-li-video, article.pm-video-item, .pm-video-thumb').length,
                      listagem: document.querySelectorAll('.listagem > div').length,
                      iframes: [...document.querySelectorAll('iframe')].map(f=>(f.src||f.getAttribute('data-src')||'').slice(0,160)),
                      challenge: /Just a moment|Checking your browser|cf-chl|challenge-platform/i.test(document.documentElement.innerHTML.slice(0,20000))})""")
                    print(f"[DOM] {json.dumps(st)[:800]}")
                    if st.get("cards",0) > 0 or st.get("listagem",0) > 0: break
                except Exception as e:
                    print(f"[DOM] eval falhou: {str(e)[:150]}"); break
            # dump estrutural
            try:
                struct = page.evaluate("""() => {
                  const q = s => [...document.querySelectorAll(s)].length;
                  const first = [...document.querySelectorAll('#pm-grid > li, li.pm-li-video')].slice(0,3).map(li=>{
                    const a = li.querySelector('a[href]'); const img = li.querySelector('img');
                    return {href:(a&&(a.getAttribute('href')||'')).slice(0,160), title:((a&&(a.getAttribute('title')||a.textContent))||'').trim().slice(0,100),
                            img:((img&&(img.getAttribute('data-echo')||img.getAttribute('data-src')||img.getAttribute('src')))||'').slice(0,160)};
                  });
                  const forms=[...document.querySelectorAll('form')].map(f=>({action:(f.action||'').slice(0,160), inputs:[...f.querySelectorAll('input')].map(i=>(i.name||i.id||'').slice(0,40)+':'+(i.value||'').slice(0,40))}));
                  const scripts=[...document.querySelectorAll('script[src]')].map(s=>s.src.slice(0,140)).slice(0,15);
                  return {counts:{pmGrid:q('#pm-grid > li'), pmLiVideo:q('li.pm-li-video'), article:q('article.pm-video-item'), category:q('.pm-category-browse li'), listagem:q('.listagem > div'), pagination:q('.pagination a'), searchSuggest:q('.pm-search-suggestion')},
                          first, forms:forms.slice(0,5), scripts,
                          iframes:[...document.querySelectorAll('iframe')].map(f=>(f.src||f.getAttribute('data-src')||'').slice(0,200)).slice(0,10),
                          cookies: document.cookie.slice(0,200)};
                }""")
            except Exception as e:
                struct = {"error": str(e)[:200]}
            html = ""
            try: html = page.content()
            except Exception: pass
            with open(f"{OUTDIR}/{slug}.struct.json", "w") as f: json.dump({"url": url, "ts": ts, "struct": struct}, f, indent=1, ensure_ascii=False)
            with open(f"{OUTDIR}/{slug}.net.json", "w") as f: json.dump(netlog, f, indent=1)
            with open(f"{OUTDIR}/{slug}.html", "w") as f: f.write(html)
            # resumo por host/status
            codes = {}
            for e in netlog:
                if e["t"]=="res": codes[e.get("code","?")] = codes.get(e.get("code","?"),0)+1
            print(f"[NET] {len(netlog)} eventos codes={codes}")
            for e in netlog[:12]: print("  ", str(e)[:220])
            sw = [e for e in netlog if "serverforms" in e.get("u","") or "redirect.api" in e.get("u","") or "__RC__" in e.get("u","") or "final_mapa" in e.get("u","")]
            print(f"[NET-KEY] serverforms/redirect/__RC__/final_mapa: {len(sw)}")
            for e in sw[:20]: print("  ", str(e)[:260])

            # TESTE DE CLIQUE no player quando for página de detalhe/watch/server
            if any(k in url for k in ("episodio", "watch.php", "server.php", "embed.php", "play.php", "filme", "video_")):
                print("[CLICK] procurando recap/captcha_button e <video>...")
                try:
                    probe = page.evaluate("""() => {
                      const btn = document.querySelector('#submit, .captcha_button, button');
                      const r = btn ? btn.getBoundingClientRect() : null;
                      const v = document.querySelector('video');
                      return {btn: btn?{tag:btn.tagName,id:btn.id,cls:(btn.className||'').toString().slice(0,60),txt:(btn.textContent||'').slice(0,60),rect:r?{x:r.x,y:r.y,w:r.width,h:r.height}:null}:null,
                              video: v?{src:(v.currentSrc||v.src||'').slice(0,200),ready:v.readyState}:null,
                              rcFn: typeof window.rcPreloadPlayer, forms: document.querySelectorAll('form').length};
                    }""")
                    print(f"[CLICK-PROBE] {json.dumps(probe)[:600]}")
                    with open(f"{OUTDIR}/{slug}.click-probe.json","w") as f: json.dump(probe,f,indent=1)
                    btn = page.query_selector("#submit, .captcha_button")
                    if btn:
                        print("[CLICK] clicando recap...")
                        btn.click(timeout=8000)
                        time.sleep(5)
                        after = page.evaluate("""() => { const v=document.querySelector('video');
                          const perf=[...performance.getEntriesByType('resource')].filter(r=>/__RC__|serverforms|redirect\\.api|\\.mp4|\\.m3u8/i.test(r.name)).map(r=>({u:r.name.slice(0,220),s:r.responseStatus||r.transferSize}));
                          return {video: v?{src:(v.currentSrc||v.src||'').slice(0,250),ready:v.readyState}:null, perf:perf.slice(0,20),
                                  rcUrls: [...document.documentElement.innerHTML.matchAll(/https?:[^\\s\"']*(?:__RC__|serverforms|redirect\\.api)[^\\s\"']*/gi)].map(m=>m[0].slice(0,220)).slice(0,10)}; }""")
                        print(f"[CLICK-AFTER] {json.dumps(after)[:1500]}")
                        with open(f"{OUTDIR}/{slug}.click-after.json","w") as f: json.dump(after,f,indent=1)
                    else:
                        print("[CLICK] sem botão recap nesta página (esperado em listagem)")
                except Exception as e:
                    print(f"[CLICK] erro: {str(e)[:250]}")
            try: page.screenshot(path=f"{OUTDIR}/{slug}.png")
            except Exception as e: print(f"[SHOT] {str(e)[:120]}")
        browser.close()
    if chrome_proc:
        print(f"[CDP] Chrome real ficou aberto p/ inspeção: http://127.0.0.1:{CDP_PORT}/json/list (pid {chrome_proc.pid})")
    print(f"\n[OK] dumps em {OUTDIR}/")
    return 0

if __name__ == "__main__":
    sys.exit(main())
