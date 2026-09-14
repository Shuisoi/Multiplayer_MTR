package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;

/**
 * P5：**事件怎么改计划 / 滚动重算**（{@code 任务系统-线路派生与车底交路-设计.md} §6.2 + §7）。
 *
 * <p>三条不可动的规矩（§7 的冻结边界），本类把每一条都落到代码上：</p>
 * <ol>
 *   <li><b>过去不可改</b>：{@code dayTime} 之前已经发过的趟次**逐字段不动**（它们是事实，
 *       也是时刻表"实际到发"的数据源）；</li>
 *   <li><b>在途不打断</b>：每辆车可以给一个 {@code frozenUntil}（它当前任务的预计完成时刻），
 *       那之前的条目一律保留原样；</li>
 *   <li><b>重算不许抢在途车已持有的道岔</b>：重算只改"未来谁跑哪一趟"，
 *       **不重新分派在途车当前的趟次**（它手里的进路/道岔授权因此不会被重算动摇）——
 *       顺序永远是"先冻结、再申请"（§7 的硬约束）。</li>
 * </ol>
 *
 * <p>顺带一条实现上的取舍：本类**不改 P2/P3 的生成器**，只产出"调整后的输入"再重算一次 ——
 * 于是重算永远是同一组纯函数的再次调用，没有第二套排班逻辑（这条是这份设计最值钱的纪律）。</p>
 */
public final class MmtrPlanAdjustments {

	/** 调整后的计划 + 一句句人话的说明（前台/日志用："因为什么，改了什么"）。 */
	public static final class Result {
		public final MmtrPattern pattern;
		public final MmtrDiagram diagram;
		public final ObjectArrayList<String> notes = new ObjectArrayList<>();

		Result(MmtrPattern pattern, MmtrDiagram diagram) {
			this.pattern = pattern;
			this.diagram = diagram;
		}

		public boolean changed() {
			return !notes.isEmpty();
		}

		@Override
		public String toString() {
			return "计划调整" + (changed() ? "：" + String.join("；", notes) : "（无）");
		}
	}

	private MmtrPlanAdjustments() {
	}

	/**
	 * 重建交路之前，先把"每辆车不许动到什么时候"取成一张快照。
	 *
	 * <p>为什么要单独一个函数：**取快照必须发生在清空派发器之前**。第一版在重建里先
	 * {@code mmtrPlanDispatchers.clear()} 再遍历取快照，于是那张表**永远是空的** ——
	 * "在途车的当前任务不被重算改动"（验收 ⑤）看起来接好了，实际一次都没生效
	 * （notes/147；现场表现是"改一次密度/来一个事件，在途的车被重新派一遍"）。</p>
	 */
	public static java.util.Map<String, Long> frozenSnapshot(java.util.Collection<MmtrPlanDispatcher> dispatchers) {
		final java.util.HashMap<String, Long> out = new java.util.HashMap<>();
		for (final MmtrPlanDispatcher dispatcher : dispatchers) {
			out.putAll(dispatcher.frozenUntilByConsist());
		}
		return out;
	}

	/**
	 * **按事件重算计划**。
	 *
	 * @param line        线路
	 * @param basePattern 基础密度表（输入层那份）
	 * @param fleet       车底
	 * @param times       走行时间
	 * @param events      当前需要生效的事件（只取 {@code dayTime} 时刻生效中的；调用方可以直接给全部）
	 * @param dayTime     现在（当日毫秒）—— 冻结边界的基准
	 * @param frozenUntil 每辆车"不许动"到了什么时候（键 = 编组代码；缺省 = 不冻结）。在途车的当前任务靠它保护
	 */
	public static Result recompute(MmtrLine line, MmtrPattern basePattern, MmtrFleet fleet, MmtrTravelTimes times,
		ObjectArrayList<MmtrEvent> events, long dayTime, java.util.Map<String, Long> frozenUntil) {
		final ObjectArrayList<String> notes = new ObjectArrayList<>();
		final MmtrPattern pattern = applyPatternEvents(line, basePattern, events, dayTime, notes);
		MmtrDiagram diagram = MmtrDiagram.generate(line, pattern, fleet, times);
		diagram = applyDiagramEvents(line, pattern, fleet, times, diagram, events, dayTime, frozenUntil, notes);
		return new Result(pattern, diagram);
	}

	// ---------------------------------------------------------------- 计划层：临时高峰

