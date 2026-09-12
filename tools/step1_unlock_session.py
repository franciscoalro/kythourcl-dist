#!/usr/bin/env python3
"""PASSO 1 — Desbloquear 1 sessão Cloudflare válida (perfil persistente + clique assistido/auto).
- Chrome headed (Xvfb :99) com perfil persistente /tmp/rc-cdp-profile + remote-debugging 19222.
- Navega ao catálogo, faz poll do Turnstile e tenta clique real (CDP Input.dispatchMouseEvent
  no rect do iframe, mesma técnica do plugin) por até --minutes minutos.
- Sucesso = cf_clearance no jar + cards>0. Salva /tmp/rc-session/cookies.json + home.html.
Uso: DISPLAY=:99 nohup python3 tools/step1_unlock_session.py --minutes 12 > /tmp/step1.log 2>&1 &
"""
import argparse, json, os, subprocess, sys, time
from datetime import datetime, timezone

PROFILE = "/tmp/rc-cdp-profile"
CDP_PORT = 19222
SESSDIR = "/tmp/rc-session"
UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/AP1A.240505.005) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36"
URL = "https://redecanais.af/browse-filmes-videos-1-date.html"

def log(*a):
    m = " ".join(str(x) for x in a)
    print(m, flush=True)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=int, default=12)
    a = ap.parse_args()
    os.makedirs(SESSDIR, exist_ok=True)
    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
        browser = pw.chromium.launch_persistent_context(
            PROFILE, headless=False, channel="chrome",
            args=["--no-sandbox", "--disable-dev-shm-usage", "--disable-blink-features=AutomationControlled",
                  "--lang=pt-BR", f"--remote-debugging-port={CDP_PORT}", "--window-size=1280,900"],
            user_agent=UA, locale="pt-BR", viewport={"width": 1280, "height": 900})
        page = browser.pages[0] if browser.pages else browser.new_page()
        log("NAV", URL)
        try:
            page.goto(URL, wait_until="domcontentloaded", timeout=30000)
        except Exception as e:
            log("NAV erro:", str(e)[:200])
        cdp = browser.new_cdp_session(page)
        try: cdp.send("Input.enable")
        except Exception: pass
        deadline = time.time() + a.minutes * 60
        clicks, ok = 0, False
        i = 0
        while time.time() < deadline:
            i += 1
            time.sleep(10)
            try:
                st = page.evaluate("""() => {
                  const ifr = [...document.querySelectorAll('iframe')].map(f=>{const r=f.getBoundingClientRect();
                    return {src:(f.src||'').slice(0,80),x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)}});
                  const html = document.documentElement.outerHTML;
                  return {title:document.title.slice(0,60),
                    chall:/Just a moment|Um momento|challenge-platform/i.test(html.slice(0,30000)),
                    cards:document.querySelectorAll('#pm-grid > li, li.pm-li-video').length,
                    len:html.length, ifr};
                }""")
            except Exception as e:
                log(f"[{i}] eval erro:", str(e)[:150]); continue
            cks = browser.cookies()
            names = [c["name"] for c in cks]
            log(f"[{i}] title={st['title']} chall={st['chall']} cards={st['cards']} len={st['len']} cookies={names} ifr={json.dumps(st['ifr'])[:220]}")
            if "cf_clearance" in names and not st["chall"] and st["cards"] > 0:
                ok = True
                break
            # tenta clique no widget (iframe grande, y>=150) via mouse real CDP
            tgt = next((f for f in st["ifr"] if f["w"] >= 150 and f["h"] >= 40 and f["y"] >= 150), None)
            if tgt and clicks < 12:
                x, y = tgt["x"] + 36, tgt["y"] + tgt["h"] // 2
                log(f"[{i}] CLICK turnstile x={x} y={y}")
                try:
                    page.mouse.move(x, y); time.sleep(0.6); page.mouse.click(x, y)
                    clicks += 1
                except Exception as e:
                    log("click erro:", str(e)[:150])
        log(f"RESULT ok={ok} clicks={clicks}")
        cks = browser.cookies()
        with open(f"{SESSDIR}/cookies.json", "w") as f:
            json.dump([{"name": c["name"], "domain": c.get("domain"), "len": len(c.get("value", ""))} for c in cks], f, indent=1)
        # salva valores reais em arquivo restrito para reuso pelo passo 2/3 (mesmo host)
        with open(f"{SESSDIR}/cookies_full.json", "w") as f:
            json.dump(cks, f)
        os.chmod(f"{SESSDIR}/cookies_full.json", 0o600)
        try:
            open(f"{SESSDIR}/home.html", "w").write(page.content())
            page.screenshot(path=f"{SESSDIR}/home.png")
        except Exception as e:
            log("dump erro:", str(e)[:150])
        with open(f"{SESSDIR}/passo1.json", "w") as f:
            json.dump({"ok": ok, "clicks": clicks, "ts": datetime.now(timezone.utc).isoformat()}, f, indent=1)
        log("Chrome segue ABERTO p/ clique assistido manual. CDP: http://127.0.0.1:19222/json/list")
        # mantém vivo mais 5 min p/ assistência manual antes de fechar
        if not ok:
            log("aguardando 5min p/ clique assistido manual...")
            time.sleep(300)
            cks = browser.cookies()
            names = [c["name"] for c in cks]
            try:
                st = page.evaluate("() => ({t:document.title, c:document.querySelectorAll('#pm-grid > li').length})")
            except Exception: st = {}
            ok = "cf_clearance" in names
            log(f"FINAL ok={ok} {st}")
            with open(f"{SESSDIR}/passo1.json", "w") as f:
                json.dump({"ok": ok, "clicks": clicks, "ts": datetime.now(timezone.utc).isoformat()}, f, indent=1)
        browser.close()
    return 0 if ok else 1

if __name__ == "__main__":
    sys.exit(main())
