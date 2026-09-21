package org.mtr.mod.client;

/**
 * 坐在车上时，原版 HUD 里哪两样东西要收起来：**物品栏（hotbar）**与**第一人称的手/物品**。
 *
 * <h2>为什么单独一个类</h2>
 *
 * <p>判据只有一处，两个 mixin（{@code InGameHudMixin}、{@code HeldItemRendererMixin}）都问这里，
 * 以后要改口径（比如"只有司机位才收"、"连经验条一起收"）只动这一个方法。</p>
 *
 * <h2>判据为什么是"骑着任何一辆车"</h2>
 *
 * <p>用户口径：「**上车后**能隐藏物品栏和手吗」—— 是"上车"，不是"进驾驶室"。
 * {@link MmtrDriverSeat#ridingVehicle()} 同时在两种情况成立：乘客上车（{@code VehicleRidingMovement.startRiding}）
 * 与进驾驶室（{@code mmtrEnterCab}），两者都把 {@code ridingVehicleId} 置上；它还会确认这辆车在客户端的
 * 车辆列表里，所以"刚下车、id 还没清"的瞬间不会误判。</p>
 *
 * <p>只想在**司机位**上收起来的话，把 {@link MmtrDriverSeat#ridingVehicle()} 换成
 * {@link MmtrDriverSeat#isAtControls()} 就行（判据同源，换一处即可）。</p>
 */
public final class MmtrVanillaHud {

	private MmtrVanillaHud() {
	}

	/** 现在要不要把物品栏和第一人称的手收起来。 */
	public static boolean hideHotbarAndHand() {
		return MmtrDriverSeat.ridingVehicle() != null;
	}
}
