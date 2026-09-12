#!/data/data/com.termux/files/usr/bin/bash
# rc-tudo-v2.sh — TUDO EM 1 SCRIPT (corrigido).
# ERRO ANTIGO: `ssh -D 10880` no celular cria um SOCKS que SAI PELA VPS
# (tráfego: celular -> VPS -> internet = Hetzner). Era um loop!
# CORREÇÃO: SOCKS em Python com saída DIRETA pela rádio/Wi-Fi do celular,
# + `ssh -R` (sem -D) levando a VPS até ele.
# Uso: ./rc-tudo-v2.sh

VPS="root@167.233.60.72"
LOCAL_SOCKS=10880
REMOTE_PORT=19053

say() { echo ">>> $*"; }

# --- 0. Python presente? ---
if ! command -v python3 >/dev/null 2>&1; then
  say "instalando python..."
  pkg install -y python
fi

# --- 1. Egresso direto (bruto, sem grep, para ver falha) ---
say "1/5 egresso DIRETO (bruto):"
curl -s -m 15 https://ipinfo.io/json 2>&1 | head -c 200; echo ""
echo "    --- segunda fonte:"
curl -s -m 15 https://api.ipify.org 2>&1 | head -c 100; echo ""

# --- 2. Limpa tudo velho ---
say "2/5 limpando SSH e SOCKS velhos:"
pkill -9 -f "ssh.*$REMOTE_PORT" 2>/dev/null
pkill -9 -f "socks5_direct" 2>/dev/null
pkill -9 -f "ssh.*-D $LOCAL_SOCKS" 2>/dev/null
sleep 2
echo "    limpo."

# --- 3. Escreve o SOCKS de saída direta ---
say "3/5 escrevendo SOCKS direto..."
cat > socks5_direct.py <<'PYEOF'
"""SOCKS5 CONNECT com saida DIRETA (roda no celular, resolve e conecta pela rede local)."""
import socket, threading, select, sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 10880

def relay(a, b):
    try:
        while True:
            r, _, _ = select.select([a, b], [], [], 60)
            if not r: break
            for s in r:
                o = b if s is a else a
                try: d = s.recv(65536)
                except OSError: return
                if not d: return
                try: o.sendall(d)
                except OSError: return
    finally:
        for s in (a, b):
            try: s.close()
            except OSError: pass

def handle(c):
    try:
        c.settimeout(15)
        h = c.recv(2)
        if len(h) < 2 or h[0] != 5: c.close(); return
        c.recv(h[1])  # methods
        c.sendall(b'\x05\x00')  # sem auth
        r = c.recv(4)
        if len(r) < 4 or r[1] != 1:  # só CONNECT
            c.sendall(b'\x05\x07\x00\x01\x00\x00\x00\x00\x00\x00'); c.close(); return
        atyp = r[3]
        if atyp == 1:
            host = socket.inet_ntoa(c.recv(4))
        elif atyp == 3:
            ln = c.recv(1)[0]
            host = c.recv(ln).decode()
        elif atyp == 4:
            host = socket.inet_ntop(socket.AF_INET6, c.recv(16))
        else:
            c.sendall(b'\x05\x08\x00\x01\x00\x00\x00\x00\x00\x00'); c.close(); return
        port = int.from_bytes(c.recv(2), 'big')
        try:
            # resolve + conecta DIRETO pela rede do celular
            info = socket.getaddrinfo(host, port, socket.AF_UNSPEC, socket.SOCK_STREAM)
            out = None
            err = None
            for fam, st, pr, _, sa in info:
                try:
                    out = socket.socket(fam, st, pr)
                    out.settimeout(15)
                    out.connect(sa)
                    break
                except OSError as e:
                    err = e
                    try: out.close()
                    except Exception: pass
                    out = None
            if out is None:
                c.sendall(b'\x05\x04\x00\x01\x00\x00\x00\x00\x00\x00'); c.close(); return
        except Exception:
            c.sendall(b'\x05\x04\x00\x01\x00\x00\x00\x00\x00\x00'); c.close(); return
        c.sendall(b'\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00')
        c.settimeout(None)
        relay(c, out)
    except Exception:
        try: c.close()
        except Exception: pass

srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('127.0.0.1', PORT))
srv.listen(50)
print(f'SOCKS direto na porta {PORT}', flush=True)
while True:
    c, _ = srv.accept()
    threading.Thread(target=handle, args=(c,), daemon=True).start()
PYEOF
echo "    escrito."

# --- 4. Sobe o SOCKS direto em fundo ---
say "4/5 subindo SOCKS direto :$LOCAL_SOCKS..."
nohup python3 socks5_direct.py $LOCAL_SOCKS > socks.log 2>&1 &
sleep 2
cat socks.log 2>/dev/null | head -2
say "teste do SOCKS local (tem que dar SEU IP, nao Hetzner):"
curl -s -m 15 --socks5-hostname 127.0.0.1:$LOCAL_SOCKS https://api.ipify.org 2>&1 | head -c 100; echo ""

# --- 5. Túnel reverso SEM -D (só -R) com auto-reconnect ---
say "5/5 subindo reverso -R $REMOTE_PORT (Ctrl+C sai; reconecta sozinho):"
termux-wake-lock 2>/dev/null
n=0
while true; do
  n=$((n+1))
  echo "[tentativa $n] $(date '+%H:%M:%S')"
  ssh -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes \
      -N -R $REMOTE_PORT:localhost:$LOCAL_SOCKS "$VPS"
  echo "[caiu] reconectando em 5s..."
  sleep 5
done
