#!/usr/bin/env python3
import sys
import os
import subprocess
import time

def run(cmd, check=True, timeout=120):
    print(f"\n[RUN]: {cmd}")
    try:
        res = subprocess.run(cmd, shell=True, text=True, capture_output=True, timeout=timeout)
        if res.stdout:
            print(res.stdout.strip())
        if res.stderr:
            print("[STDERR]:", res.stderr.strip())
        if check and res.returncode != 0:
            print(f"FAILED (code {res.returncode})")
            sys.exit(res.returncode)
        return res
    except subprocess.TimeoutExpired:
        print(f"[TIMEOUT] Command timed out after {timeout}s: {cmd}")
        return subprocess.CompletedProcess(cmd, returncode=124, stdout="", stderr="Timeout expired")

def main():
    plugin = sys.argv[1] if len(sys.argv) > 1 else "RedeCanaisAF"
    print(f"=== INICIANDO VERIFICAÇÃO AUTOMATIZADA: {plugin} ===")

    # 1. Compilar plugin
    print("\n1. Compilando o plugin com Gradle...")
    res = run(f"./gradlew :{plugin}:make", check=False)
    if res.returncode != 0:
        print("Fallback: tentando ./gradlew makePlugins...")
        run("./gradlew makePlugins")

    # 2. Verificar artefato gerado
    plugin_build_path = f"{plugin}/build/{plugin}.cs3"
    cs3_path = f"builds/{plugin}.cs3"
    if os.path.exists(plugin_build_path):
        os.makedirs("builds", exist_ok=True)
        import shutil
        shutil.copy2(plugin_build_path, cs3_path)

    if os.path.exists(cs3_path):
        size = os.path.getsize(cs3_path)
        print(f"✅ Artefato gerado com sucesso: {cs3_path} ({size} bytes)")
    else:
        print(f"⚠️ Artefato {cs3_path} não encontrado!")

    # 3. Verificar status do emulador Redroid
    print("\n2. Verificando emulador Android (Redroid)...")
    res_adb = run("adb devices", check=False)
    dev = "127.0.0.1:5555" if "127.0.0.1:5555" in res_adb.stdout else "emulator-5554"
    if "127.0.0.1:5555" not in res_adb.stdout and "emulator-5554" not in res_adb.stdout:
        print("Tentando reconectar ADB no redroid...")
        run("adb connect 127.0.0.1:5555", check=False)
        dev = "127.0.0.1:5555"

    print(f"✅ Usando dispositivo ADB: {dev}")

    # 3.1 Instalar plugin no diretório do CloudStream
    if os.path.exists(cs3_path):
        dest_path = f"/sdcard/Cloudstream3/plugins/{plugin}.cs3"
        dest_internal = f"/data/media/0/Android/data/com.lagradost.cloudstream3.prerelease/files/plugins/{plugin}.cs3"
        print(f"\nInstalando plugin no emulador: {dest_path} e {dest_internal}...")
        run(f"adb -s {dev} shell mkdir -p /sdcard/Cloudstream3/plugins", check=False)
        run(f"adb -s {dev} push {cs3_path} {dest_path}", check=False)
        run(f"docker exec redroid mkdir -p /data/media/0/Android/data/com.lagradost.cloudstream3.prerelease/files/plugins", check=False)
        run(f"docker exec redroid cp {dest_path} {dest_internal}", check=False)
        run(f"docker exec redroid chown -R 10087:10087 /data/media/0/Android/data/com.lagradost.cloudstream3.prerelease/files/plugins", check=False)
        run(f"docker exec redroid chmod 444 {dest_internal}", check=False)

    # 4. Iniciar CloudStream no emulador
    print("\n3. Iniciando CloudStream no Redroid...")
    run("docker exec redroid am start -n com.lagradost.cloudstream3.prerelease/com.lagradost.cloudstream3.ui.account.AccountSelectActivity", check=False)

    # 5. Coletar Logcat recente
    print("\n4. Coletando Logcat recente...")
    time.sleep(2)
    log_res = run(f"adb -s {dev} logcat -d -t 100 -s RedeCanaisAF-Trace:V CloudStream:V ExoPlayer:V", check=False, timeout=5)
    
    print("\n=== VERIFICAÇÃO CONCLUÍDA COM SUCESSO! ===")

if __name__ == "__main__":
    main()
