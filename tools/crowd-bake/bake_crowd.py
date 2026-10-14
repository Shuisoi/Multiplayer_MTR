"""Human Era 客流：把村民烘成**网格 + 自有图集**（离线一次，产物进 jar）。

    python mmtr/tools/crowd-bake/bake_crowd.py                 # 生成
    python mmtr/tools/crowd-bake/bake_crowd.py --check         # 与仓里现有产物逐字节比
    python mmtr/tools/crowd-bake/bake_crowd.py --preview out.png   # 软件渲染一张预览（不开游戏看效果）

产物（默认落 `mmtr/game/fabric/src/main/resources/assets/mtr/crowd/`）：
    crowd_mesh.json    28 个成年盒子（脚在 y=0、x/z 居中）+ 每面 uv 矩形（单位 = 128 像素）
    crowd_atlas.png    全部外观贴图拼成的一张图集（2 像素 gutter，防采样渗色）
    crowd_tiles.json   图集尺寸 + 每块贴图的落点 + 四个"组别"（底皮 / 群系 / 职业 / 等级）的下标表

为什么是网格而不是方块模型（结论与代价见 docs/01-设计/站台客流-烘焙静态方块-更简方案.md）：
  * 方块模型必须走方块图集（实体贴图不在图集里 ⇒ 紫黑），旋转只认 0/±22.5/±45，
    且客量一变就要重建区块网格；
  * 网格：任意角度、任意贴图、每站台 1 次 draw、层数不必复制几何。

几何/贴图口径**逐字来自 Human Era 自己的 villager.jem**（不是启发式）：
  * 成年只画 `bodywear/player` 子树的 head2 支（head3/bone3 是幼年"大头"，被 69420 移走）；
  * 坐标：E = ( -(T.x + x), 24 + T.y + y, T.z + z )，再 × 0.9375 换成方块单位（= 实体渲染的 scale）；
    x 取负来自 jem 的 `invertAxis: "xy"`；24 是"模型原点离脚底 24 单位"；
  * uv：MC 标准盒展开，单位就是 128×128 贴图上的像素（1 uv 单位 = 8 像素，与盒尺寸同一比例）。
"""
import argparse
import hashlib
import io
import json
import math
import sys
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]          # mmtr/
WORKSPACE = ROOT.parent                             # 工作区根
DEFAULT_PACK = WORKSPACE / "mmtr" / "game" / "fabric" / "run" / "resourcepacks" / "HumanEra_Villagers.zip"
DEFAULT_OUT = ROOT / "game" / "fabric" / "src" / "main" / "resources" / "assets" / "mtr"


def output_paths(out):
    """三份产物各自的位置（图集必须在 `textures/` 下才是可加载的贴图）。"""
    return {
        "mesh": out / "crowd" / "crowd_mesh.json",
        "tiles": out / "crowd" / "crowd_tiles.json",
        "atlas": out / "textures" / "crowd" / "crowd_atlas.png",
    }

JEM_ENTRIES = (
    "assets/minecraft/emf/cem/villager.jem",        # EMF 4+ 路径
    "assets/minecraft/optifine/cem/villager.jem",   # OptiFine 兼容路径
)
SKIN_DIR = "assets/minecraft/optifine/random/entity/villager/"
SKIN_FALLBACK = "assets/minecraft/textures/entity/villager/villager.png"
TYPE_DIR = "assets/minecraft/textures/entity/villager/type/"
PROFESSION_DIR = "assets/minecraft/textures/entity/villager/profession/"
LEVEL_DIR = "assets/minecraft/textures/entity/villager/profession_level/"

TILE = 128          # Human Era 的贴图一律 128×128（实测）
GUTTER = 2          # 每块四周留 2 像素（边缘像素外扩），防图集采样渗色
SCALE = 0.9375      # LivingEntityRenderer 的 scale(0.9375)
Y_ORIGIN = 24.0     # 模型原点离脚底 24 单位
LONG_HAIR_PARTS = ("head3",)   # 幼年"大头"支（含 bone3 子盒），成年不画

