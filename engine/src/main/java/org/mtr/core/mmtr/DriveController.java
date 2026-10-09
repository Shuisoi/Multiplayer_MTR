package org.mtr.core.mmtr;

/**
 * Pluggable longitudinal controller for a consist.
 *
 * <p>Pure headless step: takes the unified {@link ControlState}, the {@link ConsistType}
 * performance envelope and the current signed speed, returns the longitudinal acceleration
 * the physics layer should apply this tick. Controllers that carry state (e.g. air-brake
 * pipe/cylinder pressure) keep it in their instance; create one controller per consist.</p>
 */
public interface DriveController {

	DriveOutput compute(ControlState control, ConsistType type, double speedMetersPerSecond, long dtMillis);

	/** Reset any internal state (re-coupling, respawn, mode switch). */
	void reset();

	/**
	 * 这一拍**真正施加的轮周牵引力**（N，牵引为正）—— HUD 的"电机"行与镜像诊断用（notes/379）。
	 * 缺省 0 = 这种控制器不报（三手柄有自己的读数方法）。
	 */
	default double getLastTractionForceN() {
		return 0;
	}

	/**
	 * 这一拍**真正施加的电制动力**（N，制动为正）—— HUD 的"制动力（电）"那一份（notes/379）。
	 * 缺省 0 = 这种控制器不报。
	 */
	default double getLastElectricBrakeForceN() {
		return 0;
	}
}
