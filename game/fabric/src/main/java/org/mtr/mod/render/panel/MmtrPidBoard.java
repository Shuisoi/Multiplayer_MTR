package org.mtr.mod.render.panel;

import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.Init;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.mmtr.MmtrPidText;
import org.mtr.mod.render.StoredMatrixTransformations;

/**
 * **水牌 / PID 渲染**（notes/357，用户口径 2026-10-01：「水牌画在哪」→「资源包新增锚点，在锚点上画」）。
 *
 * <h2>画的是什么、为什么不是"车体上贴个字"那么简单</h2>
 * <p>OBJ 打包器会把 {@code mmtr_*} 锚点的面**从渲染几何里剥掉**（那是纯数据：位置 + 朝向 + 大小），
 * 所以牌底（黑底）与文字都得由客户端画出来 —— 这正是 {@code MmtrCabDashboard} 对仪表盘做的事，
 * 这里沿用同一条链：Java2D 画布 → 动态贴图 → {@link MmtrPanelQuad} 贴到锚点那个面上。</p>
 *
 * <p>两个锚点族各画各的内容（口径见 {@link MmtrPidText}）：</p>
 * <ul>
 *   <li>{@code mmtr_pid_<cab>[_<n>]} — 水牌：班次号 + 本趟终点，法线朝车外；</li>
 *   <li>{@code mmtr_next_<cab>[_<n>]} — 下一站牌：下一站 X，通常modeled在车内。</li>
 * </ul>
 *
 * <h2>谁来画、画几次</h2>
 * <p>由 {@code RenderVehicles} 在**画每一节车**的时候调用（与仪表盘/雨刷同一个位置），于是锚点坐标
 * 天然就是那节车的模型空间，不需要自己算车体变换。同一块牌只在"内容签名"变化时重画贴图
 * （{@link MmtrPanelTexture#needsRedraw}）：车开着的时候每帧都画一次画布会把 CPU 吃光。</p>
 *
 * <h2>看不见的地方</h2>
 * <ul>
 *   <li>不在作业单上（班次号为空）⇒ 一块牌都不画（车场停着的车不该挂水牌）。</li>
 *   <li>看不清就不画：牌面在远处、背对镜头时画布白画 —— 用"离玩家多远"这条粗门限挡掉，
 *       与仪表盘同一条判据（{@link #NEARBY_BOARD_RADIUS_M}）。</li>
 *   <li>单面：牌的朝向由模型法线定（朝车外）。反了就是"看不见"，见 {@code tools/anchor-check/verify_pid.js} 的判据。</li>
 * </ul>
 */
public final class MmtrPidBoard {

	/**
	 * 离玩家多远就不画。水牌比仪表盘更该"远处也看得见"，所以门限比仪表盘的
	 * {@code NEARBY_CAR_RADIUS_M} 宽松得多；但仍要有 —— 否则一列 10 节车会画 20 块牌的画布。
	 */
	private static final double NEARBY_BOARD_RADIUS_M = 96;

	/** 牌面像素密度（px/m）：牌不大，字要清，取仪表盘的同一档；版式可用 {@code pxPerMetre} 覆盖。 */
	private static final int DEFAULT_PX_PER_METRE = 512;

	/** 找不到锚点的模型只提示一次（与仪表盘同一个做法，避免每帧刷日志）。 */
	private static final java.util.Set<String> MISSING_ANCHORS_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private MmtrPidBoard() {
	}

