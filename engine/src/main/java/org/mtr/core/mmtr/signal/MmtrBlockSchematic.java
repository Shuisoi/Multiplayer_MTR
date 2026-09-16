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
	private final MmtrSectionService blocks;
	private final Object2ObjectOpenHashMap<String, Rail> railByHex = new Object2ObjectOpenHashMap<>();
	/**
	 * The 区间 layer this diagram draws: **one entry per (灯, 方向)**.
	 *
	 * <p>原来这里装的是"水闸区间"（一盏灯一个格子、节点归属唯一）。那一层已按用户裁定删除：区间是
	 * **某方向的一段路**，所以同一根轨、同一个格子可以同时属于两个方向的区间。</p>
	 */
	private final ObjectArrayList<MmtrSectionService.SectionView> sections;
	/** (cellX, cellZ) -> the lattice node id. */
	private final Object2ObjectOpenHashMap<Long, Integer> nodeIdByCell = new Object2ObjectOpenHashMap<>();
	/** Lattice node -> the world positions that snapped into it. */
	private final ObjectArrayList<ObjectArrayList<Position>> worldPositions = new ObjectArrayList<>();
	private final ObjectArrayList<Integer> cellXs = new ObjectArrayList<>();
	private final ObjectArrayList<Integer> cellZs = new ObjectArrayList<>();

	private MmtrBlockSchematic(Simulator simulator) {
		this.simulator = simulator;
		this.blocks = new MmtrSectionService(simulator);
		/*
		 * ④ 受限节点必须与 `/mmtr-sections` **同口径**（notes/166 R6）：区间图原来传 `ignored -> false`，
		 * 等于把"岔区没清 / 道岔没人定"这一档在图上关掉 —— 同一段区间于是"运营台黄、区间图绿"。
		 * 现在两处都传真实集合（{@code MmtrJunctionState.unclearedNodeKeys}）。
		 */
		this.sections = blocks.sectionViews(simulator.mmtrOccupancyTrees(),
			MmtrJunctionState.unclearedNodeKeys(simulator, simulator.mmtrOccupancyTrees())::contains);
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
		/**
		 * 覆盖这个格子的区间 id（**可能多个**：双向线路上同一段轨同属两个方向的区间）。
		 *
		 * <p>原来的 `block` 字段是"唯一归属"，那正是被删掉的水闸区间语义。区间是某方向的一段路，
		 * 所以这里必须是**集合**；空集 = 没有灯管到这一格。</p>
		 */
		public final ObjectArrayList<String> sections = new ObjectArrayList<>();
		/** Drawn centre of the square, in diagram units. */
		public final double x;
		public final double z;

		DiagramNode(int id, int cellX, int cellZ, int mergedCount, double x, double z) {
			this.id = id;
			this.cellX = cellX;
			this.cellZ = cellZ;
			this.mergedCount = mergedCount;
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
		/** Index into {@link Schematic#sections} of the section serving each direction, or -1 when nobody. */
		public final int forwardSection;
		public final int backwardSection;

		DiagramRail(int fromNode, int toNode, int rails, double lengthM, double x1, double z1, double x2, double z2, int forwardSection, int backwardSection) {
			this.fromNode = fromNode;
			this.toNode = toNode;
			this.rails = rails;
			this.lengthM = lengthM;
			this.x1 = x1;
			this.z1 = z1;
			this.x2 = x2;
			this.z2 = z2;
			this.forwardSection = forwardSection;
			this.backwardSection = backwardSection;
		}
	}

	/** One 区间 of the 灯到灯有向 layer as the diagram draws it（每个 (灯, 方向) 一条）。 */
	public static final class DiagramSection {
		public final int index;
		public final String id;
		/** 开这个区间的那盏灯（入口灯）。 */
		public final String entryLamp;
		public final String exitLamp;
		/** 本区间服务的行车方向：MTR 角（0=南 90=西 180=北 270=东）与中文名。 */
		public final double directionAngle;
		public final String directionLabel;
		public final double lengthM;
		public final boolean occupied;
		public final String aspect;
		/** The diagram edges this section covers. */
		public final ObjectOpenHashSet<Integer> railEdges = new ObjectOpenHashSet<>();
		/** The squares this section covers. */
		public final ObjectOpenHashSet<Integer> nodeIds = new ObjectOpenHashSet<>();
		/** Human-readable span list (rail short hex + arc window), for the card list. */
		public final ObjectArrayList<String> spans = new ObjectArrayList<>();

		DiagramSection(int index, String id, String entryLamp, String exitLamp, double directionAngle, String directionLabel, double lengthM, boolean occupied, String aspect) {
			this.index = index;
			this.id = id;
			this.entryLamp = entryLamp;
			this.exitLamp = exitLamp;
			this.directionAngle = directionAngle;
			this.directionLabel = directionLabel;
			this.lengthM = lengthM;
			this.occupied = occupied;
			this.aspect = aspect;
		}
	}

	/** The whole diagram: lattice nodes, lattice edges and the sections mapped onto them. */
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
		public final ObjectArrayList<DiagramSection> sections;
		/** World-metre size of the folded network, for the card header. */
		public final int worldWidthM;
		public final int worldHeightM;

		Schematic(double cellSize, int cellM, int cellWidth, int cellHeight, int originCellX, int originCellZ, ObjectArrayList<DiagramNode> nodes, ObjectArrayList<DiagramRail> rails, ObjectArrayList<DiagramSection> sections, int worldWidthM, int worldHeightM) {
			this.cellSize = cellSize;
			this.cellM = cellM;
			this.cellWidth = cellWidth;
			this.cellHeight = cellHeight;
			this.originCellX = originCellX;
			this.originCellZ = originCellZ;
			this.nodes = nodes;
			this.rails = rails;
			this.sections = sections;
			this.worldWidthM = worldWidthM;
			this.worldHeightM = worldHeightM;
		}
	}

	// ---------------------------------------------------------------- build

	private Schematic assemble() {
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
			nodes.add(new DiagramNode(id, cellX, cellZ, worldPositions.get(id).size(),
				(cellX + 0.5) * PIXELS_PER_CELL, (cellZ + 0.5) * PIXELS_PER_CELL));
			nodeIdByCell.put(cellKey(cellX, cellZ), id);
		}

		// 3. Fold the rails onto lattice edges: rails between the same pair of squares are one drawn line, and
		//    the section serving each direction says what colour that direction takes.
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

		/*
		 * 4. 把每个区间映射到图上。
		 *
		 * <p>与旧的水闸区间层最大的不同：**格子可以同时属于多个区间**（双向线路上同一段轨同属两个方向），
		 * 所以节点的归属是"追加"而不是"独占赋值"；也没有"每个格子必有唯一归属"这条不变量了。</p>
		 */
		final ObjectArrayList<DiagramSection> diagramSections = new ObjectArrayList<>();
		for (int index = 0; index < sections.size(); index++) {
			final MmtrSectionService.SectionView section = sections.get(index);
			final DiagramSection diagramSection = new DiagramSection(index, section.id, section.entrySignalKey, section.exitSignalKey,
				section.direction.angle, section.direction.label(), section.lengthM(), section.occupied, section.aspect);
			diagramSections.add(diagramSection);
			for (final MmtrSectionService.RailSpan span : section.spans) {
				final ObjectArrayList<Integer> edges = edgeIndexesByRail.get(span.railHex);
				if (edges != null) {
					diagramSection.railEdges.addAll(edges);
				}
				diagramSection.spans.add(shortHex(span.railHex) + "[" + Math.round(span.arcFromM) + "," + Math.round(span.arcToM) + ")");
				/*
				 * 本区间覆盖的**格子**：由这段弧窗的两端点算出来，而不是去查"这个格子归谁"。
				 *
				 * <p>为什么必须这样：节点归属那种单值模型已经删掉了（区间是某方向的一段路，一个格子
				 * 可以属于多个区间）。所以格子成员只能由**几何**推出来 —— 弧窗两端所在的两个格子，
				 * 以及这段路经过的格子（沿弧窗采样几个点取格子）。</p>
				 */
				final Rail rail = railByHex.get(span.railHex);
				if (rail != null) {
					final double fromM = Math.min(span.arcFromM, span.arcToM);
					final double toM = Math.max(span.arcFromM, span.arcToM);
					for (int step = 0; step <= 4; step++) {
						final double arc = fromM + (toM - fromM) * step / 4.0;
						final Position at = orderedAt(rail, arc);
						final Integer cellId = nodeIdByCell.get(cellKey(cell(at.getX()) - minCellX, cell(at.getZ()) - minCellZ));
						if (cellId != null) {
							diagramSection.nodeIds.add(cellId);
							// 反向索引：这一格被哪些区间覆盖（多值，正是双向要表达的）
							final DiagramNode node = nodes.get(cellId);
							if (!node.sections.contains(section.id)) {
								node.sections.add(section.id);
							}
						}
					}
				}
			}
		}
		// 区间也覆盖它所占边的两端格子（纯直线段采样未必落在两端格上）。
		for (final DiagramRail rail : rails) {
			if (rail.forwardSection >= 0) {
				diagramSections.get(rail.forwardSection).nodeIds.add(rail.fromNode);
				diagramSections.get(rail.forwardSection).nodeIds.add(rail.toNode);
			}
			if (rail.backwardSection >= 0) {
				diagramSections.get(rail.backwardSection).nodeIds.add(rail.fromNode);
				diagramSections.get(rail.backwardSection).nodeIds.add(rail.toNode);
			}
		}

		final int cellWidth = Math.max(MIN_CELL_WIDTH, maxCellX - minCellX + 1);
		final int cellHeight = Math.max(MIN_CELL_HEIGHT, maxCellZ - minCellZ + 1);
		return new Schematic(PIXELS_PER_CELL, CELL_M, cellWidth, cellHeight, minCellX, minCellZ,
			nodes, rails, diagramSections, (maxCellX - minCellX + 1) * CELL_M, (maxCellZ - minCellZ + 1) * CELL_M);
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
		int forwardSection = -1;
		int backwardSection = -1;
		final ObjectOpenHashSet<String> railHexes = new ObjectOpenHashSet<>();

		DiagramRailBuilder(DiagramNode from, DiagramNode to) {
			this.from = from;
			this.to = to;
		}

		void add(Rail rail, MmtrBlockSchematic schematic) {
			rails++;
			lengthM += rail.railMath.getLength();
			railHexes.add(rail.getHexId());
			forwardSection = sectionServing(rail, true, schematic);
			backwardSection = sectionServing(rail, false, schematic);
		}

		/**
		 * 这条边上**服务某个方向**的那个区间序号（没有则 -1）。
		 *
		 * <p>与旧实现同一判据（`RailSpan.matchesHeading`），但找的是**区间**而不是水闸区间：
		 * 同一根轨上两个方向各属一个区间，所以两个方向会各自命中不同的区间 —— 这正是双向线路要画的东西。</p>
		 */
		private int sectionServing(Rail rail, boolean forward, MmtrBlockSchematic schematic) {
			final double length = rail.railMath.getLength();
			final double[] positive = MmtrSectionService.railHeadingAt(rail, length <= 2 ? length / 2 : 1);
			final double[] direction = forward ? positive : new double[]{-positive[0], -positive[1]};
			for (int index = 0; index < schematic.sections.size(); index++) {
				for (final MmtrSectionService.RailSpan span : schematic.sections.get(index).spans) {
					if (span.railHex.equals(rail.getHexId()) && span.matchesHeading(direction[0], direction[1])) {
						return index;
					}
				}
			}
			return -1;
		}

		DiagramRail toRail() {
			return new DiagramRail(from.id, to.id, rails, lengthM, from.x, from.z, to.x, to.z, forwardSection, backwardSection);
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
