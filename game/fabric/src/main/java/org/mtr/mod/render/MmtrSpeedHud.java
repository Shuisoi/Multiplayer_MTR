package org.mtr.mod.render;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.holder.Style;
import org.mtr.mapping.holder.Window;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.client.MmtrDriverSeat;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;

/**
 * 屏幕**左下角的大号速度读数**：三位数字、DIN 1451、**右斜体**（notes/223）。
 *
 * <h2>为什么逐位排版，而不是整串画一次</h2>
 *
 * <p>用户口径：「三个数字**单独排版**，**位置中点固定**，防止字宽不同导致整体伸缩」。
 * 比例字体里 {@code 1} 比 {@code 8} 窄，整串画会让读数随数字内容左右抖动（速度从 199 掉到 100 时，
 * 整块往左缩一截），这在读数是唯一信息时非常刺眼。做法是给每位一个**固定格宽**（取 0–9 里最宽的
 * 那一位量出来），各位在自己的格子里**居中**：格子中心与"这一位是谁"无关，于是 1 和 8 占同样的版位、
 * 整块宽度恒定、数字在原地变化。</p>
 *
 * <h2>斜体</h2>
 *
 * <p>用 MC 自己的斜体（{@code Style.withItalic}）：它把字形**向右剪切**，正是"右斜体"；
 * DIN 1451 本身没有斜体字重，这样也省掉再找一套字体。倾斜量由 MC 实现固定，这里不另加变换。</p>
 *
 * <h2>字体</h2>
 *
 * <p>与屏幕 HUD 同一套（{@code mtr:ui}：西文数字 = DIN 1451，中文 = HarmonyOS Sans SC）。
 * 读数全是数字，所以实际命中的一定是 DIN。</p>
 */
public final class MmtrSpeedHud {

	private MmtrSpeedHud() {
	}

	/** 距屏幕左边与下边的留白（缩放前像素）。 */
	private static final int EDGE_PADDING = 8;
	/** 大号：整串按 4 倍放大（MC 字体行高 9 px ⇒ 约 36 px 高）。 */
	private static final float SCALE = 4.0F;
	/** 三位数。 */
	private static final int DIGITS = 3;
	/** 格与格之间的呼吸（缩放前像素）。 */
	private static final int DIGIT_GAP = 2;

	private static final int DIGIT_COLOR = 0xFFFFFFFF;

	/**
	 * 固定格宽（缩放前像素）—— 取 0–9 里**最宽**的那一位，加上呼吸。
	 * 缓存：字体与样式不变时这是常量；资源重载（换字体）后由 {@link #reset()} 作废。
	 */
	private static int cachedCellWidth = -1;

	public static void render(GraphicsHolder graphicsHolder) {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		if (minecraftClient.getCurrentScreenMapped() != null) {
			return;
		}
		// 只在**坐在司机位上**时画：判据与"能不能操作手柄"同源（MmtrDriverSeat），
		// 于是不会出现"有读数却按不动"这种最费解的组合。
		if (!MmtrDriverSeat.isAtControls()) {
			return;
		}
		final VehicleExtension vehicle = MmtrDriverSeat.ridingVehicle();
		if (vehicle == null) {
			return;
		}

		final int speedKmh = Math.min(999, Math.max(0, (int) Math.round(Math.abs(vehicle.getSpeed()) * 3600)));
		final String text = String.format("%0" + DIGITS + "d", speedKmh);

		final Window window = minecraftClient.getWindow();
		final int cellWidth = cellWidth();
		final int left = EDGE_PADDING;
		final int top = window.getScaledHeight() - EDGE_PADDING - Math.round(IGui.TEXT_HEIGHT * SCALE);

		graphicsHolder.push();
		graphicsHolder.translate(left, top, 0);
		graphicsHolder.scale(SCALE, SCALE, 1);

		// 逐位：每位在自己的格子里居中。★ 格子中心 = index*cellWidth + cellWidth/2，**与这一位是谁无关**
		// —— 这就是"位置中点固定、不因字宽伸缩"的全部实现（单测见 MmtrSpeedHudTests）。
		for (int i = 0; i < DIGITS; i++) {
			final MutableText digit = styledDigit(text.charAt(i));
			final int width = GraphicsHolder.getTextWidth(digit);
			graphicsHolder.drawText(digit, digitOffset(i, width, cellWidth), 0, DIGIT_COLOR, true, GraphicsHolder.getDefaultLight());
		}

		graphicsHolder.pop();
	}

	/**
	 * 第 {@code index} 位在它自己格子里的绘制 x（缩放前像素，相对整块左上角）。
	 *
	 * <p>单独抽出来是为了能被单测钉住：**格子中心与字宽无关**。整数除法会带来 1 px 取整误差，
	 * 所以判据写成"中心偏差 ≤ 1 px"。</p>
	 */
	static int digitOffset(int index, int digitWidth, int cellWidth) {
		return index * cellWidth + (cellWidth - digitWidth) / 2;
	}

	/** 固定格宽：0–9 里最宽的那位 + 呼吸。 */
	private static int cellWidth() {
		if (cachedCellWidth > 0) {
			return cachedCellWidth;
		}
		int widest = 0;
		for (char digit = '0'; digit <= '9'; digit++) {
			widest = Math.max(widest, GraphicsHolder.getTextWidth(styledDigit(digit)));
		}
		cachedCellWidth = widest + DIGIT_GAP;
		return cachedCellWidth;
	}

	/** 资源重载（换字体）后作废格宽缓存 —— 与 {@code MmtrPanelFont.reset()} 同一套路。 */
	public static void reset() {
		cachedCellWidth = -1;
	}

	/** 一位数字：屏幕 UI 字体（DIN）+ 右斜体。 */
	private static MutableText styledDigit(char digit) {
		final Style style = Style.getEmptyMapped()
			.withFont(new Identifier(Init.MOD_ID, "ui"))
			.withItalic(Boolean.TRUE);
		return TextHelper.setStyle(TextHelper.literal(String.valueOf(digit)), style);
	}
}
