package org.mtr.core.mmtr.plan;

import org.jspecify.annotations.Nullable;

/**
 * P5：**事件抽象类**（{@code 任务系统-线路派生与车底交路-设计.md} §6.1）。
 *
 * <p>用户口径：<b>"事件类型极其多，但无非就是恶劣天气降速、故障、卧轨、某站客流激增临时高峰期等，
 * 说白了就是现实里那点事"</b> —— 所以**抽象在前、细分在后**：新事件类型只需回答三件事
 * （影响什么 / 从何时到何时 / 为什么），不必改生成器。</p>
 *
 * <pre>
 * MmtrEvent（抽象）
 *   ├─ 类型 / 严重度
 *   ├─ target        目标：车站 | 区段(一组轨) | 车次 | 车列 | 全线
 *   ├─ startMillis   生效时刻（当日毫秒）
 *   ├─ endMillis     预计结束（可变；未知 = -1，由人工或后续事件终止）
 *   ├─ reason        给人看的理由文案   ← "赋予时间理由推送前台展示"
 *   └─ state         未生效 / 生效中 / 已结束（由时钟算，不自己存）
 * </pre>
 *
 * <p><b>事件不落盘回输入层</b>（§6.3）：它是运行时扰动，只改"本次重算的输入"；
 * 配置文件里那份是**规则**（{@code MmtrEventRule}，P6 的配置页），不是事件实例。</p>
 */
public abstract class MmtrEvent {

	/** 四类细分（§6.2）。新类型往这里加，不必碰生成器。 */
	public enum Kind {
		/** 临时高峰：车站客流激增 → 局部缩短发车间隔 / 加开临时车次（**动计划层**）。 */
		PEAK_SURGE,
		/** 延误：车次/车列晚点 → 保表（换车）或保车（平移趟次）（**动运用层**）。 */
		DELAY,
		/** 故障：车列下线（→ 替补顶替，§8.1）或区段不可用（→ 进路不可达）。 */
		FAULT,
		/** 降速：区段限速下降 → **动态约束**，进 min 链（§6.4，与姊妹文档唯一接合点）。 */
		SPEED_RESTRICTION
	}

	/** 影响谁（§6.1 的 target）。 */
	public enum TargetKind {
		LINE,
		STATION,
		/** 区段：一组轨（{@link #targetRailHex}）。 */
		SECTION,
		/** 车列（编组代码或车辆 id 的字符串形式，由使用者约定）。 */
		CONSIST
	}

	/** 未生效 / 生效中 / 已结束（由时钟算出来，不自己存状态 —— 存了就会和时钟不一致）。 */
	public enum State {
		PENDING,
		ACTIVE,
		ENDED
	}

	public final String eventId;
	/** 人看的理由文案（前台展示的契约：网页事件条 / HUD / 站台屏都能读）。 */
	public String reason = "";
	public final TargetKind targetKind;
	/** 目标 id：车站 id / 车列代号（按字符串约定）/ 线路 id 用 {@link #targetText}。 */
	public long targetRef;
	/** 目标的文字形式（线路 id、编组代码；空 = 不用）。 */
	public String targetText = "";
	/** 区段目标：一组轨（{@link TargetKind#SECTION}）。 */
	public String targetRailHex = "";
	/** 生效时刻（当日毫秒）。 */
	public long startMillis;
	/** 预计结束（当日毫秒）；{@code -1} = 未知，由人工或后续事件终止。 */
	public long endMillis = -1;
	/** 严重度（0 = 提示 … 3 = 严重）；前台可以按它排色。 */
	public int severity;

	protected MmtrEvent(String eventId, TargetKind targetKind) {
		this.eventId = eventId == null ? "" : eventId;
		this.targetKind = targetKind;
	}

	public abstract Kind kind();

	/** 前台一行摘要（"临时高峰（车站 5）：预计 12 分钟后恢复 —— 客流激增"）。 */
	public String describe(long dayTimeMillis) {
		return kindName() + "（" + targetName() + "）" + stateName(dayTimeMillis)
			+ (remainingText(dayTimeMillis).isEmpty() ? "" : "：" + remainingText(dayTimeMillis))
			+ (reason.isEmpty() ? "" : " —— " + reason);
	}

	/** 由时钟算状态（§6.1 的 state）。 */
	public State stateAt(long dayTimeMillis) {
		if (dayTimeMillis < startMillis) {
			return State.PENDING;
		}
		return endMillis >= 0 && dayTimeMillis >= endMillis ? State.ENDED : State.ACTIVE;
	}

	public boolean isActiveAt(long dayTimeMillis) {
		return stateAt(dayTimeMillis) == State.ACTIVE;
	}

	/** 还要持续多久（§6.3「预计 12 分钟后恢复」）；未知结束时间或已结束 = 空串。 */
	public String remainingText(long dayTimeMillis) {
		if (endMillis < 0) {
			return "结束时间未知";
		}
		final long remaining = endMillis - Math.max(dayTimeMillis, startMillis);
		if (remaining <= 0) {
			return "";
		}
		final long minutes = (remaining + 59_999) / 60_000;
		return minutes >= 60
			? "预计 " + (minutes / 60) + " 小时 " + (minutes % 60) + " 分钟后恢复"
			: "预计 " + minutes + " 分钟后恢复";
	}

	/** 结束这件事（人工或后续事件终止）。 */
	public void endAt(long dayTimeMillis) {
		endMillis = Math.max(0, dayTimeMillis);
	}