FACES = ("down", "up", "north", "south", "west", "east")
# 原版 `CubeFace` 的角表（从 1.20.4 映射 jar 的 `javap -c` 里逐个数出来的，不是凭直觉写的），
# 角 i 的 uv 按原版 `ModelElementTexture`：i∈{0,1}→u0、i∈{2,3}→u1、i∈{0,3}→v0、i∈{1,2}→v1。
# 于是"贴图朝向"与原版方块模型逐像素同源 —— 不靠猜，也不靠看着像。
FACE_CORNERS = {
    "down": ((0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)),
    "up": ((0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)),
    "north": ((1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)),
    "south": ((0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)),
    "west": ((0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)),
    "east": ((1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)),
}
FACE_NORMALS = {
    "down": (0, -1, 0), "up": (0, 1, 0),
    "north": (0, 0, -1), "south": (0, 0, 1),
    "west": (-1, 0, 0), "east": (1, 0, 0),
}


def rotate90(point, rotate):
    """jem 的 `rotate`（度）作用在**盒子局部坐标**上；本包只出现 90° 整数倍（bone2/bone3 的 [90,0,0]）。

    语义是实测出来的（见 sandbox/tmp/cross_check_mesh.py）：**绕该部件自己的原点转，translate 不参与旋转**
    —— 拿这一点去对"真渲染录下来的地面真值"，bone2 的帽子边从"竖着的板"变回 y 向厚 1、xz 各 15 的
    横板，中心由 (0,32.81,-7.97) 落到 (0,25.80,0)，与派生模型逐位吻合。
    """
    if rotate is None or all(abs(v) < 1e-9 for v in rotate):
        return point
    x, y, z = point
    rx, ry, rz = rotate
    for axis, angle in (("x", rx), ("y", ry), ("z", rz)):
        steps = round(angle / 90.0) % 4
        if steps == 0:
            continue
        for _ in range(steps):
            if axis == "x":
                x, y, z = x, -z, y
            elif axis == "y":
                x, y, z = z, y, -x
            else:
                x, y, z = -y, x, z
    return x, y, z


# --------------------------------------------------------------------- jem

def box_faces(u, v, w, h, d):
    """MC 标准盒展开：{面: (x0, y0, x1, y1)}，单位 = 贴图像素。"""
    return {
        "east": (u, v + d, u + d, v + d + h),
        "north": (u + d, v + d, u + d + w, v + d + h),
        "west": (u + d + w, v + d, u + 2 * d + w, v + d + h),
        "south": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
        "up": (u + d, v, u + d + w, v + d),
        "down": (u + d + w, v, u + d + 2 * w, v + d),
    }


def read_jem(archive):
    for entry in JEM_ENTRIES:
        try:
            return json.loads(archive.read(entry).decode("utf-8")), entry
        except KeyError:
            continue
    raise SystemExit(f"包里找不到 villager.jem（试过 {JEM_ENTRIES}）")


def walk_boxes(jem):
    """摊平 jem：每个盒子一条（部件链 + 累加 translate + 累加 rotate + 声明尺寸/uv/sizeAdd）。"""
    rows = []

    def walk(node, chain, translate, rotate):
        local = tuple(translate[i] + (node.get("translate") or [0, 0, 0])[i] for i in range(3))
        own = node.get("rotate") or [0.0, 0.0, 0.0]
        stacked = tuple((rotate[i] or 0.0) + (own[i] or 0.0) for i in range(3))
        name = node.get("id") or node.get("part") or "?"
        cur = chain + "/" + name
        for box in node.get("boxes") or []:
            rows.append((cur, tuple(box["coordinates"]), tuple(box["textureOffset"]),
                         float(box.get("sizeAdd", 0.0)), local, stacked))
        for child in node.get("submodels") or []:
            walk(child, cur, local, stacked)

    for model in jem["models"]:
        walk(model, "", (0, 0, 0), (0.0, 0.0, 0.0))
    return rows


