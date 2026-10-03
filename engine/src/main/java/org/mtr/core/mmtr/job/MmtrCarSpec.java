package org.mtr.core.mmtr.job;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.mmtr.consist.MmtrUnitCar;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

/**
 * Rolling-stock spec for one car of a job consist. Kept stable and independent of MTR's
 * VehicleCar so web-authored jobs do not leak engine schema details; the executor maps this
 * onto the engine's VehicleCar when the consist is spawned.
 *
 * <p>C2 adds the two fields that make a formation physically real: {@link #powered} (a hauled
 * wagon contributes mass and brake-pipe volume but no traction) and {@link #consistTypeId}
 * (a per-car override of the consist's default ConsistType). Both default to the previous
 * behaviour — powered, inheriting the consist default — so existing jobs are unchanged.</p>
 *
 * <p>C6 answers the open question "who declares the coupling seams" (design §8 O3): all three
 * authoring paths — the rolling-stock manifest, a consist job and a consist template — go through
 * this spec, so {@link #mmtrCouplerAfter} is the single place a seam is declared. It maps straight
 * onto {@link VehicleCar#getMmtrCouplerAfter()} (default {@code false} = a fixed unit such as an
 * 8-car EMU, no uncoupling inside it).</p>
 */
public final class MmtrCarSpec implements SerializedDataBase {

	/** Built-in MTR train used when an authoring path leaves the model id blank. */
	public static final String DEFAULT_VEHICLE_ID = "m_train";

	public String vehicleId = DEFAULT_VEHICLE_ID;
	public double length;
	public double width;
	public long capacity;
	public double bogie1Position;
	public double bogie2Position;
	public double couplingPadding1;
	public double couplingPadding2;
	public boolean powered = true;
	/**
	 * 三态（notes/271 片 1）：{@code powered} 这个键在作者数据里**到底写没写**。
	 *
	 * <p>{@code null} = 没写 ⇒ 沿用"借车底、最多一节借牵引"的老兜底；非 {@code null} = 这是一次
	 * **显式声明**（{@code false} 就永远不借牵引）。光看 {@code powered} 分不出这两件事，而"一列
	 * 全无动力的挂车"必须能被说出来（notes/247 的现场就是从这儿漏出去的）。</p>
	 */
	public @Nullable Boolean poweredDeclared;
	public String consistTypeId = "";
	/**
	 * 载重比例 0..1（用户口径 2026-09-26）：逐车质量 = 整备质量 + {@code loadRatio × 车底的载重能力}。
	 * 空车写 0；载重只影响质量（惯性、黏着法向力、制动重率），不动分配阀的两段动作。
	 */
	public double loadRatio;
	/**
	 * Whether a COUPLER sits between this car and the next one — i.e. whether this boundary is a legal
	 * uncoupling seam. {@code false} (default) keeps the previous behaviour: the cars form one fixed
	 * unit (an EMU rake), which cannot be cut. Set it on the last car of each haulable group so
	 * "locomotive + wagons" and 重联 8+8 can be uncoupled at exactly those boundaries.
	 */
	public boolean mmtrCouplerAfter;
	/**
	 * C8: whether this car's couplers are AUTOMATIC (default {@code true} - 动车组/调机). With automatic
	 * couplers on BOTH facing cars, a train that stops inside coupler reach of a standing rake under a
	 * 调车授权 latches on by itself (no key press); {@code false} declares a manual coupler (货车螺旋
	 * 车钩), which still needs the crew to confirm with the coupler key.
	 */
	public boolean mmtrAutoCoupler = true;

	public MmtrCarSpec() {
	}

