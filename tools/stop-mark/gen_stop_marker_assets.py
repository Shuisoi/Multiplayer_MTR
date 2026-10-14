#!/usr/bin/env python3
"""生成「停车对标牌」`mtr:stop_marker` 的美术资源（blockstate / 三个方块模型 / 物品模型 / 贴图 / 掉落表）。

用法（工作区自带 Python + Pillow，见仓库其它 tools 脚本）：

    python mmtr/tools/stop-mark/gen_stop_marker_assets.py                  # 生成（默认）
    python mmtr/tools/stop-mark/gen_stop_marker_assets.py --check           # 只校验磁盘产物与脚本一致
    python mmtr/tools/stop-mark/gen_stop_marker_assets.py --lang-snippet    # 打印要插进 lang 的两行（只打印，不写）

产物（路径从脚本自身位置推算，**不含任何绝对路径**）：

    1. assets/mtr/blockstates/stop_marker.json                    4 facing × 3 variant = 12 个组合
    2. assets/mtr/models/block/stop_marker_pole.json              立杆式
       assets/mtr/models/block/stop_marker_hanging.json           吊挂式
       assets/mtr/models/block/stop_marker_wall.json              贴墙式
    3. assets/mtr/models/item/stop_marker.json                    item/generated + layer0
    4. assets/mtr/textures/block/stop_marker_plate.png            牌面（深色底 + 亮色描边，中央干净、不写死数字）
       assets/mtr/textures/block/stop_marker_metal.png            杆 / 吊杆 / 背板
    5. assets/mtr/textures/item/stop_marker.png                   16×16 物品图标（小牌子 + 杆 + 白色 "N"）
    6. data/mtr/loot_tables/blocks/stop_marker.json               掉自己

语言条目（`assets/mtr/lang/en_us.json` / `zh_cn.json`）**不由本脚本写**：那两个文件很大
（en_us 763 行），整体重写会把别人的改动冲掉，只能定点插入；脚本用 `--lang-snippet`
打印要插的内容，`--check` 里也会顺带核对这两行确实在磁盘上（只读，不写）。

--------------------------------------------------------------------------- 方块契约

方块 id `mtr:stop_marker`；属性 `facing` ∈ {north,east,south,west}、`variant` ∈ {0,1,2}
（0 = 立杆式 pole、1 = 吊挂式 hanging、2 = 贴墙式 wall）。所有几何坐标都是
**16 分之一格**，方块底面 y=0、顶面 y=16、中心 x=z=8（脚本里写成乘除 16 的浮点等价量）。

    pole     杆   from [7.5, 0, 7.5]  to [8.5, 16, 8.5]   metal（y 0–1.0 格 ⇒ 0–16）
             牌面 from [3, 9, 7.5]    to [13, 15, 8.5]    south 面 plate，其余 metal
    hanging  吊杆 from [7.5, 12, 7.5] to [8.5, 16, 8.5]   metal
             牌面 from [3, 5, 7.5]    to [13, 11, 8.5]    south 面 plate，其余 metal
    wall     牌面 from [3, 5, 13]     to [13, 11, 14]     south 面 plate，其余 metal
             背板 from [3, 5, 12.5]   to [13, 11, 13]     metal

--------------------------------------------------------------------------- 朝向约定（关键）

牌面文字画在背面 = 朝向约定写错，所以把理由钉在这里。

* 三个模型都按「牌面朝 **+Z**」建模 —— 牌面法线指向模型空间的 +Z。
* MC 的面名是**模型空间**的：+Z 就是 `south`，所以三个模型里「正面」在 JSON 里写作 `"south"`。
* `blockstate` 的 `y` 按 `y = Direction.asRotation(facing)` 配：

      | facing | north | east | south | west |
      | y      |  180  |  270 |    0  |   90 |

  理由：客户端渲染器（BER）用 `rotateYDegrees(-facing.asRotation())` 把文字放到「朝 facing
  的那一面」，它的局部 +Z 就是 facing 方向；要让"模型正面"和"文字所在面"重合，模型的正面
  就必须定义在 +Z，两边才自洽。
  而 `y = asRotation(facing)` 正是「把模型的 +Z 面转到 facing 方向」的标准映射，可以拿原版
  熔炉对照：熔炉模型正面在 **-Z**，它的 blockstate 是 `y = asRotation(facing) + 180`
  （facing=east → 90），因为 -Z 比 +Z 多转了 180°；本方块正面在 +Z，所以不加这 180。
  自检一遍：facing=south ⇒ y=0，模型 +Z = 世界 +Z = 南 ✔；facing=north ⇒ y=180，+Z 转到 -Z = 北 ✔。

--------------------------------------------------------------------------- 为什么用显式 uv

牌面元素只占方块的 x 3–13 / y 5–15 一小块，MC 默认 uv 会**按元素在方块内的投影裁贴图**
（pole 的正面只会取到贴图的 u 3–13, v 1–7），那样"亮色描边"会被裁掉一半、三个样式的
裁法还不一样。所以每个面都显式写 `"uv": [0, 0, 16, 16]`：整张贴图正好铺满这个面，
描边就是描边、中央就是中央，改贴图不用再回来看模型。改配色 / 字形 / 几何只改本文件。
"""

