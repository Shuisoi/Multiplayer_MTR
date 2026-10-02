package org.mtr.core.mmtr.net;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.net.MmtrMotionFrame.Control;
import org.mtr.core.mmtr.net.MmtrMotionFrame.Drop;
import org.mtr.core.mmtr.net.MmtrMotionFrame.Motion;
import org.mtr.core.mmtr.net.MmtrMotionFrame.Ping;
import org.mtr.core.mmtr.net.MmtrMotionFrame.Record;
import org.mtr.core.mmtr.net.MmtrMotionFrame.State;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运动流线格式的用例（notes/369 §9）。
 *
 * <h2>这些用例真正钉的是什么</h2>
 * <p>不是"编解码能跑"，而是三件往后**很难回头**的事：</p>
 * <ol>
 *   <li><b>每条记录的字节数</b>（{@link #recordSizesArePinned}）：这是"低网络开销"这个指标的全部载体 ——
 *       谁往记录里加一个字段，谁的用例就会红，然后必须回到 notes/369 §4.2 改口径；</li>
 *   <li><b>位域的边界</b>：三手柄 ±97、制动 63、定速步长 5、换向 −1/0/1 —— 截错一位就是"某一档读成邻档"；</li>
 *   <li><b>坏帧不许带崩后面的记录</b>：帧是从网上来的，未知 kind / 截断只能是"少读几条 + 立一个红证"。</li>
 * </ol>
 */
public final class MmtrMotionFrameTests {

	private static MmtrMotionFrame.Reader readerOf(char[] frame) {
		return new MmtrMotionFrame.Reader(frame);
	}

	/**
	 * ★ 带宽回归：**每条记录的字节数**。稳态下每辆车每帧只有 {@code MOTION}（7 B），
	 * 所以"车队规模 × 7 B × 帧率"就是这条通道的全部成本。
	 */
	@Test
	public void recordSizesArePinned() {
		assertEquals(2, new MmtrMotionFrame.Writer().toCharArray().length, "空帧 = 4 字节头 = 2 个 16 位字");
		assertEquals(4, new MmtrMotionFrame.Writer().byteCount(), "空帧只有头");

		assertEquals(4 + 7, new MmtrMotionFrame.Writer().motion(1, 0).byteCount(), "MOTION = 头 + 7 B");
		assertEquals(4 + 11, new MmtrMotionFrame.Writer().motion(1, 0, 0).byteCount(), "MOTION_SPEED = 头 + 11 B");
		assertEquals(4 + 7, new MmtrMotionFrame.Writer().control(1, 0).byteCount(), "CONTROL = 头 + 7 B");
		assertEquals(4 + 17, new MmtrMotionFrame.Writer().state(1, 0, 0, 0, 0).byteCount(), "STATE = 头 + 17 B");
		assertEquals(4 + 3, new MmtrMotionFrame.Writer().drop(1).byteCount(), "DROP = 头 + 3 B");
		assertEquals(4 + 5, new MmtrMotionFrame.Writer().ping(0).byteCount(), "PING = 头 + 5 B");
		assertEquals(4 + 25, new MmtrMotionFrame.Writer().slot(1, 0, 0, 0, 0, 0).byteCount(), "SLOT = 头 + 25 B");
		assertEquals(4 + 6, new MmtrMotionFrame.Writer().legs(1, 0, List.of()).byteCount(), "LEGS 空 = 头 + 6 B");
	}

	/**
	 * ★ 腿阴影增量的字节数（notes/369 §4.4）：**一条腿 107 字节**，而一条腿是每 25–100 m
	 * （60 km/h 下 1.5–6 秒）才出现一次 ⇒ 18–71 B/s。把它留在 ② 里则是"每长一条腿一份 7.4 KB 整份"。
	 */
	@Test
	public void legRecordsStayAtAboutOneHundredBytesPerNewLeg() {
		final String hex = "0000000000000F51-0000000000000047-000000000000090B-000000000000102D-0000000000000047-000000000000090B";
		assertEquals(101, hex.length(), "一条腿的 hex 是 101 字符（6 组 16 位十六进制 + 5 个连字符）");
		final int oneLeg = new MmtrMotionFrame.Writer().legs(3, 1, List.of(hex)).byteCount();
		assertEquals(4 + 6 + 1 + 101, oneLeg, "一条新腿 = 107 字节（+ 4 字节帧头）");
		final int fourLegs = new MmtrMotionFrame.Writer().legs(3, 1, List.of(hex, hex, hex, hex)).byteCount();
		assertEquals(4 + 6 + 4 * (1 + 101), fourLegs, "四条新腿 = 408 字节（+ 头）");
	}

	@Test
	public void slotAndLegsRoundTrip() {
		final long vehicleId = 4_548_773_940_558_821_130L;
		final List<Record> slotRecords = MmtrMotionFrame.decode(new MmtrMotionFrame.Writer()
			.slot(9, vehicleId, MmtrMotionFrame.FLAG_MMTR_ACTIVE | MmtrMotionFrame.FLAG_MOTION_MIRROR, 45_948.0, 50_000.0, 46_100.25)
			.toCharArray());
		assertEquals(1, slotRecords.size());
		final MmtrMotionFrame.Slot slot = (MmtrMotionFrame.Slot) slotRecords.get(0);
		assertEquals(9, slot.slot(), "槽位");
		assertEquals(vehicleId, slot.vehicleId(), "长 id（i64，八字节原样）");
		assertTrue(slot.hasFlag(MmtrMotionFrame.FLAG_MOTION_MIRROR), "镜像旗标");
		assertEquals(45_948.0f, (float) slot.runStopTarget(), "初值 = 停车目标");
		assertEquals(46_100.25f, (float) slot.blockStopM(), "初值 = 闭塞停车点");

		final String hexA = "0000000000000F51-0000000000000047-000000000000090B-000000000000102D-0000000000000047-000000000000090B";
		final String hexB = "FFFFFFFFFFFFE3FA-0000000000000041-000000000000067D-FFFFFFFFFFFFE435-0000000000000041-0000000000000683";
		final List<Record> legRecords = MmtrMotionFrame.decode(new MmtrMotionFrame.Writer().legs(4, 2, List.of(hexA, hexB)).toCharArray());
		assertEquals(1, legRecords.size());
		final MmtrMotionFrame.Legs legs = (MmtrMotionFrame.Legs) legRecords.get(0);
		assertEquals(4, legs.slot(), "槽位");
		assertEquals(2, legs.droppedFromTrainTail(), "从尾巴丢两根");
		assertEquals(List.of(hexA, hexB), legs.newLegs(), "车头新增的两根腿按顺序");
	}

	/**
	 * ★ 带宽预算：10 辆车在动、10 Hz、每客户端 —— 即 notes/369 §5 表里那一行。
	 *
	 * <p>位置每帧发（7 B/车），速度每 5 帧补一次（11 B/车）⇒ 平均 (4×74 + 114)/5 = 82 B/帧。</p>
	 */
	@Test
	public void tenCarSteadyStateStaysWellUnderTheOldSevenKilobytes() {
		final int positionFrameBytes = 4 + 10 * 7;
		final int speedFrameBytes = 4 + 10 * 11;
		assertEquals(74, positionFrameBytes, "10 辆车一帧 74 字节");
		assertEquals(114, speedFrameBytes, "补速度那一帧 114 字节");

		final double averageBytesPerFrame = (4.0 * positionFrameBytes + speedFrameBytes) / 5.0;
		assertEquals(82.0, averageBytesPerFrame, "平均每帧 82 字节");

		final double bytesPerSecondPerClient = averageBytesPerFrame * 10.0;
		assertTrue(bytesPerSecondPerClient < 1024, "10 车稳态 < 1 KB/s/客户端（旧协议实测 7.4 KB/s），实际 " + bytesPerSecondPerClient);
		assertTrue(bytesPerSecondPerClient * 64 < 64 * 1024, "64 个客户端看 10 车 < 64 KB/s 出口，实际 " + bytesPerSecondPerClient * 64);
	}

	@Test
	public void motionRoundTrip() {
		final int[] slots = {1, 2, 65535};
		final double[] progresses = {0.0, 1.5, 31862.39, 59_999.75};
		for (final int slot : slots) {
			for (final double progress : progresses) {
				final List<Record> records = MmtrMotionFrame.decode(new MmtrMotionFrame.Writer().motion(slot, progress).toCharArray());
				assertEquals(1, records.size(), "一条记录");
				assertTrue(records.get(0) instanceof Motion, "是 MOTION");
				final Motion motion = (Motion) records.get(0);
				assertEquals(slot, motion.slot(), "槽位原样");
				assertNull(motion.speed(), "不带速度");
				assertEquals((float) progress, (float) motion.railProgress(), "位置按 f32 往返");
			}
		}
	}

	@Test
	public void motionWithSpeedRoundTrip() {
		final List<Record> records = MmtrMotionFrame.decode(new MmtrMotionFrame.Writer().motion(7, 1234.5, 0.0123).toCharArray());
		assertEquals(1, records.size());
		final Motion motion = (Motion) records.get(0);
		assertEquals(MmtrMotionFrame.KIND_MOTION_SPEED, motion.kind(), "带速度是另一种 kind");
		assertNotNull(motion.speed(), "带速度");
		assertEquals(0.0123f, motion.speed().floatValue(), "速度按 f32 往返");
	}

	/** 手柄位域的边界值。三手柄机车的手柄是 ±97，制动位最多到 8/EB，定速步长 5 km/h。 */
	@Test
	public void controlRoundTripAtBoundaries() {
		final int[][] cases = {
			{0, 0, 0, 0, 0, 0, 0},
			{31, 63, 127, 635, 1, 3, 3},
			{7, 8, 97, 120, 1, 2, 2},
			{7, 8, -97, 0, -1, 0, 0},
			{1, 0, 35, 5, 1, 1, 1},
		};
		for (final int[] c : cases) {
			final boolean emergency = c[5] == 3;
			final int packed = MmtrMotionFrame.packControl(c[0], c[1], c[2], c[3], c[4], emergency, c[6]);
			final List<Record> records = MmtrMotionFrame.decode(new MmtrMotionFrame.Writer().control(9, packed).toCharArray());
			assertEquals(1, records.size());
			final Control control = (Control) records.get(0);
			assertEquals(packed, control.packed(), "位域原样往返（不做 NaN 归一化也走得通）");
			assertEquals(c[0], control.throttleNotch(), "油门");
			assertEquals(c[1], control.brakeNotch(), "制动");
			assertEquals(c[2], control.driveHandle(), "油门手柄 ±97");
			assertEquals(c[3], control.cruiseKmh(), "定速按 5 km/h 量化");
			assertEquals(c[4], control.reverser(), "换向 −1/0/1");
			assertEquals(emergency, control.emergency(), "紧急");
			assertEquals(c[6], control.lightSwitch(), "灯光档位");
		}
	}

	/** 越界一律**夹住**，不抛异常：一帧里还有别的车，不能因为一个坏值整帧作废。 */
	@Test
	public void controlClampsOutOfRangeValues() {
		final int packed = MmtrMotionFrame.packControl(-5, 99, 200, 1000, -9, false, 9);
		assertEquals(0, MmtrMotionFrame.controlThrottleNotch(packed), "油门夹到 0");
		assertEquals(63, MmtrMotionFrame.controlBrakeNotch(packed), "制动夹到 63");
		assertEquals(127, MmtrMotionFrame.controlDriveHandle(packed), "手柄夹到 +127");
		assertEquals(635, MmtrMotionFrame.controlCruiseKmh(packed), "定速夹到 635（=127×5）");
		assertEquals(-1, MmtrMotionFrame.controlReverser(packed), "换向取符号");
		assertEquals(3, MmtrMotionFrame.controlLightSwitch(packed), "灯夹到 3");
	}

	@Test
	public void stateRoundTripCarriesEveryFlagAndTheThreeClamps() {
		final int flags = MmtrMotionFrame.FLAG_MMTR_ACTIVE | MmtrMotionFrame.FLAG_MOTION_MIRROR | MmtrMotionFrame.FLAG_REVERSED
			| MmtrMotionFrame.FLAG_DOOR_TARGET | MmtrMotionFrame.FLAG_DOOR_LEFT | MmtrMotionFrame.FLAG_DOOR_RIGHT
			| MmtrMotionFrame.FLAG_DOOR_MANUAL | MmtrMotionFrame.FLAG_PINNED | MmtrMotionFrame.FLAG_PROTECTION
			| MmtrMotionFrame.FLAG_BLOCK_HELD | MmtrMotionFrame.FLAG_AWS_PENDING | MmtrMotionFrame.FLAG_AWS_ACKNOWLEDGED
			| MmtrMotionFrame.FLAG_CURRENTLY_MANUAL | MmtrMotionFrame.FLAG_AUTHORITY_TRIPPED;
		final List<Record> records = MmtrMotionFrame.decode(new MmtrMotionFrame.Writer().state(3, flags, 45_948.0, 50_000.0, 46_100.25).toCharArray());
		assertEquals(1, records.size());
		final State state = (State) records.get(0);
		assertEquals(3, state.slot());
		assertEquals(flags, state.flags(), "14 个旗标全带上");
		assertTrue(state.hasFlag(MmtrMotionFrame.FLAG_BLOCK_HELD), "闭塞扣车");
		assertEquals(45_948.0f, (float) state.runStopTarget(), "停车目标");
		assertEquals(50_000.0f, (float) state.runTotalDistance(), "本次运行总里程");
		assertEquals(46_100.25f, (float) state.blockStopM(), "闭塞停车点（客户端镜像以前根本没有这个数）");
	}

	@Test
	public void dropAndPingRoundTrip() {
		final List<Record> dropRecords = MmtrMotionFrame.decode(new MmtrMotionFrame.Writer().drop(42).toCharArray());
		assertEquals(1, dropRecords.size());
		assertEquals(42, ((Drop) dropRecords.get(0)).slot(), "掉线槽位");

		final long expected = 1_790_960_629_372L & 0xFFFFFFFFL;
		final List<Record> pingRecords = MmtrMotionFrame.decode(new MmtrMotionFrame.Writer().ping(1_790_960_629_372L).toCharArray());
		assertEquals(1, pingRecords.size());
		assertEquals(expected, ((Ping) pingRecords.get(0)).serverMillis(), "时钟对齐（低 32 位）");
	}

	@Test
	public void mixedFrameKeepsOrderAndCount() {
		final char[] frame = new MmtrMotionFrame.Writer()
			.motion(1, 100)
			.control(1, MmtrMotionFrame.packControl(3, 0, 50, 0, 1, false, 2))
			.state(1, MmtrMotionFrame.FLAG_MMTR_ACTIVE, 500, 1000, 600)
			.motion(2, 200)
			.drop(3)
			.ping(1234)
			.toCharArray();
		final MmtrMotionFrame.Reader reader = readerOf(frame);
		assertEquals(MmtrMotionFrame.VERSION, reader.version(), "版本");
		assertEquals(6, reader.remainingRecords(), "记录数");
		assertFalse(reader.isMalformed(), "好帧");
		assertEquals(MmtrMotionFrame.KIND_MOTION, reader.readNext().kind());
		assertEquals(MmtrMotionFrame.KIND_CONTROL, reader.readNext().kind());
		assertEquals(MmtrMotionFrame.KIND_STATE, reader.readNext().kind());
		assertEquals(MmtrMotionFrame.KIND_MOTION, reader.readNext().kind());
		assertEquals(MmtrMotionFrame.KIND_DROP, reader.readNext().kind());
		assertEquals(MmtrMotionFrame.KIND_PING, reader.readNext().kind());
		assertNull(reader.readNext(), "读完");
		assertFalse(reader.isMalformed(), "读完不算坏帧");
	}

	/** 字节数为奇数时尾部补一个 0 字节，权威长度由帧头里的 byteCount 决定。 */
	@Test
	public void oddLengthFramesPadTheLastCharAndStillRoundTrip() {
		final MmtrMotionFrame.Writer writer = new MmtrMotionFrame.Writer().drop(1).drop(2).ping(5);
		assertEquals(15, writer.byteCount(), "4 + 3 + 3 + 5 = 15 字节（奇数）");
		assertEquals(8, writer.charCount(), "打包成 8 个 16 位字");
		final List<Record> records = MmtrMotionFrame.decode(writer.toCharArray());
		assertEquals(3, records.size(), "三条都读得出来");
		assertEquals(1, ((Drop) records.get(0)).slot());
		assertEquals(2, ((Drop) records.get(1)).slot());
		assertEquals(5L, ((Ping) records.get(2)).serverMillis());
	}

	@Test
	public void unknownKindStopsCleanlyAndFlagsTheFrame() {
		final char[] frame = new MmtrMotionFrame.Writer().motion(1, 100).toCharArray();
		// 记录区从第 5 个字节开始 ⇒ char[2] 的高字节就是第一条记录的 kind
		frame[2] = (char) ((0x7F << 8) | (frame[2] & 0xFF));
		final MmtrMotionFrame.Reader reader = readerOf(frame);
		assertNull(reader.readNext(), "未知 kind ⇒ 停");
		assertTrue(reader.isMalformed(), "并且立一个红证（客户端据此计数）");
	}

	@Test
	public void truncatedFrameIsFlagged() {
		final char[] frame = new MmtrMotionFrame.Writer().motion(1, 100).motion(2, 200).toCharArray();
		frame[1] = (char) ((MmtrMotionFrame.VERSION << 8) | 4); // 声称有 4 条，实际 2 条
		final MmtrMotionFrame.Reader reader = readerOf(frame);
		assertEquals(1, ((Motion) reader.readNext()).slot(), "第一条读得出来");
		assertEquals(2, ((Motion) reader.readNext()).slot(), "第二条也读得出来");
		assertNull(reader.readNext(), "第三条没有字节了");
		assertTrue(reader.isMalformed(), "截断 = 红证");
	}

	@Test
	public void emptyFrameIsReadableAndNotMalformed() {
		final MmtrMotionFrame.Writer writer = new MmtrMotionFrame.Writer();
		assertTrue(writer.isEmpty(), "没记录");
		final MmtrMotionFrame.Reader reader = readerOf(writer.toCharArray());
		assertFalse(reader.isMalformed(), "空帧不是坏帧");
		assertEquals(0, reader.remainingRecords(), "0 条");
		assertNull(reader.readNext(), "立刻读完");
	}

	/** 量化口径：6 万米处 f32 的 ulp 必须还在厘米级以内（否则"位置"这条就没意义了）。 */
	@Test
	public void positionQuantizationStaysBelowOneCentimetre() {
		double worst = 0;
		for (double metres = 0; metres <= 60_000; metres += 137.13) {
			worst = Math.max(worst, Math.abs((float) metres - metres));
		}
		assertTrue(worst < 0.01, "6 万米处量化误差 " + worst + " m 必须 < 1 cm");
	}
}
