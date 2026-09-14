package org.mtr.core.mmtr.plan;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;

/**
 * P4：**从轨图算走行时间**（{@link MmtrTravelTimes} 的真实实现）。
 *
 * <p>设计把"走行时间"划在生成器之外（§2.1 ②：生成器是纯函数、不认识世界），于是这一层就是
 * 那条边界的世界侧：站间里程（{@link MmtrRailDistance}，Dijkstra 沿轨算）÷ 速度 + 站停。
 * 换一张地图、换一版限速，只有这里的数字变，趟次表/交路的结构不变。</p>
 *
 * <p><b>速度取哪一条</b>：编组的 `maxSpeedKmh`（{@code MmtrFleet.ConsistSpec}）——
 * 时刻表按"这趟车能跑多快"算，而不是按轨道限速；轨道限速是**执行期**的事（信号/限速进 min 链），
 * 计划期用编组能力是现实里排点的做法（跑得快就排得紧）。</p>
 */
public final class MmtrRailTravelTimes implements MmtrTravelTimes {

	/** 终点处理默认时长（换端/掉头；设计 §4 的线路模型没有这个字段，先给一个可读的默认值）。 */
	public static final long DEFAULT_TERMINAL_MILLIS = 3L * 60 * 1000;

	private final Simulator simulator;
	private final MmtrLine line;
	private final double speedKmh;
	private final long terminalMillis;
	/** 逐段里程缓存（站序号 → 里程）；{@code -1} = 这一段在轨图上走不到。 */
	private final double[] legMeters;

	private MmtrRailTravelTimes(Simulator simulator, MmtrLine line, double speedKmh, long terminalMillis) {
		this.simulator = simulator;
		this.line = line;
		this.speedKmh = speedKmh;
		this.terminalMillis = terminalMillis;
		this.legMeters = new double[Math.max(0, line.stops.size())];
		computeDistances();
	}

	/** 按编组速度造一份（速度 &lt;= 0 时用 {@link #DEFAULT_SPEED_KMH}）。 */
	public static MmtrRailTravelTimes of(Simulator simulator, MmtrLine line, double speedKmh) {
		return new MmtrRailTravelTimes(simulator, line, speedKmh <= 0 ? DEFAULT_SPEED_KMH : speedKmh, DEFAULT_TERMINAL_MILLIS);
	}

	/** 默认编组速度（km/h）：车底没写速度时的兜底，与 MTR 列车的常见速度同量级。 */
	public static final double DEFAULT_SPEED_KMH = 80;

	/**
	 * 把相邻两站的里程算出来（起点站那条是 0）。
	 *
	 * <p>只算**相邻**站：这条线路的站序就是它的走行顺序，站与站之间的最短里程就是这一段 ——
	 * 不需要整条线路的路径规划（那是执行期的事，还要看道岔在不在位）。</p>
	 */
	private void computeDistances() {
		for (int i = 0; i < line.stops.size(); i++) {
			legMeters[i] = 0;
		}
		for (int i = 1; i < line.stops.size(); i++) {
			final Rail from = MmtrRailDistance.railOfPlatform(simulator, line.stops.get(i - 1).platformId);
			final Rail to = MmtrRailDistance.railOfPlatform(simulator, line.stops.get(i).platformId);
			legMeters[i] = from == null || to == null ? -1 : MmtrRailDistance.between(simulator, from, to);
		}
	}

	@Override
	public long legMillis(int fromStopIndex, int toStopIndex) {
		if (fromStopIndex < 0 || toStopIndex < 0 || fromStopIndex >= legMeters.length || toStopIndex >= legMeters.length) {
			return 0;
		}
		final int index = Math.max(fromStopIndex, toStopIndex);
		final double meters = legMeters[index];
		if (meters < 0) {
			// 轨图上走不到（站序与轨图对不上）：给 0 而不是编一个数字 —— 计划会显得很快，
			// 但那是**看得出来的错**（趟次表里这一段是 0 分钟），比一个编出来的假数字好排查。
			return 0;
		}
		return MmtrTravelTimes.millisForDistance(meters, speedKmh);
	}

	@Override
	public long terminalMillis(MmtrLine.TerminalTreatment treatment) {
		return terminalMillis;
	}

	/** 某一段的里程（诊断/接口用；{@code -1} = 走不到）。 */
	public double legMeters(int stopIndex) {
		return stopIndex >= 0 && stopIndex < legMeters.length ? legMeters[stopIndex] : -1;
	}

	/** 走不到的段（诊断：站序与轨图对不上时要能一眼看出来）。 */
	public @Nullable String describeMissingLegs() {
		final StringBuilder out = new StringBuilder();
		for (int i = 1; i < line.stops.size(); i++) {
			if (legMeters[i] < 0) {
				out.append(out.length() == 0 ? "" : "、")
					.append(line.stops.get(i - 1).platformId).append("→").append(line.stops.get(i).platformId);
			}
		}
		return out.length() == 0 ? null : "轨图上走不到的站间：" + out;
	}

	@Override
	public String toString() {
		return "走行时间（轨图）" + line.lineId + " @" + Math.round(speedKmh) + "km/h";
	}
}
