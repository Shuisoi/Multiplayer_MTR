package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

/**
 * P1 输入层：**线路**（{@code 任务系统-线路派生与车底交路-设计.md} §4.1）。
 *
 * <p>线路回答的是"这条线一天怎么跑"：按顺序停哪些站、每站停多久、到终点怎么办、从哪个车场
 * 出库、首发前多久出库。**这一层是持久化的**（{@link MmtrPlanInputs}）—— 计划本身不落盘，
 * 它永远是 {@code f(线路, 密度, 车底, 事件, 时钟)} 算出来的（设计 §1.2）。</p>
 *
 * <p>{@link #validate()} 只做**纯结构校验**（不碰世界、不碰轨图），所以它能在加载时立刻跑、
 * 也能被 WEB 端在"保存之前"跑一次：站数、停站时长、站序闭合性、出库前移量。
 * 走行时间不在这里 —— 那是 P2/P3 从轨图算的（本类不猜）。</p>
 */
public final class MmtrLine implements SerializedDataBase {

	/** 终点处理三态（设计 §5.2）—— 每一种都直接对应一个已有任务类型，不新增词汇。 */
	public enum TerminalTreatment {
		/** 原地换端 → {@code CHANGE_ENDS}。 */
		CHANGE_ENDS,
		/** 灯泡线掉头 → {@code DRIVE_TURNBACK}。 */
		TURNBACK,
		/** 直接回库 → {@code DRIVE_TO_SIDING}。 */
		STABLE;

		public static TerminalTreatment parse(@Nullable String raw) {
			if (raw != null) {
				for (final TerminalTreatment value : values()) {
					if (value.name().equalsIgnoreCase(raw.trim())) {
						return value;
					}
				}
			}
			return CHANGE_ENDS;
		}
	}

	/** 一站：停哪个站台、停多久。 */
	public static final class Stop implements SerializedDataBase {
		public long stationId;
		public long platformId;
		/** 停站时长（ms）：车门开着的那一段。 */
		public long dwellMillis;

		public Stop() {
		}

		public Stop(long stationId, long platformId, long dwellMillis) {
			this.stationId = stationId;
			this.platformId = platformId;
			this.dwellMillis = dwellMillis;
		}

		public Stop(ReaderBase readerBase) {
			updateData(readerBase);
		}

		@Override
		public void updateData(ReaderBase readerBase) {
			stationId = MmtrPlanIds.parse(readerBase, "stationId");
			platformId = MmtrPlanIds.parse(readerBase, "platformId");
			dwellMillis = readerBase.getLong("dwellMillis", 0);
		}

		@Override
		public void serializeData(WriterBase writerBase) {
			writerBase.writeString("stationId", String.valueOf(stationId));
			writerBase.writeString("platformId", String.valueOf(platformId));
			writerBase.writeLong("dwellMillis", dwellMillis);
		}

		/** 同一站台（站序闭合性/重复停站都按这一对判）。 */
		public boolean sameStopAs(@Nullable Stop other) {
			return other != null && stationId == other.stationId && platformId == other.platformId;
		}

		@Override
		public String toString() {
			return stationId + "/" + platformId + "@" + Math.round(dwellMillis / 1000.0) + "s";
		}
	}

	public String lineId = "";
	public String name = "";
	public final ObjectArrayList<Stop> stops = new ObjectArrayList<>();
	/** 终点处理；{@link #loop} 为真时**退化为"继续"**（设计 §4.1）。 */
	public TerminalTreatment terminalTreatment = TerminalTreatment.CHANGE_ENDS;
	/** 环线：终点即起点（同理同终），此时 {@link #terminalTreatment} 不参与。 */
	public boolean loop;
	/** 出库/回库的车场股道（0 = 未指定，出库那段交路就没法生成 —— P3 会报）。 */
	public long yardSidingId;
	/** **首发前移量**：从车场出发到站台等待出发所需时间（交路的第一个任务靠它提前）。 */
	public long leadTimeMillis;

	public MmtrLine() {
	}

	public MmtrLine(String lineId, String name) {
		this.lineId = lineId;
		this.name = name;
	}

	public MmtrLine(ReaderBase readerBase) {
		updateData(readerBase);
	}

