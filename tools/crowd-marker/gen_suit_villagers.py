#!/usr/bin/env python3
"""**西装客流**纹理包生成器：把原版村民的「衣服层」重上色成现代正装，产出一个纯纹理资源包。

    python mmtr/tools/crowd-marker/gen_suit_villagers.py            # 生成 + 装进 run/resourcepacks
    python mmtr/tools/crowd-marker/gen_suit_villagers.py --preview   # 额外出一张 8 倍对照图给眼睛看

## 为什么只改 `type/*.png`

原版村民是**两层纹理**（这是这次解剖出来的关键事实，`VillagerEntityRenderer` + 服装 feature 层）：

| 文件 | 画的是什么 | 我们的处理 |
|---|---|---|
| `textures/entity/villager/villager.png` | **脸、眼睛、手**（底层） | **一个字都不动** ⇒ 永远不会再出"没眼睛"那种事故 |
| `textures/entity/villager/type/<群系>.png` | **衣服**（袍子/袖子/裤腿，覆盖层） | 重上色成西装 |

而客流村民的"群系类型"是**按位置哈希**从 7 种里选的（见 `MmtrCrowdVillagers#TYPES`），
所以只要给 7 个 `type/*.png` 各配一套西装色，站台上自然就是**7 种颜色混在一起的通勤人流**。

## 为什么是纯纹理、零帧数代价

不引入 `.jem`（自定义模型）、不引入 `.jpm`（动画表达式）⇒ EMF 完全不参与村民渲染，
每只村民的成本和我们现在的 110+ 帧版本**一模一样**（用户口径：人数与视距都不许限制，
所以只能从"单只成本"里省，而 FA 那套 EMF 每实体处理正是被摘掉的那笔）。

## 怎么上色

1. 取原版 type 纹理里**棕色家族**（袍子的所有明暗）—— 村民的皮肤也是棕色，所以只按
   "色调偏红且 R>G>B" 判家族，再把**亮度**线性映射到西装的明暗梯度上：
   这样原图的褶皱/阴影层次被原样保留，换成西装后依然有立体感；
2. 在**身体正面**那一块矩形上画白衬衫 + 领带（矩形位置是从模型 UV 推出来的，见 BODY_FRONT）；
3. 透明像素一律保持透明（衣服层里没有被衣服盖住的地方要给底层让路）。
"""
import argparse
import io
import json
import os
import sys
import zipfile

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
RUN = os.path.join(ROOT, "mmtr", "game", "fabric", "run")
VANILLA_PACK = os.path.join(RUN, "resourcepacks", "MMTR_vanilla_villagers.zip")
OUT_PACK = os.path.join(RUN, "resourcepacks", "MMTR_suit_villagers.zip")
PREVIEW = os.path.join(ROOT, "sandbox", "tmp", "suit_preview.png")

# 身体那个方块在纹理里的位置：uv=(0,46)、尺寸 8×12×6 ⇒ 纹理块 x[0,28) y[46,64)
# 正面按 MC 的标准盒展开 = (u+dz, v+dz) 尺寸 dx×dy ⇒ x[6,14) y[52,64)
BODY_FRONT = (6, 52, 14, 64)

# 7 种群系各一套西装：base 决定色相，shirt 衬衫，tie 领带
SUITS = {
    "plains": {"name": "藏青", "base": (44, 62, 104), "tie": (150, 40, 46)},
    "desert": {"name": "驼色", "base": (150, 122, 84), "tie": (58, 78, 120)},
    "jungle": {"name": "墨绿", "base": (46, 82, 62), "tie": (196, 168, 96)},
    "savanna": {"name": "咖色", "base": (108, 78, 56), "tie": (206, 206, 206)},
    "snow": {"name": "浅灰", "base": (176, 178, 182), "tie": (60, 66, 96)},
    "swamp": {"name": "橄榄", "base": (86, 92, 56), "tie": (168, 92, 48)},
    "taiga": {"name": "炭灰", "base": (72, 74, 80), "tie": (140, 44, 52)},
}


def is_robe_family(color):
    """这一像素算不算"袍子的棕色家族"。

    村民连皮肤都是棕的，所以判据不能只看"是不是棕"，要看**偏红的棕**（R>G>B 且差值够大）——
    皮肤/手是更灰的浅棕（R-G 小），会被挡在外面（这也正是我们要的：只动衣服）。
    """
    r, g, b, a = color
    if a == 0:
        return False
    return r > g > b and (r - b) >= 18 and r >= 45


