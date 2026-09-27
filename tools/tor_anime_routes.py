#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Sonda as rotas de ANIME do backend Tomato via Tor, usando o METODO correto
extraido do bytecode (PlayerActivity.smali / PlayerActivity$11.smali).

Contexto: a versao anterior do dossie marcou /v2/anime/episode/{id} como
"rota ausente (404)". Isso foi o mesmo erro de metodo que ja tinha sido
corrigido em /checkupdate: sondei GET onde o app usa POST.

Metodos confirmados no smali:
  PlayerActivity$11.smali  super = JsonObjectRequest, metodo repassado pelo caller
  PlayerActivity.smali:1482  const/4 v7, 0x1  -> 1 = Request.Method.POST
  PlayerActivity.smali:1501  TomatoApp.patchRequest(JsonObjectRequest)
"""
import json
import subprocess
import sys

TOR = "socks5h://127.0.0.1:9050"
HOSTS = ["prod-api.tomatoanimes.com", "edge.betomato.com"]

# (rota, metodo) -- metodo vem do bytecode, nao de palpite
ROUTES = [
    ("/v2/anime/1",                          "GET"),
    ("/v2/anime/1",                          "POST"),
    ("/v2/anime/1/episode/1",                "GET"),
    ("/v2/anime/1/episode/1",                "POST"),
    ("/v2/anime/1/episode/1/playheads",      "GET"),
    ("/v2/anime/1/episode/1/playheads",      "POST"),
    ("/v2/anime/1/episode/1/playheads",      "PATCH"),
    ("/v2/playheads",                        "GET"),
    ("/v2/playheads",                        "POST"),
    ("/v2/customads/get",                    "GET"),
    # controle negativo: path que NAO existe, para provar que 404 e do Express
    ("/v2/anime/inexistente_xyz",            "GET"),
    ("/v2/anime/inexistente_xyz",            "POST"),
]


def fetch(host, path, method, timeout=25):
    """Uma requisicao via Tor. Retorna (http, tamanho, corpo_ou_motivo)."""
    url = f"https://{host}{path}"
    # -k: os hosts nao tem cadeia completa no Tor; -o /dev/null mede so tamanho
    cmd = [
        "curl", "-sS", "-k", "-x", TOR,
        "-o", "/tmp/_body", "-w", "%{http_code} %{size_download}",
        "--max-time", str(timeout),
        "-X", method,
    ]
    if method in ("POST", "PATCH", "PUT"):
        cmd += ["-H", "Content-Type: application/json", "-d", "{}"]
    cmd.append(url)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 8)
    except subprocess.TimeoutExpired:
        return ("TIMEOUT", 0, "")
    if r.returncode != 0:
        return ("ERR", 0, r.stderr.strip()[:60])
    parts = r.stdout.split()
    code = parts[0] if parts else "000"
    size = parts[1] if len(parts) > 1 else "0"
    body = ""
    try:
        with open("/tmp/_body", "r", errors="replace") as fh:
            body = fh.read(220)
    except OSError:
        pass
    return (code, size, body)


def classify(code, body):
    """Mapeia resposta -> veredito. O discriminador e' o corpo, nao so o status."""
    b = body.lower()
    if code == "200":
        return "VIVA(200)"
    if code == "403" or "authentication failed" in b:
        return "EXISTE(auth) 403"
    if code == "404" and "cannot" in b:
        return "AUSENTE 404 Express"
    if code == "500":
        return "500 borda/indecifravel"
    if code == "000":
        return "sem resposta"
    if code == "TIMEOUT":
        return "tor caiu/timeout"
    return f"?{code}"


def main():
    print("=" * 74)
    print("ROTAS DE ANIME -- METODOS DO BYTECODE, VIA TOR")
    print("=" * 74)

    for host in HOSTS:
        print(f"\n### {host}")
        print(f"{'rota':<38} {'met':<6} {'http':<7} {'bytes':<7} veredito")
        print("-" * 74)
        for path, method in ROUTES:
            code, size, body = fetch(host, path, method)
            verdict = classify(code, body)
            print(f"{path:<38} {method:<6} {code:<7} {size:<7} {verdict}")
            if code == "200":
                # mostra o inicio do JSON para provar que e' conteudo real
                print(f"{'':>40} body: {body[:150]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
