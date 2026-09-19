# 189 · 火车乘车恢复 + `driverOnBoard` 实装（W4 进游戏验收的前置）

## 1. 为什么要做

W4（实体雨刷）的离线部分已经全绿（notes/188 §十三），但**游戏里看不到雨刷动**：
`MmtrWindshield.driverOnBoard()` / `driverOnBoardAnyCab()` 是 notes/185 清空阶段留下的
**写死 false** 的桩，而刀片的绘制被它门控（`render()` 第 294 行）。桩不除，雨刷永远停在停放位。

## 2. 关键发现：B2 **不是**重建整个 riding 层

清空阶段（notes/185）删掉的是 `RenderVehicles` 里的**调用点**（36 行），而
`VehicleRidingMovement` 里的 riding 机制**当时就保留成了通用件**：
`startRiding` / `movePlayer` / `bestPosition` / `clampPosition` / 廊道（gangway）分支全都还在，
`movePlayer` 的签名与旧调用**逐字一致**。所以这一步是**恢复调用点**，不是重写机制。

（删除面实测：`VehicleRidingMovement.java` 832 -> 423 行，两侧合计 324 insertions / 767 deletions；
其中 `RenderVehicles.java` 只占 36 行。真正没了的、也**本轮没有**恢复的是驾驶室层：
司机钥匙、座位钉扎、驾驶位锁、权限门、G 键驾驶室交互。）

## 3. 做了什么

1. **上车调用点**（`RenderVehicles`，`canRide` 分支）：
   `openFloorsAndDoorways` 只收**开着的车门**（不收 floor —— 这样在车内走动不会把主动下车的玩家
   立刻吸回去），然后 `VehicleRidingMovement.startRiding(openFloorsAndDoorways, depotId, sidingId,
   vehicle.getId(), carNumber, playerX, playerY, playerZ, yaw)`。
   旧代码用 `MmtrCabPermissions.canBoard` 做门；那个类已随驾驶室层删除，故**对所有人开放**
   （"谁能开车"是重建后的驾驶室会话该回答的问题，不是"能不能上车"）。
2. **每帧移动调用点**：恢复 `VehicleRidingMovement.movePlayer(millisElapsed, vehicle.getId(), carNumber,
   floorsAndDoorways, gangway1/2, absoluteVehicleCarPositionAndRotation)`。
   这是**唯一**让乘客钉在车上、并续期行程的东西 —— `tick()` 在"没人移动玩家"时会直接结束行程，
   所以没有它，上车会立刻掉下来。
3. **`driverOnBoard(String resourceId, int carNumber)`**：骑行状态 -> 车辆 -> 车厢；
   车厢号必须等于当前渲染的车，且该车的资源 id（`getVehicleCarsAndPositions().get(car).left().getVehicleId()`，
   与 `ModelPropertiesPart.vehicleIdFor` 同一处查法）必须等于当前风挡的资源 id。
4. **`driverOnBoardAnyCab()`**：骑行的这节车**有风挡**才允许切档，否则仍提示"先坐上驾驶位"
   （坐电梯或坐无驾驶室的车不会去动一个不存在的雨刷）。
5. 更新 `VehicleRidingMovement` 三处**已过时**的文档（原文写"火车上不了车 / 只有电梯可用"），
   否则注释与代码互相矛盾。

## 4. 边界（诚实说明）

- **驾驶室层仍未重建**：现在上车 = "走进开着的车门"，不是"认领驾驶位"。没有驾驶位锁，
  也没有司机钥匙，所以**任何人**都能进驾驶室。
- "哪个驾驶室"以**车厢**为判据：每个 MMTR 模型一个驾驶室独占一节车（编组两端各一个），
  所以"我在哪节车"就是"我在哪个驾驶室"。若某模型把两个驾驶室放在同一节车上，两者会一起刮 ——
  目前没有这种模型。
- **尚未进游戏实际观察**（需启动客户端 + 世界里有车 + 走进开着的门 + 按雨刷键）。

## 5. 验收

| 项 | 结果 |
|---|---|
| `:fabric:compileJava` | **BUILD SUCCESSFUL** |
| `verify_windshield` | PASS（本轮未改打包器/校验器/夹具） |
| `selftest` | 21/21 |
| 进游戏看雨刷 | **待用户验收** |

### 进游戏验收步骤

