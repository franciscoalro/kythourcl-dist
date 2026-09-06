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
    print(" GRAVANDO DEMONSTRAÇÃO COMPLETA DE EXECUÇÃO E REPRODUÇÃO DO VÍDEO")
    print("=" * 80)

    # 1. Abre a Home do CloudStream
    run(f"{ADB} shell rm -f /sdcard/demo_playback.mp4")
    run("docker exec redroid am start -n com.lagradost.cloudstream3.prerelease/com.lagradost.cloudstream3.MainActivity")
    time.sleep(2)

    # 2. Inicia gravação da tela (30 segundos)
    print("[*] Iniciando gravação de tela (screenrecord 30s)...")
    rec_proc = subprocess.Popen(
        f"{ADB} shell screenrecord --size 720x1280 --bit-rate 3M --time-limit 30 /sdcard/demo_playback.mp4",
        shell=True
    )
    time.sleep(2)

    # 3. Clica no primeiro card de filme do catálogo para abrir os detalhes
    print("[*] Selecionando filme no catálogo...")
    run(f"{ADB} shell input tap 200 450")
    time.sleep(3)

    # 4. Clica no botão Play Movie / Assistir
    print("[*] Clicando no botão Play Movie (360, 878)...")
    run(f"{ADB} shell input tap 360 878")
    time.sleep(2)

    # Se abrir seletor de links/servidores, clica no primeiro
    run(f"{ADB} shell input tap 360 700")

    # 5. Aguarda o player carregar e reproduzir o vídeo na tela por 15 segundos
    print("[*] Aguardando o player carregar e executar o vídeo na tela...")
    time.sleep(15)

    # 6. Finaliza gravação
    print("[*] Finalizando gravação...")
    try:
        rec_proc.wait(timeout=10)
    except Exception:
        pass
    time.sleep(1)

    # 7. Extrai o vídeo gerado
    local_mp4 = "/root/cloudstream-plugins/demo_playback.mp4"
    run(f"{ADB} pull /sdcard/demo_playback.mp4 {local_mp4}")

    if not os.path.exists(local_mp4) or os.path.getsize(local_mp4) == 0:
        print("[ERRO] Arquivo de vídeo vazio ou não encontrado!")
        sys.exit(1)

    size_mb = os.path.getsize(local_mp4) / (1024 * 1024)
    print(f"[✓] Vídeo gravado com sucesso! Tamanho: {size_mb:.2f} MB")

    # 8. Upload para TmpFiles.org
    print("[*] Fazendo upload para TmpFiles.org...")
    res_tmp = run(f"curl -s -F 'file=@{local_mp4}' https://tmpfiles.org/api/v1/upload")
    tmp_url = ""
    try:
        data = json.loads(res_tmp.stdout)
        if "data" in data and "url" in data["data"]:
            raw_url = data["data"]["url"]
            # Converte http://tmpfiles.org/12345/file.mp4 em link direto http://tmpfiles.org/dl/12345/file.mp4
            tmp_url = raw_url.replace("tmpfiles.org/", "tmpfiles.org/dl/")
    except Exception as e:
        print(f"[AVISO] Erro no parsing tmpfiles: {e}")

    # Backup no litterbox
    res_lb = run(f"curl -s -F 'reqtype=fileupload' -F 'time=24h' -F 'fileToUpload=@{local_mp4}' https://litterbox.catbox.moe/resources/internals/api.php")
    lb_url = res_lb.stdout.strip()

    print("\n" + "=" * 80)
    print(" 🎬 LINKS DO VÍDEO COM A REPRODUÇÃO DO VÍDEO EM TEMPO REAL:")
    print("=" * 80)
    if tmp_url.startswith("http"):
        print(f"👉 Link TmpFiles (Download / Player Direto): {tmp_url}")
    if lb_url.startswith("http"):
        print(f"👉 Link Litterbox (Visualizador Web): {lb_url}")
    print("=" * 80)

if __name__ == "__main__":
    main()
