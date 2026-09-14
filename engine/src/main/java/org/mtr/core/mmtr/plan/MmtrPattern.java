package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

/**
 * P1 输入层：**分段密度**（{@code 任务系统-线路派生与车底交路-设计.md} §4.2）。
 *
 * <pre>
 * 线路 → [(fromMillis, toMillis, headwayMillis), ...]   // 同一天内、不重叠覆盖运营时段
 * 例：07:00–08:30 → 5 min，08:30–10:00 → 3 min
 * </pre>
 *
 * <p>校验三条（P1 验收 ②）：**不得重叠、必须连续覆盖运营时段、{@code headway > 0}**。
 * 运营时段就是这张表的 {@code [最早 from, 最晚 to]} —— 设计里没有单独的"运营开始/结束"字段，
 * 因为那正是"未覆盖"这条校验要防的东西：中间少一段就是"某段时间没有车"，必须报出来。</p>
 *
 * <p>时间一律是**当天毫秒**（{@code 07:00 = 25_200_000}），与作业单的 {@code startTimeOfDayMs} 同一口径；
 * 跨零点的班次不在 v1 范围内（设计 §11 边界）。</p>
 */
public final class MmtrPattern implements SerializedDataBase {

	/** 一段密度：这半天里每 {@code headwayMillis} 发一趟。 */
	public static final class Segment implements SerializedDataBase {
		public long fromMillis;
		public long toMillis;
		public long headwayMillis;

		public Segment() {
		}

		public Segment(long fromMillis, long toMillis, long headwayMillis) {
			this.fromMillis = fromMillis;
			this.toMillis = toMillis;
			this.headwayMillis = headwayMillis;
		}

		public Segment(ReaderBase readerBase) {
			updateData(readerBase);
		}

		@Override
		public void updateData(ReaderBase readerBase) {
			fromMillis = readerBase.getLong("fromMillis", 0);
			toMillis = readerBase.getLong("toMillis", 0);
			headwayMillis = readerBase.getLong("headwayMillis", 0);
		}

		@Override
		public void serializeData(WriterBase writerBase) {
			writerBase.writeLong("fromMillis", fromMillis);
			writerBase.writeLong("toMillis", toMillis);
			writerBase.writeLong("headwayMillis", headwayMillis);
		}

		@Override
		public String toString() {
			return hhmm(fromMillis) + "–" + hhmm(toMillis) + " / " + Math.round(headwayMillis / 60000.0) + "min";
		}
	}

	public String lineId = "";
	public final ObjectArrayList<Segment> segments = new ObjectArrayList<>();

	public MmtrPattern() {
	}

	public MmtrPattern(String lineId) {
		this.lineId = lineId;
	}

	public MmtrPattern(ReaderBase readerBase) {
		updateData(readerBase);
	}

	public MmtrPattern addSegment(long fromMillis, long toMillis, long headwayMillis) {
		segments.add(new Segment(fromMillis, toMillis, headwayMillis));
		return this;
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		lineId = readerBase.getString("lineId", "");
		readerBase.iterateReaderArray("segments", segments::clear, reader -> segments.add(new Segment(reader)));
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeString("lineId", lineId);
		writerBase.writeDataset(segments, "segments");
	}

	/** 按发车时刻排序后的副本 —— 校验与找间隔都按**时间顺序**，与作者书写顺序无关。 */
	public ObjectArrayList<Segment> sortedSegments() {
		final ObjectArrayList<Segment> sorted = new ObjectArrayList<>(segments);
		sorted.sort((a, b) -> Long.compare(a.fromMillis, b.fromMillis));
		return sorted;
	}

	/** 运营开始（最早一段的起点）；没有段时返回 0。 */
	public long operatingStartMillis() {
		final ObjectArrayList<Segment> sorted = sortedSegments();
		return sorted.isEmpty() ? 0 : sorted.get(0).fromMillis;
	}

