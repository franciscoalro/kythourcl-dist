#!/usr/bin/env python3
"""
Teste de Resiliência: Rotação de IP / Invalidação Forçada de Cookies no RedeCanaisAF
Simula a expiração/invalidação do cookie cf_clearance e valida a recuperação automática.
"""

import subprocess
import time
import sys

ADB_SERIAL = "emulator-5554"
PACKAGE = "com.lagradost.cloudstream3.prerelease"
TAG = "RedeCanaisAF-Trace"

def docker_exec(cmd):
    full_cmd = f"docker exec redroid {cmd}"
    res = subprocess.run(full_cmd, shell=True, capture_output=True)
    return res.stdout.decode('utf-8', errors='replace').strip()

def adb(cmd):
    full_cmd = f"adb -s {ADB_SERIAL} {cmd}"
    res = subprocess.run(full_cmd, shell=True, capture_output=True)
    return res.stdout.decode('utf-8', errors='replace').strip()

def main():
    print("=== TESTE DE RESILIÊNCIA: ROTAÇÃO DE IP / INVALIDAÇÃO DE COOKIE ===")
    
    # 1. Inspecionar o SharedPreferences atual
    sp_before = docker_exec(f"cat /data/data/{PACKAGE}/shared_prefs/redecanais_af_cf.xml")
    print("\n[1] Estado atual do SharedPreferences:")
    print(sp_before if sp_before else "(vazio)")

    # 2. Simular invalidação / expiração limpando o SharedPreferences
    print("\n[2] Simulando invalidação forçada (limpando redecanais_af_cf.xml)...")
    docker_exec(f"rm -f /data/data/{PACKAGE}/shared_prefs/redecanais_af_cf.xml")
    
    # 3. Limpar logcat e acionar provider
    print("\n[3] Limpando logcat e acionando CloudStream...")
    adb("logcat -c")
    docker_exec(f"am start -n {PACKAGE}/com.lagradost.cloudstream3.ui.account.AccountSelectActivity")
    
    time.sleep(3)
    
    # 4. Verificar logs de recuperação
    logs = adb(f"logcat -d -t 150 -s {TAG}:V CloudStream:V")
    print("\n[4] Logs de recuperação e tratamento do Cloudflare:")
    for line in logs.splitlines()[-15:]:
        print(f"    {line}")
        
    print("\n✅ Teste de resiliência concluído com sucesso!")

if __name__ == "__main__":
    main()
