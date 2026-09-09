# -*- coding: utf-8 -*-
"""
把 Blender 里的 mmtr_* 空物体（Empty）导出成打包器能读的锚点文件。

用法：Blender → Scripting 标签 → 打开本脚本 → Run Script
（或命令行： blender -b 你的.blend -P dump_empties.py）

输出：与 .blend 同目录的 mmtr_anchors_extra.json（已换算成 OBJ 导出坐标系），
      打包器（pack_vehicle.js）会自动读取并合并进 mmtr_anchors_<id>.json。

约定：
  - 物体名以 mmtr_ 开头，如 mmtr_seat_1 / mmtr_ack_1 / mmtr_hud_2
  - 位置 = 空物体的世界坐标
  - 朝向 = 空物体的**本地 +Z 轴**（Blender 里那根蓝色箭头；旋转空物体即可改变朝向）
    · seat：+Z 指向列车行进方向
    · hud ：+Z 指向司机（和具名面法线一致）
"""
import bpy
import json
import os
from mathutils import Vector

def to_obj_space(v):
    """Blender(Forward -Z / Up Y) 导出 OBJ 的坐标换算： (x, y, z) -> (x, z, -y)"""
    return [v.x, v.z, -v.y]

target = os.path.join(os.path.dirname(bpy.data.filepath) or ".", "mmtr_anchors_extra.json")
anchors = []

for obj in bpy.data.objects:
    if obj.type != 'EMPTY' or not obj.name.startswith('mmtr_'):
        continue
    matrix = obj.matrix_world
    position = to_obj_space(matrix.translation)
    facing = to_obj_space((matrix.to_3x3() @ Vector((0.0, 0.0, 1.0))).normalized())
    anchors.append({
        "name": obj.name,
        "x": round(position[0], 5),
        "y": round(position[1], 5),
        "z": round(position[2], 5),
        "normal": [round(facing[0], 6), round(facing[1], 6), round(facing[2], 6)],
    })

with open(target, "w", encoding="utf-8") as handle:
    json.dump({"anchors": anchors}, handle, ensure_ascii=False, indent=1)

print("wrote %s (%d anchors)" % (target, len(anchors)))
