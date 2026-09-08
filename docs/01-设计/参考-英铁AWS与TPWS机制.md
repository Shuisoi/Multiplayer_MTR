# 参考：英铁 AWS（Automatic Warning System）现实机制

> 目的：为 MMTR 的信号实现保存一份"现实基准"——做任务/信号/道岔集成时，AWS 的行为、
> 触发条件、司机职责与失败后果都以本文为准对照。
> 整理日期：2026-09-08（依据下列公开资料；如有出入以 RSSB/Network Rail 现行规则为准）。

## 0. 定位（一句话）

AWS 是**点式司机告警 + 确认系统**：它**不代替信号、不决定可否通过**，只在列车接近
"可能需减速/停车"的地点（信号显示非绿、限速起点等）时提醒司机，并要求司机**在极短
窗口内确认"我注意到了"**；不确认就**紧急制动直至停车**。它**不能**防止司机"确认后仍
冒进信号"（SPAD）——那由 TPWS 承担。

## 1. 轨道侧设备：每个 AWS 点一对磁铁（两轨之间）

- **永久磁铁（permanent magnet）**：恒定磁场。列车经过只遇永磁 = "clear"（正常通过，
  无警示；并使车载指示复位）。
- **电磁铁（electro-magnet）**：平时**断电**（无磁场）＝ clear；当关联条件成立
  （信号显示险阻 / 限速生效等）时**通电**，与永磁构成"warning"磁场序列 → 车载触发警示。
- **布置位置**：信号机**外方约 200 码（≈183 m）**（也有资料表述为按制动距离+余量），
  保证司机在看清/到达信号前已收到提醒；限速起点同理。AWS 是"二进制"：**非绿即 warning**
  （红 / 单黄 / 双黄 / 限速均触发），绿灯 = clear。
- 来源：[railcar.co.uk - Automatic Warning System](https://railcar.co.uk/technology/protection/automatic-warning-system/)；
  [AHFE 论文（电磁装置位于两轨间、约 200 码）](https://16www.ahfe.org/proceedings/files/AHFE_Transportation_Part_II_Vol.pdf#105#47)；
  [Wikipedia - Automatic Warning System](https://en.m.wikipedia.org/wiki/Automatic_Warning_System)

## 2. 车载侧：警示 → 确认 → 保持/复位

1. 过 warning 磁铁：驾驶室响**喇叭**，同时亮起**黄色"sunflower"指示灯**（提示进入警示）；
2. 司机须在约 **2～2.5 s（一说 2–3 s）内按下确认按钮**；
   - **未确认** → 自动**紧急制动**，列车**一直制动到停稳**（停止前司机无法缓解）；
   - 已确认 → 喇叭停，黄灯变为**稳定黄/已确认指示**（提示：前方确有险阻或限速，按信号/限速操作）；
3. 指示器**保持到复位**：列车通过相应 clear 点（信号转可通过/限速结束的复位磁铁）后熄灭。
4. 另有司机侧**提醒器（reminder）**：站内等信号时可手动挂起"注意"状态，防止动车时遗忘。
- 来源：[Wikipedia AWS](https://en.m.wikipedia.org/wiki/Automatic_Warning_System)；
  [加拿大研究：2.5 s 内按取消键否则自动制动](https://ualberta.scholaris.ca/bitstreams/721292df-e857-42bf-9898-10cf54d8fc68/download#18#4)；
  [BBC：How the safety systems work（1999 事故背景）](http://news.bbc.co.uk/2/hi/uk_news/941526.stm)；
  [Brunel：AWS 人因风险研究](https://bura.brunel.ac.uk/bitstream/2438/1466/1/Assessing_the_human_factors_risks_in_extending_the_use_of_AWS_McLeod_et_al.pdf)

## 3. 触发对象（要挂 AWS 的点）

- 主体信号机（四显示：绿=clear；单黄/双黄/红=warning 的"下一信号可能"逻辑在信号系统，
  AWS 只看**本信号是否非绿**，不看前方占用链）；
- 永久/临时限速起点（含慢行）；
- 其他提示点（如某些危险区）按规则增补。
- 关键：AWS 点**必须放在足够提前**的位置（200 码级），否则失去意义。

## 4. AWS 与 TPWS / LZB 的边界

| 系统 | 类型 | 作用 | 防什么 |
|---|---|---|---|
| AWS | 点式告警+确认 | 提醒司机注意信号/限速 | 无（不制动冒进） |
| TPWS | 点式超速/冒进防护 | 信号前/限速前超速或压过红灯 → 制动 | SPAD、超速冒进 |
| LZB | 连续式列控 | 地面-车载连续传输目标速度/距离，自动监督制动曲线 | 超速、冒进（连续） |
| 联锁/闭塞 | 进路/区段 | 道岔与信号互锁、一区段一车 | 进路冲突、追尾 |

（四显示信号显示规则：绿=前方两个以上闭塞空闲；单黄=下一闭塞空闲、再下一占用；
双黄=下两个空闲、第三个占用；红=下一占用或进路未设——[Network Rail: Four Aspect Signal](https://safety.networkrail.co.uk/jargon-buster/four-aspect-signal/)）

## 5. 与 MMTR 现有 S3 实现的差距清单（待核对/对齐）

MMTR S3 现状：AWS 状态机 NONE/WARN/ACKED，触发提前量 75 m，确认窗口 3000 ms，
镜像字段 pending/acked 已上 HUD 数据面。按现实基准需核对/修正：

1. **触发距离**：现实 200 yd（≈183 m）级；我们 75 m 需要说明或按"制动距离+余量"配置
   （若受线路短限制，需显式可配置并标注与现实的偏差）；
2. **warning 条件**：现实=“关联信号非绿 / 限速生效”；确认 MMTR 的 AWS 点现在挂在哪
   （限速起点？信号状态？）——信号、道岔、任务集成时应把 AWS 触发接到**信号显示状态**
   与**限速对象**，而非仅固定距离；
3. **未确认后果**：现实=窗口内未确认 → 紧急制动直至停稳；核对实现是否一致（窗口
   3000 ms vs 现实 2~2.5 s），以及"停车前不可缓解"语义；
4. **指示器保持到复位**：acked 字段已有；复位（clear 磁铁）后熄灭的字段/行为待补；
5. **提醒器（reminder）**：站内等信号防遗忘，可后置；
6. AWS 不防 SPAD：确认后冒进属司机行为——MMTR 的 protection/SPAD 防护（S4 保护）与
   AWS 的关系要在集成文档里写明（真实世界靠 TPWS；MMTR 低速区如无 TPWS 等效物，需在
   行车控制系统设计中给出答案：靠占用闭塞+保护制动兜底）。

## 6. 低速区"整体行车控制系统"中的 AWS 位置（预告）

按用户路线：信号 × 道岔 × 任务 集成先覆盖**低速区（AWS 制式）整体行车控制**：
- 任务=进路意图（车要去哪、走哪条进路）；
- 联锁按任务为最接近车设进路/扳岔/锁闭；
- 信号=进路×闭塞 输出四显示（英铁）；
- AWS 点=信号/限速的非绿条件 → 司机告警+确认（未确认→制动的实现要按上文对齐）；
- 高速区（LZB）连续列控作为同架构的第二制式并行演进。
