# 282 - 单手柄车底的 HID：Y 轴直接分配到那唯一一根杆

> 用户口径（2026-09-25）：**「控制器肯定是 Y 轴直接分配到单手柄上就行了呗」**。
> 起因：把两列车派了作业单之后，用户推杆车不动，引擎每 2 s 报一次
> `手柄语义不匹配：本车底是 NOTCHED（consist:saf420cab_a|saf420_motor|true|decl），没有三手柄规格：
> 接收到的油门手柄/定速不驱动它（油门手柄=-97 定速=160）`。

## 1. 真因：HID 那条路**要求三手柄规格**，而单手柄车底**没有**规格

`MmtrHidInput.poll(ThreeHandleSpec spec)` 一进来就 `ensureJoystick(spec)`，然后按 spec 解释 X/Z。
而 `saf420_motor` / `saf420_trailer` 的 `controlMode` 是 **NOTCHED**（配置注释：**单手柄 P5+B8+EB**）、
**没有 `handles` 规格** ⇒ 客户端**根本读不出杆位**。用户手柄的 Y 轴（油门 −97）被送到引擎，
但引擎按"这车是 NOTCHED、没有三手柄规格"的口径**正确地把 `driveHandle/cruise` 忽略了** ——
两边都没错，是**缺一条映射**。

对照：`br101_three_handle` 是 `THREE_HANDLE`，所以**同一只手柄开 BR101 是好的、开 SAF420 没反应**
（用户上一个作业单 `TT-LOOP-1-3` 用的正是 BR101，所以那次能开）。

## 2. 改法：单手柄 = 一根杆走完全程，Y 轴分段映射

新增纯函数 `MmtrHidMapping.singleHandleFromAxis(axis, powerNotches, brakeNotches, deadzone)`
（放引擎包：与 `driveHandleFromAxis` / `brakePositionFromAxis` 同址，纯函数、可单测）。

**为什么不复用 `driveHandleFromAxis`**：两者量程的**含义**不同 ——

| | 三手柄 | 单手柄 |
| --- | --- | --- |
| Y 轴 | 「牵引 ↔ 电阻制动」**对称镜像**（±97，0 = 关闭） | 一根杆走全程：正 = 牵引 1..P、0 = 关闭、负 = 制动 1..B、**再往外一格 = 紧急位** |
| 制动 | 另一根杆（Z 轴，11 位） | 同一根杆的负侧 |

所以档位按**各自侧的实际档数分段**映射，不能按比例硬套。

分段规则（`axis` 为 −1…+1，调用方已按 `invertY` 归一为"推到底 = 满牵引"）：

| 条件 | 结果 |
| --- | --- |
| `abs(axis) <= deadzone` | `0`（关闭位，中央吸附） |
| `axis > 0` | `round(axis × P)` 钳到 `1..P` |
| `axis < 0` 的中间段 | `-round(|axis| × B)` 钳到 `-B..-1` |
| `axis <= -(1 - deadzone)` | `-(B+1)` = **紧急位** |

**为什么紧急位单独占最后一段**（而不是按比例给 `-B`）：与 `brakePositionFromAxis` 把两端留给
"运行/EB"同一个道理 —— **紧急位必须能盲推到底**。SAF420 是 P=5、B=8 ⇒ 值域 `[-9, +5]`。

## 3. 客户端的接线（两处）

1. `MmtrHidInput` 新增 **spec-free** 读法 `pollSingleHandleAxis()`：只取 Y 轴（与 `poll` **同一套**
   轴号/反转/设备选定），反转在这里归一，返回 `(axis, deadzone)`。`ensureJoystick`/`logDevice`
   的 spec 参数改成 `@Nullable` —— 单手柄车底没有 spec，日志改说**生效的是哪一种映射**
   （同一只手柄在三手柄车上读 X/Y/Z、在单手柄车上只读 Y；不写清楚的话"推 Z 轴没反应"看起来像手柄坏了）。
2. `MmtrDriveInput.applyKeyDeltas()` 的单手柄分支：**有杆就把键盘让开**。
   单手柄车的 X/Z 在这类车底上不存在（没有定速杆、没有独立制动杆），所以手柄在位时它就该是
   **唯一**的杆位来源 —— 否则键盘的一次 ±1 会被下一拍的绝对轴值抹掉，表现又是"按了没反应"。
   没插手柄时退回原有的键盘增量路径，**纯键盘零回归**。

> 三手柄那条路**一个字都没动**：它仍然是"键盘增量叠在绝对轴值之上"（既有行为）。

## 4. 验证

**做到（离线实测，用真引擎 jar 跑真代码）**：

```
P=5 B=8  ->  valid singleNotch range = [-9, +5]
  axis= -1.00 ->  -9 紧急EB     axis=  0.00 ->   0 关闭
  axis= -0.95 ->  -9 紧急EB     axis=  0.40 ->   2 牵引2/5
  axis= -0.50 ->  -4 制动4/8    axis=  1.00 ->   5 牵引5/5
  axis= -0.10 ->  -1 制动1/8
checks: +1→P ✓  -1→EB ✓  0→关闭 ✓  死区内→关闭 ✓  不越 +P ✓  不越 -(B+1) ✓  制动侧不会给 -0 ✓
```

- 单测程序直接 `-cp game/libs/…jar` 编译运行（真类，不是复刻）。
- `check-java-compile.ps1` → **OK - 450 source file(s), 663 class file(s)**（比之前多 1 个内部类，
  即 `MmtrHidInput$SingleHandleAxis`）。
- 引擎 jar 已 `sync-engine.ps1` 同步（新方法 `singleHandleFromAxis` 用 `javap` 在 jar 里确认过），
  `:fabric:compileJava` 成功，服务端 `Done (2.736s)`、启动零错误。

**没做到（必须你上手推杆才算数）**：

- 你的手柄**实际推到哪个位置给哪一档**（各家摇杆行程/中位不同；方向反了用 `-Dmmtr.hid.invert.y=true`）。
- `-Dmmtr.hid.axis.y` 在你那只 `ShuisoiContorller` 上是否真的是轴 1（日志会打出生效映射）。
- 单手柄"有杆就让开键盘"这个取舍的手感（想同时用键盘微调的话，这条要改口径）。

## 5. 排障口诀（这类"推杆不动"）

按这个顺序看，别猜：

1. 日志有没有 `收到操纵` → 有 ⇒ 包到了，问题在**引擎侧的语义**；没有 ⇒ 客户端没发（占位/未接管）。
2. 有没有 `手柄语义不匹配` → 有 ⇒ **车底 controlMode 与手柄制式不匹配**（本条笔记就是这一族）。
   看它点名的那串 `consist:<stockId>|<typeId>|…`，再去 `mmtr-consist-types.json` 对 `controlMode`。
3. 有没有 `操纵被拒` → 有 ⇒ 占用锁/司机位（notes/216 与 `Simulator.removeClient` 的 `stopRiding`；
   实测 2026-09-25：**旧的引擎 jar 缺这个修复**，会让"钥匙交不出去、之后谁都开不了这列车"）。
4. **`game/libs` 的引擎 jar 是否比 `engine/build/classes` 旧** —— `check-java-compile.ps1` 会点名警告。
   `runServer`/`runClient` **不会**替你重新同步 jar；改了 `mmtr/engine` 又忘了同步，症状就是
   "代码是对的、游戏里是旧的"，而且类型检查会给假绿灯（它编的是源码，不是运行的那份 jar）。
