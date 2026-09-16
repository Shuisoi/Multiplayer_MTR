package org.mtr.core.servlet;

/**
 * 一条"要在模拟线程上跑"的网页/桥接任务。
 *
 * <h2>为什么需要这个类型（notes/172）</h2>
 * <p>网页请求的活儿不是在 Jetty 线程上算的：{@link ServletBase} 把它塞回
 * {@link org.mtr.core.simulation.Simulator}，由**模拟线程**在下一次 tick 里执行。
 * 而嵌入式运行时（模组里 {@code useThreadedSimulation=false}）模拟线程**就是 MC 服务端主线程** ——
 * 于是一个请求有多贵，直接等于它从游戏 tick 里拿走多少毫秒。</p>
 *
 * <p>所以这里必须带上 {@link #label}（接口名）：排空时才能对"是哪一路接口吃掉了 tick"点名，
 * 而不是只留下一句"这一 tick 慢了"。之前没有名字，任何耗时都只能算在"网页"这个笼统的头上。</p>
 */
public final class WebRun implements Runnable {

	/** 接口名（`SystemMapServlet` 的那个 endpoint）。只用于日志点名。 */
	public final String label;

	private final Runnable body;

	public WebRun(String label, Runnable body) {
		this.label = label;
		this.body = body;
	}

	@Override
	public void run() {
		body.run();
	}
}