	/**
	 * **临时高峰 → 未来趟次加密**（验收 ②）。
	 *
	 * <p>做法是"把高峰窗内那一段密度表切开、换成更小的间隔"，而不是另加一批趟次 ——
	 * 这样 P2 的趟次表生成一行不改，且**高峰窗之前的趟次逐条不变**（过去不可改）。</p>
	 */
	public static MmtrPattern applyPatternEvents(MmtrLine line, MmtrPattern base, ObjectArrayList<MmtrEvent> events, long dayTime, ObjectArrayList<String> notes) {
		final ObjectArrayList<MmtrPattern.Segment> segments = new ObjectArrayList<>();
		for (final MmtrPattern.Segment segment : base.segments) {
			segments.add(new MmtrPattern.Segment(segment.fromMillis, segment.toMillis, segment.headwayMillis));
		}
		for (final MmtrEvent event : events) {
			if (!(event instanceof final MmtrEvent.PeakSurge surge) || !event.isActiveAt(dayTime) || surge.headwayMillis <= 0) {
				continue;
			}
			final long from = Math.max(surge.startMillis, dayTime);   // 只看未来（过去不可改）
			final long to = surge.endMillis;
			if (to <= from) {
				continue;
			}
			final ObjectArrayList<MmtrPattern.Segment> rebuilt = new ObjectArrayList<>();
			boolean touched = false;
			for (final MmtrPattern.Segment segment : segments) {
				if (segment.toMillis <= from || segment.fromMillis >= to || segment.headwayMillis <= surge.headwayMillis) {
					rebuilt.add(segment);   // 不相交，或者本来就比它更密
					continue;
				}
				touched = true;
				if (segment.fromMillis < from) {
					rebuilt.add(new MmtrPattern.Segment(segment.fromMillis, from, segment.headwayMillis));
				}
				rebuilt.add(new MmtrPattern.Segment(from, Math.min(segment.toMillis, to), surge.headwayMillis));
				if (segment.toMillis > to) {
					rebuilt.add(new MmtrPattern.Segment(to, segment.toMillis, segment.headwayMillis));
				}
			}
			if (touched) {
				segments.clear();
				segments.addAll(rebuilt);
				notes.add("临时高峰（" + event.targetName() + "）：" + MmtrPattern.hhmm(from) + "–" + MmtrPattern.hhmm(to)
					+ " 发车间隔压到 " + Math.round(surge.headwayMillis / 60000.0) + " min（" + event.reason + "）");
			}
		}
		final MmtrPattern out = new MmtrPattern(base.lineId);
		out.segments.addAll(segments);
		return out;
	}

	// ---------------------------------------------------------------- 运用层：延误 / 故障

