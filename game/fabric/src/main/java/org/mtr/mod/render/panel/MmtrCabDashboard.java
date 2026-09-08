package org.mtr.mod.render.panel;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mod.Init;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.StoredMatrixTransformations;

/**
 * B7.6e: the driver's dashboard, painted as a 2D image on the model's {@code mmtr_hud} face.
 *
 * <p>Everything visible is drawn into one {@link MmtrPanelCanvas} and uploaded as one texture, which
 * is then placed on the face by {@link MmtrPanelQuad}. The image is only repainted when a displayed
 * value changes ({@link #signature}), so a parked train costs nothing per frame.</p>
 */
public final class MmtrCabDashboard {

	private MmtrCabDashboard() {
	}

	/** Texture resolution of the panel; the canvas clamps it so the image stays small. */
	private static final int DEFAULT_PX_PER_METRE = 256;
	private static final int BACKGROUND_COLOR = 0xFF05080C;
	private static final int PANEL_COLOR = 0xFF0E141C;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int UNIT_COLOR = 0xFFD2E6F7;

	/** Model IDs already reported as having no anchors, so the log is written once per model. */
	private static final ObjectOpenHashSet<String> MISSING_ANCHORS_LOGGED = new ObjectOpenHashSet<>();

	/**
	 * Called once per visible car by {@link org.mtr.mod.render.RenderVehicles}.
	 *
	 * @param vehicle      the vehicle being rendered
	 * @param carNumber    the car index inside the consist
	 * @param vehicleId    the model ID of that car
	 * @param carTransform the transform the car model itself is drawn with
	 */
	public static void render(VehicleExtension vehicle, int carNumber, String vehicleId, StoredMatrixTransformations carTransform) {
		final ObjectArrayList<Anchor> anchors = MmtrVehicleAnchors.get(vehicleId);
		if (anchors.isEmpty()) {
			if (MISSING_ANCHORS_LOGGED.add(vehicleId)) {
				Init.LOGGER.info("[MMTR] model {} has no mmtr_anchors_{}.json in the loaded resource packs, so no cab panel", vehicleId, vehicleId);
			}
			return;
		}

		final Anchor hud = MmtrVehicleAnchors.findHud(anchors, modelCarIndex(vehicle, carNumber));
		if (hud == null) {
			return;
		}

		final int speedKmh = (int) Math.round(vehicle.getSpeed() * 3600);
		final String signature = signature(hud, speedKmh);
		final MmtrPanelTexture slot = MmtrPanelTexture.get(vehicle.getId() + ":" + carNumber + ":" + hud.name);

		if (slot.needsRedraw(signature)) {
			final MmtrPanelCanvas canvas = MmtrPanelCanvas.create(hud.widthM, hud.heightM, hud.panelPxPerMetre > 0 ? hud.panelPxPerMetre : DEFAULT_PX_PER_METRE);
			paint(canvas, speedKmh);
			slot.redraw(canvas, signature);
		}

		MmtrPanelQuad.draw(slot.identifier(), hud, carTransform, hud.widthM, hud.heightM);
	}

	/**
	 * Every value {@link #paint} displays must appear here, otherwise the panel keeps showing stale
	 * numbers. Geometry (the modelled face size and the pixel density) is included too so a reloaded
	 * model repaints at the new size.
	 */
	private static String signature(Anchor hud, int speedKmh) {
		return speedKmh + "|" + hud.widthM + "x" + hud.heightM + "@" + hud.panelPxPerMetre;
	}

	private static void paint(MmtrPanelCanvas canvas, int speedKmh) {
		final double width = canvas.widthM();
		final double height = canvas.heightM();

		canvas.fill(0, 0, width, height, BACKGROUND_COLOR);
		canvas.fillRoundRect(width * 0.015, height * 0.06, width * 0.97, height * 0.88, height * 0.08, PANEL_COLOR);

		canvas.text(String.valueOf(speedKmh), width * 0.5, height * 0.60, height * 0.52, TEXT_COLOR, IGui.HorizontalAlignment.CENTER, IGui.VerticalAlignment.CENTER);
		canvas.text("km/h", width * 0.5, height * 0.19, height * 0.20, UNIT_COLOR, IGui.HorizontalAlignment.CENTER, IGui.VerticalAlignment.CENTER);
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
