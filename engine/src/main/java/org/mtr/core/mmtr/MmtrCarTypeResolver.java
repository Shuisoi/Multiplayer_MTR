package org.mtr.core.mmtr;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.VehicleCar;

import java.util.List;

/**
 * 本车**操纵车底**（{@link ConsistType}）的解析：按车，而不是按维度。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>在一个维度里只挂一个缺省车底（{@code Simulator.mmtrDefaultConsistTypeId}）意味着"换了车型就得改整个世界配置"，
 * 而且一改全维度的车都跟着变。BR101 的三根手柄要真正落到车上（"这列车有三根手柄"），
 * 就必须让**车自己**说明它是什么车底。两条来源，按优先级：</p>
 *
 * <ol>
 *   <li>车厢上**声明的**车底 id（资源包的 {@code mmtrConsistTypeId}，随 OBJ 车列元数据进来）；</li>
 *   <li>服务端注册表里的**车型映射**（{@code carTypeIds: {"br101": "br101_three_handle"}}）——
 *       不必重打包就能把已有车型接到新车底上。</li>
 * </ol>
 *
 * <p>都解析不出来时退回维度缺省，于是"没配过的车"行为一个字节不变（零回归）。</p>
 *
 * <h2>谁说话算数</h2>
 *
 * <p>一列车是**一个刚体**，纵向动力学只有一套参数，所以操纵车底只能有一个：
 * **第一节能解析出车底的动力车**说话（机车/动车组），没有动力车就取第一节能解析出的车厢。
 * 这样"机车 + 挂车"的编组由机车定车底，而不是由它后面第一辆碰巧配过的平车定。</p>
 *
 * <p>{@link Resolution#key()} 是"要不要重解"的判据：它带上决定车底的那节车的型号/车底/动力位，
 * 所以**连挂或解挂改变了车列**之后，车上的控制器会按新的说话人重解一次（否则一台车会一直跑挂上之前那套参数）。</p>
 */
public final class MmtrCarTypeResolver {

	private MmtrCarTypeResolver() {
	}

	/**
	 * @param consistTypeId 本车应当使用的车底 id；解析不出来时是 {@code fallbackTypeId}（可能为 null）
	 * @param key           重解判据；空串 = 谁都没解析出来（只在用维度缺省）
	 */
	public record Resolution(@Nullable String consistTypeId, String key) {
	}

	public static Resolution resolve(List<VehicleCar> cars, @Nullable ConsistTypeRegistry registry, @Nullable String fallbackTypeId) {
		if (cars != null && registry != null) {
			/*
			 * notes/247：说话的车必须**真的能出力**。
			 *
			 * <p>优先级（越前越先）：① 动力车 + 车底能出力的 → 立刻选它；② 能出力但不是动力车；
			 * ③ 动力车（车底零牵引，例如一节挂车被声明成 powered=true）；④ 随便第一节解析得出的车。</p>
			 *
			 * <p>为什么要②：现场车列里"挂车被标成 powered=true"很常见（世界数据缺省就是 true），
			 * 若挂车排在机车前面，老逻辑会让一节拖车当"说话的车" —— 整列车于是按拖车的零牵引跑，
			 * 表现为"手柄推到底车不动"，而是谁的锅还看不出来。</p>
			 */
			Resolution firstCanPull = null;
			Resolution firstPowered = null;
			Resolution firstAny = null;
			for (final VehicleCar car : cars) {
				final String typeId = typeIdOf(car, registry);
				if (typeId == null) {
					continue;
				}
				final Resolution candidate = new Resolution(typeId, keyOf(car, typeId));
				final ConsistType type = registry.get(typeId);
				final boolean canPull = type != null && type.canPull();
				// notes/271 片 2：**显式无动力的车不许当"能出力的说话人"**。否则"一列被声明成无动力的
				// 机车"会靠 ② 这一档当上说话的车，整列按它的满牵引跑 —— 正是"挂车不能开"要堵的那个口子。
				final boolean declaredUnpowered = car.isMmtrPoweredDeclared() && !car.getMmtrPowered();
				if (canPull && car.getMmtrPowered()) {
					return candidate;
				}
				if (canPull && !declaredUnpowered && firstCanPull == null) {
					firstCanPull = candidate;
				}
				if (car.getMmtrPowered() && firstPowered == null) {
					firstPowered = candidate;
				}
				if (firstAny == null) {
					firstAny = candidate;
				}
			}
			if (firstCanPull != null) {
				return firstCanPull;
			}
			if (firstPowered != null) {
				return firstPowered;
			}
			if (firstAny != null) {
				return firstAny;
			}
		}
		return new Resolution(fallbackTypeId, "");
	}

