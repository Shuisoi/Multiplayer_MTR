# 417 · TEST 站台铺半高闸机（APG）墙

> 日期：2026-10-13 ｜ 触发：用户「我放了几个屏蔽门，帮我把 test 站台的屏蔽门铺完」
> 工具：`mmtr/tools/psd-wall/gen_apg_wall.py`（本轮新增）｜ 验收：`sandbox/tmp/verify_apg_wall.py`（存档直读）
> 世界：`mmtr/game/fabric/run/world`（dev 服务端在跑，客户端在跑）

## 1. 用户口径（本轮的三个决定）

| 问题 | 用户答复 |
| --- | --- |
| 墙要占的那一排正好是「归属锚」那一排（两侧共 58 个，z=820…994 每 6 格一个） | **锚点让位、盖掉** |
| 南端收在哪 | **z=1015**（保留他已有的 1014/1015 端头，1016…1020 留空） |
| 锚点谁补 | 「**归属锚我会在不放屏蔽门那里放置**」⇒ 本轮不做锚点回填 |

## 2. 门位口径：不是拍脑袋，是 SAF420 的门距

`mmtr/tools/crowd-marker/gen_door_queue_layout.py` 的文件头（2026-10-10 的样板）已经把这条写死了：

> SAF420 门距是**精确 6.000 m**（头车 3 门：z=−4.725/+1.275/+7.275；拖车 3 门：z=−6.0/0/+6.0），
> 十节 2 头车 + 8 拖车 = **30 个门洞**。

⇒ 一个门洞占 **6 格**：门扇 2 格 + 玻璃 4 格；站台上那排客流标记的**归属锚正好在门洞起始格**
（TEST：锚 z=820,826,…,994 共 30 个）——用户手放的那 3 对门（983-984 / 989-990 / 995-996）
正是最后 3 个门洞（z0=982/988/994）的 `z0+1..z0+2`，**和本条口径逐格吻合**。

## 3. 门扇朝向：照抄 MTR，不自己发明

`ItemPSDAPGBase.useOnBlock2`：`x=0` 的块 `side=left`、`x=1` 的块 = `pos + playerFacing.rotateYClockwise()` 且 `side=right`；
`IBlock.getSideDirection`（`IBlock.java:105`）反过来读：`LEFT` 的"另一半"在 `rotateYClockwise(facing)`、
`RIGHT` 在 `rotateYCounterclockwise(facing)`。⇒ 一对门占相邻两格 `(hole, hole+1)`：

| 面 | facing | rotateYClockwise | left 在哪一格 |
| --- | --- | --- | --- |
| TEST 西侧站台面（x=-45） | east | south | **hole（北）** |
| TEST 东侧站台面（x=-35） | west | north | **hole+1（南）** |

★ 独立佐证：用户手放的那 6 格门在文件里的 `side` 正是 `983=left / 984=right`（facing=east），
与上表一致 ⇒ 本轮 60 对门没有一格需要改朝向。

## 4. ★ 铺之前发现的坑：站台两端的信号灯柱

TEST 站两端各有一对信号灯（`ledger §4`：柱 = 轨面+1、灯头 = 轨面+2）：

| 位置 | 方块 |
| --- | --- |
| `(-45/‑35, 66, 800)` `(-45/‑35, 66, 801)` | `mtr:signal_pole` |
| `(-45/‑35, 67, 800/801)` | `mtr:signal_light_4_aspect_1`（一面朝 north、一面朝 south） |
| 南端同构 | z=**1019/1020**（在墙的南端 1015 之外，无冲突） |

⇒ **墙的北端从 z=802 起**（不是 800），否则会把两列信号柱和灯头整片盖掉。
`gen_apg_wall.py` 的用法注释里写明了这条（后来人不会再踩）。

## 5. 实际下发

```
python mmtr/tools/psd-wall/gen_apg_wall.py \
    --face=-45,east --face=-35,west \
    --y 66 --z1 802 --z2 1015 --door-start 820 --door-pitch 6 --door-count 30 \
    --glass-end 802,1014,1015 --check-floor --apply
```

* `--check-floor`（发之前**逐格读存档**）：432 格脚下全是 `mtr:platform`，**0 格不合格**；
* 指令 **856** 条：**790 条真的改了方块，66 条回话是 `Could not set the block`** ——
  这 66 条**不是失败**：1.20 的 `/setblock` 在"新状态与旧状态逐字节相同"时 `setBlockState` 返回 false，
  于是回这句话。66 = 用户已有的那一段（玻璃 25×2 + 端头 2×2 + 门 6×2）。
  ⇒ 判据纪律：**不能只看回话**，要看存档（下一节）。

## 6. 验收（存档直读，`sandbox/tmp/verify_apg_wall.py`）

`save-all flush` 后直读 region：

```
面 (-45, east)：{'mtr:apg_glass_end': 6, 'mtr:apg_glass': 302, 'mtr:apg_door': 120} ｜ 残留锚 0 ｜ 脚下 {'apg': 214}
面 (-35, west)：{'mtr:apg_glass_end': 6, 'mtr:apg_glass': 302, 'mtr:apg_door': 120} ｜ 残留锚 0 ｜ 脚下 {'apg': 214}
问题数： 0
```

判据逐条对账（`z=802..1015`，两面各 214 列 × 2 层 = 428 格）：

| 判据 | 期望 | 实测 |
| --- | --- | --- |
| 每列 y=66/67 都是 `mtr:apg_*` 且 `facing` 对 | 428/428 | 428/428，0 个洞 |
| 门扇格 = 30 个门洞 × 2 格 × 2 层 = 120 | 120 | 120，且 left/right 与 §3 表一致 |
| 端头 `apg_glass_end` = 3 格 × 2 层 | 6 | 6（z=802、1014、1015）|
| 其余玻璃 | 302 | 302 |
| 那两排上残留 `mtr:crowd_anchor` | 0 | 0（按用户口径让位）|
| 脚下站台方块 `door_type` 是否跟上 | 214 | 214（MTR 自己按邻居更新算的，没手工写）|

## 7. 回滚 / 后续

* **清墙**（两面）：`fill -45 66 802 -45 67 1015 air`、`fill -35 66 802 -35 67 1015 air`
  —— 注意这会把用户自己那段也一起清掉，再想恢复门就重跑第 5 节的命令。
* **把 58 个归属锚铺回去**（会与墙抢格，先决定要墙还是要锚）：
  `python mmtr/tools/crowd-marker/gen_door_queue_layout.py --platform-block-x -45 --side -1 --start-z 820 --doors 30 --apply`
  `python mmtr/tools/crowd-marker/gen_door_queue_layout.py --platform-block-x -35 --side 1 --start-z 820 --doors 30 --apply`
* **整目录回滚**：`pwsh -File mmtr/scripts/world-backup.ps1 -Action restore`（施工前未另取还原点，本轮改动是"加方块"级别）。
* TEST 站台轨**是一条孤立的 220 m 双线**（`query node -43,65,800` 度=1，不在任何路线上）⇒
  现在没有车会停它。将来接上线时，门位已按 SAF420 的 30 个门洞铺好，只差**停车基准**（z0 相位）对齐。
* 门型：本轮铺的是用户已放的 **APG（半高闸机）** `mtr:apg_*`。要换成全高 PSD（`mtr:psd_*` + `mtr:psd_top` 顶盖）
  需给 `gen_apg_wall.py` 的 `STYLES["psd"]["top"]` 补上顶盖写法（顶盖状态由方块自己按邻居推导，脚本里留了 TODO）。
