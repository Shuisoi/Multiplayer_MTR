package org.mtr.mod.render;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityRenderer;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.block.BlockStopMarker;
import org.mtr.mod.block.IBlock;
import org.mtr.mod.data.IGui;

/**
 * **停车对标牌子的牌面渲染器**：牌子正反两面各写一组"辆数 + CARS" —— 未绑定红色、已绑定白色。
 *
 * <h3>牌面排版（用户口径 2026-10-13，逐条定的）</h3>
 * <ol>
 *   <li>牌面内容：**一个数字**（2…10，辆数没设写 {@code ?}）＋下面一行小号 {@code CARS}；</li>
 *   <li>颜色 = 归属：**白 = 已绑定、红 = 未绑定**；</li>
 *   <li>**原版像素字体**：直接调 {@link GraphicsHolder#drawText(String, int, int, int, boolean, int)}，
 *       不走 {@code IDrawing.drawStringWithFont}（那个按客户端配置会切到 MTR 的平滑字体，
 *       见 {@code IDrawing} 里的 {@code getUseMTRFont()}）；</li>
 *   <li>**居中**：用 {@code -GraphicsHolder.getTextWidth(text) / 2} 算左边界
 *       （与 {@code DrivingGuiRenderer} 同一套写法）—— 两位数的 {@code 10} 也能压住牌面中线。</li>
 * </ol>
 *
 * <h3>为什么两面都画</h3>
 * <p>牌子的朝向（面朝股道 / 面朝来车）两边都会用到，只画一面时站在另一侧的人看到的是空白牌
 * （金属背面）。正反两面各画一次：背面那一次用 {@code rotateYDegrees(180)}。</p>
 *
 * <h3>朝向 / 位置 / 正倒左右：都在实机上踩过（2026-10-13）</h3>
 * <ol>
 *   <li><b>面</b>：`rotateYDegrees(180 - facing.asRotation())` —— 第一版按 `-facing.asRotation()` 算，
 *       文字四边形法线朝里、被单面剔除 ⇒ 牌子上什么都没有（用户判据："是 UV 面反了"）；</li>
 *   <li><b>位置</b>：`translate(0, 0, -plateZ)` —— 转 180° 时"局部 +Z"翻向，平移量必须一起取负
 *       （用户判据："移动的反了，应该朝另一个方向移动"）；</li>
 *   <li><b>正倒 + 左右</b>：先只翻 Y ⇒ 字正了但左右镜像（用户判据："上下颠倒了" → "现在左右反了"），
 *       最终 `rotateZDegrees(180)`（= scale(-1, -1, 1)，两轴一起翻）——
 *       与 {@code RenderLiftPanel} / {@code RenderStationNameBase} 同一个写法；</li>
 *   <li>牌面高度：先 `translate(0, plateY, 0)` 把原点抬到牌面中心，再翻轴。</li>
 * </ol>
 * <p>间隙 {@code PLATE_CLEARANCE = 9 mm}（用户定的数）：牌面只有 1/16 格厚，这一小段是留给"不要共面"的。</p>
 *
 * <h3>诊断</h3>
 * <p>头几次渲染会往客户端日志写一行 {@code [MMTR-STOP-BER]}（块坐标 / 朝向 / 样式 / 读到的辆数 /
 * 要写的字 / 颜色），默认 8 行、{@code -Dmmtr.stopmarker.debug=N} 可调（0 = 不写）——
 * 这一层是纯客户端、没有别的读数，出问题时日志里那一行就是唯一的证据。</p>
 */
public class RenderStopMarker extends BlockEntityRenderer<BlockStopMarker.BlockEntity> implements IGui, DirectionHelper {

	/** 可见距离（格）：220 格长的站台，从一端看另一端也要能认出牌位。 */
	private static final int RENDER_DISTANCE = 160;

	/** 文字平面相对牌面再往外让多少格（用户口径：**9 mm**）。 */
	private static final float PLATE_CLEARANCE = 0.009F;

	/** 未绑定的牌面颜色（红）。已绑定用白。 */
	private static final int UNBOUND_COLOR = 0xFFFF4444;

	/**
	 * 字号：1 单位 = 1 个原版字体像素。原版数字 6×7 像素 ⇒
	 * {@code NUMBER_SCALE} 的数字约 0.19 × 0.22 格高、{@code CARS_SCALE} 的 {@code CARS} 约 0.10 格高，
	 * 两行加起来 0.32 格 —— 正好落在 0.625 × 0.375 的牌面里（上下各留一点边）。
	 */
	private static final float NUMBER_SCALE = 0.032F;
	private static final float CARS_SCALE = 0.014F;

	/** 两行的竖直位置（局部像素，牌面中心为 0；局部 +Y 是"向下"）。 */
	private static final int NUMBER_Y = -5;
	private static final int CARS_Y = 5;

