package org.mtr.mod.render;

import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.Init;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;

/**
 * B7.6e (first pass): the in-world 2D cab panel, drawn on the {@code mmtr_hud} face the modeller
 * provides. Only the speed readout is wired up for now, using MTR's own font
 * ({@link IDrawing#drawStringWithFont} picks the {@code mtr:mtr} font when the client option
 * "use MTR font" is on) so the panel looks like the rest of MTR.
 *
 * <p>The anchor gives the face centre, its basis ({@code right}/{@code up}/{@code normal}) and its
 * real size in blocks, so the panel is laid out relative to the modelled face instead of being
 * hard-coded: the text plane is rotated onto the face and everything is scaled from
 * {@code widthM}/{@code heightM}.</p>
 */
public final class MmtrCabPanel {

	private MmtrCabPanel() {
	}

	/** Cars further away than this do not schedule a panel render. */
	private static final double RENDER_DISTANCE_M = 32;
	/** Lift the plane off the modelled face so it does not z-fight with the dashboard. */
	private static final float SURFACE_OFFSET_M = 0.005F;

	private static final int TEXT_COLOR = 0xFFE8F4FF;
	private static final int UNIT_COLOR = 0xFF8FA6B8;
	/** Backing plate behind the readout (near-black, slightly translucent). */
	private static final int BACKGROUND_COLOR = 0xE6000000;
	/**
	 * Extra roll of the readout inside the dashboard plane, in degrees. The modelled face's own
	 * "right" edge does not necessarily run the way text should read, so this is dialled in once per
	 * model convention (90 = text runs across the car instead of along it).
	 */
	private static final double PANEL_ROLL_DEGREES = 90;

	/** Model IDs already reported as having no anchors, so the log is written once per model. */
	private static final ObjectOpenHashSet<String> MISSING_ANCHORS_LOGGED = new ObjectOpenHashSet<>();

	/**
	 * Called once per visible car by {@link RenderVehicles}.
	 *
	 * <p>The panel is drawn in the same space MTR draws the model and its own display text in (the
	 * model space with its 180 degree Y flip), using the very transform the car geometry uses, and
	 * with the anchor's raw OBJ coordinates. That keeps the text upright and on the modelled face
	 * instead of guessing how MTR's text space is oriented.</p>
	 *
	 * @param vehicle                 the vehicle being rendered
	 * @param carNumber               the car index inside the consist
	 * @param vehicleId               the model ID of that car
	 * @param carPositionAndRotation  the car transform, used for the distance gate only
	 * @param modelTransformations    the stored transform the car model itself is drawn with
	 */
	public static void render(VehicleExtension vehicle, int carNumber, String vehicleId, PositionAndRotation carPositionAndRotation, StoredMatrixTransformations modelTransformations) {
		final ObjectArrayList<Anchor> anchors = MmtrVehicleAnchors.get(vehicleId);
		if (anchors.isEmpty()) {
			if (MISSING_ANCHORS_LOGGED.add(vehicleId)) {
				Init.LOGGER.info("[MMTR] model {} has no mmtr_anchors_{}.json in the loaded resource packs, so no cab panel", vehicleId, vehicleId);
			}
			return;
		}

		final Vector3d cameraPosition = MinecraftClient.getInstance().getGameRendererMapped().getCamera().getPos();
		final double dx = carPositionAndRotation.position.x() - cameraPosition.getXMapped();
		final double dy = carPositionAndRotation.position.y() - cameraPosition.getYMapped();
		final double dz = carPositionAndRotation.position.z() - cameraPosition.getZMapped();
		if (dx * dx + dy * dy + dz * dz > RENDER_DISTANCE_M * RENDER_DISTANCE_M) {
			return;
		}

		final Anchor hud = MmtrVehicleAnchors.findHud(anchors, modelCarIndex(vehicle, carNumber));
		if (hud == null) {
			return;
		}

		// Speed comes from the client's vehicle mirror (same source as the screen HUD).
		final String speedText = String.valueOf((int) Math.round(vehicle.getSpeed() * 3600));
		final float numberHeightM = (float) Math.max(0.08, hud.heightM * 0.62);
		final float unitHeightM = (float) Math.max(0.04, hud.heightM * 0.22);
		// drawStringWithFont works in font units (a line is IGui.LINE_HEIGHT = 10 units), so the
		// scale that makes a glyph numberHeightM tall is TEXT_HEIGHT / height.
		final float numberScale = (float) (IGui.TEXT_HEIGHT / numberHeightM);
		final float unitScale = (float) (IGui.TEXT_HEIGHT / unitHeightM);
		final float maxWidthM = (float) (hud.widthM * 0.92);
		final double[] basisDegrees = basisDegrees(hud);

		final StoredMatrixTransformations storedMatrixTransformations = modelTransformations.copy();
		storedMatrixTransformations.add(graphicsHolder -> graphicsHolder.translate(toModelSpace(hud.filePosition).x(), toModelSpace(hud.filePosition).y(), toModelSpace(hud.filePosition).z()));
		storedMatrixTransformations.add(graphicsHolder -> {
			graphicsHolder.rotateYDegrees((float) basisDegrees[0]);
			graphicsHolder.rotateXDegrees((float) basisDegrees[1]);
			graphicsHolder.rotateZDegrees((float) basisDegrees[2]);
		});
		storedMatrixTransformations.add(graphicsHolder -> graphicsHolder.translate(0, 0, SURFACE_OFFSET_M));

		MainRenderer.scheduleRender(QueuedRenderLayer.TEXT, (graphicsHolder, offset) -> {
			storedMatrixTransformations.transform(graphicsHolder, offset);
			IDrawing.drawStringWithFont(
					graphicsHolder, speedText,
					IGui.HorizontalAlignment.CENTER, IGui.VerticalAlignment.CENTER,
					0, (float) (hud.heightM * 0.12),
					maxWidthM, (float) (hud.heightM * 0.72),
					numberScale, TEXT_COLOR, false, GraphicsHolder.getDefaultLight(), null
			);
			IDrawing.drawStringWithFont(
					graphicsHolder, "km/h",
					IGui.HorizontalAlignment.CENTER, IGui.VerticalAlignment.CENTER,
					0, (float) (-hud.heightM * 0.30),
					maxWidthM, (float) (hud.heightM * 0.28),
					unitScale, UNIT_COLOR, false, GraphicsHolder.getDefaultLight(), null
			);
			graphicsHolder.pop();
		});

		// Black backing plate so the readout stays legible against a bright interior. Drawn as a
		// white-texture quad in the same face-aligned space, slightly behind the text.
		final StoredMatrixTransformations backgroundTransformations = modelTransformations.copy();
		backgroundTransformations.add(graphicsHolder -> graphicsHolder.translate(toModelSpace(hud.filePosition).x(), toModelSpace(hud.filePosition).y(), toModelSpace(hud.filePosition).z()));
		backgroundTransformations.add(graphicsHolder -> {
			graphicsHolder.rotateYDegrees((float) basisDegrees[0]);
			graphicsHolder.rotateXDegrees((float) basisDegrees[1]);
			graphicsHolder.rotateZDegrees((float) basisDegrees[2]);
		});
		backgroundTransformations.add(graphicsHolder -> graphicsHolder.translate(0, 0, SURFACE_OFFSET_M * 0.5F));
		final float halfWidth = (float) (hud.widthM / 2);
		final float halfHeight = (float) (hud.heightM / 2);
		MainRenderer.scheduleRender(new Identifier(Init.MOD_ID, "textures/block/white.png"), false, QueuedRenderLayer.LIGHT_TRANSLUCENT, (graphicsHolder, offset) -> {
			backgroundTransformations.transform(graphicsHolder, offset);
			IDrawing.drawTexture(graphicsHolder, -halfWidth, -halfHeight, 0, halfWidth, halfHeight, 0, Direction.UP, BACKGROUND_COLOR, GraphicsHolder.getDefaultLight());
			graphicsHolder.pop();
		});
	}

