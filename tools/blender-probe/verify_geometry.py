"""四层几何验证 —— MTR/MMTR 车辆模型专用

设计原则（来自本项目实战教训）：
  · **"调用成功"不算验证通过。** 必须实测几何。
  · L3 连通分量是必须的：三个各自闭合的壳体，逐块看都水密，
    `join` / 布尔也会"不报错"，只有数连通分量才抓得出"其实是碎片"。
  · L4 截面必须用**三角形-平面求交**，不能用顶点分带：
    纯解析生成的方块只有 8 个顶点、全在两端，中间切片必然空（那是正确行为）。

用法（headless，不需要插件、不需要 GUI）：
    blender --background [file.blend] --python verify_geometry.py -- \
        --object <名称> [--expect-json <设计值.json>] [--json-out <报告.json>]

设计值 JSON（都可选，只校验给出的字段）：
{
  "width":  5.0,          # X 跨度（格）
  "height": 6.2987,       # Y 跨度
  "length": 31.0065,      # Z 跨度
  "tolerance": 0.001,
  "watertight": true,
  "single_component": true,
  "min_volume": 100.0,
  "axis": "z",            # L4 沿哪根轴切片，默认自动取最长轴
  "slices": 9
}

退出码：0 = 全 PASS；1 = 有 FAIL
"""
from __future__ import annotations

import json
import math
import sys
import traceback

import bmesh
import bpy
from mathutils import Vector

# ----------------------------------------------------------------------------
# 参数解析（Blender 用 `--` 分隔自己的参数与脚本参数）
# ----------------------------------------------------------------------------


def parse_args(argv: list[str]) -> dict:
    args = {"object": None, "expect_json": None, "json_out": None}
    if "--" in argv:
        argv = argv[argv.index("--") + 1:]
    else:
        argv = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--object" and i + 1 < len(argv):
            args["object"] = argv[i + 1]; i += 2
        elif a == "--expect-json" and i + 1 < len(argv):
            args["expect_json"] = argv[i + 1]; i += 2
        elif a == "--json-out" and i + 1 < len(argv):
            args["json_out"] = argv[i + 1]; i += 2
        else:
            i += 1
    return args


# ----------------------------------------------------------------------------
# L1 物体级
# ----------------------------------------------------------------------------


