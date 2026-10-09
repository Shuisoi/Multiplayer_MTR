package org.mtr.mod.sound;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.sound.MmtrTractionSoundModel.Entry;
import org.mtr.mod.sound.MmtrTractionSoundModel.RunningEntry;
import org.mtr.mod.sound.MmtrTractionSoundModel.RunningLayer;
import org.mtr.mod.sound.MmtrTractionSoundModel.RunningSelection;
import org.mtr.mod.sound.MmtrTractionSoundModel.Selection;
import org.mtr.mod.sound.MmtrTractionSoundModel.SoundSet;
import org.mtr.mod.sound.MmtrTractionSoundModel.Spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 游戏端牵引音模型的用例。
 *
 * <p>对表基准与 JS 侧的 {@code mmtr/tools/sound-studio/selftest.mjs} **刻意一致**：
 * 西门子「電車制御時の発生騒音」(騒音制御 29(4), 2005) 的 v→f₁ 公式、
 * 日立評論 61(5) 1979 的滑差符号与 4.5 Hz、E531 实测 31 km/h 切换点。
 * 两边用同一批有出处的数，才谈得上"两套实现各说各话时能红"。</p>
 *
 * <p>最后一条（{@link #bakedGridStaysWithinPitchBudget()}）是本类里最重要的一条：
 * 它拿**真实烘焙出来的清单**跑一遍全速度域，验证游戏端的变调量始终落在预算内、
 * 且不会被 Minecraft 的 pitch 夹取动到。那是"离线烘焙 + 在线挑档"这条路线成立的前提。</p>
 */
public final class MmtrTractionSoundTests {

	private static final double EPS = 1e-9;

	// ---- 有出处的对表向量 --------------------------------------------------------------------

	@Test
	public void rotorHzPerKmhMatchesSiemensExample() {
		// 西门子论文原例：4 极、齿轮比 5.93、轮径 820 mm。论文写 80 km/h ≈ 102 Hz。
		final double k = MmtrTractionSoundModel.rotorHzPerKmh(4, 5.93, 0.82);
		assertEquals(1.2788, k, 1e-3, "西门子原例 k");
		assertEquals(102.3, k * 80, 0.3, "西门子原例 80 km/h 的 f₁");
	}

	@Test
	public void rotorHzPerKmhMatchesE531CrossCheck() {
		// E531 实测：异步→同步切换在 31 km/h，机械转速 19.3 Hz ⇒ 电气 38.7 Hz（4 极，极对数 2）
		final double k = MmtrTractionSoundModel.rotorHzPerKmh(4, 6.0625, 0.86);
		assertEquals(1.2466, k, 1e-3, "E531 k");
		assertEquals(38.64, k * 31, 0.2, "E531 31 km/h 的 f₁ = 19.3 Hz 机械 × 2");
	}

	@Test
	public void slipIsAddedWhenPoweringAndSubtractedWhenRegenerating() {
		// 日立 1979：「力行時には加算し、回生制動時には減算する」
		final double k = 1.2788;
		final double rotor = 60 * k;
		assertEquals(rotor + 4.5, MmtrTractionSoundModel.fundamentalHz(60, 1, k, 4.5), 1e-9, "牵引 = 转频 + 滑差");
		assertEquals(rotor - 4.5, MmtrTractionSoundModel.fundamentalHz(60, -1, k, 4.5), 1e-9, "再生 = 转频 − 滑差");
		assertTrue(MmtrTractionSoundModel.fundamentalHz(60, 1, k, 4.5) > MmtrTractionSoundModel.fundamentalHz(60, -1, k, 4.5),
				"同速度下牵引 f₁ 必须高于再生");
	}

	@Test
	public void coastingHasNoStatorFundamental() {
		// 惰行时逆变器不励磁 —— 没有"定子基波"这回事，只该有走行音（走行音归采样层）
		assertEquals(0, MmtrTractionSoundModel.fundamentalHz(100, 0, 1.2788, 4.5), EPS);
	}

	@Test
	public void standstillWithNotchOpenStillHasAStartingTone() {
		// ★ 只用（功率 × 速度）两轴的话这里会是 0 —— 静止时 P = F·v ≡ 0。
		// 有励磁时 f₁ = 滑差，这就是"起步那一下"的声音来源。
		assertEquals(4.5, MmtrTractionSoundModel.fundamentalHz(0, 1, 1.2788, 4.5), EPS);
	}

	/**
	 * ★ **两个轴正交**（notes/403 续）：**速度只决定"播哪一段"，牵引力只决定"播多大"**。
	 *
	 * <p>这一条是用户口径的直接形式化，同时也是一道回归闸门 —— 它挡住"旧负载轴那种串门"再回来：
	 * 旧律 {@code max(|P|/P_max, ratio·exp(−v/vBlend))} 里带着 v，于是**同一个牵引力、
	 * 不同速度音量不同**；而且低速段还被音阶门限**按速度把音量压成 0**。</p>
	 */
	@Test
	public void segmentFollowsSpeedAndVolumeFollowsForce() {
		// 交付的那套音效集 slipHz = 0 ⇒ 选段严格只由速度定。这里用同一组数造一份等价的 spec。
		final Spec spec = new Spec(4, 5.93, 0.82, 0.0, 0, 250, 1045, 1.2, 76.8, 300_000, 6_400_000, 8);
		final SoundSet set = new SoundSet(spec, List.of(
				new Entry("motor01.ogg", 6, "async", 0, 1000),
				new Entry("motor02.ogg", 20, "async", 0, 1000),
				new Entry("motor03.ogg", 60, "async", 0, 1000)));

		// ---- 选段：30 km/h 上，**所有非零牵引力**（半/满/再生）都必须落到同一段 ----
		for (final double force : new double[]{50_000, 150_000, 300_000, -50_000, -300_000}) {
			final double ratio = MmtrTractionSoundModel.forceAxis(force, spec);
			final Selection selection = MmtrTractionSoundModel.selectFor(30, force * (30 / 3.6), ratio, set);
			assertNotNull(selection, "30 km/h + 非零牵引力必须选得出段（清单可用、门限为 0）");
			assertEquals("motor02.ogg", selection.entry().file(),
					"选段只由速度定：30 km/h 上不管牵引力多大、正负，都应当是同一段");
		}
		// 力 = 0 是**惰行**：没有励磁就没有定子基波 ⇒ 选不出段。
		// 这不是"按速度关音量"，而是"根本没有这一段"（物理）。
		assertNull(MmtrTractionSoundModel.selectFor(30, 0, 0, set), "惰行 ⇒ 没有基波 ⇒ 没有段");

		// ---- 音量：只由牵引力定（签名里根本没有速度，这里把数值也钉住） ----
		// "满"是**层增益**（总出手量 × 牵引层基线），不是 1.0 —— 基线可以大于 1（notes/403 实测纠正）
		final double full = MmtrSoundMix.MASTER * MmtrSoundMix.TRACTION;
		assertEquals(0.0, MmtrSoundMix.tractionVolume(MmtrTractionSoundModel.forceAxis(0, spec)), EPS, "没有牵引力 ⇒ 音量 0");
		assertEquals(full, MmtrSoundMix.tractionVolume(MmtrTractionSoundModel.forceAxis(300_000, spec)), EPS, "额定牵引力 ⇒ 满层增益");
		assertEquals(full, MmtrSoundMix.tractionVolume(MmtrTractionSoundModel.forceAxis(-300_000, spec)), EPS, "再生同样大小 ⇒ 满层增益");
		assertEquals(full / 2, MmtrSoundMix.tractionVolume(MmtrTractionSoundModel.forceAxis(150_000, spec)), EPS, "半额定 ⇒ 一半");

		// 力轴自身：0 ⇒ 0、额定 ⇒ 1、超出夹到 1
		assertEquals(0.0, MmtrTractionSoundModel.forceAxis(0, spec), EPS);
		assertEquals(1.0, MmtrTractionSoundModel.forceAxis(spec.maxTractiveEffortN(), spec), EPS);
		assertEquals(1.0, MmtrTractionSoundModel.forceAxis(spec.maxTractiveEffortN() * 2, spec), EPS, "超出额定夹到 1");

		// ---- "有没有励磁"现在只看牵引力 ----
		assertTrue(!MmtrTractionSoundModel.isExcited(0), "没有牵引力 ⇒ 没励磁");
		assertTrue(MmtrTractionSoundModel.isExcited(0.5), "有牵引力 ⇒ 在励磁");

		// ---- ★ 唯一一处"力会影响选段"的地方，而且它是物理不是混音 ----
		// 带滑差的音效集：力行 f₁ = 转频 + 滑差、回生 f₁ = 转频 − 滑差 ⇒ 频线不同 ⇒ 可能落到不同的段。
		final Spec slipSpec = specOf(1.2);   // slipHz = 4.5
		final SoundSet slipSet = new SoundSet(slipSpec, List.of(
				new Entry("a.ogg", 20, "async", 0, 1000),
				new Entry("b.ogg", 60, "async", 0, 1000)));
		assertNotEquals(
				MmtrTractionSoundModel.selectFor(30, 300_000 * (30 / 3.6), 1.0, slipSet).entry().file(),
				MmtrTractionSoundModel.selectFor(30, -300_000 * (30 / 3.6), 1.0, slipSet).entry().file(),
				"带滑差时力行/回生的频线不同（40.5 vs 31.5 Hz）⇒ 段可以不同；这是物理，不是音量串门");

		// ---- ★ 低速段实打实地抬起来了（这正是"低速听不到励磁音"的修法） ----
		final double force = spec.maxTractiveEffortN();
		final double vMps = 20 / 3.6;
		final double oldLaw = Math.max(Math.abs(force * vMps) / spec.maxPowerW(), Math.exp(-vMps / 8));
		final double newLaw = MmtrSoundMix.tractionVolume(MmtrTractionSoundModel.forceAxis(force, spec));
		assertEquals(full, newLaw, EPS, "满级位 ⇒ 满层增益（与速度无关）");
		assertEquals(full, MmtrSoundMix.tractionVolume(MmtrTractionSoundModel.forceAxis(force, spec)),
				EPS, "换个速度再算一次，必须一模一样 —— 音量里没有速度这项");
		assertTrue(newLaw > oldLaw * 1.5,
				String.format("20 km/h 满级位：旧律 %.3f ⇒ 新律 %.3f（应当明显更高）", oldLaw, newLaw));
	}

	@Test
	public void selectClampsIntoTheEnginePitchRange() {
		final SoundSet set = new SoundSet(specOf(1.2788), List.of(new Entry("a.ogg", 100, "7-pulse", 7, 900)));
		// 远超范围的 f₁ 会被夹进 Minecraft 的 [0.5, 2.0]，宁可音高不准也不要非线性失真
		assertEquals(MmtrTractionSoundModel.MAX_PITCH, MmtrTractionSoundModel.select(set, 5000).rate(), EPS);
		assertEquals(MmtrTractionSoundModel.MIN_PITCH, MmtrTractionSoundModel.select(set, 1).rate(), EPS);
		assertNull(MmtrTractionSoundModel.select(set, 0), "f₁ = 0 应当没有选中项");
	}

	@Test
	public void selectionCarriesTheFundamentalItUsed() {
		// 换档日志打的就是这个数。它必须是**入参那个 f₁**，而不是播放器另算的一个 ——
		// 否则日志与听感对不上，而"对表"正是靠这条日志。
		final SoundSet set = new SoundSet(specOf(1.2788), List.of(
				new Entry("a.ogg", 50, "async", 0, 300),
				new Entry("b.ogg", 120, "7-pulse", 7, 840)));
		assertEquals(50, MmtrTractionSoundModel.select(set, 50).f1Hz(), EPS, "恰好落在档上");
		assertEquals(61.5, MmtrTractionSoundModel.select(set, 61.5).f1Hz(), EPS, "落在两档之间时带的是入参");
		assertEquals(5000, MmtrTractionSoundModel.select(set, 5000).f1Hz(), EPS, "被夹取时也照带");
	}

	// ---- 真实烘焙清单：游戏端路线成立的前提 --------------------------------------------------

	@Test
	public void bakedGridStaysWithinPitchBudget() throws IOException {
		final Path manifest = findManifest();
		Assumptions.assumeTrue(manifest != null,
				"没有烘焙清单（先跑 node mmtr/tools/sound-studio/bake.mjs generic sandbox/sound-studio/final2s --seconds 2 --taperms 8）");
		final SoundSet set = readManifest(manifest);
		assertTrue(set.usable(), "清单必须可用（非空且 k > 0）");

		double worstRate = 1;
		double worstKmh = 0;
		String worstSide = "";
		for (final int sign : new int[]{1, -1}) {
			for (double kmh = 0; kmh <= 220; kmh += 0.25) {
				final double f1 = MmtrTractionSoundModel.fundamentalHz(kmh, sign, set.spec());
				if (!(f1 > 0)) {
					/*
					 * 两种"该静音"的情形，都不是漏档：
					 *  · 再生制动在**切除速度**以下根本不投入（BVE 的 RegenerationLimit / 电制动低速淡出）；
					 *  · 再生时转频还没长到滑差那么大（f₁ = max(0, 转频 − 滑差)）。
					 */
					assertTrue(sign < 0, "只有再生制动允许 f₁ = 0，牵引不该出现（" + kmh + " km/h）");
					assertNull(MmtrTractionSoundModel.select(set, f1), "f₁ = 0 时不该选中任何档");
					continue;
				}
				final Selection selection = MmtrTractionSoundModel.select(set, f1);
				assertNotNull(selection, "速度 " + kmh + " 应当能选中一档");
				assertTrue(selection.withinEngineClamp(),
						"速度 " + kmh + " 的变调 " + selection.rate() + " 落到 Minecraft 的 pitch 夹取范围之外");
				final double deviation = Math.abs(selection.rate() - 1);
				if (deviation > Math.abs(worstRate - 1)) {
					worstRate = selection.rate();
					worstKmh = kmh;
					worstSide = sign > 0 ? "牵引" : "再生";
				}
			}
		}
		assertTrue(Math.abs(worstRate - 1) <= MmtrTractionSoundModel.PITCH_BUDGET,
				"最差变调 " + worstRate + "（" + worstSide + " " + worstKmh + " km/h）超出 ±"
						+ (MmtrTractionSoundModel.PITCH_BUDGET * 100) + "% 预算 —— 档位铺得不够密，重跑 bake.mjs 并调小 --maxstep");
	}

	@Test
	public void bakedEntriesCoverLowSpeedAndAscendWithSpeed() throws IOException {
		final Path manifest = findManifest();
		Assumptions.assumeTrue(manifest != null, "没有烘焙清单");
		final SoundSet set = readManifest(manifest);

		// 起点必须低到能覆盖"起步"（f₁ = 滑差 ≈ 4.5 Hz），否则起步落在很远的档上、变调严重
		final double lowest = set.entries().stream().mapToDouble(Entry::f1Hz).min().orElse(0);
		assertTrue(lowest <= set.spec().slipHz() * 1.5,
				"最低档 f₁ = " + lowest + " Hz 高于起步所需的滑差量级（" + set.spec().slipHz() + " Hz）");

		// 选中的档必须随速度单调不降 —— 破了说明清单顺序或查找有问题（听感上就是音高来回跳）
		double lastF1 = -1;
		for (double kmh = 0; kmh <= 220; kmh += 1) {
			final double f1 = MmtrTractionSoundModel.fundamentalHz(kmh, 1, set.spec());
			final Selection selection = MmtrTractionSoundModel.select(set, f1);
			assertNotNull(selection);
			assertTrue(selection.entry().f1Hz() >= lastF1 - 1e-9,
					"速度升高时选中的档回落了：在 " + kmh + " km/h 由 " + lastF1 + " 落到 " + selection.entry().f1Hz());
			lastF1 = selection.entry().f1Hz();
		}
	}

	// ---- 清单解析：生产用的 Gson 路径 vs 用例里的正则扫描 ------------------------------------

	/**
	 * 内联的最小清单：字段名与 bake.mjs 的产物一致。这样即使工作区里没有烘焙产物，
	 * "清单 → 模型 + 事件 id"这条契约也一直有回归。
	 */
	private static final String MINIMAL_MANIFEST = """
			{
			  "format": "mmtr-traction-sound/1",
			  "namespace": "mtr",
			  "baseDir": "sounds/demo",
			  "spec": { "poleCount": 4, "gearRatio": 6.0625, "wheelDiameterM": 0.86,
			            "slipHz": 4.5, "regenCutoffKmh": 27, "carrierMinHz": 250,
			            "carrierMaxHz": 1045, "rotorHzPerKmh": 1.2466, "breakpointKmh": 76.8,
			            "maxTractiveEffortN": 300000, "maxPowerW": 6400000 },
			  "entries": [
			    { "file": "a.ogg", "eventId": "demo_a", "f1Hz": 4.5, "mode": "async", "pulses": 0, "carrierHz": 250 },
			    { "file": "b.ogg", "eventId": "demo_b", "f1Hz": 120.0, "mode": "7-pulse", "pulses": 7, "carrierHz": 840 }
			  ]
			}
			""";

	@Test
	public void manifestParserReadsTheContractFields() {
		final MmtrTractionSoundSet.ParsedManifest parsed = MmtrTractionSoundSet.parseManifest("demo", MINIMAL_MANIFEST);

		assertEquals("mtr", parsed.namespace(), "命名空间");
		assertEquals("sounds/demo", parsed.baseDir(), "音频前缀");
		assertEquals(2, parsed.model().entries().size(), "档数");
		assertEquals("demo_a", parsed.eventIds().get(0), "事件 id 必须照清单读，不是按命名规则拼");
		assertEquals("demo_b", parsed.eventIds().get(1));
		assertEquals(4, parsed.model().spec().poleCount());
		assertEquals(4.5, parsed.model().spec().slipHz(), EPS);
		assertEquals(27, parsed.model().spec().regenCutoffKmh(), EPS);
		assertEquals(6.0625, parsed.model().spec().gearRatio(), EPS);
		assertEquals(7, parsed.model().entries().get(1).pulses());
		assertEquals(120.0, parsed.model().entries().get(1).f1Hz(), EPS);
		// 解析出来的模型必须能直接用：4.5 Hz 落在第 0 档上、变调为 1
		final Selection selection = MmtrTractionSoundModel.select(parsed.model(), 4.5);
		assertNotNull(selection);
		assertEquals(1.0, selection.rate(), EPS);
	}

	@Test
	public void bothManifestReadersAgreeOnTheRealBakedFile() throws IOException {
		final Path manifest = findManifest();
		Assumptions.assumeTrue(manifest != null, "没有烘焙清单");
		final String text = Files.readString(manifest, StandardCharsets.UTF_8);

		/*
		 * 这里有**两条**读清单的路：
		 *   · 生产路径 MmtrTractionSoundSet.parseManifest（Gson）；
		 *   · 本用例里的正则扫描 readManifest（刻意不引 Gson）。
		 * 它们分歧过一次就会是"模型算得对、进游戏却没声音"这类最难查的现场，
		 * 所以拿真实清单把两者对一遍 —— 档数、f₁、事件 id 三者必须逐条相等。
		 */
		final MmtrTractionSoundSet.ParsedManifest viaGson = MmtrTractionSoundSet.parseManifest("saf420", text);
		final SoundSet viaScan = readManifest(manifest);

		assertEquals(viaScan.entries().size(), viaGson.model().entries().size(), "两条路径读出的档数必须一致");
		assertTrue(viaScan.entries().size() > 0, "清单不该是空的");
		for (int i = 0; i < viaScan.entries().size(); i++) {
			final Entry scan = viaScan.entries().get(i);
			final Entry gson = viaGson.model().entries().get(i);
			assertEquals(scan.file(), gson.file(), "第 " + i + " 条的文件名");
			assertEquals(scan.f1Hz(), gson.f1Hz(), 1e-9, "第 " + i + " 条的 f1Hz");
			assertEquals(scan.mode(), gson.mode(), "第 " + i + " 条的模式");
		}
		// 事件 id 也必须真的带上了（缺了就是"登记了事件名、游戏里却拼不出来"）。
		// 前缀不硬编码：烘焙器用的是 `--base`，这里只要求 id 由**档名**收尾。
		for (int i = 0; i < viaGson.eventIds().size(); i++) {
			final String stem = viaGson.model().entries().get(i).file().replace(".ogg", "");
			assertTrue(viaGson.eventIds().get(i).endsWith(stem),
					"第 " + i + " 条的事件 id 应当由档名收尾：" + viaGson.eventIds().get(i) + " vs " + stem);
		}
		assertNull(MmtrTractionSoundModel.select(viaGson.model(), 0), "f₁ = 0 不该选中档");
	}

	// ---- 齿轮层（第二层，独立变调基准） --------------------------------------------------------

	@Test
	public void gearRateIsStrictlyProportionalToSpeed() {
		final MmtrTractionSoundModel.GearLayer gear =
				new MmtrTractionSoundModel.GearLayer("gear.ogg", 100, 127.88, 16, 1023.05, 50, 200);
		// 参考速度处 rate = 1（原调），速度翻倍 rate 翻倍 —— 因为它跟的是机械转频
		assertEquals(1, MmtrTractionSoundModel.gearRate(100, gear), EPS);
		assertEquals(0.6, MmtrTractionSoundModel.gearRate(60, gear), EPS);
		assertEquals(2.0, MmtrTractionSoundModel.gearRate(200, gear), EPS);
		assertEquals(0, MmtrTractionSoundModel.gearRate(-5, gear), EPS, "负速度按 0 处理");
	}

	@Test
	public void gearLayerGoesSilentRatherThanPlayingAWrongPitch() {
		final MmtrTractionSoundModel.GearLayer gear =
				new MmtrTractionSoundModel.GearLayer("gear.ogg", 100, 127.88, 16, 1023.05, 50, 200);
		// 区间内出声
		assertTrue(MmtrTractionSoundModel.gearAudible(50, gear), "下边界应当在区间内");
		assertTrue(MmtrTractionSoundModel.gearAudible(200, gear), "上边界应当在区间内");
		// 出区间**静音**而不是夹取：音高错的齿轮音比没有更难听，而且夹取会让音高与转频脱节
		assertTrue(!MmtrTractionSoundModel.gearAudible(49.9, gear), "低于下界 ⇒ 静音（不夹取到 0.5）");
		assertTrue(!MmtrTractionSoundModel.gearAudible(200.1, gear), "高于上界 ⇒ 静音");
		assertTrue(!MmtrTractionSoundModel.gearAudible(0, gear), "静止 ⇒ 静音");
		// 没有齿轮层的音效集：不能 NPE，且判定为不出声
		assertTrue(!MmtrTractionSoundModel.gearAudible(100, null), "没有齿轮层 ⇒ 不出声");
		assertEquals(1, MmtrTractionSoundModel.gearRate(100, null), EPS, "没有齿轮层时给个中性值，不能除零");
	}

	@Test
	public void manifestParserReadsTheGearSection() {
		// 齿轮段是可选的：没有它时 SoundSet.gear 必须是 null（旧清单、纯牵引用例都走这条）
		final MmtrTractionSoundSet.ParsedManifest sans = MmtrTractionSoundSet.parseManifest("saf420", MINIMAL_MANIFEST);
		assertNull(sans.gearEventId(), "最小清单里没有齿轮层 ⇒ gearEventId 应当是 null");
		assertTrue(!sans.model().hasGear(), "最小清单里没有齿轮层");
		assertTrue(!MmtrTractionSoundModel.gearAudible(100, sans.model().gear()), "没有齿轮层 ⇒ 不出声");

		final String withGear = MINIMAL_MANIFEST.replace("\"entries\"",
				"\"gear\": {\"file\": \"gear.ogg\", \"eventId\": \"saf420_gear\", \"refSpeedKmh\": 100, "
						+ "\"rotorRefHz\": 127.88, \"teeth\": 16, \"gearMeshHz\": 1023.05, \"minKmh\": 50, \"maxKmh\": 200}, \"entries\"");
		final MmtrTractionSoundSet.ParsedManifest parsed = MmtrTractionSoundSet.parseManifest("saf420", withGear);
		assertEquals("saf420_gear", parsed.gearEventId(), "齿轮事件 id 要照清单读");
		assertTrue(parsed.model().hasGear(), "齿轮层要读出来");
		assertEquals(100, parsed.model().gear().refSpeedKmh(), EPS);
		assertEquals(16, parsed.model().gear().teeth());
		assertEquals(50, parsed.model().gear().minKmh(), EPS);
		assertEquals(200, parsed.model().gear().maxKmh(), EPS);
	}

	@Test
	public void realBakedGearLayerIsRegisteredAsASoundEvent() throws IOException {
		final Path manifest = findManifest();
		Assumptions.assumeTrue(manifest != null, "没有烘焙清单");
		final MmtrTractionSoundSet.ParsedManifest parsed = MmtrTractionSoundSet.parseManifest("saf420",
				Files.readString(manifest, StandardCharsets.UTF_8));
		Assumptions.assumeTrue(parsed.model().hasGear(), "这份清单没有齿轮层");

		/*
		 * ★ 这条守的是一个**会静默失败**的坑：清单里有 gear 段，但对应事件没登记进 sounds.json。
		 * 那样 MmtrTractionSoundSet.load 建齿轮 SoundEvent 时抛异常，被 catch 抓住并写成 error 日志，
		 * 于是**整个音效集退回 BVE/legacy** —— 现场表现只是"没声音"，而清单看起来完全正常。
		 * 所以这里把清单里的 gear 事件 id 与 sounds.json 片段对一遍。
		 */
		final Path sidecar = manifest.resolveSibling("mmtr_traction.sounds.json");
		Assumptions.assumeTrue(Files.exists(sidecar), "没有 sounds.json 片段");
		final String registry = Files.readString(sidecar, StandardCharsets.UTF_8);
		assertTrue(registry.contains("\"" + parsed.gearEventId() + "\""),
				"清单声明了齿轮事件 " + parsed.gearEventId() + "，但 sounds.json 片段里没有它");
		// 指向的路径也要对：清单里的 baseDir + 文件名（去扩展名）必须出现在登记项里。
		// 注意不能只找 ":" + stem —— 登记的是 `mtr:sounds/saf420/gear`，`:gear` 是匹配不上的。
		final String stem = parsed.model().gear().file().replace(".ogg", "");
		assertTrue(registry.contains(parsed.baseDir() + "/" + stem),
				"齿轮事件指向的路径与文件名对不上：期望包含 " + parsed.baseDir() + "/" + stem);
		assertTrue(parsed.model().gear().teeth() > 0, "真实清单里齿数应当 > 0");
		assertEquals(100, parsed.model().gear().refSpeedKmh(), EPS,
				"参考速度应当是 100 km/h（这样 playbackRate 恰好等于 速度/100，严格线性）");
	}

	// ---- 拖车静音：三态口径（notes/271） ------------------------------------------------------

	@Test
	public void hauledWagonsAreMutedButUndeclaredCarsAreNot() {
		// 显式声明无动力（挂车）⇒ 静音
		assertTrue(MmtrTractionSoundModel.shouldMuteMotorSound(true, false), "声明过且无动力 ⇒ 静音");
		// 显式声明有动力 ⇒ 不静音
		assertTrue(!MmtrTractionSoundModel.shouldMuteMotorSound(true, true), "声明过且有动力 ⇒ 不静音");
		// ★没声明过 ⇒ **不静音**。这一条是关键：没配车底清单的老车（BR101 那类）读出来也是 false，
		// 把"没声明"当成"无动力"会把整批车静音，而且现场只会表现为"没声音"，极难查。
		assertTrue(!MmtrTractionSoundModel.shouldMuteMotorSound(false, false), "没声明过 ⇒ 按有动力处理，不静音");
		assertTrue(!MmtrTractionSoundModel.shouldMuteMotorSound(false, true), "没声明过 ⇒ 不静音");
	}

	/**
	 * ★ **操控位豁免**（notes/403 续）：这一条是"没有电机励磁音"那个现场口径的修法。
	 *
	 * <p>SAF420 编组是 {@code Tc–M×6–T×2–Tc}，**司机坐的正好是 Tc 控制拖车**。按"挂车一律静音"办，
	 * 司机所在的那一节就被静音，而真正响的 6 节动车在 20–125 m 外、又被距离衰减削掉 ⇒
	 * 司机听到的是一片安静。判据改成"谁在操纵，谁就听得到"。</p>
	 */
	@Test
	public void theCarTheDriverIsOperatingIsNeverMuted() {
		// 挂车 + 没人在操纵 ⇒ 静音（被牵着走的货车仍然对）
		assertTrue(MmtrTractionSoundModel.muteTractionSound(true, false), "挂车、无人在操纵 ⇒ 静音");
		// ★ 挂车 + 人在这一节的操纵位上 ⇒ **不静音**（司机在 Tc 控制车里就是这一格）
		assertTrue(!MmtrTractionSoundModel.muteTractionSound(true, true),
				"听者就在这一节的操纵位上 ⇒ 即便它是 Tc 拖车也不静音（否则司机听不到牵引音）");
		// 不是挂车 ⇒ 与豁免无关，一律不静音
		assertTrue(!MmtrTractionSoundModel.muteTractionSound(false, false));
		assertTrue(!MmtrTractionSoundModel.muteTractionSound(false, true));
	}

	// ---- 走行层（第 7 轮：由速度强绑定的轮轨 + 风噪） ------------------------------------------

	/**
	 * 与 {@code artifacts/kei2100-av417534959/game/select_running.py} 的 {@code running_state(v)}
	 * **逐位对表**：下面 13 行是它打印出来的（{@code v, 档序, 烘点, roll 变调, roll 音量,
	 * 风噪变调, 风噪音量}）。
	 *
	 * <p>为什么必须对表而不是各测各的：这条链的另一半在 Python 里（离线烘焙 + 纯函数契约），
	 * 两边一旦分叉，现场表现是"声音还在、只是与设计不符"，最难发现。
	 * 这也是本仓对"两处各算一套"的一贯做法。</p>
	 */
	@Test
	public void runningLayerMatchesThePythonContract() {
		final RunningLayer roll = runningLayer("roll", 30, true);
		final RunningLayer aero = runningLayer("aero", 60, false);
		final double[][] vectors = {
				// v,      档, vBake,      rate_roll,    vol_roll,     rate_aero, vol_aero
				{5.000000, 1, 5.346419, 0.935205340, 0.904399819, 1.0, 0.817939033},
				{5.346419, 2, 6.237489, 0.857142788, 0.793559990, 1.0, 0.629737458},
				{6.200000, 3, 7.128559, 0.869740966, 0.811119589, 1.0, 0.657914987},
				{8.000000, 5, 9.356234, 0.855044882, 0.790648348, 1.0, 0.625124810},
				{12.000000, 7, 12.474979, 0.961925493, 0.943435364, 1.0, 0.890070287},
				{20.000000, 11, 22.722283, 0.880193261, 0.825785131, 1.0, 0.681921083},
				{33.632000, 14, 36.533866, 0.920570517, 0.883253958, 1.0, 0.780137555},
				{60.000000, 18, 69.503453, 0.863266468, 0.802079299, 1.0, 0.643331201},
				{89.002000, 20, 96.681085, 0.920573040, 0.883257589, 1.0, 0.780143969},
				{120.000000, 22, 134.551556, 0.891851450, 0.842245665, 1.0, 0.709377759},
				{130.017000, 23, 141.234580, 0.920574834, 0.883260172, 1.0, 0.780148531},
				{141.234580, 23, 141.234580, 1.000000001, 1.000000001, 1.0, 1.000000002},
		};
		for (final double[] row : vectors) {
			final double v = row[0];
			final RunningSelection r = MmtrTractionSoundModel.selectRunning(roll, v);
			assertNotNull(r, "roll 层在 " + v + " km/h 应当选得出档");
			assertEquals((int) row[1], indexOfRunningEntry(roll, r.entry()) + 1, "档序@" + v);
			assertEquals(row[2], r.entry().vBakeKmh(), 1e-6, "烘点@" + v);
			assertEquals(row[3], r.rate(), 1e-6, "roll 变调@" + v);
			assertEquals(row[4], r.volume(), 1e-6, "roll 音量@" + v);

			final RunningSelection a = MmtrTractionSoundModel.selectRunning(aero, v);
			assertNotNull(a, "风噪层在 " + v + " km/h 应当选得出档");
			assertEquals(row[5], a.rate(), 1e-9, "风噪变调恒 1@" + v);
			assertEquals(row[6], a.volume(), 1e-6, "风噪音量@" + v);
		}
	}

	/** 门限 + 音量上限：这两个都是"不夹取就会很难听/很难查"的地方。 */
	@Test
	public void runningLayerIsSilentBelowTheSmokeGateAndNeverBoosts() {
		final RunningLayer roll = runningLayer("roll", 30, true);
		assertNull(MmtrTractionSoundModel.selectRunning(roll, 4.999), "4.999 < 5 km/h ⇒ 整层静音");
		assertNotNull(MmtrTractionSoundModel.selectRunning(roll, 5.0), "5.0 = 门限 ⇒ 出声");
		// 超出最高档烘点（设计最高速度之外）：音量夹到 1，不 boost；变调仍在引擎硬范围内
		final RunningSelection high = MmtrTractionSoundModel.selectRunning(roll, 160);
		assertNotNull(high);
		assertTrue(high.volume() <= 1 + EPS, "音量不得超过 1（实测 " + high.volume() + "）");
		assertTrue(high.rate() <= MmtrTractionSoundModel.MAX_PITCH + EPS, "变调不得超引擎上限");
		// 烘点取档的**上几何边** ⇒ 全 23 档的设计域内音量恒 ≤ 1（select_running S4 的 Java 侧对照）
		for (double v = 5; v <= 141.2346; v += 0.5) {
			final RunningSelection s = MmtrTractionSoundModel.selectRunning(roll, v);
			assertNotNull(s, "设计域内应当处处有声：" + v);
			assertTrue(s.volume() <= 1 + EPS, "设计域内音量必须 ≤ 1：" + v + " → " + s.volume());
		}
	}

	/** 电机层的音阶门限（= {@code select.py} 的 V_SMOKE）。 */
	@Test
	public void motorGateOnlySilencesBelowTheManifestThreshold() {
		assertTrue(!MmtrTractionSoundModel.motorAudible(4.999, 5), "低于门限 ⇒ 静音");
		assertTrue(MmtrTractionSoundModel.motorAudible(5, 5), "等于门限 ⇒ 出声");
		assertTrue(MmtrTractionSoundModel.motorAudible(0, 0), "门限 ≤ 0 = 不设门限（旧清单必须照旧）");
	}

	/** 走行段必须能从清单里读出来（含事件 id 与量纲）。 */
	@Test
	public void manifestParserReadsTheRunningSection() {
		final String withRunning = MINIMAL_MANIFEST.replace("\"entries\"", RUNNING_SECTION + "\"entries\"");
		final MmtrTractionSoundSet.ParsedManifest parsed = MmtrTractionSoundSet.parseManifest("kei2100", withRunning);

		assertEquals(5, parsed.vSmokeKmh(), EPS, "gate.vSmokeKmh");
		assertTrue(parsed.model().hasRunning(), "走行段要读出来");
		assertEquals(2, parsed.model().runningLayers().size(), "两条层");
		final RunningLayer roll = parsed.model().runningLayers().get(0);
		assertEquals("roll", roll.id());
		assertEquals(30, roll.lawB(), EPS);
		assertTrue(roll.pitchFollowsSpeed(), "轮轨的形状随速度移动");
		assertEquals(2, roll.entries().size());
		assertEquals(5.346419, roll.entries().get(0).vBakeKmh(), 1e-6, "烘点");
		assertEquals(4.921778, roll.entries().get(0).vCentreKmh(), 1e-6, "档中心速度（选档用）");
		final RunningLayer aero = parsed.model().runningLayers().get(1);
		assertEquals(60, aero.lawB(), EPS);
		assertTrue(!aero.pitchFollowsSpeed(), "风噪的峰不随速度移动");
		assertEquals("kei2100_roll01", parsed.runningLayers().get(0).eventIds().get(0));
		assertEquals("kei2100_aero01", parsed.runningLayers().get(1).eventIds().get(0));
		// 没有走行段的旧清单必须**照旧可用**（可选段，不是必需段）
		final MmtrTractionSoundSet.ParsedManifest sans = MmtrTractionSoundSet.parseManifest("demo", MINIMAL_MANIFEST);
		assertTrue(!sans.model().hasRunning(), "旧清单没有走行层");
		assertEquals(0, sans.vSmokeKmh(), EPS, "旧清单没有门限（= 不设门限）");
	}

	/**
	 * ★ 真实 kei2100 清单：走行层每档的事件都必须登记在片段里、路径也要对得上。
	 *
	 * <p>守的是那个**会静默失败**的坑：清单里声明了事件、sounds.json 里没有 ⇒
	 * {@code MmtrTractionSoundSet.load} 建 SoundEvent 时抛异常被 catch ⇒
	 * **整个音效集退回 BVE/legacy**，现场只表现为"没声音"。</p>
	 */
	@Test
	public void realBakedRunningLayersAreRegisteredAsSoundEvents() throws IOException {
		final Path manifest = findKei2100Manifest();
		Assumptions.assumeTrue(manifest != null, "没有 kei2100 清单（先跑 artifacts/.../pack_kei2100.py）");
		final MmtrTractionSoundSet.ParsedManifest parsed = MmtrTractionSoundSet.parseManifest("kei2100",
				Files.readString(manifest, StandardCharsets.UTF_8));
		assertTrue(parsed.model().hasRunning(), "kei2100 清单必须带走行层");
		assertEquals(2, parsed.model().runningLayers().size(), "轮轨 + 风噪两条");
		assertEquals(5, parsed.vSmokeKmh(), EPS, "门限 5 km/h");

		final Path sidecar = manifest.resolveSibling("mmtr_traction.sounds.json");
		Assumptions.assumeTrue(Files.exists(sidecar), "没有 sounds.json 片段");
		final String registry = Files.readString(sidecar, StandardCharsets.UTF_8);
		for (int i = 0; i < parsed.runningLayers().size(); i++) {
			final RunningLayer layer = parsed.model().runningLayers().get(i);
			assertEquals(23, layer.entries().size(), layer.id() + " 应当有 23 档");
			final List<String> ids = parsed.runningLayers().get(i).eventIds();
			assertEquals(layer.entries().size(), ids.size(), "事件表与档表必须同长");
			for (int k = 0; k < ids.size(); k++) {
				assertTrue(registry.contains("\"" + ids.get(k) + "\""),
						"片段里没有事件 " + ids.get(k));
				/*
				 * ★ 路径的写法**不是** `baseDir + "/" + stem`。
				 * `baseDir`（= "sounds/kei2100"）是相对 `assets/<ns>/` 的**资源目录**，
				 * 而 sounds.json 的 `name` 是相对 `assets/<ns>/sounds/` 的 —— MC 会给它再前置一次
				 * `sounds/`。所以 name 必须是 `mtr:kei2100/<stem>`。
				 * （saf420 那份清单写成了 `mtr:sounds/saf420/<stem>`，于是引擎去找
				 *   `assets/mtr/sounds/sounds/saf420/…` —— 包里没有那条路径，整套静音。
				 *   本条断言就是为了不再犯同一个错。）
				 */
				final String stem = layer.entries().get(k).file().replace(".ogg", "");
				assertTrue(registry.contains("\"mtr:kei2100/" + stem + "\""),
						"片段里的 name 必须是 mtr:kei2100/" + stem + "（不是 baseDir + 文件名）");
				assertTrue(!registry.contains("\"mtr:sounds/"),
						"name 里不得带多余的 sounds/ 前缀");
			}
		}
		// ★ f₁ 的系数：清单里的 rotorHzPerKmh 必须是 1/k（k = 0.820296 km/h/Hz），
		//   否则游戏里的音高与我们那条链（select.py）分叉。
		assertEquals(1 / 0.820296, parsed.model().spec().rotorHzPerKmh(), 1e-4,
				"spec.rotorHzPerKmh 必须 = 1/k");
		assertEquals(23, parsed.model().entries().size(), "电机层 23 档");
	}

	private static RunningLayer runningLayer(String id, double lawB, boolean pitchFollowsSpeed) {
		// 与清单同形的 **23 档**（烘点/档中心逐个抄自 kei2100 的 mmtr_traction.json）
		final double[][] ladder = {
				{5.346419, 4.921778}, {6.237489, 5.742074}, {7.128559, 6.562371}, {8.019629, 7.382667},
				{9.356234, 8.613112}, {10.692839, 9.843556}, {12.474979, 11.484149}, {14.702653, 13.534890},
				{16.930328, 15.585630}, {19.603538, 18.046519}, {22.722283, 20.917557}, {26.732097, 24.608890},
				{31.187447, 28.710372}, {36.533866, 33.632150}, {42.771355, 39.374224}, {50.345450, 46.346743},
				{59.256149, 54.549706}, {69.503453, 63.983114}, {81.978431, 75.467263}, {96.681085, 89.002153},
				{114.056948, 104.997931}, {134.551556, 123.864747}, {141.234580, 130.016969},
		};
		final List<RunningEntry> entries = new ArrayList<>();
		for (int i = 0; i < ladder.length; i++) {
			entries.add(new RunningEntry(String.format("%s%02d.ogg", id, i + 1), ladder[i][0], ladder[i][1]));
		}
		return new RunningLayer(id, lawB, pitchFollowsSpeed, 5, 1, entries);
	}

	private static int indexOfRunningEntry(RunningLayer layer, RunningEntry entry) {
		for (int i = 0; i < layer.entries().size(); i++) {
			if (layer.entries().get(i) == entry) {
				return i;
			}
		}
		return -1;
	}

	private static final String RUNNING_SECTION = """
			  "gate": { "vSmokeKmh": 5.0 },
			  "running": { "layers": [
			    { "id": "roll", "lawB": 30, "pitchMode": "speed_ratio", "vSmokeKmh": 5, "masterVolume": 1,
			      "entries": [
			        { "file": "roll01.ogg", "eventId": "kei2100_roll01", "vBakeKmh": 5.346419, "vCentreKmh": 4.921778 },
			        { "file": "roll02.ogg", "eventId": "kei2100_roll02", "vBakeKmh": 6.237489, "vCentreKmh": 5.741778 }
			      ] },
			    { "id": "aero", "lawB": 60, "pitchMode": "fixed", "masterVolume": 1,
			      "entries": [
			        { "file": "aero01.ogg", "eventId": "kei2100_aero01", "vBakeKmh": 5.346419, "vCentreKmh": 4.921778 }
			      ] }
			  ] },
			""";

	/** kei2100 的清单：由 {@code artifacts/kei2100-av417534959/pack_kei2100.py} 生成。 */
	private static Path findKei2100Manifest() {
		return findUp("artifacts/kei2100-av417534959/pack/stage/Kei2100/assets/mtr/sounds/kei2100/mmtr_traction.json");
	}

	/** 从当前目录往上找某个相对路径（gradle 与离线 runner 的 cwd 都是 {@code fabric/}）。 */
	private static Path findUp(String relative) {
		Path dir = Path.of("").toAbsolutePath();
		for (int i = 0; i < 8 && dir != null; i++) {
			final Path path = dir.resolve(relative).normalize();
			if (Files.isRegularFile(path)) {
				return path;
			}
			dir = dir.getParent();
		}
		return null;
	}

	// ---- 工具 --------------------------------------------------------------------------------

	private static Spec specOf(double rotorHzPerKmh) {
		return new Spec(4, 5.93, 0.82, 4.5, 27, 250, 1045, rotorHzPerKmh, 76.8, 300_000, 6_400_000, 8);
	}

	/** 从当前目录往上找烘焙清单：gradle 跑测试时 cwd 是 fabric/，离线 runner 会把 cwd 设成同一个。 */
	private static Path findManifest() {
		Path dir = Path.of("").toAbsolutePath();
		// 优先"真正打进资源包的那一份"（consist/saf420.json 的 soundDir 指向它），
		// 它是带 --base saf420 烘的；final2s 只是自检用的那份（没带 --base）。
		final String[] candidates = {
				"sandbox/sound-studio/baked/saf420/mmtr_traction.json",
				"sandbox/sound-studio/final2s/mmtr_traction.json",
		};
		for (int i = 0; i < 6 && dir != null; i++) {
			for (final String candidate : candidates) {
				final Path path = dir.resolve(candidate).normalize();
				if (Files.isRegularFile(path)) {
					return path;
				}
			}
			dir = dir.getParent();
		}
		return null;
	}

	/**
	 * 读清单。
	 *
	 * <p>刻意**不引 Gson**：清单是 {@code bake.mjs} 自己生成的、形状固定，
	 * 而本类要能在"只借了 loom 类路径"的命令行环境里跑起来（见
	 * {@code mmtr/tools/sound-studio/check-java-model.ps1}）。用 Gson 会把"模型对不对"
	 * 和"类路径齐不齐"两件事绑在一起，那是本仓最不想要的耦合。</p>
	 */
	private static SoundSet readManifest(Path path) throws IOException {
		final String text = Files.readString(path, StandardCharsets.UTF_8);
		final Matcher specMatcher = Pattern.compile("\"spec\"\\s*:\\s*\\{").matcher(text);
		assertTrue(specMatcher.find(), "清单里没有 spec 段");
		final String specText = balancedObject(text, specMatcher.end() - 1);
		final Spec spec = new Spec(
				(int) num(specText, "poleCount", 0),
				num(specText, "gearRatio", 0),
				num(specText, "wheelDiameterM", 0),
				num(specText, "slipHz", 0),
				num(specText, "regenCutoffKmh", 0),
				num(specText, "carrierMinHz", 0),
				num(specText, "carrierMaxHz", 0),
				num(specText, "rotorHzPerKmh", 0),
				num(specText, "breakpointKmh", 0),
				num(specText, "maxTractiveEffortN", 0),
				num(specText, "maxPowerW", 0),
				8
		);
		final List<Entry> entries = new ArrayList<>();
		/*
		 * 只在 **entries 数组** 里扫，不是全篇扫。
		 * 为什么：清单里还有 `gear` 段，它也带一个 `"file"` —— 全篇扫会把它当成第 28 档
		 * （实测就是这个症状：扫描器读出 28、Gson 读出 27）。
		 * 这条用例的价值就在于"两条独立的路读同一份文件必须一致"，所以扫描范围必须与 Gson 对齐。
		 */
		final Matcher entriesMatcher = Pattern.compile("\"entries\"\\s*:\\s*\\[").matcher(text);
		assertTrue(entriesMatcher.find(), "清单里没有 entries 数组");
		final String entriesText = balancedArray(text, entriesMatcher.end() - 1);
		final Matcher fileMatcher = Pattern.compile("\"file\"\\s*:\\s*\"([^\"]+)\"").matcher(entriesText);
		while (fileMatcher.find()) {
			// 同一行的 f1Hz / mode / pulses / carrierHz —— 清单是格式化过的，字段顺序固定
			final int from = fileMatcher.end();
			final int to = Math.min(entriesText.length(), from + 400);
			final String window = entriesText.substring(from, to);
			entries.add(new Entry(
					fileMatcher.group(1),
					num(window, "f1Hz", 0),
					str(window, "mode", ""),
					(int) num(window, "pulses", 0),
					num(window, "carrierHz", 0)
			));
		}
		return new SoundSet(spec, entries);
	}

	private static String balancedObject(String text, int openBrace) {
		return balanced(text, openBrace, '{', '}');
	}

	/** 与 {@link #balancedObject} 同理，但配对的是 {@code [ ]}（entries 是数组）。 */
	private static String balancedArray(String text, int openBracket) {
		return balanced(text, openBracket, '[', ']');
	}

	private static String balanced(String text, int open, char openChar, char closeChar) {
		int depth = 0;
		for (int i = open; i < text.length(); i++) {
			final char c = text.charAt(i);
			if (c == openChar) {
				depth++;
			} else if (c == closeChar) {
				depth--;
				if (depth == 0) {
					return text.substring(open, i + 1);
				}
			}
		}
		return text.substring(open);
	}

	private static double num(String scope, String key, double fallback) {
		final Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[0-9.eE+]+)").matcher(scope);
		return matcher.find() ? Double.parseDouble(matcher.group(1)) : fallback;
	}

	private static String str(String scope, String key, String fallback) {
		final Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(scope);
		return matcher.find() ? matcher.group(1) : fallback;
	}
}
