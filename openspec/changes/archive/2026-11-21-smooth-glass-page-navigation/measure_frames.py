import argparse
import json
import re
import subprocess
from xml.etree import ElementTree


PACKAGE = "com.example.bilimonitor"
TABS = ("主播", "历史", "统计", "设置")


def main():
    parser = argparse.ArgumentParser(description="在已准备好的合成列表与壁纸上测全玻璃帧耗时；不改外观配置。")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--blur", type=int, choices=(0, 28), required=True)
    args = parser.parse_args()

    def adb(*commands):
        return subprocess.check_output([args.adb, "-s", args.serial, *commands], timeout=90)

    def shell(command):
        return adb("shell", command).decode("utf-8", errors="replace")

    def nodes():
        xml = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        return ElementTree.fromstring(xml[xml.index(b"<?xml"):xml.index(b"</hierarchy>") + 12]).iter("node")

    pref = adb("exec-out", "run-as", PACKAGE, "cat", "files/datastore/appearance_settings.preferences_pb")
    for key in (b"glass_dock", b"liquid_glass_cards"):
        if key + bytes((18, 2, 8, 1)) not in pref:
            raise ValueError("请在测试机开启玻璃 Dock 与所有卡片的液态玻璃。")
    if b"navigation_style" + bytes((18, 8, 42, 6)) + b"NORMAL" in pref or b"background_image_file" not in pref:
        raise ValueError("请启用悬浮栏且使用合成壁纸。")
    match = re.search(rb"glass_blur\x12.\x2a.([0-9.]+)", pref)
    if float(match.group(1) if match else 28) != args.blur:
        raise ValueError("实际模糊值与分组不符。")
    size = re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", shell("wm size"))[-1]
    width, height = map(int, size)
    positions = {}
    for node in nodes():
        label = node.get("text")
        if label in TABS:
            left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
            positions[label] = f"{(left + right) // 2} {(top + bottom) // 2}"
    if set(positions) != set(TABS):
        raise ValueError("请先打开应用一级页面。")
    home = f"input tap {positions[TABS[0]]}; sleep .7; "
    shell(home)
    if not any(node.get("text") == "Studio 01" for node in nodes()):
        raise ValueError("请使用约 90 行 Studio 合成数据，并回到列表开头。")
    tabs = "".join(f"input tap {positions[label]}; sleep .42; " for label in (*TABS[1:], TABS[0]))
    low = f"{round(width * 330 / 720)} {round(height * 1240 / 1600)}"
    high = f"{round(width * 330 / 720)} {round(height * 460 / 1600)}"
    scroll = f"input swipe {low} {high} 410; sleep .7; input swipe {high} {low} 410; sleep .7; "
    shell(tabs + scroll + home)
    metrics = {"frames": "Total frames rendered", "janky": "Janky frames", "p50_ms": "50th percentile", "p95_ms": "95th percentile"}
    rows = []
    for iteration in range(1, 4):
        for case, action in (("nav", tabs * 2), ("scroll", scroll * 3)):
            shell(home)
            shell(f"dumpsys gfxinfo {PACKAGE} reset >/dev/null; " + action)
            stats = shell(f"dumpsys gfxinfo {PACKAGE}")
            rows.append(dict(run=iteration, scene=case, **{key: int(re.search(label + r": (\d+)", stats)[1])
                for key, label in metrics.items()}))
    if not any(node.get("text") == TABS[0] for node in nodes()):
        raise ValueError("未回到应用内一级页面，本轮无效。")
    print(json.dumps(dict(package=PACKAGE, version=shell(f"dumpsys package {PACKAGE} | grep versionName").strip(),
        size=size, blur=args.blur, runs=rows), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
