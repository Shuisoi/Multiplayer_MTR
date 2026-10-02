package org.mtr.mod.mmtr.face;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 纯数据层里攒下来的"要提示一次"的账（notes/361）。
 *
 * <p>为什么要有它：扩展字段的 {@code value()} 是**别人写的代码**，它抛异常时我们只想跳过那一个字段，
 * 但纯数据层（{@code mmtr.face}）**没有日志**（它要能离线跑、也要能进用例）。于是这里先攒着，
 * 由游戏侧的绘制运行时每帧 {@link #drain()} 一次并写进日志；离线工具（{@code face-preview}）出图前
 * 也可以 drain 一遍打到控制台。空集合时 drain 是零代价的（每帧调用安全）。</p>
 */
public final class MmtrFaceWarnings {

	/** 上限：坏东西每帧都报的话，这里就是内存泄漏了（只保留最早的若干条）。 */
	private static final int MAX = 64;
	private static final Set<String> PENDING = new LinkedHashSet<>();

	private MmtrFaceWarnings() {
	}

	/** 记一条（去重；超过上限就丢掉新的）。 */
	public static synchronized void warn(String message) {
		if (PENDING.size() < MAX) {
			PENDING.add(message);
		}
	}

	/** 取走并清空（调用方负责写日志）。 */
	public static synchronized List<String> drain() {
		if (PENDING.isEmpty()) {
			return List.of();
		}
		final List<String> drained = new ArrayList<>(PENDING);
		PENDING.clear();
		return drained;
	}

	public static synchronized void clear() {
		PENDING.clear();
	}
}
