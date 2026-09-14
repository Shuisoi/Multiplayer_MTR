package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * P2：**服务计划**（{@code 任务系统-线路派生与车底交路-设计.md} §3 / §5.1）。
 *
 * <p>服务计划是**与车无关**的那一半：乘客看的趟次表。它只为一条线路回答"几点几分从起点发一趟、
 * 沿途各站几点到几点开、到终点怎么处理"。车底怎么套、哪辆车跑哪一趟是 {@code MmtrDiagram}（P3）
 * 的事 —— 两段式模型的全部意义就在这里：**替补顶替只改第二层，时刻表一行不动**。</p>
 *
 * <h3>v1 的范围（明确写下来，免得后人以为是漏了）</h3>
 * <ul>
 *   <li><b>只生成起点方向的发车序列</b>（{@link Trip.Direction#OUT}）。反方向的时刻表是它的镜像
 *       （同样的间隔、偏移一个往程走行时间），乘客视图由 P6 的 WEB 页呈现；
 *       车辆"出去再回来"的返程在 {@link #ringMillis} 里已经算进周转，交路层（P3）负责把它排进车底序列。</li>
 *   <li>发车槽位按**左闭右开**的密度段展开（{@code [from, to)}），所以 08:30 这种边界时刻
 *       **只属于后一段**（不重不漏，P2 验收 ②）。</li>
 *   <li>时刻一律是**当天毫秒**，与输入层同一口径。</li>
 * </ul>
 */
public final class MmtrServicePlan {

	/** 一趟车的一次停站：到点、开点（开点 = 到点 + 停站时长）。 */
	public static final class StopTime {
		public final int stopIndex;
		public final long stationId;
		public final long platformId;
		public final long arrivalMillis;
		public final long departureMillis;

		StopTime(int stopIndex, long stationId, long platformId, long arrivalMillis, long departureMillis) {
			this.stopIndex = stopIndex;
			this.stationId = stationId;
			this.platformId = platformId;
			this.arrivalMillis = arrivalMillis;
			this.departureMillis = departureMillis;
		}

		public long dwellMillis() {
			return departureMillis - arrivalMillis;
		}

		@Override
		public String toString() {
			return stationId + "/" + platformId + " " + MmtrPattern.hhmm(arrivalMillis) + "→" + MmtrPattern.hhmm(departureMillis);
		}
	}

	/** 一趟车（一个方向的一次运行）。 */
	public static final class Trip {
		public enum Direction {
			/** 起点 → 终点（本层生成的就是它）。 */
			OUT,
			/** 终点 → 起点（镜像时刻表，交路层按返程使用）。 */
			BACK
		}

		public final String tripId;
		/** 车次序号（1 基，按发车先后）。 */
		public final int sequence;
		public final Direction direction;
		public final long departureMillis;
		/** 终点处理方式（环线 = 不处理）。 */
		public final MmtrLine.TerminalTreatment terminalTreatment;
		public final ObjectArrayList<StopTime> stopTimes;
		/** 终点处理结束时刻 = 这趟车"腾出手"的时刻（下一趟最早从这里开始）。 */
		public final long terminalDoneMillis;

		Trip(String tripId, int sequence, Direction direction, long departureMillis, MmtrLine.TerminalTreatment terminalTreatment, ObjectArrayList<StopTime> stopTimes, long terminalDoneMillis) {
			this.tripId = tripId;
			this.sequence = sequence;
			this.direction = direction;
			this.departureMillis = departureMillis;
			this.terminalTreatment = terminalTreatment;
			this.stopTimes = stopTimes;
			this.terminalDoneMillis = terminalDoneMillis;
		}

		/** 终点站（最后一站）。 */
		public StopTime terminalStop() {
			return stopTimes.get(stopTimes.size() - 1);
		}

		/** 这趟车从发车到终点处理结束的总时长。 */
		public long durationMillis() {
			return terminalDoneMillis - departureMillis;
		}

		@Override
		public String toString() {
			return tripId + " " + MmtrPattern.hhmm(departureMillis) + " 发（" + MmtrPattern.hhmm(terminalDoneMillis)
				+ " 终到处理完，" + terminalTreatment + "）";
		}
	}

	public final String lineId;
	public final ObjectArrayList<Trip> trips;
	/** 末班车终点处理结束的时刻（运营收尾）。 */
	public final long lastTripDoneMillis;

	private MmtrServicePlan(String lineId, ObjectArrayList<Trip> trips) {
		this.lineId = lineId;
		this.trips = trips;
		this.lastTripDoneMillis = trips.isEmpty() ? 0 : trips.get(trips.size() - 1).terminalDoneMillis;
	}

	/**
	 * **按分段密度展开趟次表**（设计 §5.1 ③ 的前半）。
	 *
	 * <p>纯函数：同样的 (线路, 密度, 走行时间) 永远得到同一个计划 —— 这就是"可重放"的含义，
	 * 也是计划**不落盘**的底气（§1.2）。</p>
	 */
	public static MmtrServicePlan generate(MmtrLine line, MmtrPattern pattern, MmtrTravelTimes times) {
		final ObjectArrayList<Trip> trips = new ObjectArrayList<>();
		int sequence = 0;
		for (final long departureMillis : departureSlots(pattern)) {
			sequence++;
			trips.add(buildTrip(line, times, sequence, departureMillis));
		}
		return new MmtrServicePlan(line.lineId, trips);
	}

	/**
	 * **发车槽位**：按密度段的**左闭右开**区间展开成时刻序列（P2 验收 ②）。
	 *
	 * <p>返回的时刻严格递增、互不重复：写在同一段里的重叠已被输入层校验挡住（P1），
	 * 这里只在展开时按时间排序并丢掉不可能出现的重复值 —— 生成器不替输入层擦屁股，
	 * 但也不制造两次同刻发车。</p>
	 */
	public static ObjectArrayList<Long> departureSlots(MmtrPattern pattern) {
		final ObjectArrayList<Long> slots = new ObjectArrayList<>();
		if (pattern == null) {
			return slots;
		}
		for (final MmtrPattern.Segment segment : pattern.sortedSegments()) {
			if (segment.headwayMillis <= 0 || segment.toMillis <= segment.fromMillis) {
				continue;   // 坏段由输入层报错；这里跳过，免得生成出无穷多趟
			}
			for (long time = segment.fromMillis; time < segment.toMillis; time += segment.headwayMillis) {
				if (slots.isEmpty() || slots.get(slots.size() - 1) != time) {
					slots.add(time);
				}
			}
		}
		return slots;
	}

	private static Trip buildTrip(MmtrLine line, MmtrTravelTimes times, int sequence, long departureMillis) {
		final MmtrLine.TerminalTreatment treatment = line.effectiveTerminalTreatment();
		final ObjectArrayList<StopTime> stopTimes = new ObjectArrayList<>();
		long clock = departureMillis;
		for (int i = 0; i < line.stops.size(); i++) {
			final MmtrLine.Stop stop = line.stops.get(i);
			if (i > 0) {
				clock += Math.max(0, times.legMillis(i - 1, i));
			}
			final long arrival = clock;
			final long departure = arrival + Math.max(0, stop.dwellMillis);
			stopTimes.add(new StopTime(i, stop.stationId, stop.platformId, arrival, departure));
			clock = departure;
		}
		final long terminalDone = clock + (line.loop ? 0 : Math.max(0, times.terminalMillis(treatment)));
		return new Trip(line.lineId + "-" + String.format("%03d", sequence), sequence, Trip.Direction.OUT,
			departureMillis, treatment, stopTimes, terminalDone);
	}

	/**
	 * **周转时间 ring**（设计 §5.1 ①）：往程走行 + 沿途停站 + 终点处理 + 返程走行 + 沿途停站。
	 *
	 * <p>环线没有"终点处理"（终点即起点，继续跑），所以那一段是 0。</p>
	 *
	 * <p>它是车底数的分母：{@code N = ceil(ring / 高峰间隔)}。返程的走行时间按同一张腿表反向取，
	 * 这样不对称的线路（上坡慢、下坡快）也能算对。</p>
	 */
	public static long ringMillis(MmtrLine line, MmtrTravelTimes times) {
		if (line.stops.size() < 2) {
			return 0;
		}
		long legs = 0;
		for (int i = 1; i < line.stops.size(); i++) {
			legs += Math.max(0, times.legMillis(i - 1, i));
			legs += Math.max(0, times.legMillis(i, i - 1));
		}
		final long dwells = 2 * line.totalDwellMillis();
		final long terminal = line.loop ? 0 : Math.max(0, times.terminalMillis(line.effectiveTerminalTreatment()));
		return legs + dwells + terminal;
	}

	public int size() {
		return trips.size();
	}

	public boolean isEmpty() {
		return trips.isEmpty();
	}

	/** 首班发车时刻（没有趟次时返回 0）。 */
	public long firstDepartureMillis() {
		return trips.isEmpty() ? 0 : trips.get(0).departureMillis;
	}

	/** 末班发车时刻（没有趟次时返回 0）。 */
	public long lastDepartureMillis() {
		return trips.isEmpty() ? 0 : trips.get(trips.size() - 1).departureMillis;
	}

	@Override
	public String toString() {
		return "服务计划 " + lineId + " " + trips.size() + " 趟（"
			+ MmtrPattern.hhmm(firstDepartureMillis()) + "–" + MmtrPattern.hhmm(lastDepartureMillis()) + "）";
	}
}
