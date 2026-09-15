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

		/**
		 * **整体平移**这趟车（延误"保车"策略用，§8.1）：车次与站序不变，所有时刻 +{@code shiftMillis}。
		 *
		 * <p>时刻表是"计划"，平移是"计划让位于现实" —— 所以它是**新建一趟**而不是改原对象：
		 * 未被动过的那些趟（过去/冻结期内的）在对账时仍然是同一个对象，一眼能看出"谁被改了"。</p>
		 */
		public Trip shiftedBy(long shiftMillis) {
			if (shiftMillis == 0) {
				return this;
			}
			final ObjectArrayList<StopTime> shiftedStops = new ObjectArrayList<>();
			for (final StopTime stop : stopTimes) {
				shiftedStops.add(new StopTime(stop.stopIndex, stop.stationId, stop.platformId,
					stop.arrivalMillis + shiftMillis, stop.departureMillis + shiftMillis));
			}
			return new Trip(tripId, sequence, direction, departureMillis + shiftMillis, terminalTreatment,
				shiftedStops, terminalDoneMillis + shiftMillis);
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
	 * 也是计划**不落盘**的底气（§1.2）。本重载生成**起点方向**（{@link Trip.Direction#OUT}）。</p>
	 */
	public static MmtrServicePlan generate(MmtrLine line, MmtrPattern pattern, MmtrTravelTimes times) {
		return generate(line, pattern, times, Trip.Direction.OUT);
	}

	/**
	 * 同上，可指定方向。
	 *
	 * <p>{@link Trip.Direction#BACK}（终点 → 起点）**不是**"另一条线路的时刻表"，而是
	 * **同一辆车的回程**：它的发车时刻 = 对应往程趟的 {@link Trip#terminalDoneMillis}
	 * （终点站处理完就能开回来）。车底交路（P3）靠这条把"出去—回来"接成连续的序列，
	 * 否则一辆车的两趟车之间会凭空跳回起点。</p>
	 */
	public static MmtrServicePlan generate(MmtrLine line, MmtrPattern pattern, MmtrTravelTimes times, Trip.Direction direction) {
		final ObjectArrayList<Trip> trips = new ObjectArrayList<>();
		final long backOffsetMillis = direction == Trip.Direction.BACK ? outboundDurationMillis(line, times) : 0;
		int sequence = 0;
		for (final long departureMillis : departureSlots(pattern)) {
			sequence++;
			trips.add(buildTrip(line, times, sequence, departureMillis + backOffsetMillis, direction));
		}
		return new MmtrServicePlan(line.lineId, trips);
	}

	/** 往程一趟从发车到"终点站处理完"的时长（返程趟按它整体后移）。 */
	public static long outboundDurationMillis(MmtrLine line, MmtrTravelTimes times) {
		return tripDurationMillis(line, times, Trip.Direction.OUT);
	}

	/** 一趟（指定方向）从发车到终点处理完的时长。 */
	public static long tripDurationMillis(MmtrLine line, MmtrTravelTimes times, Trip.Direction direction) {
		if (line.stops.isEmpty()) {
			return 0;
		}
		long duration = 0;
		final int count = line.stops.size();
		for (int step = 0; step < count; step++) {
			final int index = direction == Trip.Direction.BACK ? count - 1 - step : step;
			if (step > 0) {
				final int previousIndex = direction == Trip.Direction.BACK ? index + 1 : index - 1;
				duration += Math.max(0, times.legMillis(previousIndex, index));
				duration += Math.max(0, line.stops.get(index).dwellMillis);
			}
		}
		return duration + (line.loop ? 0 : Math.max(0, times.terminalMillis(line.effectiveTerminalTreatment())));
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

	private static Trip buildTrip(MmtrLine line, MmtrTravelTimes times, int sequence, long departureMillis, Trip.Direction direction) {
		final MmtrLine.TerminalTreatment treatment = line.effectiveTerminalTreatment();
		final ObjectArrayList<StopTime> stopTimes = new ObjectArrayList<>();
		long clock = departureMillis;
		final int count = line.stops.size();
		for (int step = 0; step < count; step++) {
			final int index = direction == Trip.Direction.BACK ? count - 1 - step : step;
			final MmtrLine.Stop stop = line.stops.get(index);
			if (step > 0) {
				final int previousIndex = direction == Trip.Direction.BACK ? index + 1 : index - 1;
				clock += Math.max(0, times.legMillis(previousIndex, index));
			}
			final long arrival = clock;
			/*
			 * **停站时长属于"到达"**：这一趟的起点站不停（车本来就停在那儿、刚做完上一趟的终点处理），
			 * 其余每站按到达算一次门开闭。
			 *
			 * <p>为什么必须这样定：{@code departureMillis} 是时刻表上那一栏"发车时刻"，它就该是车轮动了
			 * 的那一刻。若在起点再加一次停站，时刻表整体后移、而车辆的循环时间被算少一次停站 ——
			 * 交路在密度交界处就会不可行（P3 的用例正是这么把它抓出来的）。</p>
			 */
			final long departure = step == 0 ? arrival : arrival + Math.max(0, stop.dwellMillis);
			/*
			 * notes/154：**回程走另一侧站台**（用户 2026-09-15：每个站都有两个站台，回程该走 2 站台）。
			 * 站序仍是一份，两个方向各自的台由 {@code Stop#returnPlatformId} 给出（0 = 与去程同一个台）。
			 */
			final long platform = direction == Trip.Direction.BACK && stop.returnPlatformId != 0
				? stop.returnPlatformId : stop.platformId;
			stopTimes.add(new StopTime(index, stop.stationId, platform, arrival, departure));
			clock = departure;
		}
		final long terminalDone = clock + (line.loop ? 0 : Math.max(0, times.terminalMillis(treatment)));
		final String suffix = direction == Trip.Direction.BACK ? "B" : "";
		return new Trip(line.lineId + "-" + suffix + String.format("%03d", sequence), sequence, direction,
			departureMillis, treatment, stopTimes, terminalDone);
	}

	/**
	 * **周转时间 ring**（设计 §5.1 ①）：一辆车从起点发车到**再次**能发车的时长 =
	 * 往程一趟（走行 + 沿途停站 + 终点处理）+ 返程一趟（走行 + 沿途停站 + 起点端处理）。
	 *
	 * <h3>与设计字面公式的一处偏离（有理由，记在这里）</h3>
	 * <p>设计写的是"往程走行 + 沿途各站停站 + 终点处理 + 返程走行 + 沿途各站停站"——
	 * 停站按"每个站算两次"、终点处理只算**一次**。按它实现之后 P3 的交路在**两段密度的交界处**
	 * 直接不可行（实测：高峰间隔 3 min、N=8 给出 24 min 的间隔，而车实际要占 25.5 min），
	 * 因为车回到起点后**还要换一次端**才能发下一趟 —— 那一次处理同样是占用。
	 * 所以这里按**车辆实际占用时间**算：每站按"到达"算一次停站（见 {@code buildTrip}），
	 * **两端各算一次终点处理**。N = ceil(ring / 高峰间隔) 这条关系不变。</p>
	 *
	 * <p>环线没有终点处理（终点即起点、继续跑），所以那两项都是 0。</p>
	 */
	public static long ringMillis(MmtrLine line, MmtrTravelTimes times) {
		if (line.stops.size() < 2) {
			return 0;
		}
		return tripDurationMillis(line, times, Trip.Direction.OUT) + tripDurationMillis(line, times, Trip.Direction.BACK);
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
