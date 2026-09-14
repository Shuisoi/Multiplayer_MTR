package org.mtr.mod.render;

import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.ItemStack;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.Init;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.MmtrClientRoutes;
import org.mtr.mod.item.ItemBlockClickingBase;
import org.mtr.mod.mmtr.MmtrSignalBlocks;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 信号绑定的**世界叠加层**：把"这盏灯守哪几根轨"直接画在轨道上。
 *
 * <h3>为什么需要它</h3>
 * <p>两把工具（铲子=绑节点、木斧=分轨）的第一步都是"右键信号灯"，第二步才是绑。可是世界里
 * 光秃秃一片，看不见"引擎现在认为这盏灯守哪根轨"—— 于是绑定变成盲操作：点错了也只能事后去
 * 网页或日志里查。拿起来就能看到，才是顺手。</p>
 *
 * <h3>画什么</h3>
 * <ul>
 *   <li>选中的那盏灯：一根竖直光柱 + 一根横线标出它所在的格（远处也找得到）；颜色跟随它的状态
 *       （红/单黄/双黄/绿，未接入=灰）；</li>
 *   <li>它守的每一根轨：轨面上一根**加粗的亮色线**，一条腿一个色相（便于区分多腿）；</li>
 *   <li>灯到各条守轨的连线：说明"这盏灯在看哪几根轨"（多腿时一眼看清是哪几条）。</li>
 * </ul>
 *
 * <h3>数据从哪来</h3>
 * <p>守轨由**引擎**算好、随 {@code PacketMmtrRoutes} 发过来（{@code MmtrClientRoutes.lampRails}）。
 * 客户端不自己按几何推 —— 那必然与引擎分叉，而"灯守哪根轨"恰恰是要看的结论本身。
 * 这与 S4（灯的状态）是同一条规矩：引擎给结论，客户端只显示。</p>
 */
public final class MmtrSignalBindingOverlay {

	/** 灯柱与守轨线的颜色：一条一色，最多区分 6 条腿（再多就是看数量了）。 */
	private static final int[] LEG_COLORS = {
		0xFF35E0A0, 0xFF3FA9F5, 0xFFF5C542, 0xFFE056FD, 0xFFFF7A45, 0xFF9BE15D
	};

	/** 轨面之上一点点，避免与轨本色重叠打架。 */
	private static final double RAIL_LIFT = 0.18;
	/** 灯柱高度（格）。 */
	private static final double POLE_HEIGHT = 6;

	private MmtrSignalBindingOverlay() {
	}

	/** 每帧调用（只有拿着两把绑定工具之一时才有意义）。 */
	public static void render(ClientPlayerEntity clientPlayerEntity) {
		final ItemStack stack = clientPlayerEntity.getStackInHand(clientPlayerEntity.getActiveHand());
		final Object item = stack.getItem().data;
		final boolean holdingBinder = item instanceof org.mtr.mod.item.ItemMmtrSignalBinder;
		final boolean holdingRailTool = item instanceof org.mtr.mod.item.ItemMmtrRailBindingTool;
		if (!holdingBinder && !holdingRailTool) {
			return;
		}

		// 第一步点过的灯（存在物品 NBT 里）；还没点过就什么都不画
		final CompoundTag compoundTag = stack.getOrCreateTag();
		final long posLong = compoundTag.getLong(ItemBlockClickingBase.TAG_POS);
		if (posLong == 0) {
			return;
		}
		final BlockPos lampPos = BlockPos.fromLong(posLong);
		final int color = aspectColor(MmtrClientRoutes.lampAspect(lampPos.getX(), lampPos.getY(), lampPos.getZ()));

		renderLampMarker(lampPos, color);

		final List<String> guarded = MmtrClientRoutes.lampRails(lampPos.getX(), lampPos.getY(), lampPos.getZ());
		final MinecraftClientData data = MinecraftClientData.getInstance();
		for (int i = 0; i < guarded.size(); i++) {
			final Rail rail = findRail(data, guarded.get(i));
			if (rail == null) {
				continue;
			}
			final int legColor = LEG_COLORS[i % LEG_COLORS.length];
			renderRailHighlight(rail, legColor);
			renderLink(lampPos, rail, legColor);
		}
	}

	/**
	 * 灯的标记：一个方块 + 一根竖直光柱。
	 *
	 * <p>光柱是必要的：信号灯往往立在轨旁一两格、和轨的颜色混在一起，没有光柱在远处根本认不出
	 * "刚才点的是哪一盏"。</p>
	 */
	private static void renderLampMarker(BlockPos lampPos, int color) {
		final double x = lampPos.getX() + 0.5;
		final double y = lampPos.getY();
		final double z = lampPos.getZ() + 0.5;
		MainRenderer.scheduleRender(QueuedRenderLayer.LINES, (graphicsHolder, offset) -> {
			// 光柱：从灯的位置往上，两根不同高度的线叠出"淡出"的感觉
			graphicsHolder.drawLineInWorld(
				(float) (x - offset.getXMapped()), (float) (y - offset.getYMapped()), (float) (z - offset.getZMapped()),
				(float) (x - offset.getXMapped()), (float) (y + POLE_HEIGHT - offset.getYMapped()), (float) (z - offset.getZMapped()),
				color
			);
			// 一根横线标出灯所在的那一格（方块坐标）
			graphicsHolder.drawLineInWorld(
				(float) (x - 0.5 - offset.getXMapped()), (float) (y + 0.5 - offset.getYMapped()), (float) (z - 0.5 - offset.getZMapped()),
				(float) (x + 0.5 - offset.getXMapped()), (float) (y + 0.5 - offset.getYMapped()), (float) (z + 0.5 - offset.getZMapped()),
				color
			);
		});
	}