	/** 运营结束（最晚一段的终点）；没有段时返回 0。 */
	public long operatingEndMillis() {
		final ObjectArrayList<Segment> sorted = sortedSegments();
		return sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1).toMillis;
	}

	/** **高峰最小间隔**：所有段里最小的 headway（{@code N = ceil(ring / 它)}，设计 §5.1 ②）。 */
	public long peakHeadwayMillis() {
		long best = Long.MAX_VALUE;
		for (final Segment segment : segments) {
			if (segment.headwayMillis > 0 && segment.headwayMillis < best) {
				best = segment.headwayMillis;
			}
		}
		return best == Long.MAX_VALUE ? 0 : best;
	}

	/** 某个时刻落在哪一段（左闭右开）；不在运营时段内返回 null。 */
	public @Nullable Segment segmentAt(long timeOfDayMillis) {
		for (final Segment segment : segments) {
			if (timeOfDayMillis >= segment.fromMillis && timeOfDayMillis < segment.toMillis) {
				return segment;
			}
		}
		return null;
	}

	/** 该时刻的发车间隔；不在运营时段内返回 {@code -1}。 */
	public long headwayAt(long timeOfDayMillis) {
		final Segment segment = segmentAt(timeOfDayMillis);
		return segment == null ? -1 : segment.headwayMillis;
	}

	/**
	 * 校验；返回全部问题（空的 = 通过）。
	 *
	 * <p>"重叠"与"未覆盖"这两条都按**排序后相邻两段**判：{@code prev.to > next.from} 是重叠，
	 * {@code prev.to < next.from} 是缺了一段（未覆盖）。这样作者把段写乱序也不会误报成重叠。</p>
	 */
	public ObjectArrayList<String> validate() {
		final ObjectArrayList<String> errors = new ObjectArrayList<>();
		if (lineId == null || lineId.isBlank()) {
			errors.add("密度表缺少 lineId");
		}
		if (segments.isEmpty()) {
			errors.add("线路 " + lineId + " 没有任何密度段（运营时段未覆盖）");
			return errors;
		}
		for (final Segment segment : segments) {
			if (segment.toMillis <= segment.fromMillis) {
				errors.add("线路 " + lineId + " 密度段 " + segment + " 的结束不晚于开始");
			}
			if (segment.headwayMillis <= 0) {
				errors.add("线路 " + lineId + " 密度段 " + segment + " 的发车间隔必须为正（现在 " + segment.headwayMillis + " ms）");
			}
		}
		final ObjectArrayList<Segment> sorted = sortedSegments();
		for (int i = 1; i < sorted.size(); i++) {
			final Segment previous = sorted.get(i - 1);
			final Segment next = sorted.get(i);
			if (next.fromMillis < previous.toMillis) {
				errors.add("线路 " + lineId + " 密度段重叠：" + previous + " 与 " + next + " 在 "
					+ hhmm(next.fromMillis) + "–" + hhmm(previous.toMillis) + " 上重叠");
			} else if (next.fromMillis > previous.toMillis) {
				errors.add("线路 " + lineId + " 运营时段未覆盖：" + previous + " 与 " + next + " 之间 "
					+ hhmm(previous.toMillis) + "–" + hhmm(next.fromMillis) + " 没有密度段");
			}
		}
		return errors;
	}

	/** {@code 25_200_000} → {@code "07:00"}（诊断里一律给人看时刻，不给人看毫秒）。 */
	public static String hhmm(long millisOfDay) {
		final long normalized = ((millisOfDay % 86_400_000L) + 86_400_000L) % 86_400_000L;
		final long minutes = normalized / 60_000L;
		return String.format("%02d:%02d", minutes / 60, minutes % 60);
	}

	@Override
	public String toString() {
		final StringBuilder out = new StringBuilder("线路 " + lineId + " 密度 ");
		final ObjectArrayList<Segment> sorted = sortedSegments();
		for (int i = 0; i < sorted.size(); i++) {
			out.append(i == 0 ? "" : "，").append(sorted.get(i));
		}
		return out.toString();
	}
}
