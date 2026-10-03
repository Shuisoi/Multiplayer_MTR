package org.mtr.core.mmtr.point;

import org.jspecify.annotations.Nullable;

/**
 * **列车优先级（服务等级 + 车号）**：两台车同时抢一处道岔时，谁先走。
 *
 * <p>用户口径（2026-09-27）：</p>
 * <blockquote>
 * 「如果同时抢一个道岔，那就得设计个优先级系统了，同级（通勤）谁车号小谁先走，
 *   不同级（如区域，高铁）则按级别踩头」
 * </blockquote>
 *
 * <p>于是裁决键是**两级**的：先比服务等级（等级高的踩等级低的头），等级相同再比车号
 * （车号小的先走）。车号不用在车上另存一份 —— 作业单号本身就按「前三位线路号 + 后二位车号」命名
 * （{@code 00101} = 001 线的 1 号车），所以从 {@code jobId} 末尾那两位直接取。</p>
 *
 * <p><b>与既有裁决键的关系</b>：这一档排在**计划时刻之前**。计划时刻（notes/151、152 的 T5/T1b）
 * 说的是"谁本来该几点到"，而服务等级/车号是**运营规则**说的"谁该先走"；真车现场也是调度级别压过
 * 时刻表。两头都问不出优先级时（玩家车、没有作业单的调车、测试夹具的 {@code vA}/{@code vB}）
 * 逐位退回老口径（计划时刻 → 入队序 → owner id），所以既有基线一动不动。</p>
 *
 * <p>本类是**不可变值**，由 {@link MmtrPointAuthority.OwnerPriority} 每次裁决时按 owner 现问
 * （车挂上别的作业单、或作业单换了等级，下一次裁决就按新的算，不留缓存）。</p>
 */
public final class MmtrTrainPriority {

	/** 车号问不出来（作业单号没有数字尾巴）时用它 —— 比较时排在所有有车号的车之后。 */
	public static final long NO_TRAIN_NUMBER = Long.MAX_VALUE;

	/**
	 * 服务等级（越大越优先）。名字与数字都认：作业单里写 {@code serviceClass: "高铁"} 或 {@code "3"}。
	 *
	 * <p>认不出来的名字按 {@link #COMMUTER} 处理 —— 老作业单没这个字段，语义必须是"最低档"，
	 * 不能因为拼错一个词就把整条线的车抬成高铁。</p>
	 */
	public enum ServiceClass {
		COMMUTER(0, "通勤"),
		REGIONAL(1, "区域"),
		INTERCITY(2, "城际"),
		HIGH_SPEED(3, "高铁");

		public final int rank;
		public final String label;

		ServiceClass(int rank, String label) {
			this.rank = rank;
			this.label = label;
		}

		/** 名字（中/英、大小写、空格、下划线都容错）或数字；空/认不出 = 通勤。 */
		public static ServiceClass parse(@Nullable String text) {
			if (text == null) {
				return COMMUTER;
			}
			final String t = text.trim().toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
			switch (t) {
				case "通勤":
				case "普速":
				case "commuter":
				case "local":
					return COMMUTER;
				case "区域":
				case "regional":
				case "region":
					return REGIONAL;
				case "城际":
				case "intercity":
					return INTERCITY;
				case "高铁":
				case "高速":
				case "highspeed":
				case "highspeedrail":
				case "hsr":
				case "express":
					return HIGH_SPEED;
				default:
					break;
			}
			try {
				return nearest(Integer.parseInt(t));
			} catch (NumberFormatException e) {
				return COMMUTER;
			}
		}

		/** 数字等级 → 枚举：取**不超过**它的最高档（{@code 7} 落到高铁，{@code -1} 落到通勤）。 */
		public static ServiceClass nearest(int rank) {
			ServiceClass best = COMMUTER;
			for (final ServiceClass candidate : values()) {
				if (candidate.rank <= rank) {
					best = candidate;
				}
			}
			return best;
		}
	}

