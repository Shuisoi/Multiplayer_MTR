# 27 - 道岔系统 P1：方向感知发现与形态分类（本会话）

> 设计文档：docs/01-设计/道岔系统-方向感知与多级控制-设计.md（R1/R2/R3 + P1-P5）。
> P1 = R1 的方向感知抽象地基（合成网可测），运行时裁决未动（P3 再接 authority 排序）。

## 改动（additive）
- 新增 org.mtr.core.mmtr.point.MmtrPoint（方向感知岔口）：
  - 每 (node, 进向轨)：按进向方向枚举 distinct 续轨（排除来轨自身/退化几何）；
  - LegKind=STRAIGHT(cos≥0.9) / LEFT / RIGHT(|cos|<0.5 按 2D 叉积符号) / OTHER(斜交)；
  - 确定性排序：straight>left>right>other，同类按 cos 降序、hex 兜底（消除 map 迭代序不稳定）；
  - Form：PASS_THROUGH(1)/FORK(2 含直)/TEE(2 无直=丁字)/MULTI(≥3=交叉等)/DEAD_END(0，发现时剔除)；
  - branch0/1 = leg0/leg1（与旧语义兼容的取法）；discoverDirectionAware() 全图发现。
- 代码事实确认：MTR Rail 无显式"单向"位（仅双向端点+方向限速），故 P1 以"进向相关推导"建模，
  为未来 one-way provider 留过滤缝（设计文档 §8）。
- 运行时（legacy discover/elect/point-op/store）零改动。

## 测试（MmtrPointDirectionTests，4 例全绿）
1. T 丁字（竖进横线）：恰 2 续向 LEFT/RIGHT、无 STRAIGHT、form=TEE、branch0=左、branch1=右；
2. 交叉 X：西进=直通(东)+两垂直轨，3 legs form=MULTI，leg0=直通；北进角色旋转（南=直通）；
3. 直通非岔：1 leg STRAIGHT、PASS_THROUGH、branch1=null；
4. 标准分叉：leg0=直、leg1=分叉；重复发现顺序稳定。

## 回归
- 全量：point-p1-full.log（基线 248/0/2，纯新增，零新增失败）。
- 下一步 P2（web 搬岔 UI，可用 mmtr-points 扩展输出）与 P3（MmtrPointAuthority 多级控制 +
  legs 排序接入运行时 + request/grant/release；planner 改经 authority 申请）。
