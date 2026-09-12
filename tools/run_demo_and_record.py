#!/usr/bin/env python3
import os
import sys
import time
import subprocess
import json
import xml.etree.ElementTree as ET

ADB = ["adb", "-s", "127.0.0.1:5555"]

def run_adb(cmd):
    if isinstance(cmd, str):
        full = " ".join(ADB) + " " + cmd
        return subprocess.run(full, shell=True, capture_output=True, text=True)
    else:
        return subprocess.run(ADB + cmd, capture_output=True, text=True)

def dump_ui():
    run_adb(["shell", "uiautomator", "dump", "/sdcard/dump.xml"])
    res = run_adb(["shell", "cat", "/sdcard/dump.xml"])
    if not res.stdout.strip().startswith("<?xml"):
        return None
    try:
        return ET.fromstring(res.stdout)
    except Exception as e:
        print("XML parse error:", e)
        return None

def find_node(root, text=None, resource_id=None, contains_text=None):
    if root is None:
        return None
    for node in root.iter("node"):
        t = node.attrib.get("text", "")
        r = node.attrib.get("resource-id", "")
        if text and t == text:
            return node
        if contains_text and contains_text.lower() in t.lower():
            return node
        if resource_id and resource_id in r:
            return node
    return None

def get_center(node):
    bounds = node.attrib.get("bounds", "")
    import re
    m = re.findall(r"\d+", bounds)
    if len(m) == 4:
        x1, y1, x2, y2 = map(int, m)
        return (x1 + x2) // 2, (y1 + y2) // 2
    return None

def tap_node(node):
    center = get_center(node)
    if center:
        print(f"Tapping {node.attrib.get('text', '')} / {node.attrib.get('resource-id', '')} at {center}")
        run_adb(["shell", "input", "tap", str(center[0]), str(center[1])])
        return True
    return False

def screenshot(filename):
    run_adb(["shell", "screencap", "-p", "/sdcard/screen.png"])
    run_adb(["pull", "/sdcard/screen.png", filename])

