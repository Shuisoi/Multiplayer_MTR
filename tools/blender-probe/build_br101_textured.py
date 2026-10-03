"""BR 101 —— 第 3 步：加 UV + 色块图集贴图 + 材质，并导出 OBJ/MTL

为什么用「色块图集」而不是画细节贴图：
  · MTR 要求 assets/mtr/<id>/ 下至少有一张 PNG，且 MTL 必须引用它
  · 低多边形车身用色块分色（车顶深灰 / 腰线浅灰 / 车体红）已经足够辨识
  · UV 只需按面指向图集里的一小块，稳定、可被 packager 原样搬运

顺带把第 2 步的轮廓放样逻辑内联进来，这样一个脚本就能从零产出可打包的资产。
"""
from __future__ import annotations

import json
import math
import os
import struct
import sys
import zlib

import bmesh
import bpy
from mathutils import Vector

ASSET_DIR = r"C:\Users\30354\Desktop\Shuisoi DEV\MC\assets\models\blender\br101"
BLEND = os.path.join(ASSET_DIR, "br101.blend")
OBJ = os.path.join(ASSET_DIR, "br101.obj")
MTL = os.path.join(ASSET_DIR, "br101.mtl")
PNG = os.path.join(ASSET_DIR, "br101_diffuse.png")
ID = "br101"
NAME = "body"

# ============================================================================
# 参数（与第 2 步一致）
# ============================================================================
REAL_LEN_OVER_BUFFERS, REAL_WIDTH = 19.100, 3.080
REAL_ROOF_ABOVE_RAIL, REAL_UNDERFRAME_ABOVE_RAIL = 4.380, 0.500
BODY_W, RAIL_TOP_Y = 5.0, 0.0
SCALE = BODY_W / REAL_WIDTH
BODY_L = REAL_LEN_OVER_BUFFERS * SCALE
BODY_H = (REAL_ROOF_ABOVE_RAIL - REAL_UNDERFRAME_ABOVE_RAIL) * SCALE
BASE_Y0 = RAIL_TOP_Y + REAL_UNDERFRAME_ABOVE_RAIL * SCALE
HALF_W = BODY_W / 2.0

WAIST_Y = 2.35
PROFILE = [
    (-1.000, 0.00), (1.000, 0.00),
    (1.000, 0.70), (1.000, 1.60), (1.000, WAIST_Y),
    (1.000, 3.60), (0.999, 4.90), (0.995, 5.55),
    (0.975, 5.85), (0.930, 6.05), (0.820, 6.22), (0.520, 6.30), (0.000, 6.32),
    (-0.520, 6.30), (-0.820, 6.22), (-0.930, 6.05), (-0.975, 5.85),
    (-0.995, 5.55), (-0.999, 4.90), (-1.000, 3.60), (-1.000, WAIST_Y),
    (-1.000, 1.60), (-1.000, 0.70),
]
STATIONS = [
    (1.000, 0.985, 0.845), (0.986, 1.000, 0.935), (0.966, 1.000, 1.000),
    (0.905, 1.000, 1.000), (0.860, 1.000, 1.000), (0.600, 1.000, 1.000),
    (0.300, 1.000, 1.000), (0.000, 1.000, 1.000), (-0.300, 1.000, 1.000),
    (-0.600, 1.000, 1.000), (-0.860, 1.000, 1.000), (-0.905, 1.000, 1.000),
    (-0.966, 1.000, 1.000), (-0.986, 1.000, 0.935), (-1.000, 0.985, 0.845),
]

# 色块图集：4 格 × 1，每格 64×64。UV 按面高度指向对应格
ATLAS_W, ATLAS_H, CELL = 256, 64, 64
REGIONS = {
    "body":   (0, (0.62, 0.055, 0.06)),    # DB 交通红（RAL 2020 近似）
    "waist":  (1, (0.78, 0.10, 0.10)),     # 腰线浅一点
    "roof":   (2, (0.30, 0.31, 0.33)),     # 车顶深灰
    "under":  (3, (0.20, 0.20, 0.22)),     # 底架近黑
}

print("=" * 74)
print("BR 101 第 3 步：UV + 贴图 + 材质 + 导出")
print("=" * 74)
print("  长 %.4f  宽 %.1f  高 %.4f 格" % (BODY_L, BODY_W, BODY_H))


def scaled_profile(hw, rc):
    out = []
    for xr, y in PROFILE:
        x = xr * hw * HALF_W
        yy = y if y <= WAIST_Y else WAIST_Y + (y - WAIST_Y) * rc
        out.append((x, yy))
    return out


