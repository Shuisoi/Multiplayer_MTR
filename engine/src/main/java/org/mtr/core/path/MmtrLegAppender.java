package org.mtr.core.path;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;

import java.util.List;
import java.util.Map;

/**
 * **① 的 {@code LEGS} 增量接到客户端镜像的腿阴影上**（notes/369 §4.4 / S3b）。
 *
 * <h2>为什么需要这一层</h2>
 * <p>客户端摆车走的是 {@code vehicleExtraData.immutablePath} —— 一份**腿阴影**：车尾 → 车头、
 * 车头正好落在快照那一刻的 {@code railProgress} 上。它在旧实现里只在 ② 的整份快照里重建，
 * 而 ② 只在"走行器新踏上一根轨"时带来新阴影 —— 60 km/h 下每 25–100 m 一次，也就是 1.5–6 秒。
 * 于是"车开起来"被切成一段一段：车头走到阴影末端就停住（摆车只能把它夹回去），等下一拍再跳。
 * 这就是用户实机报的**「车移动依旧是卡的」**。</p>
 *
 * <p>修法不是在客户端硬猜位置，而是**把服务端已经有的那条腿列表增量地重放出来**：服务端的
 * {@code LEGS} 记录说"从车尾端丢 n 根、车头端新加这几根"（{@code MmtrMotionFrame#legDelta}），
 * 这里就把它落到路径上，并**按上一根的 {@code endDistance} 续算累计里程** —— 于是新腿与旧腿在
 * 同一个坐标空间里接上，{@code railProgress}（客户端本地积分出来的那个值）自然走进新腿。</p>
 *
 * <h2>三条"不猜"的规矩（错一条的表现都是"某一节车摆到别的轨上"，很贵）</h2>
 * <ol>
 *   <li><b>没有基准就不接</b>：路径为空（镜像刚建起来、② 的整份还在路上）⇒ 返回 {@link Reason#NO_BASE}，
 *       等 ② 把完整路径与锚点带来（≤1 秒）。凭空的锚点会让整列车错位。</li>
 *   <li><b>整表替换就等 ②</b>：{@code droppedFromTrainTail >= size} 意味着服务端那边重新排了整表
 *       （换端/换向：同一批轨、相反顺序）。这时**保持现状**继续画（换端只在停车时合法，画面是静止的），
 *       由 ② 的整份快照做硬对齐。</li>
 *   <li><b>接不上就停在那</b>：hex 查不到轨、或者新轨的两个端点都不等于当前游标（列表不同源）⇒
 *       停止追加（已追加的保留），把原因报给调用方记账。**绝不按"声明顺序"猜方向** —— 轨的 hex id
 *       是声明顺序，与行驶方向无关。</li>
 * </ol>
 *
 * <p>另外还有一条**只与丢有关**的保护：车尾端的腿只有整根都在车尾之后
 * （{@code endDistance <= tailDistanceM}）才允许丢。否则客户端本地积分稍落后于服务端时，
 * 会把车底下那一根丢掉 —— 那时 {@code Utilities#getIndexFromConditionalList} 找不到落点，
 * 整车会按"最后一条腿"画（瞬移）。丢不动就多留一根，代价只是列表长一点。</p>
 */
public final class MmtrLegAppender {

	private MmtrLegAppender() {
	}

