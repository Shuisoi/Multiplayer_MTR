#!/usr/bin/env python3
"""把盒子里匹配的方块**换成**另一种（默认只干跑）。

判据纪律与 `clear_lights.py` 同一套：**先 `save-all flush` 再读存档**、**改完再复读验收**。
先干跑看"命中多少格、都在哪些 y/x"，再 `--apply`。

用法：

    # TEST 站雨棚：把石砖弧那 6 格/列换成海晶灯（穹顶两列玻璃保留）
    python mmtr/tools/station-lights/replace_in_box.py \
        --x1 -52 --x2 -28 --z1 800 --z2 1020 --y1 70 --y2 73 \
        --from "minecraft:stone_bricks" --to minecraft:sea_lantern

    # 连穹顶玻璃一起换
    ... --from "minecraft:(stone_bricks|glass)" --to minecraft:sea_lantern

    # 真改
    ... --apply
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clear_lights import report, scan  # noqa: E402

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "sandbox"))
from rcon import Rcon  # noqa: E402


def main() -> int:
	ap = argparse.ArgumentParser(description="盒子内按方块 id 正则整体替换")
	ap.add_argument("--x1", type=int, required=True)
	ap.add_argument("--x2", type=int, required=True)
	ap.add_argument("--z1", type=int, required=True)
	ap.add_argument("--z2", type=int, required=True)
	ap.add_argument("--y1", type=int, required=True)
	ap.add_argument("--y2", type=int, required=True)
	ap.add_argument("--from", dest="source", required=True, help="被换掉的方块 id 正则")
	ap.add_argument("--to", dest="target", required=True, help="换成什么（完整方块状态，如 minecraft:sea_lantern）")
	ap.add_argument("--apply", action="store_true")
	ap.add_argument("--no-flush", action="store_true")
	args = ap.parse_args()

	source = re.compile(args.source, re.IGNORECASE)
	box = dict(x1=args.x1, x2=args.x2, z1=args.z1, z2=args.z2, y1=args.y1, y2=args.y2)

	if not args.no_flush:
		with Rcon() as rcon:
			rcon.command("save-all flush")
		time.sleep(2)

	hits, missing = scan(source, **box)
	for cx, cz, error in missing[:5]:
		print(f"  ! 区块 ({cx},{cz}) 读不到：{error}")
	report(hits, f"命中（{args.source} → {args.target}，盒 x={args.x1}..{args.x2} y={args.y1}..{args.y2} z={args.z1}..{args.z2}）")
	if not hits:
		return 0

	by_z_level = Counter(y for _, y, _, _ in hits)
	print("  y 分布：" + " ｜ ".join(f"y={y}:{n}" for y, n in sorted(by_z_level.items())))
	commands = [f"setblock {x} {y} {z} {args.target}" for x, y, z, _ in hits]
	if not args.apply:
		for command in commands[:8]:
			print("  " + command)
		if len(commands) > 8:
			print(f"  …（其余 {len(commands) - 8} 条）")
		print("（干跑：加 --apply 才真的改）")
		return 0

	changed = noop = failed = 0
	with Rcon() as rcon:
		for command in commands:
			reply = rcon.command(command).strip()
			if "Changed the block" in reply:
				changed += 1
			elif "Could not set the block" in reply:
				noop += 1
			else:
				print(f"  [失败] {command} -> {reply[:120]}")
				failed += 1
		rcon.command("save-all flush")
	time.sleep(2)

	print(f"下发 {len(commands)} 条：改了 {changed} ｜ 本来就是目标方块 {noop} ｜ 失败 {failed}")
	left, _ = scan(source, **box)
	report(left, "复读验收（剩下的旧方块）")
	now, _ = scan(re.compile(re.escape(args.target), re.IGNORECASE), **box)
	report(now, "复读验收（新方块）")
	return 1 if (failed or left) else 0


if __name__ == "__main__":
	sys.exit(main())
