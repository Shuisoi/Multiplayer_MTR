package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;

/**
 * 区间图的<b>格坐标</b> (schematic): the world is folded onto a regular lattice so the line can be drawn as a
 * clean topological diagram instead of at world scale.
 *
 * <p>Why a lattice at all: the management console's map draws the real track geometry, which is useless as a
 * SIGNAGE diagram - parallel stabling roads are 8 blocks apart, a ladder throat is a smear of 15 m rails, and
 * the block layer's boundaries (which fall mid-rail) vanish. Snapping every node to the nearest lattice point
 * and drawing the rails between them gives the picture a signal engineer draws by hand: everything on 1x1
 * squares, one square per track position, so "which node is in which block" is readable at a glance.</p>
 *
 * <p><strong>The ENGINE owns this transform.</strong> The console receives ready cell coordinates and draws
 * them; it never derives geometry of its own (the operator UI must not be able to disagree with the
 * simulation about where a block begins).</p>
 *
 * <p>Two nodes that snap to the same square collapse into ONE lattice node, and two rails between the same
 * pair of squares become ONE drawn line - that is the point of a schematic (a ladder 8 blocks wide becomes one
 * column), so the diagram is smaller than the world it describes.</p>
 */
public final class MmtrBlockSchematic {

	/** World metres folded into one square of the diagram. */
	public static final int CELL_M = 16;

	/**
	 * Diagram units per square. The console's map camera is orthographic with 1 unit = 1 CSS pixel at zoom 1,
	 * so a cell of this size is a comfortable 26 px square before the user zooms.
	 */
	public static final double PIXELS_PER_CELL = 26;

	/**
	 * The figure keeps a usable shape: the dev network folds to 26 columns by 102 rows (a yard hanging off a
	 * long corridor), and a diagram wider than the panel reads better than a 1:4 sliver, so a floor is put on
	 * both sides. It only pads the drawn frame - no square is invented.
	 */
	private static final int MIN_CELL_WIDTH = 16;
	private static final int MIN_CELL_HEIGHT = 16;

	private final Simulator simulator;
	private final MmtrDirectionalBlockService blocks;
	private final Object2ObjectOpenHashMap<String, Rail> railByHex = new Object2ObjectOpenHashMap<>();
	private final ObjectArrayList<MmtrDirectionalBlockService.GateBlock> gateBlocks;
	/** (cellX, cellZ) -> the lattice node id. */
	private final Object2ObjectOpenHashMap<Long, Integer> nodeIdByCell = new Object2ObjectOpenHashMap<>();
	/** Lattice node -> the world positions that snapped into it. */
	private final ObjectArrayList<ObjectArrayList<Position>> worldPositions = new ObjectArrayList<>();
	private final ObjectArrayList<Integer> cellXs = new ObjectArrayList<>();
	private final ObjectArrayList<Integer> cellZs = new ObjectArrayList<>();

	private MmtrBlockSchematic(Simulator simulator) {
		this.simulator = simulator;
		this.blocks = new MmtrDirectionalBlockService(simulator);
		this.gateBlocks = blocks.gateBlocks();
		simulator.rails.forEach(rail -> railByHex.putIfAbsent(rail.getHexId(), rail));
	}

	/** Fold the world onto the lattice and hand the console a diagram it can draw directly. */
	public static Schematic build(Simulator simulator) {
		return new MmtrBlockSchematic(simulator).assemble();
	}

	/** One square of the diagram, in CELL coordinates: (0,0) is the corner of the drawn figure. */
	public static final class DiagramNode {
		public final int id;
		public final int cellX;
		public final int cellZ;
		/** How many real track nodes folded into this square (a ladder throat folds many into one). */
		public final int mergedCount;
		/** The block this square belongs to (a lamp key, or 无灯#... for an unguarded block). */
		public final String block;
		/** Drawn centre of the square, in diagram units. */
		public final double x;
		public final double z;

		DiagramNode(int id, int cellX, int cellZ, int mergedCount, String block, double x, double z) {
			this.id = id;
			this.cellX = cellX;
			this.cellZ = cellZ;
			this.mergedCount = mergedCount;
			this.block = block;
			this.x = x;
			this.z = z;
		}
	}

	/** One distinct lattice edge: every world rail running between the same two squares is one drawn line. */
	public static final class DiagramRail {
		public final int fromNode;
		public final int toNode;
		public final int rails;
		public final double lengthM;
		public final double x1;
		public final double z1;
		public final double x2;
		public final double z2;
		/** Index into {@link Schematic#blocks} of the block owning each direction, or -1 when nobody does. */
		public final int forwardBlock;
		public final int backwardBlock;

