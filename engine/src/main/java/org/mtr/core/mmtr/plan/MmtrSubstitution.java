package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;

/**
 * P6：**替补顶替**（{@code 任务系统-线路派生与车底交路-设计.md} §8.1）。
 *
 * <p>用户裁定的口径是**按现实做法**：</p>
 * <pre>
 * 故障 / 晚点超阈值
 *   → 在 MmtrDiagram 上把该车的**后续趟次**转给替补车
 *   → MmtrServicePlan 一行不改（乘客时刻表不变）
 *   → 接续点 = "后续某站的某一趟次"，不是凭空开始：
 *      替补车需要一条"从它的停放位置开到接续站"的前置任务
 *   → 若替补来不及（前置任务时长 &gt; 可用窗口）→ 退化为"取消该趟" + 对外发出**带理由**的事件
 * </pre>
 *
 * <p>"接续点不是凭空开始"这一条是本类的全部要点：替补车停在车场，它要**先开到接续站** ——
 * 那条前置任务（{@code DRIVE_TO_PLATFORM}）的时长就是"来不来得及"的判据，
 * 用的正是线路输入里的 {@code leadTimeMillis}（= 从车场出发到站台等待出发所需时间）。</p>
 */
public final class MmtrSubstitution {

	/** 一次顶替的结果：交路怎么改、替补要先干什么、哪几趟只能取消、为什么。 */
	public static final class Result {
		/** 出故障那辆车**保留下来**的交路（冻结期内的部分）。 */
		public final MmtrDiagram.Working remaining;
		/** 替补车的交路：前置任务 + 接手过来的趟次。 */
		public final MmtrDiagram.@Nullable Working replacement;
		/** 接续点（替补接手的第一趟的车次 id）；没有接手 = 空。 */
		public final String handoverTripId;
		/** 因为来不及而取消的趟次（车次 id）。 */
		public final ObjectArrayList<String> cancelledTripIds = new ObjectArrayList<>();
		public final ObjectArrayList<String> notes = new ObjectArrayList<>();
		/**
		 * 要对外发出的事件（"取消该趟"必须带理由推前台，§8.1）；不需要发 = null。
		 */
		public @Nullable MmtrEvent event;

		Result(MmtrDiagram.Working remaining, MmtrDiagram.@Nullable Working replacement, String handoverTripId) {
			this.remaining = remaining;
			this.replacement = replacement;
			this.handoverTripId = handoverTripId;
		}

		public boolean handoverHappened() {
			return replacement != null && !handoverTripId.isEmpty();
		}
	}

	private MmtrSubstitution() {
	}

