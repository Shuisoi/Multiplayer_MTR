package org.mtr.core.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.segment.MmtrMotionPosition;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;

/**
 * C4: the real coupling surgery (连挂真手术).
 *
 * <p>Two trains standing nose to tail on one rail under a 调车授权 become one train. MTR models a
 * whole train as one {@link Vehicle} with one ordered {@code vehicleCars} list, so the surgery is:
 * the physically leading train survives, the trailing train's cars are appended to its list, and the
 * trailing vehicle is unregistered. The result keeps the leading train's position, walker and
 * identity parameters, so nothing moves and no geometry is recomputed.</p>
 *
 * <p>Gates (design §5.1) are all enforced here, not by the caller: both trains at rest, both on the
 * same rail travelling the same way, closed up to coupler distance, a live authority for the
 * movement, and the merged formation fitting the mode's car cap and the rail. The crew's key does
 * not survive the merge as such: after coupling the merged consist is unmanned (the trailing train's
 * cab is now inside the formation, which the current two-cab model cannot express) and the crew
 * walks to the leading cab - the riding entities themselves are transferred, so nobody falls out.</p>
 *
 * <p>Air: the coupled-on cars' pipe starts empty (see {@link Vehicle#mmtrSeedAirStateAfterCoupling}),
 * which is what makes a freshly coupled rake charge up before it brakes properly.</p>
 */
public final class MmtrCoupleSurgery {

	private MmtrCoupleSurgery() {
	}

	/** How close the two couplers must be for the trains to count as touching (m). */
	public static final double COUPLER_CONTACT_M = 0.75;

	/** Outcome of a coupling attempt. */
	public record Result(boolean ok, String reason, @Nullable Vehicle merged, int mergedCarCount) {

		static Result fail(String reason) {
			return new Result(false, reason, null, 0);
		}

		static Result success(Vehicle merged) {
			return new Result(true, "", merged, merged.vehicleExtraData.immutableVehicleCars.size());
		}
	}

