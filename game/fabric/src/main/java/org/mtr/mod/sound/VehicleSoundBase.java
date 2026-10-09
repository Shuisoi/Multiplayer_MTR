package org.mtr.mod.sound;

import org.mtr.mapping.holder.BlockPos;

public abstract class VehicleSoundBase {

	public abstract void playMotorSound(BlockPos blockPos, float speed, float speedChange, float acceleration, boolean isOnRoute);

	/**
	 * **喂进这一帧的电机出力**（N，牵引为正、电阻制动为负）。
	 *
	 * <p>默认空实现，所以对现存的 BVE / legacy 音效类**没有任何影响** ——
	 * 它们本来就能从 {@code speedChange}/{@code acceleration} 推出自己要的东西。
	 * MMTR 的牵引音需要它，因为"惰行"与"起步（速度为 0 但电机在出力）"这两种情形
	 * 用速度/加速度差是分不出来的：惰行时阻力让车减速，会被误判成再生制动。</p>
	 *
	 * <p>调用点在 {@code VehicleExtension.playMotorSound}（服务端快照里的
	 * {@code mmtrMotorForceN} 每 tick 下发到客户端，见 notes/257）。</p>
	 */
	public void feedDemand(double motorForceN) {
	}

	/**
	 * **听者是不是就坐在这节车的操纵位上**（客户端本地事实）。
	 *
	 * <p>为 true 时，这节车的声音应当**忽略距离衰减、按满音量播** —— 人已经坐在驾驶室里，
	 * 不该再被"这节车中心离我 8–9.75 m"削一遍。为什么需要这条通道：MTR 的
	 * {@code playMotorSound} 只给一个 {@code BlockPos}，音效类**不知道自己是哪节车**，
	 * 也就没法自己回答"我是不是司机正在开的那节"。</p>
	 *
	 * <p>默认空实现，所以对 BVE / legacy 音效类**没有任何影响**（它们本来就没有这一级的诉求）。
	 * 调用点在 {@code VehicleExtension.playMotorSound}，判据复用的是
	 * {@code MmtrDriverSeat}（"坐在操纵位上"的唯一判据：能不能操作手柄、要不要报"我是司机"
	 * 用的是同一条）。</p>
	 */
	public void setListenerAtControls(boolean atControls) {
	}

	public final void playDoorSound(BlockPos blockPos, double doorValue, double oldDoorValue) {
		if (doorValue > 0 && oldDoorValue == 0) {
			playDoorSound(blockPos, true);
		}
		if (doorValue < getDoorCloseSoundTime() && oldDoorValue >= getDoorCloseSoundTime()) {
			playDoorSound(blockPos, false);
		}
	}

	public abstract void dispose();

	protected abstract void playDoorSound(BlockPos blockPos, boolean isOpen);

	protected abstract double getDoorCloseSoundTime();
}