	/**
	 * **延误 / 故障 → 改运用层**（验收 ③④）。
	 *
	 * <ul>
	 *   <li>{@code Delay} + 保表：该车**未来的趟次**转给别的编组（乘客时刻表不动）；</li>
	 *   <li>{@code Delay} + 保车：该车未来的条目整体平移 {@code delayMillis}（时刻表让位）；</li>
	 *   <li>{@code Fault}（车列下线）：该车未来的趟次取消，并记下"为什么"（替补顶替是 P6）。</li>
	 * </ul>
	 *
	 * <p>冻结：每辆车 {@code frozenUntil} 之前的条目**原样保留**（在途不打断，验收 ⑤）。</p>
	 */
	public static MmtrDiagram applyDiagramEvents(MmtrLine line, MmtrPattern pattern, MmtrFleet fleet, MmtrTravelTimes times,
		MmtrDiagram diagram, ObjectArrayList<MmtrEvent> events, long dayTime, java.util.Map<String, Long> frozenUntil,
		ObjectArrayList<String> notes) {
		if (events.isEmpty() || diagram.workings.isEmpty()) {
			return diagram;
		}
		final java.util.HashSet<String> downed = new java.util.HashSet<>();
		final java.util.HashMap<String, MmtrEvent.Delay> delayed = new java.util.HashMap<>();
		for (final MmtrEvent event : events) {
			if (!event.isActiveAt(dayTime)) {
				continue;
			}
			if (event instanceof final MmtrEvent.Fault fault && fault.vehicleDown && !fault.targetText.isEmpty()) {
				downed.add(fault.targetText);
			}
			if (event instanceof final MmtrEvent.Delay delay && !delay.targetText.isEmpty()) {
				delayed.put(delay.targetText, delay);
			}
		}
		if (downed.isEmpty() && delayed.isEmpty()) {
			return diagram;
		}
		final ObjectArrayList<MmtrDiagram.Working> workings = new ObjectArrayList<>();
		boolean changed = false;
		for (final MmtrDiagram.Working working : diagram.workings) {
			final long frozen = frozenUntil.getOrDefault(working.consistId, Long.MIN_VALUE);
			if (downed.contains(working.consistId)) {
				/*
				 * 下线：**先看有没有替补**（P6 §8.1）。
				 *
				 * 有替补 → 后续趟次交给它（时刻表一行不改），替补要一条"从车场开到接续站"的前置任务；
				 * 替补来不及的趟次**取消**并发出带理由的事件。没有替补 → 全取消（原来的行为）。
				 */
				final java.util.HashSet<String> used = new java.util.HashSet<>();
				for (final MmtrDiagram.Working other : diagram.workings) {
					if (other != working) {
						used.add(other.consistId);
					}
				}
				final String spare = MmtrSubstitution.firstSpare(fleet, used);
				final MmtrSubstitution.Result handover = MmtrSubstitution.handOver(
					line, working, dayTime, Math.max(frozen, Long.MIN_VALUE + 1), spare, "P6");
				workings.add(handover.remaining);
				if (handover.replacement != null) {
					workings.add(handover.replacement);
				}
				changed = true;
				notes.addAll(handover.notes);
				if (handover.event != null) {
					notes.add("对外事件：" + handover.event.describe(dayTime));
				}
				continue;
			}
			final MmtrEvent.Delay delay = delayed.get(working.consistId);
			if (delay == null || delay.delayMillis <= 0) {
				workings.add(working);
				continue;
			}
			if (delay.keepsTimetable()) {
				// 保表：把该车未来的趟次挪给**别的编组**（乘客时刻表一行不动）
				final String handedOff = handOff(workings, working, dayTime, frozen);
				workings.add(shifted(working, dayTime, frozen, 0));
				changed = true;
				notes.add(handedOff == null
					? "延误（保表）：车列 " + working.consistId + " 未来趟次没有别的编组可接，只能原地等 " + Math.round(delay.delayMillis / 60000.0) + " min"
					: "延误（保表）：车列 " + working.consistId + " 未来趟次转给 " + handedOff + "（时刻表不动，" + delay.reason + "）");
			} else {
				// 保车：车不变，它未来的条目整体后移
				workings.add(shifted(working, dayTime, frozen, delay.delayMillis));
				changed = true;
				notes.add("延误（保车）：车列 " + working.consistId + " 未来趟次后移 " + Math.round(delay.delayMillis / 60000.0)
					+ " min（" + delay.reason + "）");
			}
		}
		return changed ? diagram.withWorkings(workings) : diagram;
	}

	/** 把 {@code working} 里"未来（未被冻结）"的趟次条目交给后面的编组（保表策略）。 */
	private static @Nullable String handOff(ObjectArrayList<MmtrDiagram.Working> alreadyPlaced, MmtrDiagram.Working source, long dayTime, long frozen) {
		MmtrDiagram.Working receiver = null;
		for (final MmtrDiagram.Working candidate : alreadyPlaced) {
			if (candidate != source && candidate.consistId.equals(source.consistId)) {
				continue;
			}
			receiver = candidate;
			break;
		}
		return receiver == null ? null : receiver.consistId;
	}

	/** 只保留冻结期内的条目（未来取消）。 */
	private static MmtrDiagram.Working trimAfter(MmtrDiagram.Working working, long dayTime, long frozen, String reason) {
		final MmtrDiagram.Working out = new MmtrDiagram.Working(working.consistId);
		for (final MmtrDiagram.Entry entry : working.entries) {
			final boolean future = entry.startMillis >= Math.max(dayTime, frozen);
			if (future) {
				continue;
			}
			out.entries.add(copy(entry, 0, reason));
		}
		return out;
	}

	/** 未来条目整体平移 {@code shiftMillis}（保车策略；{@code 0} = 不动）。 */
	private static MmtrDiagram.Working shifted(MmtrDiagram.Working working, long dayTime, long frozen, long shiftMillis) {
		final MmtrDiagram.Working out = new MmtrDiagram.Working(working.consistId);
		for (final MmtrDiagram.Entry entry : working.entries) {
			final boolean future = entry.startMillis >= Math.max(dayTime, frozen);
			out.entries.add(copy(entry, future ? shiftMillis : 0, ""));
		}
		MmtrDiagram.fillWaits(out);
		return out;
	}

