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
 *     <li>{@code mmtr_cabdoor_<cab>_<n>} — a door into cab {@code <cab>} (1 = A end, 2 = B end).</li>
 *     <li>{@code mmtr_seat_<cab>} — optional explicit seat point; takes priority over the HUD offset.</li>
 * </ul>
 */
public final class MmtrVehicleAnchors {

	private MmtrVehicleAnchors() {
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

	public enum Kind {
		HUD,
		CABDOOR,
		DOOR,
		SEAT,
		OTHER
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

		private Anchor(String name, Kind kind, int cab, int car, Vector position, Vector normal, Vector up, Vector right, double widthM, double heightM, boolean panelFlipU, int panelPxPerMetre, boolean panelTwoSided) {
			this.name = name;
			this.kind = kind;
			this.cab = cab;
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
	 * @return the dashboard anchor of a model car, or {@code null} when the model has none
	 */
	@Nullable
	public static Anchor findHud(ObjectArrayList<Anchor> anchors, int modelCar) {
		return findHud(anchors, modelCar, 1);
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

	@Nullable
	private static Anchor findSeat(ObjectArrayList<Anchor> anchors, int cab) {
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
						getBoolean(object, "panelTwoSided", false)
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

	private static Kind parseKind(String kind) {		switch (kind) {
			case "hud":
				return Kind.HUD;
			case "cabdoor":
				return Kind.CABDOOR;
			case "door":
				return Kind.DOOR;
			case "seat":
				return Kind.SEAT;
			default:
				return Kind.OTHER;
		}
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
