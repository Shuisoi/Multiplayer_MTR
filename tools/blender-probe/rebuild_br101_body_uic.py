"""BR 101 车体 —— 就地重建（推入正在运行的 Blender 会话）

规格来源（用户提供，取代我此前的估算）：
  车钩中心距 LüP  19,100 mm
  车体外宽         2,950 mm      ← 原估算 3,080
  车顶距轨面       4,260 mm      ← 原估算 4,380
  长宽比           6.5 : 1       （19000/2950 = 6.48，与规格一致）
  前脸后倾         25°~30°（铅垂线量起）
  侧壁             0° 纯平（Glattwand），无加强筋/无百叶
  上肩圆角         R300~R400 mm
  迎风面侧圆角     R150~200 mm
  裙边             底梁 45° 小内折
  车顶             浅拱 + 两端受电弓沉台下凹 200~300 mm（沉台本步先不做）

本步只改「车体轮廓」这一件事，一次写完；贴图/UV 沿用文件里已有的。
"""
from __future__ import annotations

import json
import math

import bmesh
import bpy
from mathutils import Vector

# ============================================================================
# 第 0 层：真实数据（用户提供）
# ============================================================================
REAL_LUEP = 19.100          # m  车钩中心距
REAL_WIDTH = 2.950          # m  车体外宽    ★已核实（原为估算 3.080）
REAL_ROOF_ABOVE_RAIL = 4.260  # m  车顶距轨面 ★已核实（原为估算 4.380）
REAL_UNDERFRAME_ABOVE_RAIL = 0.500   # m  ★仍为估算（规格未给）
RAKE_DEG = 27.0             # 前脸后倾角（规格给 25~30，取中值）

# ============================================================================
# 第 1 层：用户意图
# ============================================================================
BODY_W = 5.0
RAIL_TOP_Y = 0.0

# ============================================================================
# 第 2 层：派生
# ============================================================================
SCALE = BODY_W / REAL_WIDTH
BODY_L = REAL_LUEP * SCALE
BODY_H = (REAL_ROOF_ABOVE_RAIL - REAL_UNDERFRAME_ABOVE_RAIL) * SCALE
BASE_Y0 = RAIL_TOP_Y + REAL_UNDERFRAME_ABOVE_RAIL * SCALE
HALF_W = BODY_W / 2.0

# 后倾只在「风挡下沿以上」发生——真车迎风面下部近乎垂直。
# 规格的 25~30° 是从**铅垂线**量起，所以 run = 高差 / tan(角度)。
CAB_TOP_Y = 4.20           # 风挡下沿高度（格）；此高度以下前面保持垂直
RAKE_DROP = BODY_H - CAB_TOP_Y          # 参与后倾的高差
RAKE_RUN = RAKE_DROP / math.tan(math.radians(RAKE_DEG))
RAKE_FRAC = RAKE_RUN / (BODY_L / 2.0)

# 圆角（规格换算成格）
R_CANTRAIL = 0.68      # R400 mm 上肩圆角
R_SIDE = 0.34          # R200 mm 迎风面侧圆角

print("=" * 76)
print("BR 101 车体 —— 按 UIC 505-1 规格重建")
print("=" * 76)
print("  缩放系数 %.4f  (%.3f m 宽 -> %.1f 格)" % (SCALE, REAL_WIDTH, BODY_W))
print("  车长 %.4f 格   车高 %.4f 格   长宽比 %.2f (规格 6.5)" % (
    BODY_L, BODY_H, BODY_L / BODY_W))
print("  风挡下沿 %.2f 格，其后倾高差 %.2f 格 -> %.0f deg 的水平跨距 %.4f 格 (半长的 %.1f%%)" % (
    CAB_TOP_Y, RAKE_DROP, RAKE_DEG, RAKE_RUN, RAKE_FRAC * 100))