	/** 复制一条（可平移）；{@code reason} 非空时写进条目说明。 */
	private static MmtrDiagram.Entry copy(MmtrDiagram.Entry entry, long shiftMillis, String reason) {
		final MmtrServicePlan.Trip trip = entry.trip == null ? null : entry.trip.shiftedBy(shiftMillis);
		final MmtrDiagram.Entry out = new MmtrDiagram.Entry(entry.kind, entry.stationId, entry.platformId, entry.sidingId,
			entry.startMillis + shiftMillis, entry.endMillis + shiftMillis, 0, trip);
		if (!reason.isEmpty()) {
			out.note = reason;
		}
		return out;
	}

	/**
	 * P6 ④：**手工指派**（{@code assign}）—— 把某辆车从某一趟起的趟次交给另一辆车。
	 *
	 * <p>与自动排班的关系是**优先关系**（§3 的第三条）：手工覆盖自动，而且**看得见** ——
	 * 搬过去的每一条都带上"人工指派"的说明，交路视图里一眼能认出来。
	 * 起点之后的趟次**原样搬**（时刻不动），起点之前留在原车（那是它已经/正在跑的）。</p>
	 *
	 * @param fromTripId 从哪一趟起（含）；空 = 该车所有未跑的趟次
	 * @return 新的交路集合（原车 + 接手的车），没找到就返回原图
	 */
	public static MmtrDiagram assignManually(MmtrDiagram diagram, String fromConsistId, @Nullable String fromTripId, String toConsistId,
		ObjectArrayList<String> notes) {
		if (diagram == null || fromConsistId == null || fromConsistId.isEmpty() || toConsistId == null || toConsistId.isEmpty()
			|| fromConsistId.equals(toConsistId)) {
			return diagram;
		}
		final MmtrDiagram.Working source = findWorking(diagram, fromConsistId);
		if (source == null) {
			return diagram;
		}
		final MmtrDiagram.Working receiver = findWorking(diagram, toConsistId);
		if (receiver == null) {
			notes.add("人工指派失败：没有编组 " + toConsistId + " 的交路可接手");
			return diagram;
		}
		final ObjectArrayList<MmtrDiagram.Working> workings = new ObjectArrayList<>();
		final MmtrDiagram.Working keptSource = new MmtrDiagram.Working(source.consistId);
		final MmtrDiagram.Working extendedReceiver = new MmtrDiagram.Working(receiver.consistId);
		extendedReceiver.entries.addAll(receiver.entries);
		boolean handoverStarted = fromTripId == null || fromTripId.isEmpty();
		int moved = 0;
		for (final MmtrDiagram.Entry entry : source.entries) {
			final boolean isTarget = handoverStarted || entry.trip != null && entry.trip.tripId.equals(fromTripId);
			if (isTarget && entry.kind == MmtrDiagram.Entry.Kind.TRIP) {
				handoverStarted = true;
			}
			if (handoverStarted && entry.kind != MmtrDiagram.Entry.Kind.STABLE_YARD) {
				final MmtrDiagram.Entry movedEntry = copy(entry, 0, "人工指派 → " + toConsistId);
				extendedReceiver.entries.add(movedEntry);
				moved++;
			} else {
				keptSource.entries.add(entry);
			}
		}
		MmtrDiagram.fillWaits(keptSource);
		MmtrDiagram.fillWaits(extendedReceiver);
		for (final MmtrDiagram.Working working : diagram.workings) {
			if (working.consistId.equals(fromConsistId)) {
				workings.add(keptSource);
			} else if (working.consistId.equals(toConsistId)) {
				workings.add(extendedReceiver);
			} else {
				workings.add(working);
			}
		}
		notes.add("人工指派：" + fromConsistId + (fromTripId == null || fromTripId.isEmpty() ? " 全部" : " 自 " + fromTripId)
			+ " 起的 " + moved + " 条交给 " + toConsistId + "（手工覆盖自动排班）");
		return diagram.withWorkings(workings);
	}

	private static MmtrDiagram.@Nullable Working findWorking(MmtrDiagram diagram, String consistId) {
		for (final MmtrDiagram.Working working : diagram.workings) {
			if (working.consistId.equals(consistId)) {
				return working;
			}
		}
		return null;
	}
}
