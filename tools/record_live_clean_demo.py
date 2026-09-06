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
    print(" GRAVANDO DEMONSTRAÇÃO COMPLETA: HOME + CATÁLOGO + DETALHES + PLAYER")
    print("=" * 80)

    # 1. Garante que CloudStream está aberto na Home
    run(f"{ADB} shell rm -f /sdcard/demo_clean.mp4")
    run("docker exec redroid am start -n com.lagradost.cloudstream3.prerelease/com.lagradost.cloudstream3.MainActivity")
    time.sleep(1)

    # 2. Inicia gravação da tela (25s)
    print("[*] Iniciando gravação de tela (screenrecord)...")
    rec_proc = subprocess.Popen(
        f"{ADB} shell screenrecord --size 720x1280 --bit-rate 3M --time-limit 25 /sdcard/demo_clean.mp4",
        shell=True
    )
    time.sleep(2)

    # 3. Rola o catálogo para baixo lentamente mostrando os cards e categorias
    print("[*] Rolando catálogo na Home para exibir todas as seções e capas...")
    run(f"{ADB} shell input swipe 360 800 360 300 600")
    time.sleep(2.5)
    run(f"{ADB} shell input swipe 360 800 360 300 600")
    time.sleep(2.5)
    run(f"{ADB} shell input swipe 360 300 360 800 400")
    time.sleep(1.5)

    # 4. Clica no botão Play / Assistir do banner principal
    print("[*] Clicando no botão Assistir / Play...")
    run(f"{ADB} shell input tap 360 850")
    time.sleep(2)

    # 5. Se abrir lista de servidores ou player, clica para confirmar
    run(f"{ADB} shell input tap 360 700")
    time.sleep(6)

    # 6. Aguarda gravação finalizar
    print("[*] Finalizando gravação...")
    rec_proc.wait(timeout=15)
    time.sleep(2)

    # 7. Extrai o vídeo gravado
    local_mp4 = "/root/cloudstream-plugins/demo_clean.mp4"
    run(f"{ADB} pull /sdcard/demo_clean.mp4 {local_mp4}")

    if not os.path.exists(local_mp4) or os.path.getsize(local_mp4) == 0:
        print("[ERRO] Arquivo de vídeo vazio ou não gerado!")
        sys.exit(1)

    file_size_mb = os.path.getsize(local_mp4) / (1024 * 1024)
    print(f"[✓] Vídeo gerado com sucesso! Tamanho: {file_size_mb:.2f} MB")

    # 8. Upload para serviços temporários
    print("[*] Fazendo upload do vídeo...")
    res_lb = run(f"curl -s -F 'reqtype=fileupload' -F 'time=24h' -F 'fileToUpload=@{local_mp4}' https://litterbox.catbox.moe/resources/internals/api.php")
    lb_url = res_lb.stdout.strip()

    res_tmp = run(f"curl -s -F 'file=@{local_mp4}' https://tmpfiles.org/api/v1/upload")
    tmp_url = ""
    try:
        data = json.loads(res_tmp.stdout)
        if "data" in data and "url" in data["data"]:
            tmp_url = data["data"]["url"].replace("tmpfiles.org/", "tmpfiles.org/dl/")
    except Exception:
        pass

    res_0x0 = run(f"curl -s -F 'file=@{local_mp4}' https://0x0.st")
    url_0x0 = res_0x0.stdout.strip()

    print("\n" + "=" * 80)
    print(" 🎬 LINKS PARA VISUALIZAR O VÍDEO DO CATÁLOGO E PLAYER:")
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
