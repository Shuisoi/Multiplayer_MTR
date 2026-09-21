package org.mtr.core.mmtr;

/**
 * Unified driver control intent merged from all input routes (keyboard + HID).
 * Notch-style and axis-style values coexist; each ConsistType/DriveController consumes
 * whichever semantics its control mode needs.
 */
public final class ControlState {

	public static final int REVERSER_BACK = -1;
	public static final int REVERSER_NEUTRAL = 0;
	public static final int REVERSER_FORWARD = 1;

	private int throttleNotch;
	private int brakeNotch;
	/**
	 * 三手柄机车的**油门手柄**位置：中央 0 = 关闭，正侧 = 牵引，负侧 = 电阻制动
	 * （±1 = 最小档，±2…±97 = 5%…100%，见 {@link ThreeHandleSpec}）。
	 * 与 {@link #throttleNotch} 并存：老控制器读 notch，三手柄控制器读这个。
	 */
	private int driveHandle;
	/**
	 * 三手柄机车的**定速巡航**设定值（km/h，0 = 关闭，步长 5）。
	 * AFB 生效时它是目标速度，油门手柄降为牵引上限。
	 */
	private int cruiseSpeedKmh;
	private int reverser;
	private double throttleAxis;
	private double brakeAxis;
	private boolean emergency;
	private int sourceMask;
	/** Signal S3 (AWS): 司机确认（acknowledge）按键意图——点按上升沿语义，警示 WARN 时生效。 */
	private boolean acknowledge;

	public ControlState() {
	}

	public static ControlState zero() {
		return new ControlState();
	}

	public int getThrottleNotch() { return throttleNotch; }
	public int getBrakeNotch() { return brakeNotch; }
	public int getDriveHandle() { return driveHandle; }
	public int getCruiseSpeedKmh() { return cruiseSpeedKmh; }
	public int getReverser() { return reverser; }
	public double getThrottleAxis() { return throttleAxis; }
	public double getBrakeAxis() { return brakeAxis; }
	public boolean isEmergency() { return emergency; }
	public int getSourceMask() { return sourceMask; }
	public boolean isAcknowledge() { return acknowledge; }

	public ControlState setThrottleNotch(int value) { this.throttleNotch = value; return this; }
	public ControlState setBrakeNotch(int value) { this.brakeNotch = value; return this; }
	public ControlState setDriveHandle(int value) { this.driveHandle = value; return this; }
	public ControlState setCruiseSpeedKmh(int value) { this.cruiseSpeedKmh = value; return this; }
	public ControlState setReverser(int value) { this.reverser = value; return this; }
	public ControlState setThrottleAxis(double value) { this.throttleAxis = clamp01(value); return this; }
	public ControlState setBrakeAxis(double value) { this.brakeAxis = clamp01(value); return this; }
	public ControlState setEmergency(boolean value) { this.emergency = value; return this; }
	public ControlState setSourceMask(int value) { this.sourceMask = value; return this; }
	public ControlState setAcknowledge(boolean value) { this.acknowledge = value; return this; }

	/** Keyboard relative step: keep the value inside [0, max]. */
	public void stepThrottle(int delta, int max) { this.throttleNotch = clamp(this.throttleNotch + delta, 0, max); }

	/** Keyboard relative step: keep the value inside [0, max]. */
	public void stepBrake(int delta, int max) { this.brakeNotch = clamp(this.brakeNotch + delta, 0, max); }

	/**
	 * 三手柄机车：油门手柄相对步进（客户端单击 = ±1%），钳在半程 {@link ThreeHandleSpec#DRIVE_HANDLE_MAX} 内。
	 * 引擎自己不产生输入，这个方法给工具/命令/测试用，与客户端 {@code MmtrDriveInput} 同一套边界。
	 */
	public void stepDriveHandle(int delta) { this.driveHandle = clamp(this.driveHandle + delta, -ThreeHandleSpec.DRIVE_HANDLE_MAX, ThreeHandleSpec.DRIVE_HANDLE_MAX); }

	public void stepReverser(int delta, int min, int max) { this.reverser = clamp(this.reverser + delta, min, max); }

	public ControlState copy() {
		final ControlState copy = new ControlState();
		copy.throttleNotch = throttleNotch;
		copy.brakeNotch = brakeNotch;
		copy.driveHandle = driveHandle;
		copy.cruiseSpeedKmh = cruiseSpeedKmh;
		copy.reverser = reverser;
		copy.throttleAxis = throttleAxis;
		copy.brakeAxis = brakeAxis;
		copy.emergency = emergency;
		copy.sourceMask = sourceMask;
		copy.acknowledge = acknowledge;
		return copy;
	}

	@Override
	public String toString() {
		return "ControlState{throttleNotch=" + throttleNotch + ", brakeNotch=" + brakeNotch + ", driveHandle=" + driveHandle
			+ ", cruiseSpeedKmh=" + cruiseSpeedKmh + ", reverser=" + reverser
			+ ", throttleAxis=" + throttleAxis + ", brakeAxis=" + brakeAxis + ", emergency=" + emergency + "}";
	}

	private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
	private static double clamp01(double value) { return Math.max(-1.0, Math.min(1.0, value)); }
}
