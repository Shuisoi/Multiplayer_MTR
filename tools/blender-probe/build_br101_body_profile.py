"""BR 101 体素模型 —— 第 2 步：雕刻车身轮廓（放样）

设计依据（BR 101 / DB Baureihe 101 的辨识特征）：
  1. 车体**侧壁近乎垂直** —— 现代电力机车不是锥形，这是它区别于老式车的关键
  2. 车头端面**上部后倾**（rake）—— 驾驶室顶到鼻尖有一道斜切，是最显眼的特征
  3. **车顶略微起拱**（crown），不是平顶
  4. 腰部有一道**折线**（车体最宽处），上收、下收
  5. 端部有**缓冲梁 + 排障器**

做法：按长度方向放样（loft）一系列横截面。
  横截面形状固定，只随站位改变「半宽」和「车顶高」——
  这样侧壁垂直、车头斜切、车顶拱形都能用同一套剖面 + 参数表达，
  比布尔/缩放可控得多，也保证是单一水密实体。

坐标系：X=车宽，Y=上，Z+ = 车尾（车头朝 -Z）。1 单位 = 1 米 = 1 格。
"""
from __future__ import annotations

import math
import os
import sys

import bpy
from mathutils import Vector

OUT_BLEND = sys.argv[-1] if sys.argv[-1].endswith(".blend") else None

# ============================================================================
# 参数区
# ============================================================================
NAME = "body"

# 沿 Z 的站位表（比例值，1.0 = 整车中心到端部；实际长度由 BODY_L 决定）
# 每个站位: (z_ratio, 半宽系数, 车顶高系数)
#   半宽系数 1.0 = BODY_W/2
#   车顶高系数 1.0 = 全高；端部降低 => 车头矮下去，形成鼻尖
STATIONS = [
    # z_ratio, 半宽系数, 车顶高系数
    # 端面**近乎垂直**：半宽几乎不收，只有顶部（车顶高系数）后倾，
    # 形成"平端面 + 斜切挡风玻璃"的机车脸——不是圆鼻。
    (1.000, 0.985, 0.845),  # 端面：全宽，顶后倾（rake）
    (0.986, 1.000, 0.935),  # 斜切过渡
    (0.966, 1.000, 1.000),  # 车顶到达全高
    (0.905, 1.000, 1.000),
    (0.860, 1.000, 1.000),
    (0.600, 1.000, 1.000),
    (0.300, 1.000, 1.000),
    (0.000, 1.000, 1.000),
    (-0.300, 1.000, 1.000),
    (-0.600, 1.000, 1.000),
    (-0.860, 1.000, 1.000),
    (-0.905, 1.000, 1.000),
    (-0.966, 1.000, 1.000),
    (-0.986, 1.000, 0.935),
    (-1.000, 0.985, 0.845),
]

# 横截面（在 X-Y 平面），从底部左侧开始逆时针。所有值都是"相对量"：
#   x 用半宽系数缩放；y 分两段——waist 以下保持绝对高度（保证底架/裙板不被压扁），
#   waist 以上按车顶高系数缩放（车头矮下去时只收上部，下部保持）
WAIST_Y = 2.35            # 腰线高度（格）——车体最宽处
# ★ 剖面要「利」不要「圆」：真车 BR 101 车顶近乎平、肩部转折紧。
#   之前把车顶拱到 6.30、肩部从 4.75 就开始收，结果整体像一条面包。
PROFILE = [
    (-1.000, 0.00),       # 底左
    ( 1.000, 0.00),       # 底右
    ( 1.000, 0.70),       # 裙板外缘（垂直段）
    ( 1.000, 1.60),
    ( 1.000, WAIST_Y),    # ★ 腰线（最宽）
    ( 1.000, 3.60),       # 腰线以上**完全垂直**（真车侧壁就是这样）
    ( 0.999, 4.90),
    ( 0.995, 5.55),       # 肩部起点——尽量高
    ( 0.975, 5.85),       # ★ 肩部转折压在这一小段里，做成"利角"
    ( 0.930, 6.05),
    ( 0.820, 6.22),
    ( 0.520, 6.30),       # 车顶翼缘
    ( 0.000, 6.32),       # 车顶中心（拱高仅 0.10，近乎平顶）
    (-0.520, 6.30),
    (-0.820, 6.22),
    (-0.930, 6.05),
    (-0.975, 5.85),
    (-0.995, 5.55),
    (-0.999, 4.90),
    (-1.000, 3.60),
    (-1.000, WAIST_Y),
    (-1.000, 1.60),
    (-1.000, 0.70),
]

# 整体尺度（沿用第 1 步的推导）
REAL_LEN_OVER_BUFFERS      = 19.100
REAL_WIDTH                 = 3.080
REAL_ROOF_ABOVE_RAIL       = 4.380
REAL_UNDERFRAME_ABOVE_RAIL = 0.500
BODY_W = 5.0
RAIL_TOP_Y = 0.0