from __future__ import annotations

import argparse
import io
import json
import sys
from pathlib import Path

# --------------------------------------------------------------------------- 路径

SCRIPT = Path(__file__).resolve()
REPO_ROOT = SCRIPT.parents[2]  # mmtr/
RESOURCES = REPO_ROOT / "game" / "fabric" / "src" / "main" / "resources"
ASSETS = RESOURCES / "assets" / "mtr"
LOOT = RESOURCES / "data" / "mtr" / "loot_tables" / "blocks"
LANG = ASSETS / "lang"

MOD_ID = "mtr"
BLOCK_ID = "stop_marker"

# --------------------------------------------------------------------------- 常量

FACINGS = ("north", "east", "south", "west")

#: facing → blockstate 的 y（= Direction.asRotation(facing)，理由见文件头「朝向约定」）
FACING_Y = {"north": 180, "east": 270, "south": 0, "west": 90}

#: variant 属性值 → 模型后缀
VARIANTS = {0: "pole", 1: "hanging", 2: "wall"}

#: 模型贴图槽位 → 贴图路径
TEXTURE_SLOTS = {
    "particle": f"{MOD_ID}:block/{BLOCK_ID}_metal",
    "plate": f"{MOD_ID}:block/{BLOCK_ID}_plate",
    "metal": f"{MOD_ID}:block/{BLOCK_ID}_metal",
}

PLATE_TEXTURE = "#plate"
METAL_TEXTURE = "#metal"

ICON_SIZE = 16
FACE_NAMES = ("down", "up", "north", "south", "east", "west")

# 配色（物品图标与方块贴图共用同一套色，手里拿的和地上放的看起来是同一块牌子）
PLATE_DARK = (30, 33, 38)  # 牌面底色（正面中央的"干净区域"）
PLATE_LIGHT = (236, 240, 246)  # 亮色描边
PLATE_RIM = (86, 92, 101)  # 描边内侧压边（给牌面一点厚度感）
METAL_BASE = (124, 129, 136)  # 杆 / 吊杆 / 背板
GLYPH_COLOUR = (250, 250, 250, 255)  # 物品图标上的 "N"（对标 N = 停车位置）


# --------------------------------------------------------------------------- 几何（16 分之一格）

#: 三个样式的元素表：variant → [(from, to, south 面贴图, 其余面贴图), ...]
GEOMETRY = {
    "pole": [
        # 立杆只到牌面下沿（y=9）为止：通到方块顶（y=16）会与牌面在 z 7.5–8.5 上体积重叠，
        # 两面 south 共面同法线 → 杆顶那一条 1/16 宽会闪烁（Lead 复核时点名的）。
        ((7.5, 0, 7.5), (8.5, 9, 8.5), METAL_TEXTURE, METAL_TEXTURE),  # 立杆
        ((3, 9, 7.5), (13, 15, 8.5), PLATE_TEXTURE, METAL_TEXTURE),  # 牌面：正面朝 +Z
    ],
    "hanging": [
        ((7.5, 12, 7.5), (8.5, 16, 8.5), METAL_TEXTURE, METAL_TEXTURE),  # 顶部吊杆
        ((3, 5, 7.5), (13, 11, 8.5), PLATE_TEXTURE, METAL_TEXTURE),  # 牌面：正面朝 +Z
    ],
    "wall": [
        ((3, 5, 12.5), (13, 11, 13), METAL_TEXTURE, METAL_TEXTURE),  # 背板
        ((3, 5, 13), (13, 11, 14), PLATE_TEXTURE, METAL_TEXTURE),  # 牌面：贴着 +Z 侧，正面朝 +Z
    ],
}


# --------------------------------------------------------------------------- 贴图

def _shade(colour: tuple, delta: int) -> tuple:
    """把 RGB 整体加减 delta 并夹到 0…255（贴图配色统一走这里，不再手写第二份色号）。"""
    return tuple(max(0, min(255, channel + delta)) for channel in colour)


