package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;

/**
 * P3：**车底运用**（{@code 任务系统-线路派生与车底交路-设计.md} §3 / §5.1 ③）。
 *
 * <p>服务计划（P2）说"几点发哪一趟"；交路说**哪辆车去跑**。两段式模型的全部好处就在这里：
 * 替补顶替（§8.1）只改这一层，乘客看的趟次表一行不动。</p>
 *
 * <h3>怎么套班</h3>
 * <pre>
 * ring/N 同上（N = ceil(ring / 高峰最小间隔)）
 * 第 i 个发车槽位 → 第 (i mod N) 辆车；这辆车同时承担**同一槽位的返程趟**
 * （返程趟的发车时刻 = 往程趟的 terminalDoneMillis，即终点站处理完就开回来）
 * 于是每辆车的序列天然连续：出库 → 往 c → 返 c → 往 c+N → 返 c+N → … → 回库
 * 替补（fleet.spares）**一个趟次都不占**，只在 P6 顶替时上场
 * </pre>
 *
 * <p>"为什么是 i mod N 而不是别的"：一辆车跑一个 ring（往 + 终点处理 + 返）回到起点，而发车每
 * headway 一次 —— 要接得上，这辆车下一个能接的槽位就是 i + N（因为 N × headway ≥ ring，
 * 取整向上所以有一点余量，那点余量就是它在起点站台上的等待）。</p>
 */
public final class MmtrDiagram {

	/** 交路里的一条：出库 / 一趟车 / 回库。 */
	public static final class Entry {
		public enum Kind {
			/** 出库：从车场开到首发站台（{@code DRIVE_TO_PLATFORM}）。 */
			DEPART_YARD,
			/** 一趟车（往程或返程），带完整的到发时刻。 */
			TRIP,
			/** 回库（{@code DRIVE_TO_SIDING}）。 */
			STABLE_YARD
		}

		public final Kind kind;
		/** 起点站的站/台（出库与趟次用）。 */
		public final long stationId;
		public final long platformId;
		/** 目标股道（出库/回库用）。 */
		public final long sidingId;
		/** 计划开始（出库 = 首发前移后的时刻；趟次 = 发车时刻）。 */
		public final long startMillis;
		/** 计划结束（趟次 = 终点处理完；回库 = 待定，等于 start）。 */
		public final long endMillis;
		/** 这一条之前要等多久（上一条结束到这一条开始；出库那条是 0）。 */
		public final long waitBeforeMillis;
		public final MmtrServicePlan.@Nullable Trip trip;

		Entry(Kind kind, long stationId, long platformId, long sidingId, long startMillis, long endMillis, long waitBeforeMillis, MmtrServicePlan.@Nullable Trip trip) {
			this.kind = kind;
			this.stationId = stationId;
			this.platformId = platformId;
			this.sidingId = sidingId;
			this.startMillis = startMillis;
			this.endMillis = endMillis;
			this.waitBeforeMillis = waitBeforeMillis;
			this.trip = trip;
		}

		public long durationMillis() {
			return endMillis - startMillis;
		}

		/** 目标是不是某条股道（出库/回库）。 */
		public boolean targetsSiding() {
			return sidingId != 0;
		}

		@Override
		public String toString() {
			return switch (kind) {
				case DEPART_YARD -> "出库 → 股道 " + sidingId + " 到 " + stationId + "/" + platformId + " " + MmtrPattern.hhmm(startMillis);
				case STABLE_YARD -> "回库 ← 股道 " + sidingId + " " + MmtrPattern.hhmm(startMillis);
				case TRIP -> trip == null ? "趟次（缺）" : trip.toString();
			};
		}
	}

	/** 一辆车一天的交路。 */
	public static final class Working {
		public final String consistId;
		public final ObjectArrayList<Entry> entries = new ObjectArrayList<>();

		Working(String consistId) {
			this.consistId = consistId;
		}

		public int tripCount() {
			int count = 0;
			for (final Entry entry : entries) {
				if (entry.kind == Entry.Kind.TRIP) {
					count++;
				}
			}
			return count;
		}

		public ObjectArrayList<MmtrServicePlan.Trip> trips() {
			final ObjectArrayList<MmtrServicePlan.Trip> out = new ObjectArrayList<>();
			for (final Entry entry : entries) {
				if (entry.kind == Entry.Kind.TRIP && entry.trip != null) {
					out.add(entry.trip);
				}
			}
			return out;
		}

		/** 出库那条（没有 = 这辆车今天不上场）。 */
		public @Nullable Entry departYard() {
			for (final Entry entry : entries) {
				if (entry.kind == Entry.Kind.DEPART_YARD) {
					return entry;
				}
			}
			return null;
		}