def build_mesh(jem):
    """盒子 → 网格条目：脚在 y=0、x/z 居中、单位 = 方块（1/16 格）。

    `sizeAdd` **要加**（原版 `Cuboid` 的 Dilation：六个面各外扩 sizeAdd）——实测派生模型
    （真渲染录下来的）每个盒子都比声明尺寸大 `2×sizeAdd×0.9375`，而叠加的衣服层正是靠这个膨胀
    避免共面闪烁的（sandbox/tmp/check_dilation.py）。膨胀只放大几何，**uv 展开仍用声明的尺寸**。
    """
    boxes = []
    for chain, coords, uv, size_add, translate, rotate in walk_boxes(jem):
        if any(part in chain for part in LONG_HAIR_PARTS):
            continue                                    # 幼年支
        x, y, z, w, h, d = coords
        # 每个角先做膨胀（部件局部空间）→ 再按部件自身的 rotate 绕原点转（本包只有 bone2/bone3 的 [90,0,0]）
        # → 再照 jem 空间 → 实体空间：x 取负（invertAxis xy）、y 平移 24（脚落 0）
        corners = [rotate90((x + dx * w + (size_add if dx else -size_add),
                             y + dy * h + (size_add if dy else -size_add),
                             z + dz * d + (size_add if dz else -size_add)), rotate)
                   for dx, dy, dz in ((0, 0, 0), (1, 1, 1))]
        ex = sorted(-(translate[0] + p[0]) for p in corners)
        ey = sorted(Y_ORIGIN + translate[1] + p[1] for p in corners)
        ez = sorted(translate[2] + p[2] for p in corners)
        boxes.append({
            "chain": chain.lstrip("/"),
            "from": [round(v * SCALE, 5) for v in (ex[0], ey[0], ez[0])],
            "to": [round(v * SCALE, 5) for v in (ex[1], ey[1], ez[1])],
            "uv": {face: list(box_faces(uv[0], uv[1], w, h, d)[face]) for face in FACES},
            "sizeAdd": size_add,
        })
    boxes.sort(key=lambda b: (b["chain"], b["from"], b["to"]))
    return boxes


# ------------------------------------------------------------------- 贴图

def list_tiles(archive):
    """四个组别的贴图清单（人类可读的短名 → zip 内路径）。

    `optifine/random/entity/villager/` 底下还有 `baby/`（幼年皮，48 张）——**不要**
    （我们烘的是成年模型，幼年皮会张冠李戴），所以只收该目录**第一层**的 png。
    """
    groups = {"skin": [], "type": [], "profession": [], "level": []}
    for name in sorted(n for n in archive.namelist() if n.startswith(SKIN_DIR) and n.endswith(".png")):
        if "/" in name[len(SKIN_DIR):]:
            continue                                    # 子目录（baby/ 等）跳过
        groups["skin"].append((Path(name).stem, name))
    groups["skin"].append(("villager", SKIN_FALLBACK))
    for key, prefix in (("type", TYPE_DIR), ("profession", PROFESSION_DIR), ("level", LEVEL_DIR)):
        for name in sorted(n for n in archive.namelist() if n.startswith(prefix) and n.endswith(".png")):
            groups[key].append((Path(name).stem, name))
    return groups


def build_atlas(archive, groups):
    tiles = []
    for key in ("skin", "type", "profession", "level"):
        for short, entry in groups[key]:
            tiles.append({"group": key, "name": short, "entry": entry})
    columns = max(1, int(len(tiles) ** 0.5))
    rows = (len(tiles) + columns - 1) // columns
    cell = TILE + GUTTER * 2
    atlas = Image.new("RGBA", (columns * cell, rows * cell), (0, 0, 0, 0))
    for index, tile in enumerate(tiles):
        column, row = index % columns, index // columns
        origin = (column * cell + GUTTER, row * cell + GUTTER)
        image = Image.open(io.BytesIO(archive.read(tile["entry"]))).convert("RGBA")
        if image.size != (TILE, TILE):
            raise SystemExit(f"{tile['entry']} 尺寸是 {image.size}，本烘焙器只支持 {TILE}×{TILE}")
        atlas.paste(image, origin)
        # 边缘外扩 gutter（最近邻放大 1 像素边框），压住图集采样渗色
        left = image.crop((0, 0, 1, TILE)).resize((GUTTER, TILE), Image.NEAREST)
        right = image.crop((TILE - 1, 0, TILE, TILE)).resize((GUTTER, TILE), Image.NEAREST)
        top = image.crop((0, 0, TILE, 1)).resize((TILE, GUTTER), Image.NEAREST)
        bottom = image.crop((0, TILE - 1, TILE, TILE)).resize((TILE, GUTTER), Image.NEAREST)
        atlas.paste(left, (origin[0] - GUTTER, origin[1]))
        atlas.paste(right, (origin[0] + TILE, origin[1]))
        atlas.paste(top, (origin[0], origin[1] - GUTTER))
        atlas.paste(bottom, (origin[0], origin[1] + TILE))
        tile["index"] = index
        tile["x"] = origin[0]
        tile["y"] = origin[1]
    meta = {
        "atlas": {"width": atlas.width, "height": atlas.height, "tile": TILE, "gutter": GUTTER},
        "groups": {key: [t["index"] for t in tiles if t["group"] == key]
                   for key in ("skin", "type", "profession", "level")},
        "tiles": [{k: t[k] for k in ("index", "group", "name", "x", "y")} for t in tiles],
    }
    return atlas, meta