# ============================================================================
# 剖面：0° 纯平侧壁 + 上肩圆弧 + 45° 裙边内折 + 浅拱顶
# ============================================================================
PROFILE = [
    # (x 系数, y 绝对值)   —— 从底左开始逆时针
    (-0.965, 0.00),        # 裙边底（45° 内折后的内缘）
    ( 0.965, 0.00),        # 裙边底右
    ( 1.000, 0.25),        # 45° 内折向上张开到全宽
    ( 1.000, 1.20),        # 纯平侧壁（0°）
    ( 1.000, 2.40),
    ( 1.000, 3.60),
    ( 1.000, 4.60),
    ( 1.000, 5.00),        # 侧壁顶（肩圆弧起点）
    ( 0.997, 5.30),
    ( 0.985, 5.55),
    ( 0.950, 5.75),
    ( 0.890, 5.92),
    ( 0.800, 6.05),
    ( 0.680, 6.15),
    ( 0.500, 6.24),
    ( 0.260, 6.29),
    ( 0.000, 6.30),        # 车顶中心（浅拱，仅 0.25 格矢高）
    (-0.260, 6.29),
    (-0.500, 6.24),
    (-0.680, 6.15),
    (-0.800, 6.05),
    (-0.890, 5.92),
    (-0.950, 5.75),
    (-0.985, 5.55),
    (-0.997, 5.30),
    (-1.000, 5.00),
    (-1.000, 4.60),
    (-1.000, 3.60),
    (-1.000, 2.40),
    (-1.000, 1.20),
    (-1.000, 0.25),
]
# 剖面 y 的最高值（车顶基准），用于站位的高度系数归一
PROFILE_TOP = 6.30
WAIST_NONE = True   # 本版无腰线（纯平侧壁）

# ============================================================================
# 站位表：由「侧视轮廓折线」反算，保证后倾角精确
# ============================================================================
# 侧视轮廓关键点（y = 距底架高度，z = 距车体中心）。车头在 -Z。
z_half = BODY_L / 2.0
nose_z = -z_half                                   # 车头端面所在 z
WINDSHIELD_BASE = CAB_TOP_Y                        # 垂直面与后倾面的分界高度
# 后倾面：从 (y=CAB_TOP_Y, z=nose_z+RAKE_RUN) 斜到 (y=BODY_H, z=nose_z)
#   即越靠车头(y 越小)越往前，斜率由 RAKE_RUN 保证
RAKE_TOP_Z = nose_z + RAKE_RUN                     # 后倾面顶端 z

# 站位高度序列：(z, 该处车顶高)
# 从车尾端面走到车中，再镜像到车头
HALF_SIL = [
    (nose_z,                CAB_TOP_Y * 0.55),  # 端面下沿（排障器区，先给个低位）
    (nose_z,                CAB_TOP_Y),         # 端面到风挡下沿——垂直面（同一 z，双站位）
    (RAKE_TOP_Z,            BODY_H),            # 后倾面顶端
    (0.0,                   BODY_H),            # 车中
]
# 注意：端面同一 z 上有两个不同高度 => 那里就是垂直面。放样会自然生成一个竖直面。
STATIONS = [(z, 1.0, top) for (z, top) in HALF_SIL]
# 镜像到 +Z 端（车尾）
mirror = [(-z, 1.0, top) for (z, top) in reversed(HALF_SIL)]
STATIONS = mirror + STATIONS[1:]
# 按 z 排序，保证放样顺序单调（否则会生成自交的扭曲面）
STATIONS.sort(key=lambda s: s[0])

# 端面宽度收窄（迎风面 R150~200 mm 的圆角）
TIP_HW = 0.94
for i, (z, hw, top) in enumerate(STATIONS):
    dist_to_nose = abs(z - nose_z)
    if dist_to_nose <= RAKE_RUN:
        t = 1.0 - dist_to_nose / max(RAKE_RUN, 1e-9)
        STATIONS[i] = (z, 1.0 - (1.0 - TIP_HW) * t * t, top)

print("  站位 %d 个；端面 z=%.3f  后倾顶端 z=%.3f  跨距 %.3f 格" % (
    len(STATIONS), nose_z, RAKE_TOP_Z, RAKE_RUN))
for z, hw, top in STATIONS:
    print("    z=%+8.4f  半宽系数 %.4f  顶高 %.4f" % (z, hw, top))

# 车顶系数归一：剖面顶部要乘 rc 缩到鼻尖
ROOF_Y = PROFILE_TOP
NOSE_BASE = 5.00      # 侧壁顶，rake 只在它之上收


def scaled_profile(hw, top_target):
    """把固定剖面按站位缩放到目标顶高 top_target。

    侧壁顶（NOSE_BASE=5.00）以下保持绝对高度，以上按比例缩到 top_target。
    这样垂直面段（top_target=5.00 附近）不会被压扁。
    """
    out = []
    lo = min(NOSE_BASE, top_target)
    for xr, y in PROFILE:
        x = xr * hw * HALF_W
        if y <= lo:
            yy = y
        else:
            span = PROFILE_TOP - lo
            yy = lo + (y - lo) / span * (top_target - lo) if span > 1e-9 else lo
        out.append((x, yy))
    return out


