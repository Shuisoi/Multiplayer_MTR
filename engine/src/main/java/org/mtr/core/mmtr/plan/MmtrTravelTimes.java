package org.mtr.core.mmtr.plan;

import org.jspecify.annotations.Nullable;

/**
 * P2：**走行时间从哪来** —— 生成器的纯函数核心不认识轨图，所以由调用方把"跑一段要多久"喂进来。
 *
 * <p>这条分界是刻意的（设计 §2.1 ②）：{@code MmtrServicePlan} / {@code MmtrDiagram} 是纯函数，
 * 可离线跑、可单测、可重放；走行时间是世界属性（轨图 + 编组 + 限速），由服务层在调用时给。
 * 于是同一份输入在任何世界里都能算出"结构一致"的计划，而"具体几点几分"取决于当时的线路。</p>
 *
 * <p>P3 会用 {@code MmtrRunPlanner} 的距离 + 编组最高速度造一个真实实现；P2 的用例用
 * {@link #uniform} 造一个等速的假世界。</p>
 */
public interface MmtrTravelTimes {

	/**
	 * 相邻两站（按站序索引）之间的走行时间。
	 *
	 * @param fromStopIndex 起点站序号（0 基，按 {@link MmtrLine#stops} 的顺序）
	 * @param toStopIndex   终点站序号；等于 {@code fromStopIndex + 1}（往程）或 {@code fromStopIndex - 1}（返程）
	 * @return 走行时间（ms）；{@code <= 0} 视为"没有走行时间"，调用方按 0 处理
	 */
	long legMillis(int fromStopIndex, int toStopIndex);

	/** 终点处理耗时（换端 / 灯泡线掉头 / 回库）。环线不处理终点，"继续"就是 0。 */
	long terminalMillis(MmtrLine.TerminalTreatment treatment);

	/** 等速假世界：每段一样长、终点处理固定时长（用例与"还没接轨图"时的兜底）。 */
	static MmtrTravelTimes uniform(long legMillis, long terminalMillis) {
		return new MmtrTravelTimes() {
			@Override
			public long legMillis(int fromStopIndex, int toStopIndex) {
				return Math.max(0, legMillis);
			}

			@Override
			public long terminalMillis(MmtrLine.TerminalTreatment treatment) {
				return Math.max(0, terminalMillis);
			}
		};
	}

	/** 只按里程给的一段走行时间（P3 用它把轨图距离折成时间）。 */
	static long millisForDistance(double meters, double speedKmh) {
		if (meters <= 0 || speedKmh <= 0) {
			return 0;
		}
		return Math.round(meters / (speedKmh / 3.6) * 1000.0);
	}

	/** 该站序是否存在（越界时生成器按 0 处理，不抛）。 */
	static MmtrLine.@Nullable Stop stopAt(MmtrLine line, int index) {
		return index >= 0 && index < line.stops.size() ? line.stops.get(index) : null;
	}
}
