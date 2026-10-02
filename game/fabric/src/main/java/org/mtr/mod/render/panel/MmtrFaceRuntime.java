package org.mtr.mod.render.panel;

import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.Init;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.mmtr.face.MmtrFaceAnim;
import org.mtr.mod.mmtr.face.MmtrFaceData;
import org.mtr.mod.mmtr.face.MmtrFaceDocument;
import org.mtr.mod.mmtr.face.MmtrFaceGeometry;
import org.mtr.mod.render.StoredMatrixTransformations;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车辆动态面的**绘制运行时**（notes/359）：把"有面文档的锚点"画出来。
 *
 * <h2>一条规则：有文档的锚点归面系统</h2>
 * <p>遍历这节车的锚点，凡是 {@code faces} 段里写了同名条目的（{@link MmtrFaceRegistry}），
 * 一律由这里画；没写的老锚点仍归各家的老渲染器（{@code MmtrPidBoard} 会跳过有文档的锚点）。
 * 于是"把水牌切到面文档"是**纯资源包动作**：JSON 里加一段 {@code faces.pid_1}，客户端一行不用改。</p>
 *
 * <h2>节拍</h2>
 * <ul>
 *   <li><b>数据</b>：每**帧**每台车一份快照（{@link #tick()} 由 {@code MainRenderer} 每帧清一次），
 *       所以同一帧里这块车的十几块面读到的是同一份数据（不会一块写海山、一块写鸥湾）；</li>
 *   <li><b>贴图</b>：只在"文档身份 + 数据 + 牌面尺寸 + 像素密度 + 姿态"的签名变化时重画
 *       （{@link MmtrPanelTexture#needsRedraw}）—— 车开着的时候每帧重画画布会把 CPU 吃光。
 *       **有动画的文档才在签名里带时间桶**（{@link MmtrFaceAnim#bucket}，默认 8 fps），
 *       所以走马灯/闪烁不会把"数据没变就不重画"这条老规矩吃掉；</li>
 *   <li><b>四边形</b>：每帧照画（便宜），画在哪一侧由文档的 {@code side} 说
 *       （默认 {@code normal} = 朝读它的人那一侧，水牌就是这条）；姿态（{@code roll}/{@code tilt}）与
 *       翻牌机（{@code drum}）也在这一步 —— 翻牌机是"一帧画 N 个四边形"，每个面自己一份贴图。</li>
 * </ul>
 *
 * <h2>门与账</h2>
 * <ul>
 *   <li>文档的 {@code require} 不成立 ⇒ 整块不画（"不在作业单上不挂牌"就是这么表达的）；</li>
 *   <li>离玩家 {@link #NEARBY_FACE_RADIUS_M} 以外不画（一列 10 节车几十块面，远处画了也看不见）；</li>
 *   <li>锚点没有几何（{@code widthM/heightM} 为 0）、折叠面（facets）暂不支持 ⇒ 跳过并各记一次日志。</li>
 * </ul>
 */
public final class MmtrFaceRuntime {

	/** 离玩家多远就不画（与水牌同一条粗门限：牌不是仪表盘，远处也该看得见，但仍要有上限）。 */
	private static final double NEARBY_FACE_RADIUS_M = 96;
	/** 文档没写 {@code pxPerMetre} 时的像素密度（与水牌同一档）。 */
	private static final int DEFAULT_PX_PER_METRE = 512;

	/** 本帧的数据快照：车 id → 快照（{@link #tick()} 每帧清）。 */
	private static final Map<Long, MmtrFaceData> DATA = new HashMap<>();
	/** 只提示一次的那些账。 */
	private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

	private MmtrFaceRuntime() {
	}

	/** 每帧一次（{@code MainRenderer} 里与 {@code MmtrPanelTexture.tick()} 同一处）：丢掉上一帧的数据快照。 */
	public static void tick() {
		DATA.clear();
	}

	/** 资源重载时清缓存。 */
	public static void clearCache() {
		DATA.clear();
		LOGGED.clear();
		MmtrFaceRegistry.clearCache();
		MmtrFaceElements.clearWarnings();
		// 图片也一起清：作者改了资源包里的图，刷新资源包就该看到新的（不清的话缓存里还是旧图）
		MmtrFaceImages.clear();
	}

	/** 画这节车上所有"有面文档"的锚点（由 {@code RenderVehicles} 在画每节车时调用）。 */
	public static void render(VehicleExtension vehicle, int carNumber, String vehicleId, StoredMatrixTransformations carTransform, Vector carWorldPosition) {
		final ObjectArrayList<MmtrVehicleAnchors.Anchor> anchors = MmtrVehicleAnchors.get(vehicleId);
		if (anchors.isEmpty()) {
			return;
		}
		final int modelCar = MmtrVehicleAnchors.modelCarIndex(vehicle, carNumber);
		ObjectArrayList<MmtrVehicleAnchors.Anchor> faces = null;
		for (final MmtrVehicleAnchors.Anchor anchor : MmtrVehicleAnchors.ofCar(anchors, modelCar)) {
			if (MmtrFaceRegistry.document(vehicleId, anchor.name) == null) {
				continue;
			}
			if (faces == null) {
				// 有面文档才做距离判定与建快照：没有面的车（绝大多数）一次都不多花
				if (!isNearPlayer(carWorldPosition)) {
					return;
				}
				faces = new ObjectArrayList<>();
			}
			faces.add(anchor);
		}
		if (faces == null) {
			return;
		}

		final MmtrFaceData data = dataOf(vehicle);
		for (final MmtrVehicleAnchors.Anchor anchor : faces) {
			drawFace(vehicle, carNumber, vehicleId, anchor, carTransform, data);
		}
	}

	private static void drawFace(VehicleExtension vehicle, int carNumber, String vehicleId, MmtrVehicleAnchors.Anchor anchor, StoredMatrixTransformations carTransform, MmtrFaceData data) {
		final MmtrFaceDocument document = MmtrFaceRegistry.document(vehicleId, anchor.name);
		if (document == null || anchor.widthM <= 0 || anchor.heightM <= 0) {
			return;
		}
		if (!anchor.facets.isEmpty()) {
			// 折叠面（曲面仪表盘）的展开画布与多四边形路径只有老渲染器有；面文档暂不支持
			if (LOGGED.add("facets:" + vehicleId + ":" + anchor.name)) {
				Init.LOGGER.warn("[MMTR] 锚点 {} 是折叠面（facets），面文档暂不支持 —— 这块面不画（notes/359 F0 边界）", anchor.name);
			}
			return;
		}
		if (!document.visible(data.asMap())) {
			return;
		}

		// 时钟每帧取一次：同一帧里这块牌的所有面（翻牌机的好几面）必须用同一个时刻，
		// 否则同一块牌的两个面会差几毫秒，翻起来像"卡了一下"
		final long timeMs = System.currentTimeMillis();
		final int pxPerMetre = document.pxPerMetre() > 0 ? document.pxPerMetre() : DEFAULT_PX_PER_METRE;
		final MmtrFaceDocument.Drum drum = document.drum();

		if (drum == null) {
			final String signature = signature(document, -1, data, anchor, pxPerMetre, timeMs);
			final MmtrPanelTexture slot = MmtrPanelTexture.get(vehicle.getId() + ":" + carNumber + ":" + anchor.name);
			if (slot.needsRedraw(signature)) {
				final MmtrPanelCanvas canvas = MmtrPanelCanvas.create(anchor.widthM, anchor.heightM, pxPerMetre);
				MmtrFaceElements.paint(canvas, document, data.asMap(), timeMs);
				slot.redraw(canvas, signature);
			}
			final Identifier texture = slot.identifier();
			if (texture != null) {
				MmtrPanelQuad.drawOriented(texture, anchor, carTransform, anchor.widthM, anchor.heightM, sideOf(document),
					document.roll(), document.tilt(), 0, 0);
			}
			return;
		}

		// 翻牌机：一个 N 面棱柱，第 i 面贴第 i 页（页不够就回绕），整根柱子绕锚点的水平轴转。
		// 每一面**自己一份贴图**（页不同、内容也不同），所以纹理槽的键要把面号带上。
		final int pageCount = document.pageCount();
		// ★ 用**并进 vars 之后**的数据选页：pageExpr 里可以引用文档自己起的名字（{"var":"上行"}）
		final int frontPage = document.pageIndex(document.augment(data.asMap()), timeMs);
		final double clockFraction = document.pageClockFraction(timeMs);
		final double radiusM = drum.radiusM() > 0 ? drum.radiusM() : MmtrFaceGeometry.prismRadius(anchor.widthM, drum.count());
		for (int face = 0; face < drum.count(); face++) {
			final int page = pageCount <= 0 ? 0 : face % pageCount;
			final String signature = signature(document, page, data, anchor, pxPerMetre, timeMs);
			final MmtrPanelTexture slot = MmtrPanelTexture.get(vehicle.getId() + ":" + carNumber + ":" + anchor.name + "#drum" + face);
			if (slot.needsRedraw(signature)) {
				final MmtrPanelCanvas canvas = MmtrPanelCanvas.create(anchor.widthM, anchor.heightM, pxPerMetre);
				MmtrFaceElements.paintPage(canvas, document, page, data.asMap(), timeMs);
				slot.redraw(canvas, signature);
			}
			final Identifier texture = slot.identifier();
			if (texture != null) {
				final double angle = MmtrFaceGeometry.drumAngle(face, frontPage, clockFraction, drum.turnFraction(), drum.count());
				MmtrPanelQuad.drawOriented(texture, anchor, carTransform, anchor.widthM, anchor.heightM, sideOf(document),
					document.roll(), document.tilt(), angle, radiusM);
			}
		}
	}

	/**
	 * 重画签名：**变了才重画**这一条全靠它（车开着的时候每帧重画画布会把 CPU 吃光）。
	 *
	 * <p>带时间桶的只有"真的有动画"的文档（{@link MmtrFaceDocument#animated()}）：
	 * 没有动画的牌一个字都不多画，回到 F0 的口径。</p>
	 *
	 * @param page 翻牌机的那一面贴的是第几页；{@code -1} = 单面（按文档自己选页）
	 */
	private static String signature(MmtrFaceDocument document, int page, MmtrFaceData data, MmtrVehicleAnchors.Anchor anchor, int pxPerMetre, long timeMs) {
		final StringBuilder builder = new StringBuilder();
		builder.append(document.id()).append('|').append(data.describe()).append('|')
			.append(anchor.widthM).append('x').append(anchor.heightM).append('@').append(pxPerMetre).append('|')
			.append(document.side()).append('|').append(document.roll()).append(',').append(document.tilt());
		if (page >= 0) {
			builder.append("|p").append(page);
		}
		if (document.animated()) {
			builder.append("|t").append(MmtrFaceAnim.bucket(timeMs, document.fps()));
		}
		return builder.toString();
	}

	/** 面文档的 {@code side} → 四边形画在哪一侧（{@code MmtrPanelQuad.Side}）。 */
	private static MmtrPanelQuad.Side sideOf(MmtrFaceDocument document) {
		return switch (document.side()) {
			case DRIVER -> MmtrPanelQuad.Side.DRIVER;
			case BOTH -> MmtrPanelQuad.Side.BOTH;
			default -> MmtrPanelQuad.Side.NORMAL;
		};
	}

	/** 这台车这一帧的数据快照（同一帧里所有面共用一份）。 */
	private static MmtrFaceData dataOf(VehicleExtension vehicle) {
		final MmtrFaceData cached = DATA.get(vehicle.getId());
		if (cached != null) {
			return cached;
		}
		final MmtrFaceData built = MmtrFaceData.of(new MmtrVehicleFaceSource(vehicle));
		DATA.put(vehicle.getId(), built);
		return built;
	}

	/** 玩家离这节车够近才画（骑在上面时当然够近 —— 96 m 的半径把"在车里看内屏"也覆盖了）。 */
	private static boolean isNearPlayer(Vector carWorldPosition) {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return false;
		}
		final Vector3d playerPosition = player.getPos();
		final double dx = playerPosition.getXMapped() - carWorldPosition.x();
		final double dy = playerPosition.getYMapped() - carWorldPosition.y();
		final double dz = playerPosition.getZMapped() - carWorldPosition.z();
		return dx * dx + dy * dy + dz * dz <= NEARBY_FACE_RADIUS_M * NEARBY_FACE_RADIUS_M;
	}
}
