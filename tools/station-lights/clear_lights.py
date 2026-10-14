#!/usr/bin/env python3
"""把一个长方体盒子里的**照明方块**清成空气（默认只干跑）。

为什么做成脚本而不是手敲：清一片照明有两条纪律——
① **先 `save-all flush` 再读存档**（世界还在内存里时读到的方块是旧的）；
② **删完要复读验收**（"发成功"≠"真没了"）。
两条都内置在里面了。

用法：

    # 干跑：列出命中（按方块 id + 按 x 列汇总），不发指令
    python mmtr/tools/station-lights/clear_lights.py --x1 -56 --x2 -24 --z1 790 --z2 1030 --y1 60 --y2 90

    # 真删（服务端在跑），删完自动复读并报"剩余"
    python mmtr/tools/station-lights/clear_lights.py --x1 -56 --x2 -24 --z1 790 --z2 1030 --y1 60 --y2 90 --apply

    # 只清某一种
    python mmtr/tools/station-lights/clear_lights.py ... --match "minecraft:lantern"

默认匹配 = 站台/雨棚那一套照明：吊灯笼、海晶灯、火把、荧石、菌光体、末地烛、红石灯、蜡烛、蛙明灯。
**不含 `mtr:signal_light_*`** —— 那是信号灯，不是照明（`--match` 可以覆盖成任何正则）。
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import time
from collections import Counter

# 本文件在 <工作区>/mmtr/tools/station-lights/ 下
WORKSPACE = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
sys.path.insert(0, os.path.join(WORKSPACE, "sandbox"))
from read_block import load_chunk_nbt, palette_entry_to_string, unpack_states  # noqa: E402
from rcon import Rcon  # noqa: E402

REGION = os.path.join(WORKSPACE, "mmtr", "game", "fabric", "run", "world", "region")

DEFAULT_MATCH = (
	r"minecraft:lantern|minecraft:sea_lantern|minecraft:torch|minecraft:wall_torch|minecraft:soul_torch|"
	r"minecraft:soul_lantern|minecraft:glowstone|minecraft:shroomlight|minecraft:end_rod|"
	r"minecraft:redstone_lamp|minecraft:candle|minecraft:ochre_froglight|minecraft:verdant_froglight|"
	r"minecraft:pearlescent_froglight|minecraft:jack_o_lantern"
)


def scan(pattern: re.Pattern, x1: int, x2: int, z1: int, z2: int, y1: int, y2: int):
	"""一次过：每个区块每个 section 只解包一次，再按调色板查命中。"""
	hits = []
	missing = []
	for cx in range(x1 >> 4, (x2 >> 4) + 1):
		for cz in range(z1 >> 4, (z2 >> 4) + 1):
			root, error = load_chunk_nbt(REGION, cx, cz)
			if root is None:
				missing.append((cx, cz, error))
				continue
			chunk = root
			if "sections" not in chunk and "Sections" not in chunk and "" in chunk:
				chunk = chunk[""]
			sections = chunk.get("sections", chunk.get("Sections"))
			if sections is None:
				missing.append((cx, cz, "没有 sections"))
				continue
			for section in sections:
				section_y = int(section.get("Y", -999))
				if section_y * 16 + 15 < y1 or section_y * 16 > y2:
					continue
				block_states = section.get("block_states")
				if block_states is not None:
					palette, data = block_states.get("palette"), block_states.get("data")
				else:
					palette, data = section.get("Palette"), section.get("BlockStates")
				if palette is None or len(palette) == 0:
					continue
				strings = [palette_entry_to_string(entry) for entry in palette]
				matching = {i for i, s in enumerate(strings) if pattern.search(s)}
				if not matching:
					continue
				if len(palette) == 1 or data is None:
					states = [0] * 4096
				else:
					states = unpack_states(data, max(4, (len(palette) - 1).bit_length()), 4096)
				for x in range(max(x1, cx << 4), min(x2, (cx << 4) + 15) + 1):
					for z in range(max(z1, cz << 4), min(z2, (cz << 4) + 15) + 1):
						for y in range(max(y1, section_y * 16), min(y2, section_y * 16 + 15) + 1):
							index = (((y & 15) * 16) + (z & 15)) * 16 + (x & 15)
							state = states[index]
							if state in matching:
								hits.append((x, y, z, strings[state]))
	return hits, missing


def report(hits, title: str) -> None:
	print(f"{title}：{len(hits)} 格")
	by_id = Counter(state.split("[")[0] for _, _, _, state in hits)
	for block_id in sorted(by_id):
		print(f"  {by_id[block_id]:5d}  {block_id}")
	by_x = Counter(x for x, _, _, _ in hits)
	if by_x:
		print("  按 x 列：" + " ｜ ".join(f"x={x}:{n}" for x, n in sorted(by_x.items())))
	by_y = Counter(y for _, y, _, _ in hits)
	if by_y:
		print("  按 y 层：" + " ｜ ".join(f"y={y}:{n}" for y, n in sorted(by_y.items())))


def main() -> int:
	ap = argparse.ArgumentParser(description="清掉盒子里的照明方块")
	ap.add_argument("--x1", type=int, required=True)
	ap.add_argument("--x2", type=int, required=True)
	ap.add_argument("--z1", type=int, required=True)
	ap.add_argument("--z2", type=int, required=True)
	ap.add_argument("--y1", type=int, default=-64)
	ap.add_argument("--y2", type=int, default=319)
	ap.add_argument("--match", default=DEFAULT_MATCH, help="方块 id 正则（默认=常见照明那一套）")
	ap.add_argument("--apply", action="store_true")
	ap.add_argument("--no-flush", action="store_true", help="跳过 save-all flush（默认先 flush 再读）")
	args = ap.parse_args()

	pattern = re.compile(args.match, re.IGNORECASE)
	box = dict(x1=args.x1, x2=args.x2, z1=args.z1, z2=args.z2, y1=args.y1, y2=args.y2)

	if not args.no_flush:
		with Rcon() as rcon:
			rcon.command("save-all flush")
		time.sleep(2)

	hits, missing = scan(pattern, **box)
	for cx, cz, error in missing[:5]:
		print(f"  ! 区块 ({cx},{cz}) 读不到：{error}")
	report(hits, f"命中（盒 x={args.x1}..{args.x2} y={args.y1}..{args.y2} z={args.z1}..{args.z2}）")
	if not hits:
		return 0

	commands = [f"setblock {x} {y} {z} air" for x, y, z, _ in hits]
	if not args.apply:
		for command in commands[:10]:
			print("  " + command)
		if len(commands) > 10:
			print(f"  …（其余 {len(commands) - 10} 条）")
		print("（干跑：加 --apply 才真的删）")
		return 0

	changed = noop = failed = 0
	with Rcon() as rcon:
		for command in commands:
			reply = rcon.command(command).strip()
			if "Changed the block" in reply:
				changed += 1
			elif "Could not set the block" in reply:
				# 1.20 的 /setblock 在"新旧状态相同"时也回这句 ⇒ 只统计，不报错
				noop += 1
			else:
				print(f"  [失败] {command} -> {reply[:120]}")
				failed += 1
		rcon.command("save-all flush")
	time.sleep(2)

	print(f"下发 {len(commands)} 条：改了 {changed} ｜ 本来就是空气 {noop} ｜ 失败 {failed}")
	left, _ = scan(pattern, **box)
	report(left, "复读验收（剩余）")
	return 1 if (failed or left) else 0


if __name__ == "__main__":
	sys.exit(main())
