package org.mtr.core.mmtr.duty;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;

/**
 * **钥匙兜底网**（notes/411）：只登记"真的丢过钥匙、而且车上还挂着在跑的自动任务"的车，
 * 然后按**有界重试**把引擎占位钥匙补回来。
 *
 * <h2>为什么不每 tick 扫全车队</h2>
 *
 * <p>"编组无人 ⇒ 没有车头 ⇒ 自动任务永久停摆"是个**低频事件**（只发生在钥匙被拔走的那一刻），
 * 而每 tick 遍历所有车辆是**稳态固定成本** —— 那个仓的探针纪律（notes/337）对"每 tick 花一点"
 * 这类成本是明确反对的：卡顿常常是稳态慢，不是尖峰。</p>
 *
 * <p>于是这里按"事件登记 + 有界重试"做：</p>
 * <ul>
 *   <li><b>登记</b>：{@code Vehicle.leaveMmtrCab}（钥匙真的从车上出去的那一刻）与
 *       "交还给自动"（{@code MmtrDutyRegistry.handBackToAutopilot}）各登记一次。
 *       一次登记 = 往小表里塞一个 long；车没在跑自动任务就不登记。</li>
 *   <li><b>重试</b>：每 {@value #RETRY_TICKS} tick 才真的看一眼表（稳态 = 一次自增 + 一次取模 +
 *       一次 {@code isEmpty}，表空就返回）。司机拔钥匙时车可能还在滚，所以"还在动"只是**继续等**，
 *       等它停稳的那一拍才补钥匙（{@code insertSystemKey} 的前提就是车停了）。</li>
 *   <li><b>有界</b>：表最多 {@value #MAX_ENTRIES} 条；每列车最多重试 {@value #MAX_RETRIES} 次
 *       （≈{@value #RETRY_TICKS}×{@value #MAX_RETRIES} tick）后记一条并放弃，绝不无限重试刷屏。</li>
 * </ul>
 *
 * <p>出表条件有三个，各自对应一种"问题已经不存在"：车没了、任务结束/换成玩家执行、钥匙回来了。
 * 这张表**永远不该长期非空** —— 非空就说明有车丢了钥匙正在等停稳（或补不进去，那会有日志）。</p>
 */
public final class MmtrAutoKeyWatch {

	/** 表上限：正常永远是 0–1 条；到上限说明出现了本不该有的并发丢钥匙。 */
	private static final int MAX_ENTRIES = 64;
	/** 每这么多 tick 才真的看一眼（20TPS ⇒ 250 ms）。 */
	private static final int RETRY_TICKS = 5;
	/** 每列车最多重试这么多次（20TPS ⇒ 10 s：够从 120 km/h 停稳）后放弃并记一条。 */
	private static final int MAX_RETRIES = 40;

	/** 车 id → 已经重试了几次。表空 = 一切正常。 */
	private final Long2IntOpenHashMap watched = new Long2IntOpenHashMap();
	private long ticks;

	/** 登记一列车（可重复登记；重复只是把重试计数清回 0）。 */
	public void watch(long vehicleId) {
		if (watched.size() >= MAX_ENTRIES && !watched.containsKey(vehicleId)) {
			return;
		}
		watched.put(vehicleId, 0);
	}

	/** 表里还有几列车在等（诊断/用例用）。 */
	public int size() {
		return watched.size();
	}

	/** 停机/清世界时清表。 */
	public void clear() {
		watched.clear();
	}

	/**
	 * 每 tick 调一次（{@code Simulator.tick} 里，紧挨着值守状态机）。
	 * 稳态成本：一次自增 + 一次取模 + 一次 {@code isEmpty}。
	 */
	public void tick(Simulator simulator) {
		ticks++;
		if (watched.isEmpty() || ticks % RETRY_TICKS != 0) {
			return;
		}
		final LongArrayList done = new LongArrayList();
		for (final long vehicleId : watched.keySet()) {
			final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
			if (vehicle == null || !vehicle.mmtrAutoMissionLive()) {
				done.add(vehicleId);   // 车没了，或任务终态/换成玩家执行 ⇒ 不在范围内
				continue;
			}
			if (vehicle.mmtrEnsureSystemKeyForAutoMissionStuck()) {
				done.add(vehicleId);   // 补上了（这一条自己会打 [MMTR-KEY]）
				continue;
			}
			final int retries = watched.get(vehicleId) + 1;
			if (retries >= MAX_RETRIES) {
				done.add(vehicleId);
				System.out.println("[MMTR-KEY] 车 " + vehicleId + "：丢了钥匙、挂了自动任务，但 " + (RETRY_TICKS * MAX_RETRIES / 20)
					+ " 秒内没能补回来（车还在动 / 插不进钥匙）—— 放弃重试，请人工看一眼");
				continue;
			}
			watched.put(vehicleId, retries);
		}
		for (int i = 0; i < done.size(); i++) {
			watched.remove(done.getLong(i));
		}
	}
}
