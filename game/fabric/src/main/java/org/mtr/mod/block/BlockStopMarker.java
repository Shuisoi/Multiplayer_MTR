package org.mtr.mod.block;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mapping.mapper.BlockExtension;
import org.mtr.mapping.mapper.BlockWithEntity;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mapping.tool.HolderBase;
import org.mtr.mod.BlockEntityTypes;
import org.mtr.mod.Blocks;
import org.mtr.mod.mmtr.stopmark.MmtrStopMarkerSync;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * **停车对标牌子**（MMTR）：放在站台上，声明"这个辆数、这个方向的车，车头要停在哪一格"。
 *
 * <h3>为什么需要它</h3>
 * <p>站台上装了屏蔽门之后，"列车停在哪儿"不再是随便的事 —— 门要对上洞（{@code notes/417}）。
 * 引擎原来的口径是"车头停在站台轨在行进方向上的远端端点"（{@code stopFraction = 1.0}），
 * 那是"能停"不是"停对"。牌子把这件事**交还给站台**：一块牌子 = 一个辆数（+ 由位置决定的方向），
 * 上面记着车头该停在哪（由牌子自己的位置反解），自动车据此停、司机据此对标。</p>
 *
 * <h3>为什么一块牌子只记一个辆数</h3>
 * <p>门洞在这类车上是一张**与编组辆数无关的 6 米栅格**（实测：整列 30 个门洞都在
 * {@code 车头端面后 5.0245 + 6.000k} 米，见 {@code notes/421}）。所以"哪一段对得上门"对任何辆数
 * 都是同一条栅格；不同辆数要不同牌子，只是因为**站台长短不一、要让列车在站台上居中**这个人选择。</p>
 *
 * <h3>方向从哪来（不需要玩家多按一下）</h3>
 * <p>牌子不存方向：**位置自己带着方向**。牌子记的是"车头后方 1.25 米的驾驶室侧窗"，
 * 而车头在列车前端 —— 所以牌子落在站台轨的**前半段**，就是"车朝另一端开"的那一趟
 * （判据在引擎的 {@code MmtrStopMarkerGeometry.deriveTowardEnd0}）。玩家要做的就是"这一辆数、
 * 这个方向"找对半段把牌子放下，不用再学一个交互。</p>
 *
 * <h3>三个样式（一个方块、三个模型）</h3>
 * <p>立杆 / 吊挂 / 贴墙只是 {@link #VARIANT} 的取值，方块 id 是同一个 —— 铺一面站台九块牌子时
 * 它们必须有同一套属性与同一条登记路径，分成三个方块会让"辆数/归属"这套账抄三份。</p>
 */
public class BlockStopMarker extends BlockExtension implements DirectionHelper, BlockWithEntity {

	/** 样式（＝方块模型）：立杆 / 吊挂 / 贴墙。名字只影响观感与模型选择。 */
	public static final String[] STYLE_NAMES = {"立杆", "吊挂", "贴墙"};

	/** 样式序号，同时也是 blockstate 里的 {@code variant}（0..2）。 */
	public static final IntegerProperty VARIANT = IntegerProperty.of("variant", 0, STYLE_NAMES.length - 1);

	/** 牌子认的辆数范围（与引擎 {@code MmtrStopMarkerRegistry} 一致）。 */
	public static final int CARS_MIN = 2;
	public static final int CARS_MAX = 10;

	/** 参照点（驾驶室侧窗）相对车头端面的偏移，米 —— 牌子位置 → 车头停点 = 牌子位置 − 这个数。 */
	public static final double DEFAULT_DATUM_M = 1.25;

	public BlockStopMarker() {
		super(Blocks.createDefaultBlockSettings(false).nonOpaque());
	}

	@Nonnull
	@Override
	public BlockState getPlacementState2(ItemPlacementContext itemPlacementContext) {
		/*
		 * 放下来是哪个**样式**，按"点到哪一面"自动定 —— 这是"手动摆得出来"的关键（用户 2026-10-13 现场问的
		 * 「倒挂为啥我手动摆没法摆」）：物品本身不带样式，改样式只能靠放置逻辑或刷子。
		 *
		 * <ul>
		 *   <li>点到**天花板/雨棚底面**（{@code DOWN}）⇒ {@code variant=1} 吊挂式：
		 *       模型的吊杆正好占本格子上沿 4/16，于是**贴住上面那一块**，看起来就是吊在吊顶下面；</li>
		 *   <li>其余（点地面、点侧面、点空气相邻面）⇒ {@code variant=0} 立杆式；</li>
		 *   <li>贴墙式（2）仍留给刷子潜行右键换 —— "点到墙"与"点到别的东西旁边"在几何上分不干净
		 *       （一块方块四面都可能是墙），自动判会经常判错。</li>
		 * </ul>
		 *
		 * <p><b>注意 `variant` 是方块状态、不是 NBT</b>：{@code /data merge block} 改不了它，
		 * 要改只能用 {@code /setblock x y z mtr:stop_marker[facing=…,variant=…]} 或刷子。</p>
		 *
		 * <p>{@code facing} 取玩家朝向的**反方向**：牌面朝 {@code facing}（模型与牌面文字都按这条走），
		 * 所以这样取的结果是"放完正面朝向自己" —— 与 MTR 那些牌子（写 {@code playerFacing}、
		 * 但牌面朝 {@code facing.getOpposite()}）在游戏里看到的效果一致。反过来取会让每块牌子都背面朝人；
		 * 想让牌面**顺着轨道朝来车**，就站在来车方向那一侧放（或直接 {@code /setblock} 指定 facing）。</p>
		 */
		return getDefaultState2()
			.with(new Property<>(FACING.data), itemPlacementContext.getPlayerFacing().getOpposite().data)
			.with(new Property<>(VARIANT.data), itemPlacementContext.getSide() == Direction.DOWN ? 1 : 0);
	}

	/**
	 * 刷子三件事（服务端判定见 {@link IBlock#checkHoldingBrush}）：
	 * <ol>
	 *   <li>普通右键：把**辆数**往上拨一格（{@code 未设定 → 2 → 3 → … → 10 → 未设定}），拨完立刻重新登记；</li>
	 *   <li>潜行右键：换**样式**（立杆 → 吊挂 → 贴墙）；</li>
	 *   <li>手里没有待绑的站台时，右键就是拨辆数；先用刷子点一下站台方块（见
	 *       {@link MmtrStopMarkerSync}）记下归属，再来右键牌子就是**写入归属**。</li>
	 * </ol>
	 */
	@Nonnull
	@Override
	public ActionResult onUse2(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		return IBlock.checkHoldingBrush(world, player, () -> {
			// 注意：这个方法所在的作用域里有本类自己的内部类 BlockEntity，所以映射层的那个必须写全名，
			// 否则 `final BlockEntity entity = world.getBlockEntity(pos);` 会解析成内部类而编译不过。
			final org.mtr.mapping.holder.BlockEntity entity = world.getBlockEntity(pos);
			if (entity == null || !(entity.data instanceof BlockEntity)) {
				return;
			}
			final BlockEntity blockEntity = (BlockEntity) entity.data;
			final ServerPlayerEntity serverPlayer = ServerPlayerEntity.cast(player);
			if (player.isSneaking()) {
				// 换样式：改方块状态（模型跟着换），登记表不用动 —— 牌的"车头停点"与样式无关。
				// ★ 这里**不取**待绑站台：潜行那一下只是"换样式"，取走就等于把玩家刚点好的归属
				//   悄悄吃掉（而且他看不出为什么下一次右键变成拨辆数了）。
				world.setBlockState(pos, state.with(new Property<>(VARIANT.data), nextStyle(IBlock.getStatePropertySafe(state, VARIANT))));
				MmtrStopMarkerSync.feedback(serverPlayer, "样式 → " + STYLE_NAMES[IBlock.getStatePropertySafe(world.getBlockState(pos), VARIANT)]);
				return;
			}
			final MmtrStopMarkerSync.BoundPlatform bound = MmtrStopMarkerSync.takePendingPlatform(player.getUuid());
			if (bound != null) {
				blockEntity.setPlatform(bound.platformId(), bound.platformHex());
				MmtrStopMarkerSync.feedback(serverPlayer, "已归属站台 " + bound.platformHex() + "（" + bound.name() + "）");
				MmtrStopMarkerSync.syncOne(world, pos);
			} else {
				blockEntity.setCars(nextCars(blockEntity.getCars()));
				MmtrStopMarkerSync.feedback(serverPlayer, blockEntity.getCars() == 0 ? "辆数 → 未设定（不计入停车基准）" : "辆数 → " + blockEntity.getCars() + " 辆");
				MmtrStopMarkerSync.syncOne(world, pos);
			}
		});
	}

	/** 辆数往上拨一格，到头回到"未设定"。 */
	public static int nextCars(int cars) {
		return cars >= CARS_MAX ? 0 : Math.max(CARS_MIN, cars + 1);
	}

	/** 样式往上拨一格，到头回到第一个。 */
	public static int nextStyle(int variant) {
		return variant >= STYLE_NAMES.length - 1 ? 0 : variant + 1;
	}

	@Nonnull
	@Override
	public BlockEntityExtension createBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new BlockEntity(blockPos, blockState);
	}

	@Override
	public void addBlockProperties(List<HolderBase<?>> properties) {
		properties.add(FACING);
		properties.add(VARIANT);
	}

	/**
	 * 牌子的方块实体：**牌子自己的那点事实**（辆数 / 归属 / 参照点 / 牌面文字）。
	 *
	 * <p>头等重要的"车头停点"（{@code fraction}）**不在这里**：那要沿着站台股道的弧长投影才
	 * 算得出来，而站台股道是引擎的数据（游戏侧只有一份镜像）。所以这里只记"牌子在哪、
	 * 归谁、认几辆"，投影由引擎在登记时做（{@code notes/422}）。</p>
	 *
	 * <p>字段全部可以从服务端控制台用 {@code /data merge block …} 直接写 —— 这正是批量铺牌子的
	 * 生成器唯一要做的事（{@code /setblock} + {@code /data merge block}），不需要另开一条指令通道。</p>
	 */
	public static class BlockEntity extends BlockEntityExtension {

		/** 本牌对应的编组辆数 2..10；0 = 未设定（不计入停车基准）。 */
		private int cars;
		/** 显式归属的站台 id（引擎 {@code Platform.getId()}）；0 = 按位置就近解析。 */
		private long platformId;
		/** 归属站台的 hex，只为人看（排障时一眼知道绑到哪条站台）。 */
		private String platformHex = "";
		/** 参照点相对车头端面的偏移（米），默认驾驶室侧窗 1.25。 */
		private double datumM = DEFAULT_DATUM_M;
		/** 牌面文字；空 = 按辆数显示。 */
		private String label = "";

		private static final String KEY_CARS = "cars";
		private static final String KEY_PLATFORM_ID = "platformId";
		private static final String KEY_PLATFORM_HEX = "platformHex";
		private static final String KEY_DATUM_M = "datumM";
		private static final String KEY_LABEL = "label";

		public BlockEntity(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.STOP_MARKER.get(), pos, state);
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			cars = compoundTag.getInt(KEY_CARS);
			platformId = compoundTag.getLong(KEY_PLATFORM_ID);
			platformHex = compoundTag.getString(KEY_PLATFORM_HEX);
			// 老存档没有 datumM（读到 0）时回落到默认参照点：0 会让"车头 = 牌子位置"，
			// 那不是任何一款车的参照点，只会静默停偏。
			final double readDatumM = compoundTag.getDouble(KEY_DATUM_M);
			datumM = readDatumM > 0 ? readDatumM : DEFAULT_DATUM_M;
			label = compoundTag.getString(KEY_LABEL);
			super.readCompoundTag(compoundTag);
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			compoundTag.putInt(KEY_CARS, cars);
			compoundTag.putLong(KEY_PLATFORM_ID, platformId);
			compoundTag.putString(KEY_PLATFORM_HEX, platformHex);
			compoundTag.putDouble(KEY_DATUM_M, datumM);
			compoundTag.putString(KEY_LABEL, label);
			super.writeCompoundTag(compoundTag);
		}

		public int getCars() {
			return cars;
		}

		public void setCars(int cars) {
			this.cars = cars >= CARS_MIN && cars <= CARS_MAX ? cars : 0;
			markDirty2();
		}

		public long getPlatformId() {
			return platformId;
		}

		public void setPlatform(long platformId, String platformHex) {
			this.platformId = platformId;
			this.platformHex = platformHex == null ? "" : platformHex;
			markDirty2();
		}

		public String getPlatformHex() {
			return platformHex;
		}

		public double getDatumM() {
			return datumM;
		}

		public String getLabel() {
			return label;
		}

		/** 牌面要显示的文字（`label` 为空时按辆数显示）。 */
		public String displayText() {
			if (!label.isEmpty()) {
				return label;
			}
			return cars == 0 ? "—" : cars + "辆";
		}
	}
}
