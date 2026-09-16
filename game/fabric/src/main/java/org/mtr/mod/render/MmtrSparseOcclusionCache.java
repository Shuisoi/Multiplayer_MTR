package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.cache.OcclusionCache;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * 只清"这一轮碰过的格子"的遮挡剔除缓存（notes/177）。
 *
 * <h2>为什么必须换掉库自带的实现</h2>
 * <p>{@code ArrayOcclusionCache} 的数组大小是 <b>立方</b> 的：{@code (reach * 2)³ / 4} 字节。
 * MTR 传进来的 reach 是 {@code min(渲染距离, 32) * 16} —— 渲染距离 32 区块时 reach = 512，
 * 于是那个数组是 {@code 1024³ / 4 = 268,435,456} 字节 = <b>256 MB</b>，而
 * {@code resetCache()} 就是对这个 256 MB 数组做一次 {@code Arrays.fill}：单次约 7 ms。</p>
 *
 * <p>而 {@link WorkerThread#runTick()} 在它的队列非空时**每一轮**都要 reset 一次（高帧率下队列长期是满的，
 * 约 100 轮/秒）⇒ 每秒约 700 ms、约 25 GB/s 的纯内存写入。实机 jstack 连续两次都停在
 * {@code Arrays.fill ← ArrayOcclusionCache.resetCache ← WorkerThread.runTick}，两次之间 5.15 秒里
 * 烧掉 3.77 秒 CPU（<b>73% 的一个核</b>），把内存带宽和 CPU 缓存全占掉，渲染线程跟着掉到几乎不出帧
 * ——现场表现就是"客户端卡死"。</p>
 *
 * <h2>这里怎么改的</h2>
 * <p>缓存的语义是"**一帧之内**某个格子判定过可见/被遮挡"，而且键是**相机相对**的（库自己在
 * {@code getCacheValue} 里加 {@code reach}），所以每帧必须清空。清空的代价不必是 reach³：
 * 每帧真正被写过的格子只有"这一帧被剔除的对象数"量级（车、升降机、轨、杂项，几百个）。
 * 于是这里记一份**写过的格子下标**，{@code resetCache()} 只把这些格子清零再把表清空 —— 
 * 从 256 MB 的 fill 降到几百字节的循环。</p>
 *
 * <p>读数路径（{@code getState}）没有任何额外开销：仍然是原来的直接下标寻址，所以射线步进的性能不变；
 * 只有写路径多了一次"这个字节是不是已经非零"的判断（用来去重）。</p>
 *
 * <p>兜底：万一某一帧写过的格子多到异常（例如剔除目标暴涨），记录表会涨到
 * {@link #MAX_TOUCHED_ENTRIES} 就退回一次全量 {@code Arrays.fill} 并清空记录表 —— 
 * 那时仍然正确，只是那一帧按老代价付费。</p>
 */
public final class MmtrSparseOcclusionCache implements OcclusionCache {

	/** 记录表上限（int 个数，约 16 MB）；超过就退回全量清空，避免记录表本身无界增长。 */
	private static final int MAX_TOUCHED_ENTRIES = 4_000_000;

	private final int reachX2;
	private final byte[] cache;
	/** 本帧写过的格子（字节下标）；同一个格子只记一次（见 {@link #markTouched} 的判据）。 */
	private final IntArrayList touchedEntries = new IntArrayList();
	private int positionKey;
	private int entry;
	private int offset;

	public MmtrSparseOcclusionCache(int reach) {
		reachX2 = reach * 2;
		cache = new byte[(reachX2 * reachX2 * reachX2) / 4];
	}

	/**
	 * 本帧记下来的格子数（用例用）。
	 *
	 * <p>这个数就是 {@link #resetCache()} 的代价：它只清这么多字节，而不是 {@code reach³/4} 个字节 —— 
	 * "只清碰过的"这条性质就是整个改动的价值所在，所以要能被断言。</p>
	 */
	int mmtrTouchedEntryCount() {
		return touchedEntries.size();
	}

	@Override
	public void resetCache() {
		final int[] entries = touchedEntries.elements();
		final int size = touchedEntries.size();
		for (int i = 0; i < size; i++) {
			cache[entries[i]] = 0;
		}
		touchedEntries.clear();
	}

	@Override
	public void setVisible(int x, int y, int z) {
		updatePosition(x, y, z);
		markTouched();
		cache[entry] |= 1 << offset;
	}

	@Override
	public void setHidden(int x, int y, int z) {
		updatePosition(x, y, z);
		markTouched();
		cache[entry] |= 1 << (offset + 1);
	}

	@Override
	public int getState(int x, int y, int z) {
		updatePosition(x, y, z);
		return cache[entry] >> offset & 3;
	}

	@Override
	public void setLastVisible() {
		markTouched();
		cache[entry] |= 1 << offset;
	}

	@Override
	public void setLastHidden() {
		markTouched();
		cache[entry] |= 1 << (offset + 1);
	}

	/**
	 * 记下"这个字节被写过"。
	 *
	 * <p>去重判据：字节为 0 说明本帧还没写过它 —— 写过的字节在 {@link #resetCache()} 之前
	 * 一定非零（4 个格子里只要有一个被标记就非零），所以"非零就不再记"是安全的；
	 * 这也保证了同一个字节最多出现一次。</p>
	 */
	private void markTouched() {
		if (cache[entry] == 0) {
			if (touchedEntries.size() >= MAX_TOUCHED_ENTRIES) {
				java.util.Arrays.fill(cache, (byte) 0);
				touchedEntries.clear();
			}
			touchedEntries.add(entry);
		}
	}

	private void updatePosition(int x, int y, int z) {
		positionKey = x + y * reachX2 + z * reachX2 * reachX2;
		entry = positionKey / 4;
		offset = (positionKey % 4) * 2;
	}
}