	public final int serviceRank;
	/** 人话等级名（日志/报告用）；数字等级超出枚举时写数字本身。 */
	public final String serviceLabel;
	public final long trainNumber;
	/** 来源作业单号（人话说明用）；可能为空（只有等级、没有作业单）。 */
	public final String jobId;

	private MmtrTrainPriority(int serviceRank, String serviceLabel, long trainNumber, String jobId) {
		this.serviceRank = serviceRank;
		this.serviceLabel = serviceLabel;
		this.trainNumber = trainNumber;
		this.jobId = jobId;
	}

	/**
	 * 从「作业单号 + 服务等级」读出优先级。
	 *
	 * @return 优先级；**两者都空 ⇒ {@code null}**（无可奉告 —— 调用方退回老口径，
	 *         这正是"没有作业单的运行逐位不变"的守卫）
	 */
	public static @Nullable MmtrTrainPriority of(@Nullable String jobId, @Nullable String serviceClass) {
		final String id = jobId == null ? "" : jobId.trim();
		final String cls = serviceClass == null ? "" : serviceClass.trim();
		if (id.isEmpty() && cls.isEmpty()) {
			return null;
		}
		final ServiceClass parsed = ServiceClass.parse(cls);
		final int rank = cls.isEmpty() ? parsed.rank : rankOf(cls, parsed);
		return new MmtrTrainPriority(rank, labelOf(rank, parsed), trainNumberOf(id), id);
	}

	/** 数字等级原样保留（{@code "5"} 就是 5 级，压过枚举里的高铁 3），名字则取枚举的档位。 */
	private static int rankOf(String serviceClass, ServiceClass parsed) {
		try {
			return Integer.parseInt(serviceClass.trim());
		} catch (NumberFormatException e) {
			return parsed.rank;
		}
	}

	private static String labelOf(int rank, ServiceClass parsed) {
		for (final ServiceClass candidate : ServiceClass.values()) {
			if (candidate.rank == rank) {
				return candidate.label;
			}
		}
		return String.valueOf(rank);
	}

	/**
	 * 作业单号 → 车号：取末尾连续数字，再取**末两位**（「前三位线路号 + 后二位车号」）。
	 *
	 * <p>{@code "00101" → 1}、{@code "00110" → 10}；没有数字尾巴（{@code "morning"}）或只有一位时
	 * 按现有位数取（{@code "7" → 7}）。长度不足两位的数字段整段就是车号 —— 这样作业单号还没编满
	 * 位数的调试单也不会被判成同一号。</p>
	 */
	public static long trainNumberOf(@Nullable String jobId) {
		final String id = jobId == null ? "" : jobId.trim();
		int start = id.length();
		while (start > 0 && Character.isDigit(id.charAt(start - 1))) {
			start--;
		}
		if (start == id.length()) {
			return NO_TRAIN_NUMBER;
		}
		final String digits = id.substring(start);
		final String car = digits.length() > 2 ? digits.substring(digits.length() - 2) : digits;
		try {
			return Long.parseLong(car);
		} catch (NumberFormatException e) {
			return NO_TRAIN_NUMBER;
		}
	}

	/**
	 * 我是不是排在 {@code other} **前面**（等级高者先；同级车号小者先）。
	 *
	 * <p>等价时**两个方向都返回 false** —— 调用方据此落到下一档键（计划时刻/入队序），
	 * 所以这里不能有"随手定个先后"的兜底，否则会盖掉老口径。</p>
	 */
	public boolean outranks(@Nullable MmtrTrainPriority other) {
		if (other == null) {
			return true;   // 有明确优先级的车排在"问不出优先级"的车前面
		}
		if (serviceRank != other.serviceRank) {
			return serviceRank > other.serviceRank;
		}
		return trainNumber != other.trainNumber && trainNumber < other.trainNumber;
	}

	/** 日志/报告里的人话：「00101（通勤 车号 01）」或「高铁 车号 03」。 */
	public String describe() {
		final String level = serviceLabel + (trainNumber == NO_TRAIN_NUMBER ? " 无车号" : " 车号 " + trainNumber);
		return jobId.isEmpty() ? level : jobId + "（" + level + "）";
	}

	@Override
	public String toString() {
		return describe();
	}
}