# ---- 生成几何 ----
verts, rings = [], []
for zr, hw, rc in STATIONS:
    z = zr * BODY_L / 2.0
    ring = []
    for x, y in scaled_profile(hw, rc):
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

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.unit_settings.system = "METRIC"
scene.unit_settings.scale_length = 1.0

me = bpy.data.meshes.new(NAME + "_mesh")
me.from_pydata(verts, [], faces)
me.update()
bm = bmesh.new()
bm.from_mesh(me)
bmesh.ops.triangulate(bm, faces=[f for f in bm.faces if len(f.verts) > 4])
bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
bm.to_mesh(me)
bm.free()
me.update()
ob = bpy.data.objects.new(NAME, me)
scene.collection.objects.link(ob)
print("  几何: 顶点 %d  面 %d" % (len(me.vertices), len(me.polygons)))

# ---- 写 PNG（纯 stdlib，避免依赖 PIL） ----
raw = bytearray()
for y in range(ATLAS_H):
    raw.append(0)  # PNG 每行的 filter 字节
    for x in range(ATLAS_W):
        cell = x // CELL
        col = (0.5, 0.5, 0.5)
        for k, (idx, rgb) in REGIONS.items():
            if idx == cell:
                col = rgb
                break
        raw += bytes(int(max(0.0, min(1.0, c)) * 255 + 0.5) for c in col)


def chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


png = (b"\x89PNG\r\n\x1a\n"
       + chunk(b"IHDR", struct.pack(">IIBBBBB", ATLAS_W, ATLAS_H, 8, 2, 0, 0, 0))
       + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
       + chunk(b"IEND", b""))
with open(PNG, "wb") as f:
    f.write(png)
print("  贴图: %s (%d 字节)" % (os.path.basename(PNG), len(png)))

# ---- UV：按面中心高度分到图集的某一格 ----
uv = me.uv_layers.new(name="UVMap")
INNER = 0.06          # 格内边距，避免采样串色
for poly in me.polygons:
    cz = sum(me.vertices[i].co.z for i in poly.vertices) / len(poly.vertices)
    y_local = cz - BASE_Y0
    if y_local <= 0.9:
        key = "under"
    elif y_local >= 5.55:
        key = "roof"
    elif abs(y_local - WAIST_Y) <= 0.45:
        key = "waist"
    else:
        key = "body"
    idx = REGIONS[key][0]
    # 该格在 0..1 纹理坐标里的范围
    u0 = (idx * CELL + CELL * INNER) / ATLAS_W
    u1 = ((idx + 1) * CELL - CELL * INNER) / ATLAS_W
    v0, v1 = INNER, 1.0 - INNER
    # 用面在 XZ 平面上的包围盒铺 UV（简单平面投影，色块图集不需要精细 UV）
    xs = [me.vertices[i].co.x for i in poly.vertices]
    zs = [me.vertices[i].co.z for i in poly.vertices]
    spanx = max(max(xs) - min(xs), 1e-6)
    spanz = max(max(zs) - min(zs), 1e-6)
    for li in poly.loop_indices:
        vi = me.loops[li].vertex_index
        co = me.vertices[vi].co
        u = u0 + (co.x - min(xs)) / spanx * (u1 - u0)
        v = v0 + (co.z - min(zs)) / spanz * (v1 - v0)
        uv.data[li].uv = (u, v)
print("  UV: 已写入 %d 个面（4 色块图集）" % len(me.polygons))

# ---- 材质 + 图像 ----
img = bpy.data.images.new("br101_diffuse", ATLAS_W, ATLAS_H, alpha=False)
img.pixels = [0.0] * (ATLAS_W * ATLAS_H * 4)
px = [0.0] * (ATLAS_W * ATLAS_H * 4)
for y in range(ATLAS_H):
    for x in range(ATLAS_W):
        cell = x // CELL
        col = (0.5, 0.5, 0.5)
        for k, (i2, rgb) in REGIONS.items():
            if i2 == cell:
                col = rgb
                break
        o = (y * ATLAS_W + x) * 4
        px[o:o + 4] = [col[0], col[1], col[2], 1.0]
img.pixels = px
img.filepath_raw = PNG
img.file_format = "PNG"
img.save()
print("  图像数据块已保存")