		DiagramRail(int fromNode, int toNode, int rails, double lengthM, double x1, double z1, double x2, double z2, int forwardBlock, int backwardBlock) {
			this.fromNode = fromNode;
			this.toNode = toNode;
			this.rails = rails;
			this.lengthM = lengthM;
			this.x1 = x1;
			this.z1 = z1;
			this.x2 = x2;
			this.z2 = z2;
			this.forwardBlock = forwardBlock;
			this.backwardBlock = backwardBlock;
		}
	}

	/** One block of the 水闸区间 layer as the diagram draws it. */
	public static final class DiagramBlock {
		public final int index;
		public final String id;
		/** The lamp that opens it (empty for a block no lamp guards). */
		public final String lamp;
		public final boolean endsOpen;
		public final double lengthM;
		public final boolean occupied;
		/** The diagram edges this block owns. */
		public final ObjectOpenHashSet<Integer> railEdges = new ObjectOpenHashSet<>();
		/** The squares this block owns. */
		public final ObjectOpenHashSet<Integer> nodeIds = new ObjectOpenHashSet<>();
		/** Human-readable span list (rail short hex + arc window), for the card list. */
		public final ObjectArrayList<String> spans = new ObjectArrayList<>();

		DiagramBlock(int index, String id, String lamp, boolean endsOpen, double lengthM, boolean occupied) {
			this.index = index;
			this.id = id;
			this.lamp = lamp;
			this.endsOpen = endsOpen;
			this.lengthM = lengthM;
			this.occupied = occupied;
		}
	}

	/** The whole diagram: lattice nodes, lattice edges and the blocks mapped onto them. */
	public static final class Schematic {
		public final double cellSize;
		public final int cellM;
		public final int cellWidth;
		public final int cellHeight;
		/** The world cell the figure's (0,0) square is (diagnostics: how the world was folded). */
		public final int originCellX;
		public final int originCellZ;
		public final ObjectArrayList<DiagramNode> nodes;
		public final ObjectArrayList<DiagramRail> rails;
		public final ObjectArrayList<DiagramBlock> blocks;
		/** World-metre size of the folded network, for the card header. */
		public final int worldWidthM;
		public final int worldHeightM;

		Schematic(double cellSize, int cellM, int cellWidth, int cellHeight, int originCellX, int originCellZ, ObjectArrayList<DiagramNode> nodes, ObjectArrayList<DiagramRail> rails, ObjectArrayList<DiagramBlock> blocks, int worldWidthM, int worldHeightM) {
			this.cellSize = cellSize;
			this.cellM = cellM;
			this.cellWidth = cellWidth;
			this.cellHeight = cellHeight;
			this.originCellX = originCellX;
			this.originCellZ = originCellZ;
			this.nodes = nodes;
			this.rails = rails;
			this.blocks = blocks;
			this.worldWidthM = worldWidthM;
			this.worldHeightM = worldHeightM;
		}
	}

	// ---------------------------------------------------------------- build