def luminance(color):
    r, g, b = color[:3]
    return 0.299 * r + 0.587 * g + 0.114 * b


def recolor(image, base, tie):
    """把衣服层的棕色家族按亮度重映射到西装色，再画衬衫 + 领带。"""
    pixels = image.load()
    family = [pixels[x, y] for y in range(image.height) for x in range(image.width) if is_robe_family(pixels[x, y])]
    if not family:
        return image
    lo = min(luminance(c) for c in family)
    hi = max(luminance(c) for c in family)
    span = max(1.0, hi - lo)

    # 西装的明暗梯度：暗端 = base × 0.55，亮端 = base 提亮到 1.28
    dark = tuple(max(0, min(255, int(channel * 0.55))) for channel in base)
    light = tuple(max(0, min(255, int(channel * 1.28 + 22))) for channel in base)

    for y in range(image.height):
        for x in range(image.width):
            color = pixels[x, y]
            if not is_robe_family(color):
                continue
            t = (luminance(color) - lo) / span
            pixels[x, y] = tuple(int(dark[i] + (light[i] - dark[i]) * t) for i in range(3)) + (255,)

    # 身体正面：白衬衫 + 领带 + 翻领
    x0, y0, x1, y1 = BODY_FRONT
    shirt = (232, 234, 238, 255)
    collar = (208, 212, 220, 255)
    tie_color = tuple(tie) + (255,)
    lapel = tuple(max(0, min(255, int(channel * 0.78))) for channel in base) + (255,)

    for y in range(y0, y1):
        for x in range(x0, x1):
            if pixels[x, y][3] == 0:
                continue
            local_y = y - y0
            local_x = x - x0
            if local_y <= 1:                       # 领口那一圈
                pixels[x, y] = collar
            elif local_x in (2, 5):                # 衬衫两侧
                pixels[x, y] = shirt
            elif local_x in (0, 1, 6, 7) and local_y >= 3:
                pixels[x, y] = lapel if local_x in (0, 7) else shirt
            elif local_x in (3, 4):                # 领带（最上面两行加宽成结）
                pixels[x, y] = tie_color
            else:
                pixels[x, y] = shirt
    return image


def load_vanilla_textures():
    if not os.path.isfile(VANILLA_PACK):
        raise SystemExit(f"找不到原版村民纹理包：{VANILLA_PACK}")
    with zipfile.ZipFile(VANILLA_PACK) as archive:
        textures = {}
        for kind in SUITS:
            path = f"assets/minecraft/textures/entity/villager/type/{kind}.png"
            textures[kind] = Image.open(io.BytesIO(archive.read(path))).convert("RGBA")
    return textures


def write_pack(textures):
    meta = {
        "pack": {
            "pack_format": 22,
            "description": "MMTR 西装客流（只改村民衣服层 type/*.png；脸/手不动；无模型无动画，零帧数代价）",
            "supported_formats": {"min_inclusive": 15, "max_inclusive": 999},
        }
    }
    with zipfile.ZipFile(OUT_PACK, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("pack.mcmeta", json.dumps(meta, ensure_ascii=False, indent=2))
        for kind, image in textures.items():
            buffer = io.BytesIO()
            image.save(buffer, "PNG")
            archive.writestr(f"assets/minecraft/textures/entity/villager/type/{kind}.png", buffer.getvalue())


def write_preview(textures):
    scale = 8
    cell_w, cell_h = 64 * scale, 64 * scale
    sheet = Image.new("RGBA", (cell_w * 4, cell_h * 2), (24, 24, 28, 255))
    draw = ImageDraw.Draw(sheet)
    for index, (kind, image) in enumerate(textures.items()):
        x = (index % 4) * cell_w
        y = (index // 4) * cell_h
        sheet.alpha_composite(image.resize((cell_w, cell_h), Image.NEAREST), (x, y))
        draw.text((x + 10, y + 10), f"{kind} {SUITS[kind]['name']}", fill=(255, 220, 0, 255))
    sheet.save(PREVIEW)
    return PREVIEW


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", action="store_true", help="额外生成 8 倍对照图")
    args = parser.parse_args()

    textures = {kind: recolor(image, SUITS[kind]["base"], SUITS[kind]["tie"]) for kind, image in load_vanilla_textures().items()}
    write_pack(textures)
    print(f"已生成 {OUT_PACK}（{len(textures)} 套西装：{'、'.join(SUITS[k]['name'] for k in textures)}）")
    if args.preview:
        print("对照图：" + write_preview(textures))
    return 0


if __name__ == "__main__":
    sys.exit(main())
