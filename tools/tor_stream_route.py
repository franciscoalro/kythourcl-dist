#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Sonda /v2/anime/episode/{id}/stream -- a rota que ENTREGA O VIDEO.

Achado do bytecode que o dossie anterior nao tinha:
  PlayerActivity.smali:5140-5170  monta
      getAPIHostSSL() + "/v2/anime/episode/" + episodeID + "/stream"
  PlayerActivity.smali:5175       new-instance PlayerActivity$10
  PlayerActivity.smali:5177       const/4 v2, 0x0   -> 0 = Request.Method.GET

O dossie listava /v2/anime/episode/{id} (sem /stream) como 404 e nao tinha
a rota de stream. Essa e' a rota que importa: e' o que da os links de
streaming que o CloudStream consome.
"""
import subprocess
import sys

TOR = "socks5h://127.0.0.1:9050"
HOSTS = ["prod-api.tomatoanimes.com", "edge.betomato.com"]

# (path, method) -- todos os metodos vindos do bytecode
ROUTES = [
    ("/v2/anime/episode/1/stream", "GET"),   # <-- a rota de video, GET do smali
    ("/v2/anime/episode/1/stream", "POST"),
    ("/v2/anime/1",                "GET"),
    ("/v2/anime/1/episode/1",      "GET"),
    # controle negativo
    ("/v2/anime/episode/1/streamxyz", "GET"),
]


def fetch(host, path, method, timeout=25):
    cmd = [
        "curl", "-sS", "-k", "-x", TOR,
        "-o", "/tmp/_stream_body", "-w", "%{http_code} %{size_download}",
        "--max-time", str(timeout),
        "-X", method,
    ]
    if method in ("POST", "PATCH", "PUT"):
        cmd += ["-H", "Content-Type: application/json", "-d", "{}"]
    cmd.append(f"https://{host}{path}")
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 8)
    except subprocess.TimeoutExpired:
        return ("TIMEOUT", 0, "")
    if r.returncode != 0:
        return ("ERR", 0, r.stderr.strip()[:60])
    parts = r.stdout.split()
    body = ""
    try:
        with open("/tmp/_stream_body", "r", errors="replace") as fh:
            body = fh.read(300)
    except OSError:
        pass
    return (parts[0] if parts else "000",
            parts[1] if len(parts) > 1 else "0",
            body)


def classify(code, body):
    b = body.lower()
    if code == "200":
        return "VIVA(200)"
    if code == "403" or "authentication failed" in b:
        return "EXISTE(auth) 403"
    if code == "404" and "cannot" in b:
        return "AUSENTE 404 Express"
    if code == "500":
        return "500 borda/indecifravel"
    if code in ("000", "ERR", "TIMEOUT"):
        return "sem resposta"
    return f"?{code}"


def main():
    print("=" * 76)
    print("ROTA DE VIDEO: /v2/anime/episode/{id}/stream  (GET, do bytecode)")
    print("=" * 76)
    for host in HOSTS:
        print(f"\n### {host}")
        print(f"{'rota':<36} {'met':<6} {'http':<7} {'bytes':<7} veredito")
        print("-" * 76)
        for path, method in ROUTES:
            code, size, body = fetch(host, path, method)
            print(f"{path:<36} {method:<6} {code:<7} {size:<7} {classify(code, body)}")
            if code == "200":
                print(f"{'':>32} body: {body[:200]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
