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
    print(" GRAVANDO DEMONSTRAÇÃO ÁGIL E DIRETA: HOME + NAVEGAÇÃO + PLAYER")
    print("=" * 80)

    # 1. Garante que CloudStream está aberto na Home
    run(f"{ADB} shell rm -f /sdcard/demo_fast.mp4")
    run("docker exec redroid am start -n com.lagradost.cloudstream3.prerelease/com.lagradost.cloudstream3.MainActivity")
    time.sleep(1.5)

    # 2. Inicia gravação da tela
    print("[*] Iniciando gravação de tela rápida...")
    rec_proc = subprocess.Popen(
        f"{ADB} shell screenrecord --size 720x1280 --bit-rate 3M --time-limit 20 /sdcard/demo_fast.mp4",
        shell=True
    )
    time.sleep(1)

    # 3. Rolagem rápida e fluida pelo catálogo
    print("[*] Navegando fluido pelo catálogo e seções...")
    run(f"{ADB} shell input swipe 360 850 360 350 250")
    time.sleep(1)
    run(f"{ADB} shell input swipe 360 850 360 350 250")
    time.sleep(1)
    run(f"{ADB} shell input swipe 360 350 360 850 200")
    time.sleep(1)

    # 4. Clica direto no botão Assistir / Play
    print("[*] Clicando no Play para demonstrar início do vídeo...")
    run(f"{ADB} shell input tap 360 850")
    time.sleep(1)
    run(f"{ADB} shell input tap 360 700")
    time.sleep(6)

    # 5. Finaliza
    print("[*] Finalizando...")
    try:
        rec_proc.wait(timeout=10)
    except Exception:
        pass
    time.sleep(1)

    # 6. Extrai o vídeo gravado
    local_mp4 = "/root/cloudstream-plugins/demo_fast.mp4"
    run(f"{ADB} pull /sdcard/demo_fast.mp4 {local_mp4}")

    if not os.path.exists(local_mp4) or os.path.getsize(local_mp4) == 0:
        print("[ERRO] Arquivo de vídeo vazio!")
        sys.exit(1)

    file_size_mb = os.path.getsize(local_mp4) / (1024 * 1024)
    print(f"[✓] Vídeo gerado: {file_size_mb:.2f} MB")

    # 7. Upload
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

    print("\n" + "=" * 80)
    print(" 🎬 NOVO VÍDEO DIRETO:")
    print("=" * 80)
    if lb_url.startswith("http"):
        print(f"👉 Link Litterbox (24h): {lb_url}")
    if tmp_url.startswith("http"):
        print(f"👉 Link TmpFiles: {tmp_url}")
    print("=" * 80)

if __name__ == "__main__":
    main()