	/**
	 * 画一节车上的所有水牌 / 下一站牌。
	 *
	 * @param vehicle        正在渲染的车
	 * @param carNumber      这一节在编组里的序号
	 * @param vehicleId      这一节的车型 id（锚点文件按它查）
	 * @param carTransform   画这节车用的变换（锚点坐标就是它的模型空间）
	 * @param carWorldPosition 这节车的世界坐标，用来判"离玩家够近吗"
	 */
	public static void render(VehicleExtension vehicle, int carNumber, String vehicleId, StoredMatrixTransformations carTransform, Vector carWorldPosition) {
		final String service = vehicle.getMmtrPidServiceFromSync();
		final String terminus = vehicle.getMmtrPidTerminusFromSync();
		final String nextStation = vehicle.getMmtrPidNextFromSync();
		if (service.isEmpty()) {
			// 不在在跑的作业单上：整列车不挂牌（引擎那边也把三项清空了，这里再判一次是为了"牌底"）
			return;
		}

		final ObjectArrayList<MmtrVehicleAnchors.Anchor> anchors = MmtrVehicleAnchors.get(vehicleId);
		if (anchors.isEmpty()) {
			return;
		}
		final int modelCar = MmtrVehicleAnchors.modelCarIndex(vehicle, carNumber);
		final ObjectArrayList<MmtrVehicleAnchors.Anchor> boards = MmtrVehicleAnchors.findPidBoards(anchors, modelCar);
		final ObjectArrayList<MmtrVehicleAnchors.Anchor> nextBoards = MmtrVehicleAnchors.findNextBoards(anchors, modelCar);
		if (boards.isEmpty() && nextBoards.isEmpty()) {
			if (MISSING_ANCHORS_LOGGED.add(vehicleId)) {
				// 说清是"这一节车（同车型第 N 节）"没有锚点，不是"这个车型没有锚点" —— 一个车型挂多节时，
				// 只有第 0 节能匹配 `car: 0` 的锚点；notes/365 加了回退之后这一条不该再出现。
				Init.LOGGER.info("[MMTR] 车型 {} 的第 {} 节（模型内序号 {}）没有对应的 mmtr_pid_* / mmtr_next_* 锚点，这节车不挂水牌（notes/357）", vehicleId, carNumber + 1, modelCar);
			}
			return;
		}

		if (!isNearPlayer(carWorldPosition)) {
			return;
		}

		for (final MmtrVehicleAnchors.Anchor board : boards) {
			drawBoard(vehicle, carNumber, vehicleId, board, MmtrPidText.Board.DESTINATION, service, terminus, nextStation, carTransform);
		}
		for (final MmtrVehicleAnchors.Anchor board : nextBoards) {
			drawBoard(vehicle, carNumber, vehicleId, board, MmtrPidText.Board.NEXT_STATION, service, terminus, nextStation, carTransform);
		}
	}

	private static void drawBoard(VehicleExtension vehicle, int carNumber, String vehicleId, MmtrVehicleAnchors.Anchor board, MmtrPidText.Board kind, String service, String terminus, String nextStation, StoredMatrixTransformations carTransform) {
		if (MmtrFaceRegistry.hasDocument(vehicleId, board.name)) {
			/*
			 * ★ notes/359：这块牌已经由**面文档**接管（锚点 JSON 的 faces[锚点名]）。
			 * 于是"把水牌切到面文档"是纯资源包动作 —— 加一段 JSON 即可，客户端一行不改；
			 * 这也保证了同一块牌不会被画两遍（面系统的运行时会画它）。
			 */
			return;
		}
		/*
		 * ★ 口径门（notes/357 那三条不变，仍由 MmtrPidText.lines 一处说了算）：
		 *   · 不在作业单上（班次号空）⇒ 两块都不画；
		 *   · 下一站牌没有站名 ⇒ 整块不画；
		 *   · 水牌在回库趟（终点未知）⇒ 照挂，只是没有终点那一行。
		 * 版式（notes/358）只管"怎么摆"，不管"该不该挂" —— 两者分开，判据各有一处。
		 */
		if (MmtrPidText.lines(kind, service, terminus, nextStation).length == 0 || board.widthM <= 0 || board.heightM <= 0) {
			return;
		}
		final MmtrPidLayout layout = MmtrPidLayout.get(vehicleId, kind);
		// 版式身份也进签名：作者改了版式 ⇒ 重画（否则牌上还是旧排版）
		final String signature = MmtrPidText.signature(kind, service, terminus, nextStation, board.widthM, board.heightM) + "|" + layout.id();
		final MmtrPanelTexture slot = MmtrPanelTexture.get(vehicle.getId() + ":" + carNumber + ":" + board.name);
		if (slot.needsRedraw(signature)) {
			final MmtrPanelCanvas canvas = MmtrPanelCanvas.create(board.widthM, board.heightM, layout.pxPerMetre() > 0 ? layout.pxPerMetre() : DEFAULT_PX_PER_METRE);
			layout.paint(canvas, service, terminus, nextStation);
			slot.redraw(canvas, signature);
		}
		final Identifier texture = slot.identifier();
		if (texture != null) {
			// ★ 必须画在**锚点法线那一侧**：水牌的法线朝车外（读它的人在站台上），
			// 而 MmtrPanelQuad 的默认规则是"画在司机那一侧"（仪表盘的口径）—— 用默认规则，
			// 背面剔除会让整块牌在站台上**看不见**（notes/357 §3）。
			MmtrPanelQuad.drawAtNormalSide(texture, board, carTransform, board.widthM, board.heightM);
		}
	}

	/** 玩家（或正被骑乘的车）离这节车够近才画：与仪表盘同一条判据，只是门限更宽松。 */
	private static boolean isNearPlayer(Vector carWorldPosition) {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return false;
		}
		final Vector3d playerPosition = player.getPos();
		final double dx = playerPosition.getXMapped() - carWorldPosition.x();
		final double dy = playerPosition.getYMapped() - carWorldPosition.y();
		final double dz = playerPosition.getZMapped() - carWorldPosition.z();
		return dx * dx + dy * dy + dz * dz <= NEARBY_BOARD_RADIUS_M * NEARBY_BOARD_RADIUS_M;
	}
}
