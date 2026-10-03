package org.mtr.core.mmtr;

/**
 * 单位换算：MMTR 的操纵模型用 SI（m/s、m/s²），引擎内部用 m/ms、m/ms²。
 *
 * <p>notes/235：原来这里还有"把原版单手柄 {@code powerLevel}（-8 紧急 … -1 常用制动 … 1..7 牵引）
 * 折算成 {@link ControlState} 的 {@code controlFromLegacyPowerLevel} 与
 * {@code LEGACY_EMERGENCY_POWER_LEVEL}。那套映射属于被删除的原版加减速模型 ——
 * 现在唯一的操纵输入就是 {@link ControlState}（三根手柄 + 定速），没有"单手柄"这个概念了。</p>
 */
public final class MmtrSupport {

	private MmtrSupport() {
	}

	/** SI acceleration (m/s^2) -> engine internal (m/ms^2). */
	public static double siAccelerationToInternal(double metersPerSecondSquared) {
		return metersPerSecondSquared * 1e-6;
	}

	/** Engine internal speed (m/ms) -> SI (m/s). */
	public static double internalSpeedToSi(double metersPerMillisecond) {
		return metersPerMillisecond * 1000.0;
	}

	/** SI speed (m/s) -> engine internal (m/ms). */
	public static double siSpeedToInternal(double metersPerSecond) {
		return metersPerSecond * 0.001;
	}
}