1. 启动客户端，进入有 MMTR 车辆的世界；
2. 走到车旁让车门打开，**走进门里**（应被"吸"进车厢并随车移动）；
3. 按雨刷键（`MMTR_WIPER`）循环 关 -> 慢 -> 快 -> 关，刀片应扫过玻璃、雨滴被刮掉；
4. 走到**另一端驾驶室**重复一次：只有你所在那节车的雨刷该动，另一端的应停在停放位。

## 6. 雨滴两处口径缺陷（本轮一并修掉）

`driverOnBoard` 变成真值之后，扇形刮水路径**第一次真的会在游戏里跑**，于是这两个一直存在、
只是从未被观察到的缺陷必须一起修：

### 6.1 `wipeFactor` 返回 -1 导致「够不到的雨滴」整片消失

调用方把**负值**当作"这颗珠子不要画"，而 `wipeFactor` 在"超出臂展"时返回 **-1**：

```java
if (deltaX * deltaX + deltaY * deltaY > reach * reach) return -1;   // 旧
...
if (wipe < 0) { continue; }        // 于是连 drawDrop 一起跳过
```

结果：雨刷一开，**臂展以外**的珠子全部凭空消失（刀片根本碰不到它们）。
`wipeFactorBand` 的文档早就写明"**绝不返回 -1**：那个值对调用方意味着整颗跳过，
而刀片没碰到的珠子必须照画" —— 扇形路径违背了同一条契约。

**修法**：超出臂展返回 `0`（= 没被刮到，照画），并删掉调用方那个 `wipe < 0` 分支，
把"两个刮水判据都是非负"写成契约。同时删掉零调用者且注释说谎的 `wasWiped()`。

### 6.2 扇形刮水测试的 y 轴被镜像

`wipeFactor` 把竖直分量取负：

```java
final double angleDeg = Math.toDegrees(Math.atan2(-deltaY, deltaX));   // 旧
```

注释说"sheet v 向下增长"，但整个面板是 **y 向上**的，四处一致：

- `MmtrPanelCanvas` 的文档：角度是"**counter clockwise from +X in the panel's own y-up space**"；
- `panelDown()` 返回 `{0,-1}`，即"下 = 负的 up"；
- `drop.y` 向上增长（`dropY = drop.y * heightM` 直接当 y 用）；
- `parkAngleDeg`/`sweepDeg` 由打包器按 `toRightUp` 的 y 向上口径算出。

而且**两条 film 路径与 `wipeFactorBand` 都不取负** —— 所以扇形测试连自己的 film 都对不上。
后果：刮水区关于主轴水平线**整体镜像**，刀片会去刮玻璃错误的那一半。

**修法**：直接用 `atan2(deltaY, deltaX)`。

**为什么一直没被发现**：这两处都在**客户端渲染**里，而渲染出来的刀片此前被
`driverOnBoard()`（恒 false）门控 —— 扇形路径在游戏里**从未真正运行过**。
这正是"先把上车恢复掉，否则离线再绿也验收不了"的具体含义。

**未做**：这两条都**没有离线判据**（客户端渲染没有测试台），只能靠上面的帧约定推导 +
进游戏目视验收。

## 7. 更正：`driverOnBoard` 的"哪个驾驶室"判据错了，已改对（2026-09-18）

上一版（§3）把"哪个驾驶室"判成"**哪节车**"，并断言"每个 MMTR 模型一个驾驶室独占一节车，
所以我在哪节车就是我在哪个驾驶室"。**真实模型推翻了这个断言。**

实测带雨刷的真车包 `MMTR_NewStock_v1.zip` 里的 `mmtr_anchors_saf101.json`：

```
windshield_1  cab=1  car=0   pos = (0, 3.76162,  7.78634)   normal z = -0.959
windshield_2  cab=2  car=0   pos = (0, 3.7476,  -7.78299)   normal z = +0.959
```

两个驾驶室都在 **car=0**（同一节车的两端），而 `cab` 的含义是 **1 = A 端、2 = B 端**
（`MmtrVehicleAnchors` 原文：`cab` follows the same meaning as everywhere else: 1 = A end, 2 = B end）。

**后果**：只比车厢号 ⇒ 坐在这一节车里时**两个驾驶室的刀片会一起扫** —— 正是本目标
括号里点名要避免的那个失败。

**修法**：判据改成**玩家沿车长的位置** vs **该车各块玻璃自己的位置**，取最近的那块玻璃所属的 cab：

