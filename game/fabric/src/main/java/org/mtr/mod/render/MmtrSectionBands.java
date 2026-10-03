package org.mtr.mod.render;

import org.mtr.core.data.Rail;
import org.mtr.core.tool.Vector;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mod.Init;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MmtrClientRoutes;
import org.mtr.mod.data.IGui;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏内**区间叠加层**：拿着信号灯时，把"哪一段是哪个区间、往哪个方向走"画在轨面上（notes/291）。
 *
 * <h3>用户要的是什么</h3>
 * <p>「建造轨道时，区间难以辨认……用三角形表示方向，用不同颜色来代表不同区间，仅需相连颜色不同即可，
 * 连续轨道可以有相同颜色。」所以这一层只回答两件事：<b>边界在哪</b>（相邻两段异色）与
 * <b>往哪走</b>（三角形指向行车方向）。颜色本身不代表状态（占用/灯位是信号层的事），只代表"这是哪一段"。</p>
 *
 * <h3>画法：按方向分左右两条带（与网页方案 B 同一口径）</h3>
 * <p>同一根轨上南行与北行各是一条区间，两条带**沿轨法向各让开半个轨宽**，合起来铺满轨道；
 * 让开的侧由**行车方向**定（{@code (-dz, dx)}，与网页 {@code bandOffsetDirection} 同一公式），
 * 所以相对的两个方向必然落在两侧，而同一根轨上叠着的同向区间仍落在同一条带上（叠着画）。</p>
 *
 * <h3>量出来的两个数（不是猜的）</h3>
 * <ul>
 *   <li><b>轨面是 ±1 格</b>：{@code railMath.render(cb, interval, -1, 1)} 的四角实测离中心线正好 1.0 格
 *       （见 {@code sandbox/_band_probe/BandProbe.java} 的实测输出），所以"半个轨宽"= 0.5 格、
 *       带宽取 0.8 格 —— 两条带之间留一条缝，看得出是两条。</li>
 *   <li><b>三角贴图的朝向</b>：{@code one_way_rail_arrow.png} 里三角形**底边在 v=0.40625、尖在
 *       v=0.59375**（把 PNG 逐像素解出来的：x∈[10,22)、y∈[13,19)，y 小的那几行最宽 = 底边）。</li>
 *   <li><b>四角与 uv 的对应</b>：{@code drawTextureInWorld} 把 uv **反着**贴 —— corner1/2 拿
 *       {@code v2}、corner3/4 拿 {@code v1}（{@code u1} 在 corner1/4、{@code u2} 在 corner2/3）。
 *       证据是 mapping jar 的字节码：调用点的 {@code InvokeDynamic unpack:(…Direction;FFFFFIFFFFFFFFFFF)}
 *       与它前面那条 fload 序列给出捕获顺序 {@code x1,y1,z1,u1,v2, …x2,y2,z2,u2, …x3,y3,z3,v1, …x4,y4,z4}，
 *       而 lambda 体里四个顶点的 uv 依次是 {@code (5,6) (11,6) (11,15) (5,15)}。
 *       <b>第一版把这条当成 v1 在 corner1/2，于是三角形整片反指</b>（实机 2026-09-25 用户报的"箭头反了"）。
 *       所以"尖朝 u2/v2 那一对角"这句话是**错的**，别再按它改；要动先重跑这条字节码。</li>
 * </ul>
 */
public final class MmtrSectionBands {

	private static final Identifier WHITE_TEXTURE = new Identifier(Init.MOD_ID, "textures/block/white.png");
	private static final Identifier ARROW_TEXTURE = new Identifier(Init.MOD_ID, "textures/block/one_way_rail_arrow.png");

	/** 带中心离轨中心线的距离（格）。轨面 ±1 格 ⇒ 半个轨宽。 */
	private static final double BAND_OFFSET = 0.5;
	/** 带宽（格）：两条带各占一半、中间留缝。 */
	private static final double BAND_WIDTH = 0.8;
	/** 带面高度：轨面画在 {@code y + 0.065625} 与 +SMALL_OFFSET 两层之上。 */
	private static final double BAND_Y_OFFSET = 0.065625 + 3 * 0.05 / 16;
	/** 采样步长（米）：1 米一段足够贴合曲线，且不会带来成百上千个四边形。 */
	private static final double STEP_M = 1;
	/** 太短的弧窗不画（量不出方向，画出来只是一坨）。 */
	private static final double MIN_BAND_LENGTH_M = 0.15;

