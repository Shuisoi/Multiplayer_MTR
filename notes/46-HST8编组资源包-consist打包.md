# 46 - 标准 8 编组 HST：consist 打包与反向头车

> 目标（用户）：把 Blender 导出的 HST 客车接进资源包，产出**标准 8 编组**：
> 首尾各一节 `hst_h`（尾车朝向相反）+ 中间 6 节 `hst_b`。
> 输入：`models/blender/HST_B/HST_B.obj` + `HST_B.mtl`（2026-09-09 11:39 导出）。
> 工具：`tools/obj-mtr-packager`。

## 输入结构（与 hst_h 的差异）

- 新导出是 **Blender OBJ + MTL**，材质在 MTL 里；`map_Kd` 写成 `C:/<basename>.png`
  （打包器按 basename 从 `textureDir` 取图，所以只需把 PNG 凑到一个目录）。
- 分组名与 hst_h 不同：`selection/chunks_merged`（车身，X ±8.5 = **17 m**）、
  `BlockEntities/material_0000`（内饰，与 hst_h 同名同位）、
  `mmtr_door_l_1/l_2/r_1/r_2`（**2 扇/侧**，共 4 扇门叶）。
- 贴图分散两处：`models/blender/HST_H/` 有 15/21，MineToMesh 导出 `hst_M/textures/` 有 18/21；
  **合并后 21/21 齐全**（见 `models/blender/HST_B/tex/`）。
- 转向架在车身网格里，材质 bbox 显示两块各 1 m 宽、中心 **X = ±5.0**（与 hst_h 一致）。
- 无 `mmtr_*` 锚点（中间车无驾驶室）。

## 打包器增强（两处）

1. **`zip.js`**：把 zip 写入从 `pack_vehicle.js` 抽成共享模块（consist 也要用）。
   回归验证：用原配置重打 hst_h，**SHA256 与 HST_H_v12.zip 完全一致**（确定性 zip）→ 重构零行为变化。
2. **`groupRename`**（`pack_vehicle.js`）：在角色匹配前重命名源分组。解决两件事：
   - `mmtr_door_*` 会撞上 `mmtr_` 锚点前缀（且门洞生成只认 `door_l*`/`door_r*`）→ 映射成 `door_l_1`…；
   - 反向头车需要把锚点挪到 2 号驾驶室（`mmtr_hud`→`mmtr_hud_2` 等）。
3. **`pack_consist.js`**（新）：一个 pack 装多个 vehicle——逐车调用现有 `pack_vehicle.js`
   到私有 staging，再合并成一份 `mtr_custom_resources.json` 与一棵 `assets/mtr/` 树。

## 三个 vehicle 与反向头车

| id | 长度 | 说明 |
| --- | --- | --- |
| `hst_h` | 15 | 头车（原样） |
| `hst_b` | **17** | 中间客车（新模型） |
| `hst_h_rev` | 15 | 头车**绕 Y 旋转 180°**（`rotationDegY: 90`，原为 -90） |

反向头车三处必须同时改，否则门/锚点会错位：

1. **锚点改名到 2 号驾驶室**：`mmtr_hud_2` / `mmtr_seat_2` / `mmtr_cabdoor_2_1`。
   客户端 `cabView(anchors, 2)` 只有找不到 `cabdoor_2_*` 时才按 z 镜像；模型已旋转，
   若仍留 1 号锚点会被二次镜像 → 视点跑到车另一头。
2. **门滑向取反**：`doorSlideByGroup: {"door_l_1": -14, "door_r_2": 14}`——整模旋转后门叶
   的收纳槽方向反转。
3. **HUD 法线翻转保留**：`flipAnchorNormalByGroup: ["mmtr_hud_2"]`（旋转不改绕序）。

## 验证（离线，全部通过）

- zip：81 条目、**全小写**（MTR 会把资源路径整体小写，大写名静默读空）；
- 每车：`properties_<id>.json` 的每个 part 都能在 OBJ 里找到同名 `g`；
- 面索引：`maxFaceIdx == vCount`，**0 个越界**（薄片索引偏移这类坑不复现）；
- 材质：每个 `usemtl` 都有 `newmtl`，每个 `map_Kd` 的 PNG 都在包里；
- 部件清单：`hst_b` = body + interior + 4 扇门 + 4 个门洞 + floor；
  `hst_h_rev` = body + interior + 2 扇门 + 2 个门洞 + floor + `cabdoor_2_1`。

## 待目视确认（实机）

- **反向头车门滑向**：若尾车门开反，把 `doorSlideByGroup` 两个符号互换即可。
- **`hst_b` 长度 17 m**：模型实测 X ±8.5；若本意与 `hst_h` 同为 15 m，需在 Blender 缩放后重导出。
- `BlockEntities` 作为 `interior`（与 hst_h 一致）；它在 Y −0.71..0.49，视觉上可能位于地板之下，
  与旧包行为相同，如异常一并排查。

## 产物

`mmtr/game/fabric/run/resourcepacks/HST8_v1.zip`（296 KB，3 个 vehicle）。
启用方式：游戏内资源包界面启用 `HST8_v1`，并**禁用 `HST_H_v12`**（两者都定义 `hst_h`，避免歧义）。
