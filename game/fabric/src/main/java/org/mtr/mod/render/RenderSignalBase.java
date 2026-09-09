package org.mtr.mod.render;

import org.mtr.core.data.Position;
import org.mtr.core.data.TwoPositionsBase;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityRenderer;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.block.BlockSignalBase;
import org.mtr.mod.block.IBlock;
import org.mtr.mod.mmtr.MmtrSignalChain;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.MmtrClientRoutes;
import org.mtr.mod.data.IGui;

import javax.annotation.Nullable;
import java.util.Collections;

public abstract class RenderSignalBase<T extends BlockSignalBase.BlockEntityBase> extends BlockEntityRenderer<T> implements IBlock, IGui {

	protected final int aspects;
	private final float colorIndicatorHeight;

	public RenderSignalBase(Argument dispatcher, int colorIndicatorHeight, int aspects) {
		super(dispatcher);
		this.aspects = aspects;
		this.colorIndicatorHeight = colorIndicatorHeight / 16F + SMALL_OFFSET;
	}

	@Override
	public final void render(T entity, float tickDelta, GraphicsHolder graphicsHolder, int light, int overlay) {
		final World world = entity.getWorld2();
		if (world == null) {
			return;
		}

		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity == null) {
			return;
		}

		final BlockPos pos = entity.getPos2();
		final BlockState state = world.getBlockState(pos);
		if (!(state.getBlock().data instanceof BlockSignalBase)) {
			return;
		}

		final float angle = BlockSignalBase.getAngle(state);
		final StoredMatrixTransformations storedMatrixTransformations = new StoredMatrixTransformations(0.5 + entity.getPos2().getX(), entity.getPos2().getY(), 0.5 + entity.getPos2().getZ());
		int redstoneLevel = 0;
		final ObjectArrayList<String> railIds1 = new ObjectArrayList<>();
		final ObjectArrayList<String> railIds2 = new ObjectArrayList<>();

		for (int i = 0; i < (entity.isDoubleSided ? 2 : 1); i++) {
			final float newAngle = angle + i * 180;
			final AspectState aspectState = getAspectState(pos, newAngle + 90);

			if (aspectState != null) {
				final boolean isBackSide = i == 1;
				final IntAVLTreeSet filterColors = entity.getSignalColors(isBackSide);
				final StoredMatrixTransformations storedMatrixTransformationsNew = storedMatrixTransformations.copy();
				storedMatrixTransformationsNew.add(graphicsHolderNew -> graphicsHolderNew.rotateYDegrees(-newAngle));

				if (RenderRails.isHoldingRailRelated(clientPlayerEntity)) {
					final float xStart = -0.015625F * aspectState.detectedColors.size();
					for (int j = 0; j < aspectState.detectedColors.size(); j++) {
						final int signalColor = aspectState.detectedColors.getInt(j);
						final boolean occupied = aspectState.occupiedColors.contains(signalColor);
						final float x = xStart + j * 0.03125F;
						final float width = 0.03125F / (filterColors.isEmpty() || filterColors.contains(signalColor) ? 1 : 8);
						MainRenderer.scheduleRender(new Identifier(Init.MOD_ID, "textures/block/white.png"), false, occupied ? QueuedRenderLayer.EXTERIOR : QueuedRenderLayer.LIGHT, (graphicsHolderNew, offset) -> {
							storedMatrixTransformationsNew.transform(graphicsHolderNew, offset);
							IDrawing.drawTexture(
									graphicsHolderNew,
									x, colorIndicatorHeight, -0.15625F,
									x + 0.03125F, colorIndicatorHeight, -0.15625F,
									x + 0.03125F, colorIndicatorHeight, -0.15625F - width,
									x, colorIndicatorHeight, -0.15625F - width,
									0, 0, 1, 1,
									Direction.UP, occupied ? MainRenderer.getFlashingColor(signalColor, 1) : signalColor | ARGB_BLACK, GraphicsHolder.getDefaultLight()
							);
							graphicsHolderNew.pop();
						});
					}
				}

				// MMTR real aspects from the occupancy chain ahead (replaces MTR's release-cooldown
				// pseudo yellows AND its per-color matching): red = protected rail occupied, single
				// yellow = the next rail occupied, double yellow (4-aspect heads only) = two rails
				// ahead occupied; clear = green. The MMTR rail signal colors are unique per rail, so
				// the legacy 16-colour checkbox filter can never match them - applying it would make
				// a configured light ignore occupancy entirely. The light ALWAYS renders the MMTR
				// chain of the rail it protects; the checkbox colours only affect the debug colour
				// strip above (visible while holding a rail tool).
				final int occupiedAspect = switch (aspectState.mmtrChainDepth) {
					case 1 -> 1;
					case 2 -> 2;
					case 3 -> aspects >= 4 ? 3 : 2;
					default -> 0;
				};
				render(storedMatrixTransformationsNew, entity, tickDelta, occupiedAspect, isBackSide);

				if (occupiedAspect > 0 && occupiedAspect < aspects) {
					redstoneLevel = Math.max(redstoneLevel, (4 - occupiedAspect) * 5);
				}

				(isBackSide ? railIds2 : railIds1).addAll(aspectState.railIds);
			}
		}