	private Schematic assemble() {
		final Object2ObjectOpenHashMap<String, String> nodeOwners = blocks.nodeOwners();

		// 1. Snap every track node to its square. Nodes sharing a square become ONE lattice node.
		int minCellX = Integer.MAX_VALUE;
		int minCellZ = Integer.MAX_VALUE;
		int maxCellX = Integer.MIN_VALUE;
		int maxCellZ = Integer.MIN_VALUE;
		final ObjectArrayList<Position> trackNodes = new ObjectArrayList<>(simulator.positionsToRail.keySet());
		trackNodes.sort(null);
		for (final Position node : trackNodes) {
			final int cellX = cell(node.getX());
			final int cellZ = cell(node.getZ());
			minCellX = Math.min(minCellX, cellX);
			minCellZ = Math.min(minCellZ, cellZ);
			maxCellX = Math.max(maxCellX, cellX);
			maxCellZ = Math.max(maxCellZ, cellZ);
			worldPositions.get(nodeId(cellX, cellZ)).add(node);
		}
		if (trackNodes.isEmpty()) {
			return new Schematic(PIXELS_PER_CELL, CELL_M, 1, 1, 0, 0, new ObjectArrayList<>(), new ObjectArrayList<>(), new ObjectArrayList<>(), 0, 0);
		}

		// 2. Normalise so cell (0,0) is the corner of the FIGURE, not of the world: the console gets small
		//    positive coordinates and lays the diagram out without knowing anything about the world.
		final ObjectArrayList<DiagramNode> nodes = new ObjectArrayList<>();
		final Object2ObjectOpenHashMap<Long, Integer> nodeIdByCell = new Object2ObjectOpenHashMap<>();
		for (int id = 0; id < worldPositions.size(); id++) {
			final int cellX = cellXs.get(id) - minCellX;
			final int cellZ = cellZs.get(id) - minCellZ;
			final Position first = worldPositions.get(id).get(0);
			nodes.add(new DiagramNode(id, cellX, cellZ, worldPositions.get(id).size(),
				nodeOwners.getOrDefault(key(first), ""), (cellX + 0.5) * PIXELS_PER_CELL, (cellZ + 0.5) * PIXELS_PER_CELL));
			nodeIdByCell.put(cellKey(cellX, cellZ), id);
		}

		// 3. Fold the rails onto lattice edges: rails between the same pair of squares are one drawn line, and
		//    the block covering each direction says what colour that direction takes.
		final Object2ObjectOpenHashMap<Long, DiagramRailBuilder> edgeByPair = new Object2ObjectOpenHashMap<>();
		final Object2ObjectOpenHashMap<String, ObjectArrayList<Integer>> edgeIndexesByRail = new Object2ObjectOpenHashMap<>();
		for (final Rail rail : simulator.rails) {
			final Position[] ordered = rail.mmtrOrderedPositions();
			final Integer fromNode = nodeIdByCell.get(cellKey(cell(ordered[0].getX()) - minCellX, cell(ordered[0].getZ()) - minCellZ));
			final Integer toNode = nodeIdByCell.get(cellKey(cell(ordered[1].getX()) - minCellX, cell(ordered[1].getZ()) - minCellZ));
			if (fromNode == null || toNode == null || fromNode.intValue() == toNode.intValue()) {
				continue; // a rail inside a single square has no direction to draw
			}
			final long pair = pairKey(fromNode, toNode);
			DiagramRailBuilder builder = edgeByPair.get(pair);
			if (builder == null) {
				builder = new DiagramRailBuilder(nodes.get(fromNode), nodes.get(toNode));
				edgeByPair.put(pair, builder);
			}
			builder.add(rail, this);
		}
		final ObjectArrayList<DiagramRail> rails = new ObjectArrayList<>();
		for (final java.util.Map.Entry<Long, DiagramRailBuilder> entry : edgeByPair.entrySet()) {
			final int index = rails.size();
			rails.add(entry.getValue().toRail());
			for (final String railHex : entry.getValue().railHexes) {
				edgeIndexesByRail.computeIfAbsent(railHex, ignored -> new ObjectArrayList<>()).add(index);
			}
		}

		// 4. Map every block onto the diagram.
		final ObjectArrayList<DiagramBlock> diagramBlocks = new ObjectArrayList<>();
		final Object2ObjectOpenHashMap<String, Integer> diagramBlockById = new Object2ObjectOpenHashMap<>();
		for (int index = 0; index < gateBlocks.size(); index++) {
			final MmtrDirectionalBlockService.GateBlock block = gateBlocks.get(index);
			final DiagramBlock diagramBlock = new DiagramBlock(index, block.id, block.entryLampKey, block.endsOpen, block.lengthM(), false);
			diagramBlocks.add(diagramBlock);
			diagramBlockById.put(block.id, index);
			for (final MmtrDirectionalBlockService.RailSpan span : block.spans) {
				final ObjectArrayList<Integer> edges = edgeIndexesByRail.get(span.railHex);
				if (edges != null) {
					diagramBlock.railEdges.addAll(edges);
				}
				diagramBlock.spans.add(shortHex(span.railHex) + "[" + Math.round(span.arcFromM) + "," + Math.round(span.arcToM) + ")");
				// A block whose rails never leave their own square (a 15 m stub, a road entirely inside one
				// cell) has no lattice EDGE - without this it would be a block the diagram never shows. Anchor
				// it to the square its stretch starts in, so the operator can still see that it exists.
				final Rail rail = railByHex.get(span.railHex);
				if (rail != null && (edges == null || edges.isEmpty())) {
					final Position start = span.arcToM >= span.arcFromM ? orderedAt(rail, span.arcFromM) : orderedAt(rail, span.arcFromM);
					final Integer node = nodeIdByCell.get(cellKey(cell(start.getX()) - minCellX, cell(start.getZ()) - minCellZ));
					if (node != null) {
						diagramBlock.nodeIds.add(node);
					}
				}
			}
		}
		for (final DiagramNode node : nodes) {
			final Integer owner = diagramBlockById.get(node.block);
			if (owner != null) {
				diagramBlocks.get(owner).nodeIds.add(node.id);
			}
		}
		// A block also covers the SQUARES at the ends of the edges it owns. Without this, a block drawn purely
		// as a line through a square would own no square at all (the square's node belongs to another block
		// there), and the map's node ring for it would be missing.
		for (final DiagramRail rail : rails) {
			if (rail.forwardBlock >= 0) {
				diagramBlocks.get(rail.forwardBlock).nodeIds.add(rail.fromNode);
				diagramBlocks.get(rail.forwardBlock).nodeIds.add(rail.toNode);
			}
			if (rail.backwardBlock >= 0) {
				diagramBlocks.get(rail.backwardBlock).nodeIds.add(rail.fromNode);
				diagramBlocks.get(rail.backwardBlock).nodeIds.add(rail.toNode);
			}
		}

		final int cellWidth = Math.max(MIN_CELL_WIDTH, maxCellX - minCellX + 1);
		final int cellHeight = Math.max(MIN_CELL_HEIGHT, maxCellZ - minCellZ + 1);
		return new Schematic(PIXELS_PER_CELL, CELL_M, cellWidth, cellHeight, minCellX, minCellZ,
			nodes, rails, diagramBlocks, (maxCellX - minCellX + 1) * CELL_M, (maxCellZ - minCellZ + 1) * CELL_M);
	}