	/** 三角形的长度（米）与宽度（格）。 */
	private static final double TRIANGLE_LENGTH_M = 0.6;
	private static final double TRIANGLE_WIDTH = 0.5;
	/** 三角形比带面抬高多少（格）：带面在 {@link #BAND_Y_OFFSET}，这里再抬 1 厘米。
	 *  抬 3 毫米（{@code SMALL_OFFSET}）在 50 米外就已经和"深度精度"同量级 —— 白箭头会与它下面的色带打架。 */
	private static final double TRIANGLE_Y_LIFT = 0.01;
	/** 隔多远放一个三角形（米）。 */
	private static final double TRIANGLE_SPACING_M = 5;
	/** 三角贴图里三角形本身的范围（量出来的，见类注释）：底边 v=13/32、尖 v=19/32，x∈[10,22)。 */
	private static final float ARROW_U1 = 10F / 32;
	private static final float ARROW_U2 = 22F / 32;
	private static final float ARROW_V_BASE = 13F / 32;
	private static final float ARROW_V_TIP = 19F / 32;

	/**
	 * 区间带的调色板（{@link MmtrSectionOverlay#COLOR_COUNT} 个色相，两边必须一致）。
	 *
	 * <p>都是中间调、彼此拉得开的颜色：饱和度过高的原色在草地上刺眼，太淡的在轨面上看不见。
	 * 引擎贪心分配色号（相连两段异色），这里只做"色号 → 颜色"的翻译。</p>
	 */
	public static final int[] COLORS = {
		0xFF4E8FF7, // 蓝
		0xFFF2994A, // 橙
		0xFF6FCF6F, // 绿
		0xFFE0679A, // 粉
		0xFFF2D24B, // 黄
		0xFF56C8D8, // 青
		0xFFB07CE8, // 紫
		0xFFE05C5C, // 红
		0xFF9BD65B, // 黄绿
		0xFF7C86E8, // 靛
		0xFFD8A657, // 沙
		0xFF5FD3A6, // 蓝绿
	};

	private MmtrSectionBands() {
	}

	/** 一根轨上的所有区间带（拿着信号灯时每帧调用）。 */
	public static void render(Rail rail) {
		final List<MmtrClientRoutes.SectionBand> bands = MmtrClientRoutes.sectionBands(rail.getHexId());
		if (bands.isEmpty()) {
			return;
		}
		final int[] sides = new int[bands.size()];
		final double[] arcFroms = new double[bands.size()];
		final double[] arcTos = new double[bands.size()];
		for (int i = 0; i < bands.size(); i++) {
			sides[i] = sideOf(rail, bands.get(i));
			arcFroms[i] = bands.get(i).arcFromM;
			arcTos[i] = bands.get(i).arcToM;
		}
		final List<List<double[]>> windows = visibleWindows(arcFroms, arcTos, sides);
		final Vector3d cameraPosition = MinecraftClient.getInstance().getGameRendererMapped().getCamera().getPos();
		final int renderDistance = MinecraftClientHelper.getRenderDistance() * 16;
		final double lengthM = rail.railMath.getLength();
		for (int i = 0; i < bands.size(); i++) {
			for (final double[] window : windows.get(i)) {
				renderBand(rail, bands.get(i), window[0], window[1], lengthM, cameraPosition, renderDistance);
			}
		}
	}

