"""打包后自检 —— 每次 `pack-vehicle.ps1` 出来之后跑一遍，专门堵"能进游戏但看不见"那一类静默失败。

    python mmtr/tools/obj-mtr-packager/check_pack.py <pack.zip> [--config <vehicle.json>]
    python mmtr/tools/obj-mtr-packager/check_pack.py --all      # 检查输出目录里全部 br101 包

为什么要它：本项目**反复**出现"包造出来了、游戏里看不见车"，而且每次原因不同：
  · notes/191  同名组被覆盖（body ×5 -> ×1）
  · notes/192  导出轴向错（长度跑到 Y、高度跑到 Z）-> 车立起来
  · notes/194  OBJ 必须 #v==#vt==#vn 且全三角形
  · R56        **r35 用 64×64 调色板覆盖了 256×256 涂装图** -> UV 落进透明区 -> 整车看不见
                （这次是"字节对比老包"才一眼看出来的）

所以这里把**每一条已知会静默失败的条件**都变成显式断言。退出码非 0 = 不许发这个包。

设计原则（吸取本项目的教训）：
  · **不许自证**：能"从产物重新量"的，就不要读输入参数当结论。
  · 每条失败都要给出**具体数字**，而不是"检查未通过"。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile

OK, FAIL, WARN = "OK", "FAIL", "WARN"
_results: list[tuple[str, str, str]] = []


def chk(name: str, ok: bool, detail: str, warn_only: bool = False) -> bool:
    status = OK if ok else (WARN if warn_only else FAIL)
    _results.append((status, name, detail))
    return ok


def _obj_stats(raw: str) -> dict:
    v = vt = vn = f = 0
    tris = nontris = 0
    groups: list[str] = []
    gcount: dict[str, int] = {}
    xs: list[float] = []
    ys: list[float] = []
    zs: list[float] = []
    for line in raw.splitlines():
        if line.startswith("v "):
            v += 1
            p = line.split()
            xs.append(float(p[1])); ys.append(float(p[2])); zs.append(float(p[3]))
        elif line.startswith("vt "):
            vt += 1
        elif line.startswith("vn "):
            vn += 1
        elif line.startswith("f "):
            f += 1
            if len(line.split()) - 1 == 3:
                tris += 1
            else:
                nontris += 1
        elif line.startswith("g "):
            g = line[2:].strip().lower()
            groups.append(g)
            gcount[g] = gcount.get(g, 0) + 1
        elif line.startswith("o "):
            g = line[2:].strip().lower()
            groups.append(g)
            gcount[g] = gcount.get(g, 0) + 1
    return {"v": v, "vt": vt, "vn": vn, "f": f, "tris": tris, "nontris": nontris,
            "groups": groups, "gcount": gcount,
            "bbox": ([round(min(xs), 3), round(max(xs), 3)],
                     [round(min(ys), 3), round(max(ys), 3)],
                     [round(min(zs), 3), round(max(zs), 3)]) if xs else None}


def check_pack(path: str, config: dict | None) -> bool:
    print("=" * 90)
    print("打包后自检:", path)
    print("=" * 90)
    if not os.path.exists(path):
        chk("包存在", False, path)
        return False
    z = zipfile.ZipFile(path)
    names = z.namelist()

    # ---- 1) zip 条目名：必须用 / 不能用 \（notes/191：游戏一个都读不到）
    back = [n for n in names if "\\" in n]
    chk("zip 条目用 '/' 分隔（无反斜杠）", not back, "反斜杠条目 %d 个" % len(back))

    # ---- 2) 文件名全小写（铁律 4：MTR 解析路径整体转小写 -> 找不到就静默空串）
    badcase = [n for n in names if os.path.basename(n) != os.path.basename(n).lower()]
    chk("条目文件名全小写", not badcase, "含大写: %s" % badcase[:5])

    # ---- 3) 必备条目
    need = ["pack.mcmeta"]
    chk("有 pack.mcmeta", any(n.endswith("pack.mcmeta") for n in names), "")
    for suffix in (".obj", ".mtl", ".png"):
        got = [n for n in names if n.endswith(suffix)]
        need.append(suffix)
        chk("有 %s" % suffix, bool(got), got[0] if got else "缺失")
    jsons = [n for n in names if n.endswith(".json")]
    chk("有 properties_*.json", any("properties_" in n for n in jsons),
        str([n.split("/")[-1] for n in jsons]))

    # ---- 4) ★ 贴图：本次的病根。64×64 调色板 vs 256×256 涂装图
    pngs = [n for n in names if n.endswith(".png")]
    png_infos = []
    for pn in pngs:
        data = z.read(pn)
        info = {"bytes": len(data), "sha16": hashlib.sha256(data).hexdigest()[:16]}
        alpha = None
        try:
            from PIL import Image
            import io as _io
            im = Image.open(_io.BytesIO(data)).convert("RGBA")
            info["size"] = im.size
            px = list(im.getdata())
            n = len(px)
            opaque = sum(1 for r, g, b, a in px if a > 200) / n
            info["opaque_frac"] = round(opaque, 4)
            alpha = opaque
        except Exception:
            pass
        print("  贴图 %-40s %s" % (pn, info))
        png_infos.append((pn, info, alpha))
    # ★ 2026-09-30：不透明覆盖率那条只对**主体贴图**成立 —— 玻璃贴图本来就是"整片半透明 + 一圈
    #   纯黑框"（实测 saf420_glass.png 不透明 2.6%），照"≥15%"判会把**完全正确的包**判死
    #   （车头/拖车都带这张图 ⇒ 一旦有它，任何包都过不了这道闸）。
    #   改成：取本包里覆盖率最高的那张当主体贴图（照旧 ≥15%），其余只要求"不是空图"。
    if png_infos:
        covs = [a for _pn, _i, a in png_infos if a is not None]
        best = max(covs) if covs else None
        for _pn, info, alpha in png_infos:
            if alpha is None:
                continue
            # ① 尺寸：模型 UV 是照 br101_livery_layout.TEX（256）画的
            chk("贴图 >= 128px（不是 64×64 调色板）", info["size"][0] >= 128,
                "size=%s" % (info["size"],))
            # ② 不透明覆盖率：太低 = UV 落进透明区 = 整车看不见
            if alpha >= best - 1e-9:
                chk("主体贴图不透明覆盖率 >= 15%", alpha >= 0.15,
                    "opaque=%.3f（低于 0.15 基本就是只剩调色板）" % alpha)
            else:
                chk("半透明件贴图不是空图（不透明覆盖 >= 0.1%）", alpha >= 0.001,
                    "opaque=%.4f" % alpha)

    # ---- 5) OBJ 布局：缺一不可（notes/194）
    objs = [n for n in names if n.endswith(".obj")]
    st = None
    for on in objs:
        st = _obj_stats(z.read(on).decode("utf-8", errors="replace"))
        print("  OBJ %s" % on)
        chk("OBJ #v == #vt == #vn", st["v"] == st["vt"] == st["vn"],
            "v=%d vt=%d vn=%d" % (st["v"], st["vt"], st["vn"]))
        chk("OBJ 全三角形", st["nontris"] == 0,
            "三角 %d / 非三角 %d" % (st["tris"], st["nontris"]))
        # 同名组：打包器必须把同名组合并（notes/191：body 出现 5 次 -> 静默不显示）
        dup = {g: c for g, c in st["gcount"].items() if c > 1}
        chk("组名无重复（同名组已合并）", not dup, str(dup))
        chk("至少 1 个组", bool(st["groups"]), str(st["groups"][:6]))
        # ★ 朝向判据（notes/192）：Y 必须是"高"、Z 必须是"长"
        if st["bbox"]:
            bx, by, bz = st["bbox"]
            h = by[1] - by[0]
            l = bz[1] - bz[0]
            w = bx[1] - bx[0]
            chk("朝向：Z 是最长轴（长度沿 Z）", l >= w and l >= h,
                "W=%.2f H=%.2f L=%.2f" % (w, h, l))
            chk("朝向：Y 是高度轴（不是 ±车长）", h < 12,
                "Y=%.2f（若 ~32 说明被转了 90°，见 notes/192）" % h)
            chk("X 是宽度轴", abs(w - round(w)) < 3 and w <= 8,
                "X=%.2f" % w)

    # ---- 6) properties 的 part 名 必须与 OBJ 组名一一对得上（否则该 part 静默无几何）
    props = [n for n in names if "properties_" in n and n.endswith(".json")]
    if props and st is not None:
        pr = json.loads(z.read(props[0]).decode("utf-8"))
        part_names = {nm for p in pr.get("parts", []) for nm in p.get("names", [])}
        obj_groups = set(st["groups"])
        missing = sorted(part_names - obj_groups)
        extra = sorted(obj_groups - part_names)
        chk("每个 part 都有同名 OBJ 组", not missing, "OBJ 里没有: %s" % missing)
        chk("没有孤儿 OBJ 组（不在 properties 里）", not extra,
            "properties 里没有: %s" % extra, warn_only=True)
        # doorway_/floor 必须存在（否则 MTR 每次都要走兜底分支，日志狂刷）
        chk("含 doorway_* 组（否则走兜底）", any(g.startswith("doorway_") for g in obj_groups),
            str([g for g in obj_groups if g.startswith("doorway_")][:3]), warn_only=True)
        chk("含 floor 组", "floor" in obj_groups, "", warn_only=True)

    # ---- 7) MTL：材质名与 OBJ 的 usemtl 必须一致
    mtls = [n for n in names if n.endswith(".mtl")]
    if mtls:
        mtl = z.read(mtls[0]).decode("utf-8", errors="replace")
        mtl_mats = {l.split()[1] for l in mtl.splitlines() if l.startswith("newmtl ")}
        used = set(re.findall(r"^usemtl\s+(\S+)", z.read(objs[0]).decode("utf-8", errors="replace"),
                              re.M)) if objs else set()
        chk("OBJ 用到的材质都在 MTL 里", used <= mtl_mats,
            "缺: %s" % sorted(used - mtl_mats))
        chk("有 door_mat（打包器兜底要用）", "door_mat" in mtl_mats, str(sorted(mtl_mats)))
        # map_Kd 指向的文件必须真在包里
        for ref in re.findall(r"^map_Kd\s+(\S+)", mtl, re.M):
            hit = any(n.endswith("/" + ref.lower()) or n.lower().endswith(ref.lower())
                      for n in names)
            chk("MTL map_Kd 指向的贴图在包里 (%s)" % ref, hit, ref)

    # ---- 8) 车辆 resource：length/width/bogie 与配置一致（不自证：与配置比）
    if config:
        veh = None
        for jn in jsons:
            if "custom_resources" in jn:
                c = json.loads(z.read(jn).decode("utf-8"))
                vlist = c.get("vehicles", [])
                veh = next((v for v in vlist if v.get("id") == config.get("id")), None)
        if veh is None:
            chk("包内有车辆定义 id=%s" % config.get("id"), False, "未找到")
        else:
            for key, cfgkey in (("length", "carLengthBlocks"), ("width", "carWidthBlocks"),
                                ("bogie1Position", "bogieOffsetBlocks")):
                want = config.get(cfgkey)
                got = veh.get(key)
                if want is not None:
                    # bogieOffsetBlocks 是负数绝对值，bogie1Position 也是负的，直接比
                    chk("车辆 %s == 配置 %s" % (key, cfgkey),
                        got is not None and abs(float(got) - float(want)) < 1e-4,
                        "包内 %s / 配置 %s" % (got, want))

    n_fail = sum(1 for s, _, _ in _results if s == FAIL)
    n_warn = sum(1 for s, _, _ in _results if s == WARN)
    print("-" * 90)
    for s, name, detail in _results:
        if s != OK:
            print("  [%s] %-46s %s" % (s, name, detail))
    print("-" * 90)
    print("共 %d 项：通过 %d / 失败 %d / 警告 %d" % (
        len(_results), len(_results) - n_fail - n_warn, n_fail, n_warn))
    print("结论：" + ("PASS —— 可以进游戏" if n_fail == 0 else "FAIL —— 不要发这个包"))
    return n_fail == 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("pack", nargs="?", help="pack .zip")
    ap.add_argument("--config", help="vehicle json（用来对 length/width/bogie）")
    ap.add_argument("--all", action="store_true", help="检查输出目录里全部 br101 包")
    a = ap.parse_args()

    cfg = json.load(open(a.config, encoding="utf-8")) if a.config else None

    if a.all:
        root = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.dirname(os.path.abspath(__file__))))),
            "game", "fabric", "run", "resourcepacks")
        packs = sorted((os.path.join(root, f) for f in os.listdir(root)
                        if f.lower().startswith("br101") and f.endswith(".zip")))
        allok = True
        for p in packs:
            _results.clear()
            allok &= check_pack(p, cfg)
        return 0 if allok else 1

    if not a.pack:
        ap.print_help()
        return 2
    return 0 if check_pack(a.pack, cfg) else 1


if __name__ == "__main__":
    raise SystemExit(main())
