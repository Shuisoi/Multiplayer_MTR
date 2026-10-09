# 395 客户端 `submit` 高的真正原因：**draw 次数**，不是每次 draw 的开销

> 用户口径（2026-10-05）：「帧率对了，目前 100fps」→（经查证是无车无轨的空场景，见 §1）
> →「现在 15 帧，和刚才没区别了」。

**这是一篇否定性记录。** notes/394 那轮的假设（`submit` 被逐 draw 的 uniform 上传主导）**被实测证伪**，
本轮把真正的自变量钉出来了。为避免后人重走，**结论放最前面**。

## 0 结论速查

| 问题 | 答案 |
|---|---|
| 帧率由什么决定？ | **`draws/frame`（每 pass 的 draw 次数）**。308 个窗口、`draws>200`，与 FPS 的皮尔逊 **r = −0.781**；分档严格单调：1000–1500→45.6 FPS、1500–2000→31.1、2000–2500→22.1、2500–3000→20.5 |
| notes/394 的修复救到帧率了吗？ | **没有。** `main.submit` 11.29 → 11.00 ms/帧（同口径） |
| 那修复白做了吗？ | 不是全白：**单 draw 成本 4.54 → 4.01 µs（−12%）**，但同场景 `draws/frame` 涨了 10%（2487→2741）把它盖掉了 |
| 每视觉帧多少 draw？ | `draws/frame` 是**每 pass** 的，要乘 `通行=2.00` ⇒ **~5400 draw/视觉帧**（2088 场景） |
| draw 是谁发的？ | **钢轨 ~924/pass + 车辆 ~1800/pass**（见 §3） |
| 车灯灯罩逐 draw 染色是放大源吗？ | **不是。** `染色draw` 只有约 3.6 次/**帧**（250–870 次/5 秒窗口） |
| 该动什么？ | **砍 draw 次数**：`renderDistance` 32→8、`disableShadowsForShaders`、以及"钢轨为什么一个批次里有 924 个 draw"（§4） |

## 1 先说那个 100fps：它是空场景，不能算数

`[MMTR-FRAME]` 与 `[MMTR-LIGHT]` 一致地报 **97–98 FPS**（`[MMTR-LIGHT] 性能` 的 197 fps 是 2× 口径，
除以 `通行=2.00` 就是真值 —— 这一点运行手册 §8.3 已写）。但同一个窗口里：

```text
车辆=0  钢轨=0   最近登记车=(NaN, NaN, NaN)   LUT: cells=0 tracked=0
draws/frame=0.0  batches/frame=0.00   已量段合计=0.15ms/帧
```

**零 draw。** notes/394 的修复只影响"每个 draw 的开销"，零 draw 时它一分钱都没省 ⇒ 这个数字与该修复无关。

**顺带得到一个有用的基线**：空场景天花板 ≈ **98 FPS（10.2ms/帧）**，而埋点段只占 **0.16ms**
⇒ 那 10ms 几乎全是原版地形 + 阴影 + 呈现，属于探针量不到的部分。

> **教训（探针口径）**：`[MMTR-FRAME]` 的 `已量段合计`**同时是一个"这个场景有没有内容"的判据**。
> 它掉到 <1ms/帧 就说明眼前没有 MMT 的东西 —— 这时任何 FPS 数字都不能用来验收性能改动。
> 验收性能改动**必须同时贴 `[MMTR-LIGHT] optimizer draws/frame`**，它才是"场景里有多少活"的度量。

## 2 修复的实测账（同口径对比）

| | 改前 13:23 | 改后 13:53 |
|---|---|---|
| `main.submit`（`累计ms ÷ 帧数`） | 1231/109 = **11.29 ms/帧** | 1221/111 = **11.00 ms/帧** |
| `shadow.submit` | 950/109 = **8.72 ms/帧** | 924/111 = **8.13 ms/帧** |
| `draws/frame` | 2487 | **2741** |
| 单 draw 成本 | **4.54 µs** | **4.01 µs** |

单 draw 成本确实降了 12%（少掉的 ~3 万次 GL 调用是真的），但：

- 它在 `submit` 里只占 12% ⇒ 说明 **`submit` 的主体是 draw 调用本身**（`glDrawElements` + VAO 绑定 + 驱动侧工作），
  不是 uniform 上传。notes/394 的假设**方向错了**。
- 而且改后场景 `draws` 涨了 10%，把收益吃干净 ⇒ 观感上"没区别"。

### 由此得到的 draw 成本账

按分档表取两个干净点：`draws≈1250 → 45.6 FPS（21.9ms）`、`draws≈2750 → 20.5 FPS（48.8ms）`。
Δdraws（每 pass）= 1500 ⇒ Δdraws（每视觉帧，×2）= 3000，Δ帧时 = 26.9ms
⇒ **边际成本 ≈ 9 µs/draw（每视觉帧）**，而 `submit` 里量到的只有 ~4 µs/draw。

**⇒ 一半以上的 draw 代价落在 `submit` 之外**（驱动命令缓冲、呈现、GPU 侧串行）。
这也解释了为什么"降分辨率没用"：分辨率减片元、**一个 draw 都不减**。

## 3 draw 的构成：钢轨和车辆各占一大块

从 `[MMTR-LIGHT]` 的两行日志交叉出来的（`批次：钢轨=N 车辆=M` 是**批次**数，
`钢轨+车辆` 每帧合计恰好等于 `batches/frame`，已核对多个窗口）：

| 场景 | `draws/frame` | `batches/frame` | 构成 |
|---|---|---|---|
| **只有钢轨、无车**（`车辆=0`） | **924** | **1.00** | 钢轨=518/518 帧 ⇒ **1 个钢轨批次里装了 ~924 个 draw** |
| 钢轨 + 车 | **2741** | **12.00** | 钢轨 1 + **车辆 11** ⇒ 车辆侧 ~1817 个 draw |

两个都大到值得单独看：

- **钢轨**：一个材质批次里 924 个 draw ⇒ 每个钢轨模型/节点段一个 `RenderCall`。
  `renderDistance=32`（512 格）沿线看过去，钢轨段数量是巨大的。
- **车辆**：11 个批次、~1817 个 draw，而 `画到的车=2` ⇒ 约 **900 draw/节车**。
  （`画到的车` 是**节**数：`锚点=2×车数`，见 `车灯：画到的车=4 锚点=8`。）

**已排除**：车灯灯罩逐 draw 染色（`染色draw` ≈ 3.6/帧）。
**已排除**：动态面（`main.faces=144ms/1026` ⇒ 9 次/帧，1.26ms/帧，便宜）。

## 4 下一步该动的（按"零代码 → 要改码"排序）

### ① 零代码，先做这两个（都能立刻用 `draws/frame` 验证）

| 实验 | 预期 |
|---|---|
| **`renderDistance` 32 → 8**（`run/options.txt`） | 钢轨与地形 draw 大幅下降 ⇒ `draws/frame` 塌下去、FPS 明显上升。**这是"降分辨率没用但降视距有用"的直接验证** |
| **`disableShadowsForShaders` 打开**（MMTR 配置界面已有按钮，`Client.java:51/95`） | 跳过 MTR 内容在阴影 pass 的绘制 ⇒ `draws/frame` 约减半（`通行` 变 1.00）⇒ FPS 接近翻倍。代价：车厢/钢轨不再投影 |

两个实验都以 `[MMTR-LIGHT] optimizer draws/frame` 为准，**不要只看 FPS**（见 §1 的教训）。

### ② 要改码：先把 draw 按来源拆开

现在只能靠"有车的窗口 vs 只有钢轨的窗口"间接推。应该让 `MmtrOptimizerStats` **按批次类别分别计数**
（`setupShaderBatchState` 处已经知道这一批是钢轨还是车辆 —— `MmtrLightField` 就是在这里分类的），
输出改成 `draws/frame=… (钢轨=… 车辆=…)`。这样"谁在发 5400 个 draw"是一行读数，
不用再靠场景对照去猜。（本轮**没有**做这个改动，避免又一次"改了却归因错"。）

### ③ 拿到拆分之后才谈优化

候选（**现在不要动**，等 ② 的数据）：
- 钢轨：按节点段合并 draw（MTR 的 `RenderCall` 是按 `VertexAttributeState` 切的，能否合并要看映射库）；
- 车辆：模型侧减少材质组/部件数（`mmtr-train-modeling` 的领域）；
- 通用：MMTR 自己有没有在放大 `RenderCall` 数量（逐 draw 颜色是已知的一个候选，但 §3 已排除灯罩那条）。

## 5 notes/394 的改动怎么处置（待用户决定）

保住：**单 draw 成本 −12%** 是真的，且三类调用（每次 draw 3 次 `glGetInteger`、每帧 ~1.5 万次冗余
`glUniform1i`）确实不该存在。

代价：引入了"**一个批次内 program 恒定**"这个前提。它靠 `RenderCall.draw()` 不重绑 program 成立；
若 MTR 改成流水线式批次，症状是**车体光照不对**（uniform 传到别的 program），不会崩。

⇒ 两个选择：**(a) 留着**（净收益 ~2–3ms/帧，前提已文档化、画面已验证正常）；
**(b) 回滚**（回到 notes/394 之前的三个 `glGetInteger`），把注意力全放到 §4 的 draw 次数上。

## 附：本次会话的关键读数留档

```text
空场景（无车无轨）：draws/frame=0.0    batches/frame=0.00    97–98 FPS   已量段=0.15ms/帧
只有钢轨（车辆=0）：draws/frame=924.0  batches/frame=1.00    ——          已量段=——
钢轨+车（改前）：    draws/frame=2487   batches/frame=12.00   21.6 FPS    main.submit=11.29ms/帧
钢轨+车（改后）：    draws/frame=2741   batches/frame=12.00   22.0 FPS    main.submit=11.00ms/帧
```

相关：`docs/02-运行与作业/性能探针-运行手册.md` §8.2 D9；`notes/394`（被证伪的假设）。

## 6 用户实测：`renderDistance` 32 → 8（2026-10-05 收盘）

用户按 §4① 做了这个实验，**结果把机制证实了**：

| `draws/frame` | FPS（同会话、相邻窗口、同世界） |
|---|---|
| 2457 – 2741（rd=32） | 19.4 – 27.1 |
| **923**（rd=8） | **40.7 – 42.4** |

`draws` **−62%**，FPS **+70%**。这与"降分辨率毫无变化"互为镜像 ⇒ **实例数决定 draw 数、像素数不决定**。

### 6.1 一个能对上账的分带解释（算术拟合，非直接测量）

`renderWithinRenderDistance` 的 `renderDistance = getRenderDistance() * 16` 把可见轨道切两带：

| 带 | 行为 |
|---|---|
| **< 32 格** | `callback.renderRail(...)` —— **不做任何剔除，无条件画** |
| 32 格 ~ 视距 | 只判"在相机**前方**"（`rotatedVector.getZ() > 0`） |

- rd=32（上限 512 格）：`[0,32)` 全画 + `[32,512]` 前向画 ⇒ **2457**
- rd=8（上限 128 格）：`[0,32)` 全画 + `[32,128]` 前向画 ⇒ **923**

差值 **1534 恰好是被砍掉的 `[128,512]`**。而唯一随视距变化的东西就是钢轨（车辆没有距离带）
⇒ **那 2457 个 draw 基本全是钢轨**。这解释了为什么降视距有效、而降分辨率无效。

⚠️ 但这是拟合，不是测量：日志无法把 `[0,32)` 与 `[32,128]` 分开，也无法排除 923 里混着车辆的
`PartCondition` 实例（车辆不走距离带，在 rd 变化里会被掩盖）。

### 6.2 顺带发现：两处剔除缺口（**都在我们自己的源码里**）

`RenderRails.renderWithinRenderDistance`：

```java
if (distanceToCamera <= renderDistance) {
    if (distanceToCamera < 32) {
        callback.renderRail(...);              // ① 完全不剔除（含背后的轨道）
    } else {
        final Vector3d rotatedVector = ...rotateY(yaw).rotateX(pitch);
        if (rotatedVector.getZMapped() > 0) {  // ② 只判"在相机前方"
            callback.renderRail(...);
        }
    }
}
```

**①** 32 格以内的轨道永远画，**包括玩家背后的** —— 在车站/车辆段里这个数不小。
**②** 那个测试**不是视锥剔除**：`z > 0` 只判"在相机前方"，**完全不判左右和上下**。顺着一条线看时，
FOV 之外侧向的轨道照画。真正的视锥剔除（加侧面平面 + 余量）能再剔掉相当一部分。

两处都在 `RenderRails.java`，**不牵动 MTR 的映射库**，且不损失画面（那些本来就在视野外）。
**但动手前必须先拿到 §7 的拆分** —— 如果那 923 主要是车辆，这两处改了也没用。

## 7 已加的探针：`[MMTR-LIGHT] optimizer` 行的 draw 拆分

用户批准后已实现（`MmtrOptimizerStats` + `ShaderManagerMixin` 一处传参 + `MmtrLightField.isRailTexture` 改 public）。
输出：

```text
[MMTR-LIGHT] optimizer draws/frame=923.0 batches/frame=12.00 (窗口 319 帧)
  ｜ 拆分：钢轨 draws=890.2(1.00批) 非钢轨 draws=32.8(11.00批)
  ｜ 非钢轨 top4：mtr:models/vehicle/saf420car.png=20.1(3.00批) | … …另 N 个贴图（合计 X.X draw/帧）
```

**为什么归属是精确的、不是推断的**：计数点选在**批次 HEAD**（`setupShaderBatchState`）。
MTR 的批次循环是"每桶 setup 一次 → 把这桶的 RenderCall 全画完"，而 `RenderCall.draw()` 不重绑材质
⇒ 一个批次内的每个 draw 必然属于这一批。这个不变量与 notes/394 的 program 缓存**是同一个依据**
（那里还额外挨了一记证伪：矩阵去重不成立，因为 `draw()` RETURN 会清零 `mmtrModelMat`）。

**判据唯一**：`isRailTexture` 改 public 复用，不写第二份"什么算钢轨"。
`批次` 数应与同行的 `批次：钢轨=N 车辆=M` 一致；不一致本身就说明有批次没走 `setupShaderBatchState`。

编译校验：`check-java-compile.ps1 -Quiet` → 504 源文件 / **761** 类（+1 = 新增的 `Bucket` 内部类），exit 0。
运行手册新增 §8.7 记录读法。

> **坑（既存）**：`game/.gitignore:36` 的 `**/org/mtr/mixin/` 使整个 mixin 层不在版本控制内，
> 而 **grep 工具也遵守 .gitignore** ⇒ 搜 `ShaderManagerMixin` / `VertexAttributeStateMixin` **搜不到**。
> 改 mixin 只能用文件读取路径，别因为 grep 没命中就以为文件不存在（本轮差点据此误判）。