	/** 还要往日志写几行诊断（客户端渲染层没有别的读数，出问题只能靠这几行）。 */
	private static int debugPrints = Integer.getInteger("mmtr.stopmarker.debug", 8);

	public RenderStopMarker(Argument dispatcher) {
		super(dispatcher);
	}

	@Override
	public void render(BlockStopMarker.BlockEntity blockEntity, float tickDelta, GraphicsHolder graphicsHolder, int light, int overlay) {
		final World world = blockEntity.getWorld2();
		if (world == null) {
			return;
		}
		final BlockPos pos = blockEntity.getPos2();
		final Direction facing = IBlock.getStatePropertySafe(world, pos, FACING);
		final int variant = IBlock.getStatePropertySafe(world, pos, BlockStopMarker.VARIANT);

		/*
		 * 牌面内容 = **一个数字**：2…10（辆数）。`cars` 没设（0）时写 `?` ——
		 * 那正是"这块牌子还没拨辆数"的现场读数（牌面贴图里不写死数字）。
		 */
		final int cars = blockEntity.getCars();
		final String label = cars >= BlockStopMarker.CARS_MIN && cars <= BlockStopMarker.CARS_MAX ? String.valueOf(cars) : "?";
		// 归属看**两个字段**：显式绑定的 platformId，或扫描时按位置解析出来的 platformHex
		// （铺牌子只写 hex、不写 id —— 只判 id 会让已归属的牌子显示成红色"未绑定"）。
		final boolean bound = blockEntity.getPlatformId() != 0 || !blockEntity.getPlatformHex().isEmpty();
		final int color = bound ? ARGB_WHITE : UNBOUND_COLOR;

		if (debugPrints > 0) {
			debugPrints--;
			System.out.println("[MMTR-STOP-BER] " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
				+ " facing=" + facing + " variant=" + variant + " cars=" + cars
				+ " platformId=" + blockEntity.getPlatformId() + " hex=[" + blockEntity.getPlatformHex() + "]"
				+ " label=[" + label + "/CARS] color=" + (bound ? "白（已绑定）" : "红（未绑定）"));
		}

		final float plateY = variant == 0 ? 0.75F : 0.5F;
		final float plateZ = (variant == 2 ? 0.375F : 0.03125F) + PLATE_CLEARANCE;
		final StoredMatrixTransformations front = new StoredMatrixTransformations(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		front.add(graphicsHolderNew -> {
			graphicsHolderNew.rotateYDegrees(180 - facing.asRotation());
			graphicsHolderNew.translate(0, 0, -plateZ);
			graphicsHolderNew.translate(0, plateY, 0);
			graphicsHolderNew.rotateZDegrees(180);
		});
		// 另一面：再转 180°、平移量同样取负 ⇒ 落在牌子的反面（两面都读得到）。
		final StoredMatrixTransformations back = front.copy();
		back.add(graphicsHolderNew -> {
			graphicsHolderNew.rotateYDegrees(180);
			graphicsHolderNew.translate(0, 0, -plateZ);
		});

		for (final StoredMatrixTransformations storedMatrixTransformations : new StoredMatrixTransformations[]{front, back}) {
			MainRenderer.scheduleRender(QueuedRenderLayer.TEXT, (graphicsHolderNew, offset) -> {
				storedMatrixTransformations.transform(graphicsHolderNew, offset);
				drawCentered(graphicsHolderNew, label, NUMBER_SCALE, NUMBER_Y, color);
				drawCentered(graphicsHolderNew, "CARS", CARS_SCALE, CARS_Y, color);
				graphicsHolderNew.pop();
			});
		}
	}

	/**
	 * 原版像素字体、按文字宽度**水平居中**地画一行。
	 *
	 * @param graphicsHolder 当前矩阵（原点在牌面中心，局部 +Y 向下）
	 * @param text           要画的字
	 * @param scale          1 单位 = 1 个字体像素
	 * @param y              这一行的**上边界**（局部像素；局部 +Y 向下，所以越往下越大）
	 * @param color          ARGB 颜色
	 */
	private static void drawCentered(GraphicsHolder graphicsHolder, String text, float scale, int y, int color) {
		graphicsHolder.push();
		graphicsHolder.scale(scale, scale, scale);
		// 居中：MC 的字从 (x, y) 往右下画，所以 x 取半个字宽（与 DrivingGuiRenderer 同一套写法）。
		graphicsHolder.drawText(text, -GraphicsHolder.getTextWidth(text) / 2, y, color, false, GraphicsHolder.getDefaultLight());
		graphicsHolder.pop();
	}

	@Override
	public int getRenderDistance2() {
		return RENDER_DISTANCE;
	}
}
