package org.mtr.mod.item;

import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.ItemSettings;
import org.mtr.mapping.holder.ItemUsageContext;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.Entity;
import org.mtr.mapping.mapper.EntityHelper;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.holder.TextFormatting;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.mmtr.MmtrSignalBlocks;

import javax.annotation.Nullable;
import java.util.List;

/**
 * MMTR 轨道分配工具（木斧）: 把一盏信号灯**分配到某一条轨**上。
 *
 * <h3>与绑定工具（铲子）的分工</h3>
 * <p>两把工具各管一件事，互不重叠：</p>
 * <ul>
 *   <li><b>信号连接器（铲子）</b>：只管"把灯登记到这个**节点**上"（第一下点灯、第二下点节点）。
 *       灯守哪根轨由引擎按站位与朝向在节点的几条腿里推断 —— 这是原版的做法。</li>
 *   <li><b>本工具（木斧）</b>：只管"这盏灯守**哪条轨**"（第一下点灯、第二下点轨）。
 *       分配是**显式**的：引擎不再推断，你说哪条就是哪条，方向由几何定
 *       （列车必然从灯所站的那一端进入）。</li>
 * </ul>
 * <p>分开的理由很实际：原版那套"朝向 + 90° 扇区"在密集站场里说不准 ——
 * 灯站在道岔旁、面朝方向与线路走向差几十度时，它到底守哪条腿就成了猜。分轨工具把这件事
 * 从"猜"变成"指"。</p>
 *
 * <h3>操作</h3>
 * <ul>
 *   <li>右键**信号灯** → 记下它</li>
 *   <li>右键**轨道/节点** → 把这条轨加到这盏灯的守轨里（一灯多轨就多点几次）</li>
 *   <li>Shift + 右键**信号灯** → 解除这盏灯的**全部**守轨，回到按站位与朝向推断</li>
 * </ul>
 */
public class ItemMmtrRailBindingTool extends ItemBlockClickingBase {

	/**
	 * 分配的轨离灯最远能有多少格。
	 *
	 * <p>引擎的选轨是按"灯投影到这条轨上"算的，太远就投影不上，绑定会被忽略（灯退回推断、
	 * 或者干脆没有区间），而玩家只看到"分配之后颜色就不对了"。所以在**分配这一端**就挡住，
	 * 比让引擎事后忽略好。实测踩到过：灯在 {@code -70,-58,-168}，木斧却分配了 30 多米外的
	 * {@code (-67,-139)->(-67,-103)} 那条库内 stub。</p>
	 */
	private static final double MAX_ASSIGN_DISTANCE_M = 3.0;

	public ItemMmtrRailBindingTool(ItemSettings itemSettings) {
		super(itemSettings);
	}

	/**
	 * Shift + 右键：解除这盏灯的**全部**守轨。
	 *
	 * <p>这里刻意**不**走基类的"两下点选"流程：那套流程把第一下当"选中"、第二下当"动作"，
	 * 而"解除全部"是一下就要做完的事，混进去会让"Shift 点灯"变成"选中灯"。
	 * 所以潜行时自己处理并返回，基类不会再看到这次点击。</p>
	 *
	 * <p>点哪盏灯：优先用工具里已选中的那盏（先点灯、再 Shift 点它，手感一致）；
	 * 还没选中时，就直接用 Shift 点到的这盏 —— 允许"一步解除"。</p>
	 */
	@Override
	public org.mtr.mapping.holder.ActionResult useOnBlock2(ItemUsageContext context) {
		if (!context.getWorld().isClient()) {
			final PlayerEntity player = context.getPlayer();
			if (player != null && player.isSneaking()) {
				final BlockState clickedState = context.getWorld().getBlockState(context.getBlockPos());
				final CompoundTag compoundTag = context.getStack().getOrCreateTag();
				final long selected = compoundTag.getLong(TAG_POS);
				final BlockPos lampPos;
				if (selected != 0) {
					lampPos = BlockPos.fromLong(selected);
				} else if (MmtrSignalBlocks.isSignalLight(clickedState.getBlock().data)) {
					lampPos = context.getBlockPos();
				} else {
					// 既没选中、Shift 点的又不是灯：没什么可解除的，交给基类按普通点选处理
					return super.useOnBlock2(context);
				}
				clearAll(context, lampPos);
				compoundTag.remove(TAG_POS);
				return org.mtr.mapping.holder.ActionResult.SUCCESS;
			}
		}
		return super.useOnBlock2(context);
	}

	@Override
	protected void onStartClick(ItemUsageContext context, CompoundTag compoundTag) {
		// 第一下：基类已把灯的位置记进 NBT，这里只回一句"选中的是哪盏、它现在守几条轨"
		final BlockPos lampPos = context.getBlockPos();
		final Simulator simulator = simulatorOf(context.getWorld());
		if (simulator == null) {
			return;
		}
		final org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry entry =
			simulator.mmtrSignals.get(lampPos.getX(), lampPos.getY(), lampPos.getZ());
		final int guarded = entry == null ? 0 : entry.rails.size();
		send(context, "§a[MMTR] 已选中灯 " + shortPos(lampPos) + "（现有守轨 " + guarded + " 条）—— 右键轨道开始分配");
	}