# ------------------------------------------------------------- 自检/预览

def check_mesh(boxes):
    """几何自检：盒数、脚落地、占地、每面 uv 在图内。"""
    problems = []
    if len(boxes) != 28:
        problems.append(f"成年盒子数 = {len(boxes)}，期望 28（32 减去 head3 3 个 + bone3 1 个）")
    lowest = min(b["from"][1] for b in boxes)
    if not (-0.3 <= lowest <= 1e-6):
        # 腿的膨胀（sizeAdd 0.25 × 0.9375 = 0.234）会让最低面略微低于 0 —— 实机里的村民也是这样，
        # 所以这里允许 [-0.3, 0]；超出就不是"小腿外扩"能解释的了。
        problems.append(f"最低盒底 y = {lowest}，期望 0（脚落地；膨胀允许低到 -0.3）")
    reach = max(max(abs(b["from"][0]), abs(b["to"][0]), abs(b["from"][2]), abs(b["to"][2])) for b in boxes)
    if reach > 16:
        problems.append(f"占地半径 {reach:.2f} 超过 16 单位（一格）")
    for box in boxes:
        for face, (u0, v0, u1, v1) in box["uv"].items():
            if not (0 <= u0 <= u1 <= TILE and 0 <= v0 <= v1 <= TILE):
                problems.append(f"{box['chain']} 的 {face} 面 uv 出界：{(u0, v0, u1, v1)}")
    return problems


def check_faces(boxes):
    """面朝向自检：角序算出来的法线必须等于该面声明的法线（逆时针 = 外侧）。

    零面积的盒子（例如声明 dz=0 且 sizeAdd=0 的薄片）会给出零法线 —— 那是退化面，直接跳过。
    """
    problems = []
    for box in boxes:
        low, high = box["from"], box["to"]
        for face in FACES:
            corners = []
            for cx, cy, cz in FACE_CORNERS[face]:
                corners.append((low[0] if cx == 0 else high[0],
                                low[1] if cy == 0 else high[1],
                                low[2] if cz == 0 else high[2]))
            ax = [corners[1][i] - corners[0][i] for i in range(3)]
            bx = [corners[2][i] - corners[1][i] for i in range(3)]
            normal = (ax[1] * bx[2] - ax[2] * bx[1],
                      ax[2] * bx[0] - ax[0] * bx[2],
                      ax[0] * bx[1] - ax[1] * bx[0])
            length = sum(v * v for v in normal) ** 0.5
            if length < 1e-9:
                continue                                # 退化面（零面积）
            unit = tuple(v / length for v in normal)
            want = FACE_NORMALS[face]
            if max(abs(unit[i] - want[i]) for i in range(3)) > 1e-6:
                problems.append(f"{box['chain']} 的 {face} 面角序反了：算得 {unit}，应为 {want}")
    return problems


