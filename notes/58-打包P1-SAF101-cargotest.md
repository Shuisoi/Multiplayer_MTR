# 58 - 打包 P1 客车 / SAF101 机车 / cargotest 货车

> 用户新导出三套模型（Blender → OBJ），要求打包。
> 产物：`mmtr/game/fabric/run/resourcepacks/MMTR_NewStock_v1.zip`（211 KB，3 个车底，44 张贴图）。

## 三套模型的实测结构

| 模型 | 主要对象（OBJ `o` 行） | 尺寸（块） | 备注 |
| --- | --- | --- | --- |
| `p1` 客车 | `selection/chunks_merged`(壳)、`BlockEntities/…`(内装)、4 扇侧门、1 扇端门 | 16 × 5 | 侧门 x ±(3.5..4.5)，端门在 x=8.5 端面 |
| `saf101` 机车 | **`body`**、`BlockEntities/…`、4 扇司机侧门、2 扇内部司机门、`mmtr_seat_1/2`、`mmtr_hud_1/2` | 16 × 5 | **双端机车**：两个 HUD + 两个座位（正是 §3.6.6 那个场景） |
| `cargotest` 货车 | `selection/chunks_merged`(壳)、`cargo`(小物件) | 16 × 5 | 无门无内装 |

## 导出必须修的三处命名（重要，下次导出请直接照这个命名）

| 问题 | 后果 | 处理 |
| --- | --- | --- |
| `saf101` 的车壳被命名成 `mmtr_cabdoor_1_3.001`（与两扇内部司机门同名，仅靠 Blender 的 `.001/.002` 后缀区分） | 打包器会**剥掉 `.001` 后缀**，三个对象同名 → 车壳会被当成锚点**从渲染里剥掉**（整台机车不可见） | 打包输入里改名：壳 → `body`，两扇内部门 → `mmtr_cabdoor_1_3` / `mmtr_cabdoor_2_3` |
| `p1` 的侧门命名成 `mmtr_door_l_1` 等（带 `mmtr_` 前缀） | `mmtr_` 前缀会被当成锚点 → 门不渲染、不滑动 | 打包输入里改名 `door_l_1` / `door_r_1` / …（同 HST_B 的做法） |
| `cargotest` 的小物件是 `minecraft:bat/<uuid>` | 名字不可读、UUID 每次导出都变 | 打包输入里改名 `cargo` |

**约定**：车壳一律叫 `body`（结构导入的模型用 `selection/chunks_merged` 也行）；门 `door_l_<n>` / `door_r_<n>`；
锚点 `mmtr_hud_<cab>` / `mmtr_seat_<cab>` / `mmtr_cabdoor_<cab>_<n>`（双端机车同一节车写两套）。

## 打包器补了两个能力（否则机车进不去）

| 参数 | 作用 |
| --- | --- |
| `floor: true` | 强制生成 FLOOR 部件。原来地板只在地板/门洞由**客车门**推导时才生成，纯机车没有客车门 → 没有地板 → 驾驶员站不住 |
| `extraDoorways: ["cabdoor_1_1", …]` | 给指定部件生成 DOORWAY 门洞板。机车只有司机门，没有门洞就**永远上不了车** |

两者都只在配置里显式开启，不影响既有包。

## 生成结果

| 车底 | 部件 | 门洞 | 地板 |
| --- | --- | --- | --- |
| `p1` | body / interior / `door_l_1,2` `door_r_1,2`（滑动 ±14px）/ `door_f`（端门，静态） | 4 | ✓ |
| `saf101` | body / interior / `cabdoor_1_1,1_2,2_1,2_2`（滑动）/ `cabdoor_1_3,2_3`（内部门，静态） | 4 | ✓ |
| `cargotest` | body / `cargo` | — | —（货车无需） |

**锚点报告（saf101）**：`hud_1` z=+6.28 法线指向 -z、`hud_2` z=-6.29 法线指向 +z —— 两个仪表**各自朝自己的司机**，
`seat_1` 朝 +z、`seat_2` 朝 -z，**不需要 `flipAnchorNormalByGroup`**。4 扇司机侧门 + 2 扇内部门齐备。

校验：zip 59 条目全小写、44 张 PNG 全命中（无 `C:/…` 绝对路径残留）、3 个 vehicle 条目、锚点 JSON 在 `assets/mtr/`。

## 实机注意

- 资源包已放进 `resourcepacks/`，**要在游戏里 Options → Resource Packs 里启用**（客户端运行中改 options.txt 会被覆盖）；
- 待目视确认：① 四个侧门的滑向（默认左 +14 / 右 -14）；② 端门 `door_f` 静态是否合适；③ 内装 `BlockEntities` 的 y 偏移（导出在车壳下方 0.71 格，与 HST 一致，先按原样）；
- 要刷车还得在滚动清单里加条目（下一步）。
