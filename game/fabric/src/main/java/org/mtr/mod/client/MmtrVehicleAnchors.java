package org.mtr.mod.client;

import org.apache.commons.io.IOUtils;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.ResourceManagerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.data.VehicleExtension;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * B7.6d: named model anchors, read from {@code assets/mtr/mmtr_anchors_<vehicleId>.json}.
 *
 * <p>The OBJ packager strips every {@code mmtr_*} face out of the render geometry and writes its
 * centre, orientation and size into that file. The coordinates are the same car-local space the
 * renderer and MTR's riding code use (1 unit = 1 block, car centre at the origin, +Z towards the
 * rear of the car), so an anchor can be transformed into the world with the car's
 * {@link org.mtr.mod.render.PositionAndRotation} and written straight back into the riding state.</p>
 *
 * <p>Anchor names (see {@code docs/MMTR-OBJ车辆资源包-标准化工作流.md} §1.1):</p>
 * <ul>
 *     <li>{@code mmtr_hud} / {@code mmtr_hud_1} — the dashboard face; its normal points at the driver.</li>
 *     <li>{@code mmtr_cabdoor_<cab>_<n>} — a door into cab {@code <cab>}（<b>模型自己的编号</b>，
 *         见 {@link #engineEndOfSeat}：它<b>不</b>必然等于引擎的 A/B 端）。</li>
 *     <li>{@code mmtr_seat_<cab>} — optional explicit seat point; takes priority over the HUD offset.</li>
 *     <li>{@code mmtr_light_<cab>_<n>} — a lamp (lens centre + throw direction + lens size).</li>
 *     <li>{@code mmtr_pid_<cab>[_<n>]} / {@code mmtr_next_<cab>[_<n>]} — 水牌（班次号 + 本趟终点）与
 *         下一站牌（notes/357）；法线朝车外，文字由客户端画（见 {@code MmtrPidBoard}）。</li>
 * </ul>
 */
public final class MmtrVehicleAnchors {

	private MmtrVehicleAnchors() {
	}

	/**
	 * **锚点驾驶室 ↔ 引擎端**的唯一换算处：只看座位点的**车体局部 Z 的符号**，不看锚点名里的编号。
	 *
	 * <h3>为什么不能按锚点编号当引擎端（2026-09-21 实机）</h3>
	 * <p>车体局部的 <b>+Z 由 bogie1→bogie2 定义</b>（{@code PositionAndRotation.getYaw} 取
	 * {@code atan2(x2−x1, z2−z1)}），而 MTR 把 bogie1 放在编组脊柱的 <b>A 端</b>
	 * （{@code Vehicle.getVehicleCarsAndPositions()} 从 A 端起算、先 bogie1 后 bogie2）。
	 * 所以 <b>+Z = 朝引擎的 B 端</b>。</p>
	 *
	 * <p>而锚点编号是**模型自己的**约定：BR101 的 {@code mmtr_cabdoor_1} 座位点在 {@code z=+7.71}
	 * （= B 端），{@code mmtr_cabdoor_2} 在 {@code z=−7.71}（= A 端）。把编号直接当引擎端用，
	 * 结果就是"引擎以为人坐在 B 端、人实际坐在 A 端" —— 而**方向是由被占用的驾驶室决定的**，
	 * 于是司机永远坐在车尾（现场："被传送至了与行进方向相反的驾驶室"）。</p>
	 *
	 * @return 1 = 引擎的 A 端，2 = 引擎的 B 端
	 */
	public static int engineEndOfSeat(double seatZ) {
		return seatZ > 0 ? 2 : 1;
	}

	/** 反过来：引擎的端（1 = A，2 = B）落在哪个锚点驾驶室编号上（找不到时按同号兜底）。 */
	public static int anchorCabOfEngineEnd(ObjectArrayList<Anchor> anchors, int engineEnd) {
		for (final Anchor anchor : anchors) {
			if (anchor.kind == Kind.CABDOOR) {
				final CabView view = cabView(anchors, anchor.cab);
				if (view != null && engineEndOfSeat(view.z) == engineEnd) {
					return anchor.cab;
				}
			}
		}
		return engineEnd;
	}

	/** The anchor files live in MTR's own namespace, next to the vehicle model. */
	private static final String NAMESPACE = "mtr";
	private static final String FILE_PREFIX = "mmtr_anchors_";
	private static final String FILE_SUFFIX = ".json";

	/** How far behind the dashboard face the driver's seat sits, in blocks. */
	public static final double EYE_BACK_M = 1.05;
	/** How far above the door sill the packager puts the walkable floor slab, in blocks. */
	private static final double FLOOR_TOP_OFFSET_M = 0.05;
	/** MTR's fallback floor height, used when no anchor tells us where the floor is. */
	private static final double DEFAULT_FLOOR_M = 1.0;

	private static final Object2ObjectOpenHashMap<String, ObjectArrayList<Anchor>> CACHE = new Object2ObjectOpenHashMap<>();

	/**
	 * MMTR：模型自带的玻璃几何，来自锚点文件的 {@code glass} 段（notes/345 §7.17）。
	 *
	 * <p>为什么玻璃要单独带一份几何：MTR 的 OBJ 优化渲染路径实际只做 cutout（贴图 α&gt;0.1 ⇒ 实心、
	 * α&lt;0.1 ⇒ 丢弃），模型自己的玻璃材质拿不到 alpha 混合。这里把 {@code glass} 组导出的四边形
	 * 交给客户端，用一条真混合、无背面剔除的层画出来 ⇒ 玻璃观感 = 作者在 Blender 里画的贴图 ✓。</p>
	 */
	private static final Object2ObjectOpenHashMap<String, GlassModel> GLASS_MODELS = new Object2ObjectOpenHashMap<>();

	/** 一个模型的玻璃：一张贴图 + 若干四边形（每块自己的 UV 矩形 [u0,v0,u1,v1]）。 */
	public static final class GlassModel {

		public final Identifier texture;
		public final double[][] positions;
		public final float[][] uvs;

		private GlassModel(Identifier texture, double[][] positions, float[][] uvs) {
			this.texture = texture;
			this.positions = positions;
			this.uvs = uvs;
		}
	}

	/**
	 * @param vehicleId the vehicle model ID
	 * @return that model's own glass geometry (from the OBJ's {@code glass} group), or {@code null}
	 */
	public static GlassModel getGlass(String vehicleId) {
		get(vehicleId);
		return GLASS_MODELS.get(vehicleId);
	}

	private static GlassModel parseGlass(JsonObject glass) {
		try {
			final JsonElement textureElement = glass.get("texture");
			final JsonArray quads = glass.getAsJsonArray("quads");
			if (textureElement == null || textureElement.isJsonNull() || quads == null || quads.size() == 0) {
				return null;
			}
			final String textureName = textureElement.getAsString();
			final int colon = textureName.indexOf(':');
			final Identifier texture = colon < 0 ? new Identifier(NAMESPACE, textureName) : new Identifier(textureName.substring(0, colon), textureName.substring(colon + 1));
			final ObjectArrayList<double[]> points = new ObjectArrayList<>();
			final ObjectArrayList<float[]> uvRects = new ObjectArrayList<>();
			for (final JsonElement element : quads) {
				if (!element.isJsonObject()) {
					continue;
				}
				final JsonObject quad = element.getAsJsonObject();
				final JsonArray corners = quad.getAsJsonArray("positions");
				final JsonArray uv = quad.getAsJsonArray("uv");
				if (corners == null || uv == null || corners.size() < 3 || uv.size() < 4) {
					continue;
				}
				final double[] p = new double[12];
				for (int i = 0; i < 4; i++) {
					final JsonArray corner = corners.get(Math.min(i, corners.size() - 1)).getAsJsonArray();
					// 文件空间 -> 骑乘空间：(x,y,z) -> (-x, y, -z)。锚点走的是同一条换算
					// （见 toRidingSpace），玻璃几何必须跟着转，否则会画到镜像/另一端去。
					p[i * 3] = -corner.get(0).getAsDouble();
					p[i * 3 + 1] = corner.get(1).getAsDouble();
					p[i * 3 + 2] = -corner.get(2).getAsDouble();
				}
				points.add(p);
				uvRects.add(new float[]{uv.get(0).getAsFloat(), uv.get(1).getAsFloat(), uv.get(2).getAsFloat(), uv.get(3).getAsFloat()});
			}
			if (points.isEmpty()) {
				return null;
			}
			return new GlassModel(texture, points.toArray(new double[0][]), uvRects.toArray(new float[0][]));
		} catch (Exception exception) {
			Init.LOGGER.error("[MMTR-GLASS] 解析 glass 段失败", exception);
			return null;
		}
	}

	/**
	 * Per model: the car-local height a rider's feet end up at (the anchor file's {@code rider.feetY}).
	 *
	 * <p>MTR builds no floor boxes for OBJ models and substitutes a synthetic slab at
	 * {@code y = 1 + legacyRiderOffset}; that slab is what {@code VehicleRidingMovement} clamps the rider
	 * onto. The cab entry uses this value so it lands ON that slab. Without it the entry height has to
	 * come from the door sill and must stay within the clamp's 1 m tolerance of the slab, which is what
	 * capped how high the driver's modelled eye point could be raised.</p>
	 */
	private static final Object2ObjectOpenHashMap<String, Double> RIDER_FEET_Y = new Object2ObjectOpenHashMap<>();

	public enum Kind {
		HUD,
		CABDOOR,
		DOOR,
		SEAT,
		/** MMTR: the rain/wiper plane (a bare windscreen face with no dashboard texture on it). */
		WINDSHIELD,
		/**
		 * MMTR: a lamp of a cab — {@code mmtr_light_<cab>_<n>}, PURE DATA (the face is stripped from the
		 * render geometry). Position = the lens centre, normal = the direction the lamp throws,
		 * size = the lens. The client turns it into a per-fragment light (see
		 * {@code org.mtr.mod.render.light.MmtrHeadlights}); the index is only the ORDINAL of the lamp on
		 * that end, and how strong it is comes from the lens AREA — measured, not assumed: SAF420's front
		 * carries two identical 0.26 m square lamps, so "1 = main, 2+ = dim marker" would have made one
		 * headlight bright and the other dim (notes/345 §2.1).
		 */
		LIGHT,
		/**
		 * MMTR notes/357：**水牌 / PID**（{@code mmtr_pid_<cab>[_<n>]}）—— 车体上的目的地牌，
		 * 纯数据（面被剥掉、不进几何）。位置 = 牌面中心，法线朝**车外**（站台上要看得见），
		 * {@code up} = 文字的上方向，{@code widthM/heightM} = 牌面大小。
		 *
		 * <p>同一端可以有多块（两侧各一块）：它们的内容**相同**（都由这一端决定），客户端逐块画。</p>
		 */
		PID,
		/** MMTR notes/357：**下一站牌**（{@code mmtr_next_<cab>[_<n>]}）—— 车内显示屏，写"下一站 X"。约定同 {@link #PID}。 */
		NEXT,
		/**
		 * MMTR notes/359：**动态面**（{@code mmtr_face_<cab>[_<n>]}）—— 画什么由锚点 JSON 的
		 * {@code faces} 段里**同名**的那份文档说了算（水牌 / 下一站牌是它的两个内置特例）。
		 *
		 * <p>★ 客户端**不靠这个 kind 决定画什么**：面系统的运行时是"凡是有文档的锚点就画"
		 * （见 {@code MmtrFaceRegistry}）—— 所以任何锚点（含水牌、仪表、以后的门内屏）都能挂文档。
		 * 这个 kind 的用处是可读与可校验：打包日志里一眼看得出这是动态面，
		 * {@code tools/anchor-check/verify_face.js} 也按它核对"该有文档的锚点有没有文档"。</p>
		 */
		FACE,
		OTHER
	}

	/**
	 * One FACET of a folded dashboard, in the raw OBJ coordinate space (the same space
	 * {@code MmtrPanelQuad} places the panel in).
	 *
	 * <p>A dashboard modelled as a bent surface carries several facets; a flat one carries none and is
	 * drawn by the original single-quad path. {@code u0,v0,u1,v1} is the facet's rectangle inside the
	 * SHARED canvas the packager computes by unfolding the surface along the crease, so the painted
	 * image is continuous across the fold. {@code v0} is the TOP edge (texture row 0 is the top of the
	 * image, which is also where the single-quad path puts it).</p>
	 */
	public static final class Facet {

		public final Vector position;
		public final Vector normal;
		public final Vector up;
		public final Vector right;
		public final double widthM;
		public final double heightM;
		public final double u0;
		public final double v0;
		public final double u1;
		public final double v1;
		/**
		 * The facet's own four corners as {@code (right, up)} offsets from {@link #position}, or
		 * {@code null} for a pack that predates them.
		 *
		 * <p>A facet is not always a rectangle: a dashboard wing that follows the desk's flowing line is a
		 * parallelogram, and the {@code widthM x heightM} rectangle then covers it with its BOUNDING box
		 * and overhangs the desk by the shear. The corners let the client draw the facet exactly.</p>
		 */
		@Nullable
		public final double[][] corners;

		private Facet(Vector position, Vector normal, Vector up, Vector right, double widthM, double heightM, double u0, double v0, double u1, double v1) {
			this(position, normal, up, right, widthM, heightM, u0, v0, u1, v1, null);
		}

		private Facet(Vector position, Vector normal, Vector up, Vector right, double widthM, double heightM, double u0, double v0, double u1, double v1, @Nullable double[][] corners) {
			this.position = position;
			this.normal = normal;
			this.up = up;
			this.right = right;
			this.widthM = widthM;
			this.heightM = heightM;
			this.u0 = u0;
			this.v0 = v0;
			this.u1 = u1;
			this.v1 = v1;
			this.corners = corners;
		}
	}

	/**
	 * One named face of the model. {@code car} is the car index inside the vehicle model, and
	 * {@code cab} is the 1-based cab number for cab anchors ({@code 0} when not cab specific).
	 *
	 * <p>{@code position/normal/up/right} are in MTR's riding space (used to place the player and to
	 * aim at a door); {@code filePosition/fileNormal/fileUp/fileRight} are the raw OBJ coordinates,
	 * which is the space MTR renders the model geometry and its display text in.</p>
	 */
	public static final class Anchor {

		public final String name;
		public final Kind kind;
		public final int cab;
		/**
		 * WHICH one of this kind this anchor is, from the naming convention
		 * {@code <kind>[_<cab>][_<index>]}: the third number of {@code mmtr_light_1_2} is 2. Only the
		 * lamp family uses it so far, as an ORDINAL among that end's lamps (brightness comes from the
		 * lens area, not from this number — notes/345 §2.1); a pack that does not write it reads as 1.
		 */
		public final int index;
		/**
		 * Which glass pane of the cab this is: 1 = the main screen, higher = side windows and the rest
		 * (docs §1.4①). Only the windscreen family has one; it is 1 when the anchor file omits it.
		 */
		public final int pane;
		public final int car;
		public final Vector position;
		public final Vector normal;
		public final Vector up;
		public final Vector right;
		public final Vector filePosition;
		public final Vector fileNormal;
		public final Vector fileUp;
		public final Vector fileRight;
		public final double widthM;
		public final double heightM;
		/** Mirrors the panel's texture horizontally, for faces whose authored "right" edge reads the other way. */
		public final boolean panelFlipU;
		/** Texture resolution of the 2D panel drawn on this face, in pixels per block; 0 = client default. */
		public final int panelPxPerMetre;
		/** Draw the panel on both sides of the face (diagnostics); normally only the driver's side is drawn. */
		public final boolean panelTwoSided;
		/**
		 * Every face of a FOLDED dashboard, in file order, with the uv rectangle each one owns in the
		 * shared unfolded canvas. EMPTY for a single-face dashboard, which keeps the original
		 * one-quad path - so a model that was fine before this existed behaves exactly as it did.
		 */
		public final ObjectArrayList<Facet> facets;
		/** Unfolded canvas size of a folded dashboard, in blocks; {@code 0} when there are no facets. */
		public final double canvasWidthM;
		public final double canvasHeightM;
		/**
		 * THE SAG GRID of a CURVED face: 9 x 9 samples of how far the modelled surface sits from this
		 * anchor's own plane, in metres along {@code normal}, row-major from {@link #sagVMinM} upwards and
		 * {@link #sagUMinM} rightwards. EMPTY for a flat face, which keeps the original plane-only path -
		 * so a model that was fine before this existed behaves exactly as it did.
		 *
		 * <p>An anchor's frame comes from ONE face (the largest), which is exact only while the group is
		 * flat. A curved mmtr_windshield is therefore treated as the plane of whichever patch happened to
		 * be biggest: measured on BR101 V25, the real glass sits up to 112 mm from that plane while the
		 * water layer's own offset is 50 mm and the glass is 23 mm thick - the rain floats a hand's width
		 * off the screen, and nothing said so.</p>
		 *
		 * <p>A grid rather than a profile along one axis: a windscreen is usually a cylinder, which a
		 * profile would capture exactly, but only against a frame whose normal is perpendicular to the
		 * cylinder's axis, and nothing guarantees that. Measured on this very surface, a 17-sample profile
		 * still left 18.3 mm because part of the departure is linear across the WIDTH.</p>
		 */
		public final double[] sagGridM;
		public final double sagUMinM;
		public final double sagUMaxM;
		public final double sagVMinM;
		public final double sagVMaxM;
		/** The grid's resolution, so the client never has to be told it twice. */
		public static final int SAG_NX = 17;
		public static final int SAG_NY = 17;

		/**
		 * 包内可见（刻意不是 {@code private}）：{@code MmtrVehicleAnchorsTests} 要造几个锚点来钉
		 * "一个模型挂多节"的查找规则（notes/365）。锚点是不可变值对象，包外依旧构造不出来。
		 */
		Anchor(String name, Kind kind, int cab, int index, int car, Vector position, Vector normal, Vector up, Vector right, double widthM, double heightM, boolean panelFlipU, int panelPxPerMetre, boolean panelTwoSided, ObjectArrayList<Facet> facets, double canvasWidthM, double canvasHeightM, double[] sagGridM, double sagUMinM, double sagUMaxM, double sagVMinM, double sagVMaxM, int pane) {
			this.name = name;
			this.kind = kind;
			this.cab = cab;
			this.index = index <= 0 ? 1 : index;
			this.pane = pane <= 0 ? 1 : pane;
			this.car = car;
			this.filePosition = position;
			this.fileNormal = normal;
			this.fileUp = up;
			this.fileRight = right;
			this.position = toRidingSpace(position);
			this.normal = toRidingSpace(normal);
			this.up = toRidingSpace(up);
			this.right = toRidingSpace(right);
			this.widthM = widthM;
			this.heightM = heightM;
			this.panelFlipU = panelFlipU;
			this.panelPxPerMetre = panelPxPerMetre;
			this.panelTwoSided = panelTwoSided;
			this.facets = facets;
			this.canvasWidthM = canvasWidthM;
			this.canvasHeightM = canvasHeightM;
			this.sagGridM = sagGridM;
			this.sagUMinM = sagUMinM;
			this.sagUMaxM = sagUMaxM;
			this.sagVMinM = sagVMinM;
			this.sagVMaxM = sagVMaxM;
		}
	}

	/** Where a driver ends up when they take a cab, in car-local coordinates. */
	public static final class CabView {

		/** Car index inside the vehicle model this point belongs to. */
		public final int modelCar;
		/** Car-local X/Z of the driver's seat, and the Y their feet start at. */
		public final double x;
		public final double y;
		public final double z;
		/** Horizontal direction the driver looks at, derived from the dashboard normal. */
		public final double forwardX;
		public final double forwardZ;
		/** True when this was derived by mirroring a single-cab model into the B end. */
		public final boolean mirrored;

		private CabView(int modelCar, double x, double y, double z, double forwardX, double forwardZ, boolean mirrored) {
			this.modelCar = modelCar;
			this.x = x;
			this.y = y;
			this.z = z;
			this.forwardX = forwardX;
			this.forwardZ = forwardZ;
			this.mirrored = mirrored;
		}
	}

	/**
	 * @param vehicleId the vehicle model ID (for example {@code hst_h})
	 * @return the anchors of that model, or an empty list when the model has none
	 */
	public static ObjectArrayList<Anchor> get(String vehicleId) {
		if (vehicleId == null || vehicleId.isEmpty()) {
			return new ObjectArrayList<>();
		}
		final ObjectArrayList<Anchor> cached = CACHE.get(vehicleId);
		if (cached != null) {
			return cached;
		}
		final ObjectArrayList<Anchor> anchors = read(vehicleId);
		CACHE.put(vehicleId, anchors);
		return anchors;
	}

	/** Drops the cache so a reloaded resource pack is picked up. */
	public static void clearCache() {
		CACHE.clear();
		GLASS_MODELS.clear();
		RIDER_FEET_Y.clear();
	}

	/**
	 * @param vehicleId the vehicle model ID
	 * @return the car-local height a rider's feet belong at ({@code rider.feetY} from the anchor file),
	 *         or {@link Double#NaN} when the model does not declare one
	 */
	public static double riderFeetY(String vehicleId) {
		get(vehicleId);
		final Double value = RIDER_FEET_Y.get(vehicleId);
		return value == null ? Double.NaN : value;
	}

	/**
	 * @return the door anchor into cab {@code cab}, or {@code null} when the model has none
	 */
	@Nullable
	public static Anchor findCabDoor(ObjectArrayList<Anchor> anchors, int cab) {
		for (final Anchor anchor : anchors) {
			if (anchor.kind == Kind.CABDOOR && anchor.cab == cab) {
				return anchor;
			}
		}
		return null;
	}

	/**
	 * C6: the dashboard anchor of cab {@code cab} in {@code modelCar}.
	 *
	 * <p>A double-ended locomotive has TWO cabs in ONE car, named {@code mmtr_hud_1} and
	 * {@code mmtr_hud_2} (see the packager convention {@code <kind>[_<cab>][_<index>]}), so the lookup
	 * has to be by (car, cab) — looking only by car would always find the first dashboard and put the
	 * second cab's seat and panel in the first cab.</p>
	 *
	 * @param cab 1 = A-end cab, 2 = B-end cab; {@code <= 0} means "unspecified" (single-cab model)
	 */
	@Nullable
	public static Anchor findHud(ObjectArrayList<Anchor> anchors, int modelCar, int cab) {
		final int car = effectiveCar(anchors, modelCar);
		final int wantedCab = cab <= 0 ? 1 : cab;
		Anchor carOnly = null;
		Anchor cabOnly = null;
		for (final Anchor anchor : anchors) {
			if (anchor.kind != Kind.HUD) {
				continue;
			}
			final int anchorCab = anchor.cab <= 0 ? 1 : anchor.cab;
			if (anchor.car == car && anchorCab == wantedCab) {
				return anchor;
			}
			if (anchor.car == car && carOnly == null) {
				carOnly = anchor;
			}
			if (anchorCab == wantedCab && cabOnly == null) {
				cabOnly = anchor;
			}
		}
		return carOnly != null ? carOnly : cabOnly;
	}

	/**
	 * C6: EVERY dashboard anchor of {@code modelCar}, one per cab, in file order. A double-ended
	 * locomotive carries two dashboards in one car ({@code mmtr_hud_1} / {@code mmtr_hud_2}), and each
	 * one belongs to its own cab, so the renderer has to draw a panel per anchor instead of picking a
	 * single one. A single-cab model returns its one anchor.
	 */
	public static ObjectArrayList<Anchor> findHuds(ObjectArrayList<Anchor> anchors, int modelCar) {
		final int car = effectiveCar(anchors, modelCar);
		final ObjectArrayList<Anchor> result = new ObjectArrayList<>();
		for (final Anchor anchor : anchors) {
			if (anchor.kind == Kind.HUD && anchor.car == car) {
				result.add(anchor);
			}
		}
		return result;
	}

	/**
	 * MMTR: EVERY lamp anchor of {@code modelCar}, in file order. A cab carries one per physical lamp
	 * (SAF420: two, left and right), and each one becomes its own shader light, so the caller iterates
	 * them instead of picking a single one.
	 *
	 * <p>The lamp's POSITION is the lens centre, its NORMAL the direction the lamp throws (which
	 * {@code verify_lights.js} checks points AWAY from the car — the hud and windshield anchors point at
	 * the driver, so their winding must not be copied here), and {@code index} is its ordinal on that
	 * end. Strength follows the lens AREA (notes/345 §2.1).</p>
	 */
	public static ObjectArrayList<Anchor> findLights(ObjectArrayList<Anchor> anchors, int modelCar) {
		final int car = effectiveCar(anchors, modelCar);
		final ObjectArrayList<Anchor> result = new ObjectArrayList<>();
		for (final Anchor anchor : anchors) {
			if (anchor.kind == Kind.LIGHT && anchor.car == car) {
				result.add(anchor);
			}
		}
		return result;
	}

	/**
	 * 请求的"模型内车节序号" → **真正该用哪一份锚点**（notes/365）。
	 *
	 * <p>为什么要这一道：锚点 JSON 是**按模型 id** 一份，而 {@code Anchor.car} 是"这个模型里的第几节车"。
	 * 现实里**所有包都只声明了 {@code car: 0}**（SAF420 三份、HST、BR101、saf101 实测全是 {@code car=[0]}），
	 * 而一条编组里同一个模型往往被挂很多次 —— SAF420 的 6M4T 就是 {@code saf420car} 挂了 **8 次**，
	 * 于是第 2..8 节算出来的序号是 1..7，一个锚点都匹配不到：水牌、动态面、仪表、车灯全部消失
	 * （客户端日志只会说"车型 saf420car 没有任何 mmtr_pid_* 锚点"，看起来像车型的问题，其实是序号的问题）。</p>
	 *
	 * <p>规则一句话：**精确声明了就按精确的来，没声明就回退到第 0 节那份**（"这个模型只声明了一套锚点
	 * ⇒ 它的每一节都用这一套"）。这样"一个模型挂 N 次"的编组每一节都能拿到自己的牌；而将来真有包给
	 * 某节车单独声明锚点（{@code car: 1}…），精确匹配优先，回退不会抢。</p>
	 */
	private static int effectiveCar(ObjectArrayList<Anchor> anchors, int modelCar) {
		if (modelCar == 0) {
			return 0;
		}
		for (final Anchor anchor : anchors) {
			if (anchor.car == modelCar) {
				return modelCar;
			}
		}
		return 0;
	}

	/**
	 * Index of a consist car inside its OWN model (a model can be used several times in one consist).
	 *
	 * <p>One rule, one place: the cab panel picks the right {@code mmtr_hud_*} set with it and the
	 * headlight collector picks the right {@code mmtr_light_*} set with it. Two copies would drift the
	 * day a pack starts declaring {@code carIndex}.</p>
	 *
	 * <p>注意：查锚点时**不要**直接拿它去比 {@code anchor.car}，要走 {@link #effectiveCar} —— 只声明了
	 * 一套锚点的模型，它的每一节都该用那一套（notes/365）。</p>
	 */
	public static int modelCarIndex(VehicleExtension vehicle, int carNumber) {
		// 只要"车节 → 模型 id"的顺序，**不要**整列车的位置（那是这一帧里最贵的一步）。
		// 引擎的 mmtrCarResourceId 读的就是 getVehicleCarsAndPositions() 用的那份 immutableVehicleCars，结果相同。
		final String vehicleId = vehicle.mmtrCarResourceId(carNumber);
		if (vehicleId == null) {
			return 0;
		}
		int index = 0;
		for (int i = 0; i < carNumber; i++) {
			if (vehicleId.equals(vehicle.mmtrCarResourceId(i))) {
				index++;
			}
		}
		return index;
	}

	/**
	 * MMTR notes/357：**水牌（目的地牌）** —— 这节车的每一块 {@code mmtr_pid_<cab>[_<n>]}，按文件顺序。
	 *
	 * <p>为什么一次返回一整组而不是"按端挑一块"：一个驾驶室常常两侧各挂一块（{@code _1}/{@code _2}），
	 * 内容一样、位置不同，画的时候要逐块画（与 {@link #findHuds}/{@link #findLights} 同一个道理）。</p>
	 */
	public static ObjectArrayList<Anchor> findPidBoards(ObjectArrayList<Anchor> anchors, int modelCar) {
		return findOfKind(anchors, modelCar, Kind.PID);
	}

	/** MMTR notes/357：**下一站牌** —— 这节车的每一块 {@code mmtr_next_<cab>[_<n>]}，按文件顺序。 */
	public static ObjectArrayList<Anchor> findNextBoards(ObjectArrayList<Anchor> anchors, int modelCar) {
		return findOfKind(anchors, modelCar, Kind.NEXT);
	}

	/**
	 * MMTR notes/359：这节车的**所有**锚点，按文件顺序。
	 *
	 * <p>面文档的运行时用它的理由：**"哪块面归谁画"由数据说了算**（有 {@code faces} 条目的锚点归面系统），
	 * 所以这里刻意**不**按种类过滤 —— 将来任何新锚点族都能直接挂一份面文档，不用改这里。</p>
	 */
	public static ObjectArrayList<Anchor> ofCar(ObjectArrayList<Anchor> anchors, int modelCar) {
		final int car = effectiveCar(anchors, modelCar);
		final ObjectArrayList<Anchor> result = new ObjectArrayList<>();
		for (final Anchor anchor : anchors) {
			if (anchor.car == car) {
				result.add(anchor);
			}
		}
		return result;
	}

	private static ObjectArrayList<Anchor> findOfKind(ObjectArrayList<Anchor> anchors, int modelCar, Kind kind) {
		final int car = effectiveCar(anchors, modelCar);
		final ObjectArrayList<Anchor> result = new ObjectArrayList<>();
		for (final Anchor anchor : anchors) {
			if (anchor.kind == kind && anchor.car == car) {
				result.add(anchor);
			}
		}
		return result;
	}

	/**
	 * MMTR: EVERY windshield (rain/wiper) anchor of {@code modelCar}, in file order. A car can carry
	 * more than one ({@code mmtr_windshield_1} = cab 1's screen, {@code mmtr_windshield_2} = cab 2's),
	 * and each one gets its own precipitation layer and its own wiper, so the renderer draws them
	 * individually. {@code cab} follows the same meaning as everywhere else: 1 = A end, 2 = B end.
	 */
	public static ObjectArrayList<Anchor> findWindshields(ObjectArrayList<Anchor> anchors, int modelCar) {
		final int car = effectiveCar(anchors, modelCar);
		final ObjectArrayList<Anchor> result = new ObjectArrayList<>();
		for (final Anchor anchor : anchors) {
			if (anchor.kind == Kind.WINDSHIELD && anchor.car == car) {
				result.add(anchor);
			}
		}
		return result;
	}

	/**
	 * MMTR: **哪一个驾驶室**的骑乘者正坐在 {@code playerZ} 处（骑乘空间，沿车长）——取最近的风挡锚点。
	 *
	 * <p>这条规则只有一个地方写着，因为已经有三个消费者：雨刷（哪把刀该动）、驾驶输入（能不能操作三根手柄）、
	 * 以及"我是不是司机"的上报。各写一份的下场在本仓有先例：判据一分为二，就会出现"仪表台上写着驾驶室 1、
	 * 动的却是驾驶室 2"（notes/189 §7 就是这么修的）。</p>
	 *
	 * <p>按**沿车长的位置**判，不按车厢号：真模型会在一节车上带两个驾驶室（saf101/BR101 的
	 * {@code windshield_1} 与 {@code windshield_2} 同在 car 0），按车厢号会把两端一起选中。</p>
	 *
	 * @return 1 = A 端驾驶室，2 = B 端驾驶室；这节车没有风挡时返回 0（= 判不出，调用方按"没在驾驶室"处理）
	 */
	public static int nearestCab(ObjectArrayList<Anchor> anchors, int modelCar, double playerZ) {
		double nearestDistanceM = Double.MAX_VALUE;
		int nearestCab = 0;
		for (final Anchor other : findWindshields(anchors, modelCar)) {
			final double distanceM = Math.abs(playerZ - other.position.z());
			if (distanceM < nearestDistanceM) {
				nearestDistanceM = distanceM;
				nearestCab = other.cab <= 0 ? 1 : other.cab;
			}
		}
		return nearestCab;
	}

	/**
	 * MMTR: every glass pane of ONE cab, in file order. Use THIS (not {@link #findWindshield}) for any
	 * cab-wide decision - the wiper stalk, "is the driver aboard", an indicator lamp. A cab with a
	 * three-pane screen has three anchors, and acting on only the first would leave the other two
	 * un-wiped while looking perfectly correct in the log.
	 *
	 * @param cab 1 = A-end cab, 2 = B-end cab; {@code <= 0} means "unspecified" (single-cab model)
	 */
	public static ObjectArrayList<Anchor> findWindshields(ObjectArrayList<Anchor> anchors, int modelCar, int cab) {
		final int car = effectiveCar(anchors, modelCar);
		final ObjectArrayList<Anchor> result = new ObjectArrayList<>();
		final int wantedCab = cab <= 0 ? 1 : cab;
		for (final Anchor anchor : anchors) {
			if (anchor.kind == Kind.WINDSHIELD && anchor.car == car && (anchor.cab <= 0 ? 1 : anchor.cab) == wantedCab) {
				result.add(anchor);
			}
		}
		return result;
	}

	/**
	 * The windshield of one cab and one PANE, or {@code null} when the model has none.
	 *
	 * <p>Pane 1 is the main screen. The pane number defaults to 1 and is only written into the anchor
	 * file when it is not 1 (see docs §1.4①), which is what keeps single-pane models byte-identical.
	 * Prefer {@link #findWindshields(ObjectArrayList, int, int)} for anything about the whole cab.</p>
	 *
	 * @param cab  1 = A-end cab, 2 = B-end cab; {@code <= 0} means "unspecified" (single-cab model)
	 * @param pane 1 = main screen; {@code <= 0} means pane 1
	 */
	@Nullable
	public static Anchor findWindshield(ObjectArrayList<Anchor> anchors, int modelCar, int cab, int pane) {
		final int car = effectiveCar(anchors, modelCar);
		final int wantedCab = cab <= 0 ? 1 : cab;
		final int wantedPane = pane <= 0 ? 1 : pane;
		for (final Anchor anchor : anchors) {
			if (anchor.kind == Kind.WINDSHIELD && anchor.car == car
					&& (anchor.cab <= 0 ? 1 : anchor.cab) == wantedCab
					&& anchor.pane == wantedPane) {
				return anchor;
			}
		}
		return null;
	}

	/**
	 * The MAIN (pane 1) windshield of one cab, or {@code null} when the model has none for it.
	 *
	 * <p>This used to hand back "the first windshield of this cab". That is the same anchor for every
	 * model built before multi-pane glass existed, and a different one only when a cab has several
	 * panes - in which case "whichever came first in the file" was never what the caller meant.</p>
	 */
	@Nullable
	public static Anchor findWindshield(ObjectArrayList<Anchor> anchors, int modelCar, int cab) {
		return findWindshield(anchors, modelCar, cab, 1);
	}

	/**
	 * The point a driver ends up at when taking a cab: an explicit {@code mmtr_seat_<cab>} anchor
	 * wins, otherwise the seat is placed behind the dashboard along the dashboard's own normal, with
	 * the feet at the door sill. MTR forces the riding Y onto the floor every tick, so the X/Z of the
	 * seat is what actually defines the view; Y only decides the first frame.
	 *
	 * @param anchors the model's anchors
	 * @param cab     1 for the A end, 2 for the B end
	 * @return the car-local seat point, or {@code null} when the model has no cab door to enter
	 */
	@Nullable
	public static CabView cabView(ObjectArrayList<Anchor> anchors, int cab) {
		final Anchor door = findCabDoor(anchors, cab);
		final boolean mirrored = cab == 2 && door == null;
		final Anchor effectiveDoor = mirrored ? findCabDoor(anchors, 1) : door;
		if (effectiveDoor == null) {
			return null;
		}
		// The effective cab number: a mirrored single-cab model uses its cab 1 anchors for cab 2.
		final int effectiveCab = mirrored ? 1 : cab;

		final double zSign = mirrored ? -1 : 1;
		final Anchor hud = findHud(anchors, effectiveDoor.car, effectiveCab);
		final Anchor seat = findSeat(anchors, effectiveCab);
		if (seat != null) {
			// A seat anchor's normal is the direction of travel; mirroring only flips the Z component.
			// The Y is taken from the door sill: MTR snaps the rider onto the floor every tick and
			// drops them entirely when the placed Y is more than a block above the floor, so a seat
			// modelled at eye height would make the crew fall out of the train.
			final double seatFloorY = effectiveDoor.heightM > 0.05 ? effectiveDoor.position.y() - effectiveDoor.heightM / 2 : DEFAULT_FLOOR_M;
			return new CabView(seat.car, seat.position.x(), seatFloorY + FLOOR_TOP_OFFSET_M, zSign * seat.position.z(), seat.normal.x(), zSign * seat.normal.z(), mirrored);
		}

		final double sillY = effectiveDoor.heightM > 0.05 ? effectiveDoor.position.y() - effectiveDoor.heightM / 2 : DEFAULT_FLOOR_M;
		final double baseX = hud == null ? effectiveDoor.position.x() : hud.position.x();
		final double baseZ = (hud == null ? effectiveDoor.position.z() : hud.position.z()) * zSign;

		// The dashboard normal points at the driver, so walking along its horizontal part moves the
		// seat backwards into the cab. A face without a usable horizontal normal falls back to +Z.
		double backX = hud == null ? 0 : hud.normal.x();
		double backZ = hud == null ? 1 : hud.normal.z() * zSign;
		final double backLength = Math.sqrt(backX * backX + backZ * backZ);
		if (backLength < 1.0E-4) {
			backX = 0;
			backZ = 1;
		} else {
			backX /= backLength;
			backZ /= backLength;
		}

		// The driver faces the opposite way the dashboard normal points (it points at them).
		return new CabView(
				effectiveDoor.car,
				baseX + backX * EYE_BACK_M,
				sillY + FLOOR_TOP_OFFSET_M,
				baseZ + backZ * EYE_BACK_M,
				-backX,
				-backZ,
				mirrored
		);
	}

	/**
	 * The explicit {@code mmtr_seat_<cab>} anchor of a cab, or {@code null} when the model has none.
	 *
	 * <p>Its POSITION is where the driver's eyes go - that is what the anchor is authored for - so a
	 * caller that wants to put a camera exactly there uses this, while {@link #cabView} hands back the
	 * FLOOR-derived point instead (it deliberately discards the seat's own Y, because MTR forces a
	 * rider's Y onto the floor every tick and a seat modelled at eye height would drop them out).</p>
	 *
	 * <p>Both callers must mirror cab 2 onto cab 1 when the model is single-ended:
	 * {@code cabView(anchors, 2)} uses {@code findSeat(anchors, 1)} for a mirrored model. Passing a raw
	 * 2 there returns null and silently sends the caller down the dashboard-derived fallback.</p>
	 */
	@Nullable
	public static Anchor findSeat(ObjectArrayList<Anchor> anchors, int cab) {
		for (final Anchor anchor : anchors) {
			if (anchor.kind == Kind.SEAT && anchor.cab == cab) {
				return anchor;
			}
		}
		return null;
	}

	private static ObjectArrayList<Anchor> read(String vehicleId) {
		final ObjectArrayList<Anchor> anchors = new ObjectArrayList<>();
		final String[] content = {""};
		try {
			ResourceManagerHelper.readResource(new Identifier(NAMESPACE, FILE_PREFIX + vehicleId + FILE_SUFFIX), inputStream -> {
				try (final InputStream stream = inputStream) {
					content[0] = IOUtils.toString(stream, StandardCharsets.UTF_8);
				} catch (IOException e) {
					Init.LOGGER.error("Failed to read MMTR anchors for {}", vehicleId, e);
				}
			});
		} catch (Exception e) {
			Init.LOGGER.error("Failed to load MMTR anchors for {}", vehicleId, e);
			return anchors;
		}

		if (content[0].isEmpty()) {
			return anchors;
		}

		try {
			final JsonElement root = JsonParser.parseString(content[0]);
			if (!root.isJsonObject()) {
				return anchors;
			}
			// The rider block rides next to `hud`; remember the feet height for the cab entry.
			final JsonElement rider = root.getAsJsonObject().get("rider");
			if (rider != null && rider.isJsonObject() && rider.getAsJsonObject().has("feetY")) {
				RIDER_FEET_Y.put(vehicleId, rider.getAsJsonObject().get("feetY").getAsDouble());
			}
			// MMTR 玻璃（notes/345 §7.17）：模型自带的玻璃几何 —— 打包器从 OBJ 的 `glass` 组导出
			// （文件空间的四边形 + 该组材质的贴图 + UV 矩形）。完全由 Blender 决定，客户端只负责混合绘制。
			final JsonElement glassElement = root.getAsJsonObject().get("glass");
			if (glassElement != null && glassElement.isJsonObject()) {
				final GlassModel glassModel = parseGlass(glassElement.getAsJsonObject());
				if (glassModel != null) {
					GLASS_MODELS.put(vehicleId, glassModel);
				}
			}
			final JsonArray array = root.getAsJsonObject().getAsJsonArray("anchors");
			if (array == null) {
				return anchors;
			}
			for (final JsonElement element : array) {
				if (!element.isJsonObject()) {
					continue;
				}
				final JsonObject object = element.getAsJsonObject();
				anchors.add(new Anchor(
						getString(object, "name", ""),
						parseKind(getString(object, "kind", "")),
						getInt(object, "cab", 0),
						getInt(object, "index", 1),
						getInt(object, "car", 0),
						getVector(object, "x", "y", "z"),
						getVector(object, "normal"),
						getVector(object, "up"),
						getVector(object, "right"),
						getDouble(object, "widthM", 0),
						getDouble(object, "heightM", 0),
						getBoolean(object, "panelFlipU", false),
						getInt(object, "panelPxPerMetre", 0),
						getBoolean(object, "panelTwoSided", false),
						getFacets(object),
						getDouble(object, "canvasWidthM", 0),
						getDouble(object, "canvasHeightM", 0),
						getSagGrid(object),
						getDouble(object, "sagUMinM", -getDouble(object, "widthM", 0) / 2),
						getDouble(object, "sagUMaxM", getDouble(object, "widthM", 0) / 2),
						getDouble(object, "sagVMinM", -getDouble(object, "heightM", 0) / 2),
						getDouble(object, "sagVMaxM", getDouble(object, "heightM", 0) / 2),
						getInt(object, "pane", 1)
				));
			}
		} catch (Exception e) {
			Init.LOGGER.error("Failed to parse MMTR anchors for {}", vehicleId, e);
		}

		return anchors;
	}

	/**
	 * Converts an anchor coordinate or direction from the OBJ file space into MTR's riding space.
	 *
	 * <p>MTR renders a vehicle model with a 180 degree Y flip ({@code getStoredMatrixTransformations})
	 * and then builds its floor/doorway boxes by negating all three axes of the parsed bounds, so the
	 * space that players, floors and doorways live in is the file space rotated 180 degrees about Y:
	 * {@code (x, y, z) -> (-x, y, -z)}. Anchors are authored in the file space (easy to compare with
	 * Blender), so they must be mirrored here or the panel, the seat and the cab door all end up on
	 * the opposite side of the car.</p>
	 */
	private static Vector toRidingSpace(Vector vector) {
		return new Vector(-vector.x(), vector.y(), -vector.z());
	}

	private static Kind parseKind(String kind) {
		switch (kind) {
			case "hud":
				return Kind.HUD;
			case "cabdoor":
				return Kind.CABDOOR;
			case "door":
				return Kind.DOOR;
			case "seat":
				return Kind.SEAT;
			case "windshield":
				return Kind.WINDSHIELD;
			case "light":
				return Kind.LIGHT;
			case "pid":
				return Kind.PID;
			case "next":
				return Kind.NEXT;
			case "face":
				return Kind.FACE;
			default:
				return Kind.OTHER;
		}
	}

	/**
	 * The {@code faces} array of a folded dashboard, or an empty list for a flat one.
	 *
	 * <p>An empty list is what keeps the original single-quad path alive, so a resource pack built
	 * before facets existed needs no repacking.</p>
	 */
	/**
	 * The sag profile of a curved face, in metres along the anchor normal, sampled evenly along up.
	 *
	 * <p>An empty array is what keeps a flat model on the original plane-only path, so a pack built before
	 * curved faces existed needs no repacking. A profile shorter than two samples is meaningless and is
	 * treated as flat rather than believed.</p>
	 */
	private static double[] getSagGrid(JsonObject object) {
		final JsonElement element = object.get("sagGridM");
		if (element == null || !element.isJsonArray()) {
			return EMPTY_SAG;
		}
		final JsonArray array = element.getAsJsonArray();
		if (array.size() != Anchor.SAG_NX * Anchor.SAG_NY) {
			// A grid of the wrong size cannot be indexed; treating it as flat is the only safe reading.
			return EMPTY_SAG;
		}
		final double[] grid = new double[array.size()];
		for (int index = 0; index < grid.length; index++) {
			grid[index] = array.get(index).getAsDouble();
		}
		return grid;
	}

	private static final double[] EMPTY_SAG = new double[0];
	private static ObjectArrayList<Facet> getFacets(JsonObject object) {		final ObjectArrayList<Facet> facets = new ObjectArrayList<>();
		final JsonElement element = object.get("faces");
		if (element == null || !element.isJsonArray()) {
			return facets;
		}
		for (final JsonElement faceElement : element.getAsJsonArray()) {
			if (!faceElement.isJsonObject()) {
				continue;
			}
			final JsonObject faceObject = faceElement.getAsJsonObject();
			facets.add(new Facet(
					getVector(faceObject, "x", "y", "z"),
					getVector(faceObject, "normal"),
					getVector(faceObject, "up"),
					getVector(faceObject, "right"),
					getDouble(faceObject, "widthM", 0),
					getDouble(faceObject, "heightM", 0),
					getDouble(faceObject, "u0", 0),
					getDouble(faceObject, "v0", 0),
					getDouble(faceObject, "u1", 1),
					getDouble(faceObject, "v1", 1),
					getCorners(faceObject)
			));
		}
		return facets;
	}

	/**
	 * @return the facet's own four corners as {@code (right, up)} offsets, or {@code null} when the pack
	 *         does not declare any (a rectangle, or a pack from before the corners existed)
	 */
	@Nullable
	private static double[][] getCorners(JsonObject faceObject) {
		final JsonElement element = faceObject.get("corners");
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 4) {
			return null;
		}
		final double[][] corners = new double[4][];
		int index = 0;
		for (final JsonElement cornerElement : element.getAsJsonArray()) {
			if (!cornerElement.isJsonArray() || cornerElement.getAsJsonArray().size() != 2) {
				return null;
			}
			final JsonArray pair = cornerElement.getAsJsonArray();
			corners[index++] = new double[]{pair.get(0).getAsDouble(), pair.get(1).getAsDouble()};
		}
		return corners;
	}

	private static String getString(JsonObject object, String key, String fallback) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	private static int getInt(JsonObject object, String key, int fallback) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsInt();
	}

	private static double getDouble(JsonObject object, String key, double fallback) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
	}

	private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
	}

	private static Vector getVector(JsonObject object, String key) {
		final JsonElement element = object.get(key);
		if (element != null && element.isJsonArray()) {
			final JsonArray array = element.getAsJsonArray();
			return new Vector(getArrayValue(array, 0), getArrayValue(array, 1), getArrayValue(array, 2));
		}
		return new Vector(0, 0, 0);
	}

	private static Vector getVector(JsonObject object, String keyX, String keyY, String keyZ) {
		return new Vector(getDouble(object, keyX, 0), getDouble(object, keyY, 0), getDouble(object, keyZ, 0));
	}

	private static double getArrayValue(JsonArray array, int index) {
		return index < array.size() ? array.get(index).getAsDouble() : 0;
	}
}
