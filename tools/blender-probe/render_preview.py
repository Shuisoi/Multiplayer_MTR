"""BR 101 预览渲染（正交，手工取景，不依赖任何自动取景 API）

关键：正交相机的取景只由 ortho_scale + 相机朝向决定，与相机距离无关。
所以把相机放得足够远（远大于物体），再显式设 ortho_scale。
之前出错的根因就是误以为「距离控制取景」（那是透视相机才有的行为）。
"""
import os
import sys

import bpy
from mathutils import Vector

OUT_DIR = sys.argv[-1] if os.path.isdir(sys.argv[-1]) else os.path.dirname(bpy.data.filepath)
os.makedirs(OUT_DIR, exist_ok=True)

ob = bpy.data.objects["body"]
corners = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
center = sum(corners, Vector()) / 8.0
print("物体 %s  dimensions=(%.3f, %.3f, %.3f)" % (ob.name, *ob.dimensions))
print("中心 (%.3f, %.3f, %.3f)" % tuple(center))

scene = bpy.context.scene
scene.render.engine = "BLENDER_WORKBENCH"
scene.render.resolution_x, scene.render.resolution_y = 1400, 800
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "PNG"
sh = scene.display.shading
sh.light = "STUDIO"; sh.color_type = "MATERIAL"
sh.show_object_outline = True; sh.show_cavity = True; sh.show_shadows = False
scene.display.render_aa = "8"
AR = scene.render.resolution_x / scene.render.resolution_y

cd = bpy.data.cameras.new("C")
cd.type = "ORTHO"
cd.clip_start = 0.1
cd.clip_end = 1e6
cam = bpy.data.objects.new("C", cd)
scene.collection.objects.link(cam)
scene.camera = cam
cam.rotation_mode = "XYZ"

# 相机放在够远的位置（正交下距离不影响取景，只为避免近裁剪）
FAR = 500.0

JOBS = [
    # 名称,   方位角, 仰角,  视线起点方向
    ("3q",    55.0,  20.0),
    ("side",  90.0,   3.0),
    ("front",  0.0,   5.0),
    ("top",    0.0,  89.0),
]

import math

from mathutils import Matrix, Vector


def set_camera(cam, az_deg, el_deg, center, far):
    """显式用「右/上/前」三个正交基构造相机矩阵。

    为什么不用 `direction.to_track_quat("-Z", "Y")`：
    它只保证相机 -Z 对准目标、并尽量让相机 +Y 对齐世界 +Y，**滚转由它自行决定**，
    结果是长条物体的长轴会跑到屏幕竖直方向（本项目实测：side 视图里
    屏幕竖直方向的跨度 = 物体 Z 跨度 31 格，正是这个原因）。
    自己造基则完全可控。
    """
    az, el = math.radians(az_deg), math.radians(el_deg)
    f = Vector((-math.cos(el) * math.sin(az), -math.sin(el),
                -math.cos(el) * math.cos(az))).normalized()   # 视线方向
    right = f.cross(Vector((0.0, 1.0, 0.0)))                  # 世界 Y 为"上"参考
    if right.length < 1e-6:
        right = Vector((1.0, 0.0, 0.0))
    right.normalize()
    up = right.cross(f).normalized()

    cam.location = center - f * far
    # 相机本地轴：+X 屏幕右、+Y 屏幕上、+Z 相机后(= -f)
    m = Matrix((
        (right.x, up.x, -f.x),
        (right.y, up.y, -f.y),
        (right.z, up.z, -f.z),
    ))
    cam.rotation_mode = "QUATERNION"
    cam.rotation_quaternion = m.to_quaternion()
    # 必须立刻 update，否则 matrix_world 仍是上一轮的过期值
    # （本项目踩过：不 update 时"基校验"会假通过，因为读到的是上一轮自己设的值）
    bpy.context.view_layer.update()
    return f, right, up


JOBS = [
    ("3q",    55.0,  18.0),   # 3/4：长轴横过画面
    ("side",  90.0,   0.0),   # 正侧：车长横向、高度竖向
    ("front",  0.0,   0.0),   # 车头端面
    ("top",    0.0,  89.5),   # 俯视
]

for name, az_deg, el_deg in JOBS:
    f, right, up = set_camera(cam, az_deg, el_deg, center, FAR)
    bpy.context.view_layer.update()

    # 校验与投影一律用「我自己构造的基」，**不读 matrix_world**。
    # 原因（实测）：在同一段脚本里设完旋转后，view_layer.update() 并不保证
    # matrix_world 已刷新——曾读到上一轮迭代的值，导致"基校验"假通过、
    # 取景算错。自算基既确定又无状态依赖。
    # 注意命名（本项目在这里错过一次）：max|(c-center)·axis| 得到的是**半跨度**，
    # 画面全宽 = 2 × 半跨度。曾把它当"半跨度"又乘 2，导致 ortho_scale 大一倍、
    # 物体只占应有大小的一半。变量名一律用 span_* 表示**全跨度**。
    span_w = 2.0 * max(abs((c - center).dot(right)) for c in corners)
    span_h = 2.0 * max(abs((c - center).dot(up)) for c in corners)
    # 正交相机：ortho_scale = 画面宽度（世界单位）。
    # 同时要满足竖直方向：span_h <= ortho_scale / AR  =>  ortho_scale >= span_h * AR
    cd.ortho_scale = max(span_w, span_h * AR) * 1.10
    bpy.context.view_layer.update()

    path = os.path.join(OUT_DIR, "body_%s.png" % name)
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    print("%-6s | 全跨度 横=%.2f 竖=%.2f | ortho=%.2f | 占宽%.0f%% 占高%.0f%% -> %s" % (
        name, span_w, span_h, cd.ortho_scale,
        100.0 * span_w / cd.ortho_scale, 100.0 * span_h * AR / cd.ortho_scale,
        os.path.basename(path)))
