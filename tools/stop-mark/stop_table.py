#!/usr/bin/env python3
"""算「对标表」：不同编组（2…10 辆）要让车门对上站台屏蔽门，车头 / 驾驶室参照点必须停在哪。

## 依据（全部实测，出处见 mmtr/notes/420）

* 车底（`world/mtr/minecraft/overworld/mmtr-rolling-stock.json`）：
  头车 `saf420cab_a/b` **长 19.5 m**、拖车 `saf420car` **长 16.95 m**、两端 `couplingPadding = 0.525`。
  ⇒ 相邻两车中心距 = (L1+L2)/2 + 1.05：拖↔拖 **18.000**、头↔拖 **19.275**。
* 车门（`assets/models/blender/saf420{car,cab}/*.obj`，`o` 分组的包围盒）：
  每个**门洞** = 两片 1 m 门叶（`door_l_N`/`door_r_N` 是同 z 的左右**两侧**，不是两个门），
  拖车门洞中心 z = −6.000 / 0.000 / +6.000，头车门洞中心 = −4.725 / +1.275 / +7.275。
  ⇒ 上面那两组数字**恰好**让整列车的门洞落在一个 **6.000 m 等距栅格**上：`u = 5.025 + 6k`（u = 距车头前脸）。
  这条对 **任何 N（2…10 辆、两端都有驾驶室）都成立** ⇒ 门洞栅格相位与 N 无关，只有"用哪一段站台"随 N 变。
* 驾驶室参照点（头车局部 z，前脸 = −9.750）：
  挡风 `mmtr_windshield_1_1` −9.725 ｜ 司机座 `mmtr_seat_1` −8.200 ｜ 驾驶室侧窗 ≈ −8.500 ｜ 驾驶室侧门 `mmtr_cabdoor_*` −7.500
  ⇒ 距前脸：挡风 0.025 m ｜ **侧窗 ≈1.25 m** ｜ 座位 1.55 m ｜ 侧门 2.25 m。
* 站台屏蔽门（notes/417 铺的 APG 墙）：门洞占 `z0+1, z0+2` 两格 ⇒ 门洞中心 = `z0 + 2`，
  `z0 = 821 + 6k`（k=0…29）⇒ 中心 **822, 828, …, 996**（等距 6 m，共 30 个）。

## 算法

要让整列车的门洞落在站台那 30 个门洞上（取其中连续的 3N 个），车头前脸必须满足
`车头 + 5.025 ≡ 822 (mod 6)`，即 `车头 = 816.975 + 6j`（N 行方向按下面的符号）。
每个 N 里 `j` 由"列车尽量对准站台中心"选出，同时要求整列车不出站台。

    python mmtr/tools/stop-mark/stop_table.py --z1 800 --z2 1020 --apg-phase 822
"""

from __future__ import annotations

import argparse

# --- 车底与司机参照点（实测；见文件头） ---
CAB_LENGTH = 19.5
CAR_LENGTH = 16.95
COUPLING = 0.525
FRONT_TO_FIRST_DOOR = 5.025  # 5.025 = 头车前脸(−9.750) 到第一个门洞中心(−4.725)
DOOR_PITCH = 6.0
DOORS_PER_CAR = 3

# 距车头前脸（驾驶室参照点）
REFERENCES = {
	"挡风玻璃": 0.025,
	"驾驶室侧窗": 1.25,
	"司机座位": 1.55,
	"驾驶室侧门": 2.25,
}


def consist_length(cars: int) -> float:
	"""N 辆车（两头都是头车）front-face 到 rear-face 的总长。"""
	if cars < 2:
		raise ValueError("编组至少 2 辆（两端都要有驾驶室）")
	return 2 * CAB_LENGTH + (cars - 2) * CAR_LENGTH + (cars - 1) * 2 * COUPLING


def row(cars: int, platform_z1: int, platform_z2: int, apg_phase: float, reference: str):
	length = consist_length(cars)
	half = length / 2
	platform_center = (platform_z1 + platform_z2) / 2
	# 车头 = apg_phase - FRONT_TO_FIRST_DOOR + 6j（往 -z 方向开时）⇒ 列车中心 = 车头 + half
	j = round((platform_center - (apg_phase - FRONT_TO_FIRST_DOOR) - half) / DOOR_PITCH)
	head_north = apg_phase - FRONT_TO_FIRST_DOOR + DOOR_PITCH * j   # 北行（车头在小 z 侧）
	head_south = head_north + length                                 # 南行（车头在大 z 侧）
	delta = REFERENCES[reference]
	doors = DOORS_PER_CAR * cars
	return {
		"cars": cars,
		"length": length,
		"doors": doors,
		"head_north": head_north,
		"head_south": head_south,
		"body": (head_north, head_north + length),
		"marker_north": head_north + delta,
		"marker_south": head_south - delta,
		"center": head_north + half,
		"fits": platform_z1 <= head_north and head_north + length <= platform_z2,
	}


def main() -> int:
	ap = argparse.ArgumentParser(description="算各编组的停车对标点")
	ap.add_argument("--z1", type=int, required=True, help="站台轨起格（北端）")
	ap.add_argument("--z2", type=int, required=True, help="站台轨止格（南端）")
	ap.add_argument("--apg-phase", type=float, default=822, help="第一个屏蔽门洞中心 z")
	ap.add_argument("--cars-min", type=int, default=2)
	ap.add_argument("--cars-max", type=int, default=10)
	ap.add_argument("--reference", default="驾驶室侧窗", choices=sorted(REFERENCES))
	args = ap.parse_args()

	print(f"站台轨 z={args.z1}…{args.z2}（中心 {(args.z1+args.z2)/2:.1f}）｜屏蔽门洞中心 = {args.apg_phase} + 6k")
	print(f"对标参照点：{args.reference}（车头前脸后 {REFERENCES[args.reference]} m）")
	print()
	print(f"{'辆':>3} {'编组长':>7} {'门数':>4} {'北行车头':>10} {'南行车头':>10} {'北行标记':>10} {'南行标记':>10} {'列车中心':>9} 出站台")
	for cars in range(args.cars_min, args.cars_max + 1):
		r = row(cars, args.z1, args.z2, args.apg_phase, args.reference)
		print(
			f"{r['cars']:>3} {r['length']:>7.2f} {r['doors']:>4} "
			f"{r['head_north']:>10.3f} {r['head_south']:>10.3f} "
			f"{r['marker_north']:>10.3f} {r['marker_south']:>10.3f} {r['center']:>9.2f} "
			f"{'' if r['fits'] else '★超界'}"
		)
	print()
	print("注：北行 = 车头在小 z 一侧（门洞在车头 +z 方向）；南行 = 车头在大 z 一侧。")
	print("    标记 z = 驾驶室参照点的世界 z（司机把这个参照点对到标记上即可）。")
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
