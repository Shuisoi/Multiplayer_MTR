package org.mtr.core.mmtr.command;

import org.mtr.core.data.Depot;
import org.mtr.core.data.Siding;
import org.mtr.core.simulation.Simulator;

/**
 * 从 {@code org.mtr.core.simulation} 包外查找车辆段 / 股道的小工具。
 *
 * <p>指令包与 {@code Simulator} 不在同一个包，所以 {@code Simulator} 里那些"按 id 找股道"的动作
 * 需要一个公开入口。放在这里而不是直接写在 {@code Simulator} 上：查找逻辑属于指令层，
 * 而 {@code Simulator} 保持只做"动作"（生成、重读、落盘）。</p>
 */
public final class MmtrCommandLookup {

	private MmtrCommandLookup() {
	}

	/** 按 64 位 id 找股道；找不到返回 null。 */
	public static @org.jspecify.annotations.Nullable Siding findSiding(Simulator simulator, long sidingId) {
		for (final Siding siding : simulator.sidings) {
			if (siding.getId() == sidingId) {
				return siding;
			}
		}
		return null;
	}

	/** 按 64 位 id 或名字找车辆段；找不到返回 null。 */
	public static @org.jspecify.annotations.Nullable Depot findDepot(Simulator simulator, String idOrName) {
		return MmtrCommandDispatcher.findDepot(simulator, idOrName);
	}
}
