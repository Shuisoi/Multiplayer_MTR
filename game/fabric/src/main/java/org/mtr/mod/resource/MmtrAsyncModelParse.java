package org.mtr.mod.resource;

import org.mtr.mod.Init;

import javax.annotation.Nullable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 把一段"纯 CPU、结果可以原样交给渲染线程"的活挪到别的线程上做（notes/400 §7）。
 *
 * <p>名字里带 {@code Parse} 是因为它最初只服务"OBJ 文本 → 源网格"；现在它是一台通用的
 * "后台干一段纯 CPU 活"的机器 —— 车辆几何的归一化 + 合桶（notes/400 §8）也走它。
 * {@code label} 就是分工的标记：日志前缀一定是 {@code 后台解析 …} 或 {@code 后台烘焙 …}。</p>
 *
 * <h2>为什么可以搬</h2>
 *
 * <p>车辆模型重建那 284–455 ms 里，最大的一块是 <b>OBJ 文本 → RawMesh</b>：
 * 4.3 MB 的 `saf420car.obj` 离线量到 <b>解析 120–155 ms</b>（JIT 冷的第一轮 1115 ms ——
 * 一个会话里每种模型只解析一两次，JIT 根本热不起来）。这一段**不碰 GL**：
 * `sandbox/railbake-verify/ObjParseBench` 在一个**没有 Minecraft、没有 GL 上下文**的普通 JVM 里
 * 把同一个 `OptimizedModel.ObjModel.loadModel` 完整跑通了。</p>
 *
 * <p>只有最后一步 {@code RawModel.upload()}（建 VBO）必须在渲染线程 —— 所以这里搬的是"文本 → RawMesh"，
 * 建 VBO 那一半留给调用方。</p>
 *
 * <h2>为什么读<b>不</b>搬</h2>
 *
 * <p>{@code ResourceProvider} 背后是 {@code CustomResourceLoader.RESOURCE_CACHE}，一个**没有同步的**
 * {@code Object2ObjectAVLTreeMap}；从别的线程读会与渲染线程并发改同一个结构。所以
 * {@link ModelResourceLoader#createSource} 那一步（实测只要 <b>3 ms</b>）留在渲染线程，
 * 这里只搬解析。</p>
 *
 * <h2>纪律</h2>
 *
 * <ul>
 *   <li>失败<b>绝不静默</b>：worker 抛的异常会在 {@link #poll()} 里原样抛回渲染线程，
 *       调用方据此回退到同步解析（模型照样出现，只是这一轮没省下时间）；</li>
 *   <li>{@code null} 只有一个意思：**还没好**。调用方这一轮就当"模型未就绪"，
 *       下一轮再问 —— {@code CachedResource} 与 {@code getCachedVehicleResource} 那条链
 *       本来就用 {@code null} 表达"未就绪"；</li>
 *   <li>专用单线程 + daemon：解析是纯 CPU 的重活，多开线程只会和渲染线程、区块构建抢核；
 *       daemon 保证退出进程不用等它。</li>
 * </ul>
 *
 * <p>开关：{@code -Dmmtr.modelparseasync=false} 回到"全在渲染线程上解析"。</p>
 */
public final class MmtrAsyncModelParse<T> {

	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mmtr.modelparseasync", "true"));

	private static final ExecutorService WORKER = newDaemonWorker("MMTR-ModelParse");

	/**
	 * 造一个"专用单线程 + daemon"的线程池。
	 *
	 * <p>为什么允许第二条线程（notes/401）：车辆那条（解析 + 几何烘焙）一次就能占住 `MMTR-ModelParse`
	 * 几百毫秒，而钢轨烘焙的活是**突发**的（进世界那一瞬实测 42 次）。共用一条线程会让钢轨每次都要
	 * 排在车辆后面 —— 排队期间那些轨只能逐实例画（draw 数暴涨），比"慢一点烘好"更糟。
	 * 两条线程各自串行、互不排队，代价只是一个多出来的 daemon 线程。</p>
	 */
	public static ExecutorService newDaemonWorker(String threadName) {
		return Executors.newSingleThreadExecutor(runnable -> {
			final Thread thread = new Thread(runnable, threadName);
			thread.setDaemon(true);
			return thread;
		});
	}

	private final String label;
	private final Supplier<T> supplier;
	private final ExecutorService executor;
	private final AtomicReference<T> result = new AtomicReference<>();
	private final AtomicReference<Throwable> failure = new AtomicReference<>();
	private volatile boolean started;
	private volatile boolean done;
	/** 工作线程上那段活自己的耗时（纳秒）—— 给调用方的分段读数用（notes/401）。 */
	private volatile long elapsedNanos = -1;

	public MmtrAsyncModelParse(String label, Supplier<T> supplier) {
		this(label, supplier, WORKER);
	}

	public MmtrAsyncModelParse(String label, Supplier<T> supplier, ExecutorService executor) {
		this.label = label;
		this.supplier = supplier;
		this.executor = executor;
	}

	public static boolean isEnabled() {
		return ENABLED;
	}

	/** 工作线程上那段活自己的耗时（纳秒）；还没跑完返回 {@code -1}。 */
	public long elapsedNanos() {
		return elapsedNanos;
	}

	/**
	 * 现在就把作业交给工作线程。
	 *
	 * <p>不调也行 —— {@link #poll()} 第一次会自己提交。显式调只是为了让活早一个 tick 开始
	 * （调用方本来就要等下一轮才会来问结果）。</p>
	 */
	public void start() {
		if (!started) {
			started = true;
			executor.execute(() -> {
				final long startNanos = System.nanoTime();
				try {
					result.set(supplier.get());
				} catch (Throwable throwable) {
					failure.set(throwable);
					Init.LOGGER.warn("[MMTR-MODELASYNC] {} 失败 —— 这一轮回退到渲染线程上同步算（结果照常出现）", label, throwable);
				} finally {
					elapsedNanos = System.nanoTime() - startNanos;
					done = true;
					Init.LOGGER.info("[MMTR-MODELASYNC] {}: {} ms（线程 {}）",
							label, elapsedNanos / 1_000_000L, Thread.currentThread().getName());
				}
			});
		}
	}

	/**
	 * @return 作业结果；{@code null} = **还没好**（调用方这一轮按"未就绪"处理，下一轮再问）
	 * @throws Throwable 作业失败 —— 调用方必须回退到渲染线程上同步算，绝不能让结果消失
	 */
	@Nullable
	public T poll() throws Throwable {
		start();

		final Throwable throwable = failure.get();
		if (throwable != null) {
			throw throwable;
		}
		if (!done) {
			return null;
		}
		if (result.get() == null) {
			// 解析"成功"却没有结果：这条路不该出现，按失败处理去走同步兜底，而不是让调用方永远等下去。
			throw new IllegalStateException("后台解析 " + label + " 没有产出结果");
		}
		return result.get();
	}
}