	/**
	 * 一条带让到哪一侧：由"**弧增方向**与行车方向的点积"定（同一个行车方向必然同侧，相对方向必然异侧）。
	 *
	 * <p>只有同侧的带才会落在同一个横向位置上、才可能共面打架 —— 异侧的两条横向就分开了（0.1..0.9 与 −0.9..−0.1），
	 * 所以剪裁只需要认这一对 ±1。</p>
	 */
	static int sideOf(Rail rail, MmtrClientRoutes.SectionBand band) {
		final double lengthM = rail.railMath.getLength();
		if (lengthM <= 0) {
			return 1;
		}
		final double arc = Math.max(0, Math.min((band.arcFromM + band.arcToM) / 2, lengthM - 1e-3));
		final Vector start = rail.railMath.getPosition(arc, false);
		final Vector end = rail.railMath.getPosition(Math.min(arc + 0.5, lengthM), false);
		final double dx = end.x() - start.x();
		final double dz = end.z() - start.z();
		return dx * band.headingX + dz * band.headingZ >= 0 ? 1 : -1;
	}

	/**
	 * 同侧、互相重叠的带：**后写的盖住先写的**（与网页"叠色"同一条口径），把被盖住的弧窗从先写的那条上剪掉。
	 *
	 * <h3>为什么必须剪（用户 2026-09-25 报的"有些颜色是透明的不显示"）</h3>
	 * <p>区间带画在 {@code RenderLayer.getEntityCutout} 那一层（alpha-test、**写深度**），而同侧的带
	 * 横向位置与高度**完全相同** ⇒ 两个四边形是**完全共面**的，谁赢按像素随机。实测（
	 * {@code sandbox/_band_probe}，只读打开真实存档）：**46 根轨里有 14 根**存在这种叠法，最多叠 2 层 ——
	 * 于是那些地方看着就是"色带缺一块/透掉了"。</p>
	 *
	 * <p>剪完以后同一根轨上任意两段弧窗都不再重叠（同侧已剪开、异侧本来就分开），
	 * 所以谁在上谁在下不再重要，也不需要靠"抬高几毫米"去躲深度冲突（那在远处根本不够）。</p>
	 *
	 * @param arcFrom 每条带的弧窗起点（米）
	 * @param arcTo 每条带的弧窗终点（米）
	 * @param sides 每条带的让开侧（见 {@link #sideOf}）
	 * @return 与输入等长：每条带**实际要画**的弧窗（空 = 整条被盖住；多段 = 中间被别的带切掉）
	 */
	static List<List<double[]>> visibleWindows(double[] arcFrom, double[] arcTo, int[] sides) {
		final List<List<double[]>> out = new ArrayList<>();
		for (int i = 0; i < arcFrom.length; i++) {
			out.add(new ArrayList<>());
		}
		final List<double[]> coveredPositive = new ArrayList<>();
		final List<double[]> coveredNegative = new ArrayList<>();
		// 从后往前：后面的先占位，前面的撞上占位就被剪
		for (int i = arcFrom.length - 1; i >= 0; i--) {
			final double from = Math.min(arcFrom[i], arcTo[i]);
			final double to = Math.max(arcFrom[i], arcTo[i]);
			final List<double[]> covered = sides[i] >= 0 ? coveredPositive : coveredNegative;
			out.get(i).addAll(subtract(from, to, covered));
			covered.add(new double[]{from, to});
		}
		return out;
	}

	/** {@code [from,to)} 减去一串已占位的弧窗，返回剩下的若干段（按顺序）。 */
	static List<double[]> subtract(double from, double to, List<double[]> covered) {
		List<double[]> remaining = new ArrayList<>();
		remaining.add(new double[]{from, to});
		for (final double[] block : covered) {
			final List<double[]> next = new ArrayList<>();
			for (final double[] piece : remaining) {
				if (block[1] <= piece[0] || block[0] >= piece[1]) {
					next.add(piece);
				} else {
					if (block[0] > piece[0]) {
						next.add(new double[]{piece[0], block[0]});
					}
					if (block[1] < piece[1]) {
						next.add(new double[]{block[1], piece[1]});
					}
				}
			}
			remaining = next;
		}
		return remaining;
	}