def main():
    print("=" * 80)
    print(" GRAVAÇÃO COMPLETA: CARREGAMENTO RÁPIDO E REPRODUÇÃO NO PLAYER EXOPLAYER")
    print("=" * 80)

    # 1. Limpa logcat e arquivos antigos
    run_adb(["shell", "logcat", "-c"])
    run_adb(["shell", "rm", "-f", "/sdcard/demo_playback.mp4"])
    local_mp4 = "/root/cloudstream-plugins/demo_playback.mp4"
    if os.path.exists(local_mp4):
        os.remove(local_mp4)

    # 2. Inicia gravação da tela em background (40s)
    print("[*] Iniciando gravação de tela (screenrecord 40s)...")
    rec_proc = subprocess.Popen(
        "adb -s 127.0.0.1:5555 shell screenrecord --size 720x1280 --bit-rate 4M --time-limit 40 /sdcard/demo_playback.mp4",
        shell=True
    )
    time.sleep(1)

    # 3. Abre o CloudStream
    print("[*] Abrindo CloudStream...")
    run_adb(["shell", "su", "0", "am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", "com.lagradost.cloudstream3.prerelease/com.lagradost.cloudstream3.ui.account.AccountSelectActivity"])
    time.sleep(2)

    # 4. Trata popup "Extensions / Done" se existir
    root = dump_ui()
    done = find_node(root, text="Done")
    if done is not None:
        print("[*] Dispensando tela inicial de Extensions...")
        tap_node(done)
        time.sleep(2)

    # 5. Home carregada: clica na aba de busca ou em um card
    print("[*] Selecionando conteúdo...")
    root = dump_ui()
    
    # Procura cards na home
    card = find_node(root, resource_id="imageView")
    if card is not None:
        print("[*] Clicando em card na Home...")
        tap_node(card)
        time.sleep(3)
    else:
        # Tenta clicar no primeiro card da tela (coordenada comum de card de topo)
        print("[*] Clicando em card por coordenadas (360, 300)...")
        run_adb(["shell", "input", "tap", "360", "300"])
        time.sleep(3)

    # 6. Página de detalhes: clica em Play Movie ou Episódio 1
    root = dump_ui()
    play_btn = find_node(root, resource_id="result_play_movie")
    if play_btn is None:
        play_btn = find_node(root, contains_text="Assistir")
    if play_btn is None:
        play_btn = find_node(root, contains_text="Play")
    if play_btn is None:
        play_btn = find_node(root, resource_id="episode_holder")
    if play_btn is None:
        play_btn = find_node(root, contains_text="Episódio")

    if play_btn is not None:
        print(f"[*] Clicando no botão de reprodução...")
        tap_node(play_btn)
        time.sleep(3)
    else:
        print("[*] Clicando no botão central de play (360, 878)...")
        run_adb(["shell", "input", "tap", "360", "878"])
        time.sleep(3)

    # 7. Se abrir diálogo de links / servidores, seleciona o primeiro
    root = dump_ui()
    server_opt = find_node(root, contains_text="RedeCanais")
    if server_opt is None:
        server_opt = find_node(root, contains_text="Player")
    if server_opt is not None:
        print("[*] Selecionando servidor de stream...")
        tap_node(server_opt)
        time.sleep(2)

    # 8. Aguarda reprodução ativa no player por 20 segundos
    print("[*] Reproduzindo vídeo na tela (aguardando 20 segundos)...")
    for sec in range(20):
        time.sleep(1)
        if sec == 5:
            # Toca na tela para exibir a barra de progresso do player (provando tempo avançando)
            run_adb(["shell", "input", "tap", "360", "640"])

    # 9. Aguarda finalização da gravação
    print("[*] Finalizando screenrecord...")
    try:
        rec_proc.wait(timeout=15)
    except Exception:
        pass
    time.sleep(1)

    # 10. Extrai o vídeo gravado
    print("[*] Baixando vídeo gravado do emulador...")
    run_adb(["pull", "/sdcard/demo_playback.mp4", local_mp4])

    if not os.path.exists(local_mp4) or os.path.getsize(local_mp4) == 0:
        print("[ERRO] Arquivo de vídeo vazio ou não encontrado!")
        sys.exit(1)

    size_mb = os.path.getsize(local_mp4) / (1024 * 1024)
    print(f"[✓] Vídeo gravado com sucesso! Tamanho: {size_mb:.2f} MB ({local_mp4})")

    # 11. Faz upload para TmpFiles.org
    print("[*] Fazendo upload para TmpFiles.org...")
    res_tmp = subprocess.run(f"curl -s -F 'file=@{local_mp4}' https://tmpfiles.org/api/v1/upload", shell=True, capture_output=True, text=True)
    tmp_url = ""
    try:
        data = json.loads(res_tmp.stdout)
        if "data" in data and "url" in data["data"]:
            raw_url = data["data"]["url"]
            tmp_url = raw_url.replace("tmpfiles.org/", "tmpfiles.org/dl/")
    except Exception as e:
        print(f"[AVISO] Erro no parsing tmpfiles: {e}")

    # Upload backup no Litterbox
    print("[*] Fazendo upload backup para Litterbox...")
    res_lb = subprocess.run(f"curl -s -F 'reqtype=fileupload' -F 'time=24h' -F 'fileToUpload=@{local_mp4}' https://litterbox.catbox.moe/resources/internals/api.php", shell=True, capture_output=True, text=True)
    lb_url = res_lb.stdout.strip()

    print("\n" + "=" * 80)
    print(" 🎬 LINKS DE COMPROVAÇÃO DE CARREGAMENTO RÁPIDO E REPRODUÇÃO ATIVA:")
    print("=" * 80)
    if tmp_url.startswith("http"):
        print(f"👉 Link TmpFiles (Download / Player Direto): {tmp_url}")
    if lb_url.startswith("http"):
        print(f"👉 Link Litterbox (Visualizador Web): {lb_url}")
    print("=" * 80)

if __name__ == "__main__":
    main()
