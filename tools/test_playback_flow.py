#!/usr/bin/env python3
import time
import subprocess
import xml.etree.ElementTree as ET
import sys
import re

ADB = ["adb", "-s", "emulator-5554"]

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
        if text is not None and t == text:
            return node
        if contains_text is not None and contains_text.lower() in t.lower():
            return node
        if resource_id is not None and resource_id in r:
            return node
    return None

def get_center(node):
    bounds = node.attrib.get("bounds", "")
    m = re.findall(r"\d+", bounds)
    if len(m) == 4:
        x1, y1, x2, y2 = map(int, m)
        return (x1 + x2) // 2, (y1 + y2) // 2
    return None

def tap_node(node):
    if node is None:
        return False
    center = get_center(node)
    if center:
        print(f"Tapping {node.attrib.get('text')} / {node.attrib.get('resource-id')} at {center}")
        run_adb(["shell", "input", "tap", str(center[0]), str(center[1])])
        return True
    return False

def screenshot(filename):
    run_adb(["shell", "screencap", "-p", "/sdcard/screen.png"])
    run_adb(["pull", "/sdcard/screen.png", filename])

def main():
    print("Starting CloudStream...")
    run_adb(["shell", "su", "0", "am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", "com.lagradost.cloudstream3.prerelease/com.lagradost.cloudstream3.ui.account.AccountSelectActivity"])
    time.sleep(2)

    root = dump_ui()
    done = find_node(root, text="Done")
    if done is not None:
        tap_node(done)
        time.sleep(2)

    root = dump_ui()
    # Click top search bar
    search_bar = find_node(root, resource_id="search_button")
    if search_bar is not None:
        tap_node(search_bar)
        time.sleep(1)
    else:
        run_adb(["shell", "input", "tap", "60", "95"])
        time.sleep(1)

    print("Typing guerra civil and submitting search...")
    run_adb(["shell", "input", "text", "guerra%scivil"])
    time.sleep(1)
    run_adb(["shell", "input", "keyevent", "66"])
    time.sleep(6)

    root = dump_ui()
    card = find_node(root, contains_text="Capitão") or find_node(root, contains_text="Guerra") or find_node(root, contains_text="Acessar")
    if card is not None:
        tap_node(card)
        time.sleep(4)
    else:
        run_adb(["shell", "input", "tap", "360", "400"])
        time.sleep(4)

    screenshot("/root/cloudstream-plugins/step_details.png")
    
    root = dump_ui()
    # Click episode 1
    ep1 = find_node(root, contains_text="Episódio 1")
    if ep1 is not None:
        tap_node(ep1)
        time.sleep(2)
    else:
        # Tap episode holder text at (250, 1074)
        run_adb(["shell", "input", "tap", "250", "1074"])
        time.sleep(2)

    # Check if stream links dialog opened
    root = dump_ui()
    srv = find_node(root, contains_text="RedeCanais")
    if srv is not None:
        tap_node(srv)
        time.sleep(2)

    print("Waiting for player to initialize and stream to start playing...")
    time.sleep(8)
    screenshot("/root/cloudstream-plugins/step_player.png")

if __name__ == "__main__":
    main()
