package org.mtr.core.mmtr.net;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * **列车运动流的线格式**（notes/369）。
 *
 * <h2>它解决什么</h2>
 * <p>notes/368 的实测：客户端大约**每秒**才收到一份车辆包，而那一份还是 **7.4 KB 的整份 JSON**
 * （`整份=1 补丁=0`，1191/1711 次）——位置、速度、手柄全挤在"脏拍才发"的那条通道上，
 * 于是镜像要么冻住（输入没到），要么每秒被拉回一次（位移掉帧）。</p>
 *
 * <p>这一层把"每 tick 都要到的东西"单独拿出来：**定长原语、量化、按需增量**，
 * 每 2 tick 一帧（默认 10 Hz）。稳态下每辆车每帧只要 <b>7 字节</b>（位置），
 * 手柄变化走边沿的 {@code CONTROL}（7 字节），停车目标/闭塞停车点走边沿的 {@code STATE}（17 字节）。</p>
 *
 * <h2>为什么是这个形状（而不是 JSON / 字节数组）</h2>
 * <p>MTR 的 mapping 层只给 {@code boolean/char/int/float/long/double/String} 七种原语
 * （{@code javap} 实测 {@code PacketBufferSender}/{@code PacketBufferReceiver} 逐一对称），
 * <b>没有字节数组</b>。而 {@code char} 正好是 2 字节 ⇒ 帧在线上就是一串 16 位字。
 * 所以本类**按字节组帧**（字节数才说得清、也才量得准），最后再**两字节一 char** 打包交给
 * {@code writeChar} —— 于是编解码可以放在**引擎侧纯 Java**：可以写用例，也可以把
 * "每条记录多少字节"钉死（谁往记录里加字段，谁的用例就会红）。</p>
 *
 * <h2>字节序与浮点</h2>
 * <p>多字节原语一律**大端**；浮点用 {@link Float#floatToRawIntBits}（不做 NaN 归一化），
 * 于是 round-trip 逐位相等、且不会把某个恰好是 NaN 位型的位域值改写掉。</p>
 *
 * <h2>线格式</h2>
 * <pre>
 * 字节 0..1  byteCount (u16, 含头)
 * 字节 2     version (u8)
 * 字节 3     recordCount (u8)
 * 字节 4..   record*
 * record := kind (u8) | payload
 *
 * kind 0x01 MOTION        slot(u16) railProgress(f32)                                    7 B
 * kind 0x02 MOTION_SPEED  slot(u16) railProgress(f32) speed(f32)                        11 B
 * kind 0x03 CONTROL       slot(u16) packedControl(u32)                                   7 B
 * kind 0x04 STATE         slot(u16) flags(u16) runStopTarget(f32) runTotalDistance(f32)
 *                         blockStopM(f32)                                               17 B
 * kind 0x05 DROP          slot(u16)                                                      3 B
 * kind 0x06 PING          serverMillis(u32)                                              5 B
 * </pre>
 *
 * <h2>量化口径（用例钉死）</h2>
 * <ul>
 *   <li>{@code railProgress} / 目标距离：{@code f32}（米）。60000 m 处 ulp ≈ 3.9 mm &lt; 1 cm；</li>
 *   <li>{@code speed}：{@code f32}（blocks/ms，引擎内部单位），只在需要校速的帧携带；</li>
 *   <li>手柄位域：油门 5 位、制动 6 位、手柄 8 位（±97，偏移 128）、定速 7 位（步长 5 km/h）、
 *       换向 2 位、紧急 1 位、灯 2 位 = 31 位。</li>
 * </ul>
 */
public final class MmtrMotionFrame {

	public static final int VERSION = 1;

	public static final int KIND_MOTION = 0x01;
	public static final int KIND_MOTION_SPEED = 0x02;
	public static final int KIND_CONTROL = 0x03;
	public static final int KIND_STATE = 0x04;
	public static final int KIND_DROP = 0x05;
	public static final int KIND_PING = 0x06;

	/** 帧头字节数（byteCount u16 + version u8 + recordCount u8）。 */
	public static final int HEADER_BYTES = 4;

	/** `STATE` 记录里的旗标位（u16）。 */
	public static final int FLAG_MMTR_ACTIVE = 1;
	public static final int FLAG_MOTION_MIRROR = 1 << 1;
	public static final int FLAG_REVERSED = 1 << 2;
	public static final int FLAG_DOOR_TARGET = 1 << 3;
	public static final int FLAG_DOOR_LEFT = 1 << 4;
	public static final int FLAG_DOOR_RIGHT = 1 << 5;
	public static final int FLAG_DOOR_MANUAL = 1 << 6;
	public static final int FLAG_PINNED = 1 << 7;
	public static final int FLAG_PROTECTION = 1 << 8;
	public static final int FLAG_BLOCK_HELD = 1 << 9;
	public static final int FLAG_AWS_PENDING = 1 << 10;
	public static final int FLAG_AWS_ACKNOWLEDGED = 1 << 11;
	public static final int FLAG_CURRENTLY_MANUAL = 1 << 12;
	public static final int FLAG_AUTHORITY_TRIPPED = 1 << 13;

	private MmtrMotionFrame() {
	}

	// ------------------------------------------------------------------ records

	/**
	 * 一条记录。解出来的对象只用于**读**，写永远是 {@link Writer} 的职责 ——
	 * 于是"线上是什么形状"只有一处定义。
	 */
	public sealed interface Record permits Motion, Control, State, Drop, Ping {
		int kind();
	}

	/**
	 * 位置帧。{@code speed} 为 {@code null} = 这一帧不带速度（客户端本地积分够准，
	 * 每几帧校一次速即可）。
	 */
	public record Motion(int slot, double railProgress, @Nullable Double speed) implements Record {

		@Override
		public int kind() {
			return speed == null ? KIND_MOTION : KIND_MOTION_SPEED;
		}
	}

	/** 手柄边沿。{@code packed} 见 {@link #packControl}。 */
	public record Control(int slot, int packed) implements Record {

		@Override
		public int kind() {
			return KIND_CONTROL;
		}

		public int throttleNotch() {
			return controlThrottleNotch(packed);
		}

		public int brakeNotch() {
			return controlBrakeNotch(packed);
		}

		public int driveHandle() {
			return controlDriveHandle(packed);
		}

		public int cruiseKmh() {
			return controlCruiseKmh(packed);
		}

		public int reverser() {
			return controlReverser(packed);
		}

		public boolean emergency() {
			return controlEmergency(packed);
		}

		public int lightSwitch() {
			return controlLightSwitch(packed);
		}
	}

	/**
	 * 状态边沿：旗标 + 三个"夹紧量"。{@code blockStopM} 是闭塞/占用停车点
	 * （服务端 {@code mmtrBlockStopM}，客户端镜像此前**根本没有**这个数）。
	 */
	public record State(int slot, int flags, double runStopTarget, double runTotalDistance, double blockStopM) implements Record {

		@Override
		public int kind() {
			return KIND_STATE;
		}

		public boolean hasFlag(int flag) {
			return (flags & flag) != 0;
		}
	}

	/** 这辆车不再由本客户端镜像（出视距 / 被删 / 驾驶室解散）。 */
	public record Drop(int slot) implements Record {

		@Override
		public int kind() {
			return KIND_DROP;
		}
	}

	/** 时钟对齐（误差缓冲要按时间收敛）。{@code serverMillis} = 服务端自启动的毫秒数，低 32 位。 */
	public record Ping(long serverMillis) implements Record {

		@Override
		public int kind() {
			return KIND_PING;
		}
	}

	// ------------------------------------------------------------------ control bit fields

	private static final int THROTTLE_BITS = 5;
	private static final int BRAKE_BITS = 6;
	private static final int DRIVE_HANDLE_BITS = 8;
	private static final int CRUISE_BITS = 7;
	private static final int REVERSER_BITS = 2;
	private static final int LIGHT_BITS = 2;

	private static final int DRIVE_HANDLE_OFFSET = 128;
	private static final int CRUISE_STEP_KMH = 5;

	private static final int THROTTLE_SHIFT = 0;
	private static final int BRAKE_SHIFT = THROTTLE_SHIFT + THROTTLE_BITS;
	private static final int DRIVE_HANDLE_SHIFT = BRAKE_SHIFT + BRAKE_BITS;
	private static final int CRUISE_SHIFT = DRIVE_HANDLE_SHIFT + DRIVE_HANDLE_BITS;
	private static final int REVERSER_SHIFT = CRUISE_SHIFT + CRUISE_BITS;
	private static final int EMERGENCY_SHIFT = REVERSER_SHIFT + REVERSER_BITS;
	private static final int LIGHT_SHIFT = EMERGENCY_SHIFT + 1;

	/**
	 * 把手柄打包成 u32 位域。**越界一律夹住**，不抛异常：夹住只会让"某一档读成邻档"，
	 * 而抛异常会打断整帧（一帧里还有别的车）。
	 */
	public static int packControl(int throttleNotch, int brakeNotch, int driveHandle, int cruiseKmh, int reverser, boolean emergency, int lightSwitch) {
		final int throttle = clamp(throttleNotch, 0, (1 << THROTTLE_BITS) - 1);
		final int brake = clamp(brakeNotch, 0, (1 << BRAKE_BITS) - 1);
		final int handle = clamp(driveHandle, -DRIVE_HANDLE_OFFSET, (1 << DRIVE_HANDLE_BITS) - 1 - DRIVE_HANDLE_OFFSET);
		final int cruise = clamp((int) Math.round(cruiseKmh / (double) CRUISE_STEP_KMH), 0, (1 << CRUISE_BITS) - 1);
		final int reverserRaw = reverser < 0 ? 2 : reverser > 0 ? 1 : 0;
		final int light = clamp(lightSwitch, 0, (1 << LIGHT_BITS) - 1);
		return ((throttle & 0x1F) << THROTTLE_SHIFT)
			| ((brake & 0x3F) << BRAKE_SHIFT)
			| (((handle + DRIVE_HANDLE_OFFSET) & 0xFF) << DRIVE_HANDLE_SHIFT)
			| ((cruise & 0x7F) << CRUISE_SHIFT)
			| ((reverserRaw & 0x3) << REVERSER_SHIFT)
			| ((emergency ? 1 : 0) << EMERGENCY_SHIFT)
			| ((light & 0x3) << LIGHT_SHIFT);
	}

	public static int controlThrottleNotch(int packed) {
		return (packed >>> THROTTLE_SHIFT) & 0x1F;
	}

	public static int controlBrakeNotch(int packed) {
		return (packed >>> BRAKE_SHIFT) & 0x3F;
	}

	public static int controlDriveHandle(int packed) {
		return ((packed >>> DRIVE_HANDLE_SHIFT) & 0xFF) - DRIVE_HANDLE_OFFSET;
	}

	public static int controlCruiseKmh(int packed) {
		return ((packed >>> CRUISE_SHIFT) & 0x7F) * CRUISE_STEP_KMH;
	}

	public static int controlReverser(int packed) {
		return switch ((packed >>> REVERSER_SHIFT) & 0x3) {
			case 1 -> 1;
			case 2 -> -1;
			default -> 0;
		};
	}

	public static boolean controlEmergency(int packed) {
		return ((packed >>> EMERGENCY_SHIFT) & 0x1) != 0;
	}

	public static int controlLightSwitch(int packed) {
		return (packed >>> LIGHT_SHIFT) & 0x3;
	}

	private static int clamp(int value, int min, int max) {
		return value < min ? min : Math.min(value, max);
	}

	// ------------------------------------------------------------------ writer

	/** 组帧。**一个客户端一帧一个**（notes/369 §4.3）：通道头只付一次。 */
	public static final class Writer {

		private byte[] buffer = new byte[64];
		private int size = HEADER_BYTES;
		private int records;

		public Writer motion(int slot, double railProgress) {
			start(KIND_MOTION, 6);
			u16(slot);
			f32(railProgress);
			return this;
		}

		public Writer motion(int slot, double railProgress, double speed) {
			start(KIND_MOTION_SPEED, 10);
			u16(slot);
			f32(railProgress);
			f32(speed);
			return this;
		}

		public Writer control(int slot, int packedControl) {
			start(KIND_CONTROL, 6);
			u16(slot);
			u32(packedControl);
			return this;
		}

		public Writer state(int slot, int flags, double runStopTarget, double runTotalDistance, double blockStopM) {
			start(KIND_STATE, 16);
			u16(slot);
			u16(flags);
			f32(runStopTarget);
			f32(runTotalDistance);
			f32(blockStopM);
			return this;
		}

		public Writer drop(int slot) {
			start(KIND_DROP, 2);
			u16(slot);
			return this;
		}

		public Writer ping(long serverMillis) {
			start(KIND_PING, 4);
			u32((int) serverMillis);
			return this;
		}

		public int recordCount() {
			return records;
		}

		/** 帧的**字节**数（含 4 字节头）—— 带宽算术用的就是这个数。 */
		public int byteCount() {
			return size;
		}

		/** 帧打包成多少个 16 位字（奇数长度时尾部补一个 0 字节）。 */
		public int charCount() {
			return (size + 1) / 2;
		}

		public boolean isEmpty() {
			return records == 0;
		}

		/**
		 * 打包成 16 位字。线上就是这么发的：{@code for (char c : frame) sender.writeChar(c);}
		 * 帧头里的 {@code byteCount} 才是权威长度（尾部那个补零字节不算）。
		 */
		public char[] toCharArray() {
			final char[] out = new char[charCount()];
			out[0] = (char) size;
			out[1] = (char) ((VERSION << 8) | (records & 0xFF));
			for (int i = HEADER_BYTES; i < size; i++) {
				out[i / 2] |= (char) ((buffer[i] & 0xFF) << (i % 2 == 0 ? 8 : 0));
			}
			return out;
		}

		private void start(int kind, int payloadBytes) {
			ensure(1 + payloadBytes);
			buffer[size++] = (byte) kind;
			records++;
		}

		private void ensure(int extra) {
			if (size + extra > buffer.length) {
				final byte[] grown = new byte[Math.max(buffer.length * 2, size + extra)];
				System.arraycopy(buffer, 0, grown, 0, size);
				buffer = grown;
			}
		}

		private void u16(int value) {
			ensure(2);
			buffer[size++] = (byte) ((value >>> 8) & 0xFF);
			buffer[size++] = (byte) (value & 0xFF);
		}

		private void u32(int value) {
			ensure(4);
			buffer[size++] = (byte) ((value >>> 24) & 0xFF);
			buffer[size++] = (byte) ((value >>> 16) & 0xFF);
			buffer[size++] = (byte) ((value >>> 8) & 0xFF);
			buffer[size++] = (byte) (value & 0xFF);
		}

		private void f32(double value) {
			u32(Float.floatToRawIntBits((float) value));
		}
	}

	// ------------------------------------------------------------------ reader

	/**
	 * 解帧。**按"未知即停"处理**：帧是从网上来的，宁可少读几条记录，也不能让一条坏记录
	 * 把后面好的也带走（{@link #isMalformed()} 供调用方计数）。
	 */
	public static final class Reader {

		private final byte[] frame;
		private final int end;
		private final int version;
		private int remaining;
		private int cursor = HEADER_BYTES;
		private boolean malformed;

		public Reader(char[] chars) {
			final byte[] bytes = new byte[chars.length * 2];
			for (int i = 0; i < chars.length; i++) {
				bytes[i * 2] = (byte) ((chars[i] >>> 8) & 0xFF);
				bytes[i * 2 + 1] = (byte) (chars[i] & 0xFF);
			}
			frame = bytes;
			int count;
			int declaredVersion = VERSION;
			int declaredRecords = 0;
			if (bytes.length < HEADER_BYTES) {
				count = 0;
				malformed = true;
			} else {
				final int declared = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
				count = declared < HEADER_BYTES || declared > bytes.length ? HEADER_BYTES : declared;
				declaredVersion = bytes[2] & 0xFF;
				declaredRecords = bytes[3] & 0xFF;
				if (declared < HEADER_BYTES || declared > bytes.length) {
					malformed = true;
				}
			}
			end = count;
			version = declaredVersion;
			remaining = declaredRecords;
			if (version != VERSION) {
				malformed = true;
				remaining = 0;
			}
		}

		public int version() {
			return version;
		}

		public int remainingRecords() {
			return remaining;
		}

		public boolean isMalformed() {
			return malformed;
		}

		/** @return 下一条记录；{@code null} = 帧读完（或遇到坏数据，见 {@link #isMalformed()}）。 */
		public @Nullable Record readNext() {
			if (malformed || remaining <= 0) {
				return null;
			}
			if (cursor >= end) {
				// 声明了还有记录、字节却已经用完 = 帧被截断（线上丢了尾巴）。这就是红证，要计数。
				malformed = true;
				return null;
			}
			final int kind = frame[cursor++] & 0xFF;
			remaining--;
			switch (kind) {
				case KIND_MOTION: {
					final Integer slot = readU16();
					final Double progress = readF32();
					return slot == null || progress == null ? null : new Motion(slot, progress, null);
				}
				case KIND_MOTION_SPEED: {
					final Integer slot = readU16();
					final Double progress = readF32();
					final Double speed = readF32();
					return slot == null || progress == null || speed == null ? null : new Motion(slot, progress, speed);
				}
				case KIND_CONTROL: {
					final Integer slot = readU16();
					final Integer packed = readU32();
					return slot == null || packed == null ? null : new Control(slot, packed);
				}
				case KIND_STATE: {
					final Integer slot = readU16();
					final Integer flags = readU16();
					final Double runStopTarget = readF32();
					final Double runTotalDistance = readF32();
					final Double blockStopM = readF32();
					if (slot == null || flags == null || runStopTarget == null || runTotalDistance == null || blockStopM == null) {
						return null;
					}
					return new State(slot, flags, runStopTarget, runTotalDistance, blockStopM);
				}
				case KIND_DROP: {
					final Integer slot = readU16();
					return slot == null ? null : new Drop(slot);
				}
				case KIND_PING: {
					final Integer millis = readU32();
					return millis == null ? null : new Ping(millis & 0xFFFFFFFFL);
				}
				default:
					malformed = true;
					return null;
			}
		}

		private @Nullable Integer readU16() {
			if (!has(2)) {
				return null;
			}
			final int value = ((frame[cursor] & 0xFF) << 8) | (frame[cursor + 1] & 0xFF);
			cursor += 2;
			return value;
		}

		private @Nullable Integer readU32() {
			if (!has(4)) {
				return null;
			}
			final int value = ((frame[cursor] & 0xFF) << 24)
				| ((frame[cursor + 1] & 0xFF) << 16)
				| ((frame[cursor + 2] & 0xFF) << 8)
				| (frame[cursor + 3] & 0xFF);
			cursor += 4;
			return value;
		}

		private @Nullable Double readF32() {
			final Integer bits = readU32();
			return bits == null ? null : (double) Float.intBitsToFloat(bits);
		}

		private boolean has(int bytes) {
			if (cursor + bytes > end) {
				malformed = true;
				return false;
			}
			return true;
		}
	}

	/** 便利方法（用例与诊断用；热路径请直接用 {@link Reader}，免得建列表）。 */
	public static List<Record> decode(char[] frame) {
		final Reader reader = new Reader(frame);
		final List<Record> records = new ArrayList<>();
		Record record;
		while ((record = reader.readNext()) != null) {
			records.add(record);
		}
		return records;
	}
}
