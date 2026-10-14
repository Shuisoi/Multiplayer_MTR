#!/usr/bin/env python3
"""生成「客流标记」四个方块的美术资源（blockstate / 空模型 / 物品模型 / 物品贴图 / 掉落表）。

用法（工作区自带 Python + Pillow）：

    python mmtr/tools/crowd-marker/gen_crowd_marker_assets.py            # 生成
    python mmtr/tools/crowd-marker/gen_crowd_marker_assets.py --check    # 只校验磁盘上的产物与脚本一致

产物（路径从脚本自身位置推算，**不含任何绝对路径**）：

    assets/mtr/blockstates/crowd_mark_1.json        四个朝向都指到空模型
    assets/mtr/blockstates/crowd_mark_2.json
    assets/mtr/blockstates/crowd_flow.json
    assets/mtr/blockstates/crowd_anchor.json
    assets/mtr/models/block/crowd_marker_empty.json 空模型（方块整格交给方块实体渲染器，见 BlockCrowdMarker）
    assets/mtr/models/item/<id>.json
    assets/mtr/textures/item/<id>.png               16x16 图标（一级/二级/流动/锚）
    data/mtr/loot_tables/blocks/<id>.json           掉自己（标记要能捡回来复用）

为什么要脚本：四个方块除了贴图里的字形之外完全同构，手写 13 个 JSON 只会抄错；
而"拿到手里长什么样"必须一眼能分清（一级/二级/流动/锚），字形得是像素级的、可复现的。
改配色或字形只改本文件。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# --------------------------------------------------------------------------- 路径

SCRIPT = Path(__file__).resolve()
REPO_ROOT = SCRIPT.parents[2]  # mmtr/
RESOURCES = REPO_ROOT / "game" / "fabric" / "src" / "main" / "resources"
ASSETS = RESOURCES / "assets" / "mtr"
LOOT = RESOURCES / "data" / "mtr" / "loot_tables" / "blocks"

MOD_ID = "mtr"

# --------------------------------------------------------------------------- 四个标记

#: id → (物品图标配色, 字形)。颜色与 BlockCrowdMarker.Kind 里的 ARGB 一致（那边是半透明版）。
KINDS = {
    "crowd_mark_1": {"fill": (214, 45, 76), "border": (122, 18, 40), "glyph": "one"},
    "crowd_mark_2": {"fill": (38, 160, 214), "border": (16, 88, 128), "glyph": "two"},
    "crowd_flow": {"fill": (52, 190, 96), "border": (22, 106, 50), "glyph": "arrow"},
    "crowd_anchor": {"fill": (222, 150, 32), "border": (128, 82, 10), "glyph": "anchor"},
}

GLYPH_COLOUR = (250, 250, 250, 255)
FACINGS = ("north", "east", "south", "west")
ICON_SIZE = 16


# --------------------------------------------------------------------------- 图标

def draw_icon(kind: dict) -> "object":
    """画一张 16x16 的物品图标：填色 + 深色边框 + 白色字形。"""
    from PIL import Image, ImageDraw

    image = Image.new("RGBA", (ICON_SIZE, ICON_SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    fill = kind["fill"] + (235,)
    border = kind["border"] + (255,)

    draw.rectangle((1, 1, ICON_SIZE - 2, ICON_SIZE - 2), fill=fill, outline=border)

    glyph = kind["glyph"]
    if glyph == "one":
        # 一级：一根竖条 + 左上小旗（"1"）
        draw.rectangle((7, 4, 9, 12), fill=GLYPH_COLOUR)
        draw.rectangle((5, 4, 6, 6), fill=GLYPH_COLOUR)
    elif glyph == "two":
        # 二级：一个"2"（再往里一层的意思）—— 顶横、右竖、中横、左竖、底横
        draw.rectangle((5, 4, 11, 5), fill=GLYPH_COLOUR)
        draw.rectangle((10, 5, 11, 8), fill=GLYPH_COLOUR)
        draw.rectangle((5, 8, 10, 9), fill=GLYPH_COLOUR)
        draw.rectangle((4, 9, 5, 11), fill=GLYPH_COLOUR)
        draw.rectangle((4, 12, 12, 13), fill=GLYPH_COLOUR)
    elif glyph == "arrow":
        # 流动：一支朝右的箭头（人流方向）
        draw.rectangle((3, 7, 8, 9), fill=GLYPH_COLOUR)
        draw.polygon([(8, 4), (13, 8), (8, 12)], fill=GLYPH_COLOUR)
    elif glyph == "anchor":
        # 归属锚：一根竖杆 + 顶上一横 + 底下两只钩
        draw.rectangle((7, 3, 8, 12), fill=GLYPH_COLOUR)
        draw.rectangle((5, 4, 10, 5), fill=GLYPH_COLOUR)
        draw.rectangle((4, 10, 5, 12), fill=GLYPH_COLOUR)
        draw.rectangle((10, 10, 11, 12), fill=GLYPH_COLOUR)
        draw.rectangle((4, 12, 11, 13), fill=GLYPH_COLOUR)
    else:
        raise ValueError(f"不认识的字形：{glyph}")

    return image


# --------------------------------------------------------------------------- JSON 产物

def blockstate() -> str:
    variants = {f"facing={facing}": {"model": f"{MOD_ID}:block/crowd_marker_empty"} for facing in FACINGS}
    return json.dumps({"variants": variants}, indent=2, ensure_ascii=False) + "\n"


def empty_model() -> str:
    # 空 elements：整格交给方块实体渲染器（BlockCrowdMarker.getRenderType2 = ENTITYBLOCK_ANIMATED），
    # 所以这块模型永远不会被画出来，只是为了让每个方块状态都能解析到一个模型。
    return json.dumps(
        {
            "parent": "block/block",
            "textures": {"particle": "minecraft:block/stone"},
            "elements": [],
        },
        indent=2,
        ensure_ascii=False,
    ) + "\n"


def item_model(identifier: str) -> str:
    return json.dumps(
        {"parent": "item/generated", "textures": {"layer0": f"{MOD_ID}:item/{identifier}"}},
        indent=2,
        ensure_ascii=False,
    ) + "\n"


def loot_table(identifier: str) -> str:
    return json.dumps(
        {
            "type": "minecraft:block",
            "pools": [
                {
                    "rolls": 1,
                    "entries": [{"type": "minecraft:item", "name": f"{MOD_ID}:{identifier}"}],
                    "conditions": [{"condition": "minecraft:survives_explosion"}],
                }
            ],
        },
        indent=2,
        ensure_ascii=False,
    ) + "\n"


# --------------------------------------------------------------------------- 主流程

def expected_text_files() -> "dict[Path, str]":
    files = {
        ASSETS / "models" / "block" / "crowd_marker_empty.json": empty_model(),
    }
    for identifier in KINDS:
        files[ASSETS / "blockstates" / f"{identifier}.json"] = blockstate()
        files[ASSETS / "models" / "item" / f"{identifier}.json"] = item_model(identifier)
        files[LOOT / f"{identifier}.json"] = loot_table(identifier)
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description="生成客流标记方块的美术资源")
    parser.add_argument("--check", action="store_true", help="只校验磁盘上的产物与脚本一致")
    args = parser.parse_args()

    problems = 0
    for path, content in sorted(expected_text_files().items()):
        if args.check:
            actual = path.read_text(encoding="utf-8") if path.is_file() else None
            if actual != content:
                print(f"[不一致] {path.relative_to(REPO_ROOT)}")
                problems += 1
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")
        print(f"[写] {path.relative_to(REPO_ROOT)}")

    # 贴图走 Pillow：统一转成 PNG 字节再比对（--check 时不会碰磁盘）
    import io

    for identifier, kind in KINDS.items():
        path = ASSETS / "textures" / "item" / f"{identifier}.png"
        buffer = io.BytesIO()
        draw_icon(kind).save(buffer, format="PNG", optimize=True)
        content = buffer.getvalue()
        if args.check:
            actual = path.read_bytes() if path.is_file() else None
            if actual != content:
                print(f"[不一致] {path.relative_to(REPO_ROOT)}")
                problems += 1
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        print(f"[写] {path.relative_to(REPO_ROOT)}")

    if args.check:
        print(f"-- 校验完成：{'全部一致' if problems == 0 else str(problems) + ' 个文件与脚本不一致'}")
        return 1 if problems else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
