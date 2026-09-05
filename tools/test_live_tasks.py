#!/usr/bin/env python3
import time
import subprocess
import json
import os
import sys

def adb(cmd):
    return subprocess.run(f"adb -s emulator-5554 {cmd}", shell=True, text=True, capture_output=True)

def run_logcat(filter_tag="RedeCanaisAF-Trace", lines=200):
    res = subprocess.run(
        f"adb -s emulator-5554 logcat -d -t {lines} -s {filter_tag}:V CloudStream:V ExoPlayer:V",
        shell=True, text=True, capture_output=True, timeout=15
    )
    return res.stdout

def tap(x, y):
    adb(f"shell input tap {x} {y}")
    time.sleep(1)

def input_text(text):
    # Escape spaces
    safe = text.replace(" ", "%s")
    adb(f"shell input text {safe}")
    time.sleep(1)

def keyevent(code):
    adb(f"shell input keyevent {code}")
    time.sleep(1)

def dump_ui():
    adb("shell uiautomator dump /sdcard/dump.xml")
    res = adb("shell cat /sdcard/dump.xml")
    return res.stdout

def restart_app():
    print("Restarting CloudStream...")
    subprocess.run("docker exec redroid am force-stop com.lagradost.cloudstream3.prerelease", shell=True)
    time.sleep(1)
    subprocess.run("adb -s emulator-5554 logcat -c", shell=True)
    subprocess.run("docker exec redroid am start -n com.lagradost.cloudstream3.prerelease/com.lagradost.cloudstream3.ui.account.AccountSelectActivity", shell=True)
    time.sleep(3)

def main():
    print("=== Testing Live RedeCanaisAF Plugin ===")
    restart_app()

    # Check if Account select screen is shown (tap first profile or continue)
    ui = dump_ui()
    if "AccountSelectActivity" in ui or "account_name" in ui or "profile" in ui.lower():
        print("Selecting account...")
        tap(360, 600)
        time.sleep(2)

    # Check home page
    print("Checking home page logs...")
    time.sleep(5)
    logs = run_logcat(lines=100)
    print("--- LOGCAT SNIPPET ---")
    for line in logs.splitlines()[-30:]:
        print(line)
    print("----------------------")

if __name__ == "__main__":
    main()