def render(boxes, meta, atlas, choice, path, scale=4.0, zoom=None):
    """软件渲染：正交三视图（前 / 左 / 3/4 俯视），cutout + 深度 + 背面剔除。

    够用来肉眼验两件事：几何朝向（脸/背有没有反）、贴图有没有接错层。
    `zoom` = (y0, y1)：只画这个高度区间（看头/脸时用）。
    """
    width, height = 260, 200
    views = ((0.0, 0.0, 0), (90.0, 0.0, width), (35.0, 18.0, width * 2))
    canvas = Image.new("RGBA", (width * len(views), height), (28, 28, 34, 255))
    floor = zoom[0] if zoom else 0.0
    layers = [meta["tiles"][index] for index in
              (meta["groups"]["skin"][choice[0] % len(meta["groups"]["skin"])],
               meta["groups"]["type"][choice[1] % len(meta["groups"]["type"])],
               meta["groups"][
                   "profession"][choice[2] % len(meta["groups"]["profession"])],
               meta["groups"]["level"][choice[3] % len(meta["groups"]["level"])])]

    def rotate(point, yaw, pitch):
        cos_y, sin_y = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
        cos_p, sin_p = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
        x, y, z = point
        x, z = x * cos_y - z * sin_y, x * sin_y + z * cos_y
        y, z = y * cos_p - z * sin_p, y * sin_p + z * cos_p
        return x, y, z

    for yaw, pitch, offset in views:
        depth = [1e30] * (width * height)
        pixels = canvas.load()
        centre_x, centre_y = offset + width / 2.0, height - 24.0
        for layer in layers:
            tile_x, tile_y = layer["x"], layer["y"]
            for box in boxes:
                low, high = box["from"], box["to"]
                for face in FACES:
                    world = []
                    for cx, cy, cz in FACE_CORNERS[face]:
                        world.append((low[0] if cx == 0 else high[0],
                                      low[1] if cy == 0 else high[1],
                                      low[2] if cz == 0 else high[2]))
                    normal = FACE_NORMALS[face]
                    rotated_normal = rotate(normal, yaw, pitch)
                    if rotated_normal[2] > -1e-6:
                        continue                    # 背面（相机沿 +z 看，z 越小越近）
                    projected = [rotate(point, yaw, pitch) for point in world]
                    screen = [(centre_x + p[0] * scale, centre_y - (p[1] - floor) * scale, p[2])
                              for p in projected]
                    u0, v0, u1, v1 = box["uv"][face]
                    uvs = [(u0, v0), (u0, v1), (u1, v1), (u1, v0)]
                    for triangle in ((0, 1, 2), (0, 2, 3)):
                        (x1, y1, z1), (x2, y2, z2), (x3, y3, z3) = (screen[i] for i in triangle)
                        area = (x2 - x1) * (y3 - y1) - (x3 - x1) * (y2 - y1)
                        if abs(area) < 1e-9:
                            continue
                        min_x = max(0, int(min(x1, x2, x3)))
                        max_x = min(width * len(views), int(max(x1, x2, x3)) + 1)
                        min_y = max(0, int(min(y1, y2, y3)))
                        max_y = min(height, int(max(y1, y2, y3)) + 1)
                        for py in range(min_y, max_y):
                            for px in range(min_x, max_x):
                                weights = []
                                inside = True
                                for i in range(3):
                                    ax, ay, _ = screen[triangle[i]]
                                    bx, by, _ = screen[triangle[(i + 1) % 3]]
                                    cx2, cy2, _ = screen[triangle[(i + 2) % 3]]
                                    det = (bx - ax) * (cy2 - ay) - (cx2 - ax) * (by - ay)
                                    if abs(det) < 1e-9:
                                        inside = False
                                        break
                                    weights.append(((bx - px) * (cy2 - py) - (cx2 - px) * (by - py)) / det)
                                if not inside or min(weights) < -1e-6 or max(weights) > 1 + 1e-6:
                                    continue
                                u = sum(weights[i] * uvs[triangle[i]][0] for i in range(3))
                                v = sum(weights[i] * uvs[triangle[i]][1] for i in range(3))
                                z = sum(weights[i] * screen[triangle[i]][2] for i in range(3))
                                local_x = px - offset
                                if not (0 <= local_x < width):
                                    continue
                                index = py * width + local_x
                                if z >= depth[index]:
                                    continue
                                sx = min(TILE - 1, max(0, int(u)))
                                sy = min(TILE - 1, max(0, int(v)))
                                colour = atlas.getpixel((tile_x + sx, tile_y + sy))
                                if colour[3] <= 8:
                                    continue                # cutout：丢弃的像素不写深度
                                depth[index] = z
                                pixels[px, py] = (colour[0], colour[1], colour[2], 255)
    canvas.save(path)
    return path