SCALE = BODY_W / REAL_WIDTH
BODY_L = REAL_LEN_OVER_BUFFERS * SCALE
BODY_H = (REAL_ROOF_ABOVE_RAIL - REAL_UNDERFRAME_ABOVE_RAIL) * SCALE
BASE_Y0 = RAIL_TOP_Y + REAL_UNDERFRAME_ABOVE_RAIL * SCALE
ROOF_TOP = BODY_H                    # 剖面里车顶最高的 y（相对底架）
HALF_W = BODY_W / 2.0

print("=" * 74)
print("BR 101 车身轮廓放样")
print("=" * 74)
print("  缩放系数 %.4f  ->  长 %.4f 格  宽 %.1f 格  高 %.4f 格" % (SCALE, BODY_L, BODY_W, BODY_H))
print("  剖面顶点 %d 个，站位 %d 个 -> 面数约 %d" % (
    len(PROFILE), len(STATIONS), len(PROFILE) * (len(STATIONS) - 1) + 2))


def scaled_profile(half_w_coef: float, roof_coef: float):
    """把固定剖面按站位参数变换成实际 (x, y)。

    waist 以下：y 保持绝对值（底架/裙板不被压扁）
    waist 以上：y 从 WAIST_Y 起按 roof_coef 缩放
    """
    pts = []
    for xr, y in PROFILE:
        x = xr * half_w_coef * HALF_W
        if y <= WAIST_Y:
            yy = y
        else:
            yy = WAIST_Y + (y - WAIST_Y) * roof_coef
        pts.append((x, yy))
    return pts


# ============================================================================
# 放样生成
# ============================================================================
verts: list[tuple[float, float, float]] = []
rings: list[list[int]] = []

for z_ratio, hw_coef, roof_coef in STATIONS:
    z = z_ratio * BODY_L / 2.0
    ring = []
    for x, y in scaled_profile(hw_coef, roof_coef):
        ring.append(len(verts))
        verts.append((x, BASE_Y0 + y, z))
    rings.append(ring)

n = len(PROFILE)
faces: list[tuple[int, ...]] = []
for a in range(len(rings) - 1):
    r0, r1 = rings[a], rings[a + 1]
    for i in range(n):
        j = (i + 1) % n
        # 绕序：从外侧看逆时针
        faces.append((r0[i], r0[j], r1[j], r1[i]))

# 两端封口（扇形三角化，保证水密）
faces.append(tuple(reversed(rings[0])))
faces.append(tuple(rings[-1]))

print("  生成: 顶点 %d  面 %d" % (len(verts), len(faces)))

# ============================================================================
# 建网格
# ============================================================================
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.unit_settings.system = "METRIC"
scene.unit_settings.scale_length = 1.0

me = bpy.data.meshes.new(NAME + "_mesh")
me.from_pydata(verts, [], faces)
me.update()
me.validate()
# 端面是 ngon（23 边），Blender 会自动扇形三角化；这里显式三角化保证导出稳定
import bmesh
bm = bmesh.new()
bm.from_mesh(me)
bmesh.ops.triangulate(bm, faces=[f for f in bm.faces if len(f.verts) > 4])
bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
bm.to_mesh(me)
bm.free()
me.update()

ob = bpy.data.objects.new(NAME, me)
scene.collection.objects.link(ob)

mat = bpy.data.materials.new("BR101_Red")
nodes_ok = getattr(mat, "use_nodes", True)   # 5.0+ 已废弃，设了无作用
bsdf = mat.node_tree.nodes.get("Principled BSDF") if mat.node_tree else None
if bsdf:
    bsdf.inputs["Base Color"].default_value = (0.62, 0.055, 0.06, 1.0)
    bsdf.inputs["Metallic"].default_value = 0.15
    bsdf.inputs["Roughness"].default_value = 0.42
me.materials.append(mat)

ob["br101_param"] = {
    "scale": SCALE, "body_w": BODY_W, "body_l": BODY_L, "body_h": BODY_H,
    "base_y0": BASE_Y0, "base_y1": BASE_Y0 + BODY_H, "rail_top_y": RAIL_TOP_Y,
    "waist_y": BASE_Y0 + WAIST_Y,
    "real_len_over_buffers": REAL_LEN_OVER_BUFFERS, "real_width": REAL_WIDTH,
    "real_roof_above_rail": REAL_ROOF_ABOVE_RAIL,
    "real_underframe_above_rail": REAL_UNDERFRAME_ABOVE_RAIL,
}

bpy.ops.object.select_all(action="DESELECT")
ob.select_set(True)
bpy.context.view_layer.objects.active = ob

# 设计值：轮廓件的尺寸不再是简单长方体，只锁总长/总宽/车顶高
design = {
    "width": BODY_W,
    "tolerance": 0.02,
    "watertight": True,
    "single_component": True,
    "min_volume": BODY_W * BODY_H * BODY_L * 0.55,   # 收腰后体积约为外接盒的 0.6~0.75
    "axis": "z", "slices": 11,
}
dp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "br101_body_design.json")
with open(dp, "w", encoding="utf-8") as f:
    import json
    json.dump(design, f, ensure_ascii=False, indent=2)
print("设计值:", dp)

if OUT_BLEND:
    os.makedirs(os.path.dirname(OUT_BLEND), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=OUT_BLEND)
    print("已保存:", OUT_BLEND, "(%d 字节)" % os.path.getsize(OUT_BLEND))
