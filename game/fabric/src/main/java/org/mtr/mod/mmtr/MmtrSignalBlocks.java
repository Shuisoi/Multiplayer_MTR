package org.mtr.mod.mmtr;

import org.mtr.mod.block.BlockSignalBase;
import org.mtr.mod.block.BlockSignalLight2Aspect1;
import org.mtr.mod.block.BlockSignalLight2Aspect2;
import org.mtr.mod.block.BlockSignalLight2Aspect3;
import org.mtr.mod.block.BlockSignalLight2Aspect4;
import org.mtr.mod.block.BlockSignalLight3Aspect1;
import org.mtr.mod.block.BlockSignalLight3Aspect2;
import org.mtr.mod.block.BlockSignalLight4Aspect1;
import org.mtr.mod.block.BlockSignalLight4Aspect2;
import org.mtr.mod.block.BlockSignalSemaphore1;
import org.mtr.mod.block.BlockSignalSemaphore2;

/**
 * MMTR shared rules for recognising placed MTR signal blocks and deriving their aspect count,
 * used by both the covered-bind tool (ItemMmtrSignalBinder) and the {@code signals scan}
 * executor so both stay consistent.
 */
public final class MmtrSignalBlocks {

	private MmtrSignalBlocks() {
	}

	/** Accepts every light/semaphore that extends BlockSignalBase (the whole MTR signal family). */
	public static boolean isSignalLight(Object block) {
		return block instanceof BlockSignalBase;
	}

	public static int aspectsOf(Object block) {
		if (block instanceof BlockSignalLight4Aspect1 || block instanceof BlockSignalLight4Aspect2) {
			return 4;
		}
		if (block instanceof BlockSignalLight3Aspect1 || block instanceof BlockSignalLight3Aspect2 || block instanceof BlockSignalSemaphore2) {
			return 3;
		}
		if (block instanceof BlockSignalLight2Aspect1 || block instanceof BlockSignalLight2Aspect2 || block instanceof BlockSignalLight2Aspect3 || block instanceof BlockSignalLight2Aspect4 || block instanceof BlockSignalSemaphore1) {
			return 2;
		}
		return 2;
	}
}
