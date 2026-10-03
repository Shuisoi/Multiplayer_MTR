package org.mtr.core.data;

import org.jspecify.annotations.Nullable;
import org.mtr.core.generated.data.VehicleCarSchema;
import org.mtr.core.serializer.ReaderBase;

public final class VehicleCar extends VehicleCarSchema {

	public final boolean hasOneBogie;

	/**
	 * 三态（notes/271 片 1）：读到的数据里**到底有没有** {@code mmtrPowered} 这个键。
	 *
	 * <p>{@code true} = 这是一次**显式声明**（世界文件/清单/指令写了 {@code powered}）；
	 * {@code false} = 没写，只有一路兜底值。为什么要分：{@code boolean} 上"没声明"与"显式无动力"
	 * 长得一样，于是"一列全无动力的挂车"和"作者没表态的车列"会被同一套兜底（把第一节当动力车）
	 * 处理 —— 现场 BR101+2×p1 被当成三台机车就是这一族问题的入口（notes/247）。</p>
	 *
	 * <p>不落盘：它描述的是"读进来时那个键在不在"，写完一次世界之后所有车都会带上这个键
	 * （生成的 {@code serializeData} 一直是无条件写），于是重载后一律视为已声明 —— 这条已在
	 * 设计文档 §4 片 1 记明。</p>
	 */
	private boolean mmtrPoweredDeclared;

	private static final int PASSENGERS_PER_SQUARE_METER = 2;

	/** MTR's car geometry; the MMTR metadata defaults to powered with the consist's default type. */
	public VehicleCar(String vehicleId, double length, double width, long capacity, double bogie1Position, double bogie2Position, double couplingPadding1, double couplingPadding2) {
		this(vehicleId, length, width, capacity, bogie1Position, bogie2Position, couplingPadding1, couplingPadding2, true, "");
	}

	/**
	 * Full constructor: adds the MMTR per-car metadata. {@code mmtrPowered} is what makes a hauled
	 * wagon contribute mass but no traction, and {@code mmtrConsistTypeId} overrides the consist's
	 * default ConsistType for this car only.
	 */
	public VehicleCar(String vehicleId, double length, double width, long capacity, double bogie1Position, double bogie2Position, double couplingPadding1, double couplingPadding2, boolean mmtrPowered, @Nullable String mmtrConsistTypeId) {
		super(vehicleId, length, width, capacity, bogie1Position, bogie2Position, couplingPadding1, couplingPadding2);
		this.mmtrPowered = mmtrPowered;
		this.mmtrConsistTypeId = mmtrConsistTypeId == null ? "" : mmtrConsistTypeId;
		hasOneBogie = this.bogie1Position == this.bogie2Position;
	}

	public VehicleCar(ReaderBase readerBase) {
		super(readerBase);
		hasOneBogie = bogie1Position == bogie2Position;
		updateData(readerBase);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		super.updateData(readerBase);
		// 只用来记"这个键在不在"：值本身由生成的 schema 读进 mmtrPowered。
		readerBase.unpackBoolean("mmtrPowered", value -> mmtrPoweredDeclared = true);
	}

	/** Whether this car can produce traction (hauled wagons cannot). */
	public boolean getMmtrPowered() {
		return mmtrPowered;
	}

	/** 见 {@link #mmtrPoweredDeclared}：数据里到底写没写 {@code mmtrPowered}。 */
	public boolean isMmtrPoweredDeclared() {
		return mmtrPoweredDeclared;
	}

	/** 把"动力与否"标成一次显式声明（指令/清单那条路走的就是它）。 */
	public void setMmtrPoweredDeclared(boolean value) {
		mmtrPoweredDeclared = value;
	}

	/** 载重比例 0..1（notes/271 片 1）：逐车质量 = 整备质量 + 比例 × 车底的载重能力。 */
	public double getMmtrLoadRatio() {
		return mmtrLoadRatio;
	}

	/** 超范围按边界夹住 —— 那是数据错误，不是一个物理状态。 */
	public void setMmtrLoadRatio(double value) {
		mmtrLoadRatio = Math.max(0, Math.min(1, value));
	}

	/** Per-car ConsistType override; empty means "use the consist's default type". */
	public String getMmtrConsistTypeId() {
		return mmtrConsistTypeId;
	}

	/**
	 * C4b: whether a COUPLER sits between this car and the next one — i.e. whether this boundary is a
	 * legal uncoupling seam. A fixed unit (a 8-car EMU) has no internal couplers, so it cannot be cut;
	 * a coupled-on rake leaves exactly one seam behind (the joint the surgery created).
	 */
	public boolean getMmtrCouplerAfter() {
		return mmtrCouplerAfter;
	}

	/** Marks (or clears) the coupler seam after this car. */
	public void setMmtrCouplerAfter(boolean value) {
		mmtrCouplerAfter = value;
	}

	/**
	 * C8: whether this car's couplers are AUTOMATIC (动车组/调机的自动车钩). A train that has drawn up
	 * to a standing rake under a 调车授权 and stopped inside coupler reach latches on by itself when
	 * both facing cars are automatic; a manual coupler (货车螺旋车钩) still needs the crew to confirm.
	 */
	public boolean getMmtrAutoCoupler() {
		return mmtrAutoCoupler;
	}

	/** Declares (or clears) this car's automatic couplers. */
	public void setMmtrAutoCoupler(boolean value) {
		mmtrAutoCoupler = value;
	}

	public String getVehicleId() {
		return vehicleId;
	}

	public double getLength() {
		return length;
	}

	public double getWidth() {
		return width;
	}

	public long getCapacity() {
		return capacity > 0 ? capacity : Math.round(length * width * PASSENGERS_PER_SQUARE_METER);
	}

	public double getBogie1Position() {
		return bogie1Position;
	}

	public double getBogie2Position() {
		return bogie2Position;
	}

	public double getTotalLength(boolean firstCar, boolean lastCar) {
		return getCouplingPadding1(firstCar) + length + getCouplingPadding2(lastCar);
	}

	/** Raw coupling padding at the A end (no first/last-car adjustment). */
	public double getCouplingPadding1() {
		return couplingPadding1;
	}

	/** Raw coupling padding at the B end (no first/last-car adjustment). */
	public double getCouplingPadding2() {
		return couplingPadding2;
	}

	double getCouplingPadding1(boolean firstCar) {
		return firstCar ? 0 : couplingPadding1;
	}

	double getCouplingPadding2(boolean lastCar) {
		return lastCar ? 0 : couplingPadding2;
	}
}
