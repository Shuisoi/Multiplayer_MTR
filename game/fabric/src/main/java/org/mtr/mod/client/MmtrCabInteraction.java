package org.mtr.mod.client;

import org.mtr.core.data.Vehicle;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.packet.PacketMmtrCabOp;

/**
 * B7.6c: the in-game "press F to enter the cab" interaction (design §3.6.4).
 *
 * <p>Pressing the cab key while standing within reach of a consist either takes the nearest cab or
 * leaves the one the crew already holds. The cab is decided by which end of the train the player
 * stands at — car 0's end is cab 1 (A), the last car's end is cab 2 (B) — using the same car
 * positions the renderer already computes, so no extra data is needed on the client. The engine
 * validates the physical gates; this class only picks the target and sends the request.</p>
 *
 * <p>The view point (snapping the player's camera into the cab) is the next slice: MTR's riding
 * entity already carries a car-local position, so entering a cab is a matter of setting it.</p>
 */
public final class MmtrCabInteraction {

	private MmtrCabInteraction() {
	}

	/** How close (blocks) the player must stand to a car to reach its cab. */
	private static final double REACH_M = 6.0;
	/** Key edge detection: the mapping key only exposes isPressed(). */
	private static boolean lastPressed = false;

	public static void tick() {
		final boolean pressed = KeyBindings.MMTR_CAB_INTERACT.isPressed();
		if (pressed && !lastPressed) {
			handle();
		}
		lastPressed = pressed;
	}

	private static void handle() {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return;
		}
		final double playerX = player.getX();
		final double playerY = player.getY();
		final double playerZ = player.getZ();

		VehicleExtension nearest = null;
		double nearestDistanceSquared = REACH_M * REACH_M;
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			final double distanceSquared = nearestCarDistanceSquared(vehicle, playerX, playerY, playerZ);
			if (distanceSquared >= 0 && distanceSquared < nearestDistanceSquared) {
				nearestDistanceSquared = distanceSquared;
				nearest = vehicle;
			}
		}
		if (nearest == null) {
			player.sendMessage(new Text(TextHelper.literal("附近没有可进入的列车 / no train within reach").data), true);
			return;
		}
		if (VehicleRidingMovement.isRiding(nearest.getId())) {
			InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(nearest.getId(), PacketMmtrCabOp.Op.LEAVE, ""));
			player.sendMessage(new Text(TextHelper.literal("拔出钥匙，离开驾驶室 / key out").data), true);
		} else {
			final String cab = nearestEndCab(nearest, playerX, playerY, playerZ);
			InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(nearest.getId(), PacketMmtrCabOp.Op.ENTER, cab));
			player.sendMessage(new Text(TextHelper.literal("CAB_A".equals(cab) ? "进入 1 号驾驶室（A 端）/ cab 1" : "进入 2 号驾驶室（B 端）/ cab 2").data), true);
		}
	}

	/** Squared distance to the nearest car centre of the consist, or -1 when it has no cars. */
	private static double nearestCarDistanceSquared(Vehicle vehicle, double x, double y, double z) {
		double nearest = -1;
		for (final var car : vehicle.getVehicleCarsAndPositions()) {
			for (final Vehicle.BogiePosition bogie : car.right()) {
				final double distanceSquared = squaredDistance(bogie.positionAndTiltAngle1().position(), x, y, z);
				if (nearest < 0 || distanceSquared < nearest) {
					nearest = distanceSquared;
				}
			}
		}
		return nearest;
	}

	/**
	 * Which end cab the player stands at: the closer of the first car (A end, cab 1) and the last
	 * car (B end, cab 2). A consist of one car only has the two ends of that car.
	 */
	private static String nearestEndCab(Vehicle vehicle, double x, double y, double z) {
		final var cars = vehicle.getVehicleCarsAndPositions();
		if (cars.isEmpty()) {
			return "CAB_A";
		}
		double firstEndDistanceSquared = Double.MAX_VALUE;
		for (final Vehicle.BogiePosition bogie : cars.get(0).right()) {
			firstEndDistanceSquared = Math.min(firstEndDistanceSquared, squaredDistance(bogie.positionAndTiltAngle1().position(), x, y, z));
		}
		double lastEndDistanceSquared = Double.MAX_VALUE;
		for (final Vehicle.BogiePosition bogie : cars.get(cars.size() - 1).right()) {
			lastEndDistanceSquared = Math.min(lastEndDistanceSquared, squaredDistance(bogie.positionAndTiltAngle1().position(), x, y, z));
		}
		return firstEndDistanceSquared <= lastEndDistanceSquared ? "CAB_A" : "CAB_B";
	}

	private static double squaredDistance(org.mtr.core.tool.Vector position, double x, double y, double z) {
		final double dx = position.x() - x;
		final double dy = position.y() - y;
		final double dz = position.z() - z;
		return dx * dx + dy * dy + dz * dz;
	}
}