mat = bpy.data.materials.new("br101_mat")
nt = mat.node_tree
bsdf = nt.nodes.get("Principled BSDF")
tex = nt.nodes.new("ShaderNodeTexImage")
tex.image = img
tex.interpolation = "Closest"      # 色块图集必须最近邻，防串色
nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
bsdf.inputs["Metallic"].default_value = 0.12
bsdf.inputs["Roughness"].default_value = 0.42
me.materials.clear()
me.materials.append(mat)
print("  材质: %s -> %s" % (mat.name, img.name))

# ---- 预览相机 + 灯光 ----
def basis(az_deg, el_deg):
    az, el = math.radians(az_deg), math.radians(el_deg)
    f = Vector((-math.cos(el) * math.sin(az), -math.sin(el),
                -math.cos(el) * math.cos(az))).normalized()
    r = f.cross(Vector((0.0, 1.0, 0.0)))
    r = r.normalized() if r.length > 1e-9 else Vector((1.0, 0.0, 0.0))
    return f, r, r.cross(f).normalized()


corners = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
center = sum(corners, Vector()) / 8.0
cd = bpy.data.cameras.new("PreviewCam")
cd.type = "ORTHO"
cd.clip_end = 100000.0
cam = bpy.data.objects.new("PreviewCam", cd)
scene.collection.objects.link(cam)
f, r, u = basis(55.0, 18.0)
cam.location = center - f * 400.0
from mathutils import Matrix
cam.rotation_mode = "QUATERNION"
cam.rotation_quaternion = Matrix(((r.x, u.x, -f.x), (r.y, u.y, -f.y), (r.z, u.z, -f.z))).to_quaternion()
ar = 16.0 / 9.0
cd.ortho_scale = max(2 * max(abs((c - center).dot(r)) for c in corners),
                     2 * max(abs((c - center).dot(u)) for c in corners) * ar) * 1.12
scene.camera = cam
scene.render.resolution_x, scene.render.resolution_y = 1600, 900

ld = bpy.data.lights.new("Sun", type="SUN")
ld.energy = 3.0
lo = bpy.data.objects.new("Sun", ld)
lo.location = center + Vector((40.0, 70.0, 50.0))
scene.collection.objects.link(lo)

# 引擎切成 EEVEE 才能看到贴图颜色（Workbench 只认材质色，不认纹理节点的接法）
scene.render.engine = "BLENDER_EEVEE_NEXT" if "BLENDER_EEVEE_NEXT" in \
    [i.identifier for i in bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items] else "BLENDER_EEVEE"

# ---- 保存 ----
bpy.ops.wm.save_as_mainfile(filepath=BLEND)
print("  已保存: %s" % BLEND)

# ---- 写 MTL ----
with open(MTL, "w", encoding="utf-8") as f:
    f.write("# BR 101 body\n")
    f.write("newmtl br101_mat\n")
    f.write("Ka 0.200 0.200 0.200\nKd 1.000 1.000 1.000\nKs 0.100 0.100 0.100\n")
    f.write("d 1.0\nillum 2\n")
    f.write("map_Kd br101_diffuse.png\n")
print("  已写 MTL: %s" % MTL)

# ---- 导出 OBJ（带 UV、法线、材质） ----
bpy.ops.object.select_all(action="DESELECT")
ob.select_set(True)
bpy.context.view_layer.objects.active = ob
kwargs = dict(filepath=OBJ, export_selected_objects=True, export_uv=True,
              export_normals=True, export_materials=True, export_triangulated_mesh=False,
              forward_axis="NEGATIVE_Z", up_axis="Y")
try:
    bpy.ops.wm.obj_export(**kwargs)
except TypeError as exc:
    print("  obj_export 参数不匹配，退回最少参数:", exc)
    bpy.ops.wm.obj_export(filepath=OBJ, export_selected_objects=True)
print("  已导出 OBJ: %s (%d 字节)" % (OBJ, os.path.getsize(OBJ)))

# ---- 检查 OBJ 里的组名（packager 靠它做角色映射） ----
groups = []
with open(OBJ, encoding="utf-8") as f:
    head = []
    for i, line in enumerate(f):
        if i < 60 and (line.startswith("g ") or line.startswith("o ") or line.startswith("usemtl") or line.startswith("mtllib")):
            head.append(line.rstrip())
        if line.startswith("g ") or line.startswith("o "):
            groups.append(line.rstrip())
print("  OBJ 组/对象行:", groups[:6])
print("  OBJ 头部关键行:", head[:6])
print("  OBJ 是否引用贴图: ", "map_Kd" in open(MTL, encoding="utf-8").read())
