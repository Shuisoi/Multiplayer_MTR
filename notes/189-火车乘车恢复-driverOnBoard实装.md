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
