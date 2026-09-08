package org.mtr.core.mmtr;

import org.mtr.core.data.Vehicle;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

/**
 * Decoupled vehicle motion state for client/map communication (SimRail / Stepford style):
 * the vehicle is identified independently of any spawn route and positioned on the rail network
 * by (segment id + offset) with speed/doors/mission - clients interpolate between snapshots.
 * No baked route is exposed; the server remains authoritative.
 */
public final class MmtrMotionSnapshot implements SerializedDataBase {

	public String vehicleId = "";
	public String sidingId = "";
	public String sidingName = "";
	public int cars;
	public double headX;
	public double headZ;
	public double segStartX;
	public double segStartZ;
	public double segEndX;
	public double segEndZ;
	public boolean segmentReversed;
	public double segmentOffsetM;
	public double segmentLengthM;
	public double speedKmh;
	public boolean moving;
	public boolean onRoute;
	public boolean doorsOpen;
	public String platformId = "";
	public String mission = "";
	/**
	 * B4 (consist body): the two ends of the physical train in world space. A client must place the
	 * cars along rear -&gt; front and never rotate the model: 换端 only relabels which end is the
	 * front, it does not move either end.
	 */
	public double frontX;
	public double frontZ;
	public double rearX;
	public double rearZ;
	/** Which cab the driver is in ("CAB_A" / "CAB_B" / "NONE"); decides the direction of travel. */
	public String activeCab = "NONE";
	/** Whether a key is inserted at all (an unmanned consist cannot move). */
	public boolean cabManned;

	public MmtrMotionSnapshot() {
	}

	public MmtrMotionSnapshot(ReaderBase readerBase) {
		updateData(readerBase);
	}

	public static MmtrMotionSnapshot from(org.mtr.core.data.Siding siding, Vehicle vehicle) {
		// MMTR (L3): a live Motion-Core run vehicle reports its decoupled (segment, offset) state
		// straight from the walker — no baked path is involved.
		final org.mtr.core.mmtr.segment.MmtrMotionWalker walker = vehicle.getMmtrMotionWalker();
		if (walker != null) {
			final MmtrMotionSnapshot out = ofWalker(walker);
			out.vehicleId = String.valueOf(vehicle.getId());
			out.sidingId = String.valueOf(siding == null ? 0 : siding.getId());
			out.sidingName = siding == null ? "" : siding.getName();
			out.cars = vehicle.vehicleExtraData.immutableVehicleCars.size();
			out.speedKmh = Math.round(vehicle.getSpeed() * 3600000.0) / 1000.0;
			out.moving = vehicle.getSpeed() > 0;
			out.onRoute = vehicle.getIsOnRoute();
			out.doorsOpen = vehicle.vehicleExtraData.getDoorMultiplier() > 0;
			out.platformId = String.valueOf(vehicle.vehicleExtraData.getThisPlatformId());
			final org.mtr.core.mmtr.MmtrMission mission = vehicle.getMmtrMission();
			if (mission != null) {
				out.mission = mission.getKind().name() + "/" + mission.getState().name() + "/" + mission.getExecutor().name();
			}
			return out;
		}
		final MmtrMotionSnapshot out = new MmtrMotionSnapshot();
		out.vehicleId = String.valueOf(vehicle.getId());
		out.sidingId = String.valueOf(siding.getId());
		out.sidingName = siding.getName();
		out.cars = vehicle.vehicleExtraData.immutableVehicleCars.size();
		final Vehicle.PositionAndTiltAngle head = vehicle.getHeadPositionAndTiltAngle();
		if (head != null) {
			out.headX = head.position().x();
			out.headZ = head.position().z();
		}
		final it.unimi.dsi.fastutil.objects.ObjectImmutableList<org.mtr.core.data.PathData> path = vehicle.vehicleExtraData.immutablePath;
		if (!path.isEmpty()) {
			final int index = org.mtr.core.tool.Utilities.getIndexFromConditionalList(path, vehicle.getRailProgress());
			final org.mtr.core.data.PathData segment = path.get(Math.max(0, Math.min(index, path.size() - 1)));
			final org.mtr.core.data.Position start = segment.getOrderedPosition1();
			final org.mtr.core.data.Position end = segment.getOrderedPosition2();
			out.segStartX = start.getX();
			out.segStartZ = start.getZ();
			out.segEndX = end.getX();
			out.segEndZ = end.getZ();
			out.segmentReversed = segment.reversePositions;
			out.segmentLengthM = Math.max(0, segment.getEndDistance() - segment.getStartDistance());
			out.segmentOffsetM = Math.max(0, vehicle.getRailProgress() - segment.getStartDistance());
		}
		out.segmentOffsetM = Math.round(out.segmentOffsetM * 100.0) / 100.0;
		out.speedKmh = Math.round(vehicle.getSpeed() * 3600000.0) / 1000.0;
		out.moving = vehicle.isMoving();
		out.onRoute = vehicle.getIsOnRoute();
		out.doorsOpen = vehicle.vehicleExtraData.getDoorMultiplier() > 0;
		out.platformId = String.valueOf(vehicle.vehicleExtraData.getThisPlatformId());
		final org.mtr.core.mmtr.MmtrMission mission = vehicle.getMmtrMission();
		if (mission != null) {
			out.mission = mission.getKind().name() + "/" + mission.getState().name() + "/" + mission.getExecutor().name();
		}
		return out;
	}

