package org.mtr.core.mmtr;

import org.mtr.core.mmtr.physics.DynamicsEnvelope;

/**
 * Overrun/SPAD protection decision (SCR/TPWS-style), kept pure so it can be unit-tested and
 * mirrored identically by clients.
 *
 * <p>Units follow the engine internals: speeds in m/ms and accelerations in m/ms^2. The rule:
 * a moving manual mmtr consist must start an emergency stop as soon as the deceleration needed
 * to halt before its {@code stoppingPoint} exceeds the emergency envelope (with a 5% margin).</p>
 */
public final class MmtrProtection {

	/**
	 * Hold time (ms) after an overrun/SPAD emergency stop before the driver can take control
	 * again.
	 */
	public static final long LOCK_MILLIS = 10_000;
	/** Margin above the emergency envelope before protection trips. */
	private static final double MARGIN = 1.05;

	private MmtrProtection() {
	}

	/**
	 * @param speedInternal        current speed (m/ms)
	 * @param remainingDistance    distance to the stopping point ({@code <= 0} = already past it)
	 * @param emergencyDecelInternal max emergency deceleration (m/ms^2)
	 * @return {@code true} when overrun protection must engage
	 */
	public static boolean requiresProtection(double speedInternal, double remainingDistance, double emergencyDecelInternal) {
		if (speedInternal <= 0 || emergencyDecelInternal <= 0) {
			return false;
		}
		if (remainingDistance <= 0) {
			return true;
		}
		// 包线数学只有一份（notes/234）：所需减速度是否已超过紧急包线的 MARGIN 倍。
		return DynamicsEnvelope.requiresBraking(speedInternal, 0, remainingDistance, emergencyDecelInternal, MARGIN);
	}
}
