#!/usr/bin/env python3
"""在站台贴轨那一排（站台方块上面那一层）铺 APG（半高闸机）墙。

门位与「门洞排队样板」同一口径（见 `mmtr/tools/crowd-marker/gen_door_queue_layout.py`）：
SAF420 = 十节车 30 个门洞、**门距精确 6.000 m** ⇒ 一个门洞占 6 格：门扇 2 格 + 玻璃 4 格。
于是「第一条队 z0、门洞 z0+1..z0+2、队尾 z0+3」——和站台上那排客流标记的锚点完全同相位。

用法（**默认只干跑**，确认无误再加 `--apply`）：

    python mmtr/tools/psd-wall/gen_apg_wall.py \
        --face -45,east --face -35,west \
        --y 66 --z1 802 --z2 1015 \
        --door-start 820 --door-pitch 6 --door-count 30 \
        --glass-end 802,1014,1015

    # 干跑 + 逐格读存档核对"墙下面那一格是不是 mtr:platform"（发之前先 save-all flush）
    python mmtr/tools/psd-wall/gen_apg_wall.py ... --check-floor

    # 真铺（服务端在跑）
    python mmtr/tools/psd-wall/gen_apg_wall.py ... --apply

> ⚠ `--z1` 要避开**站台两端的信号灯柱**：TEST 站北端 z=800/801 两列各有「柱 y=66 + 灯头 y=67」
> （南端在 z=1019/1020）。墙从 z=802 起刚好贴在柱子南侧；铺到柱子那一格会把信号灯盖掉。

## `--z1/--z2/--door-start` 的轴口径（2026-10-13 补）

`--z1/--z2/--glass-end/--door-start` 都是**沿站台方向**的坐标，名字里的 z 是历史叫法：

| 站台朝向 `--face` | 墙沿哪个轴 | 这些参数给哪个坐标 |
| --- | --- | --- |
| `east` / `west`（TEST：轨沿 z） | z | z |
| `north` / `south`（下水等 10 座站：轨沿 x） | x | **x** |

例（下水，站台轨 x=-500…-280、轨面 y=65、贴轨两行 z=1798 `facing=south` / z=1808 `facing=north`）：

    python mmtr/tools/psd-wall/gen_apg_wall.py \
        --face 1798,south --face 1808,north \
        --y 66 --z1 -496 --z2 -285 \
        --door-start -480 --door-pitch 6 --door-count 30 \
        --glass-end=-496,-495,-286,-285 --check-floor --apply

> ⚠ **`--glass-end` 的值以 `-` 开头时必须写 `--glass-end=…`**（argparse 会把独立成词的
> `-496,-495,…` 当成开关，报 `expected one argument`）。批量铺站时踩过一次，墙整条没铺。

生成的 `side=left/right` 由**朝向**自己推（见下），所以**不要**照抄别的站的墙方块状态：
轴交换是镜像，left/right 会互换 —— 让本工具按 `facing` 重算才对。

## 站位口径（照抄 MTR，不许自己发明）

`ItemPSDAPGBase.useOnBlock2` 摆门时：`x = 0` 那一格 `side=left`，`x = 1` 那一格 =
`pos + playerFacing.rotateYClockwise()`、`side=right`；`IBlock.getSideDirection` 反过来读：
`LEFT` 的"另一半"在 `rotateYClockwise(facing)`，`RIGHT` 在 `rotateYCounterclockwise(facing)`。
⇒ 一对门占相邻两格 `(hole, hole+1)`（沿墙轴向）：

| 朝向 | rotateYClockwise | left 在哪一格 |
| --- | --- | --- |
| east（面朝 +x） | south | hole（北） |
| west（面朝 -x） | north | hole+1（南） |
| north（面朝 -z） | east | hole（西） |
| south（面朝 +z） | west | hole+1（东） |

`facing` = 墙**面朝轨道**的方向（西侧站台面朝 east，东侧站台面朝 west）。

## 为什么玻璃要用 `side=single`

`side`（`SIDE_EXTENDED`）只由**刷子**（`BlockPSDAPGGlassBase.connectGlass`）改，摆下去就是
`single`；渲染不看它，所以整条墙全 `single` 与 MTR 原版摆法一致（用户现有的那一段就是全 `single`）。

`glass_end`（端头）只在墙的两头各一块：它按左右邻居是不是门/玻璃算出 `touching_left/right`，
`AIR` 那一侧才会长出"收头"那块；这些状态由方块自己的邻居更新算，本工具只给个初值。
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys

try:
	sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
	pass

# --------------------------------------------------------------------------- 常量

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "mmtr-dev-rcon"

ROTATE_CW = {"north": "east", "east": "south", "south": "west", "west": "north"}
# 墙沿哪个水平轴铺：东西面沿 z 排、南北面沿 x 排
WALL_AXIS = {"east": "z", "west": "z", "north": "x", "south": "x"}
# 该轴的 +1 方向是哪个朝向
AXIS_POSITIVE_FACING = {"z": "south", "x": "east"}

STYLES = {
	"apg": {"door": "mtr:apg_door", "glass": "mtr:apg_glass", "end": "mtr:apg_glass_end", "top": None},
	# PSD（全高）还差 `mtr:psd_top` 顶盖：它的状态（air_left/air_right/side/persistent）由方块
	# 自己按邻居推导，本工具还没实装 —— 要用就先补 `STYLES["psd"]["top"]` 的写法。
}

# --------------------------------------------------------------------------- RCON（与 gen_door_queue_layout.py 同一套最小实现）


def pack(request_id: int, kind: int, body: str) -> bytes:
	payload = struct.pack("<ii", request_id, kind) + body.encode("utf-8") + b"\x00\x00"
	return struct.pack("<i", len(payload)) + payload


def read_packet(sock: socket.socket):
	raw = sock.recv(4)
	if len(raw) < 4:
		return None, None, ""
	(length,) = struct.unpack("<i", raw)
	data = b""
	while len(data) < length:
		chunk = sock.recv(length - len(data))
		if not chunk:
			break
		data += chunk
	if len(data) < 10:
		return None, None, ""
	request_id, kind = struct.unpack("<ii", data[:8])
	return request_id, kind, data[8:-2].decode("utf-8", "replace")


class Rcon:
	def __init__(self, password: str = PASSWORD):
		self.sock = socket.create_connection((HOST, PORT), timeout=60)
		self.sock.sendall(pack(1, 3, password))
		read_packet(self.sock)
		self.next_id = 10

	def cmd(self, command: str) -> str:
		self.next_id += 1
		request_id = self.next_id
		self.sock.sendall(pack(request_id, 2, command))
		out = []
		while True:
			got_id, _, body = read_packet(self.sock)
			if got_id is None:
				break
			if body:
				out.append(body)
			if got_id == request_id:
				break
		return "\n".join(out)


# --------------------------------------------------------------------------- 布局 → 方块

def parse_face(text: str):
	"""`<坐标>,<facing>`：东西面给 x，南北面给 z。"""
	parts = text.split(",")
	if len(parts) != 2:
		raise SystemExit(f"--face 要写成 <坐标>,<facing>，收到「{text}」")
	coord = int(parts[0])
	facing = parts[1].strip().lower()
	if facing not in ROTATE_CW:
		raise SystemExit(f"facing 只能是 north/east/south/west，收到「{facing}」")
	return coord, facing


def blocks_for(style: str, facing: str, kind: str, half: str) -> str:
	"""kind = door_left / door_right / glass / end。"""
	ids = STYLES[style]
	if kind == "glass":
		return f"{ids['glass']}[facing={facing},half={half},propagate_property=0,side=single]"
	if kind == "end":
		return f"{ids['end']}[facing={facing},half={half},side=single,touching_left=none,touching_right=none]"
	side = "left" if kind == "door_left" else "right"
	return f"{ids['door']}[end=false,facing={facing},half={half},side={side},unlocked=true]"


def plan_face(coord: int, facing: str, y: int, z1: int, z2: int, door_start: int, pitch: int, doors: int, glass_ends: set, style: str):
	"""返回 (columns, doors_at)，columns = [(x, y, z, state), ...]。"""
	axis = WALL_AXIS[facing]
	left_at_hole = ROTATE_CW[facing] == AXIS_POSITIVE_FACING[axis]
	doors_at = {}
	for index in range(doors):
		bay = door_start + index * pitch
		hole = bay + 1
		left = hole if left_at_hole else hole + 1
		right = hole + 1 if left_at_hole else hole
		doors_at[left] = "door_left"
		doors_at[right] = "door_right"

	columns = []
	for along in range(z1, z2 + 1):
		x, z = (coord, along) if axis == "z" else (along, coord)
		if along in doors_at:
			kinds = [doors_at[along], doors_at[along]]
		elif along in glass_ends:
			kinds = ["end", "end"]
		else:
			kinds = ["glass", "glass"]
		for offset, half in ((0, "lower"), (1, "upper")):
			columns.append((x, y + offset, z, blocks_for(style, facing, kinds[offset], half)))
	return columns, doors_at


# --------------------------------------------------------------------------- 存档直读核对


def check_floor(columns, y: int) -> int:
	"""逐格读存档：墙下面那一格必须是 `mtr:platform`（读之前先 `save-all flush`）。"""
	import os

	# 本文件在 <工作区>/mmtr/tools/psd-wall/ 下 ⇒ 往上 3 层才是工作区根
	workspace = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
	sys.path.insert(0, os.path.join(workspace, "sandbox"))
	from read_block import block_at, load_chunk_nbt  # noqa: E402

	region_dir = os.path.join(workspace, "mmtr", "game", "fabric", "run", "world", "region")
	roots = {}
	bad = 0
	seen = set()
	for x, _y, z, _state in columns:
		if (x, z) in seen:
			continue
		seen.add((x, z))
		chunk_key = (x >> 4, z >> 4)
		if chunk_key not in roots:
			root, error = load_chunk_nbt(region_dir, *chunk_key)
			roots[chunk_key] = root
			if root is None:
				print(f"  ! 区块 {chunk_key} 读不到：{error}")
		root = roots[chunk_key]
		if root is None:
			bad += 1
			continue
		below = block_at(root, x, y - 1, z)
		if not below.startswith("mtr:platform"):
			print(f"  ! ({x}, {y - 1}, {z}) = {below}（不是 mtr:platform）")
			bad += 1
	print(f"脚下核对：{len(seen)} 格，{bad} 格不合格")
	return bad


# --------------------------------------------------------------------------- 主流程


def main() -> int:
	parser = argparse.ArgumentParser(description="按 6 格门距在站台贴轨那一排铺 APG 墙")
	parser.add_argument("--face", action="append", required=True, help="可重复：<坐标>,<facing>，如 -45,east")
	parser.add_argument("--y", type=int, default=66, help="墙的下半那一层（人站的那一格）")
	parser.add_argument("--z1", type=int, required=True, help="墙沿站台方向的起格（含）")
	parser.add_argument("--z2", type=int, required=True, help="墙沿站台方向的止格（含）")
	parser.add_argument("--door-start", type=int, required=True, help="第 1 个门洞的起始格 z0（锚点那一格）")
	parser.add_argument("--door-pitch", type=int, default=6)
	parser.add_argument("--door-count", type=int, default=30)
	parser.add_argument("--glass-end", default="", help="端头格（逗号分隔）：这些格用 glass_end，且不放门")
	parser.add_argument("--style", default="apg", choices=sorted(STYLES))
	parser.add_argument("--apply", action="store_true", help="真的发指令（默认只干跑打印）")
	parser.add_argument("--check-floor", action="store_true", help="逐格读存档核对脚下是 mtr:platform")
	parser.add_argument("--rcon-password", default=PASSWORD)
	args = parser.parse_args()

	if STYLES[args.style]["top"] is None and args.style != "apg":
		raise SystemExit(f"--style {args.style} 还没实装（顶盖状态要由方块自己推导）")

	glass_ends = {int(v) for v in args.glass_end.split(",") if v.strip()}
	columns = []
	doors_at = {}
	for text in args.face:
		coord, facing = parse_face(text)
		face_columns, face_doors = plan_face(coord, facing, args.y, args.z1, args.z2, args.door_start, args.door_pitch, args.door_count, glass_ends, args.style)
		columns.extend(face_columns)
		doors_at[(coord, facing)] = face_doors

	door_bays = [args.door_start + i * args.door_pitch for i in range(args.door_count)]
	print(f"墙：{len(args.face)} 面 × {args.z2 - args.z1 + 1} 格 × 2 层 = {len(columns)} 格")
	print(f"门：{args.door_count} 个门洞（z0 = {door_bays[0]} … {door_bays[-1]}，间距 {args.door_pitch}），"
		f"门扇 z = z0+1, z0+2（与站台标记的锚点同相位）")
	for (coord, facing), face_doors in doors_at.items():
		lefts = sorted(z for z, kind in face_doors.items() if kind == "door_left")
		rights = sorted(z for z, kind in face_doors.items() if kind == "door_right")
		print(f"  面 {coord},{facing}：left z={lefts[0]}..{lefts[-1]}（{len(lefts)} 格），right z={rights[0]}..{rights[-1]}（{len(rights)} 格）")
	if glass_ends:
		print(f"端头 glass_end：z={sorted(glass_ends)}")

	commands = [f"setblock {x} {y} {z} {state}" for x, y, z, state in columns]
	print(f"指令 {len(commands)} 条")

	if args.check_floor:
		check_floor(columns, args.y)

	if not args.apply:
		for command in commands[:12]:
			print("  " + command)
		if len(commands) > 12:
			print(f"  …（其余 {len(commands) - 12} 条）")
		return 0

	rcon = Rcon(args.rcon_password)
	failures = 0
	for index, command in enumerate(commands, start=1):
		reply = rcon.cmd(command).strip()
		if "Changed the block" not in reply and "Successfully" not in reply:
			print(f"  [失败] {command} -> {reply[:120]}")
			failures += 1
		if index % 200 == 0:
			print(f"  … {index}/{len(commands)}")
	print(f"完成：{len(commands) - failures} 条成功，{failures} 条失败")
	return 1 if failures else 0


if __name__ == "__main__":
	sys.exit(main())