	/**
	 * Builds a decoupled (segment + offset) motion snapshot directly from a Motion Core walker — i.e.
	 * from a consist being <em>driven by Motion Core</em>, with no Vehicle / no baked MTR path in the
	 * loop. The segment endpoints come from the walker's current rail geometry; clients interpolate on
	 * the same representation the engine already feeds the map/ops UI. This is the slice-4 seam that
	 * lets a Motion-Core-driven consist be rendered/reported without any MTR VehicleExtraData path.
	 */
	public static MmtrMotionSnapshot ofWalker(org.mtr.core.mmtr.segment.MmtrMotionWalker walker) {
		final MmtrMotionSnapshot out = new MmtrMotionSnapshot();
		final org.mtr.core.data.Position start = walker.enteredFromPosition();
		final org.mtr.core.data.Position end = walker.aheadNode();
		if (start != null) {
			out.segStartX = start.getX();
			out.segStartZ = start.getZ();
		}
		if (end != null) {
			out.segEndX = end.getX();
			out.segEndZ = end.getZ();
		}
		out.segmentReversed = false;
		out.segmentLengthM = walker.currentRailLengthM();
		out.segmentOffsetM = Math.max(0, walker.offsetM());
		out.moving = !walker.haltedAtAuthority() && !walker.atTarget() && !walker.endOfLine();
		// World head position interpolated along the current segment at the current offset
		// (straight-segment projection between the two rail endpoints; sufficient for render/map).
		final double len = out.segmentLengthM > 0 ? out.segmentLengthM : 1;
		final double frac = Math.min(1, out.segmentOffsetM / len);
		if (start != null && end != null) {
			out.headX = start.getX() + (end.getX() - start.getX()) * frac;
			out.headZ = start.getZ() + (end.getZ() - start.getZ()) * frac;
		}
		out.speedKmh = 0;
		out.cars = 0;
		return out;
	}

	/**
	 * B4: build the decoupled motion snapshot from the consist-body walker. Unlike
	 * {@link #ofWalker(MmtrMotionWalker)} — which reports the single point that made 换端 look like a
	 * 180° turn — this carries both physical ends plus the manned cab, and the segment field is the
	 * body's own segment with a truthful {@code segmentReversed}.
	 */
	public static MmtrMotionSnapshot ofConsistWalker(org.mtr.core.mmtr.consist.MmtrConsistWalker walker) {
		final MmtrMotionSnapshot out = new MmtrMotionSnapshot();
		out.activeCab = walker.cabs().activeCab().name();
		out.cabManned = walker.cabs().isManned();
		out.moving = walker.cabs().isManned() && !walker.haltedAtAuthority() && !walker.endOfLine();
		out.cars = walker.body().carCount();
		out.segmentLengthM = walker.body().spineLengthM();
		final org.mtr.core.mmtr.consist.MmtrConsistBody.SpineLeg frontLeg = walker.spineLegAtArcM(walker.frontArcM());
		if (frontLeg != null) {
			out.segStartX = frontLeg.entryNode().getX();
			out.segStartZ = frontLeg.entryNode().getZ();
			out.segEndX = frontLeg.exitNode().getX();
			out.segEndZ = frontLeg.exitNode().getZ();
			out.segmentReversed = frontLeg.entryNode().compareTo(frontLeg.exitNode()) > 0;
			out.segmentOffsetM = Math.max(0, walker.offsetAtArcM(walker.frontArcM()));
		}
		worldAt(walker, walker.frontArcM(), true, out);
		worldAt(walker, walker.rearArcM(), false, out);
		return out;
	}