def draw_plate() -> "object":
    """牌面贴图：深色底 + 外圈 1 px 亮色描边 + 内侧 1 px 压边，中央只有很淡的竖向渐变。

    中央**不写任何数字/文字**：数字由方块实体渲染器按实际对标位置画上去，
    贴图里写死就废了（见文件头）。
    """
    from PIL import Image

    image = Image.new("RGBA", (ICON_SIZE, ICON_SIZE), PLATE_DARK + (255,))
    pixels = image.load()
    for y in range(ICON_SIZE):
        for x in range(ICON_SIZE):
            if x in (0, ICON_SIZE - 1) or y in (0, ICON_SIZE - 1):
                colour = PLATE_LIGHT
            elif x in (1, ICON_SIZE - 2) or y in (1, ICON_SIZE - 2):
                colour = PLATE_RIM
            else:
                # 上亮下暗的极淡渐变（±5），让牌面不至于是一块死板的纯色
                colour = _shade(PLATE_DARK, (ICON_SIZE // 2 - y) // 2)
            pixels[x, y] = colour + (255,)
    return image


def draw_metal() -> "object":
    """杆 / 吊杆 / 背板贴图：中灰底 + 确定性的细噪点（拉丝感）+ 上下轻微明暗。

    噪点用纯算术算出来（不用 random），保证同一次改动永远得到同一张 PNG，
    `--check` 的字节比对才有意义。
    """
    from PIL import Image

    image = Image.new("RGBA", (ICON_SIZE, ICON_SIZE), METAL_BASE + (255,))
    pixels = image.load()
    for y in range(ICON_SIZE):
        for x in range(ICON_SIZE):
            grain = ((x * 5 + y * 11) % 7) - 3  # -3…+3，位置固定
            gradient = (ICON_SIZE // 2 - y) // 3  # -2…+2
            pixels[x, y] = _shade(METAL_BASE, grain + gradient) + (255,)
    return image


def draw_icon() -> "object":
    """16×16 物品图标：下面一根金属杆，上面一块深色牌面（亮色描边）+ 白色 "N"。

    画法与 gen_crowd_marker_assets.py 的图标一致（同尺寸、同"填色 + 深色边框 + 白色字形"风格），
    好让这排标记方块在快捷栏里一眼能对上。
    """
    from PIL import Image, ImageDraw

    image = Image.new("RGBA", (ICON_SIZE, ICON_SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    # 杆：先画杆，牌面覆盖上沿（露出来的部分就是牌下面那截柱子）
    draw.rectangle((7, 8, 8, 15), fill=METAL_BASE + (255,), outline=_shade(METAL_BASE, -40) + (255,))

    # 牌面：深色底 + 亮色描边（与方块贴图同色系）
    draw.rectangle((2, 3, 13, 10), fill=PLATE_DARK + (255,), outline=PLATE_LIGHT + (255,))

    # 白色 "N"：两根竖笔 + 一条从左上到右下的斜笔（5×5 点阵，斜笔要逐行连上才不像 "H"）
    for row in range(5):
        draw.point((5, 5 + row), fill=GLYPH_COLOUR)  # 左竖
        draw.point((9, 5 + row), fill=GLYPH_COLOUR)  # 右竖
    for column in (6, 7, 8):
        draw.point((column, column + 0), fill=GLYPH_COLOUR)  # 斜笔 (6,6) (7,7) (8,8)

    return image


# --------------------------------------------------------------------------- JSON 产物

def blockstate() -> str:
    """4 facing × 3 variant = 12 个组合，键写成 `facing=north,variant=0`。"""
    variants = {}
    for facing in FACINGS:
        for variant, style in VARIANTS.items():
            variants[f"facing={facing},variant={variant}"] = {
                "model": f"{MOD_ID}:block/{BLOCK_ID}_{style}",
                "y": FACING_Y[facing],
            }
    return json.dumps({"variants": variants}, indent=2, ensure_ascii=False) + "\n"


def _face(texture: str) -> dict:
    """一个面：显式 uv [0,0,16,16]（理由见文件头「为什么用显式 uv」）。"""
    return {"texture": texture, "uv": [0, 0, 16, 16]}


def block_model(style: str) -> str:
    """一个样式的方块模型：牌面元素的 `south`（模型空间 +Z）面贴 plate，其余面贴 metal。"""
    elements = []
    for start, end, front_texture, side_texture in GEOMETRY[style]:
        faces = {name: _face(side_texture) for name in FACE_NAMES}
        faces["south"] = _face(front_texture)  # +Z = 牌面正面
        elements.append({"from": list(start), "to": list(end), "faces": faces})
    return json.dumps(
        {"parent": "block/block", "textures": TEXTURE_SLOTS, "elements": elements},
        indent=2,
        ensure_ascii=False,
    ) + "\n"


def item_model() -> str:
    return json.dumps(
        {"parent": "item/generated", "textures": {"layer0": f"{MOD_ID}:item/{BLOCK_ID}"}},
        indent=2,
        ensure_ascii=False,
    ) + "\n"


def loot_table() -> str:
    return json.dumps(
        {
            "type": "minecraft:block",
            "pools": [
                {
                    "rolls": 1,
                    "entries": [{"type": "minecraft:item", "name": f"{MOD_ID}:{BLOCK_ID}"}],
                    "conditions": [{"condition": "minecraft:survives_explosion"}],
                }
            ],
        },
        indent=2,
        ensure_ascii=False,
    ) + "\n"


# --------------------------------------------------------------------------- 语言条目（只读核对 / 打印）

LANG_ENTRIES = {"en_us": "Stop Marker", "zh_cn": "停车对标牌"}


def lang_snippet() -> str:
    lines = [f'语言条目（定点插入，不要整体重写这两个文件）：', ""]
    for locale, value in LANG_ENTRIES.items():
        lines.append(f'  {LANG / (locale + ".json")}')
        lines.append(f'\t"block.mtr.{BLOCK_ID}": {json.dumps(value, ensure_ascii=False)},')
        lines.append("")
    lines.append("en_us.json：插在 block.mtr.station_pole 与 block.mtr.tactile_map 之间（字典序 sta < sto < tac）。")
    lines.append("zh_cn.json：插在最后一个 block.mtr.* 条目之后、gui.mtr.* 之前。")
    return "\n".join(lines)


def lang_problems() -> "list[str]":
    """只读核对：两个 lang 文件里都真的有 block.mtr.stop_marker（不会改动文件）。"""
    problems = []
    for locale, value in LANG_ENTRIES.items():
        path = LANG / f"{locale}.json"
        key = f"block.mtr.{BLOCK_ID}"
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            problems.append(f"{path.relative_to(REPO_ROOT)} 不存在")
            continue
        except json.JSONDecodeError as error:
            problems.append(f"{path.relative_to(REPO_ROOT)} 不是合法 JSON：{error}")
            continue
        if data.get(key) != value:
            problems.append(f'{path.relative_to(REPO_ROOT)} 缺少 {key} = "{value}"（当前 {data.get(key)!r}）')
    return problems


# --------------------------------------------------------------------------- 主流程

def expected_text_files() -> "dict[Path, str]":
    files = {
        ASSETS / "blockstates" / f"{BLOCK_ID}.json": blockstate(),
        ASSETS / "models" / "item" / f"{BLOCK_ID}.json": item_model(),
        LOOT / f"{BLOCK_ID}.json": loot_table(),
    }
    for style in VARIANTS.values():
        files[ASSETS / "models" / "block" / f"{BLOCK_ID}_{style}.json"] = block_model(style)
    return files


def expected_texture_files() -> "dict[Path, bytes]":
    images = {
        ASSETS / "textures" / "block" / f"{BLOCK_ID}_plate.png": draw_plate(),
        ASSETS / "textures" / "block" / f"{BLOCK_ID}_metal.png": draw_metal(),
        ASSETS / "textures" / "item" / f"{BLOCK_ID}.png": draw_icon(),
    }
    blobs = {}
    for path, image in images.items():
        buffer = io.BytesIO()
        image.save(buffer, format="PNG", optimize=True)
        blobs[path] = buffer.getvalue()
    return blobs


def main() -> int:
    parser = argparse.ArgumentParser(description="生成「停车对标牌」mtr:stop_marker 的美术资源")
    parser.add_argument("--check", action="store_true", help="只校验磁盘上的产物与脚本一致")
    parser.add_argument("--lang-snippet", action="store_true", help="打印要插进 lang 的两行（只打印，不写）")
    args = parser.parse_args()

    if args.lang_snippet:
        print(lang_snippet())
        return 0

    problems = 0

    # 文本产物
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
    for path, content in sorted(expected_texture_files().items()):
        if args.check:
            actual = path.read_bytes() if path.is_file() else None
            if actual != content:
                print(f"[不一致] {path.relative_to(REPO_ROOT)}")
                problems += 1
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        print(f"[写] {path.relative_to(REPO_ROOT)}")

    # 语言条目不在本脚本的产物清单里（大文件，定点插入），但 --check 时顺带只读核对
    for problem in lang_problems():
        print(f"[不一致] {problem}")
        problems += 1

    if args.check:
        print(f"-- 校验完成：{'全部一致' if problems == 0 else str(problems) + ' 个文件与脚本不一致'}")
        return 1 if problems else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
