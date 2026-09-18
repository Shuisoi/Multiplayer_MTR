package org.mtr.mod.render.panel;

import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.Init;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.StoredMatrixTransformations;

/**
 * B7.6e: the driver's dashboard, painted as a 2D image on the model's {@code mmtr_hud} face.
 *
 * <p>Everything visible is drawn into one {@link MmtrPanelCanvas} and uploaded as one texture, which
 * is then placed on the face by {@link MmtrPanelQuad}. The image is only repainted when a displayed
 * value changes ({@link #signature}), so a parked train costs nothing per frame.</p>
 *
 * <p>C6: a car can carry ONE DASHBOARD PER CAB (a double-ended locomotive has {@code mmtr_hud_1} and
 * {@code mmtr_hud_2} in the same car), so every HUD anchor of the car gets its own panel - before
 * this the second face stayed blank. The IMAGE is per MODEL, not per cab: different rolling stock
 * authors its own layout ({@link MmtrHudLayout}, read from the anchor file), and both ends of one car
 * paint the same one.</p>
 */
public final class MmtrCabDashboard {

	private MmtrCabDashboard() {
	}

	/** Texture resolution of the panel; the canvas clamps it so the image stays small. */
	private static final int DEFAULT_PX_PER_METRE = 256;

	/** Model IDs already reported as having no anchors, so the log is written once per model. */
	private static final ObjectOpenHashSet<String> MISSING_ANCHORS_LOGGED = new ObjectOpenHashSet<>();

	/** Cars further than this from the player do not draw a dashboard when the player is not riding. */
	private static final double NEARBY_CAR_RADIUS_M = 12;

	/**
	 * Called once per visible car by {@link org.mtr.mod.render.RenderVehicles}.
	 *
	 * <p>A dashboard is only readable from inside its own car, and every car of a consist can carry the
	 * {@code mmtr_hud} anchor (they are usually the same model), so drawing one per car puts several
	 * dashboards in view at once. Only the car the local player is riding draws one; if the player is
	 * riding something else (another car or a lift) this car draws nothing, and if the player is not
	 * riding at all only a car close to them draws one.</p>
	 *
	 * @param vehicle         the vehicle being rendered
	 * @param carNumber       the car index inside the consist
	 * @param vehicleId       the model ID of that car
	 * @param carTransform    the transform the car model itself is drawn with
	 * @param carWorldPosition the car's world position, for the "near the player" test
	 */
	public static void render(VehicleExtension vehicle, int carNumber, String vehicleId, StoredMatrixTransformations carTransform, Vector carWorldPosition) {
		final long ridingVehicleId = VehicleRidingMovement.getRidingVehicleId();
		if (ridingVehicleId != 0) {
			if (ridingVehicleId != vehicle.getId()) {
				return;
			}
		} else {
			final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
			if (player == null) {
				return;
			}
			final Vector3d playerPosition = player.getPos();
			final double dx = playerPosition.getXMapped() - carWorldPosition.x();
			final double dy = playerPosition.getYMapped() - carWorldPosition.y();
			final double dz = playerPosition.getZMapped() - carWorldPosition.z();
			if (dx * dx + dy * dy + dz * dz > NEARBY_CAR_RADIUS_M * NEARBY_CAR_RADIUS_M) {
				return;
			}
		}

		final ObjectArrayList<Anchor> anchors = MmtrVehicleAnchors.get(vehicleId);
		if (anchors.isEmpty()) {
			if (MISSING_ANCHORS_LOGGED.add(vehicleId)) {
				Init.LOGGER.info("[MMTR] model {} has no mmtr_anchors_{}.json in the loaded resource packs, so no cab panel", vehicleId, vehicleId);
			}
			return;
		}

		// C6: draw one panel per dashboard anchor of this car. A double-ended locomotive carries two
		// dashboards in one car (mmtr_hud_1 / mmtr_hud_2) and each belongs to its own cab, so looking
		// up a single anchor would leave the second cab's panel blank.
		final ObjectArrayList<Anchor> huds = MmtrVehicleAnchors.findHuds(anchors, modelCarIndex(vehicle, carNumber));
		if (huds.isEmpty()) {
			return;
		}

		final int speedKmh = (int) Math.round(vehicle.getSpeed() * 3600);
		final long limitKmh = vehicle.getMmtrSpeedLimitKmhFromSync();
		// The layout belongs to the MODEL (see MmtrHudLayout), so both dashboards of a double-ended
		// locomotive paint the same image; only the face they sit on differs.
		final MmtrHudLayout layout = MmtrHudLayout.get(vehicleId);

		for (final Anchor hud : huds) {
			final String signature = signature(hud, layout, speedKmh, limitKmh);
			final MmtrPanelTexture slot = MmtrPanelTexture.get(vehicle.getId() + ":" + carNumber + ":" + hud.name);

			if (slot.needsRedraw(signature)) {
				// A FOLDED dashboard is painted on ONE canvas covering the whole surface UNFOLDED along
				// its crease (canvasWidthM/canvasHeightM), then drawn as one quad per facet, each
				// sampling only its own rectangle. A flat dashboard has no facets and keeps the original
				// canvas and one-quad path exactly.
				final double canvasWidthM = hud.canvasWidthM > 0 ? hud.canvasWidthM : hud.widthM;
				final double canvasHeightM = hud.canvasHeightM > 0 ? hud.canvasHeightM : hud.heightM;
				final MmtrPanelCanvas canvas = MmtrPanelCanvas.create(canvasWidthM, canvasHeightM, hud.panelPxPerMetre > 0 ? hud.panelPxPerMetre : DEFAULT_PX_PER_METRE);
				layout.paint(canvas, speedKmh, limitKmh);
				slot.redraw(canvas, signature);
			}

			if (hud.facets.isEmpty()) {
				MmtrPanelQuad.draw(slot.identifier(), hud, carTransform, hud.widthM, hud.heightM);
			} else {
				for (final MmtrVehicleAnchors.Facet facet : hud.facets) {
					MmtrPanelQuad.drawFacet(slot.identifier(), hud, facet, carTransform);
				}
			}
		}
	}

	/**
	 * Every value the panel displays must appear here, otherwise it keeps showing stale numbers. The
	 * layout id changes when the authored layout changes, and the geometry (modelled face size, the
	 * unfolded canvas of a folded dashboard, and pixel density) so a reloaded model repaints at the
	 * new size.
	 */
	private static String signature(Anchor hud, MmtrHudLayout layout, int speedKmh, long limitKmh) {
		return layout.id() + "|" + speedKmh + "|" + limitKmh + "|" + hud.widthM + "x" + hud.heightM + "@" + hud.panelPxPerMetre
				+ "|canvas" + hud.canvasWidthM + "x" + hud.canvasHeightM + "|facets" + hud.facets.size();
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
