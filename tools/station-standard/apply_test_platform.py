#!/usr/bin/env python3
"""把 **TEST 站台** 当作新标准，整体铺到另外 10 座标准站（轨道除外）。

## 为什么是"复制 TEST"

用户口径（2026-10-13）：**「还有乘客标记方块，灯这些，我建议是除了轨道外全部替换一遍」**。
实测两边的**船体完全相同**（`stone_bricks` 16888、`glass` 884，逐块数一致），差别只在
TEST 后来加的那几样：

| 层 | TEST | 另外 10 站实测 |
| --- | --- | --- |
| APG 半高闸机墙 | 两面各 212 列（门洞 30 个，6 m 距） | **没有** |
| 客流标记 `mtr:crowd_flow/mark_1/mark_2/anchor` | 满铺（约 1650 格/面） | 只有 4 格残桩 |
| 雨棚内沿 `minecraft:sea_lantern` | 2 列 × 219 格 | **没有**（那两行 y=70 是空气） |
| 吊灯笼 | 44 盏（**只挂外侧两列**） | 88 盏（4 列都挂，内侧两列与墙/牌子抢位） |
| 停车对标牌子 | 8 块（8/10 辆 × 双向） | **没有** |

⇒ 本工具做"**除轨道外整站替换**"：以 TEST 为模板，做一次**镜像映射**（见下），
只对那些**由本工具负责的方块类型**下 `setblock`，**逐格与存档现状做差**（只发真正变化的格）。

## 映射（TEST 轴 z ↔ 目标站轴 x）

TEST 的轨沿 z（800..1020）、横向 x∈[-52,-28]；目标站的轨沿 x（x1..x2=220 m）、横向 z∈[cz-12,cz+12]。
两站都是 25 格宽、221 格长，所以映射是一一对应的**轴交换**：

    along :  x_目标 = x1 + (z_TEST - 800)          # TEST 的 +z ↔ 目标的 +x
    cross :  z_目标 = cz + (x_TEST + 40)           # TEST 的 +x ↔ 目标的 +z
    y     :  y_目标 = y_TEST + (rail_y_目标 - 65)

轴交换是**镜像**（左右手性互换），所以带方向的方块状态要一起映射：
`facing`/`head` 的 north↔west、south↔east；`side=left/right` 这类"相对左右"的**不在这里复制**
（APG 墙由 `mmtr/tools/psd-wall/gen_apg_wall.py` 按朝向自己算 left/right；`mtr:platform` 的
`door_type/side` 由方块自己随邻居更新）。

## 不动的方块（刻意排除，或另外的工具负责）

* `mtr:rail` —— **绝对不碰**（用户口径"除了轨道外"）
* `mtr:platform` —— 延迟到墙铺好之后由 MTR 邻居更新；本工具不写
* `mtr:apg_*` —— 由 `gen_apg_wall.py` 铺（左右手性必须按朝向重算）
* `mtr:stop_marker` —— 由 `mark_stop_markers.py` 铺（`platformHex` 要逐站改写）
* 自然方块（草/土/沙/石/矿石/树叶/原木/水…）—— 模板里的这些**不复制、也不删**

## 用法

    # 干跑（默认）：出每站差量 + 命令清单，不写世界
    python mmtr/tools/station-standard/apply_test_platform.py --stations 下水

    # 真铺（先自动 save-all flush 再读，铺完再读回复核）
    python mmtr/tools/station-standard/apply_test_platform.py --apply

    # 只出某一站的命令文件（给人看/当回滚底稿）
    python mmtr/tools/station-standard/apply_test_platform.py --stations 下水 --emit sandbox/tmp/cmd-xiazui.txt

回滚：`--emit` 同时写 `<名字>.rollback.txt`（把每格还原成"改之前读到的原文"）。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
WORKSPACE = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(WORKSPACE, "sandbox"))

try:  # Windows 控制台默认 GBK：⚠/★ 这类字符会直接让脚本崩在 print 上
	sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
	pass

from read_block import block_at, load_chunk_nbt, palette_entry_to_string, unpack_states  # noqa: E402
from rcon import Rcon  # noqa: E402

REGION = os.path.join(WORKSPACE, "mmtr", "game", "fabric", "run", "world", "region")

#: 模板：TEST 站（轨沿 z，rail_y=65）
TEMPLATE = {"name": "TEST", "along_lo": 800, "along_hi": 1020, "cross_lo": -52, "cross_hi": -28, "rail_y": 65,
            "y_lo": 63, "y_hi": 74, "center": -40}

#: 目标站：站名 / x1 / x2（站台轨两端）/ 双线中心 z / 轨面 y / 北面 platformHex / 南面 platformHex
STATIONS = [
	("下水", -500, -280, 1803, 65, "AEFA6421680F7EB3", "99DAE30DB9E259D1"),
	("上水村", 800, 1020, 1803, 65, "9255B7521A0BEFA9", "AF6E0426C546ADB0"),
	("莫氏岛", 2300, 2520, 1803, 75, "3039C1B3CC9ECABD", "8044B25EC86F540F"),
	("叶楼村", 3921, 4141, 2312, 71, "369805A06AEB3E03", "544EB03143E23B40"),
	("鸥湾", -1910, -1690, 1390, 74, "0904DC45FCEB31D3", "F34879D2DB12FFF0"),
	("西段2站", -2757, -2537, 1390, 74, "A5A0FE237EC318E9", "66D31C50C1B8C679"),
	("西段3站", -4010, -3790, 1390, 70, "54DAA0B7343633D2", "F7386DA79DE55AF9"),
	("干沙", -5299, -5079, 1390, 71, "745000D173C690FD", "431D7BD670691050"),
	("岩壁", -6401, -6181, 1935, 63, "1ED4CBA9F0DE0990", "F10BE9FFDB81A2F5"),
	("西段6站", -7394, -7174, 1664, 65, "9F9FEA221428909A", "DA7507B730555A72"),
]

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}

#: 模板里允许被复制的结构类型（前缀匹配）
#: 模板里允许被复制的结构类型（前缀匹配）。
#: ⚠ `mtr:crowd_anchor`（归属锚）**刻意不在里面**：它的位置由人决定 ——
#: 实机施工时用户就在现场一站一站自己放（下水 `(-497,66,1808)`、上水村 `(1018,66,1798/1808)`），
#: 工具只"报告"这些多余的锚，既不铺模板里那个、也不删用户的（见 REPORT_ONLY_EXACT）。
PLACE_EXACT = {"minecraft:stone_bricks", "minecraft:glass", "minecraft:sea_lantern", "minecraft:lantern",
               "mtr:crowd_flow", "mtr:crowd_mark_1", "mtr:crowd_mark_2", "mtr:signal_pole"}
PLACE_PREFIX = ("mtr:signal_light",)

#: 目标站里"本工具负责"、允许被删/改的类型（前缀匹配）。刻意不含信号杆/灯、也不含归属锚：
#: 位置若与模板不符，只报告不删（那是玩家/别的工具放的）。
REMOVE_EXACT = {"minecraft:lantern", "minecraft:sea_lantern", "minecraft:glass", "minecraft:stone_bricks",
                "mtr:crowd_flow", "mtr:crowd_mark_1", "mtr:crowd_mark_2"}

#: 只报告、绝不删的方块（玩家口径里它们是"归属/声明"类，位置由人决定）
REPORT_ONLY_EXACT = {"mtr:crowd_anchor"}

#: 刻意不碰（另有工具负责 / 绝对不能动）
EXCLUDED_EXACT = {"mtr:rail", "mtr:platform", "mtr:stop_marker"}
EXCLUDED_PREFIX = ("mtr:apg", "mtr:psd", "mtr:rail")

NON_NATURAL_KEEP = ()  # 保留占位：自然方块不在任何集合里，天然被跳过

FACING_MAP = {"north": "west", "west": "north", "east": "south", "south": "east"}
DIRECTION_PROP = re.compile(r"\b(facing|head)=(\w+)")


def block_id(state: str) -> str:
	return state.split("[", 1)[0]


def in_set(state: str, exact: set, prefixes: tuple) -> bool:
	bid = block_id(state)
	return bid in exact or any(bid.startswith(p) for p in prefixes)


def is_placeable(state: str) -> bool:
	return state != "" and in_set(state, PLACE_EXACT, PLACE_PREFIX)


def is_removable(state: str) -> bool:
	return state != "" and in_set(state, REMOVE_EXACT, ())


def is_excluded(state: str) -> bool:
	bid = block_id(state)
	return bid in EXCLUDED_EXACT or any(bid.startswith(p) for p in EXCLUDED_PREFIX)


def transform_state(state: str) -> str:
	"""轴交换（镜像）后改写带方向的属性值。"""
	if "[" not in state:
		return state
	head, _, props = state.partition("[")

	def repl(match):
		key, value = match.group(1), match.group(2)
		return f"{key}={FACING_MAP.get(value, value)}"

	return head + "[" + DIRECTION_PROP.sub(repl, props)


# --------------------------------------------------------------------------- 读存档


def read_cells(x1, x2, z1, z2, y1, y2, region_dir=REGION):
	"""把盒子里每一格的原文方块状态读出来。

	⚠ 一个 section 的 `states` **解一次就够**：早先按 `read_block.block_at` 逐格读，等于每格都把
	4096 项的调色板索引数组解包一遍（6.6 万格 × 4096 ≈ 2.7 亿次循环 ⇒ 单站读一遍要 1 分多钟，
	10 座站光读就十几分钟）。这里改成"每区块每 section 解一次、再逐格查表"，与 `find_blocks.scan`
	同一套写法。
	"""
	out = {}
	for cx in range(x1 >> 4, (x2 >> 4) + 1):
		for cz in range(z1 >> 4, (z2 >> 4) + 1):
			root, error = load_chunk_nbt(region_dir, cx, cz)
			if root is None:
				print(f"  ! 区块 ({cx},{cz}) 读不到：{error}")
				continue
			chunk = root
			if "sections" not in chunk and "Sections" not in chunk and "" in chunk:
				chunk = chunk[""]
			sections = chunk.get("sections", chunk.get("Sections"))
			if sections is None:
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
				if len(palette) == 1 or data is None:
					states = [0] * 4096
				else:
					states = unpack_states(data, max(4, (len(palette) - 1).bit_length()), 4096)
				for x in range(max(x1, cx << 4), min(x2, (cx << 4) + 15) + 1):
					for z in range(max(z1, cz << 4), min(z2, (cz << 4) + 15) + 1):
						for y in range(max(y1, section_y * 16), min(y2, section_y * 16 + 15) + 1):
							index = (((y & 15) * 16) + (z & 15)) * 16 + (x & 15)
							if index < len(states):
								out[(x, y, z)] = strings[states[index]]
	return out


# --------------------------------------------------------------------------- 模板与映射


def load_template(region_dir=REGION):
	cells = read_cells(TEMPLATE["cross_lo"], TEMPLATE["cross_hi"], TEMPLATE["along_lo"], TEMPLATE["along_hi"],
	                   TEMPLATE["y_lo"], TEMPLATE["y_hi"], region_dir)
	structure = {k: v for k, v in cells.items() if is_placeable(v) or is_excluded(v)}
	return cells, structure


def map_cell(station, xt, yt, zt):
	_, x1, x2, cz, rail_y, _, _ = station
	along = x1 + (zt - TEMPLATE["along_lo"])
	cross = cz + (xt - TEMPLATE["center"])
	y = yt + (rail_y - TEMPLATE["rail_y"])
	return along, y, cross


def station_box(station):
	_, x1, x2, cz, rail_y, _, _ = station
	dy = rail_y - TEMPLATE["rail_y"]
	return (x1, x2, cz - 12, cz + 12, TEMPLATE["y_lo"] + dy, TEMPLATE["y_hi"] + dy)


def plan_station(station, template_structure, region_dir=REGION):
	x1, x2, z1, z2, y1, y2 = station_box(station)
	current = read_cells(x1, x2, z1, z2, y1, y2, region_dir)

	target = {}
	excluded = {}
	overwritten_non_owned = []
	for (xt, yt, zt), state in template_structure.items():
		key = map_cell(station, xt, yt, zt)
		if is_excluded(state):
			excluded[key] = state
			continue
		target[key] = transform_state(state)

	commands = []
	rollback = []
	removals = []
	orphan_signals = []
	skipped = []
	for key, tgt in sorted(target.items()):
		cur = current.get(key)
		if cur is None:
			skipped.append((key, "读不到"))
			continue
		if cur == tgt:
			continue
		if not is_removable(cur) and cur not in AIR:
			# 目标格上现在不是本工具负责的类型（自然方块等）⇒ 仍然要铺结构，但要记下来
			overwritten_non_owned.append((key, cur, tgt))
		commands.append((key, cur, tgt))

	for key, cur in sorted(current.items()):
		if key in target or key in excluded or cur in AIR:
			continue
		if is_removable(cur):
			removals.append((key, cur))
			commands.append((key, cur, "minecraft:air"))
		elif block_id(cur).startswith("mtr:signal") or block_id(cur) in REPORT_ONLY_EXACT:
			orphan_signals.append((key, cur))

	return {
		"station": station,
		"box": (x1, x2, z1, z2, y1, y2),
		"commands": commands,
		"removals": removals,
		"overwritten_non_owned": overwritten_non_owned,
		"orphan_signals": orphan_signals,
		"skipped": skipped,
		"current": current,
	}


# --------------------------------------------------------------------------- 主流程


def summarize(plan, limit=8):
	station = plan["station"]
	name = station[0]
	places = [(k, c, t) for k, c, t in plan["commands"] if t != "minecraft:air"]
	by_type = Counter(f"{block_id(c)}→{block_id(t)}" for _, c, t in places)
	print(f"=== {name}  盒 {plan['box']}  命令 {len(plan['commands'])} 条（其中删除 {len(plan['removals'])}）")
	print("    改动类型：" + (", ".join(f"{k}×{v}" for k, v in by_type.most_common(14)) or "无"))
	if plan["removals"]:
		by_del = Counter(block_id(c) for _, c in plan["removals"])
		print(f"    删除（模板里没有的旧装饰）：" + ", ".join(f"{k}×{v}" for k, v in by_del.most_common()))
		for key, cur in plan["removals"][:limit]:
			print(f"      - {key} = {cur}")
		if len(plan["removals"]) > limit:
			print(f"      …（其余 {len(plan['removals']) - limit} 条）")
	if plan["overwritten_non_owned"]:
		print(f"    ⚠ 会覆盖 {len(plan['overwritten_non_owned'])} 格非本工具类型（自然方块等）：")
		for key, cur, tgt in plan["overwritten_non_owned"][:limit]:
			print(f"      * {key} {cur} → {tgt}")
	if plan["orphan_signals"]:
		print(f"    ⚠ {len(plan['orphan_signals'])} 格「额外」方块（模板里没有、**不删**只报告：归属锚/信号杆等）：")
		for key, cur in plan["orphan_signals"][:limit]:
			print(f"      ? {key} = {cur}")
	if plan["skipped"]:
		print(f"    ! {len(plan['skipped'])} 格读不到，跳过（前几格：{plan['skipped'][:3]}）")
	return len(plan["commands"])


def apply_commands(commands, password=None, box=None, forceload=True):
	changed = noop = failed = 0
	failures = []
	with Rcon(password) as rcon:
		if forceload and box is not None:
			x1, x2, z1, z2 = box[0], box[1], box[2], box[3]
			print(f"    forceload add {x1} {z1} {x2} {z2}")
			print("      " + rcon.command(f"forceload add {x1} {z1} {x2} {z2}").strip()[:120])
			time.sleep(2)
		for index, (key, _cur, tgt) in enumerate(commands, start=1):
			x, y, z = key
			reply = rcon.command(f"setblock {x} {y} {z} {tgt}").strip()
			if "Changed the block" in reply or "Successfully" in reply:
				changed += 1
			elif "Could not set the block" in reply:
				noop += 1  # 新旧状态相同（幂等重发）
			else:
				failed += 1
				if len(failures) < 10:
					failures.append((key, tgt, reply[:120]))
			if index % 500 == 0:
				print(f"    …{index}/{len(commands)}")
		rcon.command("save-all flush")
		time.sleep(2)
		if forceload and box is not None:
			x1, x2, z1, z2 = box[0], box[1], box[2], box[3]
			print("      " + rcon.command(f"forceload remove {x1} {z1} {x2} {z2}").strip()[:120])
	return changed, noop, failed, failures


def verify_station(plan, region_dir=REGION):
	"""铺完读回复核：逐格比对期望状态。"""
	x1, x2, z1, z2, y1, y2 = plan["box"]
	current = read_cells(x1, x2, z1, z2, y1, y2, region_dir)
	bad = []
	for key, _cur, tgt in plan["commands"]:
		got = current.get(key)
		if got != tgt:
			bad.append((key, tgt, got))
	return bad


def main() -> int:
	ap = argparse.ArgumentParser(description="把 TEST 站台标准整体铺到其它标准站（轨道除外）")
	ap.add_argument("--stations", default="", help="逗号分隔站名筛选；留空=全部")
	ap.add_argument("--apply", action="store_true", help="真发指令（默认只干跑）")
	ap.add_argument("--no-forceload", action="store_true", help="不要 forceload 目标站（站的区块本来就加载着时用）")
	ap.add_argument("--check", action="store_true", help="只当验收器：有任何差量就以非 0 退出（不写世界）")
	ap.add_argument("--emit", default="", help="把某站的命令写成文件（配合 --stations 单站）")
	ap.add_argument("--no-flush", action="store_true")
	ap.add_argument("--json", default="", help="把每站计划摘要写成 JSON")
	args = ap.parse_args()

	want = [s.strip() for s in args.stations.split(",") if s.strip()]
	chosen = [s for s in STATIONS if not want or s[0] in want]
	if want and len(chosen) != len(want):
		raise SystemExit(f"有站名没匹配上：{[w for w in want if w not in [s[0] for s in chosen]]}")

	if not args.no_flush:
		with Rcon() as rcon:
			rcon.command("save-all flush")
		time.sleep(3)

	print("读模板（TEST 站）…")
	_, template_structure = load_template()
	placed = Counter(block_id(v) for v in template_structure.values())
	print(f"  模板结构格 {len(template_structure)} 格：" + ", ".join(f"{k}×{v}" for k, v in placed.most_common(12)))

	plans = []
	total = 0
	for station in chosen:
		plan = plan_station(station, template_structure)
		total += summarize(plan)
		plans.append(plan)

	print(f"\n合计 {total} 条指令（{len(chosen)} 站）")

	if args.emit:
		plan = plans[0]
		name = plan["station"][0]
		with open(args.emit, "w", encoding="utf-8") as f:
			for (x, y, z), _cur, tgt in plan["commands"]:
				f.write(f"setblock {x} {y} {z} {tgt}\n")
		rollback = os.path.splitext(args.emit)[0] + ".rollback.txt"
		with open(rollback, "w", encoding="utf-8") as f:
			for (x, y, z), cur, _tgt in reversed(plan["commands"]):
				f.write(f"setblock {x} {y} {z} {cur}\n")
		print(f"命令 -> {args.emit}；回滚 -> {rollback}")

	if args.json:
		payload = [{"station": p["station"][0], "box": p["box"], "commands": len(p["commands"]),
		            "removals": len(p["removals"]), "overwrite_non_owned": len(p["overwritten_non_owned"]),
		            "orphan_signals": len(p["orphan_signals"])} for p in plans]
		with open(args.json, "w", encoding="utf-8") as f:
			json.dump(payload, f, ensure_ascii=False, indent=1)
		print(f"摘要 -> {args.json}")

	if args.check:
		print(f"\n[{'OK' if total == 0 else 'NG'}] 与 TEST 模板的差量合计 {total} 条"
		      + ("（逐格一致）" if total == 0 else "（还没铺干净）"))
		return 0 if total == 0 else 1

	if not args.apply:
		print("\n（干跑：加 --apply 才真的铺）")
		return 0

	for plan in plans:
		name = plan["station"][0]
		print(f"\n--- 铺设 {name}：{len(plan['commands'])} 条")
		changed, noop, failed, failures = apply_commands(plan["commands"], box=plan["box"],
		                                                 forceload=not args.no_forceload)
		time.sleep(2)
		bad = verify_station(plan)
		print(f"    {name}：成功 {changed}｜无变化 {noop}｜失败 {failed}｜复核不符 {len(bad)}")
		for key, tgt, got in bad[:8]:
			print(f"      ! {key} 期望 {tgt}，实为 {got}")
		for key, tgt, reply in failures[:8]:
			print(f"      [失败] {key} {tgt} -> {reply}")
	return 0


if __name__ == "__main__":
	sys.exit(main())
