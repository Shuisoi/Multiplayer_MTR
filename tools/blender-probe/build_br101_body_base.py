"""BR 101 体素模型 —— 车体底座（长方体）

headless 直接生成 .blend，产出干净文件供 GUI 打开。
不依赖插件、不依赖 MCP。

坐标系：直接按 MTR 规范建 —— X=车宽，Y=上，Z+ = 车尾（车头朝 -Z）
        1 单位 = 1 米 = 1 格。这样导出时 rotationDegY = 0，比 SAF101 省一步。

数据来源与不确定性：
  已核实（多源一致）：
    长度（缓冲器间） 19.100 m   bahnstatistik.de / de.wikipedia / nl.wikipedia
    轴距（转向架内）  2.650 m   bahnstatistik.de / de.wikipedia
    轮径（新）        1.250 m
  未核实（德/中/荷/瑞典四语维基信息框 + DB 官方数据表均无此二字段）：
    车体宽度、车顶高度  -> 用 ESTIMATE，集中在 REAL_* 常量里，一改全改

本步范围：只做「车体底座」长方体。转向架/车门/内装/锚点留后续。
"""
from __future__ import annotations

import json
import math
import os
import sys

import bpy
from mathutils import Vector

# ============================================================================
# 第 0 层：真实车辆数据
# ============================================================================
REAL_LEN_OVER_BUFFERS      = 19.100   # m  已核实
REAL_BOGIE_WHEELBASE       = 2.650    # m  已核实
REAL_WHEEL_DIA             = 1.250    # m  已核实
REAL_WIDTH                 = 3.080    # m  ★ESTIMATE
REAL_ROOF_ABOVE_RAIL       = 4.380    # m  ★ESTIMATE
REAL_UNDERFRAME_ABOVE_RAIL = 0.500    # m  ★ESTIMATE

# ============================================================================
# 第 1 层：用户意图（唯一拍板处）
# ============================================================================
BODY_W = 5.0          # 格 —— 用户指定「车等比到 5m 宽」
RAIL_TOP_Y = 0.0      # 轨面在模型坐标里的 y

# ============================================================================
# 第 2 层：派生量（全部算出，不手填）
# ============================================================================
SCALE  = BODY_W / REAL_WIDTH
BODY_L = REAL_LEN_OVER_BUFFERS * SCALE
BODY_H = (REAL_ROOF_ABOVE_RAIL - REAL_UNDERFRAME_ABOVE_RAIL) * SCALE
BASE_Y0 = RAIL_TOP_Y + REAL_UNDERFRAME_ABOVE_RAIL * SCALE
BASE_Y1 = BASE_Y0 + BODY_H

NAME = "body"          # ★ MTR 角色名：所有不可动件合并成一个，就叫 body
OUT_BLEND = sys.argv[-1] if sys.argv[-1].endswith(".blend") else None

print("=" * 74)
print("BR 101 车体底座 —— 尺寸推导（无手填值）")
print("=" * 74)
print("  等比缩放系数 = %.4f  (%.3f m 宽 -> %.1f 格宽)" % (SCALE, REAL_WIDTH, BODY_W))
print("  车长  %.3f m x %.4f = %8.4f 格" % (REAL_LEN_OVER_BUFFERS, SCALE, BODY_L))
print("  车高  (%.3f - %.3f) m x %.4f = %8.4f 格" % (
    REAL_ROOF_ABOVE_RAIL, REAL_UNDERFRAME_ABOVE_RAIL, SCALE, BODY_H))
print("  长/宽 = %.2f   高/宽 = %.2f" % (BODY_L / BODY_W, BODY_H / BODY_W))
print("  y 范围: 车体底面 %.4f  ->  车顶 %.4f   (轨面 y=%.2f)" % (BASE_Y0, BASE_Y1, RAIL_TOP_Y))
print("=" * 74)