	/**
	 * Euler angles that rotate the text plane (its +X to the right, +Y up, +Z out of the screen) onto
	 * the anchor's face in the model space. MTR rotates vertices by X first, then Y, so the calls are
	 * made in the reverse order and a roll about the face normal is applied last.
	 */
	private static double[] basisDegrees(Anchor anchor) {
		final Vector normal = toModelSpace(anchor.fileNormal).normalize();
		final double pitch = Math.asin(Math.max(-1, Math.min(1, normal.y())));
		final double yaw = Math.atan2(normal.x(), normal.z());

		// Where the text axes end up after rotateX(pitch) then rotateY(yaw), following MTR's
		// Vector.rotateX/rotateY convention.
		final double mappedRightX = Math.cos(yaw);
		final double mappedRightZ = -Math.sin(yaw);
		final double mappedUpX = -Math.sin(pitch) * Math.sin(yaw);
		final double mappedUpY = Math.cos(pitch);
		final double mappedUpZ = -Math.sin(pitch) * Math.cos(yaw);

		final Vector right = toModelSpace(anchor.fileRight).normalize();
		final double roll = -Math.atan2(
				right.x() * mappedUpX + right.y() * mappedUpY + right.z() * mappedUpZ,
				right.x() * mappedRightX + right.z() * mappedRightZ
		) + Math.toRadians(PANEL_ROLL_DEGREES);

		return new double[]{Math.toDegrees(yaw), Math.toDegrees(pitch), Math.toDegrees(roll)};
	}

	/**
	 * Converts an anchor coordinate or direction from the OBJ file space into the space MTR renders
	 * the model (and its own display text) in. Verified against MTR's own loader: a part at file
	 * {@code z = +1.09} (the cab door) is parsed at {@code z = -1.09}, so the model space is
	 * {@code (x, -y, -z)} of the file space.
	 */
	private static Vector toModelSpace(Vector fileVector) {
		return new Vector(fileVector.x(), -fileVector.y(), -fileVector.z());
	}

	/** Index of a consist car inside its own model (a model can be used several times). */
	private static int modelCarIndex(VehicleExtension vehicle, int carNumber) {
		final var cars = vehicle.getVehicleCarsAndPositions();
		if (carNumber < 0 || carNumber >= cars.size()) {
			return 0;
		}
		final String vehicleId = cars.get(carNumber).left().getVehicleId();
		int index = 0;
		for (int i = 0; i < carNumber; i++) {
			if (cars.get(i).left().getVehicleId().equals(vehicleId)) {
				index++;
			}
		}
		return index;
	}
}
