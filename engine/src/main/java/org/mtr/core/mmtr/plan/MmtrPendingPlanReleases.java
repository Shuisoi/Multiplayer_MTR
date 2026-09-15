package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.function.Predicate;

/**
 * **要收回、但还没收回的计划任务**（notes/153）。
 *
 * <p>为什么不能"当场就收"：计划一变（改密度/事件重算/手工指派），新计划里可能已经没有某台车
 * 正在跑的那一步了 —— 那一步该收回。但它可能**正开在咽喉里**：当场把活撤掉，车就停在那儿，
 * 把后面的车全挡住（现场实测：一台被撤活的车停在咽喉区间里，后面那台开到站台前被它挡在区间外）。</p>
 *
 * <p>所以收回分两步：**先记账，等它停稳再收**。判据只有一条 —— 车还在动就先不动它；
 * 车停稳了（或在整备位上）就立刻收，且**只收一次**。</p>
 *
 * <p>这一件单独成类是为了能被钉住：它的行为就是"记账 → 每次 tick 问一句在不在动 → 收掉能收的"，
 * 用假的数据就能逐条断言（见 {@code MmtrPlanDispatcherTests}）。</p>
 */
public final class MmtrPendingPlanReleases {

	/** {@code [车辆 id, 任务 id, 记账时刻]}；同一个 (车, 任务) 只记一条。 */
	private final ObjectArrayList<String[]> pending = new ObjectArrayList<>();

	/**
	 * 记一笔"这一步要收回"。
	 *
	 * @return 是否新记了一笔（已经在账上 = false，不重复记）
	 */
	public boolean defer(String vehicleId, String taskId, long nowMillis) {
		if (vehicleId == null || vehicleId.isEmpty() || taskId == null || taskId.isEmpty()) {
			return false;
		}
		for (final String[] entry : pending) {
			if (entry[0].equals(vehicleId) && entry[1].equals(taskId)) {
				return false;
			}
		}
		pending.add(new String[]{vehicleId, taskId, String.valueOf(nowMillis)});
		return true;
	}

	/**
	 * 取出**现在就能收**的那些（车已经不在动了），并从账上划掉。
	 *
	 * <p>没轮到的留在账上，下一次 tick 再问 —— 这就是"等它停稳再收"的全部实现。</p>
	 *
	 * @param vehicleStillInTransit 车是不是还在动（由调用方问世界）
	 */
	public ObjectArrayList<String[]> claimReleasable(Predicate<String> vehicleStillInTransit) {
		final ObjectArrayList<String[]> out = new ObjectArrayList<>();
		for (int i = pending.size() - 1; i >= 0; i--) {
			final String[] entry = pending.get(i);
			if (!vehicleStillInTransit.test(entry[0])) {
				out.add(entry);
				pending.remove(i);
			}
		}
		return out;
	}

	/** 账上有几笔（诊断/用例用）。 */
	public int size() {
		return pending.size();
	}

	/** 车已经不存在 / 任务已经不是它的了：把账划掉（不该一直挂着）。 */
	public void forget(String vehicleId, String taskId) {
		pending.removeIf(entry -> entry[0].equals(vehicleId) && entry[1].equals(taskId));
	}
}
