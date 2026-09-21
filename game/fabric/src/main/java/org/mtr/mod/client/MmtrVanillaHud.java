package org.mtr.mod.client;

/**
 * 坐在车上时，原版 HUD 里的"物品栏那一簇"要不要收起来：**物品栏（hotbar）**、**它上方的状态信息**
 * （血量 / 饥饿 / 护甲 / 氧气 / 经验条 / 手持物名称）以及**第一人称的手**。
 *
 * <h2>用户口径（逐字）</h2>
 *
 * <p>先问「上车后能隐藏物品栏和手吗」，再收窄成 **「只把物品栏，物品栏上的信息，隐藏，聊天一定要保留」**。
 * 所以这里**只**管那一簇，不动：聊天（{@code ChatHud}）、准星、状态效果图标（右上角那排药水）、
 * 计分板、F3 调试、字幕。也**没有**用 {@code GameOptions.hudHidden}（等同 F1）—— 那是"整条 HUD"，
 * 而用户明确说"只"。判据只有一个：{@link #hideWhileRiding()}。</p>
 *
 * <h2>为什么判据是"骑着任何一辆车"</h2>
 *
 * <p>用户说"上车"，不是"进驾驶室"。{@link MmtrDriverSeat#ridingVehicle()} 在乘客上车
 * （{@code VehicleRidingMovement.startRiding}）与进驾驶室（{@code mmtrEnterCab}）两条路上都成立，
 * 而且它还会确认这辆车在客户端的车辆列表里，所以"刚下车、id 还没清"的瞬间不会误判。</p>
 *
 * <p>只想在**司机位**上收起来，把这一行换成 {@link MmtrDriverSeat#isAtControls()} 就行（判据同源，一处改）。</p>
 */
public final class MmtrVanillaHud {

	private MmtrVanillaHud() {
	}

	/** 现在要不要把"物品栏那一簇"和第一人称的手收起来。 */
	public static boolean hideWhileRiding() {
		return MmtrDriverSeat.ridingVehicle() != null;
	}
}
