package org.mtr.mod.render;

import org.mtr.core.data.Vehicle;
import org.mtr.mapping.holder.Box;

import javax.annotation.Nullable;

/**
 * B7.6h: which car-local side of a train is "left", and which doors the crew has opened.
 *
 * <p>A car model runs lengthwise along Z with <strong>+Z towards the rear</strong> of the consist, and
 * MTR's riding/render space is the model file space rotated 180° about Y (see
 * {@link org.mtr.mod.client.MmtrVehicleAnchors}). A driver in the A-end cab faces -Z, so their left
 * hand points to <strong>-X</strong>; a driver in the B-end cab faces +Z, so their left hand points
 * to <strong>+X</strong>. The mirrored cab car of a fixed formation is authored with the same rule,
 * which is why a door part named "left" in the model is on the driver's left from either cab.</p>
 *
 * <p>Everything here is derived from state the engine already mirrors, so the renderer never has to
 * guess: {@code mmtrActiveCab} says which cab leads and {@code mmtrDoorLeft}/{@code mmtrDoorRight}
 * are the crew's per-side door targets.</p>
 */
public final class MmtrDoorSides {

	private MmtrDoorSides() {
	}

	/** Whether the driver's left-hand side sits at negative car-local X for this consist. */
	public static boolean leftIsNegativeX(Vehicle vehicle) {
		return !"CAB_B".equals(vehicle.getMmtrActiveCabFromSync());
	}

	/** Whether {@code carLocalX} lies on the driver's left-hand side of the car. */
	public static boolean isLeftSide(Vehicle vehicle, double carLocalX) {
		return (carLocalX < 0) == leftIsNegativeX(vehicle);
	}

	/** Whether the door part whose car-local bounding box is {@code box} belongs to the open side. */
	public static boolean isOpenSide(Vehicle vehicle, Box box) {
		final double centreX = (box.getMinXMapped() + box.getMaxXMapped()) / 2;
		return isLeftSide(vehicle, centreX)
				? vehicle.vehicleExtraData.getMmtrDoorLeft()
				: vehicle.vehicleExtraData.getMmtrDoorRight();
	}

	/**
	 * Cuts a doorway box down to the half of the car it is actually on, so a doorway that spans the
	 * whole width (as a resource pack may author it) only lets people through on an open side.
	 *
	 * @return the clipped box, or {@code null} when the box is entirely on the closed side
	 */
	@Nullable
	public static Box clipToOpenSide(Vehicle vehicle, Box box, boolean leftSide) {
		final boolean negative = leftSide == leftIsNegativeX(vehicle);
		if (negative) {
			if (box.getMinXMapped() >= 0) {
				return null;
			}
			return new Box(box.getMinXMapped(), box.getMinYMapped(), box.getMinZMapped(), Math.min(box.getMaxXMapped(), 0), box.getMaxYMapped(), box.getMaxZMapped());
		}
		if (box.getMaxXMapped() <= 0) {
			return null;
		}
		return new Box(Math.max(box.getMinXMapped(), 0), box.getMinYMapped(), box.getMinZMapped(), box.getMaxXMapped(), box.getMaxYMapped(), box.getMaxZMapped());
	}
}
