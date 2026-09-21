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

		private Anchor(String name, Kind kind, int cab, int car, Vector position, Vector normal, Vector up, Vector right, double widthM, double heightM, boolean panelFlipU, int panelPxPerMetre, boolean panelTwoSided, ObjectArrayList<Facet> facets, double canvasWidthM, double canvasHeightM, double[] sagGridM, double sagUMinM, double sagUMaxM, double sagVMinM, double sagVMaxM, int pane) {
			this.name = name;
			this.kind = kind;
			this.cab = cab;
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
		final int wantedCab = cab <= 0 ? 1 : cab;
		Anchor carOnly = null;
		Anchor cabOnly = null;
		for (final Anchor anchor : anchors) {
			if (anchor.kind != Kind.HUD) {
				continue;
			}
			final int anchorCab = anchor.cab <= 0 ? 1 : anchor.cab;
			if (anchor.car == modelCar && anchorCab == wantedCab) {
				return anchor;
			}
			if (anchor.car == modelCar && carOnly == null) {
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
		final ObjectArrayList<Anchor> result = new ObjectArrayList<>();
		for (final Anchor anchor : anchors) {
			if (anchor.kind == Kind.HUD && anchor.car == modelCar) {
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
		final ObjectArrayList<Anchor> result = new ObjectArrayList<>();
		for (final Anchor anchor : anchors) {
			if (anchor.kind == Kind.WINDSHIELD && anchor.car == modelCar) {
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
		final ObjectArrayList<Anchor> result = new ObjectArrayList<>();
		final int wantedCab = cab <= 0 ? 1 : cab;
		for (final Anchor anchor : anchors) {
			if (anchor.kind == Kind.WINDSHIELD && anchor.car == modelCar && (anchor.cab <= 0 ? 1 : anchor.cab) == wantedCab) {
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
		final int wantedCab = cab <= 0 ? 1 : cab;
		final int wantedPane = pane <= 0 ? 1 : pane;
		for (final Anchor anchor : anchors) {
			if (anchor.kind == Kind.WINDSHIELD && anchor.car == modelCar
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
