package org.mtr.core.mmtr.physics;

/**
 * **牵引能力**（牛顿口径）：起动牵引力 + 额定轮周功率。
 *
 * <h2>为什么是两个"力/功率"而不是"加速度 + 折点"</h2>
 *
 * <p>旧的 {@code tractionAccelerationMps2 + tractionBreakpointKmh} 把"恒力矩段多大、折点在哪"
 * 直接写成两个数，于是"质量多少、装机功率多少"这些**真车就有的量**在模型里根本不存在 ——
 * 多挂一节车、换一台机车，谁都算不出来手感该变成什么。这里换成力：</p>
 *
 * <pre>
 *   F(v) = min(F_max · ratio, P · ratio / v)     // 恒力矩 → 恒功率；折点 v_bp = P / F_max **不用填**
 * </pre>
 *
 * <p>质量与回转质量系数在 {@link TrainPhysics} 上（车列属性），不在这里 ——
 * 拖车与机车的质量一样、牵引能力不一样，这样拆才表达得出来。</p>
 *
 * <p>单位：牛顿、瓦、米/秒（**SI**）。引擎内部 m/ms 的换算由调用方做（{@code MmtrSupport}）。</p>
 */
public final class TractionSpec {

	/** 无动力（拖车 / 被牵引的车节）：牵引力恒为 0。 */
	public static final TractionSpec POWERLESS = new TractionSpec(0, 0);

	private final double maxTractiveEffortN;
	private final double maxPowerW;
	/** 弱磁（自然特性）段起点（m/s）；{@code 0} = 不启用第三段（一路恒功率到顶速，BR 101 就是这样）。 */
	private final double fieldWeakeningSpeedMps;

	public TractionSpec(double maxTractiveEffortN, double maxPowerW) {
		this(maxTractiveEffortN, maxPowerW, 0);
	}

	/**
	 * @param fieldWeakeningSpeedMps 弱磁升速段起点（m/s）：超过它之后 {@code F ∝ 1/v²}。
	 *                               规格见 {@code docs/01-设计/列车纵向动力学-指标与模型.md} §2 的第三段。
	 *                               **BR 101 不需要**（真车表：恒功率一路到 220 km/h，220 km/h 仍给
	 *                               ~104.7 kN = 6.4 MW / 61.1 m/s），所以缺省 0 = 关闭；
	 *                               给"弱磁点较早"的其它车底留的接口。
	 */
	public TractionSpec(double maxTractiveEffortN, double maxPowerW, double fieldWeakeningSpeedMps) {
		this.maxTractiveEffortN = Math.max(0, maxTractiveEffortN);
		this.maxPowerW = Math.max(0, maxPowerW);
		this.fieldWeakeningSpeedMps = Math.max(0, fieldWeakeningSpeedMps);
	}

	public double getMaxTractiveEffortN() { return maxTractiveEffortN; }
	public double getMaxPowerW() { return maxPowerW; }
	/** 弱磁段起点（m/s）；0 = 不启用第三段。 */
	public double getFieldWeakeningSpeedMps() { return fieldWeakeningSpeedMps; }

	/** 恒力矩段与恒功率段的折点速度（m/s）：{@code v_bp = P / F_max}；无动力 / 无功率时为 0。 */
	public double breakpointMetersPerSecond() {
		return maxTractiveEffortN <= 0 || maxPowerW <= 0 ? 0 : maxPowerW / maxTractiveEffortN;
	}

	/**
	 * 牵引力（牛顿）：手柄比例 {@code ratio} **同时缩放牵引力上限与功率上限** ——
	 * 于是部分手柄 = "更低的恒力矩段 + 更低的功率"（真车电阻/斩波控制就是这样），
	 * 而不是把整条曲线平移。
	 */
	public double effortN(double ratio, double speedMetersPerSecond) {
		final double r = Math.max(0, Math.min(1, ratio));
		if (r <= 0 || maxTractiveEffortN <= 0) {
			return 0;
		}
		final double effortLimit = maxTractiveEffortN * r;
		final double v = Math.max(0, speedMetersPerSecond);
		if (maxPowerW <= 0 || v <= 0) {
			return effortLimit;
		}
		final double powerLimited = maxPowerW * r / v;
		// 第三段（可选）：弱磁升速，F ∝ 1/v²（以弱磁点为锚，保证曲线连续）
		final double fieldWeakened = fieldWeakeningSpeedMps > 0 && v > fieldWeakeningSpeedMps
			? maxPowerW * r * fieldWeakeningSpeedMps / (v * v)
			: Double.MAX_VALUE;
		return Math.min(effortLimit, Math.min(powerLimited, fieldWeakened));
	}

	/** 折点以上就进入恒功率段（用于诊断/校验，运行时不依赖）。 */
	public boolean isPowerLimitedAt(double speedMetersPerSecond) {
		return speedMetersPerSecond > breakpointMetersPerSecond();
	}
}