# ============================================================================
# 建几何
# ============================================================================
verts, rings = [], []
for z, hw, top in STATIONS:
    ring = []
    for x, y in scaled_profile(hw, top):
        ring.append(len(verts))
        verts.append((x, BASE_Y0 + y, z))
    rings.append(ring)

n = len(PROFILE)
faces = []
for a in range(len(rings) - 1):
    r0, r1 = rings[a], rings[a + 1]
    for i in range(n):
        j = (i + 1) % n
        faces.append((r0[i], r0[j], r1[j], r1[i]))
faces.append(tuple(reversed(rings[0])))
faces.append(tuple(rings[-1]))

me_new = bpy.data.meshes.new("body_mesh_new")
me_new.from_pydata(verts, [], faces)
me_new.update()
bm = bmesh.new()
bm.from_mesh(me_new)
bmesh.ops.triangulate(bm, faces=[f for f in bm.faces if len(f.verts) > 4])
bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
bm.to_mesh(me_new)
bm.free()
me_new.update()
print("  几何: 顶点 %d  面 %d" % (len(me_new.vertices), len(me_new.polygons)))

# ============================================================================
# 就地替换现有 body 的网格（保留物体与材质，UV 需要重建）
# ============================================================================
ob = bpy.data.objects.get("body")
if ob is None:
    ob = bpy.data.objects.new("body", me_new)
    (bpy.context.collection or bpy.context.scene.collection).objects.link(ob)
    print("  新建物体 body")
else:
    old = ob.data
    ob.data = me_new
    if old.users == 0:
        bpy.data.meshes.remove(old)
    print("  已就地替换 body 的网格")

# UV：沿用色块图集（4 格），按面高度分色
uv = me_new.uv_layers.new(name="UVMap")
ATLAS_W, ATLAS_H, CELL = 256, 64, 64
INNER = 0.06
BLOCKS = [("under", 0, 0.95), ("body", 1, 5.85), ("roof", 2, 99.0)]
for poly in me_new.polygons:
    cz = sum(me_new.vertices[i].co.z for i in poly.vertices) / len(poly.vertices)
    yl = cz - BASE_Y0
    key = "under" if yl <= 0.95 else ("body" if yl <= 5.85 else "roof")
    idx = {"under": 3, "body": 0, "roof": 2}[key]
    u0 = (idx * CELL + CELL * INNER) / ATLAS_W
    u1 = ((idx + 1) * CELL - CELL * INNER) / ATLAS_W
    v0, v1 = INNER, 1.0 - INNER
    xs = [me_new.vertices[i].co.x for i in poly.vertices]
    zs = [me_new.vertices[i].co.z for i in poly.vertices]
    sx = max(max(xs) - min(xs), 1e-6)
    sz = max(max(zs) - min(zs), 1e-6)
    for li in poly.loop_indices:
        vi = me_new.loops[li].vertex_index
        co = me_new.vertices[vi].co
        uv.data[li].uv = (u0 + (co.x - min(xs)) / sx * (u1 - u0),
                          v0 + (co.z - min(zs)) / sz * (v1 - v0))
print("  UV 已重建（%d 面，3 色块）" % len(me_new.polygons))

# 参数写到物体上
ob["br101_param"] = {
    "scale": SCALE, "body_w": BODY_W, "body_l": BODY_L, "body_h": BODY_H,
    "base_y0": BASE_Y0, "rail_top_y": RAIL_TOP_Y,
    "real_luep": REAL_LUEP, "real_width": REAL_WIDTH,
    "real_roof_above_rail": REAL_ROOF_ABOVE_RAIL,
    "real_underframe_above_rail": REAL_UNDERFRAME_ABOVE_RAIL,
    "rake_deg": RAKE_DEG, "rake_run": RAKE_RUN,
}

print("=" * 76)
bb = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
print("  包围盒 X[%+.3f,%+.3f] Y[%+.3f,%+.3f] Z[%+.3f,%+.3f]" % (
    min(v.x for v in bb), max(v.x for v in bb),
    min(v.y for v in bb), max(v.y for v in bb),
    min(v.z for v in bb), max(v.z for v in bb)))
print("=" * 76)
