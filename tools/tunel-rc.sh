#!/data/data/com.termux/files/usr/bin/bash
# tunel-rc.sh — mantém reverse SOCKS para a VPS com auto-reconnect.
# Uso: ./tunel-rc.sh   (deixe em foreground, com wake-lock ativo)
#   ./tunel-rc.sh check  (só testa o egresso local, sem conectar)

VPS="root@167.233.60.72"
LOCAL_SOCKS=10880
REMOTE_PORT=19053

check() {
  echo "--- egresso direto (sem proxy):"
  curl -s -m 15 https://ipinfo.io/json | grep -o '"ip": "[^"]*"\|"org": "[^"]*"' || echo "SEM INTERNET"
  echo "--- egresso via SOCKS local :$LOCAL_SOCKS:"
  curl -s -m 15 --socks5-hostname 127.0.0.1:$LOCAL_SOCKS https://ipinfo.io/json | grep -o '"ip": "[^"]*"\|"org": "[^"]*"' || echo "SOCKS LOCAL FORA"
}

if [ "$1" = "check" ]; then
  check
  exit 0
fi

termux-wake-lock 2>/dev/null
pkill -9 -f "ssh.*$REMOTE_PORT" 2>/dev/null
sleep 2

echo "=== teste pré-voo ==="
check
echo ""
echo "=== conectando (encerre com Ctrl+C; reconecta sozinho se cair) ==="
n=0
while true; do
  n=$((n+1))
  echo "[tentativa $n] $(date '+%H:%M:%S')"
  ssh -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes \
      -N -D $LOCAL_SOCKS -R $REMOTE_PORT:localhost:$LOCAL_SOCKS "$VPS"
  echo "[caiu] reconectando em 5s..."
  sleep 5
done
