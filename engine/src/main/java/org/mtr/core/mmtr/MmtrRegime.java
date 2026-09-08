package org.mtr.core.mmtr;

/**
 * Signal regime (信号系统 v3, 制式判据定案): every rail carries two directional speed limits
 * (km/h, persisted with the rail); the regime is derived from the limit of the direction the
 * vehicle travels: ≤ 100 km/h = AWS (英国 AWS 制式: 点式警示 + 司机确认, 超速不强制), ≥ 101 km/h
 * = LZB (德铁 LZB 制式: 连续曲线监督, 超速强制). 0 km/h means the direction is unreachable and
 * never appears on a running train.
 */
public enum MmtrRegime {

	AWS,
	LZB;

	public static MmtrRegime fromSpeedLimitKmh(double speedLimitKmh) {
		return speedLimitKmh >= 101 ? LZB : AWS;
	}
}