def layer1_world_bbox(ob) -> dict:
    xs = [(ob.matrix_world @ v.co).x for v in ob.data.vertices]
    ys = [(ob.matrix_world @ v.co).y for v in ob.data.vertices]
    zs = [(ob.matrix_world @ v.co).z for v in ob.data.vertices]
    return {
        "min": [min(xs), min(ys), min(zs)],
        "max": [max(xs), max(ys), max(zs)],
        "span": [max(xs) - min(xs), max(ys) - min(ys), max(zs) - min(zs)],
        "center": [(max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2, (max(zs) + min(zs)) / 2],
        "dimensions": [ob.dimensions.x, ob.dimensions.y, ob.dimensions.z],
        "location": list(ob.location),
        "scale": list(ob.scale),
        "rotation_euler": list(ob.rotation_euler),
        "modifiers": [m.name for m in ob.modifiers],
    }


# ----------------------------------------------------------------------------
# L3 拓扑级（水密 + 连通分量 + 体积）
# ----------------------------------------------------------------------------


def layer3_topology(me) -> dict:
    edge_faces: dict = {}
    for poly in me.polygons:
        for ek in poly.edge_keys:
            edge_faces[ek] = edge_faces.get(ek, 0) + 1
    non_manifold = sum(1 for c in edge_faces.values() if c != 2)
    boundary = sum(1 for c in edge_faces.values() if c == 1)

    # 并查集数连通分量
    parent = list(range(len(me.vertices)))

    def find(a: int) -> int:
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for e in me.edges:
        ra, rb = find(e.vertices[0]), find(e.vertices[1])
        if ra != rb:
            parent[ra] = rb
    groups: dict = {}
    for i in range(len(me.vertices)):
        groups.setdefault(find(i), []).append(i)
    comps = []
    for idxs in sorted(groups.values(), key=len, reverse=True):
        xs = [me.vertices[i].co.x for i in idxs]
        ys = [me.vertices[i].co.y for i in idxs]
        zs = [me.vertices[i].co.z for i in idxs]
        comps.append({
            "verts": len(idxs),
            "bbox": [[min(xs), min(ys), min(zs)], [max(xs), max(ys), max(zs)]],
        })

    bm = bmesh.new()
    bm.from_mesh(me)
    try:
        volume = abs(bm.calc_volume())
    except Exception:
        volume = None
    area = sum(f.calc_area() for f in bm.faces)
    bm.free()

    return {
        "verts": len(me.vertices), "edges": len(me.edges), "faces": len(me.polygons),
        "unique_edges": len(edge_faces),
        "non_manifold_edges": non_manifold,
        "boundary_edges": boundary,
        "watertight": non_manifold == 0,
        "components": len(comps),
        "component_detail": comps[:10],
        "volume": volume,
        "surface_area": area,
    }


# ----------------------------------------------------------------------------
# L4 特征级（三角-平面求交，任意形体都准）
# ----------------------------------------------------------------------------


def slice_profile(me, axis: str, value: float) -> dict | None:
    """axis=value 平面与网格的交点，在其余两轴上的范围。

    三角形级求交：对每条跨越 value 的边做线性插值。比顶点分带可靠，
    因为平面形体（长方体）的顶点只集中在两端。
    """
    idx = {"x": 0, "y": 1, "z": 2}[axis]
    pts = []
    for poly in me.polygons:
        vs = [me.vertices[i].co for i in poly.vertices]
        n = len(vs)
        for i in range(n):
            a, b = vs[i], vs[(i + 1) % n]
            av, bv = a[idx], b[idx]
            if av == bv:
                continue
            if (av - value) * (bv - value) <= 0:
                t = (value - av) / (bv - av)
                pts.append(a.lerp(b, t))
    if not pts:
        return None
    others = [k for k in ("x", "y", "z") if k != axis]
    out = {}
    for k in others:
        j = {"x": 0, "y": 1, "z": 2}[k]
        vals = [p[j] for p in pts]
        out[k] = [min(vals), max(vals), max(vals) - min(vals)]
    out["points"] = len(pts)
    return out


def layer4_sections(me, axis: str, nslices: int) -> dict:
    idx = {"x": 0, "y": 1, "z": 2}[axis]
    coords = [v.co[idx] for v in me.vertices]
    lo, hi = min(coords), max(coords)
    rows = []
    for k in range(nslices):
        value = lo + (hi - lo) * k / (nslices - 1)
        prof = slice_profile(me, axis, value)
        rows.append({"value": value, "profile": prof})
    return {"axis": axis, "range": [lo, hi], "slices": rows}


# ----------------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------------


def main() -> int:
    args = parse_args(list(sys.argv))

    if args["object"]:
        ob = bpy.data.objects.get(args["object"])
        if ob is None:
            print("找不到物体:", args["object"])
            print("场景里的网格物体:", [o.name for o in bpy.data.objects if o.type == "MESH"])
            return 1
    else:
        meshes = [o for o in bpy.data.objects if o.type == "MESH"]
        if not meshes:
            print("场景里没有网格物体")
            return 1
        ob = meshes[0]
        print("(未指定 --object，取第一个网格物体)")

    if ob.type != "MESH":
        print("物体不是网格:", ob.name, ob.type)
        return 1

    expect = {}
    if args["expect_json"]:
        with open(args["expect_json"], encoding="utf-8") as f:
            expect = json.load(f)
    tol = float(expect.get("tolerance", 1e-3))

    print("=" * 74)
    print("四层几何验证")
    print("=" * 74)
    print("文件:", bpy.data.filepath or "(未保存)")
    print("物体:", ob.name, "| 网格:", ob.data.name)
    print("=" * 74)

    me = ob.data
    l1 = layer1_world_bbox(ob)
    l3 = layer3_topology(me)
    axis = expect.get("axis")
    if not axis:
        spans = {"x": l1["span"][0], "y": l1["span"][1], "z": l1["span"][2]}
        axis = max(spans, key=spans.get)
    l4 = layer4_sections(me, axis, int(expect.get("slices", 9)))

    fails: list[str] = []

    # ---------------- L1 ----------------
    print()
    print("── L1 物体级 ──────────────────────────────────────────────")
    print("  世界包围盒 min (%.4f, %.4f, %.4f)" % tuple(l1["min"]))
    print("  世界包围盒 max (%.4f, %.4f, %.4f)" % tuple(l1["max"]))
    print("  跨度        X=%.4f  Y=%.4f  Z=%.4f" % tuple(l1["span"]))
    print("  几何中心    (%+.6f, %+.6f, %+.6f)" % tuple(l1["center"]))
    print("  变换        loc=%s scale=%s" % (
        ["%.3f" % v for v in l1["location"]], ["%.3f" % v for v in l1["scale"]]))
    print("  修改器      %s" % (l1["modifiers"] or "无"))
    for key, idx in (("width", 0), ("height", 1), ("length", 2)):
        if key in expect:
            got, want = l1["span"][idx], float(expect[key])
            mark = "OK" if abs(got - want) <= tol else "FAIL"
            print("  校验 %-7s 实测 %-10.5f 设计 %-10.5f  %s" % (key, got, want, mark))
            if mark == "FAIL":
                fails.append("L1 %s: 实测 %.5f != 设计 %.5f (差 %.5f)" % (
                    key, got, want, got - want))

    # ---------------- L2 ----------------
    print()
    print("── L2 顶点级 ──────────────────────────────────────────────")
    print("  顶点数 %d" % len(me.vertices))

    # ---------------- L3 ----------------
    print()
    print("── L3 拓扑级 ★ ────────────────────────────────────────────")
    print("  顶点 %d / 边 %d / 唯一边 %d / 面 %d" % (
        l3["verts"], l3["edges"], l3["unique_edges"], l3["faces"]))
    print("  非流形边 %d / 边界边 %d -> %s" % (
        l3["non_manifold_edges"], l3["boundary_edges"],
        "水密闭合" if l3["watertight"] else "有开口或非流形"))
    print("  ★ 连通部件数 = %d -> %s" % (
        l3["components"], "单一实体" if l3["components"] == 1 else "!! 多块碎片"))
    if l3["volume"] is not None:
        print("  体积 %.6f 立方格   表面积 %.6f 平方格" % (l3["volume"], l3["surface_area"]))
    for i, c in enumerate(l3["component_detail"]):
        if l3["components"] > 1:
            print("     部件%d: %d 顶点  bbox %s .. %s" % (
                i, c["verts"],
                ["%.2f" % v for v in c["bbox"][0]],
                ["%.2f" % v for v in c["bbox"][1]]))
    if expect.get("watertight", True) and not l3["watertight"]:
        fails.append("L3 非水密（非流形边 %d）" % l3["non_manifold_edges"])
    if expect.get("single_component", True) and l3["components"] != 1:
        fails.append("L3 连通部件数 %d != 1（是碎片！）" % l3["components"])
    if "min_volume" in expect and l3["volume"] is not None:
        if l3["volume"] < float(expect["min_volume"]):
            fails.append("L3 体积 %.4f < 下限 %.4f" % (l3["volume"], float(expect["min_volume"])))
            print("  校验体积 FAIL：%.4f < %.4f" % (l3["volume"], float(expect["min_volume"])))
        else:
            print("  校验体积 OK：%.4f >= %.4f" % (l3["volume"], float(expect["min_volume"])))

    # ---------------- L4 ----------------
    print()
    print("── L4 特征级 ★  沿 %s 轴切片（三角-平面求交） ──────────────" % axis.upper())
    hdr = {"x": ("y", "z"), "y": ("x", "z"), "z": ("x", "y")}[axis]
    print("  %-10s %-24s %-24s" % (axis, hdr[0] + " 范围/跨度", hdr[1] + " 范围/跨度"))
    for row in l4["slices"]:
        p = row["profile"]
        if p is None:
            print("  %-10.3f %s" % (row["value"], "（该平面与网格不相交 → 真的是空的）"))
        else:
            print("  %-10.3f [%+.3f,%+.3f] 跨度 %-8.4f [%+.3f,%+.3f] 跨度 %-8.4f" % (
                row["value"], p[hdr[0]][0], p[hdr[0]][1], p[hdr[0]][2],
                p[hdr[1]][0], p[hdr[1]][1], p[hdr[1]][2]))

    # ---------------- 判定 ----------------
    print()
    print("=" * 74)
    if fails:
        print("总判定: FAIL  (%d 项)" % len(fails))
        for f in fails:
            print("   -", f)
    else:
        print("总判定: PASS")
    print("=" * 74)

    report = {"object": ob.name, "file": bpy.data.filepath,
              "pass": not fails, "fails": fails,
              "L1": l1, "L3": l3, "L4": l4}
    if args["json_out"]:
        with open(args["json_out"], "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print("报告已写入:", args["json_out"])
    return 0 if not fails else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        traceback.print_exc()
        sys.exit(2)