- 两个量都在 MTR 的 riding space 里（anchor 经 `MmtrVehicleAnchors.toRidingSpace` 转到该空间；
  骑行 offset 本来就在该空间），所以可以直接比较；
- 用"最近"而不是正负号判断，因此**不假设前后约定**，而且多于两个驾驶室的模型也能解；
- 判据从"每节车算一次"改成"**每块玻璃算一次**"（移进 `render` 的 anchor 循环内），
  因为同一节车上不同玻璃的答案不同。

## 8. 覆盖面与遗留（诚实说明）

1. **三个真车判据不覆盖风挡路径**：`hst_h` / `saf101_snd` / `br101` 三个配置的模型
   实测都**没有** `mmtr_windshield` 组（`assets/models/blender/saf101/pack/saf101.obj` = 0 个），
   所以"三个真车锚点逐字节不变"这条判据**只覆盖非风挡路径**。风挡路径的离线覆盖来自
   `wipefix` 合成夹具；真正带雨刷的真车（`saf101v2.0/SAF101v2.obj`，含
   `mmtr_windshield_1` / `mmtr_windshield_2`）在仓库里**没有对应配置**，本轮无法重打包验证。
2. **实体部件动画的门控是间接的**：`pushPartTransform` 本身不查驾驶/驾驶室，
   但它读的是**每块玻璃自己的 `State` 角度**，而 `State` 只在被驱动的那块玻璃上被推进
   （`mode == OFF` 时停在停放位）。所以"只有你所在驾驶室的部件会动"是成立的，
   但这条依赖**门控经由 State 传递**这一事实 —— 改动 State 的生命周期时要一起想。
3. **先前就存在、本轮未动**：`render(vehicleId, carNumber, …)` 用**编组车厢号**去匹配 anchor 的
   **模型内车厢号**（`anchor.car`）。对"一节 OBJ 的单车模型"（`anchor.car` 恒为 0），
   多节编组里只有 `carNumber == 0` 那节能匹配到玻璃 —— 即多节编组的其它车厢可能根本不画风挡。
   saf101 是单车（probe），所以从未暴露。不在本目标范围内，留作待办。

## 9. 本轮验收证据

| 项 | 结果 |
|---|---|
| `:fabric:compileJava`（JDK 21） | **BUILD SUCCESSFUL** |
| `verify_windshield` | **PASS**：6 玻璃 / 4 拟合作用面 / 4 实体雨刷 |
| `selftest` | **21/21** 注入故障全被抓 |
| 三个真车 zip 整体 SHA256（旧打包器 fa9ab8e vs 现打包器） | HST_H_v12 / SAF101_snd_probe / BR101_v1 **逐字节不变** |
| 进游戏看雨刷只动所在驾驶室 | **待用户验收**（我无法观察画面） |

## 10. 为什么"玩家位置 vs 玻璃位置"可以直接比较（帧约定核对）

§7 的比较依赖一个坐标帧假设。刚因为假设没核对吃过一次亏（§7 本身），所以这次去代码里核对了：

- 骑行 offset 来自 `VehicleRidingMovement` 的 `ridingPositionCache`，而它由 `startRiding` 的
  `x,y,z` 写入，调用方传的是 `RenderVehicles` 第 141 行的
  `final Vector3d playerPosition = absoluteVehicleCarPositionAndRotation.transformBackwards(clientPlayerEntity.getPos(), …)`
  —— **世界坐标反变换进车体坐标系**，所以是**车体局部**的。
- 它被消费的地方是 `getRenderPositionAndRotation`（第 428 行）：把 offset 取负后按被骑车的朝向旋转、
  再加上两车位置之差 —— 这是标准的"车体局部偏移"用法（取负是 MTR 的 180° 翻转约定）。
- 锚点侧：`Anchor.position = toRidingSpace(filePosition)`，而 `toRidingSpace` 就是
  `(x, y, z) -> (-x, y, -z)`（同样的 180° 翻转，文档写明"players, floors and doorways 所在的空间"）。

⇒ **两者同帧、同为车体局部米**，可以直接比较 z。§7 的修法成立。

顺带核对：saf101 的两块玻璃 normal 分别是 `z = -0.959` 与 `+0.959`（各自朝向车体中心），
位置 `z = +7.79 / -7.78`，与"1 = A 端、2 = B 端"一致。

## 11. 门控的 `[MMTR-DBG]` 诊断（为什么必须加、怎么读）

