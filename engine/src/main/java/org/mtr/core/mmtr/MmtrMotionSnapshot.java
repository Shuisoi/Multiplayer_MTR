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

	public MmtrMotionSnapshot() {
	}

	public MmtrMotionSnapshot(ReaderBase readerBase) {
		updateData(readerBase);
	}

	public static MmtrMotionSnapshot from(org.mtr.core.data.Siding siding, Vehicle vehicle) {
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
	}
}