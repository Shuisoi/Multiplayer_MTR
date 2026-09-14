package org.mtr.core.mmtr.plan;

import org.mtr.core.serializer.ReaderBase;

/**
 * 计划输入层里那些"人在网页上填、脚本在命令行里给"的 id 的解析。
 *
 * <p>为什么不用 {@code readerBase.getLong} 了事：车场/站台 id 是 64 位整数，浏览器
 * （JSON 数字只有 53 位精度）与手写配置都会把它们写成**字符串**。作业单那条路早就踩过这个坑
 * （{@code MmtrConsistJob.parseNumericId}），这里用同一个口径：先当字符串按十进制解析，
 * 解析不了再退回数值字段 —— 两条路都认，避免"网页存进去、引擎读成 0"。</p>
 */
final class MmtrPlanIds {

	private MmtrPlanIds() {
	}

	static long parse(ReaderBase readerBase, String key) {
		final String raw = readerBase.getString(key, "").trim();
		if (!raw.isEmpty()) {
			try {
				return Long.parseLong(raw);
			} catch (NumberFormatException ignored) {
				// 不是十进制数字：退回数值字段（老文件里可能是 JSON 数字）
			}
		}
		return readerBase.getLong(key, 0);
	}
}
