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
			String firstResolvedId = null;
			String firstResolvedKey = null;
			for (final VehicleCar car : cars) {
				final String typeId = typeIdOf(car, registry);
				if (typeId == null) {
					continue;
				}
				final String key = keyOf(car, typeId);
				if (car.getMmtrPowered()) {
					// 动力车说话：它就是这列车的操纵车底
					return new Resolution(typeId, key);
				}
				if (firstResolvedId == null) {
					firstResolvedId = typeId;
					firstResolvedKey = key;
				}
			}
			if (firstResolvedId != null) {
				return new Resolution(firstResolvedId, firstResolvedKey);
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
		return car.getVehicleId() + '|' + typeId + '|' + car.getMmtrPowered();
	}
}