	/**
	 * Couples {@code targetVehicleId} onto {@code initiatorVehicleId}: the initiator is the train that
	 * drove up to the target (it holds the authority). Either train may be the physically leading one.
	 */
	public static Result couple(Simulator simulator, long initiatorVehicleId, long targetVehicleId) {
		if (initiatorVehicleId == targetVehicleId) {
			return Result.fail("不能与自己连挂");
		}
		final Vehicle initiator = simulator.mmtrFindVehicle(initiatorVehicleId);
		final Vehicle target = simulator.mmtrFindVehicle(targetVehicleId);
		if (initiator == null || target == null) {
			return Result.fail("找不到车辆（" + initiatorVehicleId + " / " + targetVehicleId + "）");
		}
		if (initiator.getMmtrMotionWalker() == null || target.getMmtrMotionWalker() == null) {
			return Result.fail("连挂要求两列车都运行在 Motion Core 模式");
		}
		if (initiator.getSpeed() > 1e-9 || target.getSpeed() > 1e-9) {
			return Result.fail("两列车都必须停稳");
		}
		final String initiatorRail = initiator.getMmtrMotionWalker().railHex();
		final String targetRail = target.getMmtrMotionWalker().railHex();
		if (initiatorRail == null || !initiatorRail.equals(targetRail)) {
			return Result.fail("两列车必须在同一条轨上（" + initiatorRail + " / " + targetRail + "）");
		}
		final Position initiatorEntry = initiator.getMmtrMotionWalker().enteredFromPosition();
		final Position targetEntry = target.getMmtrMotionWalker().enteredFromPosition();
		if (initiatorEntry == null || !initiatorEntry.equals(targetEntry)) {
			return Result.fail("两列车必须同向（A↔B 对接）");
		}
		final MmtrShuntAuthority authority = simulator.mmtrShuntAuthorities.active(initiatorVehicleId);
		if (authority == null || !authority.covers(targetRail)) {
			return Result.fail("调车授权未授或已过期");
		}

		// Which train physically leads (larger rail-local head offset along the shared direction).
		final boolean initiatorLeads = initiator.getMmtrMotionWalker().offsetM() > target.getMmtrMotionWalker().offsetM();
		final Vehicle leading = initiatorLeads ? initiator : target;
		final Vehicle trailing = initiatorLeads ? target : initiator;
		final double gap = leading.getMmtrMotionWalker().offsetM() - leading.vehicleExtraData.getTotalVehicleLength() - trailing.getMmtrMotionWalker().offsetM();
		if (gap > COUPLER_CONTACT_M) {
			return Result.fail("两车车钩还差 " + Math.round(gap * 100.0) / 100.0 + " m，未接触");
		}
		if (gap < -0.05) {
			return Result.fail("两车已经重叠 " + Math.round(-gap * 100.0) / 100.0 + " m");
		}

		final ObjectArrayList<VehicleCar> leadingCars = new ObjectArrayList<>(leading.vehicleExtraData.immutableVehicleCars);
		final ObjectArrayList<VehicleCar> trailingCars = new ObjectArrayList<>(trailing.vehicleExtraData.immutableVehicleCars);
		final ObjectArrayList<VehicleCar> mergedCars = new ObjectArrayList<>(leadingCars.size() + trailingCars.size());
		mergedCars.addAll(leadingCars);
		mergedCars.addAll(trailingCars);
		if (mergedCars.size() > leading.getTransportMode().maxLength) {
			return Result.fail("合并后 " + mergedCars.size() + " 节超过该运输方式的节数上限 " + leading.getTransportMode().maxLength);
		}
		final Siding leadingSiding = sidingOf(simulator, leading);
		final Siding trailingSiding = sidingOf(simulator, trailing);
		if (leadingSiding == null || trailingSiding == null) {
			return Result.fail("找不到车辆所属股道");
		}
		if (Siding.getTotalVehicleLength(mergedCars) > leadingSiding.getRailLength() + 1e-6) {
			return Result.fail("合并后长度 " + Math.round(Siding.getTotalVehicleLength(mergedCars) * 10.0) / 10.0 + " m 超过股道长度 "
					+ Math.round(leadingSiding.getRailLength() * 10.0) / 10.0 + " m");
		}

		// Rebuild the leading vehicle's data with the merged formation and the transferred crew.
		final JsonObject mergedJson = Utilities.getJsonObjectFromData(leading.vehicleExtraData);
		final JsonArray carsJson = new JsonArray();
		mergedCars.forEach(car -> carsJson.add(Utilities.getJsonObjectFromData(car)));
		mergedJson.add("vehicleCars", carsJson);
		mergedJson.add("ridingEntities", mergedRidingEntitiesJson(leading, trailing, leadingCars.size()));
		final VehicleExtraData mergedData = new VehicleExtraData(new JsonReader(mergedJson));

		final MmtrMotionPosition walker = leading.getMmtrMotionWalker();
		final TransportMode transportMode = leading.getTransportMode();
		leadingSiding.unregisterVehicle(leading);
		if (trailingSiding != leadingSiding) {
			trailingSiding.unregisterVehicle(trailing);
		} else {
			leadingSiding.unregisterVehicle(trailing);
		}

		final Vehicle merged = new Vehicle(mergedData, leadingSiding, transportMode, simulator);
		if (walker instanceof final MmtrConsistWalker consistWalker) {
			// The walker keeps its own cab state (which end leads); the crew's key from the trailing
			// train does not transfer - after coupling the crew takes the leading cab.
			merged.engageMmtrConsistMotion(consistWalker, MmtrCabState.Cab.NONE);
			consistWalker.removeKey();
		} else if (walker instanceof final MmtrMotionWalker legacyWalker) {
			merged.engageMmtrMotion(legacyWalker);
		} else {
			leadingSiding.adoptVehicle(leading);
			return Result.fail("未知的走行器类型，未做手术");
		}
		leadingSiding.adoptVehicle(merged);
		merged.mmtrSeedAirStateAfterCoupling(leadingCars.size());
		simulator.mmtrShuntAuthorities.revoke(initiatorVehicleId);
		simulator.mmtrShuntAuthorities.revoke(targetVehicleId);

		System.out.println("[MMTR-COUP] 连挂完成: " + leading.getId() + " + " + trailing.getId() + " -> " + merged.getId()
				+ "（" + mergedCars.size() + " 节，" + Math.round(Siding.getTotalVehicleLength(mergedCars) * 10.0) / 10.0 + " m，车钩间隙 "
				+ Math.round(gap * 100.0) / 100.0 + " m）");
		return Result.success(merged);
	}

	/** The crew of both trains rides the merged consist; the trailing train's car indices are rebased. */
	private static JsonArray mergedRidingEntitiesJson(Vehicle leading, Vehicle trailing, int leadingCarCount) {
		final JsonArray entities = new JsonArray();
		leading.vehicleExtraData.iterateRidingEntities(entity -> entities.add(Utilities.getJsonObjectFromData(entity)));
		trailing.vehicleExtraData.iterateRidingEntities(entity -> {
			final JsonObject json = Utilities.getJsonObjectFromData(entity);
			json.addProperty("ridingCar", entity.getRidingCar() + leadingCarCount);
			entities.add(json);
		});
		return entities;
	}

	private static @Nullable Siding sidingOf(Simulator simulator, Vehicle vehicle) {
		final Siding[] found = {null};
		simulator.sidings.forEach(siding -> {
			if (siding.getVehicleById(vehicle.getId()) != null) {
				found[0] = siding;
			}
		});
		return found[0];
	}
}
