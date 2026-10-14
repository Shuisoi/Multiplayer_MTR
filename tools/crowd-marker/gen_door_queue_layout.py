#!/usr/bin/env python3
"""按「门洞排队样板」在站台面上批量铺客流标记（一级 / 二级 / 流动 / 归属锚）。

用法（工作区自带 Python；带 `--apply` 时用 RCON 直接铺到 dev 服务端）：

    # 干跑：只打印将要发的指令（默认）
    python mmtr/tools/crowd-marker/gen_door_queue_layout.py --platform-block-x -45 --side -1 --start-z 805 --doors 30

    # 真铺（需要服务端在跑）
    python mmtr/tools/crowd-marker/gen_door_queue_layout.py ... --apply

    # 清掉（把铺过的格清成空气）
    python mmtr/tools/crowd-marker/gen_door_queue_layout.py ... --apply --clear

## 样板（用户 2026-10-10 手摆的，本脚本就是"照葫芦画瓢"那把瓢）

以「站台方块那一排」（贴轨的安全线块，记为第 0 排）为基准，往站台里数：

| 排 | 内容 |
|---|---|
| 第 1..3 排（`px-1 .. px-3`） | **一级**：每队 3 格，朝站台方向排的竖队 |
| 第 4 排（`px-4`） | **二级**：每队第 4 格（队尾） |
| 第 5 排（`px-5`） | **流动**：贯通整条站台的走道（每一格都铺） |
| 第 0 排（`px`，站台方块上方一格） | **归属锚**：每个门洞起始格放一个 |

每一排的**沿站台方向**（z）布局，一个门洞占 **6 格**：

```
z 偏移:   0   1   2   3   4   5
一级:     ■   ·   ·   ■   ·   ·     ← 两条队（两片门叶各一条），中间空 2 格
二级:     ■   ·   ·   ■   ■   ■     ← 队尾 + 门洞之间那 2 格（候车块）
流动:     ■   ■   ■   ■   ■   ■     ← 贯通（与下一个门洞相接）
锚:       ■   ·   ·   ·   ·   ·
```

规则出处（用户口径，2026-10-10 修正）："**内间距是 4 格，也就是 2 个红色标记之间是 2 格**"
—— 相邻门洞的**内侧队**相距 4 格（含两端）⇒ 中间空 2 格；同一门洞两条队相距 3 格（中间空 2 格）。
于是"两支队 + 2 格候车块"= **6 格一个门洞**。

> ✔ 这条口径与**车门的物理几何一致**：模型量出来 SAF420 门距是**精确 6.000 m**
> （头车 3 门：z=−4.725/+1.275/+7.275；拖车 3 门：z=−6.0/0/+6.0），十节 2 头车 + 8 拖车 = **30 个门洞**。
> 所以按 6 格铺出来的队，和列车停稳后的车门是一一对齐的（差一个停车基准）。
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys

# Windows 控制台默认 GBK，打印中文/箭头会炸：显式切到 UTF-8（失败也不影响功能）
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# --------------------------------------------------------------------------- RCON（与 sandbox/tmp/_rcon.py 同一套最小实现）

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "mmtr-dev-rcon"


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


# --------------------------------------------------------------------------- 样板 → 格

def cells(platform_block_x: int, side: int, y: int, start_z: int, door_index: int, pitch: int, doors: int):
    """返回一个门洞的四类格（(x, z, 方块id) 列表）。side=-1 表示"往 -x 方向是站台面"。"""
    z0 = start_z + door_index * pitch
    out = []
    for queue_z in (z0, z0 + 3):
        for row in (1, 2, 3):
            out.append((platform_block_x + side * row, queue_z, "mtr:crowd_mark_1"))
        out.append((platform_block_x + side * 4, queue_z, "mtr:crowd_mark_2"))
    # 门洞之间那几格（第 4 格起、到下一个门洞之前）= 二级候车块
    for gap_z in range(z0 + 4, z0 + pitch):
        for row in (1, 2, 3, 4):
            out.append((platform_block_x + side * row, gap_z, "mtr:crowd_mark_2"))
    out.append((platform_block_x, z0, "mtr:crowd_anchor"))
    return out


def commands(platform_block_x: int, side: int, y: int, start_z: int, doors: int, pitch: int):
    """把整个布局折成尽量少的 /fill 指令（走道一条长 fill，其余逐格）。"""
    cmds = []
    everything = []
    for index in range(doors):
        everything.extend(cells(platform_block_x, side, y, start_z, index, pitch, doors))

    # 1) 流动走道：一整条（从第一个门洞的 z0 到最后一个门洞覆盖的最后一格），在"往站台里数第 5 排"
    flow_x = platform_block_x + side * 5
    first_z = start_z
    last_z = start_z + (doors - 1) * pitch + pitch - 1
    lo, hi = sorted((first_z, last_z))
    cmds.append(f"fill {flow_x} {y} {lo} {flow_x} {y} {hi} mtr:crowd_flow[facing={'north' if side < 0 else 'south'}]")

    # 2) 其余逐格（一级/二级/锚）——按方块 id 分组，方便核对与清理
    groups = {}
    for x, z, block_id in everything:
        groups.setdefault(block_id, []).append((x, z))
    for block_id, coords in groups.items():
        for x, z in coords:
            cmds.append(f"setblock {x} {y} {z} {block_id}")
    return cmds, groups, (flow_x, lo, hi)


def clear_commands(platform_block_x: int, side: int, y: int, start_z: int, doors: int, pitch: int):
    """清掉：把样板覆盖到的每一格（含走道）填成空气。"""
    cmds = []
    ids = ["mtr:crowd_mark_1", "mtr:crowd_mark_2", "mtr:crowd_flow", "mtr:crowd_anchor"]
    min_z = start_z
    max_z = start_z + (doors - 1) * pitch + pitch
    for row in range(0, 6):
        x = platform_block_x + side * row
        for block_id in ids:
            cmds.append(f"fill {x} {y} {min_z} {x} {y} {max_z} air replace {block_id}")
    return cmds


def main() -> int:
    parser = argparse.ArgumentParser(description="按门洞排队样板铺客流标记")
    parser.add_argument("--platform-block-x", type=int, required=True, help="站台方块那一排的 x（人不能站的那排）")
    parser.add_argument("--side", type=int, default=-1, choices=(-1, 1), help="往站台里的方向：-1 = 往 -x，1 = 往 +x")
    parser.add_argument("--y", type=int, default=66, help="标记所在那一层（人站的那一格）")
    parser.add_argument("--start-z", type=int, required=True, help="第一个门洞的起始格 z")
    parser.add_argument("--doors", type=int, default=30, help="门洞数（SAF420 十节 = 30）")
    parser.add_argument("--pitch", type=int, default=6, help="门洞间距（格）：用户口径 = 6（两条队相距 3 + 门洞之间空 2）")
    parser.add_argument("--apply", action="store_true", help="真的发指令（默认只干跑打印）")
    parser.add_argument("--clear", action="store_true", help="改成清理（把样板覆盖的格清成空气）")
    parser.add_argument("--rcon-password", default=PASSWORD)
    args = parser.parse_args()

    if args.clear:
        cmds = clear_commands(args.platform_block_x, args.side, args.y, args.start_z, args.doors, args.pitch)
        summary = f"清理：{len(cmds)} 条 fill（x 第 0..5 排，z {args.start_z}..{args.start_z + args.doors * args.pitch - 1}）"
    else:
        cmds, groups, (flow_x, lo, hi) = commands(args.platform_block_x, args.side, args.y, args.start_z, args.doors, args.pitch)
        summary = (f"铺：{args.doors} 个门洞 × 每洞 {args.pitch} 格 ⇒ z {args.start_z}..{args.start_z + args.doors * args.pitch - 1}"
                   f"；流动走道 x={flow_x} z={lo}..{hi}；"
                   + " ｜ ".join(f"{k.split(':')[1]} {len(v)} 格" for k, v in sorted(groups.items())))
    print(summary)
    print(f"指令 {len(cmds)} 条")

    if not args.apply:
        for command in cmds[:20]:
            print("  " + command)
        if len(cmds) > 20:
            print(f"  …（其余 {len(cmds) - 20} 条）")
        return 0

    rcon = Rcon(args.rcon_password)
    failures = 0
    for index, command in enumerate(cmds, start=1):
        reply = rcon.cmd(command)
        if "Successfully" not in reply and "Changed the block" not in reply and "No blocks" not in reply:
            print(f"  [失败] {command} -> {reply.strip()[:120]}")
            failures += 1
        if index % 50 == 0:
            print(f"  … {index}/{len(cmds)}")
    print(f"完成：{len(cmds) - failures} 条成功，{failures} 条失败")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
