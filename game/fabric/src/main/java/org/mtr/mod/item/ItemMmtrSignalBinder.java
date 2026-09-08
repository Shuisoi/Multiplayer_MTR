package org.mtr.mod.item;

import org.mtr.core.operation.MmtrSignalsOp;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.ItemSettings;
import org.mtr.mapping.holder.ItemUsageContext;
import org.mtr.mapping.holder.World;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.block.BlockSignalBase;
import org.mtr.mod.mmtr.MmtrSignalBlocks;

/**
 * MMTR covered-bind tool (遮罩绑定工具, wooden shovel look):
 * first right-click a placed MTR signal light (stores its position), then right-click a rail
 * node to register the light in the MMTR wayside signal table, covered-bound (BOUND) to the
 * rail leaving that node which best matches the light facing. Used where lights cannot sit
 * next to their read rail (dense layouts, gantries 2+ blocks away, overhangs).
 */
public class ItemMmtrSignalBinder extends ItemBlockClickingBase {

	public ItemMmtrSignalBinder(ItemSettings itemSettings) {
		super(itemSettings);
	}

	@Override
	protected void onStartClick(ItemUsageContext context, CompoundTag compoundTag) {
		// First click: nothing to do besides remembering the light position (done by the base class)
	}

	@Override
	protected void onEndClick(ItemUsageContext context, BlockPos posEnd, CompoundTag compoundTag) {
		final World world = context.getWorld();
		final BlockState lightState = world.getBlockState(posEnd);
		if (!(lightState.getBlock().data instanceof BlockSignalBase)) {
			return;
		}
		final BlockPos nodePos = context.getBlockPos();
		Init.sendMessageC2S(
			OperationProcessor.MMTR_SIGNALS,
			world.getServer(),
			world,
			new MmtrSignalsOp(posEnd.getX(), posEnd.getY(), posEnd.getZ(), BlockSignalBase.getAngle(lightState), MmtrSignalBlocks.aspectsOf(lightState.getBlock().data), "set", "", true, nodePos.getX(), nodePos.getY(), nodePos.getZ()),
			null,
			null
		);
	}

	@Override
	protected boolean clickCondition(ItemUsageContext context) {
		final Object block = context.getWorld().getBlockState(context.getBlockPos()).getBlock().data;
		if (context.getStack().getOrCreateTag().contains(TAG_POS)) {
			// Second click must land on a rail node (continuous movement nodes extend BlockNode)
			return block instanceof BlockNode;
		} else {
			// First click must land on a placed signal light / semaphore
			return MmtrSignalBlocks.isSignalLight(block);
		}
	}
}