	public MmtrCarSpec(ReaderBase readerBase) {
		updateData(readerBase);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		vehicleId = readerBase.getString("vehicleId", "");
		length = readerBase.getDouble("length", 0);
		width = readerBase.getDouble("width", 0);
		capacity = readerBase.getLong("capacity", 0);
		bogie1Position = readerBase.getDouble("bogie1Position", 0);
		bogie2Position = readerBase.getDouble("bogie2Position", 0);
		couplingPadding1 = readerBase.getDouble("couplingPadding1", 0);
		couplingPadding2 = readerBase.getDouble("couplingPadding2", 0);
		powered = readerBase.getBoolean("powered", true);
		poweredDeclared = null;
		readerBase.unpackBoolean("powered", value -> poweredDeclared = value);
		consistTypeId = readerBase.getString("consistTypeId", "");
		loadRatio = readerBase.getDouble("loadRatio", 0);
		mmtrCouplerAfter = readerBase.getBoolean("mmtrCouplerAfter", false);
		mmtrAutoCoupler = readerBase.getBoolean("mmtrAutoCoupler", true);
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeString("vehicleId", vehicleId);
		writerBase.writeDouble("length", length);
		writerBase.writeDouble("width", width);
		writerBase.writeLong("capacity", capacity);
		writerBase.writeDouble("bogie1Position", bogie1Position);
		writerBase.writeDouble("bogie2Position", bogie2Position);
		writerBase.writeDouble("couplingPadding1", couplingPadding1);
		writerBase.writeDouble("couplingPadding2", couplingPadding2);
		writerBase.writeBoolean("powered", powered);
		writerBase.writeString("consistTypeId", consistTypeId);
		writerBase.writeDouble("loadRatio", loadRatio);
		writerBase.writeBoolean("mmtrCouplerAfter", mmtrCouplerAfter);
		writerBase.writeBoolean("mmtrAutoCoupler", mmtrAutoCoupler);
	}

	/** Converts this authoring spec into MTR's runtime car, carrying the MMTR metadata with it. */
	public VehicleCar toVehicleCar() {
		final VehicleCar car = new VehicleCar(vehicleId, length, width, capacity, bogie1Position, bogie2Position, couplingPadding1, couplingPadding2, powered, consistTypeId);
		car.setMmtrCouplerAfter(mmtrCouplerAfter);
		car.setMmtrAutoCoupler(mmtrAutoCoupler);
		// 片 1：三态与载重一路带到运行时车卡上（否则"显式无动力"和"零载重"出了作者层就没了）。
		car.setMmtrPoweredDeclared(poweredDeclared != null);
		car.setMmtrLoadRatio(loadRatio);
		return car;
	}

	/** Converts this authoring spec into the consist core's immutable car (job → consist). */
	public MmtrUnitCar toUnitCar() {
		return new MmtrUnitCar(vehicleId, length, couplingPadding1, couplingPadding2, powered, consistTypeId == null || consistTypeId.isEmpty() ? null : consistTypeId);
	}

	/**
	 * C9: rebuild a spec from a runtime car. After a coupling/uncoupling surgery the job's fleet no
	 * longer matches its authored list, so the scheduler refreshes it from the merged formation's own
	 * cars - that keeps a following UNCOUPLE step's coupler gate honest.
	 */
	public static MmtrCarSpec fromVehicleCar(VehicleCar car) {
		final MmtrCarSpec spec = new MmtrCarSpec();
		spec.vehicleId = car.getVehicleId();
		spec.length = car.getLength();
		spec.width = car.getWidth();
		spec.capacity = car.getCapacity();
		spec.bogie1Position = car.getBogie1Position();
		spec.bogie2Position = car.getBogie2Position();
		spec.couplingPadding1 = car.getCouplingPadding1();
		spec.couplingPadding2 = car.getCouplingPadding2();
		spec.powered = car.getMmtrPowered();
		// 三态照着运行时车卡回填：只有"当时确实写了这个键"的车才带着声明往前走。
		spec.poweredDeclared = car.isMmtrPoweredDeclared() ? car.getMmtrPowered() : null;
		spec.consistTypeId = car.getMmtrConsistTypeId();
		spec.loadRatio = car.getMmtrLoadRatio();
		spec.mmtrCouplerAfter = car.getMmtrCouplerAfter();
		spec.mmtrAutoCoupler = car.getMmtrAutoCoupler();
		return spec;
	}
}
