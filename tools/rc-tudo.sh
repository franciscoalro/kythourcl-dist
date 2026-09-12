#!/data/data/com.termux/files/usr/bin/bash
# rc-tudo.sh — diagnóstico + túnel em 1 script.
# Uso: ./rc-tudo.sh
# Ele: 1) mostra egresso direto, 2) checa VPN/proxy, 3) limpa sessões velhas,
#      4) se a rede estiver limpa, sobe o túnel com auto-reconnect.

VPS="root@167.233.60.72"
LOCAL_SOCKS=10880
REMOTE_PORT=19053
OK=1

say() { echo ">>> $*"; }

say "1/5 egresso DIRETO do celular (sem proxy):"
DIRECTO=$(curl -s -m 15 https://ipinfo.io/json | grep -o '"ip": "[^"]*"' | head -1)
echo "    $DIRECTO"
if echo "$DIRECTO" | grep -qE "167\.233\.|2\.57\.| Falkenstein"; then
  echo "    !!! IP DA VPS/VPN — a rede do celular está saindo pela VPS. Desligue a VPN do Android e rode de novo."
  OK=0
fi

say "2/5 VPN e proxy no ambiente:"
VPN=$(ip route show table all 2>/dev/null | grep -ciE "tun0|ppp|vpn" || echo 0)
echo "    rotas vpn/tun: $VPN"
PROXY=$(env | grep -iE "http_proxy|https_proxy|all_proxy" || echo "nenhum proxy no env")
echo "    $PROXY"
if [ "$VPN" != "0" ] && [ -n "$VPN" ]; then OK=0; echo "    !!! TUNEL VPN ATIVO no Android — desligue em Config > Rede > VPN."; fi

say "3/5 limpando sessoes SSH velhas:"
pkill -9 -f "ssh.*$REMOTE_PORT" 2>/dev/null
pkill -9 -f "ssh.*-D $LOCAL_SOCKS" 2>/dev/null
sleep 2
echo "    limpo."

if [ "$OK" != "1" ]; then
  echo ""
  echo "=== PAREI AQUI: rede suja (VPN/proxy). Corrija e rode de novo. ==="
  exit 1
fi

say "4/5 rede limpa. Subindo tunel (Ctrl+C para sair; reconecta sozinho):"
termux-wake-lock 2>/dev/null
n=0
while true; do
  n=$((n+1))
  echo "[tentativa $n] $(date '+%H:%M:%S')"
  ssh -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes \
      -N -D $LOCAL_SOCKS -R $REMOTE_PORT:localhost:$LOCAL_SOCKS "$VPS" &
  SSHPID=$!
  sleep 6
  say "5/5 teste do SOCKS local :$LOCAL_SOCKS:"
  EGRESSO=$(curl -s -m 15 --socks5-hostname 127.0.0.1:$LOCAL_SOCKS https://ipinfo.io/json | grep -o '"ip": "[^"]*"\|"org": "[^"]*"' | tr '\n' ' ')
  echo "    $EGRESSO"
  if echo "$EGRESSO" | grep -q "Hetzner"; then
    echo "    !!! SOCKS sai pela Hetzner — rede contaminada APÓS o teste 1. Matando e tentando de novo."
    kill -9 $SSHPID 2>/dev/null
    sleep 5
    continue
  fi
  echo "    EGRESSO LIMPO — túnel válido. Deixe rodando e avise 'verifique'."
  wait $SSHPID
  echo "[caiu] reconectando em 5s..."
  sleep 5
done
