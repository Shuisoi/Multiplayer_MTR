#!/usr/bin/env python3
"""核对「停车对标牌子」真的登记进了引擎：**设计表 ↔ 登记表 ↔ 世界** 三方对账。

为什么要三方：牌子的位置写在存档里（用 `mark_stop_markers.py` 摆、并在摆完读回），
而"这块牌子对应车头停在哪"是**引擎**算出来的（登记表里的 fraction）。两边的数只有放在一起看，
才能确认"摆的位置"和"引擎理解的位置"是同一个点 —— 这正是 P2 停车基准的全部内容。

三个数据来源：
1. 设计表：`stop_table.row(...)` + `mark_stop_markers.plan(...)`（与本仓库铺牌子的算法同一个函数）；
2. 登记表：HTTP `GET /mtr/api/map/mmtr-stop-markers`（引擎只读出口，设计 §5.5）；
3. 引擎指令回话：`POST /mtr/api/map/mmtr-command {"command":"stop-marker list"}`（人看的清单）。

用法（默认按 TEST 两个站台面来核）：

    python mmtr/tools/stop-mark/check_stop_markers.py --apply

`--apply` 只是"允许连 HTTP"（默认也会连，这个开关留着是为了以后能只做离线计算）。
其它站台用 `--face x:y:facing:direction:platformHex`（可重复）指定。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.request
from types import SimpleNamespace

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from mark_stop_markers import cars_list, plan  # noqa: E402

ENGINE_BASE = "http://127.0.0.1:8888/mtr/api/map"
TOLERANCE = 0.002


def http_get(path: str) -> dict:
    with urllib.request.urlopen(ENGINE_BASE + path, timeout=15) as response:
        envelope = json.loads(response.read().decode("utf-8"))
    # ★ 出口统一带信封：{code,currentTime,text,version,data:{…}} —— 载荷在 data 里。
    #   （第一版直接读顶层 markers，于是永远读到空的 0 条，白白怀疑了引擎一轮。）
    return envelope.get("data", envelope)


def http_command(command: str) -> dict:
    request = urllib.request.Request(
        ENGINE_BASE + "/mmtr-command",
        data=json.dumps({"command": command}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def parse_face(token: str):
    parts = token.split(":")
    if len(parts) != 5:
        raise argparse.ArgumentTypeError("--face 需要 x:y:facing:direction:platformHex")
    return {
        "x": int(parts[0]),
        "y": int(parts[1]),
        "facing": parts[2],
        "direction": parts[3],
        "platform_hex": parts[4],
    }


def planned_rows(face: dict, args) -> list:
    namespace = SimpleNamespace(
        x=face["x"], y=face["y"], facing=face["facing"], variant=args.variant,
        direction=face["direction"], z1=args.z1, z2=args.z2, apg_phase=args.apg_phase,
        cars_min=args.cars_min, cars_max=args.cars_max, cars=args.cars, platform_hex=face["platform_hex"],
        platform_id=0, label="",
        # 轴口径：axis=x 时沿轴用 --along-lo/--along-hi、横向用 --face 的第一段（cross）
        axis=args.axis, along_lo=args.along_lo if args.along_lo is not None else args.z1,
        along_hi=args.along_hi if args.along_hi is not None else args.z2, cross=face["x"],
    )
    return plan(namespace)


def main() -> int:
    parser = argparse.ArgumentParser(description="核对停车对标牌子的登记表")
    parser.add_argument("--face", action="append", type=parse_face, default=None,
                        help="x:y:facing:direction:platformHex（可重复；默认按 TEST 两个站台面）")
    parser.add_argument("--axis", default="z", choices=["z", "x"],
                        help="轨沿哪个轴：z=TEST（默认）；x=下水那 10 座站（--face 第一段是牌子所在 z）")
    parser.add_argument("--along-lo", type=int, default=None, help="沿站台轨的下端（axis=x 时是 x1）")
    parser.add_argument("--along-hi", type=int, default=None, help="沿站台轨的上端（axis=x 时是 x2）")
    parser.add_argument("--variant", type=int, default=0)
    parser.add_argument("--z1", type=int, default=800)
    parser.add_argument("--z2", type=int, default=1020)
    parser.add_argument("--apg-phase", type=float, default=822)
    parser.add_argument("--cars-min", type=int, default=2)
    parser.add_argument("--cars-max", type=int, default=10)
    parser.add_argument("--cars", default="", help="只对账这些辆数（逗号分隔，如 8,10）；给了就覆盖 min/max")
    parser.add_argument("--apply", action="store_true", help="允许连引擎 HTTP（默认也连；留着做离线开关）")
    args = parser.parse_args()

    faces = args.face or [
        {"x": -45, "y": 68, "facing": "east", "direction": "north", "platform_hex": "E041E8FCA3EB548B"},
        {"x": -35, "y": 68, "facing": "west", "direction": "south", "platform_hex": "83F73AF9F3A78AE3"},
    ]

    expected = []
    for face in faces:
        for row in planned_rows(face, args):
            expected.append((face, row))

    print(f"设计表：{len(expected)} 块（每个站台面 {len(cars_list(args))} 个辆数 × 方向）")
    # 只读出口是暖缓存（TTL 1 s，过期的那一次读负责触发重算），而且**首次扫描到登记齐**可能
    # 要十几秒（游戏端按 tick 分批扫已加载区块；刚 `/setblock` 出来的区块也可能先被卸载）。
    # 所以这里连读到"条数够"为止（最多 25 次、每次隔 2 s），拿不到就如实报最后那一份。
    feed = None
    for attempt in range(25):
        try:
            feed = http_get("/mmtr-stop-markers")
        except Exception as exception:  # noqa: BLE001
            print(f"[NG] 读不到引擎只读出口 {ENGINE_BASE}/mmtr-stop-markers：{exception}")
            print("  （旧引擎 jar 里没有这个出口 —— 404 就是「还没换 jar」的证据）")
            return 2
        count = len(feed.get("markers", []))
        if count >= len(expected) or attempt == 24:
            break
        if attempt % 3 == 0:
            print(f"  …登记表还只有 {count}/{len(expected)} 条，等它扫齐（第 {attempt + 1} 次）")
        time.sleep(2)
    markers = feed.get("markers", [])
    print(f"登记表：{feed.get('count', len(markers))} 条")
    actual = {(int(m["x"]), int(m["y"]), int(m["z"])): m for m in markers}

    problems = 0
    print()
    print(f"{'牌位 (x,y,z)':>18} {'辆':>3} {'方向':>7} {'设计 fraction':>13} {'登记 fraction':>13} {'偏差':>8}  站台 / 冲突")
    for face, row in expected:
        key = (row["x"], row["y"], row["z"])
        marker = actual.get(key)
        if marker is None:
            print(f"{str(key):>18} {row['cars']:>3} {row['direction']:>7} {row['fraction']:>13.4f} {'—':>13} {'—':>8}  [NG] 登记表里没有")
            problems += 1
            continue
        delta = float(marker.get("fraction", -1)) - row["fraction"]
        conflict = marker.get("conflict")
        flag = "" if abs(delta) <= TOLERANCE else "  [NG] 偏差过大"
        if conflict:
            flag += "  [NG] 冲突"
        if flag:
            problems += 1
        print(f"{str(key):>18} {row['cars']:>3} {row['direction']:>7} {row['fraction']:>13.4f} "
              f"{float(marker.get('fraction', -1)):>13.4f} {delta:>+8.4f}  "
              f"{marker.get('platformHex', '')[:8]} / {conflict}{flag}")

    # 只读出口是**全局**登记表：别的站的牌子不该算"多余"。所以按本次设计表涉及的 platformHex 收窄。
    scoped_hexes = {face["platform_hex"] for face in faces if face.get("platform_hex")}
    extra = {key for key, marker in actual.items()
             if key not in {(row["x"], row["y"], row["z"]) for _, row in expected}
             and (not scoped_hexes or marker.get("platformHex", "") in scoped_hexes)}
    if extra:
        print(f"  ！登记表里多出 {len(extra)} 条不在本次设计表里的牌子：{sorted(extra)}")

    print()
    try:
        listing = http_command("stop-marker list")
        for line in listing.get("data", {}).get("lines", [])[:40]:
            print("  " + line)
    except Exception as exception:  # noqa: BLE001
        print(f"  （stop-marker list 没答上来：{exception}）")

    print()
    if problems == 0 and not extra:
        print(f"[OK] {len(expected)} 块牌子：位置、辆数、方向、引擎算出的 fraction 全部与设计表一致")
        return 0
    print(f"[NG] 有 {problems} 处不一致" + (f"，另有 {len(extra)} 条多余登记" if extra else ""))
    return 1


if __name__ == "__main__":
    sys.exit(main())