	@Override
	protected void onEndClick(ItemUsageContext context, BlockPos posEnd, CompoundTag compoundTag) {
		final BlockPos lampPos = BlockPos.fromLong(compoundTag.getLong(TAG_POS));
		if (lampPos.asLong() == 0) {
			return;
		}
		final String railHex = resolveRailAt(context.getWorld(), context.getBlockPos(), context.getPlayer());
		if (railHex == null) {
			send(context, "§e[MMTR] 这里没有可分配的轨道（请点在轨或轨节点上）");
			return;
		}
		/*
		 * 分配的轨必须**离这盏灯足够近**。
		 *
		 * <p>为什么必须挡：{@link #facingRail} 在节点上只按玩家视线的角度挑轨，不看距离 —— 在一处
		 * 密集站场里点节点，很容易挑到几十米开外的另一条轨。引擎那边对这种绑定是"投影不到就忽略"，
		 * 于是这盏灯要么退回推断、要么干脆没有区间，而玩家看到的只是"分配之后颜色就不对了"。
		 * 实测就踩到过：灯在 {@code -70,-58,-168}，却被分配了 {@code (-67,-139)->(-67,-103)}
		 * 那条库内 stub（相距 30 多米），绑定被忽略、灯没有区间。</p>
		 */
		final Simulator simulator = simulatorOf(context.getWorld());
		final double railDistance = simulator == null ? Double.NaN : distanceToRail(simulator, railHex, lampPos);
		if (!Double.isNaN(railDistance) && railDistance > MAX_ASSIGN_DISTANCE_M) {
			send(context, "§c[MMTR] 这条轨离灯 " + Math.round(railDistance) + " 格，太远了，没有分配（上限 "
				+ (int) MAX_ASSIGN_DISTANCE_M + " 格）。请点在灯旁边那条轨上。");
			return;
		}
		runCommand(context, "signal bind " + lampPos.getX() + " " + lampPos.getY() + " " + lampPos.getZ() + " --add=" + railHex,
			"§a[MMTR] 已把这条轨分配给灯 " + shortPos(lampPos)
				+ (Double.isNaN(railDistance) ? "" : "（离灯 " + Math.round(railDistance) + " 格）"));
	}

