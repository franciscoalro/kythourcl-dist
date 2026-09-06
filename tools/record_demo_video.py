#!/usr/bin/env python3
import os
import sys
import time
import subprocess
import json

ADB = "adb -s 127.0.0.1:5555"

def run(cmd):
    return subprocess.run(cmd, shell=True, text=True, capture_output=True)

def main():
    print("=" * 80)
    print(" GRAVANDO VÍDEO DE DEMONSTRAÇÃO DO REDECANAIS NO EMULADOR")
    print("=" * 80)

    # 1. Limpa gravações anteriores
    run(f"{ADB} shell rm -f /sdcard/demo_redecanais.mp4")
    run("docker exec redroid am force-stop com.lagradost.cloudstream3.prerelease")
    time.sleep(1)

    # 2. Inicia screenrecord em background no Android (25 segundos)
    print("[*] Iniciando gravação de tela (screenrecord)...")
    rec_proc = subprocess.Popen(
        f"{ADB} shell screenrecord --size 720x1280 --bit-rate 3M --time-limit 25 /sdcard/demo_redecanais.mp4",
        shell=True
    )
    time.sleep(1.5)

    # 3. Abre o CloudStream
    print("[*] Abrindo CloudStream...")
    run("docker exec redroid am start -n com.lagradost.cloudstream3.prerelease/com.lagradost.cloudstream3.ui.account.AccountSelectActivity")
    time.sleep(2)

    # 4. Seleciona o perfil / entra na Home
    print("[*] Entrando na Home...")
    run(f"{ADB} shell input tap 360 600")
    time.sleep(3.5)

    # 5. Rola a página Home para exibir as seções carregadas rapidamente
    print("[*] Rolando catálogo na Home...")
    run(f"{ADB} shell input swipe 360 900 360 400 400")
    time.sleep(2)
    run(f"{ADB} shell input swipe 360 400 360 900 400")
    time.sleep(1.5)

    # 6. Clica no primeiro card de filme
    print("[*] Clicando no card de filme para carregar detalhes...")
    run(f"{ADB} shell input tap 200 450")
    time.sleep(3)

    # 7. Clica no botão Play / Assistir
    print("[*] Clicando no botão Assistir / Play...")
    run(f"{ADB} shell input tap 360 850")
    time.sleep(1)
    run(f"{ADB} shell input tap 360 700")
    
    # 8. Aguarda reprodução do vídeo
    print("[*] Aguardando reprodução no Player...")
    time.sleep(6)

    # 9. Aguarda processo de gravação finalizar
    print("[*] Finalizando gravação...")
    rec_proc.wait(timeout=15)
    time.sleep(2)

    # 10. Baixa o arquivo do emulador
    local_mp4 = "/root/cloudstream-plugins/demo_redecanais.mp4"
    print(f"[*] Extraindo vídeo para {local_mp4}...")
    run(f"{ADB} pull /sdcard/demo_redecanais.mp4 {local_mp4}")

    if not os.path.exists(local_mp4) or os.path.getsize(local_mp4) == 0:
        print("[ERRO] Arquivo de vídeo não encontrado ou vazio!")
        sys.exit(1)

    file_size_mb = os.path.getsize(local_mp4) / (1024 * 1024)
    print(f"[✓] Vídeo gerado com sucesso! Tamanho: {file_size_mb:.2f} MB")

    # 11. Faz upload para serviços de link temporário
    print("[*] Fazendo upload do vídeo para link temporário...")
    
    # Tentativa 1: Litterbox (Catbox) - 24 horas
    res_lb = run(f"curl -s -F 'reqtype=fileupload' -F 'time=24h' -F 'fileToUpload=@{local_mp4}' https://litterbox.catbox.moe/resources/internals/api.php")
    lb_url = res_lb.stdout.strip()
    
    # Tentativa 2: Tmpfiles.org
    res_tmp = run(f"curl -s -F 'file=@{local_mp4}' https://tmpfiles.org/api/v1/upload")
    tmp_url = ""
    try:
        data = json.loads(res_tmp.stdout)
        if "data" in data and "url" in data["data"]:
            tmp_url = data["data"]["url"].replace("tmpfiles.org/", "tmpfiles.org/dl/")
    except Exception:
        pass

    # Tentativa 3: 0x0.st
    res_0x0 = run(f"curl -s -F 'file=@{local_mp4}' https://0x0.st")
    url_0x0 = res_0x0.stdout.strip()

    print("\n" + "=" * 80)
    print(" 🎬 LINKS PARA VISUALIZAR O VÍDEO DO EMULADOR:")
    print("=" * 80)
    if lb_url.startswith("http"):
        print(f"👉 Link Principal (Litterbox - Válido por 24h): {lb_url}")
    if tmp_url.startswith("http"):
        print(f"👉 Link Alternativo (TmpFiles): {tmp_url}")
    if url_0x0.startswith("http"):
        print(f"👉 Link Alternativo (0x0.st): {url_0x0}")
    print("=" * 80)

if __name__ == "__main__":
    main()