	/** The world position at {@code arcM} of a rail, snapped to the block coordinate it stands in. */
	private static Position orderedAt(Rail rail, double arcM) {
		return new Position(Math.round(rail.railMath.getPosition(Math.max(0, arcM), false).x()), 0, Math.round(rail.railMath.getPosition(Math.max(0, arcM), false).z()));
	}

	/** Accumulates every world rail that folds onto one lattice edge. */
	private static final class DiagramRailBuilder {
		final DiagramNode from;
		final DiagramNode to;
		int rails;
		double lengthM;
		int forwardBlock = -1;
		int backwardBlock = -1;
		final ObjectOpenHashSet<String> railHexes = new ObjectOpenHashSet<>();

		DiagramRailBuilder(DiagramNode from, DiagramNode to) {
			this.from = from;
			this.to = to;
		}

		void add(Rail rail, MmtrBlockSchematic schematic) {
			rails++;
			lengthM += rail.railMath.getLength();
			railHexes.add(rail.getHexId());
			forwardBlock = ownerOf(rail, true, schematic);
			backwardBlock = ownerOf(rail, false, schematic);
		}

		/** Which block owns this rail read in the ordered-position direction (forward) or against it. */
		private int ownerOf(Rail rail, boolean forward, MmtrBlockSchematic schematic) {
			final double length = rail.railMath.getLength();
			final double[] positive = MmtrDirectionalBlockService.railHeadingAt(rail, length <= 2 ? length / 2 : 1);
			final double[] direction = forward ? positive : new double[]{-positive[0], -positive[1]};
			for (int index = 0; index < schematic.gateBlocks.size(); index++) {
				for (final MmtrDirectionalBlockService.RailSpan span : schematic.gateBlocks.get(index).spans) {
					if (span.railHex.equals(rail.getHexId()) && span.matchesHeading(direction[0], direction[1])) {
						return index;
					}
				}
			}
			return -1;
		}

		DiagramRail toRail() {
			return new DiagramRail(from.id, to.id, rails, lengthM, from.x, from.z, to.x, to.z, forwardBlock, backwardBlock);
		}
	}

	/** The rails of this schematic (kept for diagnostics and for the tests). */
	public Object2ObjectOpenHashMap<String, Rail> railsByHex() {
		return railByHex;
	}

	private int nodeId(int cellX, int cellZ) {
		final long key = cellKey(cellX, cellZ);
		final Integer existing = nodeIdByCell.get(key);
		if (existing != null) {
			return existing;
		}
		final int id = worldPositions.size();
		nodeIdByCell.put(key, id);
		worldPositions.add(new ObjectArrayList<>());
		cellXs.add(cellX);
		cellZs.add(cellZ);
		return id;
	}

	private static int cell(long coordinate) {
		return (int) Math.floor((double) coordinate / CELL_M);
	}

	private static long cellKey(int cellX, int cellZ) {
		return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
	}

	private static long pairKey(int nodeA, int nodeB) {
		return nodeA < nodeB ? ((long) nodeA << 32) | (nodeB & 0xFFFFFFFFL) : ((long) nodeB << 32) | (nodeA & 0xFFFFFFFFL);
	}

	private static String key(Position position) {
		return position.getX() + "," + position.getY() + "," + position.getZ();
	}

	private static String shortHex(String railHex) {
		return railHex.length() > 8 ? railHex.substring(0, 8) : railHex;
	}
}
