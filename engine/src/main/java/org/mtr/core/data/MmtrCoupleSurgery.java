package org.mtr.core.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.consist.MmtrConsistBody;
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

	/**
	 * Outcome of a coupling/uncoupling attempt. {@code vehicle} is the surviving (or head) train;
	 * {@code other} is the uncoupled tail, {@code null} for a coupling.
	 */
	public record Result(boolean ok, String reason, @Nullable Vehicle vehicle, @Nullable Vehicle other, int mergedCarCount) {

		static Result fail(String reason) {
			return new Result(false, reason, null, null, 0);
		}

		static Result success(Vehicle merged) {
			return new Result(true, "", merged, null, merged.vehicleExtraData.immutableVehicleCars.size());
		}

		static Result split(Vehicle head, Vehicle tail) {
			return new Result(true, "", head, tail, head.vehicleExtraData.immutableVehicleCars.size());
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

		// Which train physically leads: compare the leading faces along the direction of travel (C5 -
		// a consist body's offsetM() is its leading face, and its body extends BEHIND that face, which
		// is the opposite side from a legacy walker's offset arithmetic).
		final double[] initiatorFrame = initiator.mmtrTravelFrame();
		final double[] targetFrame = target.mmtrTravelFrame();
		if (initiatorFrame == null || targetFrame == null) {
			return Result.fail("无法确定两车的走行坐标");
		}
		final boolean initiatorLeads = initiatorFrame[0] > targetFrame[0];
		final Vehicle leading = initiatorLeads ? initiator : target;
		final Vehicle trailing = initiatorLeads ? target : initiator;
		final double[] leadingFrame = initiatorLeads ? initiatorFrame : targetFrame;
		final double[] trailingFrame = initiatorLeads ? targetFrame : initiatorFrame;
		final double gap = leadingFrame[0] - leadingFrame[1] - trailingFrame[0];
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
		// C4b: the joint is a coupler seam - the only place this formation may be cut again.
		mergedCars.get(leadingCars.size() - 1).setMmtrCouplerAfter(true);
		if (mergedCars.size() > leading.getTransportMode().maxLength) {
			return Result.fail("合并后 " + mergedCars.size() + " 节超过该运输方式的节数上限 " + leading.getTransportMode().maxLength);
		}
		final Siding leadingSiding = sidingOf(simulator, leading);
		final Siding trailingSiding = sidingOf(simulator, trailing);
		if (leadingSiding == null || trailingSiding == null) {
			return Result.fail("找不到车辆所属股道");
		}
		// 实机反馈 (2026-09-09): the old "merged length must fit the siding rail" gate refused a perfectly
		// legal coupling - a locomotive at the siding mouth + the rake inside it are already longer than
		// the siding (the locomotive stands across the mouth on the lead rail), so 16 m + 32 m > 43 m was
		// rejected even though the merged formation stands exactly where the two trains already are.
		// A consist body is not confined to one rail: the real check is whether the merged body can be
		// placed on the rails (placeMergedConsistWalker below), which fails cleanly when there is not
		// enough track behind the seam.

		// Rebuild the leading vehicle's data with the merged formation and the transferred crew.
		final JsonObject mergedJson = Utilities.getJsonObjectFromData(leading.vehicleExtraData);
		final JsonArray carsJson = new JsonArray();
		mergedCars.forEach(car -> carsJson.add(Utilities.getJsonObjectFromData(car)));
		mergedJson.add("vehicleCars", carsJson);
		mergedJson.add("ridingEntities", mergedRidingEntitiesJson(leading, trailing, leadingCars.size()));
		patchGeometry(mergedJson, mergedCars);
		final VehicleExtraData mergedData = new VehicleExtraData(new JsonReader(mergedJson));

		final MmtrMotionPosition walker = leading.getMmtrMotionWalker();
		final TransportMode transportMode = leading.getTransportMode();
		// C5b: where the crew's key ends up in the merged formation. The trailing train's cab faces
		// the seam (its leading end faces the train it coupled onto), so its arc becomes
		// leadingLength + its own cab arc; a key at a named end keeps its label (and therefore the
		// direction of travel), an interior cab keeps its arc.
		final MmtrConsistWalker trailingWalker = trailing.getMmtrConsistWalker();
		final boolean trailingHasCrewKey = trailingWalker != null && trailingWalker.cabs().isCrewKey();
		final double trailingCabArc = trailingWalker == null ? 0 : cabArcOf(trailingWalker);
		final boolean trailingCabFacesA = trailingWalker != null && trailingWalker.cabs().activeCab() == MmtrCabState.Cab.CAB_A;
		final java.util.UUID trailingCrewUuid = trailingWalker == null ? null : trailingWalker.cabs().crewUuid();
		// C5: a consist body must be rebuilt too - its car lengths and coupler seams are part of the
		// body, and the spine has to cover the trailing train's rails. Do this BEFORE any mutation so
		// a formation that cannot be placed leaves the world untouched.
		MmtrConsistWalker mergedConsistWalker = null;
		if (walker instanceof final MmtrConsistWalker consistWalker) {
			mergedConsistWalker = placeMergedConsistWalker(simulator, consistWalker, mergedCars);
			if (mergedConsistWalker == null) {
				return Result.fail("合并后的编组体无法放在当前轨道上（岔道未定或长度不足）");
			}
		}

		leadingSiding.unregisterVehicle(leading);
		if (trailingSiding != leadingSiding) {
			trailingSiding.unregisterVehicle(trailing);
		} else {
			leadingSiding.unregisterVehicle(trailing);
		}

		final Vehicle merged = new Vehicle(mergedData, leadingSiding, new JsonReader(Utilities.getJsonObjectFromData(leading)), simulator);
		if (mergedConsistWalker != null) {
			merged.engageMmtrConsistMotion(mergedConsistWalker, MmtrCabState.Cab.NONE);
			mergedConsistWalker.removeKey();
			if (trailingHasCrewKey) {
				// C5b: the crew keeps its key - it is now in an interior cab (the locomotive's cab
				// inside the merged formation), which the state machine expresses with an arc position.
				// The joint's arc comes from the merged body itself (MTR's total-length convention adds
				// the coupling paddings at the seam, so summing the two halves would be off by one).
				final double jointArc = jointSeamArcM(mergedConsistWalker.body(), leadingCars.size() - 1);
				mergedConsistWalker.cabs().insertKeyAtArc(jointArc + trailingCabArc, trailingCabFacesA, true, true, trailingCrewUuid);
				System.out.println("[MMTR-COUP] 连挂后钥匙留在机车驾驶室（编组内 " + Math.round((jointArc + trailingCabArc) * 10.0) / 10.0 + " m 处，朝向 "
						+ (trailingCabFacesA ? "A" : "B") + " 端）");
			}
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
	private static JsonArray mergedRidingEntitiesJson(Vehicle leading, Vehicle trailing, int leadingCarCount) {		final JsonArray entities = new JsonArray();
		leading.vehicleExtraData.iterateRidingEntities(entity -> entities.add(Utilities.getJsonObjectFromData(entity)));
		trailing.vehicleExtraData.iterateRidingEntities(entity -> {
			final JsonObject json = Utilities.getJsonObjectFromData(entity);
			json.addProperty("ridingCar", entity.getRidingCar() + leadingCarCount);
			entities.add(json);
		});
		return entities;
	}

	/**
	 * C5: build the consist body of the merged train. The A end stays exactly where it was (the body
	 * only grows toward the B end), and the spine is re-placed from that A end so it covers the
	 * trailing train's rails as well.
	 */
	private static @Nullable MmtrConsistWalker placeMergedConsistWalker(Simulator simulator, MmtrConsistWalker leadingWalker, ObjectArrayList<VehicleCar> mergedCars) {
		final MmtrConsistBody body = leadingWalker.body();
		final MmtrConsistBody.SpineLeg aEndLeg = body.legAtArcM(body.aEndArcM());
		if (aEndLeg == null) {
			return null;
		}
		final Rail rail = simulator.railIdMap.get(aEndLeg.railHex());
		if (rail == null) {
			return null;
		}
		final double[] carLengthsM = new double[mergedCars.size()];
		final boolean[] couplerAfter = new boolean[mergedCars.size()];
		for (int i = 0; i < carLengthsM.length; i++) {
			carLengthsM[i] = mergedCars.get(i).getTotalLength(i == 0, i == carLengthsM.length - 1);
			couplerAfter[i] = mergedCars.get(i).getMmtrCouplerAfter();
		}
		return MmtrConsistWalker.place(
				simulator,
				simulator.mmtrPointBranches,
				rail,
				aEndLeg.entryNode(),
				body.legOffsetM(body.aEndArcM()),
				carLengthsM,
				null,
				MmtrConsistBody.seamArcMsFrom(body.aEndArcM(), carLengthsM, couplerAfter),
				MmtrConsistBody.seamCarIndexesFrom(carLengthsM, couplerAfter)
		);
	}

	/** C5b: the manned cab's arc from the formation's A end (0 / body length for the named ends). */
	private static double cabArcOf(MmtrConsistWalker walker) {
		final MmtrCabState cabs = walker.cabs();
		if (!Double.isNaN(cabs.cabArcM())) {
			return cabs.cabArcM();
		}
		return cabs.activeCab() == MmtrCabState.Cab.CAB_B ? walker.body().lengthM() : 0;
	}

	/** C5b: arc of the seam that sits after car {@code carIndex} in a body, or the body length. */
	private static double jointSeamArcM(MmtrConsistBody body, int carIndex) {
		for (int i = 0; i < body.seamCount(); i++) {
			if (body.carIndexAfterSeam(i) == carIndex) {
				return body.seamArcM(i);
			}
		}
		return body.aEndArcM() + body.lengthM();
	}

	private static @Nullable Siding sidingOf(Simulator simulator, Vehicle vehicle) {		final Siding[] found = {null};
		simulator.sidings.forEach(siding -> {
			if (siding.getVehicleById(vehicle.getId()) != null) {
				found[0] = siding;
			}
		});
		return found[0];
	}

	/**
	 * C4b: cut a formation after car {@code cutAfterCarIndex} (0-based, so the tail starts at the next
	 * car). The head half keeps the train's position and identity; the tail half becomes a new vehicle
	 * standing on the same rail behind the cut, with its own rolling-stock entry. Both halves must be
	 * stopped (U5) and the cut must hit a coupler (U6) - a fixed unit has none.
	 *
	 * <p>C6: the parameter is the CAR index, not a seam index — "cut after car k" is what the operator
	 * aims at (a car in the formation) and what a job author writes; the coupler gate below then maps
	 * it onto the seam, refusing a boundary that has no coupler. Earlier code called it
	 * {@code seamIndex}, which read as "the k-th seam" and did not match the arithmetic.</p>
	 */
	public static Result uncouple(Simulator simulator, long vehicleId, int cutAfterCarIndex) {
		final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			return Result.fail("找不到车辆 " + vehicleId);
		}
		final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		if (walker == null) {
			return Result.fail("解挂要求列车运行在 Motion Core 模式");
		}
		if (vehicle.getSpeed() > 1e-9) {
			return Result.fail("列车必须停稳才能解挂");
		}
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>(vehicle.vehicleExtraData.immutableVehicleCars);
		if (cutAfterCarIndex < 0 || cutAfterCarIndex >= cars.size() - 1) {
			return Result.fail("切分点必须两侧都留至少一节车厢（0.." + (cars.size() - 2) + "）");
		}
		if (!cars.get(cutAfterCarIndex).getMmtrCouplerAfter()) {
			return Result.fail("第 " + cutAfterCarIndex + " 节之后没有车钩（切分只认接缝，EMU 内部与机车单车无接缝可切）");
		}
		final ObjectArrayList<VehicleCar> headCars = new ObjectArrayList<>(cars.subList(0, cutAfterCarIndex + 1));
		final ObjectArrayList<VehicleCar> tailCars = new ObjectArrayList<>(cars.subList(cutAfterCarIndex + 1, cars.size()));
		headCars.get(headCars.size() - 1).setMmtrCouplerAfter(false);
		final Siding siding = sidingOf(simulator, vehicle);
		if (siding == null) {
			return Result.fail("找不到车辆所属股道");
		}

		// Air: each half keeps the pipe state it had (the two halves are no longer connected).
		final String airState = vehicle.mmtrAirStateSnapshot();
		final String[] airUnits = airState.isEmpty() ? new String[0] : airState.split(";");

		final JsonObject headJson = Utilities.getJsonObjectFromData(vehicle.vehicleExtraData);
		headJson.add("vehicleCars", carsJson(headCars));
		headJson.add("ridingEntities", ridingEntitiesJson(vehicle, cutAfterCarIndex, false));
		patchGeometry(headJson, headCars);
		final VehicleExtraData headData = new VehicleExtraData(new JsonReader(headJson));

		final JsonObject tailJson = Utilities.getJsonObjectFromData(vehicle.vehicleExtraData);
		tailJson.add("vehicleCars", carsJson(tailCars));
		tailJson.add("ridingEntities", ridingEntitiesJson(vehicle, cutAfterCarIndex, true));
		patchGeometry(tailJson, tailCars);
		final VehicleExtraData tailData = new VehicleExtraData(new JsonReader(tailJson));

		// C5b: the walkers of both halves. A consist body is cut at a seam ARC (its own geometry); a
		// legacy motion walker at a rail offset derived from the head half's length.
		final MmtrConsistWalker consistWalker = vehicle.getMmtrConsistWalker();
		MmtrConsistWalker headConsistWalker = null;
		MmtrConsistWalker tailConsistWalker = null;
		MmtrMotionWalker tailLegacyWalker = null;
		MmtrMotionWalker legacyWalker = null;
		double seamArc = 0;
		double cutOffset = 0;
		if (consistWalker != null) {
			final MmtrConsistBody body = consistWalker.body();
			int bodySeam = -1;
			for (int i = 0; i < body.seamCount(); i++) {
				if (body.carIndexAfterSeam(i) == cutAfterCarIndex) {
					bodySeam = i;
					break;
				}
			}
			if (bodySeam < 0) {
				return Result.fail("第 " + cutAfterCarIndex + " 节不是编组体的接缝");
			}
			seamArc = body.seamArcM(bodySeam);
			headConsistWalker = placeConsistHalf(simulator, body, body.aEndArcM(), headCars);
			tailConsistWalker = placeConsistHalf(simulator, body, seamArc, tailCars);
			if (headConsistWalker == null || tailConsistWalker == null) {
				return Result.fail("切分后的编组体无法放在当前轨道上（跨轨切分或岔道未定）");
			}
		} else if (walker instanceof final MmtrMotionWalker motionWalker) {
			legacyWalker = motionWalker;
			final Rail rail = walker.currentRail();
			final Position entry = walker.enteredFromPosition();
			if (rail == null || entry == null) {
				return Result.fail("找不到列车所在轨道");
			}
			cutOffset = walker.offsetM() - Siding.getTotalVehicleLength(headCars);
			if (cutOffset <= 0.05 || cutOffset >= rail.railMath.getLength() - 0.05) {
				return Result.fail("切分点落在轨外（跨轨切分待后续），cutOffset=" + Math.round(cutOffset * 100.0) / 100.0 + " m");
			}
			tailLegacyWalker = MmtrMotionWalker.startAtOffset(simulator, rail, entry, cutOffset, simulator.mmtrPointBranches, null);
		} else {
			return Result.fail("未知的走行器类型，未做手术");
		}

		siding.unregisterVehicle(vehicle);
		// The head keeps the original vehicle's identity (clients keep their mirror and just see the
		// formation shrink); the tail is a genuinely new vehicle with a fresh id.
		final Vehicle head = new Vehicle(headData, siding, new JsonReader(Utilities.getJsonObjectFromData(vehicle)), simulator);
		if (headConsistWalker != null) {
			head.engageMmtrConsistMotion(headConsistWalker, MmtrCabState.Cab.NONE);
			headConsistWalker.removeKey();
		} else {
			head.engageMmtrMotion(legacyWalker);
		}
		siding.adoptVehicle(head);

		final JsonObject tailVehicleJson = new JsonObject();
		tailVehicleJson.addProperty("id", new java.util.Random().nextLong());
		final Vehicle tail = new Vehicle(tailData, siding, new JsonReader(tailVehicleJson), simulator);
		if (tailConsistWalker != null) {
			tail.engageMmtrConsistMotion(tailConsistWalker, MmtrCabState.Cab.NONE);
			tailConsistWalker.removeKey();
		} else {
			tail.engageMmtrMotion(tailLegacyWalker);
		}
		siding.adoptVehicle(tail);

		// C5b: the crew's key stays with the half that contains the cab.
		if (consistWalker != null && consistWalker.cabs().isCrewKey()) {
			final double cabArc = cabArcOf(consistWalker);
			final boolean towardA = consistWalker.cabs().activeCab() == MmtrCabState.Cab.CAB_A;
			final java.util.UUID crew = consistWalker.cabs().crewUuid();
			// The cab arc is at or behind the seam: the driver's cab belongs to the tail half (a cab
			// sitting exactly on the joint is the tail half's leading cab, which is the common case -
			// the locomotive's cab is right at the end that coupled).
			if (cabArc >= seamArc - 1e-6) {
				tailConsistWalker.cabs().insertKeyAtArc(cabArc - seamArc, towardA, true, true, crew);
				System.out.println("[MMTR-COUP] 解挂后钥匙留在后段（编组内 " + Math.round((cabArc - seamArc) * 10.0) / 10.0 + " m 处）");
			} else {
				headConsistWalker.cabs().insertKeyAtArc(cabArc, towardA, true, true, crew);
			}
		}

		if (airUnits.length == cars.size()) {
			head.mmtrApplyAirStateString(String.join(";", java.util.Arrays.copyOfRange(airUnits, 0, cutAfterCarIndex + 1)));
			tail.mmtrApplyAirStateString(String.join(";", java.util.Arrays.copyOfRange(airUnits, cutAfterCarIndex + 1, airUnits.length)));
		}

		System.out.println("[MMTR-COUP] 解挂完成: " + vehicleId + " 在第 " + (cutAfterCarIndex + 1) + " 节后切分 -> 前段 " + head.getId()
				+ "（" + headCars.size() + " 节）+ 后段 " + tail.getId() + "（" + tailCars.size() + " 节）");
		return Result.split(head, tail);
	}

	/**
	 * C5b: place one half of a cut consist body. Its A end sits at {@code aEndArcM} in the ORIGINAL
	 * body's spine; the new walker's own spine starts there, so the seam arcs are recomputed in its
	 * space.
	 */
	private static @Nullable MmtrConsistWalker placeConsistHalf(Simulator simulator, MmtrConsistBody body, double aEndArcM, ObjectArrayList<VehicleCar> cars) {
		final MmtrConsistBody.SpineLeg leg = body.legAtArcM(aEndArcM);
		if (leg == null) {
			return null;
		}
		final Rail rail = simulator.railIdMap.get(leg.railHex());
		if (rail == null) {
			return null;
		}
		final double aEndOffsetM = body.legOffsetM(aEndArcM);
		final double[] carLengthsM = new double[cars.size()];
		final boolean[] couplerAfter = new boolean[cars.size()];
		for (int i = 0; i < carLengthsM.length; i++) {
			carLengthsM[i] = cars.get(i).getTotalLength(i == 0, i == carLengthsM.length - 1);
			couplerAfter[i] = cars.get(i).getMmtrCouplerAfter();
		}
		return MmtrConsistWalker.place(
				simulator,
				simulator.mmtrPointBranches,
				rail,
				leg.entryNode(),
				aEndOffsetM,
				carLengthsM,
				null,
				MmtrConsistBody.seamArcMsFrom(aEndOffsetM, carLengthsM, couplerAfter),
				MmtrConsistBody.seamCarIndexesFrom(carLengthsM, couplerAfter)
		);
	}

	private static JsonArray carsJson(ObjectArrayList<VehicleCar> cars) {
		final JsonArray array = new JsonArray();
		cars.forEach(car -> array.add(Utilities.getJsonObjectFromData(car)));
		return array;
	}

	/**
	 * C4/C5: patch the derived geometry of a VED rebuilt from JSON. {@code totalVehicleLength} and
	 * {@code defaultPosition} are final schema fields copied verbatim from the source formation, so a
	 * merged/split train would otherwise keep the old train's length (which the yard rule, the
	 * occupancy spans and {@code getIsOnRoute()} all read).
	 */
	private static void patchGeometry(JsonObject json, ObjectArrayList<VehicleCar> cars) {
		final double totalLengthM = Siding.getTotalVehicleLength(cars);
		json.addProperty("totalVehicleLength", totalLengthM);
		final double railLengthM = json.has("railLength") ? json.get("railLength").getAsDouble() : 0;
		json.addProperty("defaultPosition", (railLengthM + totalLengthM) / 2);
	}

	/** Riding entities of one side of the cut, rebased onto that side's car list. */
	private static JsonArray ridingEntitiesJson(Vehicle vehicle, int cutAfterCarIndex, boolean tailSide) {
		final JsonArray entities = new JsonArray();
		vehicle.vehicleExtraData.iterateRidingEntities(entity -> {
			final boolean inTail = entity.getRidingCar() > cutAfterCarIndex;
			if (inTail != tailSide) {
				return;
			}
			final JsonObject json = Utilities.getJsonObjectFromData(entity);
			json.addProperty("ridingCar", inTail ? entity.getRidingCar() - cutAfterCarIndex - 1 : entity.getRidingCar());
			entities.add(json);
		});
		return entities;
	}
}
