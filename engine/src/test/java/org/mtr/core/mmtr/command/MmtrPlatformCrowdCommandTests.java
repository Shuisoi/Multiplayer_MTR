package org.mtr.core.mmtr.command;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.crowd.MmtrCrowd;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站台客量（{@code platform crowd …}）与"客量 → 人数"的换算：**这些数必须能被读回来核对**。
 *
 * <h3>钉住的三件事</h3>
 * <ol>
 *   <li><b>0–100 是硬口径</b>：默认 0、越界夹住 —— 下游（游戏侧铺方块）拿它当乘数用，
 *       一个 150 或负数会直接变成"铺出站台外"或"人全没了"。</li>
 *   <li><b>折合人数的分母是格数</b>：客量 50% × 40 格 = 20 人（口径：1 格一人 = 100%）。
 *       这个数在 {@code platform list} 与网页 feed 上都要出现，否则"设了 60% 到底铺几个人"
 *       只能靠数方块。</li>
 *   <li><b>调制器只改"有效客量"</b>：存下来的客量不动，有效客量可以不同 —— 这就是以后
 *       接临时高峰/时段曲线/真实候车人数的接口（用户口径 2026-10："暂时留接口和指令就行"）。</li>
 * </ol>
 */
public final class MmtrPlatformCrowdCommandTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	/** 两个站台的站台轨长度都是 40 格 ⇒ 折合人数 = 客量% × 40 / 100，断言里可以直接心算。 */
	private static final int PLATFORM_LENGTH_BLOCKS = 40;

	private static final class Net {

		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-crowd-command"), false);
		final Station alpha;
		final Platform alpha1;
		final Platform alpha2;

		Net() {
			alpha = new Station(sim);
			alpha.setName("Alpha");
			alpha.setCorners(new Position(-5, -10, -5), new Position(45, 10, 25));
			sim.stations.add(alpha);

			alpha1 = addPlatform(0);
			alpha2 = addPlatform(20);
			sim.sync();

			// 夹具守卫：站台必须真的挂在车站上（sync() 会删掉底下没有图轨的站台，
			// 删掉之后 setStation 会走"名下没有站台"那条分支，用例会以假理由失败）。
			assertNotNull(sim.platformIdMap.get(alpha1.getId()), "夹具：alpha1 必须在 platformIdMap 里");
			assertNotNull(sim.platformIdMap.get(alpha2.getId()), "夹具：alpha2 必须在 platformIdMap 里");
			assertEquals(2, alpha.savedRails.size(), "夹具：Alpha 名下应当有 2 个站台");
		}

		private Platform addPlatform(int z) {
			sim.rails.add(Rail.newPlatformRail(new Position(0, 0, z), Angle.fromAngle(0), new Position(PLATFORM_LENGTH_BLOCKS, 0, z), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN));
			final Platform platform = new Platform(new Position(0, 0, z), new Position(PLATFORM_LENGTH_BLOCKS, 0, z), TransportMode.TRAIN, sim);
			sim.platforms.add(platform);
			return platform;
		}

		static String text(MmtrCommandDispatcher.Result result) {
			return String.join("\n", result.lines);
		}
	}

	@Test
	public void crowdLevelDefaultsToZeroAndIsClamped() {
		final Net net = new Net();
		assertEquals(0, net.alpha1.getCrowdLevel(), "新站台的客量默认 0（站台上没有人）");

		net.alpha1.setCrowdLevel(150);
		assertEquals(100, net.alpha1.getCrowdLevel(), "越界上界夹到 100");

		net.alpha1.setCrowdLevel(-5);
		assertEquals(0, net.alpha1.getCrowdLevel(), "负数夹到 0");
	}

	@Test
	public void stationCommandSetsEveryPlatformAndReportsPeople() {
		final Net net = new Net();
		final MmtrCommandDispatcher.Result result = MmtrCommandDispatcher.execute(net.sim, "platform station Alpha 50");

		assertTrue(result.ok, Net.text(result));
		assertEquals("platform", result.namespace);
		assertEquals("station", result.verb);
		assertEquals(2, result.affected.size(), "Alpha 名下两个站台都要被改到");
		assertEquals(50, net.alpha1.getCrowdLevel());
		assertEquals(50, net.alpha2.getCrowdLevel());

		// 50% × 40 格 = 20 人/站台，两个站台合计 40 人 —— 这条换算就是"设完立刻核对"的读数。
		final String text = Net.text(result);
		assertTrue(text.contains("约 40 人"), text);
	}

	@Test
	public void setByHexWorksAndListReportsBothLevels() {
		final Net net = new Net();
		final String hex = net.alpha1.getHexId();
		assertEquals(16, hex.length(), "站台 hex 是 16 位（存档文件名）");

		final MmtrCommandDispatcher.Result set = MmtrCommandDispatcher.execute(net.sim, "platform set " + hex + " 25");
		assertTrue(set.ok, Net.text(set));
		assertEquals(25, net.alpha1.getCrowdLevel(), "按 16 位 hex 也要能设到（网页/存档里给的就是 hex）");
		assertEquals(0, net.alpha2.getCrowdLevel(), "只改指定的那一个，别的站台不动");

		final MmtrCommandDispatcher.Result list = MmtrCommandDispatcher.execute(net.sim, "platform list");
		assertTrue(list.ok, Net.text(list));
		final String text = Net.text(list);
		assertTrue(text.contains(hex), text);
		assertTrue(text.contains("客量=25%"), text);
		assertTrue(text.contains("约 10 人"), text); // 25% × 40 格
		assertTrue(text.contains("共 2 个站台"), text);
	}

	@Test
	public void modulatorOnlyChangesEffectiveLevel() {
		final Net net = new Net();
		net.alpha1.setCrowdLevel(40);
		try {
			// 以后接"临时高峰"就是这个形状：站台上存的是基础客量，事件生效期内有效客量更高。
			MmtrCrowd.setModulator((platform, baseLevel, data) -> baseLevel + 30);
			assertEquals(40, net.alpha1.getCrowdLevel(), "存下来的客量不因调制器改变");
			assertEquals(70, net.alpha1.getEffectiveCrowdLevel(), "有效客量 = 基础 + 调制");
			assertEquals(30, net.alpha2.getEffectiveCrowdLevel(), "调制器对所有站台生效（alpha2 基础 0 ⇒ 0+30）");

			final MmtrCommandDispatcher.Result list = MmtrCommandDispatcher.execute(net.sim, "platform list");
			assertTrue(Net.text(list).contains("（有调制器在起作用）"), Net.text(list));
		} finally {
			MmtrCrowd.setModulator(null);
		}
		assertEquals(40, net.alpha1.getEffectiveCrowdLevel(), "取消调制器后有效客量回到基础客量");
	}

	@Test
	public void modulatorExceptionFallsBackToBaseLevel() {
		final Net net = new Net();
		net.alpha1.setCrowdLevel(60);
		try {
			MmtrCrowd.setModulator((platform, baseLevel, data) -> {
				throw new IllegalStateException("调制器炸了");
			});
			assertEquals(60, net.alpha1.getEffectiveCrowdLevel(), "调制器抛异常时退回基础客量，而不是把客流清零");
		} finally {
			MmtrCrowd.setModulator(null);
		}
	}

	@Test
	public void refreshBumpsRevisionWithoutChangingLevels() {
		final Net net = new Net();
		net.alpha1.setCrowdLevel(35);
		final long before = MmtrCrowd.revision();

		final MmtrCommandDispatcher.Result result = MmtrCommandDispatcher.execute(net.sim, "platform refresh");
		assertTrue(result.ok, Net.text(result));
		assertTrue(MmtrCrowd.revision() > before, "refresh 必须让版本号前进，游戏侧才会立刻重铺");
		assertEquals(35, net.alpha1.getCrowdLevel(), "refresh 不改客量");
	}

	/**
	 * 客量必须**落盘并读得回来**：设完客量重启服务端，站台上的人还得在。
	 *
	 * <p>这条用例走的是真存档路径（{@code platforms/<末两位hex>/<hex>} 的 MessagePack 文件）：
	 * 第一个模拟器 {@code stop()} 全量落盘，第二个模拟器从同一目录读回来 —— 只有真的写进文件
	 * 才可能通过，"只改了内存里的字段"会在这里露馅。</p>
	 */
	@Test
	public void crowdLevelSurvivesSaveAndReload() throws Exception {
		final Path root = Paths.get("build/mmtr-crowd-persist");
		// 上一轮跑剩下的存档文件会让"读回来几个站台"变成不确定（每轮的 platform id 都是随机 long），
		// 所以这一条用例自己先清干净：它要验的是"写进去→读回来"，不是"目录里恰好有几个文件"。
		deleteRecursively(root);
		final Simulator first = new Simulator("test", new String[]{"test"}, root, false);
		first.rails.add(Rail.newPlatformRail(new Position(0, 0, 0), Angle.fromAngle(0), new Position(PLATFORM_LENGTH_BLOCKS, 0, 0), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN));
		final Platform platform = new Platform(new Position(0, 0, 0), new Position(PLATFORM_LENGTH_BLOCKS, 0, 0), TransportMode.TRAIN, first);
		first.platforms.add(platform);
		first.sync();
		platform.setCrowdLevel(70);
		first.stop();

		final Simulator second = new Simulator("test", new String[]{"test"}, root, false);
		assertEquals(1, second.platforms.size(), "落盘的站台要被读回来");
		final Platform reloaded = second.platforms.iterator().next();
		assertEquals(platform.getId(), reloaded.getId(), "id 必须一致（hex 就是文件名）");
		assertEquals(70, reloaded.getCrowdLevel(), "客量要能从 platforms/<末两位hex>/<hex> 里读回来");
	}

	/** 删掉一棵目录树（用例用来清自己那份存档；不依赖工作区外的任何工具）。 */
	private static void deleteRecursively(Path root) throws Exception {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> stream = Files.walk(root)) {
			for (final Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	@Test
	public void badInputIsRejectedWithUsage() {		final Net net = new Net();

		assertFalse(MmtrCommandDispatcher.execute(net.sim, "platform set " + net.alpha1.getHexId() + " 101").ok, "客量 101 必须被拒（不是夹成 100 静默通过）");
		assertFalse(MmtrCommandDispatcher.execute(net.sim, "platform set NOPE 50").ok, "找不到的站台必须报错");
		assertFalse(MmtrCommandDispatcher.execute(net.sim, "platform station NoSuchStation 50").ok, "找不到的车站必须报错");
		assertFalse(MmtrCommandDispatcher.execute(net.sim, "platform all").ok, "platform all 缺客量参数必须报用法");
		assertFalse(MmtrCommandDispatcher.execute(net.sim, "platform nope").ok, "不认识动词时给用法");

		final MmtrCommandDispatcher.Result usage = MmtrCommandDispatcher.execute(net.sim, "platform nope");
		assertTrue(Net.text(usage).contains("platform 支持"), Net.text(usage));
	}
}
