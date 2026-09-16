package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.cache.ArrayOcclusionCache;
import com.logisticscraft.occlusionculling.cache.OcclusionCache;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * notes/177：遮挡剔除缓存换成了"只清这一轮碰过的格子"的实现，这个用例把两件事钉死 ——
 *
 * <ol>
 *   <li><b>语义与库自带实现逐位一致</b>。这一条最要紧：缓存是 2 bit 一格的打包字节数组，
 *       位序错了不会崩，只会让剔除偶尔判断反（画面上一亮一灭），所以必须拿库自己那份
 *       {@link ArrayOcclusionCache} 当对照跑同一串读写。</li>
 *   <li><b>清空的代价只看"碰过的格子数"，不看数组大小</b>。这就是修掉那个 73% 一个核的判据：
 *       库的实现是 {@code Arrays.fill((2*reach)³/4 字节)} —— 渲染距离 32 区块时 256 MB；
 *       这里应当只清几百个字节。</li>
 * </ol>
 */
public final class MmtrSparseOcclusionCacheTests {

	private static final int REACH = 8;

	@Test
	public void everyReadAndWriteMatchesTheLibraryCache() {
		final OcclusionCache reference = new ArrayOcclusionCache(REACH);
		final OcclusionCache sparse = new MmtrSparseOcclusionCache(REACH);
		final Random random = new Random(20260916L);
		final int limit = REACH * 2;

		for (int step = 0; step < 20_000; step++) {
			final int x = random.nextInt(limit);
			final int y = random.nextInt(limit);
			final int z = random.nextInt(limit);
			switch (random.nextInt(5)) {
				case 0 -> {
					reference.setVisible(x, y, z);
					sparse.setVisible(x, y, z);
				}
				case 1 -> {
					reference.setHidden(x, y, z);
					sparse.setHidden(x, y, z);
				}
				case 2 -> {
					// setLastVisible/setLastHidden 打的是"上一次查询过的那个格子"，所以必须先查
					assertEquals(reference.getState(x, y, z), sparse.getState(x, y, z), "getState 必须一致（第 " + step + " 步）");
					reference.setLastVisible();
					sparse.setLastVisible();
				}
				case 3 -> {
					assertEquals(reference.getState(x, y, z), sparse.getState(x, y, z), "getState 必须一致（第 " + step + " 步）");
					reference.setLastHidden();
					sparse.setLastHidden();
				}
				case 4 -> assertEquals(reference.getState(x, y, z), sparse.getState(x, y, z), "getState 必须一致（第 " + step + " 步）");
				default -> throw new IllegalStateException();
			}
		}
	}

	@Test
	public void twoCellsInsideTheSameByteKeepTheirOwnBits() {
		final OcclusionCache reference = new ArrayOcclusionCache(REACH);
		final OcclusionCache sparse = new MmtrSparseOcclusionCache(REACH);

		// (x, y, z) = (0,0,0) 与 (1,0,0) 落在同一个字节的两格上
		sparse.setHidden(1, 0, 0);
		reference.setHidden(1, 0, 0);
		sparse.setVisible(0, 0, 0);
		reference.setVisible(0, 0, 0);

		// 库的编码：bit0 = visible（getState==1），bit1 = hidden（getState==2），见 ArrayOcclusionCache
		assertEquals(1, sparse.getState(0, 0, 0), "setVisible 应当读回 1");
		assertEquals(2, sparse.getState(1, 0, 0), "setHidden 应当读回 2");
		assertEquals(reference.getState(0, 0, 0), sparse.getState(0, 0, 0));
		assertEquals(reference.getState(1, 0, 0), sparse.getState(1, 0, 0));
		assertEquals(1, ((MmtrSparseOcclusionCache) sparse).mmtrTouchedEntryCount(), "同一个字节里的第二个格子不能重复计数");
	}

	@Test
	public void resetCacheForgetsEverythingAndKeepsWorkingAfterwards() {
		final MmtrSparseOcclusionCache sparse = new MmtrSparseOcclusionCache(REACH);
		sparse.setVisible(3, 4, 5);
		sparse.setHidden(6, 7, 8);
		assertTrue(sparse.mmtrTouchedEntryCount() > 0, "写过就应该记下来");

		sparse.resetCache();
		assertEquals(0, sparse.mmtrTouchedEntryCount(), "清空之后记录表也要空");
		assertEquals(0, sparse.getState(3, 4, 5), "清空之后旧格子必须回到「未判定」");
		assertEquals(0, sparse.getState(6, 7, 8), "清空之后旧格子必须回到「未判定」");

		sparse.setHidden(3, 4, 5);
		assertEquals(2, sparse.getState(3, 4, 5), "清空之后仍然可写可读（setHidden ⇒ 2）");
	}

	@Test
	public void theResetCostFollowsTouchedCellsNotTheArraySize() {
		// reach=64 ⇒ 库自带实现要清 (128)³/4 = 512 KB，而这里只该清"写过的那些**字节**"
		final int reach = 64;
		final MmtrSparseOcclusionCache sparse = new MmtrSparseOcclusionCache(reach);
		final int limit = reach * 2;

		// 100 个格子会落进 30 个字节里（一格 = 2 bit，一个字节装 4 格）——
		// 记录表数的是字节，所以期望值必须按"不同的 entry"算，而不是按写了几次
		final java.util.Set<Integer> expectedEntries = new java.util.HashSet<>();
		for (int x = 0; x < 10; x++) {
			for (int y = 0; y < 10; y++) {
				sparse.setVisible(x, y, 0);
				expectedEntries.add((x + y * limit) / 4);
			}
		}

		assertEquals(expectedEntries.size(), sparse.mmtrTouchedEntryCount(), "记下来的格子数应当等于写过的**字节**数（同字节里的 4 格只算一次）");
		assertTrue(expectedEntries.size() < 100, "这个用例本身要有意义：100 个格子必须共用少于 100 个字节");
		assertTrue(sparse.mmtrTouchedEntryCount() < (long) limit * limit * limit / 4 / 100,
				"记录表必须远小于整个缓存（渲染距离 32 区块时那两者是 几百 vs 2.68 亿）");
	}
}
