import argparse
import csv
import io
import json
import re
import subprocess
from xml.etree import ElementTree


PACKAGE = "com.example.bilimonitor"
SOURCES = ("主播", "历史", "统计")


def profile(text):
    rows = {}
    for block in text.split("---PROFILEDATA---")[1::2]:
        for row in csv.DictReader(io.StringIO(block.strip())):
            if row.get("Flags") != "0":
                continue
            start, end = (int(row[key]) for key in ("HandleInputStart", "SyncQueued"))
            if 0 < start < end:
                rows[int(row["IntendedVsync"])] = (end - start) / 1e6
    return rows


def main():
    parser = argparse.ArgumentParser(description="测试机需停在主播页，提前设置相同的合成数据、壁纸、两项玻璃开关和模糊值。")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()

    def adb(*commands):
        return subprocess.check_output([args.adb, "-s", args.serial, *commands], timeout=60)

    def shell(command):
        return adb("shell", command).decode("utf-8", errors="replace")

    def labels():
        raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        return list(ElementTree.fromstring(raw[raw.index(b"<?xml"):raw.index(b"</hierarchy>") + 12]).iter("node"))

    positions = {}
    for node in labels():
        label = node.get("text")
        if label in (*SOURCES, "设置"):
            left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
            positions[label] = f"{(left+right)//2} {(top+bottom)//2}"
    if len(positions) != 4:
        raise ValueError("未找到四个标签")
    rows = []
    metrics = {"frames": "Total frames rendered", "janky": "Janky frames", "p50": "50th percentile", "p95": "95th percentile"}
    for run in range(3):
        for source in SOURCES[run:] + SOURCES[:run]:
            shell(f"input tap {positions[source]}; sleep .8")
            stats = f"dumpsys gfxinfo {PACKAGE}"
            old_frames = profile(shell(stats + " framestats"))
            shell(f"{stats} reset >/dev/null; input tap {positions['设置']}; sleep .65")
            text = shell(stats + " framestats")
            new_frames = profile(text)
            costs = [duration for key, duration in new_frames.items() if key not in old_frames]
            if not costs or not any(node.get("text") == "开启监控" for node in labels()):
                raise ValueError("无有效设置切入帧")
            rows.append(dict(run=run + 1, source=source, ui_max_ms=round(max(costs), 2), ui_frames=len(costs),
                **{key: int(re.search(label + r": (\d+)", text)[1]) for key, label in metrics.items()}))
    print(json.dumps(rows, ensure_ascii=False))


if __name__ == "__main__":
    main()
