package org.mtr.init;

import net.fabricmc.api.ModInitializer;
import org.mtr.mod.Init;
import org.mtr.mod.mmtr.MmtrChunkTracker;
import org.mtr.mod.mmtr.MmtrCommandExecutor;
import org.mtr.mod.mmtr.MmtrPlayerSessions;

public final class MTR implements ModInitializer {

	@Override
	public void onInitialize() {
		Init.init();
		MmtrChunkTracker.register();
		MmtrCommandExecutor.register();
		MmtrPlayerSessions.register();
	}
}