		public @Nullable Entry stableYard() {
			for (final Entry entry : entries) {
				if (entry.kind == Entry.Kind.STABLE_YARD) {
					return entry;
				}
			}
			return null;
		}

		/** 首个趟次的发车时刻（没有趟次返回 0）。 */
		public long firstDepartureMillis() {
			for (final Entry entry : entries) {
				if (entry.kind == Entry.Kind.TRIP && entry.trip != null) {
					return entry.trip.departureMillis;
				}
			}
			return 0;
		}

		/** 最后一个趟次处理完的时刻。 */
		public long lastDoneMillis() {
			long last = 0;
			for (final Entry entry : entries) {
				if (entry.kind == Entry.Kind.TRIP && entry.trip != null) {
					last = Math.max(last, entry.trip.terminalDoneMillis);
				}
			}
			return last;
		}

		/** 一天里等了多少（上一条结束到这一条开始的总和）—— 套班取整留下的余量都在这里。 */
		public long totalWaitMillis() {
			long total = 0;
			for (final Entry entry : entries) {
				total += Math.max(0, entry.waitBeforeMillis);
			}
			return total;
		}

		@Override
		public String toString() {
			return "交路 " + consistId + "：" + tripCount() + " 趟（首发 "
				+ MmtrPattern.hhmm(firstDepartureMillis()) + "，收车 " + MmtrPattern.hhmm(lastDoneMillis()) + "）";
		}
	}

	public final String lineId;
	/** 周转时间与高峰间隔 —— N 就是由这两个数算出来的（验收 ④）。 */
	public final long ringMillis;
	public final long peakHeadwayMillis;
	/** 套班需要的车底数 N = ceil(ring / 高峰间隔)。 */
	public final long requiredConsists;
	public final ObjectArrayList<Working> workings = new ObjectArrayList<>();
	/** 配了但**没排班**的编组（超过 N 的多余车底）——与替补不同：它们是套班车底，只是今天没用到。 */
	public final ObjectArrayList<String> idleConsistIds = new ObjectArrayList<>();
	/** 替补编组（不占趟次，只等顶替）。 */
	public final ObjectArrayList<String> spareConsistIds = new ObjectArrayList<>();
	/** 车底不够跑时的那句话（null = 够）；与 P1 的加载期校验同一个口径。 */
	public final @Nullable String capacityProblem;

	private MmtrDiagram(String lineId, long ringMillis, long peakHeadwayMillis, long requiredConsists, @Nullable String capacityProblem) {
		this.lineId = lineId;
		this.ringMillis = ringMillis;
		this.peakHeadwayMillis = peakHeadwayMillis;
		this.requiredConsists = requiredConsists;
		this.capacityProblem = capacityProblem;
	}