	/** 一根轨：沿着它的采样点画一串短线段，整根用同一个颜色加粗标出。 */
	private static void renderRailHighlight(Rail rail, int color) {
		MainRenderer.scheduleRender(QueuedRenderLayer.LINES, (graphicsHolder, offset) -> rail.railMath.render((cx1, cy1, cz1, cx2, cy2, cz2, cx3, cy3, cz3, cx4, cy4, cz4, tiltAngle) -> {
			/*
			 * 这一段的两个"角"取中点连起来就是轨道中心线（与 RenderRails 同一套坐标约定：
			 * 回调给的是轨面四角，x2/y2 取的是 cx2,cy3 —— 见那边的 MMTR port 注释），再抬高一点
			 * 避免与轨本色重叠打架。
			 */
			final double x1 = cx1;
			final double z1 = cz1;
			final double y1 = cy1 + RAIL_LIFT;
			final double x2 = cx3;
			final double z2 = cz3;
			graphicsHolder.drawLineInWorld(
				(float) (x2 - offset.getXMapped()), (float) (y1 - offset.getYMapped()), (float) (z2 - offset.getZMapped()),
				(float) (x1 - offset.getXMapped()), (float) (y1 - offset.getYMapped()), (float) (z1 - offset.getZMapped()),
				color
			);
		}, 0.5, 0, 0));
	}

	/** 灯 → 守轨的连线：说明"这盏灯在看哪根轨"。 */
	private static void renderLink(BlockPos lampPos, Rail rail, int color) {
		final Position nearest = nearestPointOnRail(rail, lampPos.getX() + 0.5, lampPos.getY() + 0.5, lampPos.getZ() + 0.5);
		if (nearest == null) {
			return;
		}
		MainRenderer.scheduleRender(QueuedRenderLayer.LINES, (graphicsHolder, offset) -> graphicsHolder.drawLineInWorld(
			(float) (lampPos.getX() + 0.5 - offset.getXMapped()), (float) (lampPos.getY() + 0.5 - offset.getYMapped()), (float) (lampPos.getZ() + 0.5 - offset.getZMapped()),
			(float) (nearest.getX() + 0.5 - offset.getXMapped()), (float) (nearest.getY() + 0.5 - offset.getYMapped()), (float) (nearest.getZ() + 0.5 - offset.getZMapped()),
			color
		));
	}

	/**
	 * 离给定点最近的轨端点（客户端只用来画一根连线，所以取端点足够）。
	 *
	 * <p>刻意不做"投影到轨上的精确点"：那需要复刻引擎的弧长空间，而这里只是画一根指示线 ——
	 * 用端点既简单又不会与引擎的口径混淆。</p>
	 */
	@Nullable
	private static Position nearestPointOnRail(Rail rail, double x, double y, double z) {
		final Position[] ends = rail.mmtrOrderedPositions();
		if (ends == null || ends.length < 2) {
			return null;
		}
		final double da = distanceSq(ends[0], x, y, z);
		final double db = distanceSq(ends[1], x, y, z);
		return da <= db ? ends[0] : ends[1];
	}

	private static double distanceSq(Position position, double x, double y, double z) {
		final double dx = position.getX() + 0.5 - x;
		final double dy = position.getY() + 0.5 - y;
		final double dz = position.getZ() + 0.5 - z;
		return dx * dx + dy * dy + dz * dz;
	}

	@Nullable
	private static Rail findRail(MinecraftClientData data, String railHex) {
		return data.positionsToRail.values().stream()
			.flatMap(map -> map.values().stream())
			.filter(rail -> rail.getHexId().equals(railHex) || org.mtr.core.mmtr.signal.MmtrDirectionalBlockService.canonicalHex(rail.getHexId()).equals(org.mtr.core.mmtr.signal.MmtrDirectionalBlockService.canonicalHex(railHex)))
			.findFirst()
			.orElse(null);
	}

	/** 灯状态 → 标记颜色。未接入用灰色，与渲染层其余地方同一套色。 */
	private static int aspectColor(@Nullable String aspect) {
		if (aspect == null || aspect.isEmpty()) {
			return 0xFF9AA0A6;
		}
		switch (aspect) {
			case "RED":
				return 0xFFEF4444;
			case "SINGLE_YELLOW":
				return 0xFFF59E0B;
			case "DOUBLE_YELLOW":
				return 0xFFEAB308;
			case "GREEN":
				return 0xFF22C55E;
			default:
				return 0xFF9AA0A6;
		}
	}
}
