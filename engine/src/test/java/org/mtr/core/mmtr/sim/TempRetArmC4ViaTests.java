package org.mtr.core.mmtr.sim;

import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 回库现场取证的一档：**N4 带经由点**（4 圈 + 回库）。逻辑与打印都在 {@link TempRetArms}。
 *
 * <p>一档一个类是为了能并行：Gradle 的 {@code maxParallelForks} 按**测试类**分发
 * （{@code -Dmmtr.testForks=N}）；副本目录名由这里显式给出，两臂不会撞在同一个目录上。</p>
 */
public final class TempRetArmC4ViaTests {

	@Test
	public void arm() throws IOException {
		TempRetArms.run("N4 带经由点", "c4-via", "ret-c4-via.json", 4);
	}
}