	/**
	 * **排班**：把趟次表套到车底上（设计 §5.1 ③）。
	 *
	 * <p>纯函数，与 {@link MmtrServicePlan} 同一层：同样的输入永远得到同一份交路。</p>
	 */
	public static MmtrDiagram generate(MmtrLine line, MmtrPattern pattern, MmtrFleet fleet, MmtrTravelTimes times) {
		final long ring = MmtrServicePlan.ringMillis(line, times);
		final long peakHeadway = pattern == null ? 0 : pattern.peakHeadwayMillis();
		final long required = MmtrFleet.requiredConsists(ring, peakHeadway);
		final MmtrDiagram diagram = new MmtrDiagram(line.lineId, ring, peakHeadway, required,
			fleet == null ? "没有车底" : fleet.validateCapacity(ring, peakHeadway));

		for (final MmtrFleet.ConsistSpec spare : fleet == null ? new ObjectArrayList<MmtrFleet.ConsistSpec>() : fleet.spares) {
			diagram.spareConsistIds.add(spare.consistId);
		}
		if (fleet == null || fleet.consists.isEmpty()) {
			return diagram;   // 没有套班车底：交路为空，capacityProblem 已经说明原因
		}

		final int vehicleCount = fleet.consists.size();
		final ObjectArrayList<MmtrServicePlan.Trip> outbound = MmtrServicePlan.generate(line, pattern, times, MmtrServicePlan.Trip.Direction.OUT).trips;
		final ObjectArrayList<MmtrServicePlan.Trip> inbound = MmtrServicePlan.generate(line, pattern, times, MmtrServicePlan.Trip.Direction.BACK).trips;
		for (int i = 0; i < vehicleCount; i++) {
			diagram.workings.add(new Working(fleet.consists.get(i).consistId));
		}
		/*
		 * 套班：**第 i 个发车槽位 → 第 (i mod N) 辆车**，同一槽位的返程趟也归它。
		 *
		 * <p>一辆车跑完"往 + 终点处理 + 返"（= 一个 ring）回到起点，而发车每 headway 一次 ——
		 * 它下一个接得上的槽位就是 i + N（N × headway ≥ ring，取整向上留出的那点余量变成它在
		 * 起点站台上的等待）。这条关系就是 N = ceil(ring / 高峰间隔) 的物理含义。</p>
		 */
		final int vehiclesInService = (int) Math.min(required, vehicleCount);
		for (int i = 0; i < vehicleCount; i++) {
			if (i >= vehiclesInService) {
				diagram.idleConsistIds.add(fleet.consists.get(i).consistId);
			}
		}
		if (vehiclesInService > 0) {
			for (int i = 0; i < outbound.size(); i++) {
				final Working working = diagram.workings.get(i % vehiclesInService);
				final MmtrServicePlan.Trip out = outbound.get(i);
				working.entries.add(new Entry(Entry.Kind.TRIP, 0, 0, 0, out.departureMillis, out.terminalDoneMillis, 0, out));
				if (i < inbound.size()) {
					final MmtrServicePlan.Trip back = inbound.get(i);
					working.entries.add(new Entry(Entry.Kind.TRIP, 0, 0, 0, back.departureMillis, back.terminalDoneMillis, 0, back));
				}
			}
		}
		// 首尾：出库（首发前移量）与回库（末趟处理完）—— 逐车补，等多久由下面统一算
		for (final Working working : diagram.workings) {
			if (working.entries.isEmpty()) {
				continue;   // 今天不上场的车底
			}
			final MmtrServicePlan.Trip firstTrip = working.trips().get(0);
			final MmtrServicePlan.StopTime firstStop = firstTrip.stopTimes.get(0);
			final long yardDeparture = Math.max(0, firstTrip.departureMillis - Math.max(0, line.leadTimeMillis));
			working.entries.add(0, new Entry(Entry.Kind.DEPART_YARD, firstStop.stationId, firstStop.platformId,
				line.yardSidingId, yardDeparture, firstTrip.departureMillis, 0, null));
			final MmtrServicePlan.Trip lastTrip = working.trips().get(working.tripCount() - 1);
			final MmtrServicePlan.StopTime lastStop = lastTrip.stopTimes.get(lastTrip.stopTimes.size() - 1);
			working.entries.add(new Entry(Entry.Kind.STABLE_YARD, lastStop.stationId, lastStop.platformId,
				line.yardSidingId, lastTrip.terminalDoneMillis, lastTrip.terminalDoneMillis, 0, null));
			fillWaits(working);
		}
		return diagram;
	}

	/**
	 * 把"这一条之前等多久"按**上一条结束 → 这一条开始**统一算一遍（每辆车内部）。
	 *
	 * <p>不在这里算"应该等多久"，只报**实际的空档**：套班取整、终点处理、站台等待全都表现成它 ——
	 * 交路视图（P6）与"为什么这辆车不连续"的排查都读这一个数。</p>
	 */
	private static void fillWaits(Working working) {
		for (int i = 0; i < working.entries.size(); i++) {
			final Entry entry = working.entries.get(i);
			final long wait = i == 0 ? 0 : Math.max(0, entry.startMillis - working.entries.get(i - 1).endMillis);
			working.entries.set(i, new Entry(entry.kind, entry.stationId, entry.platformId, entry.sidingId,
				entry.startMillis, entry.endMillis, wait, entry.trip));
		}
	}

	/** 排了班的那些车（出库那条存在的）。 */
	public ObjectArrayList<Working> scheduledWorkings() {
		final ObjectArrayList<Working> out = new ObjectArrayList<>();
		for (final Working working : workings) {
			if (working.departYard() != null) {
				out.add(working);
			}
		}
		return out;
	}

	/** 这条线路一天用了多少趟次（往 + 返）。 */
	public int totalTripCount() {
		int total = 0;
		for (final Working working : workings) {
			total += working.tripCount();
		}
		return total;
	}

	@Override
	public String toString() {
		return "交路 " + lineId + "：N=" + requiredConsists + "（ring " + Math.round(ringMillis / 1000.0) + "s / 高峰间隔 "
			+ Math.round(peakHeadwayMillis / 1000.0) + "s），" + scheduledWorkings().size() + " 辆车 "
			+ totalTripCount() + " 趟" + (capacityProblem == null ? "" : "；" + capacityProblem);
	}
}
