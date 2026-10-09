package org.mtr.mod.data;
import org.mtr.mod.MathUtils;

import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mod.client.Oscillation;
import org.mtr.mod.client.ScrollingText;
import org.mtr.mod.resource.DoorAnimationType;
import org.mtr.mod.resource.Interpolation;
import org.mtr.mod.resource.VehicleResource;
import org.mtr.mod.sound.VehicleSoundBase;

import java.util.function.Supplier;

public final class PersistentVehicleData {

	private double smoothedRailProgress;
	private double railProgressSmoothingAdjustment;
	private double doorValue;
	private double oldDoorValue;
	private double nextAnnouncementRailProgress;
	private int doorCooldown;
	private int overrideDoorMultiplier;

	public final boolean[] rayTracing;
	public final double[] longestDimensions;
	private final TransportMode transportMode;
	private final ObjectArrayList<VehicleSoundBase> vehicleSoundBaseList = new ObjectArrayList<>();
	private final ObjectArrayList<ObjectArrayList<ScrollingText>> scrollingTexts = new ObjectArrayList<>();
	private final ObjectArrayList<Oscillation> oscillations = new ObjectArrayList<>();
	private final Object2ObjectOpenHashMap<String, DoorMovementInterpolation> doorMovementInterpolations = new Object2ObjectOpenHashMap<>();

	public PersistentVehicleData(ObjectImmutableList<VehicleCar> immutableVehicleCars, TransportMode transportMode) {
		rayTracing = new boolean[immutableVehicleCars.size()];
		longestDimensions = new double[immutableVehicleCars.size()];
		for (int i = 0; i < immutableVehicleCars.size(); i++) {
			longestDimensions[i] = Math.max(immutableVehicleCars.get(i).getLength(), immutableVehicleCars.get(i).getWidth());
		}
		this.transportMode = transportMode;
	}

	/**
	 * Whether this cached data still describes the formation behind a vehicle id. The client keeps one
	 * instance per vehicle id, but an id's car count is NOT constant: MMTR coupling/uncoupling surgery
	 * adds and removes cars on the same id, while {@link #rayTracing} and {@link #longestDimensions} are
	 * sized once at construction. Reusing a stale instance made the renderer index past the end of the
	 * array (visible in game as every train and rail vanishing behind a render-thread crash loop).
	 *
	 * @param carCount the car count of the formation the id now stands for
	 */
	public boolean matchesCarCount(int carCount) {
		return rayTracing.length == carCount;
	}

	/**
	 * Captures the rail progress difference of an incoming vehicle update. This will be used for smoothing out animations.
	 *
	 * @param newRailProgress    the rail progress coming from the server
	 * @param totalVehicleLength the total length of this vehicle
	 */
	public void update(double newRailProgress, double totalVehicleLength) {
		railProgressSmoothingAdjustment = newRailProgress - smoothedRailProgress;
		if (Math.abs(railProgressSmoothingAdjustment) > totalVehicleLength - 1) {
			railProgressSmoothingAdjustment = 0;
		}
	}

	public double getSmoothedRailProgress(double railProgress, double adjustmentAmount) {
		if (railProgressSmoothingAdjustment > 0) {
			railProgressSmoothingAdjustment = Math.max(railProgressSmoothingAdjustment - adjustmentAmount, 0);
		} else if (railProgressSmoothingAdjustment < 0) {
			railProgressSmoothingAdjustment = Math.min(railProgressSmoothingAdjustment + adjustmentAmount, 0);
		}
		smoothedRailProgress = railProgress - railProgressSmoothingAdjustment;
		return smoothedRailProgress;
	}

	public ObjectArrayList<ScrollingText> getScrollingText(int carNumber) {
		return getElement(scrollingTexts, carNumber, ObjectArrayList::new);
	}

	public Oscillation getOscillation(int carNumber) {
		return getElement(oscillations, carNumber, () -> new Oscillation(transportMode));
	}

	public void tick(double railProgress, long millisElapsed, VehicleExtraData vehicleExtraData) {
		oldDoorValue = doorValue;
		doorValue = MathUtils.clamp(doorValue + (double) (millisElapsed * getAdjustedDoorMultiplier(vehicleExtraData)) / Vehicle.DOOR_MOVE_TIME, 0, 1);
		if (checkCanOpenDoors()) {
			doorCooldown--;
		} else {
			overrideDoorMultiplier = 0;
		}
		if (doorValue > 0) {
			doorCooldown = 2;
			nextAnnouncementRailProgress = railProgress + vehicleExtraData.getTotalVehicleLength() * 1.5;
		}
		oscillations.forEach(oscillation -> oscillation.tick(millisElapsed));
	}

	public double getDoorValue() {
		return doorValue;
	}

