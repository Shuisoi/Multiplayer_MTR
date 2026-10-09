#!/usr/bin/env python3
"""生成「客流村民」方块的美术资源（模型 / blockstate / 物品模型 / 贴图）。

用法（工作区自带 Python 即可，需要 Pillow）：

    python mmtr/tools/crowd-villager/gen_crowd_villager.py             # 生成
    python mmtr/tools/crowd-villager/gen_crowd_villager.py --check     # 只校验磁盘上的产物与脚本一致

产物（都写进 mmtr/game 的 fabric 资源目录，路径从脚本自身位置推算，**不含任何绝对路径**）：

    assets/mtr/models/block/crowd_villager_<variant>_<foot>.json   (3 配色 x 3 落脚高度)
    assets/mtr/blockstates/crowd_villager.json                     (36 个状态：3x3x4 朝向)
    assets/mtr/models/item/crowd_villager.json
    assets/mtr/textures/block/crowd_villager.png                   (64x64，全不透明)

为什么要脚本：这三个属性（配色 / 落脚高度 / 朝向）的笛卡尔积是 36 个状态、9 个模型，
手写必然抄错；而贴图里的脸（眉毛/眼睛）只能靠像素画，也要可复现。改比例或配色只改本文件。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# --------------------------------------------------------------------------- 路径

SCRIPT = Path(__file__).resolve()
REPO_ROOT = SCRIPT.parents[2]  # mmtr/
ASSETS = REPO_ROOT / "game" / "fabric" / "src" / "main" / "resources" / "assets" / "mtr"

# --------------------------------------------------------------------------- 常量

VARIANT_COUNT = 3
FOOT_COUNT = 3
FACINGS = ("north", "east", "south", "west")

#: 落脚高度（1/16 格）：与 BlockCrowdVillager.FOOT_* 一一对应。
FOOT_OFFSET = {0: 0, 1: -3, 2: -8}

#: 一层贴图里的区域（u1, v1, u2, v2）——脸/头发/肤色/三种衣服颜色。
UV = {
    "skin": (0, 0, 16, 16),
    "face": (0, 16, 16, 32),
    "hair": (16, 16, 32, 32),
    "robe": [(16, 0, 32, 16), (32, 0, 48, 16), (48, 0, 64, 16)],
    "robe_dark": [(16, 16, 32, 32), (32, 16, 48, 32), (48, 16, 64, 32)],
}

COLOURS = {
    "skin": (200, 158, 118),
    "skin_shadow": (176, 136, 100),
    "hair": (74, 51, 34),
    "brow": (56, 38, 25),
    "eye_white": (242, 242, 242),
    "pupil": (51, 36, 26),
    "mouth": (108, 74, 55),
    "robe": [(110, 75, 46), (126, 90, 56), (92, 61, 36)],
    "robe_dark": [(88, 59, 36), (102, 72, 44), (72, 47, 27)],
}

#: 村民的四个立方体（单位 = 1/16 格，y 从 0 = 脚底算起）。
#: 总高 26/16 = 1.625 格：比玩家矮一点，一排人看上去才像"人群"而不是"雕像"。
PARTS = {
    "robe": {"from": (4, 0, 4), "to": (12, 15, 12), "uv": "robe"},
    "head": {"from": (4, 15, 4), "to": (12, 24, 12), "uv": "head"},
    "nose": {"from": (7, 18, 2.5), "to": (9, 20.5, 4), "uv": "skin"},
    # 双手抱在身前（村民的标准姿势）：比身体略宽一点点，胸前一条。
    "arms": {"from": (3, 11, 3), "to": (13, 13.5, 5.5), "uv": "robe_dark"},
}


# --------------------------------------------------------------------------- 贴图


def build_texture():
    from PIL import Image, ImageDraw

    image = Image.new("RGBA", (64, 64), (0, 0, 0, 255))
    draw = ImageDraw.Draw(image)

    def fill(region, colour):
        draw.rectangle([region[0], region[1], region[2] - 1, region[3] - 1], fill=colour + (255,))

    fill(UV["skin"], COLOURS["skin"])
    fill(UV["hair"], COLOURS["hair"])
    fill(UV["face"], COLOURS["skin"])
    for index in range(VARIANT_COUNT):
        fill(UV["robe"][index], COLOURS["robe"][index])
        fill(UV["robe_dark"][index], COLOURS["robe_dark"][index])

    # 脸（UV["face"]，16x16，v 向下 = 从头顶到下巴）：
    #   眉：村民的标志性"一字眉"，横贯整张脸靠上一点；
    #   眼：白眼珠在外、深色瞳孔靠内（对称，所以贴图 u 方向反了也看不出来）。
    fx, fy = UV["face"][0], UV["face"][1]
    fill((fx + 1, fy + 4, fx + 15, fy + 6), COLOURS["brow"])
    for left in (1, 10):
        fill((fx + left, fy + 6, fx + left + 3, fy + 9), COLOURS["eye_white"])
    for left in (3, 10):
        fill((fx + left, fy + 6, fx + left + 2, fy + 9), COLOURS["pupil"])
    # 下巴一圈阴影 + 一条嘴线，让人脸不糊成一块。
    fill((fx + 5, fy + 12, fx + 11, fy + 13), COLOURS["mouth"])
    fill((fx, fy + 15, fx + 16, fy + 16), COLOURS["skin_shadow"])

    # 后脑勺：上面三分之二是头发，下面是肤色（脖子）。
    hx, hy = UV["hair"][0], UV["hair"][1]
    fill((hx, hy + 11, hx + 16, hy + 16), COLOURS["skin"])

    return image


# --------------------------------------------------------------------------- 模型


def face_entry(region, cullface=None):
    entry = {"uv": list(region), "texture": "#0"}
    if cullface:
        entry["cullface"] = cullface
    return entry


def part_faces(part_name, variant, dy):
    """一个立方体的六个面：按部位选贴图区域。"""
    spec = PARTS[part_name]
    region_for_part = spec["uv"]
    skin = UV["skin"]
    face = UV["face"]
    hair = UV["hair"]
    robe = UV["robe"][variant]
    robe_dark = UV["robe_dark"][variant]

    if region_for_part == "skin":
        return {side: face_entry(skin) for side in ("north", "east", "south", "west", "up", "down")}
    if region_for_part == "head":
        return {
            "north": face_entry(face),
            "south": face_entry(hair),
            "east": face_entry(skin),
            "west": face_entry(skin),
            "up": face_entry(skin),
            "down": face_entry(skin),
        }
    if region_for_part == "robe":
        return {side: face_entry(robe) for side in ("north", "east", "south", "west", "up", "down")}
    if region_for_part == "robe_dark":
        return {side: face_entry(robe_dark) for side in ("north", "east", "south", "west", "up", "down")}
    raise ValueError(region_for_part)


def build_model(variant, foot):
    dy = FOOT_OFFSET[foot]
    elements = []
    for part_name in ("robe", "head", "arms", "nose"):
        spec = PARTS[part_name]
        elements.append({
            "from": [spec["from"][0], spec["from"][1] + dy, spec["from"][2]],
            "to": [spec["to"][0], spec["to"][1] + dy, spec["to"][2]],
            "faces": part_faces(part_name, variant, dy),
        })
    return {
        "credit": "MMTR crowd villager (generated by mmtr/tools/crowd-villager/gen_crowd_villager.py)",
        "ambientocclusion": False,
        "textures": {"0": "mtr:block/crowd_villager", "particle": "mtr:block/crowd_villager"},
        "elements": elements,
    }


def build_blockstate():
    variants = {}
    for foot in range(FOOT_COUNT):
        for variant in range(VARIANT_COUNT):
            for rotation_index, facing in enumerate(FACINGS):
                key = f"facing={facing},foot={foot},variant={variant}"
                apply = {"model": f"mtr:block/crowd_villager_{variant}_{foot}"}
                if rotation_index:
                    apply["y"] = rotation_index * 90
                variants[key] = apply
    return {"variants": variants}


def build_item_model():
    """
    物品栏里用一个"满格站台 + 0 号配色"的模型，够认出是什么东西。

    为什么要自带 display：方块模型没有 `block/block` 父模型（那样会连它的 6 个面模板一起继承，
    而且立方体超出 1 格），所以物品的握持/图标变换只能在这里给。数值 = 原版 `block/block` 的
    display 乘 1/1.625（模型总高 26/16 格），让图标大小与普通方块一致。
    """
    return {
        "parent": "mtr:block/crowd_villager_0_0",
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": [0, -1, 0], "scale": [0.385, 0.385, 0.385]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 1.85, 0], "scale": [0.154, 0.154, 0.154]},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, -0.5, 0], "scale": [0.308, 0.308, 0.308]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 1.6, 0], "scale": [0.231, 0.231, 0.231]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, -0.5, 0], "scale": [0.246, 0.246, 0.246]},
        },
    }


# --------------------------------------------------------------------------- 写盘


def planned_files():
    files = {}
    for variant in range(VARIANT_COUNT):
        for foot in range(FOOT_COUNT):
            files[ASSETS / "models" / "block" / f"crowd_villager_{variant}_{foot}.json"] = build_model(variant, foot)
    files[ASSETS / "blockstates" / "crowd_villager.json"] = build_blockstate()
    files[ASSETS / "models" / "item" / "crowd_villager.json"] = build_item_model()
    return files


def dump_json(data):
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="只校验产物是否与脚本一致（CI/提交前用）")
    args = parser.parse_args()

    files = planned_files()
    mismatches = []

    for path, data in files.items():
        text = dump_json(data)
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                mismatches.append(path)
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        print(f"WROTE {path.relative_to(REPO_ROOT.parent)}")

    texture_path = ASSETS / "textures" / "block" / "crowd_villager.png"
    if args.check:
        if not texture_path.exists():
            mismatches.append(texture_path)
        else:
            from PIL import Image

            with Image.open(texture_path) as existing:
                expected = build_texture()
                if existing.size != expected.size or existing.convert("RGBA").tobytes() != expected.tobytes():
                    mismatches.append(texture_path)
    else:
        texture_path.parent.mkdir(parents=True, exist_ok=True)
        build_texture().save(texture_path)
        print(f"WROTE {texture_path.relative_to(REPO_ROOT.parent)}")

    if args.check:
        if mismatches:
            print("产物与脚本不一致：", file=sys.stderr)
            for path in mismatches:
                print(f"  {path}", file=sys.stderr)
            return 1
        print(f"OK：{len(files)} 个 JSON + 1 张贴图与脚本一致")
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