		entity.checkForRedstoneUpdate(redstoneLevel, railIds1, railIds2);
	}

	protected abstract void render(StoredMatrixTransformations storedMatrixTransformations, T entity, float tickDelta, int occupiedAspect, boolean isBackSide);

	@Nullable
	public static AspectState getAspectState(BlockPos blockPos, float angle) {
		final ClientWorld clientWorld = MinecraftClient.getInstance().getWorldMapped();
		if (clientWorld == null) {
			return null;
		}

		final BlockPos startPos = getNodePos(clientWorld, blockPos, Direction.fromRotation(angle));
		if (startPos == null) {
			return null;
		}

		final MinecraftClientData minecraftClientData = MinecraftClientData.getInstance();
		final IntArrayList detectedColors = new IntArrayList();
		final IntAVLTreeSet occupiedColors = new IntAVLTreeSet();
		final boolean[] blocked = {false};
		final ObjectArrayList<String> railIds = new ObjectArrayList<>();
		final Position startPosition = Init.blockPosToPosition(startPos);

		minecraftClientData.positionsToRail.getOrDefault(startPosition, new Object2ObjectOpenHashMap<>()).forEach((endPosition, rail) -> {
			if (Math.abs(Utilities.circularDifference(Math.round(Math.toDegrees(Math.atan2(endPosition.getZ() - startPos.getZ(), endPosition.getX() - startPos.getX()))), Math.round(angle), 360)) < 90) {
				rail.getSignalColors().forEach(detectedColors::add);
				final String railId = rail.getHexId();
				minecraftClientData.railIdToCurrentlyBlockedSignalColors.getOrDefault(railId, new LongArrayList()).forEach(color -> occupiedColors.add((int) color));
				if (minecraftClientData.blockedRailIds.contains(TwoPositionsBase.getHexIdRaw(startPosition, endPosition))) {
					blocked[0] = true;
				}
				railIds.add(railId);
			}
		});

		Collections.sort(detectedColors);
		return new AspectState(detectedColors, occupiedColors, blocked[0], railIds, mmtrChainDepth(minecraftClientData, startPosition, railIds));
	}

	@Nullable
	private static BlockPos getNodePos(ClientWorld world, BlockPos pos, Direction facing) {
		int closestDistance = Integer.MAX_VALUE;
		BlockPos closestPos = null;
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				for (int y = -5; y <= 5; y++) {
					final BlockPos checkPos = pos.up(y).offset(facing.rotateYClockwise(), x).offset(facing, z);
					final BlockState checkState = world.getBlockState(checkPos);
					final int distance = checkPos.getManhattanDistance(new Vector3i(pos.data));
					if (checkState.getBlock().data instanceof BlockNode && distance < closestDistance) {
						closestDistance = distance;
						closestPos = checkPos;
					}
				}
			}
		}
		return closestPos;
	}

	public static class AspectState {

		public final IntArrayList detectedColors;
		private final IntAVLTreeSet occupiedColors;
		private final boolean nodeBlocked;
		private final ObjectArrayList<String> railIds;
		/**
		 * MMTR real-aspect depth: 1 = the protected rail itself is occupied (red); 2 = the next
		 * rail(s) along the travel direction are occupied (single yellow); 3 = the rail after that
		 * (double yellow on a 4-aspect head); 0 = clear ahead. Computed from the authoritative
		 * per-rail blocked states instead of MTR's release-cooldown pseudo yellows.
		 */
		public final int mmtrChainDepth;

		private AspectState(IntArrayList detectedColors, IntAVLTreeSet occupiedColors, boolean nodeBlocked, ObjectArrayList<String> railIds, int mmtrChainDepth) {
			this.detectedColors = detectedColors;
			this.occupiedColors = occupiedColors;
			this.nodeBlocked = nodeBlocked;
			this.railIds = railIds;
			this.mmtrChainDepth = mmtrChainDepth;
		}
	}

	/**
	 * MMTR: how far along the travel direction the nearest occupied rail sits, counted from the
	 * protected rail(s) found at {@code nodePos}: 1 = protected occupied, 2 = one rail beyond,
	 * 3 = two rails beyond, 0 = clear. Blocked = locally simulated occupancy OR the authoritative
	 * server per-rail signal-color holds. The walk itself lives in {@link MmtrSignalChain} (pure, so
	 * the client rule is unit-tested); this adapter only supplies the client rail graph. A rail that
	 * is the entry of a still-PENDING route shows danger (the movement waits outside its signal).
	 */
	private static int mmtrChainDepth(MinecraftClientData data, Position nodePos, ObjectArrayList<String> protectedHexes) {
		for (final String hex : protectedHexes) {
			if (MmtrClientRoutes.isPendingEntry(hex)) {
				return 1;
			}
		}
		return MmtrSignalChain.depth(nodePos, protectedHexes, new ClientRailGraph(data),
			hex -> data.blockedRailIds.contains(hex) || !data.railIdToCurrentlyBlockedSignalColors.getOrDefault(hex, new LongArrayList()).isEmpty(),
			MmtrClientRoutes::nextRails, 3);
	}

	/** The client rail graph ({@code MinecraftClientData.positionsToRail}) behind {@link MmtrSignalChain}. */
	private static final class ClientRailGraph implements MmtrSignalChain.RailGraph {

		private final MinecraftClientData data;

		private ClientRailGraph(MinecraftClientData data) {
			this.data = data;
		}

		@Override
		public @Nullable Position farEnd(Position node, String railHex) {
			return mmtrFarEnd(data, node, railHex);
		}

		@Override
		public java.util.List<MmtrSignalChain.RailEnd> otherRailsAt(Position node, String railHex) {
			final java.util.List<MmtrSignalChain.RailEnd> out = new java.util.ArrayList<>();
			final Object2ObjectOpenHashMap<Position, org.mtr.core.data.Rail> neighbours = data.positionsToRail.get(node);
			if (neighbours != null) {
				neighbours.forEach((otherEnd, rail) -> {
					if (!rail.getHexId().equals(railHex)) {
						out.add(new MmtrSignalChain.RailEnd(rail.getHexId(), otherEnd));
					}
				});
			}
			return out;
		}
	}

	/** The far endpoint of the rail {@code hex} that the train enters from {@code nodePos}. */
	@Nullable
	private static Position mmtrFarEnd(MinecraftClientData data, Position nodePos, String hex) {
		final Position[] found = {null};
		final Object2ObjectOpenHashMap<Position, org.mtr.core.data.Rail> neighbours = data.positionsToRail.get(nodePos);
		if (neighbours != null) {
			neighbours.forEach((endPosition, rail) -> {
				if (rail.getHexId().equals(hex)) {
					found[0] = endPosition;
				}
			});
		}
		return found[0];
	}
}