	private static void worldAt(org.mtr.core.mmtr.consist.MmtrConsistWalker walker, double arcM, boolean front, MmtrMotionSnapshot out) {
		final org.mtr.core.data.Rail rail = walker.railAtArcM(arcM);
		final org.mtr.core.mmtr.consist.MmtrConsistBody.SpineLeg leg = walker.spineLegAtArcM(arcM);
		if (rail == null || leg == null) {
			return;
		}
		final boolean reverse = leg.entryNode().compareTo(leg.exitNode()) > 0;
		final org.mtr.core.tool.Vector vector = rail.railMath.getPosition(walker.offsetAtArcM(arcM), reverse);
		if (front) {
			out.frontX = vector.x();
			out.frontZ = vector.z();
		} else {
			out.rearX = vector.x();
			out.rearZ = vector.z();
		}
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		vehicleId = readerBase.getString("vehicleId", "");
		sidingId = readerBase.getString("sidingId", "");
		sidingName = readerBase.getString("sidingName", "");
		cars = readerBase.getInt("cars", 0);
		headX = readerBase.getDouble("headX", 0);
		headZ = readerBase.getDouble("headZ", 0);
		segStartX = readerBase.getDouble("segStartX", 0);
		segStartZ = readerBase.getDouble("segStartZ", 0);
		segEndX = readerBase.getDouble("segEndX", 0);
		segEndZ = readerBase.getDouble("segEndZ", 0);
		segmentReversed = readerBase.getBoolean("segmentReversed", false);
		segmentOffsetM = readerBase.getDouble("segmentOffsetM", 0);
		segmentLengthM = readerBase.getDouble("segmentLengthM", 0);
		speedKmh = readerBase.getDouble("speedKmh", 0);
		moving = readerBase.getBoolean("moving", false);
		onRoute = readerBase.getBoolean("onRoute", false);
		doorsOpen = readerBase.getBoolean("doorsOpen", false);
		platformId = readerBase.getString("platformId", "");
		mission = readerBase.getString("mission", "");
		frontX = readerBase.getDouble("frontX", 0);
		frontZ = readerBase.getDouble("frontZ", 0);
		rearX = readerBase.getDouble("rearX", 0);
		rearZ = readerBase.getDouble("rearZ", 0);
		activeCab = readerBase.getString("activeCab", "NONE");
		cabManned = readerBase.getBoolean("cabManned", false);
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeString("vehicleId", vehicleId);
		writerBase.writeString("sidingId", sidingId);
		writerBase.writeString("sidingName", sidingName);
		writerBase.writeInt("cars", cars);
		writerBase.writeDouble("headX", headX);
		writerBase.writeDouble("headZ", headZ);
		writerBase.writeDouble("segStartX", segStartX);
		writerBase.writeDouble("segStartZ", segStartZ);
		writerBase.writeDouble("segEndX", segEndX);
		writerBase.writeDouble("segEndZ", segEndZ);
		writerBase.writeBoolean("segmentReversed", segmentReversed);
		writerBase.writeDouble("segmentOffsetM", segmentOffsetM);
		writerBase.writeDouble("segmentLengthM", segmentLengthM);
		writerBase.writeDouble("speedKmh", speedKmh);
		writerBase.writeBoolean("moving", moving);
		writerBase.writeBoolean("onRoute", onRoute);
		writerBase.writeBoolean("doorsOpen", doorsOpen);
		writerBase.writeString("platformId", platformId);
		writerBase.writeString("mission", mission);
		writerBase.writeDouble("frontX", frontX);
		writerBase.writeDouble("frontZ", frontZ);
		writerBase.writeDouble("rearX", rearX);
		writerBase.writeDouble("rearZ", rearZ);
		writerBase.writeString("activeCab", activeCab);
		writerBase.writeBoolean("cabManned", cabManned);
	}
}