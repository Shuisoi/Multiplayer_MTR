package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.VehicleCar;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **停放 = 钉住**（notes/276 片 6，决定 1/6：停放制动"简化为不动就行了"，多人游戏里
 * "不被连上就钉死在地里"）。
 *
 * <p>钉住是**推导出来的**，不是搬来搬去的标志：*没有司机* + *没有在跑的任务* +
 * *整列车列没有任何能出力的车底* 三条同时成立。于是连挂/解挂、人上车/下车、任务起止
 * 任何一条变了都自动跟上，不存在"标志忘了搬"。</p>
 *
 * <p>本类钉三件事：① 三条的真值表（缺一条就不钉）；② 与准入层**同源**的"能不能出力"
 * （片 2 的三态判据）；③ 单节被声明成无动力的车（车底本身能出力）这个口子 ——
 * 单节不走等效车底那条路，准入层与钉住层各自堵一半。</p>
 */
public final class MmtrHauledRakeLifecycleTests {

	private static final String RAKE_JSON = "{"
		+ "\"carTypeIds\":{\"loco\":\"loco\",\"wagon\":\"wagon\"},"
		+ "\"consistTypes\":["
		+ "{\"id\":\"loco\",\"name\":\"loco\",\"controlMode\":\"NOTCHED\",\"massKg\":84000,"
		+ "\"maxTractiveEffortN\":300000,\"maxPowerW\":6400000},"
		+ "{\"id\":\"wagon\",\"name\":\"wagon\",\"controlMode\":\"NOTCHED\",\"massKg\":24000,"
		+ "\"maxTractiveEffortN\":0,\"maxPowerW\":0}]}";

	/** 一节车：{@code declaredUnpowered = true} 就是世界文件里真的写了 {@code powered:false}。 */
	private static VehicleCar car(String vehicleId, boolean powered, boolean declared, String consistTypeId) {
		final VehicleCar car = new VehicleCar(vehicleId, 16, 5, 0, -16 / 3.0, 16 / 3.0, 0, 0, powered, consistTypeId);
		car.setMmtrPoweredDeclared(declared);
		return car;
	}

	@Test
	public void thePinRuleNeedsAllThreeConditionsToBeAbsent() {
		assertTrue(MmtrDriveAccess.shouldPin(false, false, false), "无人 + 无任务 + 整列拉不动 ⇒ 钉住");
		assertFalse(MmtrDriveAccess.shouldPin(true, false, false), "有人掌权 ⇒ 不钉（人自己负责）");
		assertFalse(MmtrDriveAccess.shouldPin(false, true, false), "有任务 ⇒ 不钉（任务就是让它动的理由）");
		assertFalse(MmtrDriveAccess.shouldPin(false, false, true), "整列能出力 ⇒ 不钉（真车也不会把机车钉住）");
		assertFalse(MmtrDriveAccess.shouldPin(true, true, true));
	}

	/** 停放待挂的车列被钉住；连上机车（整列能出力）就解钉 —— 这正是"被连上就不钉了"。 */
	@Test
	public void aHauledRakeIsPinnedUntilALocomotiveIsCoupledToIt() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(RAKE_JSON);
		final ConsistType fallback = registry.get("loco");
		final List<VehicleCar> rakeOnly = List.of(
			car("wagon", false, true, "wagon"), car("wagon", false, true, "wagon"));
		final List<VehicleCar> withLoco = List.of(
			car("wagon", false, true, "wagon"), car("loco", true, true, "loco"));

		final boolean rakeCanPull = MmtrCarTypeResolver.anyCarCanPull(rakeOnly, registry, fallback);
		final boolean mergedCanPull = MmtrCarTypeResolver.anyCarCanPull(withLoco, registry, fallback);
		assertFalse(rakeCanPull, "两节挂车：整列拉不动");
		assertTrue(mergedCanPull, "连上机车：整列能出力");
		assertTrue(MmtrDriveAccess.shouldPin(false, false, rakeCanPull), "停放待挂 ⇒ 钉住");
		assertFalse(MmtrDriveAccess.shouldPin(false, false, mergedCanPull), "连上之后 ⇒ 解钉（不需要搬任何标志）");
	}

	/**
	 * **单节被声明成无动力的车**（车底本身能出力，例如一台机车被写成 {@code powered:false}）：
	 * 单节不走"按车求和"的等效车底，所以物理层拦不住它 —— 准入层（片 2 拒绝掌权）与钉住层
	 * （这一条）各自堵一半，合起来才是"挂车开不走、也钉得住"。
	 */
	@Test
	public void aSingleDeclaredUnpoweredCarIsPinnedEvenThoughItsTypeCouldPull() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(RAKE_JSON);
		assertTrue(registry.get("loco").canPull(), "车底本身是能出力的");
		final List<VehicleCar> one = List.of(car("loco", false, true, "loco"));
		final boolean canPull = MmtrCarTypeResolver.anyCarCanPull(one, registry, registry.get("loco"));
		assertFalse(canPull, "显式无动力 ⇒ 不算能出力（片 2 的三态判据）");
		assertTrue(MmtrDriveAccess.shouldPin(false, false, canPull), "⇒ 钉住");
		assertFalse(MmtrDriveAccess.shouldPin(false, false, MmtrCarTypeResolver.anyCarCanPull(
			List.of(car("loco", true, true, "loco")), registry, registry.get("loco"))), "声明有动力 ⇒ 不钉");
	}
}
