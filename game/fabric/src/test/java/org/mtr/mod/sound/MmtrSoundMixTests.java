package org.mtr.mod.sound;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MmtrSoundMix} 的回归：**混音表自洽** + **写在代码里的交付电平与磁盘上的音频一致**。
 *
 * <p>两条各自守一类错：</p>
 * <ol>
 *   <li>表自洽：出手量永不超过 1.0（"播放端只能衰减"这条硬性质）、三层同时到顶时**必须留余量**。
 *       以后谁把 {@code MASTER} 往上顶到削顶，这里先红。</li>
 *   <li>电平对账：{@link MmtrSoundMix#MOTOR_PEAK_DBFS} 那三个常数是**实测**来的，
 *       而"实测"是会过期的 —— 重烘焙一次（换编码口径、改末层、换素材）它们就旧了，
 *       旧了的后果是"混音表看起来对、耳朵不对"。所以这里拿
 *       {@code artifacts/kei2100-av417534959/} 下真正的源 WAV 逐条量一遍峰值去对。</li>
 * </ol>
 *
 * <p>没有那段素材时**跳过而不是失败**（与 {@code MmtrTractionSoundTests} 里那几条清单用例同一个口径）：
 * 素材属于 {@code artifacts/}（可再生、不入库），不该让别的机器上跑不过。</p>
 */
public final class MmtrSoundMixTests {

	private static final double PEAK_TOLERANCE_DB = 0.05;

	// ---- 表自洽 ------------------------------------------------------------------------------

	/**
	 * **层增益可以大于 1，但必须夹在 {@link MmtrSoundMix#MAX_LAYER_GAIN} 这道护栏以内。**
	 *
	 * <p>★ 这条用例原来断言"永不超过 1.0"，依据是"播放端只能衰减" —— 那个前提在 2026-10-08
	 * 被**实测推翻**了：OpenAL Soft（LWJGL 打包的那个，就是游戏用的）不夹 {@code AL_GAIN}，
	 * 写 8.0 读回 8.0（探针在 {@code sandbox/openal-gain-probe/}）。
	 * 现在上界是护栏 {@code MAX_LAYER_GAIN}，**削顶**由 {@link #worstCaseKeepsHeadroom} 那条守。</p>
	 */
	@Test
	public void layerGainsStayWithinTheGuardRail() {
		final double tractionFull = MmtrSoundMix.MASTER * MmtrSoundMix.TRACTION;
		assertEquals(tractionFull, MmtrSoundMix.tractionVolume(1.0), 1e-9, "满力 ⇒ 满层增益");
		assertEquals(0.0, MmtrSoundMix.tractionVolume(0.0), 1e-9, "没有牵引力 ⇒ 0");
		assertTrue(MmtrSoundMix.tractionVolume(0.5) < tractionFull, "半个力必须真的更小");
		assertEquals(MmtrSoundMix.MAX_LAYER_GAIN, MmtrSoundMix.tractionVolume(1000), 1e-9, "再大也夹在护栏上");
		assertTrue(tractionFull > 1.0,
				"牵引层基线必须**大于 1** —— 它就是「两种音效配比不一样」的那个旋钮"
						+ "（抬的是 −25.47 dBFS 那一层）");

		assertEquals(MmtrSoundMix.MASTER * MmtrSoundMix.TRACTION * MmtrSoundMix.GEAR,
				MmtrSoundMix.gearVolume(1.0), 1e-9, "齿轮层 = 力轴 × 牵引层 × 齿轮比例");
		assertEquals(MmtrSoundMix.MASTER * MmtrSoundMix.RUNNING,
				MmtrSoundMix.runningVolume(1.0, 1.0), 1e-9, "走行层：层内律 1 × 层刻度 1 ⇒ 总出手量 × 走行基线");
		assertEquals(MmtrSoundMix.MAX_LAYER_GAIN, MmtrSoundMix.runningVolume(1.0, 100), 1e-9, "层刻度再大也夹在护栏上");
	}

	/**
	 * **总出手量不许白丢分贝。**
	 *
	 * <p>它原来是 0.45 = 白丢 6.9 dB，而它**不承担任何平衡作用**（三层同乘）——
	 * 现场"声音不够大"里有一截就是它。这条用例把口径钉在 1.0：将来想压总音量，
	 * 得有意识地改这里并说明为什么，而不是顺手写一个小数。</p>
	 */
	@Test
	public void masterDoesNotThrowAwayDecibels() {
		assertEquals(1.0, MmtrSoundMix.MASTER, 1e-9,
				"总出手量必须顶到 1.0：写小于 1 的数就是白丢分贝（原是 0.45 = −6.9 dB）");
		assertEquals(0.0, MmtrSoundMix.gainDb(MmtrSoundMix.MASTER), 1e-9);
	}

	/**
	 * **最坏情况必须留余量。**
	 *
	 * <p>三层各自最响的那一档会同时出现（满力 + 高速），它们的峰叠加就是最坏情况 ——
	 * 而牵引层现在**带着一条 &gt;1 的层基线**，所以这条是抬响度时唯一的安全网。
	 * 留不到 3 dB 就说明有一级被顶过头了，现场表现是削顶失真，比"偏轻"难查得多。</p>
	 */
	@Test
	public void worstCaseKeepsHeadroom() {
		final double peak = MmtrSoundMix.worstCasePeak();
		System.out.println("[MmtrSoundMix] " + MmtrSoundMix.summary());
		assertTrue(peak < 1.0, "三层同时到顶不许削顶，实测 " + dbfs(peak) + " dBFS");
		assertTrue(MmtrSoundMix.headroomDb() > 3.0,
				"余量应当 > 3 dB，实测 " + round(MmtrSoundMix.headroomDb()) + " dB");
	}

	/**
	 * ★ **牵引层确实从它的烘焙电平上抬起来了** —— 这是「两种音效配比不一样」的修法读数。
	 *
	 * <p>两条一起守：① 有人把 {@link MmtrSoundMix#TRACTION} 悄悄改回 ≤1（配比又回去了）；
	 * ② 抬得不够（与走行层的差距没收窄）。抬得过头的削顶风险由
	 * {@link #worstCaseKeepsHeadroom} 那条拦。</p>
	 */
	@Test
	public void tractionLayerIsLiftedAboveItsBakedPeak() {
		assertEquals(MmtrSoundMix.MOTOR_PEAK_DBFS + MmtrSoundMix.gainDb(MmtrSoundMix.MASTER * MmtrSoundMix.TRACTION),
				MmtrSoundMix.tractionCeilingDbfs(), 1e-9,
				"牵引层的满力输出 = 烘焙峰值 + 总出手量 + 牵引层基线（三级相乘）");
		assertTrue(MmtrSoundMix.tractionCeilingDbfs() > MmtrSoundMix.MOTOR_PEAK_DBFS + 5,
				"牵引层必须比烘焙峰值高 5 dB 以上，实测高 "
						+ round(MmtrSoundMix.tractionCeilingDbfs() - MmtrSoundMix.MOTOR_PEAK_DBFS) + " dB");
		// 与走行层的差距：原来 14～15 dB（那一截就是现场说的"配比不一样"），现在必须收窄
		assertTrue(MmtrSoundMix.tractionCeilingDbfs() > MmtrSoundMix.ROLL_PEAK_DBFS - 10,
				"与轮轨层的差距必须收窄到 10 dB 以内，实测 "
						+ round(MmtrSoundMix.ROLL_PEAK_DBFS - MmtrSoundMix.tractionCeilingDbfs()) + " dB");
		assertTrue(MmtrSoundMix.tractionCeilingDbfs() > MmtrSoundMix.AERO_PEAK_DBFS - 10,
				"与风噪层的差距必须收窄到 10 dB 以内，实测 "
						+ round(MmtrSoundMix.AERO_PEAK_DBFS - MmtrSoundMix.tractionCeilingDbfs()) + " dB");
	}

	// ---- 电平对账（拿磁盘上真正的音频量一遍） ----------------------------------------------------

	/**
	 * **写在代码里的交付电平，与 {@code artifacts/} 下真正的源 WAV 一致。**
	 *
	 * <p>量的就是"各层最响那一档"的峰值，与 {@link MmtrSoundMix} 里那三个常数的定义逐字对应。
	 * 重烘焙之后这条会红 —— 那是**要的**：红了就说明混音表里的数已经不是现场听到的数了。</p>
	 */
	@Test
	public void declaredLayerPeaksMatchTheDeliveredAudio() throws IOException {
		final Path artifacts = findUp("artifacts/kei2100-av417534959");
		Assumptions.assumeTrue(artifacts != null, "没有 artifacts/kei2100-av417534959（素材可再生，跳过）");

		assertPeakMatches("motor", MmtrSoundMix.MOTOR_PEAK_DBFS,
				artifacts.resolve("game"), "kei2100-upd-step%02d.wav");
		assertPeakMatches("roll", MmtrSoundMix.ROLL_PEAK_DBFS,
				artifacts.resolve("game").resolve("run"), "roll-step%02d.wav");
		assertPeakMatches("aero", MmtrSoundMix.AERO_PEAK_DBFS,
				artifacts.resolve("game").resolve("run"), "aero-step%02d.wav");
	}

	/** 一层 23 档：取最响那一档的峰值，与声明的常数比。 */
	private static void assertPeakMatches(String layer, double declaredDbfs, Path dir, String template) throws IOException {
		double loudest = Double.NEGATIVE_INFINITY;
		int seen = 0;
		for (int step = 1; step <= 23; step++) {
			final Path wav = dir.resolve(String.format(Locale.ROOT, template, step));
			if (!Files.isRegularFile(wav)) {
				continue;
			}
			loudest = Math.max(loudest, peakDbfs(Files.readAllBytes(wav)));
			seen++;
		}
		Assumptions.assumeTrue(seen > 0, "没有 " + layer + " 层的源 WAV（跳过）");
		assertEquals(declaredDbfs, loudest, PEAK_TOLERANCE_DB,
				String.format(Locale.ROOT,
						"%s 层实测最响峰值 %.2f dBFS 与 MmtrSoundMix 里声明的 %.2f dBFS 不符"
								+ "（重烘焙过就得回去改那个常数，否则混音表已经不是现场的数了）",
						layer, loudest, declaredDbfs));
	}

	/**
	 * 16 bit PCM WAV 的峰值（dBFS）。
	 *
	 * <p>自己走一遍 RIFF 块，而不是 {@code javax.sound.sampled}：那边要 {@code AudioSystem}
	 * 与真实音频设备栈，在无头/离线 runner 上是负担；这里只要 {@code data} 块的短整型样本。</p>
	 */
	private static double peakDbfs(byte[] wav) {
		final ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
		int dataOffset = -1;
		int dataLength = 0;
		for (int offset = 12; offset + 8 <= wav.length; ) {
			final String id = new String(wav, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
			final int size = buffer.getInt(offset + 4);
			if ("data".equals(id)) {
				dataOffset = offset + 8;
				dataLength = Math.min(size, wav.length - dataOffset);
				break;
			}
			offset += 8 + size + (size & 1);   // 块按偶数字节对齐
		}
		if (dataOffset < 0) {
			throw new IllegalArgumentException("找不到 data 块");
		}
		int peak = 0;
		for (int i = 0; i + 1 < dataLength; i += 2) {
			final int sample = buffer.getShort(dataOffset + i);
			peak = Math.max(peak, Math.abs(sample));
		}
		return peak == 0 ? Double.NEGATIVE_INFINITY : 20 * Math.log10(peak / 32768.0);
	}

	private static String dbfs(double linear) {
		return String.format(Locale.ROOT, "%.2f", MmtrSoundMix.gainDb(linear));
	}

	private static String round(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	/** 从当前目录往上找某个相对路径（gradle 与离线 runner 的 cwd 都是 {@code fabric/}）。 */
	private static Path findUp(String relative) {
		Path dir = Path.of("").toAbsolutePath();
		for (int i = 0; i < 8 && dir != null; i++) {
			final Path path = dir.resolve(relative).normalize();
			if (Files.isDirectory(path)) {
				return path;
			}
			dir = dir.getParent();
		}
		return null;
	}
}