# ------------------------------------------------------------------- main

def payload(boxes, meta, atlas):
    """要写盘的三份内容（**按字节**比较，避免 CRLF 之类的假不一致）。"""
    mesh = {"scale": SCALE, "yOrigin": Y_ORIGIN, "tile": TILE, "faces": list(FACES), "boxes": boxes}
    buffer = io.BytesIO()
    atlas.save(buffer, format="PNG", optimize=True)
    return {
        "crowd_mesh.json": (json.dumps(mesh, ensure_ascii=False, indent=1, sort_keys=True) + "\n").encode("utf-8"),
        "crowd_tiles.json": (json.dumps(meta, ensure_ascii=False, indent=1, sort_keys=True) + "\n").encode("utf-8"),
        "crowd_atlas.png": buffer.getvalue(),
    }


def emit(out, boxes, meta, atlas):
    for key, data in payload(boxes, meta, atlas).items():
        path = output_paths(out)[{"crowd_mesh.json": "mesh",
                                  "crowd_tiles.json": "tiles",
                                  "crowd_atlas.png": "atlas"}[key]]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pack", type=Path, default=DEFAULT_PACK)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--preview", type=Path)
    parser.add_argument("--preview-choice", default="0,2,9,3",
                        help="预览用的 (底皮, 群系, 职业, 等级) 下标，逗号分隔")
    parser.add_argument("--preview-scale", type=float, default=4.0)
    parser.add_argument("--preview-zoom", default=None,
                        help="只画这个高度区间，例如 22,32（看头/脸）")
    args = parser.parse_args()

    archive = zipfile.ZipFile(args.pack)
    jem, entry = read_jem(archive)
    boxes = build_mesh(jem)
    groups = list_tiles(archive)
    atlas, meta = build_atlas(archive, groups)
    archive.close()

    problems = check_mesh(boxes) + check_faces(boxes)
    groups_note = " ｜ ".join(f"{k}={len(v)}" for k, v in groups.items())
    print(f"jem = {entry}")
    print(f"网格 = {len(boxes)} 盒 × 6 面（脚 y={min(b['from'][1] for b in boxes):.3f}，"
          f"顶 y={max(b['to'][1] for b in boxes):.3f}）")
    print(f"图集 = {atlas.width}×{atlas.height}（{len(meta['tiles'])} 块，{groups_note}）")

    args.out.mkdir(parents=True, exist_ok=True)
    files = payload(boxes, meta, atlas)
    paths = output_paths(args.out)
    mapping = {"crowd_mesh.json": paths["mesh"], "crowd_tiles.json": paths["tiles"],
               "crowd_atlas.png": paths["atlas"]}

    if args.check:
        problems = []
        for name, want in files.items():
            path = mapping[name]
            if not path.exists():
                problems.append(f"缺文件：{path}")
            elif path.read_bytes() != want:
                got = path.read_bytes()
                problems.append(f"与磁盘不一致：{name}（磁盘 {hashlib.md5(got).hexdigest()[:10]} / "
                                f"重算 {hashlib.md5(want).hexdigest()[:10]}）")
        print("--check：" + ("产物与磁盘一致" if not problems else f"{len(problems)} 处不一致"))
        for line in problems:
            print("★ " + line)
        return 1 if problems else 0

    emit(args.out, boxes, meta, atlas)
    for name, path in mapping.items():
        print(f"已写：{path}（{len(files[name])} 字节）")

    if args.preview:
        choice = tuple(int(v) for v in args.preview_choice.split(","))
        zoom = tuple(float(v) for v in args.preview_zoom.split(",")) if args.preview_zoom else None
        render(boxes, meta, atlas, choice, args.preview, args.preview_scale, zoom)
        print(f"预览：{args.preview}（档位 {choice}，scale={args.preview_scale}，zoom={zoom}）")

    for line in problems:
        print("★ " + line)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