# ============================================================================
# 全新空场景（不要默认立方体/灯/相机）
# ============================================================================
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.unit_settings.system = "METRIC"
scene.unit_settings.scale_length = 1.0
scene.unit_settings.length_unit = "METERS"

# ============================================================================
# 解析生成：8 个顶点 + 6 个面（不用 bpy.ops 堆算子，保证单一水密实体）
# ============================================================================
hx, hz = BODY_W / 2.0, BODY_L / 2.0
y0, y1 = BASE_Y0, BASE_Y1

verts = [
    (-hx, y0, -hz), ( hx, y0, -hz), ( hx, y0,  hz), (-hx, y0,  hz),   # 0-3 底
    (-hx, y1, -hz), ( hx, y1, -hz), ( hx, y1,  hz), (-hx, y1,  hz),   # 4-7 顶
]
# 绕序：从外侧看逆时针 => 法线朝外（Blender 约定）
faces = [
    (0, 3, 2, 1),   # 底  -Y
    (4, 5, 6, 7),   # 顶  +Y
    (0, 1, 5, 4),   # 车头端 -Z
    (2, 3, 7, 6),   # 车尾端 +Z
    (1, 2, 6, 5),   # +X 右侧
    (3, 0, 4, 7),   # -X 左侧
]

me = bpy.data.meshes.new(NAME + "_mesh")
me.from_pydata(verts, [], faces)
me.update()      # 必须：重算法线
me.validate()    # 必须：捕获退化几何

ob = bpy.data.objects.new(NAME, me)
scene.collection.objects.link(ob)

# 材质：DB 交通红（RAL 2020 近似）
mat = bpy.data.materials.new("BR101_Red")
mat.use_nodes = True
bsdf = mat.node_tree.nodes.get("Principled BSDF")
if bsdf:
    bsdf.inputs["Base Color"].default_value = (0.62, 0.055, 0.06, 1.0)
    bsdf.inputs["Metallic"].default_value = 0.15
    bsdf.inputs["Roughness"].default_value = 0.45
me.materials.append(mat)

bpy.ops.object.select_all(action="DESELECT")
ob.select_set(True)
bpy.context.view_layer.objects.active = ob

# 参数写进物体自定义属性，后续件直接读，保证同源
ob["br101_param"] = {
    "scale": SCALE, "body_w": BODY_W, "body_l": BODY_L, "body_h": BODY_H,
    "base_y0": BASE_Y0, "base_y1": BASE_Y1, "rail_top_y": RAIL_TOP_Y,
    "real_len_over_buffers": REAL_LEN_OVER_BUFFERS,
    "real_bogie_wheelbase": REAL_BOGIE_WHEELBASE,
    "real_wheel_dia": REAL_WHEEL_DIA,
    "real_width": REAL_WIDTH, "real_roof_above_rail": REAL_ROOF_ABOVE_RAIL,
    "real_underframe_above_rail": REAL_UNDERFRAME_ABOVE_RAIL,
}
print("参数已写入 obj['br101_param']")

# ============================================================================
# 设计值 JSON —— 供 verify_geometry.py 做 L1/L3 的实测-设计对照
# ============================================================================
design = {
    "width": BODY_W, "height": BODY_H, "length": BODY_L,
    "tolerance": 0.0005,
    "watertight": True, "single_component": True,
    "min_volume": BODY_W * BODY_H * BODY_L * 0.99,
    "axis": "z", "slices": 9,
}
design_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "br101_body_design.json")
with open(design_path, "w", encoding="utf-8") as f:
    json.dump(design, f, ensure_ascii=False, indent=2)
print("设计值写入:", design_path)

# ============================================================================
# 保存
# ============================================================================
if OUT_BLEND:
    os.makedirs(os.path.dirname(OUT_BLEND), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=OUT_BLEND)
    print("已保存:", OUT_BLEND, "(%d 字节)" % os.path.getsize(OUT_BLEND))
else:
    print("(未给输出路径，未保存)")
