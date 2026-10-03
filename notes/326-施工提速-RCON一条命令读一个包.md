# 326 - 施工慢的根因是我们自己的 RCON 客户端：一条命令读一个包

> 用户口径（2026-09-26）：「话说建造可不可以快一点执行」。

**能，而且快了两个数量级。** 一次完整 `gen_p2p` 施工从 **10–15 分钟 → 31 秒**。

---

## 1. 根因：`rcon.py` 的 `command()` 每条命令都多干两件蠢事

```python
SENTINEL_COMMAND = "help"      # ① 每条命令后再发一个 help 当"响应结束"哨兵
DRAIN_TIMEOUT = 0.35           # ② 然后 sleep 0.35 秒排空
```

* **①** `help` 在服务端是一条**很长**的响应，等于每条命令让服务端干两遍活；
* **②** 1100 条命令 × 0.35 s ≈ **6.4 分钟纯睡觉**（再加上命令本身与重连，就是十几分钟）。

更要命的是：**它还自己把连接搞断**。批量施工日志里那几百次

```
连接中断，重连后继续（第 517 次）: RconError
```

**不是服务端的问题，是哨兵法自己造成的** —— 哨兵的长响应 + 排空逻辑把包流搞乱，
服务端索性关连接。旧代码的注释里写的"this dev server drops rapidly re-established
RCON connections"是**误诊**。

---

## 2. 实测（`sandbox/probe_rcon_speed.py`）

同一台服务端、同 40 条 `setblock`：

| | 每条耗时 | 结果 |
| --- | --- | --- |
| 哨兵法（旧） | ~350 ms | **第 3 条就 `connection closed by server`** |
| **一包法（新）** | **2 ms** | **40/40 正常、零串包**，`seed` 的答案与哨兵法逐字一致 |

⇒ 本服务端**一条命令正好回一个包**，不需要哨兵。

---

## 3. 改法

`Rcon.command()` 改成：

```python
request_id = self._next_id()
self._send(request_id, SERVERDATA_EXECCOMMAND, command)
response_id, _, body = self._recv_packet()
if response_id != request_id:            # 串包 = 立刻报错，不静默
    raise RconError(...)
if len(body) >= 4000:                    # 只有长响应（可能被拆包）才做一次很短的排空
    self._drain(0.02)
return body
```

* 旧实现保留为 `command_with_sentinel()`（**只为考古与对照，别用**）；
* `rcon_batch.run_with_reconnect` 新增 `quiet_ok=True`（只打印出问题的条目）——
  上千行 `OK … / -> Changed the block…` 的日志写盘本身也是可观开销。

**顺带的好处**：`rcon_batch` 里的重连分支现在基本走不到了；施工日志从几万行掉到几十行。

---

## 4. 实测效果

| 场景 | 旧 | 新 |
| --- | --- | --- |
| 单条 CLI（`python sandbox/rcon.py list`） | ~0.4 s | **0.14 s** |
| 200 条命令 | ~70 s | **0.40 s**（2.0 ms/条，重连 0 次） |
| **一次完整 `gen_p2p` 施工**（备份 + forceload + 等生成 + 1100 条 fill + 铺轨 + 摆灯 + 扫描 + 清理） | 10–15 分钟 | **31 秒** |

---

## 5. 教训

1. **"服务端老掉线"要先怀疑自己的客户端**：这次的"几百次重连"完全是自家哨兵造成的。
   判据很简单——**换一条最朴素的实现跑 40 条，看还掉不掉**。
2. **协议边界不确定时，先做探针再决定要不要兜底**：`probe_rcon_speed.py` 同时回答了
   "是不是一包一响应"和"去掉兜底会不会串包"，两个问题一次 0.6 秒测完。
3. 慢不是"服务端就这样"，是**我们每条命令都在等一个固定的 sleep**。