门控（`driverOnBoard`）**无法离线验证**（客户端渲染没有测试台），而它在屏幕上的三种失败长得**一模一样**：
"没判定成在车上"、"判成了但选错驾驶室"、"这节车根本没有风挡锚点"。三者的修法完全不同。
所以在 `render` 里按**变化**打印一行 `[MMTR-DBG] wiper gate <vehicleId>:<car> -> …`：

```
riding=1234 ridingCar=0 panes=2 stalk=SLOW | windshield_1 cab=1 paneZ=7.79 driven=true
                                           | windshield_2 cab=2 paneZ=-7.78 driven=false
```

只在该车的情况**变化时**打印，所以一次测试只有几行。读法：

| 观察到 | 含义 | 下一步查 |
|---|---|---|
| **完全没有** `wiper gate` 行 | 客户端认为**没在车上**（骑行状态没建立） | 是否走进了**开着的门**；`movePlayer` 是否在跑（它同时负责续期行程） |
| `panes=0` | 这节车**没有风挡锚点** | 模型是否真有 `mmtr_windshield_*`；或多节编组时编组车厢号与模型内车厢号不匹配（§8.3） |
| `driven=false`（两块都是） | 判定没通过 | 比 `paneZ` 与 `ridingCar`/玩家 z；`driving=true` 只应有一块 |
| `driven=true` 但刀片不动 | 门控是对的，问题在**绘制/角度推进** | `State.advance` 与 `pushPartTransform`，不在本文件的判定 |

## 12. 目标状态

代码部分（(1) 门控实装 + 每驾驶室判定、(2) 雨滴两处口径修复）已完成并编译通过，
离线判据全绿、三个真车 zip 逐字节不变。**只剩进游戏目视验收**，这一步只能由用户完成。

## 13. 恢复乘车后暴露的一个现实障碍：手动门车没有开门的键

W4 的目视验收要"走进开着的门"上车，而**门怎么开**在清空阶段被改掉了一半：

- **普通车（`isMmtrDoorManual` 为假）**：走 MTR 原路径（`RenderVehicles` 第 180 行起）——
  门在**停靠站台**（靠近站台方块/屏蔽门/自动闸机）时自动打开，玩家可走进门里上车 ✓。
  验收可行。
- **手动门车（`isMmtrDoorManual` 为真）**：门只由 `getMmtrDoorLeft()` / `getMmtrDoorRight()` 决定
  （第 151-174 行，"驾驶室用手开关门"模式），而这两个标志原本由 **Y / U 键**驱动 ——
  `MMTR_DOOR_LEFT/RIGHT` 已随驾驶室层在 notes/185 一并删除，**现在没有任何键能开它们**。

⇒ 手动门车**当前无法上车**，因此也无法做雨刷验收。

**这不是本次改动引入的**，是"删干净再重建"的必然结果：门机构（`MmtrDoorSides`、
`clipToOpenSide`、`isMmtrDoorManual`）都保留着，缺的只是**司机侧的那两个按键**。
包里的 `PacketUpdateVehicleRidingEntities.create(...)` 也仍然带着 `manualToggleDoors` 参数
（`sendUpdate` 目前恒传 `false`），所以管道是通的，缺的只是入口。

**未做**：补回开门键属于"驾驶室层"（本目标范围之外），而且同样无法离线验证。
若验收时发现门打不开，再决定是否补一个最小的手动门键（左/右）。

## 14. §13 的补充更正：手动门车**有**开门办法（指挥命令）

§13 说"没有任何键能开手动门车的门"——**键**确实没有，但**命令**有，所以上车验收是可行的：
`MmtrCommandExecutor`（游戏端）与网页指令栏提供乘务指令，用法见该文件第 282 行的自述：

```
doors <vehicleId> [open|close|toggle] [left|right|both]      # 缺省 toggle / both
（网页指令栏的写法：train doors <id> open / train couple <a> <b> / …）
```

实现在第 424-431 行：调 `vehicle.vehicleExtraData.mmtrSetDoors(action, side)`，**不要求驾驶室、
不要求编组体车**，并且会回报 `L=… R=… 手动=…`（顺便就能看出这辆车是不是手动门车）。
同一行还列出了其它乘务指令：`changeends <id>`、`cab <id> <A|B|out>`、`shunt …`、`couple/uncouple`、`trace`。

⇒ 手动门车打不开门不再阻塞验收：车停稳后用 `doors <id> open` 开门，再走进去上车。