	/**
	 * **把出故障那辆车的后续趟次交给替补**。
	 *
	 * @param line           线路（取 {@code leadTimeMillis} 与出库股道）
	 * @param source         出故障那辆车当前的交路
	 * @param dayTime        现在（当日毫秒）
	 * @param frozenUntil    这辆车的冻结边界（它当前那趟跑完之前不许动）
	 * @param spareConsistId 替补编组代码（空 = 没有替补可用 → 只能取消）
	 * @param eventIdPrefix  事件 id 前缀（取消事件要用）
	 */
	public static Result handOver(MmtrLine line, MmtrDiagram.Working source, long dayTime, long frozenUntil,
		@Nullable String spareConsistId, String eventIdPrefix) {
		final long handoverEarliest = Math.max(dayTime, frozenUntil);

		// ① 冻结期内的部分原样留给故障车（它得先把手上的活跑完 —— 在途不打断）
		final MmtrDiagram.Working remaining = new MmtrDiagram.Working(source.consistId);
		final ObjectArrayList<MmtrDiagram.Entry> future = new ObjectArrayList<>();
		for (final MmtrDiagram.Entry entry : source.entries) {
			if (entry.startMillis >= handoverEarliest && entry.kind == MmtrDiagram.Entry.Kind.TRIP) {
				future.add(entry);
			} else {
				remaining.entries.add(entry);
			}
		}
		final Result result = new Result(remaining, null, "");

		if (future.isEmpty()) {
			result.notes.add("车列 " + source.consistId + " 剩下的趟次都在冻结期内，无需顶替");
			return result;
		}
		if (spareConsistId == null || spareConsistId.isEmpty()) {
			for (final MmtrDiagram.Entry entry : future) {
				if (entry.trip != null) {
					result.cancelledTripIds.add(entry.trip.tripId);
				}
			}
			result.notes.add("车列 " + source.consistId + " 下线，没有替补可用 → 取消 " + result.cancelledTripIds.size() + " 趟");
			result.event = cancelEvent(eventIdPrefix, source.consistId, result.cancelledTripIds.size(), "没有替补编组可用");
			return result;
		}

		/*
		 * ② 接续点：从第一趟"替补来得及开到接续站"的趟次开始。
		 *
		 * 判据就是现实里那一条 —— 前置任务时长（车场 → 接续站）必须塞得进"从现在到那趟发车"的窗口。
		 * 塞不进的趟**取消**（不能让它凭空出现在接续站），并一路往后找。
		 */
		final long requiredLeadMillis = Math.max(0, line.leadTimeMillis);
		int handoverIndex = -1;
		for (int i = 0; i < future.size(); i++) {
			final MmtrDiagram.Entry entry = future.get(i);
			final long availableMillis = entry.startMillis - handoverEarliest;
			if (availableMillis >= requiredLeadMillis) {
				handoverIndex = i;
				break;
			}
			if (entry.trip != null) {
				result.cancelledTripIds.add(entry.trip.tripId);
			}
		}
		if (!result.cancelledTripIds.isEmpty()) {
			result.notes.add("替补来不及的 " + result.cancelledTripIds.size() + " 趟取消（前置任务要 "
				+ Math.round(requiredLeadMillis / 60000.0) + " min）");
		}
		if (handoverIndex < 0) {
			result.notes.add("车列 " + source.consistId + " 剩下的趟次**替补都来不及** → 全部取消，等它自己修好");
			result.event = cancelEvent(eventIdPrefix, source.consistId, result.cancelledTripIds.size(),
				"替补从车场开到接续站也来不及");
			return result;
		}

		// ③ 替补的交路：一条前置任务（车场 → 接续站） + 接手的那些趟
		final MmtrDiagram.Entry handoverEntry = future.get(handoverIndex);
		final MmtrServicePlan.Trip firstTrip = handoverEntry.trip;
		final MmtrDiagram.Working replacement = new MmtrDiagram.Working(spareConsistId);
		if (firstTrip != null) {
			final MmtrServicePlan.StopTime firstStop = firstTrip.stopTimes.get(0);
			replacement.entries.add(new MmtrDiagram.Entry(MmtrDiagram.Entry.Kind.DEPART_YARD,
				firstStop.stationId, firstStop.platformId, line.yardSidingId,
				Math.max(0, firstTrip.departureMillis - requiredLeadMillis), firstTrip.departureMillis, 0, null));
		}
		for (int i = handoverIndex; i < future.size(); i++) {
			replacement.entries.add(future.get(i));
		}
		MmtrDiagram.fillWaits(replacement);
		final Result out = new Result(remaining, replacement, firstTrip == null ? "" : firstTrip.tripId);
		out.cancelledTripIds.addAll(result.cancelledTripIds);
		out.notes.addAll(result.notes);
		out.notes.add("替补 " + spareConsistId + " 从第 " + (firstTrip == null ? "?" : firstTrip.tripId)
			+ " 趟接手（" + (future.size() - handoverIndex) + " 趟），时刻表一行不改");
		if (!out.cancelledTripIds.isEmpty()) {
			out.event = cancelEvent(eventIdPrefix, source.consistId, out.cancelledTripIds.size(), "替补来不及接前几趟");
		}
		return out;
	}

	/** 取第一个还没上场的替补（按代码定序，可复现）。 */
	public static String firstSpare(MmtrFleet fleet, java.util.Set<String> alreadyUsed) {
		if (fleet == null) {
			return null;
		}
		for (final MmtrFleet.ConsistSpec spare : fleet.spares) {
			if (!alreadyUsed.contains(spare.consistId)) {
				return spare.consistId;
			}
		}
		return null;
	}

	private static MmtrEvent cancelEvent(String prefix, String consistId, int cancelled, String why) {
		final MmtrEvent.Fault fault = new MmtrEvent.Fault((prefix == null ? "" : prefix) + "-cancel-" + consistId, consistId, 0, false);
		fault.reason = "取消 " + cancelled + " 趟：" + why;
		fault.severity = 1;
		return fault;
	}
}