	public MmtrLine addStop(long stationId, long platformId, long dwellMillis) {
		stops.add(new Stop(stationId, platformId, dwellMillis));
		return this;
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		lineId = readerBase.getString("lineId", "");
		name = readerBase.getString("name", "");
		terminalTreatment = TerminalTreatment.parse(readerBase.getString("terminalTreatment", ""));
		loop = readerBase.getBoolean("loop", false);
		yardSidingId = MmtrPlanIds.parse(readerBase, "yardSidingId");
		leadTimeMillis = readerBase.getLong("leadTimeMillis", 0);
		readerBase.iterateReaderArray("stops", stops::clear, reader -> stops.add(new Stop(reader)));
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeString("lineId", lineId);
		if (!name.isEmpty()) {
			writerBase.writeString("name", name);
		}
		writerBase.writeString("terminalTreatment", terminalTreatment.name());
		writerBase.writeBoolean("loop", loop);
		writerBase.writeString("yardSidingId", String.valueOf(yardSidingId));
		writerBase.writeLong("leadTimeMillis", leadTimeMillis);
		writerBase.writeDataset(stops, "stops");
	}

	/** 环线没有"终点处理"，实际生效的那个（设计 §4.1）。 */
	public TerminalTreatment effectiveTerminalTreatment() {
		return loop ? TerminalTreatment.CHANGE_ENDS : terminalTreatment;
	}

	/** 沿途停站总时长（ms）—— {@code ring} 的一半（设计 §5.1 ①）。 */
	public long totalDwellMillis() {
		long total = 0;
		for (final Stop stop : stops) {
			total += Math.max(0, stop.dwellMillis);
		}
		return total;
	}

	/** 站序首尾是不是同一处站台（环线的判据）。 */
	public boolean sameEnds() {
		return stops.size() >= 2 && stops.get(0).sameStopAs(stops.get(stops.size() - 1));
	}

	/**
	 * 纯结构校验；返回**全部**问题（一行一条，空的 = 通过）。
	 *
	 * <p>站序闭合性是这里最要紧的一条（P1 验收 ②）：环线必须首尾同站同台，非环线不许首尾相同 ——
	 * 后者是"忘了勾 loop"的典型写法，放过去会生成一条永远不折返的交路。</p>
	 */
	public ObjectArrayList<String> validate() {
		final ObjectArrayList<String> errors = new ObjectArrayList<>();
		if (lineId == null || lineId.isBlank()) {
			errors.add("线路缺少 lineId");
		}
		if (stops.size() < 2) {
			errors.add("线路 " + lineId + " 至少要有 2 站（现在 " + stops.size() + "）");
		}
		for (int i = 0; i < stops.size(); i++) {
			final Stop stop = stops.get(i);
			if (stop.stationId == 0 && stop.platformId == 0) {
				errors.add("线路 " + lineId + " 第 " + (i + 1) + " 站没有站/台 id");
			}
			if (stop.dwellMillis < 0) {
				errors.add("线路 " + lineId + " 第 " + (i + 1) + " 站停站时长为负（" + stop.dwellMillis + " ms）");
			}
			if (i > 0 && stop.sameStopAs(stops.get(i - 1))) {
				errors.add("线路 " + lineId + " 第 " + (i + 1) + " 站与上一站是同一处站台（" + stop + "）");
			}
		}
		if (sameEnds() && !loop) {
			errors.add("线路 " + lineId + " 站序首尾相同，但没有标成环线（loop=false）—— 要么勾上 loop，要么终点改成别的站");
		}
		if (loop && !sameEnds() && stops.size() >= 2) {
			errors.add("线路 " + lineId + " 标成环线，但站序不闭合（首 " + stops.get(0) + "，尾 " + stops.get(stops.size() - 1) + "）");
		}
		if (leadTimeMillis < 0) {
			errors.add("线路 " + lineId + " 的首发前移量为负（" + leadTimeMillis + " ms）");
		}
		return errors;
	}

	@Override
	public String toString() {
		return "线路 " + lineId + (name.isEmpty() ? "" : "（" + name + "）") + " " + stops.size() + " 站"
			+ (loop ? " 环线" : " 终点=" + terminalTreatment);
	}
}
