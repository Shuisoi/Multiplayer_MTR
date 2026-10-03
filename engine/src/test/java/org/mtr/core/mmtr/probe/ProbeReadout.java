package org.mtr.core.mmtr.probe;

import java.util.List;

/**
 * 从 {@link MmtrProbe} 的**人读汇总**里把数字读回来（只给测试用）。
 *
 * <h2>为什么需要它，而不是"直接调个 getter"</h2>
 * <p>探针的输出是**故意**保持人读的一行行文本：它要同时出现在服务端日志、网页指令栏、
 * 明细文件三处，而这三处都只能吃字符串。测试因此也要从同一条文本里读数 —— 这反而是好事：
 * <b>断言与运维看到的是同一份东西</b>，格式一旦改坏，测试当场红。</p>
 *
 * <h2>三个已经踩过的坑（都写进注释，免得下一版再踩）</h2>
 * <ol>
 *   <li>分段行**只在窗口里有段读数时才出现** ⇒ 必须扫所有行，不能写死取第 3 行
 *       （第一版写死取行，症状是读数恒 0/0）。</li>
 *   <li>分段行的标题里**本身带一个 {@code /}**（"累计毫秒／次数"）⇒ 取"次数"绝不能
 *       {@code segment.indexOf("/")}，那会命中标题里那个斜杠，读到的是标题后半句
 *       （实测症状最阴的一次：{@code solve} 恒 0，而排在后面的 {@code hit} 因为段里没有第二个斜杠反而读得到，
 *       于是"命中率 100%"这种假结论就出来了）。必须**先定位段名，再从段名之后**找标记。</li>
 *   <li>第一个段名前面挂着标题（"—— " 开头）⇒ 不能用 {@code startsWith} 匹配段名，要用 {@code contains}。</li>
 * </ol>
 */
final class ProbeReadout {

	private ProbeReadout() {
	}

	/**
	 * 取某一段的**计时段**调用次数（{@code 名字=12ms/345 …} 里的 345）。
	 *
	 * @param sectionName 段名（含 {@code =}，例如 {@code "projection.solve="}）
	 */
	static long callCount(List<String> summary, String sectionName) {
		return maxOf(summary, sectionName, "/");
	}

	/**
	 * 取某一段的**事件**次数（{@code 名字=… ev=345} 里的 345）。
	 *
	 * <p>与 {@link #callCount} 分开是刻意的：命中、重建次数、跳过次数这类量**不耗时**，
	 * 记的是事件数（{@code ev=}），而计时段记的是调用次数（{@code /} 后面）。混用会读出 0。</p>
	 */
	static long eventCount(List<String> summary, String sectionName) {
		return maxOf(summary, sectionName, "ev=");
	}

	/** 取某一段的峰值（{@code peak=…}）。 */
	static long peak(List<String> summary, String sectionName) {
		return maxOf(summary, sectionName, "peak=");
	}

	/**
	 * 扫所有行、所有 {@code |} 分隔的段，取含 {@code sectionName} 的那一段里 {@code marker} 之后的整数（多条取最大）。
	 *
	 * <p>搜索起点取 <b>段名之后</b>而不是段首 —— 见类注释第 ② 条：段首到段名之间是标题，
	 * 而标题里本身带着一个 {@code /}。</p>
	 */
	static long maxOf(List<String> summary, String sectionName, String marker) {
		long best = 0;
		for (final String line : summary) {
			for (final String segment : line.split("\\|")) {
				final int nameAt = segment.indexOf(sectionName);
				if (nameAt >= 0) {
					best = Math.max(best, numberAfter(segment, marker, nameAt + sectionName.length()));
				}
			}
		}
		return best;
	}

	/**
	 * 从 {@code from} 起找 {@code marker}，取它之后紧跟的整数（找不到返回 0）。
	 *
	 * <p>{@code from} 这个参数就是上面第 ② 条坑的解法：段名之前的标题里可能有一个同形字符。</p>
	 */
	static long numberAfter(String segment, String marker, int from) {
		final int at = segment.indexOf(marker, Math.max(0, from));
		if (at < 0) {
			return 0;
		}
		final StringBuilder digits = new StringBuilder();
		for (int i = at + marker.length(); i < segment.length(); i++) {
			final char c = segment.charAt(i);
			if (Character.isDigit(c)) {
				digits.append(c);
			} else {
				break;
			}
		}
		return digits.isEmpty() ? 0 : Long.parseLong(digits.toString());
	}
}