	/**
	 * 一条轨离某一格有多近（米）：取轨的全部采样点到该格中心的最近距离。
	 *
	 * <p>用采样点而不是两个端点：轨是圆弧，端点可能很远而中间紧贴这一格 —— 按端点算会把
	 * "就在灯旁边的那条轨"判成太远。</p>
	 */
	private static double distanceToRail(Simulator simulator, String railHex, BlockPos blockPos) {
		if (railHex == null || railHex.isEmpty()) {
			return Double.NaN;
		}
		final org.mtr.core.data.Rail rail = simulator.rails.stream()
			.filter(candidate -> candidate.getHexId().equals(railHex)).findFirst().orElse(null);
		if (rail == null) {
			return Double.NaN;
		}
		final double x = blockPos.getX() + 0.5;
		final double y = blockPos.getY() + 0.5;
		final double z = blockPos.getZ() + 0.5;
		final Position[] points = rail.mmtrOrderedPositions();
		if (points == null || points.length == 0) {
			return Double.NaN;
		}
		double best = Double.MAX_VALUE;
		for (final Position point : points) {
			final double dx = point.getX() + 0.5 - x;
			final double dy = point.getY() + 0.5 - y;
			final double dz = point.getZ() + 0.5 - z;
			best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		return best;
	}

	/** Shift 右键：解除这盏灯的全部守轨。 */
	private void clearAll(ItemUsageContext context, BlockPos lampPos) {
		runCommand(context, "signal bind " + lampPos.getX() + " " + lampPos.getY() + " " + lampPos.getZ() + " --clear",
			"§e[MMTR] 已解除灯 " + shortPos(lampPos) + " 的全部守轨（回到按站位与朝向推断）");
	}

	@Override
	protected boolean clickCondition(ItemUsageContext context) {
		final Object block = context.getWorld().getBlockState(context.getBlockPos()).getBlock().data;
		if (context.getStack().getOrCreateTag().contains(TAG_POS)) {
			// 第二下：点在轨（或轨节点）上
			return block instanceof BlockNode || nearestRail(context.getWorld(), context.getBlockPos()) != null;
		}
		// 第一下：点在信号灯上
		return MmtrSignalBlocks.isSignalLight(block);
	}

	/**
	 * 走统一的指令通道执行（与网页指令栏、控制台是同一条路）。
	 *
	 * <p>好处是只有一套绑定语义：`--add` / `--clear` 怎么作用于引擎，两边完全一致，
	 * 不会出现"游戏里这样绑、网页里那样绑"的分叉。</p>
	 */
	private void runCommand(ItemUsageContext context, String command, String successHint) {
		final Simulator simulator = simulatorOf(context.getWorld());
		if (simulator == null) {
			return;
		}
		final org.mtr.core.mmtr.command.MmtrCommandDispatcher.Result result = simulator.mmtrExecuteCommand(command);
		if (!result.ok) {
			send(context, "§c[MMTR] " + (result.lines.isEmpty() ? "指令未执行" : result.lines.get(0)));
			return;
		}
		send(context, successHint);
		result.lines.forEach(line -> send(context, "§7[MMTR] " + line));
	}

	/**
	 * 点到的这一格对应哪条轨。
	 *
	 * <p>两种点法都支持：点在**轨节点**上 → 取"朝向玩家视线"的那条腿（与客户端
	 * {@code getFacingRailAndBlockPos} 同一判据，于是"看着哪条就分配哪条"）；
	 * 点在轨的其它位置 → 取离这一格最近的那根轨。</p>
	 */
	@Nullable
	private static String resolveRailAt(World world, BlockPos blockPos, @Nullable PlayerEntity player) {
		final Simulator simulator = simulatorOf(world);
		if (simulator == null) {
			return null;
		}
		final BlockState state = world.getBlockState(blockPos);
		if (state.getBlock().data instanceof BlockNode) {
			final Rail aimed = facingRail(simulator, Init.blockPosToPosition(blockPos), player);
			if (aimed != null) {
				return aimed.getHexId();
			}
		}
		return nearestRail(world, blockPos);
	}

	/** 节点上"朝着玩家视线"的那条轨（与客户端按视线选轨同一判据）。 */
	@Nullable
	private static Rail facingRail(Simulator simulator, Position node, @Nullable PlayerEntity player) {
		/*
		 * 用 `var` 而不是写出类型：引擎 jar 自带**重定位过的 fastutil**（org.mtr.libraries.*），
		 * 在这里写出 it.unimi.dsi.fastutil... 会与游戏自己的那份撞类型（编译期直接不兼容）。
		 * 这是本项目里反复出现的一条注意事项。
		 */
		final var neighbours = simulator.positionsToRail.get(node);
		if (neighbours == null || neighbours.isEmpty()) {
			return null;
		}
		final double playerAngle = player == null ? 0 : EntityHelper.getYaw(new Entity(player.data)) + 90;
		Rail best = null;
		double bestAngle = 720;
		for (final var entry : neighbours.entrySet()) {
			final Position end = entry.getKey();
			final double angle = Math.abs(Math.toDegrees(Math.atan2(end.getZ() - node.getZ(), end.getX() - node.getX())) - playerAngle) % 360;
			final double clamped = angle > 180 ? 360 - angle : angle;
			if (clamped < bestAngle) {
				bestAngle = clamped;
				best = entry.getValue();
			}
		}
		return best;
	}

	/** 离这一格最近的轨（点在轨身上时用）：取轨的两个端点比较距离。 */
	@Nullable
	private static String nearestRail(World world, BlockPos blockPos) {
		final Simulator simulator = simulatorOf(world);
		if (simulator == null) {
			return null;
		}
		final double x = blockPos.getX() + 0.5;
		final double y = blockPos.getY() + 0.5;
		final double z = blockPos.getZ() + 0.5;
		String best = null;
		double bestDistance = 6 * 6;
		for (final Rail rail : simulator.rails) {
			final Position[] ends = rail.mmtrOrderedPositions();
			if (ends == null || ends.length < 2) {
				continue;
			}
			for (final Position end : ends) {
				final double dx = end.getX() + 0.5 - x;
				final double dy = end.getY() + 0.5 - y;
				final double dz = end.getZ() + 0.5 - z;
				final double distance = dx * dx + dy * dy + dz * dz;
				if (distance < bestDistance) {
					bestDistance = distance;
					best = rail.getHexId();
				}
			}
		}
		return best;
	}

	@Nullable
	private static Simulator simulatorOf(World world) {
		final org.mtr.core.Main main = Init.getMain();
		return main == null ? null : main.getSimulator(Init.getWorldId(world));
	}

	/** 把提示发给用工具的玩家。 */
	private static void send(ItemUsageContext context, String message) {
		final PlayerEntity player = context.getPlayer();
		if (player != null) {
			player.sendMessage(new Text(TextHelper.literal(message).data), false);
		}
	}

	private static String shortPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	/** 工具提示：说清两个动作的区别。 */
	@Override
	public void addTooltips(org.mtr.mapping.holder.ItemStack stack, @Nullable World world, List<MutableText> tooltip, org.mtr.mapping.holder.TooltipContext options) {
		super.addTooltips(stack, world, tooltip, options);
		tooltip.add(TextHelper.literal("右键信号灯 = 选中；右键轨/节点 = 把这条轨分配给它").formatted(TextFormatting.GRAY));
		tooltip.add(TextHelper.literal("Shift + 右键 = 解除这盏灯的全部守轨").formatted(TextFormatting.GRAY));
	}
}
