package org.mtr.mod.mmtr;

/**
 * **水牌 / PID 的文字内容**（notes/357）—— 纯函数，不碰 Minecraft 的任何类。
 *
 * <h2>为什么单独一份、而且不带 MC 依赖</h2>
 * <p>水牌画出来对不对，取决于"该写哪几个字、哪一行是主行、什么时候整块不写"。把它与画布/渲染
 * 混在一起，就只能靠进游戏看 —— 而本仓的规矩是**判据要能离线钉**（同 {@code MmtrDecoupleCache}）。
 * 于是这一份只管"内容与主次"，画的那一份（{@code MmtrPidBoard}）只管"排版与贴图"。</p>
 *
 * <h2>内容口径（引擎给的三项）</h2>
 * <ul>
 *   <li><b>水牌（DESTINATION）</b>：副行 = 班次号（作业单号），主行 = 本趟终点站名。
 *       例：{@code 00101 / 海山}（上下两行）。终点未知（回库趟）时只有副行 —— 牌上有字，但不谎报终点。</li>
 *   <li><b>下一站牌（NEXT_STATION）</b>：副行 = 固定的"下一站"三字，主行 = 站名。
 *       站名未知时**整块不画**（写个"下一站"却空着，比不写更容易误读）。</li>
 * </ul>
 *
 * <h2>什么时候整块牌都不该画</h2>
 * <p>车不挂在任何在跑的作业单上（班次号为空）⇒ 不画：车场里停着的车挂一块"开往 ——"的牌子是错的。
 * 引擎侧这时也把三项清空了，这里再判一次是为了**牌底**：空串只会画出一块黑牌，看上去像坏了。</p>
 */
public final class MmtrPidText {

	/** 牌照的两种内容。 */
	public enum Board {
		/** 水牌：班次号 + 本趟终点。 */
		DESTINATION,
		/** 下一站牌：下一站 X。 */
		NEXT_STATION
	}

	/** 车牌上"下一站"这三个字的写法。 */
	public static final String NEXT_STATION_LABEL = "下一站";

	/**
	 * 一块牌上的一行。
	 *
	 * @param text    这一行写什么
	 * @param primary 是不是**主行**（主行字大：站名；副行字小：班次号 / "下一站"）
	 */
	public record Line(String text, boolean primary) {
	}

	private MmtrPidText() {
	}

	/**
	 * 这块牌该写哪几行（**自上而下**，与画出来的顺序一致）；空数组 = **整块牌都不画**。
	 *
	 * @param board        牌照种类
	 * @param service      班次号（作业单号；空 = 不在作业单上）
	 * @param terminus     本趟终点站名（可能为空：回库趟没有站台目标）
	 * @param nextStation  下一站站名（可能为空：后面不再有站台作业）
	 */
	public static Line[] lines(Board board, String service, String terminus, String nextStation) {
		final String serviceText = clean(service);
		if (serviceText.isEmpty()) {
			// 不在任何在跑的作业单上：整列车不挂牌
			return new Line[0];
		}
		final String terminusText = clean(terminus);
		final String nextText = clean(nextStation);
		switch (board) {
			case DESTINATION:
				// 副行在上（班次号）、主行在下（终点）—— 像一块车次在上、终点在下的水牌
				return terminusText.isEmpty()
					? new Line[]{new Line(serviceText, false)}
					: new Line[]{new Line(serviceText, false), new Line(terminusText, true)};
			case NEXT_STATION:
				return nextText.isEmpty()
					? new Line[0]
					: new Line[]{new Line(NEXT_STATION_LABEL, false), new Line(nextText, true)};
			default:
				return new Line[0];
		}
	}

	/**
	 * 这块牌的**内容签名**：画过的牌只在签名变化时重画（画布不是免费的）。
	 *
	 * <p>签名里带上牌面尺寸：模型换了（牌变大变小）必须按新尺寸重画一次，否则字会被拉花。</p>
	 */
	public static String signature(Board board, String service, String terminus, String nextStation, double widthM, double heightM) {
		return board + "|" + clean(service) + "|" + clean(terminus) + "|" + clean(nextStation)
			+ "|" + round(widthM) + "x" + round(heightM);
	}

	/** 日志/验收用的一行人话：{@code 水牌 00101 / 海山}；不画时明确写出来。 */
	public static String describe(Board board, Line[] lines) {
		final String label = board == Board.DESTINATION ? "水牌" : "下一站牌";
		if (lines == null || lines.length == 0) {
			return label + "（不画）";
		}
		final StringBuilder builder = new StringBuilder(label).append(' ');
		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				builder.append(" / ");
			}
			builder.append(lines[i].text());
		}
		return builder.toString();
	}

	/**
	 * **版式里一行取哪个字段**（notes/358）：这是"版式"与"内容"之间唯一的接口。
	 *
	 * <p>认四个名字：{@code service}（班次号）、{@code terminus}（本趟终点，也认 {@code dest/destination}）、
	 * {@code next}（下一站）、以及其它任何名字 ⇒ **空串**（那一行不画，而不是画个占位符）。</p>
	 *
	 * <p>不在作业单上（班次号空）⇒ 一律空串：整块牌都不该挂，与 {@link #lines} 同一条口径。</p>
	 */
	public static String field(String fieldName, String service, String terminus, String nextStation) {
		if (clean(service).isEmpty()) {
			return "";
		}
		return switch (fieldName == null ? "" : fieldName.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "service", "number" -> clean(service);
			case "terminus", "destination", "dest" -> clean(terminus);
			case "next", "nextstation" -> clean(nextStation);
			default -> "";
		};
	}

	private static String clean(String value) {
		return value == null ? "" : value.trim();
	}

	private static String round(double value) {
		return String.valueOf(Math.round(value * 1000.0) / 1000.0);
	}
}
