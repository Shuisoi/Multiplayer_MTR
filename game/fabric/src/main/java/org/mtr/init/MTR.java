package org.mtr.init;

import net.fabricmc.api.ModInitializer;
import org.mtr.mod.Init;
import org.mtr.mod.mmtr.MmtrChunkTracker;
import org.mtr.mod.mmtr.MmtrCommandExecutor;
import org.mtr.mod.mmtr.MmtrPlayerSessions;
import org.mtr.mod.mmtr.MmtrRouteMirror;
import org.mtr.mod.mmtr.MmtrSignalSync;
import org.mtr.mod.mmtr.MmtrTickProbe;
import org.mtr.mod.mmtr.MmtrVehicleMotionSync;

public final class MTR implements ModInitializer {

	@Override
	public void onInitialize() {
		Init.init();
		MmtrChunkTracker.register();
		MmtrSignalSync.register();
		MmtrCommandExecutor.register();
		MmtrPlayerSessions.register();
		/*
		 * 列车运动流（notes/369 ①）。**必须排在 MmtrRouteMirror.register() 之前**：
		 * 性能探针靠"MmtrRouteMirror 的钩子最后跑"来收尾（{@code MmtrTickProbe.onHooksFinished}），
		 * 插到它后面会让探针读数随这个文件的顺序变。
		 */
		MmtrVehicleMotionSync.register();
		MmtrRouteMirror.register();
		/*
		 * 性能探针（notes/337）。放最后：它只挂 START_SERVER_TICK，靠 MmtrRouteMirror 的包裹收尾，
		 * 不参与 END_SERVER_TICK 的注册顺序 —— 读数不随这个文件的顺序变。
		 */
		MmtrTickProbe.register();
	}
}
