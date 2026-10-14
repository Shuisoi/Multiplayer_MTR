#!/usr/bin/env python3
"""铺「停车对标牌子」：按对标表算出每个辆数的牌位，摆 `mtr:stop_marker` 并写 NBT。

为什么要脚本：一面站台 9 块牌子，牌位是**算出来的小数**（例如 2 辆北行 890.225），
落到方块格上只能取最近的一格（方块中心 = 整数 + 0.5，残差 ±0.275 m —— 见下），
手摆必然对不上；而且每一步都要"先存盘再读回"才能算是真做成了。

牌位表来自同目录的 `stop_table.py`（那里的依据是 notes/420/421 的实测）：
车头前脸必须满足 `车头 + 5.025 ≡ 屏蔽门洞中心 (mod 6)`，牌子记的是**驾驶室侧窗**
（车头前脸后方 1.25 m），所以 `牌位 = 车头 + 1.25`（北行）或 `牌位 = 车头 − 1.25`（南行）。

**方块栅格是这套方案的精度下限**：牌位理想值落在 `*.225` / `*.775`，而方块中心的栅格是 `*.5`，
最近的一格差 **±0.275 m**（引擎侧按方块中心投影，见 `MmtrStopMarkerGeometry`）。
0.275 < 屏蔽门对位容差（±0.45 m，notes/420 §容差），所以可用；要更准只能改引擎按角点解释（不建议）。

用法：

    # 干跑：出计划 + 读存档核对（默认会 save-all flush 后读）
    python mmtr/tools/stop-mark/mark_stop_markers.py --x -45 --y 66 --facing west \
        --direction north --platform-hex E041E8FCA3EB548B --variant 0

    # 真摆
    python mmtr/tools/stop-mark/mark_stop_markers.py ... --apply

    # 清掉同一范围内的牌子
    python mmtr/tools/stop-mark/mark_stop_markers.py --x -45 --y 66 --clear --clear-z1 800 --clear-z2 1020 --apply
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import time

# 本文件在 <工作区>/mmtr/tools/stop-mark/ 下
HERE = os.path.dirname(os.path.abspath(__file__))
WORKSPACE = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(WORKSPACE, "sandbox"))

from stop_table import REFERENCES, row  # noqa: E402
from read_block import load_chunk_nbt, palette_entry_to_string, unpack_states  # noqa: E402
from rcon import Rcon  # noqa: E402

REGION = os.path.join(WORKSPACE, "mmtr", "game", "fabric", "run", "world", "region")
BLOCK_ID = "mtr:stop_marker"

#: 牌面文字/位置都按"驾驶室侧窗"这个参照点（notes/420 §参照点）
REFERENCE = "驾驶室侧窗"
#: 方块中心的栅格与理想牌位之间的残差上限（超出就说明表或参数有问题，不是"差一点"）
MAX_GRID_ERROR_M = 0.45
STYLES = {"pole": 0, "hanging": 1, "wall": 2}


def scan(pattern: re.Pattern, x1: int, x2: int, y1: int, y2: int, z1: int, z2: int):
	"""一次过扫盒子里的命中方块（与 mmtr/tools/station-lights/clear_lights.py 同一套读档）。"""
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


def cars_list(args) -> list:
	"""要摆哪些辆数：给了 `--cars 8,10` 就只摆这些（覆盖 --cars-min/--cars-max）。

	为什么要挑着摆：站台上"一格一辆数"的牌子在短编组时非常密（2…7 辆那一排基本用不上），
	双向股道还要再乘 2；现场按需只留 8 辆与 10 辆时，用 --cars 精确点名比挪 min/max 清楚。
	"""
	if getattr(args, "cars", "").strip():
		return sorted({int(part) for part in args.cars.split(",") if part.strip()})
	return list(range(args.cars_min, args.cars_max + 1))


def along_params(args) -> tuple:
	"""返回 (along_lo, along_hi, cross)：站台轨"沿站台方向"的两端 与 "横向"的那一列。

	轴口径（2026-10-13 加，为"把站台铺到其它站"服务）：
	* `--axis z`（默认，TEST）：轨沿 z 走 ⇒ `--z1/--z2` 是沿轴两端、`--x` 是牌子所在那一列 x；
	* `--axis x`：轨沿 x 走（下水/上水村/… 那 10 座站）⇒ `--along-lo/--along-hi` 是沿轴两端 x、
	  `--cross` 是牌子所在那一行 z。老开关 `--x/--z1/--z2` 在 axis=x 时语义改为
	  "cross/along_lo/along_hi"（照样认，省得两套名字）。
	"""
	axis = getattr(args, "axis", "z")
	along_lo = getattr(args, "along_lo", None)
	along_hi = getattr(args, "along_hi", None)
	cross = getattr(args, "cross", None)
	if along_lo is None:
		along_lo = args.z1
	if along_hi is None:
		along_hi = args.z2
	if cross is None:
		cross = args.x
	if along_lo is None or along_hi is None or cross is None:
		raise SystemExit("要给 (--along-lo/--along-hi/--cross) 或 (--z1/--z2/--x)")
	return int(along_lo), int(along_hi), int(cross), axis


def facing_for(axis: str, direction: str) -> str:
	"""牌面朝"来车"那一侧（与 TEST 的实机口径一致）。

	TEST（axis=z）：北行 = 车头在小 z 一侧 ⇒ 车从大 z 来 ⇒ 牌面朝 south；南行朝 north。
	axis=x：沿轴两端 x1<x2，"朝轨端0"= 车头在小 x 一侧 ⇒ 车从大 x 来 ⇒ 牌面朝 **east**；
	        另一方向朝 **west**。方向仍用 north/south 两个词，含义是"朝轨端0 / 朝轨端1"。
	"""
	if axis == "x":
		return "east" if direction == "north" else "west"
	return "south" if direction == "north" else "north"


def plan(args) -> list:
	"""算出这一面站台要摆的每块牌子：辆数 / 方向 / 牌位（理想与落的格）/ 车头停点 / 引擎侧期望 fraction。"""
	along_lo, along_hi, cross, axis = along_params(args)
	rows = []
	directions = ["north", "south"] if args.direction == "both" else [args.direction]
	for cars in cars_list(args):
		r = row(cars, along_lo, along_hi, args.apg_phase, REFERENCE)
		for direction in directions:
			ideal = r["marker_north"] if direction == "north" else r["marker_south"]
			block_along = int(round(ideal - 0.5))  # 方块中心 = block_along + 0.5，取最近的整数
			center = block_along + 0.5
			head = ideal + (1.25 if direction == "north" else -1.25)
			if direction == "north":
				# 朝轨端0（axis=z 时即小 z）⇒ 车头弧长 = 牌位弧长 − datum
				# 弧长原点 = 节点坐标 + 0.5：MTR 的轨中心线相对节点方块坐标平移了 +0.5
				# （RailMath.getPosition 里的 +0.5；引擎 MmtrStopMarkerGeometry 也按这条量）
				head_arc = (center - (along_lo + 0.5)) - REFERENCES[REFERENCE]
				toward_end0 = True
			else:
				head_arc = (center - (along_lo + 0.5)) + REFERENCES[REFERENCE]
				toward_end0 = False
			facing = args.facing if args.facing != "auto" else facing_for(axis, direction)
			x, z = (block_along, cross) if axis == "x" else (cross, block_along)
			rows.append({
				"cars": cars,
				"direction": direction,
				"ideal": ideal,
				"x": x,
				"y": args.y,
				"z": z,
				"axis": axis,
				"along": block_along,
				"cross": cross,
				"facing": facing,
				"center": center,
				"grid_error": center - ideal,
				"head": head,
				"fraction": head_arc / (along_hi - along_lo),
				"toward_end0": toward_end0,
				"head_world_z": (along_lo + 0.5) + head_arc,
			})
	return rows


def _is_air(state: str) -> bool:
	"""这一格是不是空气（三种空气都算：air / cave_air / void_air）。"""
	return state in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air")


def print_plan(rows: list, args) -> None:
	along_lo, along_hi, cross, axis = along_params(args)
	print(f"牌位计划（{len(rows)} 块）｜{'牌子列 x' if axis == 'z' else '牌子行 z'}={cross} y={args.y} "
		f"facing={'auto(按方向)' if args.facing == 'auto' else args.facing} variant={args.variant}（{args.variant} = "
		f"{[k for k, v in STYLES.items() if v == args.variant][0]}）")
	print(f"  站台轨 沿轴 {'z' if axis == 'z' else 'x'}={along_lo}…{along_hi}（axis={axis}）｜屏蔽门洞中心 = {args.apg_phase} + 6k｜"
		f"参照点 = {REFERENCE}（车头前脸后 {REFERENCES[REFERENCE]} m）")
	print(f"  {'辆':>3} {'方向':>5} {'facing':>7} {'理想牌位':>10} {'落格':>7} {'方块中心':>9} {'栅格残差':>9} {'车头停点':>11} {'引擎 fraction':>13} 方向判定")
	for r in rows:
		flag = "★残差偏大" if abs(r["grid_error"]) > MAX_GRID_ERROR_M else ""
		print(f"  {r['cars']:>3} {r['direction']:>5} {r['facing']:>7} {r['ideal']:>10.3f} {r['along']:>7d} {r['center']:>9.2f} "
			f"{r['grid_error']:>+9.3f} {r['head']:>11.3f} {r['fraction']:>13.4f} "
			f"{'朝轨端0' if r['toward_end0'] else '朝轨端1'} {flag}")
	print(f"  牌位落格：(x,y,z) = " + ", ".join(f"({r['x']},{r['y']},{r['z']})" for r in rows))


def build_commands(rows: list, args) -> list:
	commands = []
	for r in rows:
		commands.append(f"setblock {r['x']} {r['y']} {r['z']} {BLOCK_ID}[facing={r['facing']},variant={args.variant}]")
		nbt = f'{{cars:{r["cars"]},datumM:{REFERENCES[REFERENCE]}d'
		if args.platform_hex:
			nbt += f',platformHex:"{args.platform_hex}"'
		if args.platform_id:
			nbt += f",platformId:{args.platform_id}L"
		if args.label:
			nbt += f',label:"{args.label}"'
		nbt += "}"
		commands.append(f"data merge block {r['x']} {r['y']} {r['z']} {nbt}")
	return commands


def main() -> int:
	ap = argparse.ArgumentParser(description="铺停车对标牌子")
	ap.add_argument("--x", type=int, default=None, help="牌子所在那一列（axis=z）/ 那一行 z（axis=x）")
	ap.add_argument("--y", type=int, required=True, help="牌子所在 y（站台面上一格）")
	ap.add_argument("--axis", default="z", choices=["z", "x"], help="轨沿哪个轴走：z=TEST 那种（默认），x=下水那种")
	ap.add_argument("--along-lo", type=int, default=None, help="沿站台方向的下端（axis=x 时是 x1）")
	ap.add_argument("--along-hi", type=int, default=None, help="沿站台方向的上端（axis=x 时是 x2）")
	ap.add_argument("--cross", type=int, default=None, help="横向那一列/行（axis=x 时是牌子所在 z）")
	ap.add_argument("--facing", default="west", choices=["north", "east", "south", "west", "auto"],
		help="牌面朝哪边；auto = 按 axis+方向自动取（朝来车）")
	ap.add_argument("--variant", type=int, default=0, choices=[0, 1, 2], help="0 立杆 / 1 吊挂 / 2 贴墙")
	ap.add_argument("--direction", default="north", choices=["north", "south", "both"], help="这份牌子对应哪个行车方向")
	ap.add_argument("--z1", type=int, default=800, help="站台轨北端（axis=z；对齐 rail mmtrOrderedPositions()[0]）")
	ap.add_argument("--z2", type=int, default=1020)
	ap.add_argument("--apg-phase", type=float, default=822, help="第一个屏蔽门洞中心的 z")
	ap.add_argument("--cars-min", type=int, default=2)
	ap.add_argument("--cars-max", type=int, default=10)
	ap.add_argument("--cars", default="", help="只摆这些辆数（逗号分隔，如 8,10）；给了就覆盖 --cars-min/--cars-max")
	ap.add_argument("--platform-hex", default="", help="显式归属的站台 hex（写进牌子 NBT；留空=按位置解析）")
	ap.add_argument("--platform-id", type=int, default=0, help="显式归属的站台十进制 id")
	ap.add_argument("--label", default="", help="牌面文字（留空=按辆数显示）")
	ap.add_argument("--apply", action="store_true")
	ap.add_argument("--clear", action="store_true", help="把 --clear-z1..--clear-z2 范围内的牌子清成空气")
	ap.add_argument("--clear-z1", type=int, default=0)
	ap.add_argument("--clear-z2", type=int, default=0)
	ap.add_argument("--no-flush", action="store_true", help="跳过 save-all flush（默认先 flush 再读）")
	ap.add_argument("--allow-floating", action="store_true", help="允许牌子下面那格是空气（吊挂式挂在雨棚下时用）")
	args = ap.parse_args()

	if args.clear:
		return clear_mode(args)

	rows = plan(args)
	print_plan(rows, args)
	bad = [r for r in rows if abs(r["grid_error"]) > MAX_GRID_ERROR_M]
	if bad:
		print(f"  ★ 有 {len(bad)} 块的栅格残差超过 {MAX_GRID_ERROR_M} m —— 先查表/参数，不要硬摆")
		return 1
	# 两个方向的牌位理论上差 2.5 m，四舍五入后可能落到同一格（短站台/极端辆数时）；
	# 那样两块牌子会互相顶掉，最后少一块 —— 必须当成错误，不能"摆完少一块"了事。
	seen = {}
	for r in rows:
		key = (r["x"], r["y"], r["z"])
		if key in seen:
			print(f"  ★ {r['cars']} 辆 {r['direction']} 与 {seen[key]} 落到同一格 z={r['z']} —— 两个方向挨得太近，不能都摆在这一列")
			return 1
		seen[key] = f"{r['cars']} 辆 {r['direction']}"

	if not args.no_flush:
		with Rcon() as rcon:
			rcon.command("save-all flush")
		time.sleep(2)

	# 现状核对：目标格必须是空的（或已经是同款牌子），至少下面一格要有东西托着。
	#
	# 为什么必须连"目标格上原来是什么"一起看：牌子常常摆在站台边缘那一列，而那一列上可能已经有
	# 屏蔽门/玻璃/天花板灯 —— 直接 setblock 会把它们**悄悄顶掉**（现场踩过的同类事故：
	# 铺护栏时盖掉了信号灯杆）。所以这里一律按"读档看到的原文方块"判，非空气就拦。
	all_pattern = re.compile(r".")
	xs = [r["x"] for r in rows]
	zs = [r["z"] for r in rows]
	box_x1, box_x2 = min(xs), max(xs)
	z1, z2 = min(zs), max(zs)
	cells, missing = scan(all_pattern, box_x1, box_x2, args.y, args.y, z1, z2)
	for cx, cz, error in missing[:3]:
		print(f"  ! 区块 ({cx},{cz}) 读不到：{error}")
	state_at = {(x, y, z): state for x, y, z, state in cells}
	floors = {}
	if not args.allow_floating:
		floor_cells, _ = scan(all_pattern, box_x1, box_x2, args.y - 1, args.y - 1, z1, z2)
		floors = {(x, y, z): state for x, y, z, state in floor_cells}
	already = sum(1 for r in rows if str(state_at.get((r["x"], r["y"], r["z"]))).startswith(BLOCK_ID))
	print(f"  现状：这一列 y={args.y} 上已经有 {already} 块 {BLOCK_ID}（范围内共扫到 {len(cells)} 格）")
	blockers = []
	for r in rows:
		key = (r["x"], r["y"], r["z"])
		state = state_at.get(key)
		if state is None:
			blockers.append((r, "读不到这一格（区块没扫到？）"))
		elif state.startswith(BLOCK_ID):
			if f"variant={args.variant}" not in state:
				blockers.append((r, "已有牌子但样式不同：" + state))
		elif not _is_air(state):
			blockers.append((r, "目标格上已经有 " + state + "（会被顶掉，先挪牌位或换 --y）"))
		elif not args.allow_floating:
			floor = floors.get((r["x"], r["y"] - 1, r["z"]))
			if floor is None:
				blockers.append((r, "读不到下面那一格"))
			elif _is_air(floor):
				blockers.append((r, "下面一格是空气（牌子会悬空；吊挂式请加 --allow-floating）"))
	for r, why in blockers:
		print(f"  ! {r['cars']} 辆 {r['direction']} z={r['z']}：{why}")
	if blockers:
		print("  （先修好这些再 --apply；干跑到此为止）")
		return 1

	commands = build_commands(rows, args)
	if not args.apply:
		for command in commands:
			print("  " + command)
		print("（干跑：加 --apply 才真的摆）")
		return 0

	changed = noop = failed = 0
	with Rcon() as rcon:
		for command in commands:
			reply = rcon.command(command).strip()
			if command.startswith("data merge"):
				if "Modified block data" in reply:
					changed += 1
				elif "Nothing changed" in reply:
					# 重铺同一块牌子：NBT 已经是这些值 ⇒ 不是失败（与 /setblock 的
					# "Could not set the block" 同一类：命令成功、只是没有变化）
					noop += 1
				else:
					print(f"  [失败] {command} -> {reply[:160]}")
					failed += 1
			elif "Changed the block" in reply:
				changed += 1
			elif "Could not set the block" in reply:
				noop += 1  # 1.20 里"新旧状态相同"也回这句
			else:
				print(f"  [失败] {command} -> {reply[:160]}")
				failed += 1
		rcon.command("save-all flush")
	time.sleep(2)

	print(f"下发 {len(commands)} 条：成功 {changed} ｜ 无变化 {noop} ｜ 失败 {failed}")
	left, _ = scan(re.compile(re.escape(BLOCK_ID)), box_x1, box_x2, args.y, args.y, z1, z2)
	want = {(r["x"], r["y"], r["z"]) for r in rows}
	got = {(x, y, z) for x, y, z, _ in left}
	print(f"  复读：牌子 {len(got)} 块（应有 {len(want)}）；缺 {sorted(want - got)}")
	verify_nbt(args, rows)
	return 1 if (failed or (want - got)) else 0


def verify_nbt(args, rows: list) -> None:
	"""逐块 `/data get block` 核对 NBT：方块在 ≠ 牌子认这个辆数。"""
	print("  NBT 核对（/data get block）：")
	problems = 0
	with Rcon() as rcon:
		for r in rows:
			reply = rcon.command(f"data get block {r['x']} {r['y']} {r['z']}").strip()
			if f"cars: {r['cars']}" not in reply and f"cars:{r['cars']}" not in reply:
				print(f"    [不一致] {r['cars']} 辆 z={r['z']} -> {reply[:160]}")
				problems += 1
	print(f"    {len(rows) - problems}/{len(rows)} 块 NBT 对得上辆数" + ("" if problems == 0 else f"（{problems} 块不一致）"))


def clear_mode(args) -> int:
	along_lo, along_hi, cross, axis = along_params(args)
	z1, z2 = args.clear_z1, args.clear_z2
	if z1 == 0 and z2 == 0:
		z1, z2 = along_lo, along_hi
	if axis == "x":
		box_x1, box_x2, bz1, bz2 = z1, z2, cross, cross
	else:
		box_x1, box_x2, bz1, bz2 = cross, cross, z1, z2
	pattern = re.compile(re.escape(BLOCK_ID))
	if not args.no_flush:
		with Rcon() as rcon:
			rcon.command("save-all flush")
		time.sleep(2)
	hits, _ = scan(pattern, box_x1, box_x2, args.y, args.y, bz1, bz2)
	print(f"清理范围 axis={axis} 沿轴 {z1}…{z2}（横向 {cross}）y={args.y}：命中 {len(hits)} 块 {BLOCK_ID}")
	commands = [f"setblock {x} {y} {z} air" for x, y, z, _ in hits]
	if not args.apply:
		for command in commands:
			print("  " + command)
		print("（干跑：加 --apply 才真的清）")
		return 0
	failed = 0
	with Rcon() as rcon:
		for command in commands:
			reply = rcon.command(command).strip()
			if "Changed the block" not in reply and "Could not set the block" not in reply:
				print(f"  [失败] {command} -> {reply[:120]}")
				failed += 1
		rcon.command("save-all flush")
	time.sleep(2)
	left, _ = scan(pattern, box_x1, box_x2, args.y, args.y, bz1, bz2)
	print(f"复读：剩余 {len(left)} 块" + ("" if not left else f" -> {[h[2] for h in left]}"))
	return 1 if (failed or left) else 0


if __name__ == "__main__":
	sys.exit(main())
