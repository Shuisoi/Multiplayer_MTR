# 89 - 联锁诊断指令 interlock（实机对照物）

> 目的：实机目视验收时，让"引擎认为信号该显示什么"变成**可打印、可对照**的东西，而不是让人去读 JSON feed。

## 1. 交付

| 件 | 内容 |
| --- | --- |
| `MmtrInterlockReport`（引擎） | `describe(sim, vehicleId)` / `describeAll(sim)`：一行车况 + 逐个道岔（要的腿、是否已越过、`lock/holder/queue`）+ 进路每条轨的**显示** + **推给客户端的收窄** + PENDING 入口集合 |
| `MmtrCommandExecutor`（fabric） | 新 OP 指令 `interlock <vehicleId> \| interlock all`，结果写进指令日志（网页指令栏可见）；帮助行同步 |
| 03 实机文档 §9 | 清单里加入"用 `interlock` 对照"这一步 |

## 2. 实机输出（真实存档，运行中的 dev 服务端）

```
> interlock -6167647060477028947
[interlock] vehicle=-6167647060477028947 rail=FFFFFFB1 next=FFFFFF77 route=MAIN/SET target=FFFFFFB1 rails=6 forks=3
  turnouts:
    -170,-60,-137 via=FFFFFF77 wantLeg=0 待过 | lock=false holder=v-6167647060477028947@0 until=…
    -170,-60,-161 via=FFFFFF77 wantLeg=1 待过 | lock=false holder=v-6167647060477028947@1 until=…
    -154,-60,-139 via=FFFFFF75 wantLeg=1 待过 | lock=false holder=v-6167647060477028947@1 until=…
  aspects: FFFFFFB1=RED FFFFFF77=SINGLE_YELLOW FFFFFF77=DOUBLE_YELLOW FFFFFF75=DOUBLE_YELLOW FFFFFF86=SINGLE_YELLOW FFFFFFB1=RED
  mirror(客户端收窄): FFFFFFB1->FFFFFF77 FFFFFF77->FFFFFF77 FFFFFF77->FFFFFF75 FFFFFF75->FFFFFF86 FFFFFF86->FFFFFFB1
  pendingEntryRails: 无
```

这份真实输出同时印证了三件事：

1. **S5**：一条 MAIN 进路 SET、三个道岔全部由该车持有（`holder=v…@leg`），说明原子进路锁在真实网络上成立；
2. **A2**：进路各轨显示沿闭塞链变化（车所在轨 RED、前方单黄/双黄），`mirror` 给出客户端收窄；
3. **notes/87 的折返修复在实机生效**：该进路把 `FFFFFF77` 走了两次，`mirror` 为它给出了**两条**候选
   （`FFFFFF77->FFFFFF77` 与 `FFFFFF77->FFFFFF75`），客户端可据此按方向挑对那一程。

`interlock all` 在无进路时输出：`[interlock] 当前没有任何进路（所有信号按占用链显示）`。

## 3. 用例与验证

- `MmtrInterlockReportTests`：无进路时报告 `route=none` 并说明自由驾驶规则；派车后报告含
  `route=MAIN/SET`、`turnouts:`、`wantLeg=`、`lock=/holder=`、`aspects:`、`mirror(客户端收窄):` 与 `->`；
  `describeAll` 计数并点名列车；未知 id 返回 `找不到车辆` 而不抛异常。
- 全量引擎套件 **472/0/2**（上一轮 471/0/2，+1 例）；`:fabric:compileJava` SUCCESS。
- 端到端：经 `mmtr-command` 推送 → fabric 指令执行器执行 → 指令日志读回（HTTP 全程可验证）。

## 4. 目标剩余

游戏内目视：现在可以「看灯 + 同时 `interlock <id>` 对照」。分岔按进路放行 / PENDING 压红 /
AWS 黄灯响与绿灯复位 / 未确认 SPAD 仍需人工驾驶观察。
