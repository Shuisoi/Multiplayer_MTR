# 64 - 换向器（REV）：倒挡（同一驾驶室反向行驶）

> 用户口径（2026-09-09）：红线 `748a349`「火车不能倒车」**是服务器玩家规则**，不是物理限制；
> "实际上这车爱怎么开怎么开"。本片把 `ControlState.reverser` 真正接进 Motion Core。
> 引擎侧改动 + 2 个用例；无模组侧改动（客户端 HUD/按键早就有 `R` 显示与 ←/→ 换向）。

## 语义

| 换向器 | 含义 |
| --- | --- |
| `1` | 车头在前（操纵端朝向即为行进方向，原行为） |
| `0` | 中立：无牵引（原行为的一部分） |
| `-1` | **车尾在前**：同一个驾驶室、同一个司机，列车反向行驶（`REV`） |

- 行进方向 = 操纵端朝向 **XOR** 换向位（`MmtrConsistWalker.towardB()`）；
- **换向只在停稳时落地**：运行中拉换向器 → 记 `mmtrReverserPending`、牵引被切（`wantPower=false`），
  列车滑行/制动到停，再自动换向。这是真实换向器的机械联锁，也是唯一不会让运动瞬间反向的做法；
  用户侧观感 = "拉换向器 → 车停 → 反着走"，没有报错、没有规则拦截；
- 换向后与换端同一套收尾：清 plan / 停点 / 岔权请求（都按旧方向算），重发编组镜像（同轨反向顺序、
  同一 `railProgress`），**车体不动（I1）、几何不动（I2）**；
- legacy 单点行走器（非编组体）保持旧行为（`reverser > 0` 才给牵引）——它没有反向能力，既有测试不受影响。

## 改动

| 位置 | 改动 |
| --- | --- |
| `MmtrConsistWalker` | 新增 `travelReversed` + `setTravelReversed()` / `travelReversed()` / `travelsTowardB()`；内部 10 处方向判断全部收口到 `towardB()`；`syncDirectionFlags()` 现在同时看驾驶室与换向位（任一变化就作废 `endOfLine`/`atTarget`） |
| `Vehicle.applyMmtrControl` | 删掉"强行 `reverser=1`"（红线实现）；编组体上按停稳/运行分别**立即生效**或**挂起** |
| `Vehicle.simulateMmtrMotion` | 开头在停稳时落地挂起的换向；`wantPower` 改为"编组体看 `reverser != 0`，legacy 看 `> 0`"，且挂起期间不给牵引 |
| `Vehicle` 镜像/快照 | 三处 `cabs().travelsToward(End.B)` 改用 `walker.travelsTowardB()`，否则镜像朝向会忽略换向 |

## 用例（`MmtrConsistVehicleMotionTests`）

| 用例 | 断言 |
| --- | --- |
| `reverserDrivesTheConsistTailFirstFromTheSameCab` | 端 2 开一段 → 停稳 → 拉换向器：驾驶室仍是 CAB_B、前后端互换（I1）、车没动；再给油门 → `distanceM` 继续增长（反向行驶） |
| `reverserChangeWhileRollingWaitsForTheStand` | 运行中"拉换向器 + 制动"：方向**未**变（挂起）；停稳后自动换向，且 `distanceM` 不减少（I3） |

## 验证

- `MmtrConsistVehicleMotionTests` 12/12；
- 全量引擎套件：本片零新增失败（见提交说明）。

## 文档要回填

红线表 `docs/01-设计/编组构建-连挂单元与连挂端点-设计.md` §9 与
`docs/01-设计/运动系统-编组体与双驾驶室换端-设计.md`（§62/§323、不变量 I3）里"`reverser` 继续忽略 /
火车不能倒车"的表述，应改成：**引擎支持换向（REV）；"不许倒着开"属于服务器玩家规则层**。