	public float getInterpolatedDoorValue(DoorAnimationType doorAnimationType, double doorZMultiplier, boolean flipped, double doorOverrideValue, boolean opening) {
		final String key = doorZMultiplier + "_" + doorAnimationType + "_" + flipped;
		final DoorMovementInterpolation doorMovementInterpolation = doorMovementInterpolations.get(key);
		final double value = doorAnimationType.getDoorAnimationZ(doorZMultiplier, flipped, doorValue, opening);
		final float interpolatedDoorValue;
		if (doorMovementInterpolation == null) {
			final DoorMovementInterpolation newDoorMovementInterpolation = new DoorMovementInterpolation();
			doorMovementInterpolations.put(key, newDoorMovementInterpolation);
			interpolatedDoorValue = newDoorMovementInterpolation.setAndGet(value, opening);
		} else {
			interpolatedDoorValue = doorMovementInterpolation.setAndGet(value, opening);
		}

		final float newDoorOverrideValue = (float) (doorOverrideValue * doorZMultiplier) * (flipped ? -1 : 1);
		return Math.abs(newDoorOverrideValue) > Math.abs(interpolatedDoorValue) ? newDoorOverrideValue : interpolatedDoorValue;
	}

	public boolean checkCanOpenDoors() {
		return doorCooldown > 0;
	}

	/**
	 * Get the actual door value, including any overridden value, for example when debugging a train from the Resource Pack Creator.
	 */
	public int getAdjustedDoorMultiplier(VehicleExtraData vehicleExtraData) {
		return overrideDoorMultiplier != 0 ? overrideDoorMultiplier : vehicleExtraData.getDoorMultiplier();
	}

	/**
	 * Override the door value, for example when debugging a train from the Resource Pack Creator. This must be called every tick.
	 *
	 * @param overrideDoorMultiplier {@code 1} for open and {@code -1} for close
	 */
	public void overrideDoorMultiplier(int overrideDoorMultiplier) {
		this.overrideDoorMultiplier = overrideDoorMultiplier;
	}

	public boolean canAnnounce(double oldRailProgress, double railProgress) {
		return oldRailProgress < nextAnnouncementRailProgress && railProgress >= nextAnnouncementRailProgress;
	}

	public void playMotorSound(VehicleResource vehicleResource, int carNumber, BlockPos bogiePosition, float speed, float speedChange, float acceleration, boolean isOnRoute) {
		getVehicleSoundBase(vehicleResource, carNumber).playMotorSound(bogiePosition, speed, speedChange, acceleration, isOnRoute);
	}

	/**
	 * 把这一帧的**电机出力**（N，牵引为正、电阻制动为负）喂给该节车的音效。
	 *
	 * <p>单独一个方法而不是塞进 {@code playMotorSound} 的参数表：那是 MTR 的签名，
	 * 两个加载器（fabric/forge）与所有现存音效类都挂在上面，加参数会牵动一大片；
	 * 而 {@link org.mtr.mod.sound.VehicleSoundBase#feedDemand} 默认空实现，只有 MMTR 牵引音用得上。</p>
	 */
	public void feedDemand(VehicleResource vehicleResource, int carNumber, double motorForceN) {
		getVehicleSoundBase(vehicleResource, carNumber).feedDemand(motorForceN);
	}

	/**
	 * 把"听者是不是就坐在这节车的操纵位上"递给该节车的音效。
	 *
	 * <p>与 {@link #feedDemand} 同一个理由走单独的方法：{@code playMotorSound} 是 MTR 的签名，
	 * 而 {@link org.mtr.mod.sound.VehicleSoundBase#setListenerAtControls} 默认空实现，
	 * 只有 MMTR 牵引音用得上。它决定那一节车的所有层**要不要忽略距离衰减**。</p>
	 */
	public void setListenerAtControls(VehicleResource vehicleResource, int carNumber, boolean atControls) {
		getVehicleSoundBase(vehicleResource, carNumber).setListenerAtControls(atControls);
	}

	public void playDoorSound(VehicleResource vehicleResource, int carNumber, BlockPos vehiclePosition) {
		getVehicleSoundBase(vehicleResource, carNumber).playDoorSound(vehiclePosition, doorValue, oldDoorValue);
	}

	private VehicleSoundBase getVehicleSoundBase(VehicleResource vehicleResource, int carNumber) {
		return getElement(vehicleSoundBaseList, carNumber, vehicleResource.createVehicleSoundBase);
	}

	public void dispose() {
		for (VehicleSoundBase sounds : vehicleSoundBaseList) {
			sounds.dispose();
		}
	}

	private static <T> T getElement(ObjectArrayList<T> list, int index, Supplier<T> supplier) {
		while (list.size() <= index) {
			list.add(supplier.get());
		}
		return list.get(index);
	}

	private static class DoorMovementInterpolation {

		private boolean opening;
		private final Interpolation interpolation;

		private DoorMovementInterpolation() {
			this.interpolation = new Interpolation(500);
		}

		private float setAndGet(double value, boolean opening) {
			interpolation.setValue(value, this.opening != opening);
			this.opening = opening;
			return (float) interpolation.getValue();
		}
	}
}