	/**
	 * 把一次 {@code LEGS} 增量落到 {@code path} 上（**原地**修改：先丢车尾端，再在末尾追加）。
	 *
	 * @param path                客户端镜像的路径（车尾 → 车头；会被就地修改）
	 * @param droppedFromTrainTail 要从**开头**（车尾端）丢掉的条数（服务端 {@code legDelta} 给的）
	 * @param newLegHexIds        车头端新追加的轨 hex id，**按行驶顺序**
	 * @param railIdMap           客户端本地轨表（{@code Data#railIdMap}）
	 * @param tailDistanceM       车尾在**本路径坐标空间**里的位置（= {@code railProgress - 车长}），
	 *                            只用来判断"这根腿还能不能丢"
	 */
	public static Applied apply(ObjectArrayList<PathData> path, int droppedFromTrainTail, List<String> newLegHexIds, Map<String, Rail> railIdMap, double tailDistanceM) {
		if (path.isEmpty()) {
			// 没有基准：等 ② 的整份（它带完整路径与锚点）。这条路上"猜"的代价是整列车错位。
			return new Applied(0, 0, Reason.NO_BASE);
		}
		if (droppedFromTrainTail >= path.size()) {
			// 整表替换（换端/换向）：保持现状继续画，由 ② 的整份快照硬对齐。
			return new Applied(0, 0, Reason.FULL_REPLACE);
		}

		int dropped = 0;
		while (dropped < droppedFromTrainTail) {
			final PathData front = path.get(0);
			if (front.getEndDistance() > tailDistanceM) {
				// 这一根还在车身底下：留着（多留一根不会错位，丢掉会）。
				break;
			}
			path.remove(0);
			dropped++;
		}

		final PathData last = path.get(path.size() - 1);
		Position cursor = endPosition(last);
		final ObjectArrayList<PathData> appended = new ObjectArrayList<>();
		Reason reason = Reason.OK;

		for (final String hexId : newLegHexIds) {
			final Rail rail = railIdMap.get(hexId);
			if (rail == null) {
				reason = Reason.UNKNOWN_RAIL;
				break;
			}
			// 轨的两端走 mmtrOrderedPositions()：Rail#getPosition1/2 是 protected（本包在 data 之外），
			// 而这一支要的恰好就是"规范化顺序"的两端。
			final Position[] ends = rail.mmtrOrderedPositions();
			final Position position1 = ends[0];
			final Position position2 = ends[1];
			final Position exit;
			if (cursor.equals(position1)) {
				exit = position2;
			} else if (cursor.equals(position2)) {
				exit = position1;
			} else {
				// 新轨与现有腿不连着：列表不同源。不猜方向，停在这里等 ② 修。
				reason = Reason.DISCONTINUOUS;
				break;
			}
			// 整根轨、从游标那一端进：与引擎 {@code MmtrConsistWalker#addLeg} 的构造约定一致。
			appended.add(new PathData(rail, 0L, 0L, 0, cursor, exit));
			cursor = exit;
		}

		if (!appended.isEmpty()) {
			// 累计里程从"旧表最后一条的末端"续算 —— 这一步就是"重新锚定"，缺了它新腿会从 0 开始，整车瞬移。
			SidingPathFinder.generatePathDataDistances(appended, last.getEndDistance());
			path.addAll(appended);
		}
		return new Applied(dropped, appended.size(), reason);
	}

	/**
	 * 一根腿**沿行驶方向**的末端（= 下一根腿的接入端）。
	 *
	 * <p>{@code PathData#getOrderedPosition1/2} 是"按坐标大小排序"的两端，方向信息在
	 * {@code reversePositions} 里：它为真时 {@code ordered1} 其实是**终点**。这里把这个换算收在一处，
	 * 免得每个调用点各推算一次。</p>
	 */
	public static Position endPosition(PathData pathData) {
		return pathData.reversePositions ? pathData.getOrderedPosition1() : pathData.getOrderedPosition2();
	}

	/** 一次增量落地的结果：丢了几根、接了几根、终止原因。 */
	public record Applied(int dropped, int appended, Reason reason) {

		public boolean isEmpty() {
			return dropped == 0 && appended == 0;
		}
	}

	/** 增量落地为什么停在这里（{@link #OK} = 请求的腿全部接上了）。 */
	public enum Reason {
		/** 全部接上。 */
		OK,
		/** 没有基准路径（等 ② 的整份）。 */
		NO_BASE,
		/** 整表替换（换端/换向；等 ② 的整份）。 */
		FULL_REPLACE,
		/** hex 在本地轨表里查不到。 */
		UNKNOWN_RAIL,
		/** 新轨与现有腿不连着。 */
		DISCONTINUOUS
	}
}