	public String kindName() {
		return switch (kind()) {
			case PEAK_SURGE -> "临时高峰";
			case DELAY -> "延误";
			case FAULT -> "故障";
			case SPEED_RESTRICTION -> "降速";
		};
	}

	public String targetName() {
		return switch (targetKind) {
			case LINE -> "线路 " + (targetText.isEmpty() ? targetRef : targetText);
			case STATION -> "车站 " + targetRef;
			case SECTION -> "区段 " + (targetRailHex.isEmpty() ? "?" : shortHex(targetRailHex));
			case CONSIST -> "车列 " + (targetText.isEmpty() ? String.valueOf(targetRef) : targetText);
		};
	}

	public String stateName(long dayTimeMillis) {
		return switch (stateAt(dayTimeMillis)) {
			case PENDING -> "未生效";
			case ACTIVE -> "生效中";
			case ENDED -> "已结束";
		};
	}

	private static String shortHex(String hex) {
		return hex.length() <= 10 ? hex : hex.substring(0, 10) + "…";
	}

	// ---------------------------------------------------------------- 四类细分（§6.2）

	/**
	 * **临时高峰**（车站客流激增）：把某个时间窗里的发车间隔压小（→ 趟次加密）。
	 *
	 * <p>用户原话就是"某站客流激增临时高峰期"。影响**计划层**：只改未来趟次，
	 * 已经跑过的趟次一行不动（§7 的过去不可改）。</p>
	 */
	public static final class PeakSurge extends MmtrEvent {
		/** 高峰窗内的发车间隔（ms）；必须 &gt; 0。 */
		public long headwayMillis;

		public PeakSurge(String eventId, long stationId, long startMillis, long endMillis, long headwayMillis) {
			super(eventId, TargetKind.STATION);
			this.targetRef = stationId;
			this.startMillis = startMillis;
			this.endMillis = endMillis;
			this.headwayMillis = headwayMillis;
		}

		@Override
		public Kind kind() {
			return Kind.PEAK_SURGE;
		}
	}

	/** **延误**：车列（或车次）晚点 {@link #delayMillis}；两种策略（§8.1）。 */
	public static final class Delay extends MmtrEvent {
		/** 保表（换车）：趟次时刻不变，换执行车辆。 */
		public static final int STRATEGY_KEEP_TIMETABLE = 0;
		/** 保车（平移）：车不变，它未来的趟次整体后移。 */
		public static final int STRATEGY_KEEP_VEHICLE = 1;

		public long delayMillis;
		/** {@link #STRATEGY_KEEP_TIMETABLE} 或 {@link #STRATEGY_KEEP_VEHICLE}；默认保表（乘客体验优先）。 */
		public int strategy = STRATEGY_KEEP_TIMETABLE;

		public Delay(String eventId, String consistId, long startMillis, long delayMillis) {
			super(eventId, TargetKind.CONSIST);
			this.targetText = consistId == null ? "" : consistId;
			this.targetRef = 0;
			this.startMillis = startMillis;
			this.endMillis = -1;   // 未知：由恢复事件或人工终止
			this.delayMillis = delayMillis;
		}

		public Delay withStrategy(int strategy) {
			this.strategy = strategy;
			return this;
		}

		public boolean keepsTimetable() {
			return strategy == STRATEGY_KEEP_TIMETABLE;
		}

		@Override
		public Kind kind() {
			return Kind.DELAY;
		}
	}

	/** **故障**：车列下线（→ 替补顶替，P6）；或区段不可用（→ 进路不可达）。 */
	public static final class Fault extends MmtrEvent {
		/** 下线的是车列（{@link #targetText} = 编组代码）。 */
		public final boolean vehicleDown;

		public Fault(String eventId, String consistId, long startMillis, boolean vehicleDown) {
			super(eventId, vehicleDown ? TargetKind.CONSIST : TargetKind.SECTION);
			this.targetText = consistId == null ? "" : consistId;
			this.targetRef = 0;
			this.startMillis = startMillis;
			this.endMillis = -1;
			this.vehicleDown = vehicleDown;
		}

		@Override
		public Kind kind() {
			return Kind.FAULT;
		}
	}

	/**
	 * **降速**：区段允许速度下降 —— 这是 {@code 信号系统…设计.md §1.2} 那个**动态约束**层的
	 * 第一个真实成员，进 {@code min(轨限速, 动态约束, 目标距离制动曲线)} 链（§6.4）。
	 *
	 * <p>⚠️ 跨文档依赖：接进 min 链要等姊妹文档的动态约束层。P5 先做**事件存在、前台可见**，
	 * 以及"计划期的参考速度取 min(编组速度, 本事件限速)"这一半（不影响执行期保护）。</p>
	 */
	public static final class SpeedRestriction extends MmtrEvent {
		public double speedKmh;

		public SpeedRestriction(String eventId, String railHex, long startMillis, long endMillis, double speedKmh) {
			super(eventId, TargetKind.SECTION);
			this.targetRailHex = railHex == null ? "" : railHex;
			this.startMillis = startMillis;
			this.endMillis = endMillis;
			this.speedKmh = speedKmh;
		}

		@Override
		public Kind kind() {
			return Kind.SPEED_RESTRICTION;
		}
	}

	/** 目标是不是这条线路（线路级事件对所有线路生效；车列/车站/区段级只影响对应目标）。 */
	public boolean targetsLine(@Nullable String lineId) {
		return targetKind == TargetKind.LINE && (targetText.isEmpty() || targetText.equals(lineId));
	}
}