	/** 车厢声明的车底优先，其次是注册表里按车型的映射。 */
	private static @Nullable String typeIdOf(VehicleCar car, ConsistTypeRegistry registry) {
		final String declared = car.getMmtrConsistTypeId();
		if (declared != null && !declared.isEmpty() && registry.contains(declared)) {
			return declared;
		}
		final String mapped = registry.typeIdForCar(car.getVehicleId());
		return mapped != null && registry.contains(mapped) ? mapped : null;
	}

	private static String keyOf(VehicleCar car, String typeId) {
		// 三态进 key（notes/271 片 2）：把一节车从"没表态"改成"显式无动力"必须触发重解，
		// 否则车底会一直跑改之前那套参数。
		return car.getVehicleId() + '|' + typeId + '|' + car.getMmtrPowered() + (car.isMmtrPoweredDeclared() ? "|decl" : "|auto");
	}

	/**
	 * notes/271 片 2：**这一列车列里有没有一节能出力的车** —— "挂车不能开"的唯一判据。
	 *
	 * <p>与 {@link MmtrComposition#toConsistType} 的牵引求和**同源**（同一套三态规则），因为准入层
	 * （能不能掌权）与物理层用不同判据时，会出现"物理上零牵引、准入却说能开"或者反过来的组合 ——
	 * 那正是"手柄推到底车不动、还看不出是谁的锅"（notes/216/217）。规则：</p>
	 *
	 * <ul>
	 *   <li>显式无动力（世界文件 {@code powered:false} / {@code --unpowered}）⇒ 跳过；</li>
	 *   <li>有动力 ⇒ 看它自己的车底（声明 / 车型映射 / 编组缺省）能不能出力；</li>
	 *   <li>没表态且动力位为假 ⇒ 老兜底：**整列最多一节**可以借牵引，同样要车底能出力。</li>
	 * </ul>
	 */
	public static boolean anyCarCanPull(List<VehicleCar> cars, @Nullable ConsistTypeRegistry registry, @Nullable ConsistType fallbackType) {
		if (cars == null || cars.isEmpty()) {
			return false;
		}
		/*
		 * notes/276 片 6 修正：这一段必须与 {@link MmtrComposition#fromVehicleCars} +
		 * {@link MmtrComposition#toConsistType} **逐条同源**，否则"准入/钉住说能开、物理却零牵引"
		 * （或者反过来）就会出现 —— 而那正是"手柄推到底车不动、还看不出是谁的锅"。
		 *
		 * <p>之前这里用"每节车自己的车底，解析不到就用编组缺省"来判 `canPull`，与
		 * {@code fromVehicleCars} 的**借车底**规则不一致：只要整列有**一节**显式解析出车底，
		 * 借来的车一律按拖车（{@code F_max = P = 0}）—— 现场那列车（货车车型没配映射）因此会被判成
		 * "能出力"，于是钉不住它。</p>
		 */
		boolean anyExplicitType = false;
		for (final VehicleCar car : cars) {
			anyExplicitType |= typeOf(car, registry, null) != null;
		}
		// 每节车最后落在哪个车底（= fromVehicleCars 的结论）
		final ConsistType[] unitTypes = new ConsistType[cars.size()];
		boolean anyDeclaredPowered = false;
		for (int i = 0; i < cars.size(); i++) {
			final VehicleCar car = cars.get(i);
			final ConsistType explicit = typeOf(car, registry, null);
			if (explicit != null) {
				unitTypes[i] = explicit;
			} else if (fallbackType == null) {
				unitTypes[i] = null;
			} else if (!anyExplicitType && i == 0) {
				unitTypes[i] = fallbackType;                       // 整列谁都没声明 ⇒ 只有第一节借（保留牵引）
			} else {
				unitTypes[i] = fallbackType.asHauledTrailer();     // 借来的车底：不给牵引
			}
			anyDeclaredPowered |= car.getMmtrPowered() && car.isMmtrPoweredDeclared();
		}
		// toConsistType 的牵引求和：powered 的车 +（整列没有声明有动力时）**最多一节**没表态的借牵引车
		int borrowed = -1;
		if (!anyDeclaredPowered) {
			for (int i = 0; i < cars.size(); i++) {
				if (!cars.get(i).isMmtrPoweredDeclared() && unitTypes[i] != null && unitTypes[i].canPull()) {
					borrowed = i;
					break;
				}
			}
		}
		for (int i = 0; i < cars.size(); i++) {
			if ((cars.get(i).getMmtrPowered() || i == borrowed) && unitTypes[i] != null && unitTypes[i].canPull()) {
				return true;
			}
		}
		return false;
	}

	/** 这节车**自己的**车底（车厢声明 / 车型映射）；解析不到返回 null（不借缺省）。 */
	private static @Nullable ConsistType typeOf(VehicleCar car, @Nullable ConsistTypeRegistry registry, @Nullable ConsistType fallbackType) {
		if (registry == null) {
			return fallbackType;
		}
		final String typeId = typeIdOf(car, registry);
		final ConsistType type = typeId == null ? null : registry.get(typeId);
		return type == null ? fallbackType : type;
	}
}
