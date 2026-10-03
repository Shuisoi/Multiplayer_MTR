import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.engine.discovery.DiscoverySelectors;

import java.io.PrintWriter;

/**
 * 离线跑 fabric 侧的 JUnit 用例（不经过 gradle，因而不碰 {@code build/classes}）。
 *
 * <p>为什么需要它：dev 会话在跑时，{@code gradlew :fabric:test} 会把新编译的类写进
 * {@code build/classes/java/main}，而那个目录正在**活着的**客户端/服务端 classpath 上
 * （notes/195/281 实测过后果）。所以用例只能在沙箱里编译 + 用 JUnit 的 Launcher API 直接跑。</p>
 *
 * <p>用法：{@code java ... FabricTestRunner <全限定类名>...}</p>
 */
public final class FabricTestRunner {

	public static void main(String[] args) {
		final LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
		for (final String className : args) {
			builder.selectors(DiscoverySelectors.selectClass(className));
		}
		final LauncherDiscoveryRequest request = builder.build();
		final Launcher launcher = LauncherFactory.create();
		final SummaryGeneratingListener listener = new SummaryGeneratingListener();
		launcher.execute(request, listener);

		final TestExecutionSummary summary = listener.getSummary();
		final PrintWriter out = new PrintWriter(System.out, true);
		summary.printTo(out);
		summary.printFailuresTo(out, 20);
		System.out.println("[fabric-tests] 通过 " + summary.getTestsSucceededCount() + " / 失败 " + summary.getTestsFailedCount()
			+ " / 跳过 " + summary.getTestsSkippedCount() + " / 容器失败 " + summary.getContainersFailedCount());
		System.exit(summary.getTotalFailureCount() == 0 ? 0 : 1);
	}
}
