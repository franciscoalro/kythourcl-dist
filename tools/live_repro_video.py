#!/usr/bin/env python3
"""Reproduz UM vídeo do site de ponta a ponta via browser real (CDP) e dumpa bytes.
Fluxo: server.php --(bundle.js)--> serverforms.api init+resolve --> __RC__/proxy --> MP4 (206).
Como o challenge bloqueia o lab hoje, usa sessão via contexto persistente + clique recap real,
captura a URL __RC__/proxy no contexto da página e baixa bytes via page.request (mesmo TLS/JA3).
Fallback documentado: 520/403 fora do TLS emissor.

Uso: DISPLAY=:99 python3 tools/live_repro_video.py [--server RCFServer3] [--vid CAPTAMRC3LEG] [--timeout 120]
Saída: /tmp/rc-repro/*.{json,mp4,log} + veredito ffprobe.
"""
import argparse, base64, json, os, re, subprocess, sys, time, urllib.parse
from datetime import datetime, timezone

OUTDIR = "/tmp/rc-repro"
UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/AP1A.240505.005) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36"

def log(*a):
    msg = " ".join(str(x) for x in a)
    print(msg, flush=True)
    with open(f"{OUTDIR}/repro.log", "a") as f: f.write(msg + "\n")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="RCFServer3")
    ap.add_argument("--vid", default="CAPTAMRC3LEG")
    ap.add_argument("--timeout", type=int, default=120)
    ap.add_argument("--headless", action="store_true", default=False)
    a = ap.parse_args()
    os.makedirs(OUTDIR, exist_ok=True)
    open(f"{OUTDIR}/repro.log", "w").write(f"start {datetime.now(timezone.utc).isoformat()} server={a.server} vid={a.vid}\n")
    from playwright.sync_api import sync_playwright
    server_url = f"https://redecanais.af/player3/server.php?categoria=vod&server={a.server}&subfolder=ondemand&vid={a.vid}"
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=a.headless, channel="chrome",
            args=["--no-sandbox", "--disable-dev-shm-usage", "--disable-blink-features=AutomationControlled",
                  "--lang=pt-BR", "--window-size=1280,900", "--autoplay-policy=no-user-gesture-required"])
        ctx = browser.new_context(user_agent=UA, locale="pt-BR", viewport={"width": 1280, "height": 900})
        page = ctx.new_page()
        events = []
        rc_urls, mp4_urls, api_bodies = [], [], {}
        def on_req(r):
            u = r.url
            events.append({"t": "req", "m": r.method, "u": u[:500], "rt": r.resource_type})
            if "__RC__/proxy" in u and u not in rc_urls: rc_urls.append(u); log("[RC] __RC__/proxy:", u[:300])
            if re.search(r"\.mp4(\?|&|$)", u) and u not in mp4_urls: mp4_urls.append(u); log("[MP4-URL]", u[:300])
        def on_res(r):
            try:
                events.append({"t": "res", "code": r.status, "u": r.url[:500], "ct": r.headers.get("content-type", "")[:80]})
                if "serverforms.api" in r.url and r.status == 200:
                    log("[API]", r.status, r.url[:200])
            except Exception: pass
        page.on("request", on_req); page.on("response", on_res)
        log("NAV", server_url)
        try:
            resp = page.goto(server_url, wait_until="domcontentloaded", timeout=30000)
            log("NAV status:", resp.status if resp else "?")
        except Exception as e:
            log("NAV ERRO:", str(e)[:300])
        # 1. estado inicial + desafio?
        try:
            st0 = page.evaluate("""() => ({title: document.title, len: document.documentElement.outerHTML.length,
              chall: /Just a moment|Um momento|Attention Required|challenge-platform/i.test(document.documentElement.outerHTML.slice(0,30000))})""")
            log("STATE0:", json.dumps(st0)[:300])
        except Exception as e: log("STATE0 erro:", str(e)[:150])
        # 2. espera bundle.js montar player: poll de recap/botão/video por até timeout
        btn_found, clicked = None, 0
        t0 = time.time()
        while time.time() - t0 < a.timeout:
            time.sleep(5)
            try:
                st = page.evaluate("""() => {
                  const btn = document.querySelector('#submit, .captcha_button, button.btn, input[type=button], .play-btn, #player button');
                  const v = document.querySelector('video');
                  const perf = [...performance.getEntriesByType('resource')].filter(r=>/serverforms|__RC__|\\.mp4|\\.m3u8|redirect\\.api/i.test(r.name)).map(r=>r.name.slice(0,220));
                  return {btn: btn?{tag:btn.tagName,id:btn.id||'',cls:((btn.className||'').toString()||'').slice(0,80),txt:(btn.textContent||btn.value||'').slice(0,80)}:null,
                    video: v?{ready:v.readyState,src:(v.currentSrc||v.src||'').slice(0,250)}:null, perf:perf.slice(0,12),
                    title:document.title.slice(0,60), n_api:[...document.documentElement.outerHTML.matchAll(/serverforms\\.api[^\\s\"']{0,80}/g)].length};
                }""")
            except Exception as e:
                log("POLL eval erro:", str(e)[:150]); break
            log(f"POLL t+{time.time()-t0:.0f}s btn={json.dumps(st.get('btn'))[:160]} video={json.dumps(st.get('video'))[:200]} perf={len(st.get('perf',[]))}")
            for p in st.get("perf", []): log("   PERF:", p[:220])
            if st.get("btn") and clicked < 3:
                try:
                    sel = "#submit" if page.query_selector("#submit") else (".captcha_button" if page.query_selector(".captcha_button") else "button")
                    log("CLICK recap:", sel)
                    page.click(sel, timeout=8000)
                    clicked += 1
                    time.sleep(6)
                    # tenta video.play() no contexto (autoplay policy liberada)
                    try:
                        pr = page.evaluate("""() => { const v=document.querySelector('video'); if(!v) return 'no-video';
                          const p=v.play(); return p?'play-called':'play-sync'; }""")
                        log("PLAY:", pr)
                    except Exception as e: log("PLAY erro:", str(e)[:150])
                except Exception as e: log("CLICK erro:", str(e)[:200])
            if rc_urls or mp4_urls:
                log(f"SUCESSO rede: rc={len(rc_urls)} mp4={len(mp4_urls)} — indo ao dump de bytes")
                break
        # 3. dump de corpos serverforms via request no contexto (mesmo TLS)
        try:
            bodies = page.evaluate("""async () => {
              const out = {};
              const urls = [...performance.getEntriesByType('resource')].map(r=>r.name).filter(u=>/serverforms\\.api/i.test(u)).slice(-4);
              for (const u of urls) {
                try { const r = await fetch(u, {credentials:'include'}); out[u.slice(0,120)] = (await r.text()).slice(0,500); }
                catch(e){ out[u.slice(0,120)] = 'FETCH_ERR:'+e; }
              }
              return {urls: urls.map(u=>u.slice(0,250)), out};
            }""")
            log("BODIES:", json.dumps(bodies)[:2000])
            with open(f"{OUTDIR}/api_bodies.json", "w") as f: json.dump(bodies, f, indent=1)
        except Exception as e: log("BODIES erro:", str(e)[:200])
        # 4. DUMP DE BYTES via page.request (mesmo contexto TLS/JA3 do browser!) + via fetch+base64
        dumped = None
        for u in (rc_urls + mp4_urls)[:4]:
            log("DUMP tentativa:", u[:200])
            try:
                r = ctx.request.get(u, headers={"Referer": server_url, "Range": "bytes=0-1048575"}, timeout=30000)
                body = r.body()
                log(f"DUMP status={r.status} bytes={len(body)} ct={r.headers.get('content-type','')[:60]}")
                if r.status in (200, 206) and len(body) > 10000:
                    with open(f"{OUTDIR}/video_dump.mp4", "wb") as f: f.write(body)
                    dumped = {"url": u[:300], "status": r.status, "bytes": len(body),
                              "head_hex": body[:32].hex(), "ftyp": (b"ftyp" in body[:32]),
                              "mp4_box": body[4:8].decode("latin1", "replace") if len(body) > 8 else ""}
                    log("DUMP OK:", json.dumps(dumped)[:400])
                    break
                else:
                    log("DUMP corpo:", body[:200])
            except Exception as e: log("DUMP erro:", str(e)[:250])
        # 4b. via <video>.currentSrc direto (se o player montou o elemento)
        if not dumped:
            try:
                vsrc = page.evaluate("() => { const v=document.querySelector('video'); return v?(v.currentSrc||v.src||''):''; }")
                log("VIDEO SRC:", (vsrc or "")[:300])
                if vsrc:
                    r = ctx.request.get(vsrc, headers={"Referer": server_url, "Range": "bytes=0-1048575"}, timeout=30000)
                    body = r.body()
                    log(f"VSRC status={r.status} bytes={len(body)}")
                    if len(body) > 10000:
                        with open(f"{OUTDIR}/video_dump.mp4", "wb") as f: f.write(body)
                        dumped = {"url": vsrc[:300], "status": r.status, "bytes": len(body), "head_hex": body[:32].hex()}
            except Exception as e: log("VSRC erro:", str(e)[:200])
        # 5. ffprobe no dump
        probe = None
        if dumped:
            try:
                pr = subprocess.run(["ffprobe", "-v", "error", "-show_entries",
                                     "format=format_name,duration,size:stream=codec_name,width,height",
                                     "-of", "json", f"{OUTDIR}/video_dump.mp4"],
                                    capture_output=True, text=True, timeout=30)
                probe = pr.stdout[:1500] or pr.stderr[:500]
                log("FFPROBE:", probe[:800])
            except FileNotFoundError: log("FFPROBE: ffprobe ausente")
            except Exception as e: log("FFPROBE erro:", str(e)[:150])
        with open(f"{OUTDIR}/events.json", "w") as f: json.dump(events, f, indent=1)
        with open(f"{OUTDIR}/veredito.json", "w") as f:
            json.dump({"server_url": server_url, "clicks": clicked, "rc_urls": [u[:300] for u in rc_urls],
                       "mp4_urls": [u[:300] for u in mp4_urls], "dumped": dumped, "ffprobe": probe,
                       "ts": datetime.now(timezone.utc).isoformat()}, f, indent=1)
        try: page.screenshot(path=f"{OUTDIR}/player.png")
        except Exception: pass
        browser.close()
    log("FIM. dumped =", bool(dumped))
    return 0 if dumped else 1

if __name__ == "__main__":
    sys.exit(main())
