#!/usr/bin/env python3
"""Fase 2: CDP avançado — resolve Turnstile (headed via Xvfb + clique real) e dumpa DOM/cookies/rede.
Gera: /tmp/rc-live-sim2/*.struct.json, *.net.json, *.html, cookies.json + relatório.
Uso: Xvfb :99 & ; DISPLAY=:99 python3 tools/live_cdp_sim2.py [--url URL|--all] [--no-click]
"""
import argparse, json, os, re, subprocess, sys, time
from datetime import datetime, timezone

OUTDIR = "/tmp/rc-live-sim2"
UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/AP1A.240505.005) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36"
TARGETS = {
    "home": "https://redecanais.af/",
    "browse_filmes": "https://redecanais.af/browse-filmes-videos-1-date.html",
    "browse_series": "https://redecanais.af/browse-series-videos-1-date.html",
    "search_batman": "https://redecanais.af/search.php?keywords=Batman",
    "detail_batman": "https://redecanais.af/a-sombra-do-batman-episodio-01-cacados_a8b6b81b2.html",
    "server_rcf2": "https://redecanais.af/player3/server.php?categoria=vod&server=RCFServer2&subfolder=ondemand&vid=ASMBRDBTMNEP01",
}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=None)
    ap.add_argument("--all", action="store_true", default=False)
    ap.add_argument("--no-click", dest="click", action="store_false", default=True)
    ap.add_argument("--headless", action="store_true", default=False)
    ap.add_argument("--timeout", type=int, default=90)
    a = ap.parse_args()
    os.makedirs(OUTDIR, exist_ok=True)
    from playwright.sync_api import sync_playwright
    urls = [a.url] if a.url else (list(TARGETS.values()) if a.all else [TARGETS["browse_filmes"], TARGETS["search_batman"]])
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=a.headless, channel="chrome",
            args=["--no-sandbox", "--disable-dev-shm-usage", "--disable-blink-features=AutomationControlled",
                  "--lang=pt-BR", "--window-size=1280,900"])
        ctx = browser.new_context(user_agent=UA, locale="pt-BR", viewport={"width": 1280, "height": 900})
        page = ctx.new_page()
        netlog = []
        page.on("request", lambda r: netlog.append({"t": "req", "m": r.method, "u": r.url[:400], "rt": r.resource_type}))
        def on_res(r):
            try: netlog.append({"t": "res", "code": r.status, "u": r.url[:400], "ct": (r.headers.get("content-type", "")[:90])})
            except Exception: pass
        page.on("response", on_res)
        cdp = ctx.new_cdp_session(page)
        for m in ("Network.enable", "DOM.enable", "Runtime.enable"):
            try: cdp.send(m)
            except Exception as e: print(f"[CDP] {m}: {e}")
        for url in urls:
            slug = re.sub(r"[^a-z0-9]+", "_", url.split("://", 1)[-1].lower())[:80]
            netlog.clear()
            print(f"\n===== NAV {url} =====")
            t0 = time.time()
            try:
                resp = page.goto(url, wait_until="domcontentloaded", timeout=30000)
                print(f"[NAV] status={resp.status if resp else '?'} dt={time.time()-t0:.1f}s")
            except Exception as e:
                print(f"[NAV] ERRO: {str(e)[:250]}")
            # Loop Turnstile: espera até 90s, tenta clique no checkbox quando widget aparece
            solved, clicked = False, False
            for i in range(a.timeout // 5):
                time.sleep(5)
                try:
                    st = page.evaluate("""() => {
                      const html = document.documentElement.outerHTML;
                      const turnstile = !!document.querySelector('iframe[src*=\"challenges.cloudflare.com\"], .cf-turnstile, [id*=\"cf-chl\"]');
                      const cards = document.querySelectorAll('#pm-grid > li, li.pm-li-video, article.pm-video-item, .pm-video-thumb').length;
                      const listagem = document.querySelectorAll('.listagem > div').length;
                      const challenge = /Just a moment|Checking your browser|challenge-platform/i.test(html.slice(0,30000));
                      const ifr = [...document.querySelectorAll('iframe')].map(f=>{const r=f.getBoundingClientRect();return {src:(f.src||'').slice(0,120),x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)}});
                      return {ready:document.readyState, title:document.title.slice(0,80), turnstile, cards, listagem, challenge,
                              cookies:document.cookie.slice(0,120), iframes:ifr.slice(0,6), len:html.length};
                    }""")
                except Exception as e:
                    print(f"[POLL] eval erro: {str(e)[:150]}"); break
                print(f"[POLL {i}] title={st.get('title')} turnstile={st.get('turnstile')} cards={st.get('cards')} list={st.get('listagem')} chall={st.get('challenge')} len={st.get('len')}")
                if not st.get("challenge") and (st.get("cards", 0) > 0 or st.get("listagem", 0) > 0 or st.get("len", 0) > 60000):
                    solved = True; break
                if a.click and st.get("turnstile") and not clicked:
                    # Tenta clique no centro do iframe do turnstile via mouse real (headed)
                    try:
                        box = page.evaluate("""() => {
                          const f = document.querySelector('iframe[src*=\"challenges.cloudflare.com\"]');
                          if(!f) return null; const r=f.getBoundingClientRect(); return {x:r.x+36,y:r.y+r.height/2};
                        }""")
                        if box:
                            print(f"[CLICK] turnstile em x={box['x']:.0f} y={box['y']:.0f} — mouse real")
                            page.mouse.move(box["x"], box["y"]); time.sleep(0.5)
                            page.mouse.click(box["x"], box["y"]); clicked = True
                            print("[CLICK] clique disparado, aguardando validação...")
                    except Exception as e:
                        print(f"[CLICK] erro: {str(e)[:200]}")
            print(f"[RESULT] solved={solved} clicked={clicked}")
            # Dump estrutural completo
            try:
                struct = page.evaluate("""() => {
                  const q = s => [...document.querySelectorAll(s)].length;
                  const cards=[...document.querySelectorAll('#pm-grid > li, li.pm-li-video, article.pm-video-item, .pm-video-thumb')].slice(0,5).map(li=>{
                    const a=li.querySelector('a[href]'); const img=li.querySelector('img');
                    return {href:((a&&(a.getAttribute('href')||''))||'').slice(0,180), title:(((a&&(a.getAttribute('title')||a.textContent))||'').trim()).slice(0,110),
                      img:(((img&&(img.getAttribute('data-echo')||img.getAttribute('data-src')||img.getAttribute('src')))||'')).slice(0,180)};});
                  const list=[...document.querySelectorAll('.listagem > div')].slice(0,5).map(d=>({a:(d.querySelector('a')||{}).href?'':(d.textContent||'').trim().slice(0,110), html:d.innerHTML.slice(0,200)}));
                  return {counts:{pmGrid:q('#pm-grid > li'),pmLiVideo:q('li.pm-li-video'),article:q('article.pm-video-item'),thumb:q('.pm-video-thumb'),listagem:q('.listagem > div'),pagination:q('.pagination a'),iframes:q('iframe'),forms:q('form')},
                    cards, list,
                    iframes:[...document.querySelectorAll('iframe')].map(f=>(f.src||f.getAttribute('data-src')||'').slice(0,220)).slice(0,10),
                    scripts:[...document.querySelectorAll('script[src]')].map(s=>s.src.slice(0,150)).slice(0,15),
                    title:document.title, cookies:document.cookie.slice(0,250)};
                }""")
            except Exception as e:
                struct = {"error": str(e)[:200]}
            html = ""
            try: html = page.content()
            except Exception: pass
            cookies = []
            try: cookies = ctx.cookies()
            except Exception: pass
            cookiev = [{"name": c["name"], "domain": c.get("domain"), "len": len(c.get("value", ""))} for c in cookies]
            with open(f"{OUTDIR}/{slug}.struct.json", "w") as f: json.dump({"url": url, "ts": ts, "solved": solved, "struct": struct, "cookies": cookiev}, f, indent=1, ensure_ascii=False)
            with open(f"{OUTDIR}/{slug}.net.json", "w") as f: json.dump(netlog, f, indent=1)
            with open(f"{OUTDIR}/{slug}.html", "w") as f: f.write(html)
            codes = {}
            for e in netlog:
                if e["t"] == "res": codes[e.get("code", "?")] = codes.get(e.get("code", "?"), 0) + 1
            key = [e for e in netlog if any(k in e.get("u", "") for k in ("serverforms", "redirect.api", "__RC__", "final_mapa", "bundle.js", "query.js", "dt.api"))]
            print(f"[NET] {len(netlog)} ev codes={codes} key={len(key)} cookies={cookiev}")
            for e in key[:25]: print("   ", str(e)[:240])
            try: page.screenshot(path=f"{OUTDIR}/{slug}.png")
            except Exception as e: print(f"[SHOT] {str(e)[:120]}")
        browser.close()
    print(f"\n[OK] dumps em {OUTDIR}/")
    return 0

if __name__ == "__main__":
    sys.exit(main())
