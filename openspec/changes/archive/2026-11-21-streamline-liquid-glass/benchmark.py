import re
import time
import json
import argparse
import subprocess
from pathlib import Path
from xml.etree import ElementTree


def benchmark(device, output):
    package = "com.example.bilimonitor"

    def adb(*args):
        return subprocess.check_output(["adb", "-s", device, *args])

    def shell(command):
        return adb("shell", command).decode("utf-8", errors="replace")

    if "1080x2600" not in shell("wm size"):
        raise ValueError("请使用 1080×2600 的专用测试模拟器并先打开合成主播首页")
    dump = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    begin, end = dump.find(b"<?xml"), dump.rfind(b"</hierarchy>")
    if begin < 0 or end < begin:
        raise RuntimeError("无法读取当前窗口；请解锁并确认测试应用在前台")
    tree = ElementTree.fromstring(dump[begin:end + len(b"</hierarchy>")])
    if not any(node.get("package") == package and node.get("text", "").startswith("Studio ")
               for node in tree.iter("node")):
        raise ValueError("测试应用未打开合成主播首页")
    centers = {}
    for node in tree.iter("node"):
        if node.get("text") in ("主播", "历史", "统计", "设置"):
            left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
            centers[node.get("text")] = f"{(left + right) // 2} {(top + bottom) // 2}"
    nav = "".join(f"input tap {centers[name]}; sleep .32; " for name in ("历史", "统计", "设置", "主播"))
    scroll = "input swipe 490 1810 490 820 350; sleep .7; input swipe 490 820 490 1810 350; sleep .7; "
    shell(nav + scroll)
    time.sleep(.4)
    output.with_suffix(".png").write_bytes(adb("exec-out", "screencap", "-p"))
    samples = []
    for _ in range(3):
        for case, commands in (("scroll", scroll * 4), ("nav", nav * 3)):
            shell(f"dumpsys gfxinfo {package} reset >/dev/null; " + commands)
            text = shell(f"dumpsys gfxinfo {package}")
            fields = {"frames": "Total frames rendered", "janky": "Janky frames",
                      "p50": "50th percentile", "p95": "95th percentile"}
            result = {key: int(re.search(label + r": (\d+)", text).group(1)) for key, label in fields.items()}
            print(case, result, flush=True)
            samples.append((case, result))
    output.with_suffix(".json").write_text(json.dumps(samples), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="比较两版 Debug 包的固定手势；需 adb、合成数据及前台首页。")
    parser.add_argument("device")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    benchmark(args.device, args.output)
