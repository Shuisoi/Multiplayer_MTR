package org.mtr.core.mmtr.physics;

/**
 * **运行阻力**（牛顿口径的 Davis 形式）：{@code R(v) = A + B·v + C·v²}。
 *
 * <p>与旧的 {@code resistanceA/B/C}（当时量纲是 m/s²，等于"每千克的力"）只差一个质量：
 * 迁移时 {@code A_N = A · m}、{@code B_N = B · m}、{@code C_N = C · m} 就是**同一条曲线**。
 * 换成牛顿之后，多挂车、换牵引单元都不会让阻力算错（这正是旧口径做不到的）。</p>
 *
 * <p>坡道与曲线阻力**不在这里**：它们要读世界数据（轨道 Y/倾角、曲率），
 * 属于"合力里另加一项"，由 {@link TrainPhysics} 的调用方按轨段给入。</p>
 */
public final class RunningResistanceSpec {

	public static final RunningResistanceSpec NONE = new RunningResistanceSpec(0, 0, 0);

	private final double aN;
	private final double bN;
	private final double cN;

	public RunningResistanceSpec(double aN, double bN, double cN) {
		this.aN = aN;
		this.bN = bN;
		this.cN = cN;
	}

	public double getAN() { return aN; }
	public double getBN() { return bN; }
	public double getCN() { return cN; }

	/** 阻力（牛顿），方向恒与运动相反；速度取绝对值。 */
	public double forceN(double speedMetersPerSecond) {
		final double v = Math.abs(speedMetersPerSecond);
		return aN + bN * v + cN * v * v;
	}
}
