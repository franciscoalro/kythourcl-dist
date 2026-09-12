#!/usr/bin/env python3
"""Dump CDP avançado DIRETO do WebView do app no Redroid (sessão cf_clearance válida!).
Pré-req: adb forward tcp:9223 localabstract:webview_devtools_remote_<PID>.
Captura: targets page+service_worker, Network.enable nos dois, reload, coleta
requestWillBeSent/responseReceived/dataReceived/ExtraInfo, clique recap se houver,
e tenta baixar 1MB do __RC__/proxy via Runtime.fetch no contexto da página.
NENHUM valor de cookie/token é publicado — só nomes/contagens/códigos.

Uso: python3 tools/live_cdp_webview_dump.py [--reload] [--click] [--fetch-mp4]
"""
import argparse, json, os, socket, struct, sys, time, base64
from datetime import datetime, timezone
from urllib.parse import urlparse
import urllib.request

OUTDIR = "/tmp/rc-webview"
CDP_HTTP = "http://127.0.0.1:9223/json/list"

def ws_call(ws_url, method, params=None, mid=1, timeout=30, retries=3):
    u = urlparse(ws_url.replace("ws://", "http://"))
    host, port, path = u.hostname, u.port, u.path or "/"
    if u.query: path += "?" + u.query
    import os as _os
    key = base64.b64encode(_os.urandom(16)).decode()
    s = socket.create_connection((host, port), timeout=15)
    s.sendall(f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode())
    hdr = b""
    while b"\r\n\r\n" not in hdr: hdr += s.recv(4096)
    if b"101" not in hdr: raise RuntimeError(f"WS handshake: {hdr[:200]}")
    msg = json.dumps({"id": mid, "method": method, "params": params or {}}).encode()
    frame = bytes([0x81])
    if len(msg) < 126: frame += bytes([0x80 | len(msg)])
    elif len(msg) < 65536: frame += bytes([0x80 | 126]) + struct.pack(">H", len(msg))
    else: frame += bytes([0x80 | 127]) + struct.pack(">Q", len(msg))
    mask = _os.urandom(4)
    s.sendall(frame + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(msg)))
    s.settimeout(timeout)
    def read(n):
        d = b""
        while len(d) < n:
            c = s.recv(n - len(d))
            if not c: break
            d += c
        return d
    evts = []
    start = time.time()
    while time.time() - start < timeout:
        h = read(2)
        if len(h) < 2: break
        mlen = h[1] & 0x7F
        if mlen == 126: mlen = struct.unpack(">H", read(2))[0]
        elif mlen == 127: mlen = struct.unpack(">Q", read(8))[0]
        payload = read(mlen) if mlen else b""
        try: data = json.loads(payload.decode("utf-8", "replace"))
        except Exception: continue
        if data.get("id") == mid:
            s.close(); return data, evts
        evts.append(data)
    s.close()
    return {"id": mid, "timeout": True}, evts

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--reload", action="store_true", default=True)
    ap.add_argument("--no-reload", dest="reload", action="store_false")
    ap.add_argument("--click", action="store_true", default=True)
    ap.add_argument("--fetch-mp4", action="store_true", default=True)
    ap.add_argument("--wait", type=int, default=45)
    a = ap.parse_args()
    os.makedirs(OUTDIR, exist_ok=True)
    with urllib.request.urlopen(CDP_HTTP, timeout=8) as r:
        targets = json.load(r)
    print(f"[CDP] {len(targets)} targets:")
    for t in targets:
        print(f"  {t['type']:15s} {t['title'][:70]} :: {t['url'][:100]}")
    page = next((t for t in targets if t["type"] == "page"), None)
    sw = next((t for t in targets if "service_worker" in t["type"]), None)
    if not page: print("sem page target"); sys.exit(2)
    ws = page["webSocketDebuggerUrl"]
    mid = 100
    def call(m, p=None, timeout=30):
        nonlocal mid; mid += 1
        return ws_call(ws, m, p, mid, timeout)
    # enable
    for m in (["Network.enable", {}], ["Page.enable", {}], ["Runtime.enable", {}], ["DOM.enable", {}]):
        r, _ = call(m[0], m[1]); print("[EN]", m[0], str(r.get("result", r))[:100])
    if sw:
        r, _ = ws_call(sw["webSocketDebuggerUrl"], "Network.enable", {}, mid + 1)
        print("[EN-SW] Network.enable", str(r.get("result", r))[:100]); mid += 1
    if a.reload:
        try:
            r, _ = call("Page.reload", {"ignoreCache": False}); print("[RELOAD]", str(r)[:150])
        except Exception as e:
            print("[RELOAD] target morreu no reload (WebView efêmero do solver) — re-listando...")
            time.sleep(3)
            with urllib.request.urlopen(CDP_HTTP, timeout=8) as rr:
                targets = json.load(rr)
            page = next((t for t in targets if t["type"] == "page"), None)
            if not page: print("sem page após reload"); sys.exit(2)
            ws = page["webSocketDebuggerUrl"]
            print("[RELIST]", page["title"][:70], page["url"][:100])
            for m in (["Network.enable", {}], ["Page.enable", {}], ["Runtime.enable", {}], ["DOM.enable", {}]):
                r, _ = call(m[0], m[1]); print("[EN2]", m[0], str(r.get("result", r))[:80])
    # poll estado + coleta eventos passivos
    all_evts, rc_urls, clicked = [], [], 0
    t0 = time.time()
    n_poll = max(1, a.wait // 5)
    for i in range(n_poll):
        time.sleep(5)
        r, ev = call("Runtime.evaluate", {"expression": """() => { const v=document.querySelector('video');
          const btn=document.querySelector('#submit, .captcha_button');
          return JSON.stringify({title:document.title.slice(0,80), btn:!!btn,
            video: v?{ready:v.readyState,src:(v.currentSrc||v.src||'').slice(0,200)}:null,
            rc: [...document.documentElement.outerHTML.matchAll(/__RC__\\/proxy\\?src=[^\\s\"']{0,120}/g)].length}); }""",
          "awaitPromise": False, "returnByValue": True}, timeout=20)
        try: st = r.get("result", {}).get("result", {}).get("value", "{}")
        except Exception: st = "{}"
        print(f"[POLL {i}] {st[:300]}")
        all_evts.extend(ev)
        for e in ev:
            if e.get("method") in ("Network.requestWillBeSent", "Network.responseReceived"):
                u = (e.get("params", {}).get("request", {}).get("url") or e.get("params", {}).get("response", {}).get("url") or "")
                if "__RC__/proxy" in u and u not in rc_urls:
                    rc_urls.append(u); print("[RC]", u[:250])
        if a.click:
            try:
                b, _ = call("Runtime.evaluate", {"expression": "!!document.querySelector('#submit, .captcha_button')", "returnByValue": True}, timeout=15)
                if b.get("result", {}).get("result", {}).get("value") and clicked < 2:
                    print("[CLICK] recap via Runtime.click → Input.dispatchMouseEvent")
                    q, _ = call("DOM.getDocument", {"depth": 2}, timeout=15)
                    # clique no centro estimado do player (320,180 CSS) via Input
                    call("Input.dispatchMouseEvent", {"type": "mousePressed", "x": 320, "y": 180, "button": "left", "clickCount": 1}, timeout=10)
                    call("Input.dispatchMouseEvent", {"type": "mouseReleased", "x": 320, "y": 180, "button": "left", "clickCount": 1}, timeout=10)
                    clicked += 1
            except Exception as e: print("[CLICK] erro:", str(e)[:150])
        if rc_urls and len(rc_urls) >= 1 and i > 2: break
    print(f"[CLICKS] {clicked} [RC] {len(rc_urls)}")
    # fetch de 1MB no contexto da página (mesmo TLS!) via Runtime
    dumped = None
    if a.fetch_mp4 and rc_urls:
        for u in rc_urls[:2]:
            print("[FETCH] ", u[:200])
            expr = f"""(async () => {{ try {{
              const r = await fetch({json.dumps(u)}, {{credentials:'include', headers:{{'Range':'bytes=0-1048575'}}}});
              const b = await r.arrayBuffer();
              const bytes = new Uint8Array(b);
              let bin=''; const CH=8192;
              for (let i=0;i<bytes.length;i+=CH) bin += String.fromCharCode.apply(null, bytes.subarray(i,i+CH));
              return JSON.stringify({{status:r.status, ct:r.headers.get('content-type'), n:bytes.length,
                head:Array.from(bytes.slice(0,32)).map(x=>x.toString(16).padStart(2,'0')).join(''),
                b64:btoa(bin).slice(0, 1400000)}});
            }} catch(e){{ return 'ERR:'+e; }} }})()"""
            r, _ = call("Runtime.evaluate", {"expression": expr, "awaitPromise": True, "returnByValue": True}, timeout=60)
            try:
                val = r.get("result", {}).get("result", {}).get("value", "")
                info = json.loads(val)
                print(f"[FETCH] status={info.get('status')} ct={info.get('ct')} n={info.get('n')} head={info.get('head')[:64]}")
                raw = base64.b64decode(info["b64"])
                if info.get("status") in (200, 206) and len(raw) > 10000:
                    open(f"{OUTDIR}/video_dump.mp4", "wb").write(raw)
                    dumped = {"status": info["status"], "bytes": len(raw), "head": info["head"][:64], "ftyp": raw[4:8].decode("latin1", "replace")}
                    print("[DUMP OK]", dumped)
                    break
                else: print("[DUMP] corpo pequeno:", raw[:120])
            except Exception as e: print("[FETCH] parse erro:", str(e)[:200], str(r)[:300])
    # resumo de rede dos eventos coletados
    codes = {}
    for e in all_evts:
        if e.get("method") == "Network.responseReceived":
            c = e.get("params", {}).get("response", {}).get("status")
            codes[c] = codes.get(c, 0) + 1
    with open(f"{OUTDIR}/events.json", "w") as f: json.dump(all_evts, f, indent=1)
    with open(f"{OUTDIR}/veredito.json", "w") as f:
        json.dump({"ts": datetime.now(timezone.utc).isoformat(), "codes": codes, "clicks": clicked,
                   "rc_urls": [u[:200] for u in rc_urls], "dumped": dumped}, f, indent=1)
    print(f"[OK] codes={codes} dumped={bool(dumped)} → {OUTDIR}/")
    return 0 if dumped else 1

if __name__ == "__main__":
    sys.exit(main())