	private static void renderBand(Rail rail, MmtrClientRoutes.SectionBand band, double rawFrom, double rawTo, double lengthM, Vector3d cameraPosition, int renderDistance) {
		final double from = Math.max(0, Math.min(rawFrom, lengthM));
		final double to = Math.max(0, Math.min(rawTo, lengthM));
		if (to - from < MIN_BAND_LENGTH_M) {
			return;
		}
		final int color = COLORS[Math.floorMod(band.colorIndex, COLORS.length)];
		final int steps = Math.max(1, (int) Math.ceil((to - from) / STEP_M));
		Vector previous = rail.railMath.getPosition(from, false);
		for (int i = 1; i <= steps; i++) {
			final Vector current = rail.railMath.getPosition(from + (to - from) * i / steps, false);
			if (withinRenderDistance(current, cameraPosition, renderDistance) || withinRenderDistance(previous, cameraPosition, renderDistance)) {
				drawBandQuad(band, color, previous, current);
			}
			previous = current;
		}
		renderTriangles(rail, band, from, to, cameraPosition, renderDistance);
	}

	/**
	 * 一段带面：沿轨中心线两侧各让开 {@link #BAND_OFFSET}，宽 {@link #BAND_WIDTH}。
	 *
	 * <p>让开的**侧**由行车方向定，不由采样方向定：采样方向反了（弧增方向与行车方向相反）就会把带子
	 * 甩到对面那条带上去，两条带叠在一起 —— 这正是"双向分不开"的老毛病。</p>
	 */
	private static void drawBandQuad(MmtrClientRoutes.SectionBand band, int color, Vector previous, Vector current) {
		final double dx = current.x() - previous.x();
		final double dz = current.z() - previous.z();
		final double length = Math.hypot(dx, dz);
		if (length < 1e-6) {
			return;
		}
		final double factor = travelFactor(band, dx / length, dz / length);
		final double normalX = -dz / length * factor;
		final double normalZ = dx / length * factor;
		final double inner = BAND_OFFSET - BAND_WIDTH / 2;
		final double outer = BAND_OFFSET + BAND_WIDTH / 2;
		final double x1 = previous.x() + normalX * inner;
		final double z1 = previous.z() + normalZ * inner;
		final double x2 = previous.x() + normalX * outer;
		final double z2 = previous.z() + normalZ * outer;
		final double x3 = current.x() + normalX * outer;
		final double z3 = current.z() + normalZ * outer;
		final double x4 = current.x() + normalX * inner;
		final double z4 = current.z() + normalZ * inner;
		final double y1 = previous.y() + BAND_Y_OFFSET;
		final double y2 = current.y() + BAND_Y_OFFSET;
		MainRenderer.scheduleRender(WHITE_TEXTURE, false, QueuedRenderLayer.EXTERIOR, (graphicsHolder, offset) -> {
			// 两面各画一次（与轨道本体的画法一致）：背面剔除之下只有一次能看见，所以不会打架。
			IDrawing.drawTexture(graphicsHolder, x1, y1, z1, x2, y1 + IGui.SMALL_OFFSET, z2, x3, y2, z3, x4, y2 + IGui.SMALL_OFFSET, z4, offset, 0, 0, 1, 1, Direction.UP, color, GraphicsHolder.getDefaultLight());
			IDrawing.drawTexture(graphicsHolder, x2, y1 + IGui.SMALL_OFFSET, z2, x1, y1, z1, x4, y2 + IGui.SMALL_OFFSET, z4, x3, y2, z3, offset, 0, 0, 1, 1, Direction.UP, color, GraphicsHolder.getDefaultLight());
		});
	}

