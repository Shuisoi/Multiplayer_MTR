package org.mtr.init;

import net.fabricmc.api.ModInitializer;
import org.mtr.mod.Init;
import org.mtr.mod.mmtr.MmtrChunkTracker;
import org.mtr.mod.mmtr.MmtrCommandExecutor;
import org.mtr.mod.mmtr.MmtrPlayerSessions;
import org.mtr.mod.mmtr.MmtrRouteMirror;
import org.mtr.mod.mmtr.MmtrSignalSync;

public final class MTR implements ModInitializer {

	@Override
	public void onInitialize() {
		Init.init();
		MmtrChunkTracker.register();
		MmtrSignalSync.register();
		MmtrCommandExecutor.register();
		MmtrPlayerSessions.register();
		MmtrRouteMirror.register();
	}
}
