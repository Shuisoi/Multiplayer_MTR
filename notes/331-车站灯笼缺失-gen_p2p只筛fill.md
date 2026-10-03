# 331 · 自动建造的车站"有棚无灯"：`gen_p2p` 只筛 `fill`，把 88 条吊灯笼 `setblock` 丢了

> 用户口径（2026-09-27，紧接着 notes/330 那一单）：
> **「自动建造的车站没有灯笼照明」**。
> 结论：**属实，而且不止一座** —— `gen_p2p.py` 建的车站全都缺；`stamp_station.py` 建的都正常。

---

## 1. 定位：车站模板的产物里**不只是 `fill`**

`gen_station.py` 的吊灯笼是**悬挂状态的方块**，`fill` 表达不了 ⇒ 只能逐条 `setblock`：

```text
setblock -1901 78 1378 minecraft:lantern[hanging=true,waterlogged=false]
```

`p2p_下水_station_fills.txt` 的行类型统计（修复前后都一样）：

| 类型 | 条数 |
| --- | --- |
| `fill`（清障 / 路基 / 站台 / 雨棚 / 立柱） | 68 |
| **`setblock`（吊灯笼）** | **88** |
| 注释 `#` | 32 |

而 `gen_p2p.main` 落方块时写的是：

```python
[l for l in st_fills if l.startswith("fill")]        # ✗ 灯笼全被丢掉
```

⇒ **两座由 `gen_p2p` 建的站（叶楼村 / 鸥湾）"有棚、有柱、有信号灯，就是没有灯笼照明"**。
`stamp_station.py` 走的是整包下发，所以 `stamp_station` 建的四座站（下水 / 上水村 / 莫氏岛 / 样板站）
一直是有灯的 —— 这也解释了为什么只有"自动建造的车站"出问题。

## 2. 修法（两处）

| 位置 | 改动 |
| --- | --- |
| `sandbox/gen_p2p.py` ⑧ 步 | 车站产物**整包下发**：`fill + setblock`（并把灯笼条数打进日志），不再只筛 fill |
| `sandbox/verify_p2p_build.py` L2 | 新增判据：**站台吊灯笼在**（轨面+3..+5 三层、雨棚外侧两列采样，命中 >= 20）；顺手给 `blk()` 加了区块缓存（原来每个方块都重新 parse 一遍 NBT） |

> 补既有站的工具：**`sandbox/_station_lanterns_backfill.py`**（从产物文件里取 `setblock`，
> 自动 forceload 出包围盒 → 铺 → `save-all flush` → **逐条直读存档核对** → 清 forceload）。
> 既有两座站已用它补齐。

## 3. 实测

| 站 | 轨面 | 灯层 y | 灯笼（存档直读计数） | 来源 |
| --- | --- | --- | --- | --- |
| 下水 | 65 | 68..70 | 92（比模板多 4，早先手工加的） | `stamp_station`（本来就有） |
| 上水村 | 65 | 68..70 | 88 | 同上 |
| 莫氏岛 | 75 | 78..80 | 88 | 同上 |
| 样板站（z 轴） | 65 | 68..70 | 88 | 同上 |
| **叶楼村**（东段，gen_p2p 建） | 71 | 74..76 | **0 → 88** | 本轮补 |
| **鸥湾**（西段，gen_p2p 建） | 74 | 77..79 | **0 → 88** | 本轮补 |

* 两次补铺都 **88/88 条命令被接受、0 拒**；`save-all flush` 后**逐条读存档**全部是
  `minecraft:lantern[hanging=true,…]`（悬挂状态也在）；
* 计数的扫法是 `sandbox/_station_lanterns_backfill.py` 同款：轨面+3..+5 三层 × 站台范围 × 断面 ±13。

## 4. 顺带记两件现场变化（用户侧）

1. **西段新站已归站命名 = 「鸥湾」**：`stationId = 1329735459136473121`、
   `stationHex = 12742B5AD7120821`，平台 `0904DC45FCEB31D3` / `F34879D2DB12FFF0`
   （notes/330 记的"未归站"到此结束）；台账已同步。
2. 引擎里**信号灯由 160 变 162**（用户自己在客户端加了 2 盏）。四层验收按 162 复跑：
   **西段四层全绿**（含新加的灯笼判据，采样命中 22 处），**东段回归也全绿**。

## 5. 回滚

* 灯笼是**纯新增的方块**（`setblock` 成 `minecraft:lantern`），回滚只需把这两片的灯笼
  `fill … air`（或整目录回滚到 notes/330 的还原点 `20260927-151428-p2p-p2p_下水`，
  但那会把**整条西段线**一起撤掉，没必要）；
* 产物与脚本：`sandbox/p2p_下水_station_fills.txt`（西段）、`sandbox/p2p_v6_station_fills.txt`
  （东段；`p2p_v2..v6` / `p2p_next_station` 六份**内容完全相同**，已比对 sha256）、
  `sandbox/_station_lanterns_backfill.py`。