	/**
	 * 方向三角形：沿带每隔 {@link #TRIANGLE_SPACING_M} 米放一个，尖指向行车方向。
	 *
	 * <p>贴图里三角形底边在 {@link #ARROW_V_BASE}、尖在 {@link #ARROW_V_TIP}，而四角的 uv 顺序是
	 * corner1/2 → v1、corner3/4 → v2。所以"行车方向与弧增方向一致"时 v1 取底边、v2 取尖，
	 * 反之对调 —— 对调这一处就是"三角形全部反着指"的唯一原因，别在这里凭感觉改。</p>
	 */
	private static void renderTriangles(Rail rail, MmtrClientRoutes.SectionBand band, double from, double to, Vector3d cameraPosition, int renderDistance) {
		final double half = TRIANGLE_LENGTH_M / 2;
		for (double center = from + TRIANGLE_SPACING_M / 2; center + half <= to; center += TRIANGLE_SPACING_M) {
			final Vector start = rail.railMath.getPosition(center - half, false);
			final Vector end = rail.railMath.getPosition(center + half, false);
			if (!withinRenderDistance(start, cameraPosition, renderDistance) && !withinRenderDistance(end, cameraPosition, renderDistance)) {
				continue;
			}
			final double dx = end.x() - start.x();
			final double dz = end.z() - start.z();
			final double length = Math.hypot(dx, dz);
			if (length < 1e-6) {
				continue;
			}
			final double factor = travelFactor(band, dx / length, dz / length);
			final double normalX = -dz / length * factor;
			final double normalZ = dx / length * factor;
			final double inner = BAND_OFFSET - TRIANGLE_WIDTH / 2;
			final double outer = BAND_OFFSET + TRIANGLE_WIDTH / 2;
			/*
			 * 三角形的尖朝哪一端：贴图里尖在 {@link #ARROW_V_TIP}、底边在 {@link #ARROW_V_BASE}，而
			 * `drawTextureInWorld` 的 uv 是**反着**贴到四角上的（见类注释的字节码证据）：
			 * corner1/2 拿 v2、corner3/4 拿 v1。
			 *
			 * 所以"弧增方向 = 行车方向"时（尖该落在弧大的那一端 = corner3/4 = v1 那一对角），
			 * **v1 要填尖的值**。第一版把这一处写反了 —— 实机表现就是"箭头全部反着指"（用户 2026-09-25 报的），
			 * 而它不像 bug、像自己拿错了工具，所以这里把证据留在注释里，别凭感觉再翻回去。
			 */
			final boolean tipAtFarEnd = dx * band.headingX + dz * band.headingZ >= 0;
			final float v1 = tipAtFarEnd ? ARROW_V_TIP : ARROW_V_BASE;
			final float v2 = tipAtFarEnd ? ARROW_V_BASE : ARROW_V_TIP;
			final double x1 = start.x() + normalX * inner;
			final double z1 = start.z() + normalZ * inner;
			final double x2 = start.x() + normalX * outer;
			final double z2 = start.z() + normalZ * outer;
			final double x3 = end.x() + normalX * outer;
			final double z3 = end.z() + normalZ * outer;
			final double x4 = end.x() + normalX * inner;
			final double z4 = end.z() + normalZ * inner;
			final double y1 = start.y() + BAND_Y_OFFSET + TRIANGLE_Y_LIFT;
			final double y2 = end.y() + BAND_Y_OFFSET + TRIANGLE_Y_LIFT;
			MainRenderer.scheduleRender(ARROW_TEXTURE, false, QueuedRenderLayer.EXTERIOR, (graphicsHolder, offset) -> {
				IDrawing.drawTexture(graphicsHolder, x1, y1, z1, x2, y1, z2, x3, y2, z3, x4, y2, z4, offset, ARROW_U1, v1, ARROW_U2, v2, Direction.UP, IGui.ARGB_WHITE, GraphicsHolder.getDefaultLight());
				IDrawing.drawTexture(graphicsHolder, x2, y1, z2, x1, y1, z1, x4, y2, z4, x3, y2, z3, offset, ARROW_U1, v1, ARROW_U2, v2, Direction.UP, IGui.ARGB_WHITE, GraphicsHolder.getDefaultLight());
			});
		}
	}

	/**
	 * 采样方向（单位向量）要不要翻：弧增方向与行车方向相同时不翻。
	 *
	 * <p>翻不翻决定了带子让到哪一侧、以及三角形尖朝哪一端 —— 两个地方共用这一个判据，
	 * 所以它们不可能互相矛盾（"带在左、箭头朝右"这类错都出在有两份判据上）。</p>
	 */
	private static double travelFactor(MmtrClientRoutes.SectionBand band, double unitX, double unitZ) {
		return unitX * band.headingX + unitZ * band.headingZ >= 0 ? 1 : -1;
	}

	private static boolean withinRenderDistance(Vector position, Vector3d cameraPosition, int renderDistance) {
		final double dx = position.x() - cameraPosition.getXMapped();
		final double dz = position.z() - cameraPosition.getZMapped();
		return dx * dx + dz * dz <= (double) renderDistance * renderDistance;
	}
}
