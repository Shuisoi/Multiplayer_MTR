package org.mtr.core.mmtr.job;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.mmtr.consist.MmtrUnitCar;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2: a car spec now carries its own traction capability and consist type, so "locomotive + hauled
 * wagons" is physically real instead of every car being powered. Both fields keep the previous
 * behaviour as their default (powered, inherit the consist type), so existing jobs are unchanged.
 */
public final class MmtrCarSpecTests {

	private static MmtrCarSpec spec(String vehicleId, boolean powered, String consistTypeId) {
		final MmtrCarSpec car = new MmtrCarSpec();
		car.vehicleId = vehicleId;
		car.length = 10;
		car.width = 2;
		car.capacity = 100;
		car.bogie1Position = 0;
		car.bogie2Position = 5;
		car.couplingPadding1 = 0.5;
		car.couplingPadding2 = 0.5;
		car.powered = powered;
		car.consistTypeId = consistTypeId;
		return car;
	}

	@Test
	public void poweredAndConsistTypeRoundTrip() {
		final MmtrCarSpec wagon = spec("flatcar", false, "freight_wagon");
		final MmtrCarSpec parsed = new MmtrCarSpec(new JsonReader(Utilities.getJsonObjectFromData(wagon)));
		assertFalse(parsed.powered, "a hauled wagon stays unpowered through the round trip");
		assertEquals("freight_wagon", parsed.consistTypeId);
		assertEquals(10, parsed.length, 1e-9);
		assertEquals(0.5, parsed.couplingPadding1, 1e-9);
	}

	@Test
	public void legacyPayloadDefaultsToPoweredWithTheConsistDefaultType() {
		final JsonObject legacy = Utilities.getJsonObjectFromData(spec("loco", true, ""));
		legacy.remove("powered");
		legacy.remove("consistTypeId");
		final MmtrCarSpec parsed = new MmtrCarSpec(new JsonReader(legacy));
		assertTrue(parsed.powered, "jobs written before C2 keep the previous behaviour: powered");
		assertEquals("", parsed.consistTypeId, "empty means 'use the consist default type'");
	}

	@Test
	public void toVehicleCarCarriesTheMetadata() {
		final VehicleCar car = spec("flatcar", false, "freight_wagon").toVehicleCar();
		assertEquals("flatcar", car.getVehicleId());
		assertFalse(car.getMmtrPowered());
		assertEquals("freight_wagon", car.getMmtrConsistTypeId());
		assertEquals(11.0, car.getTotalLength(false, false), 1e-9, "geometry is unchanged by the metadata");
	}

	@Test
	public void toUnitCarMapsAnEmptyConsistTypeToNull() {
		final MmtrUnitCar loco = spec("loco", true, "").toUnitCar();
		assertTrue(loco.powered());
		assertNull(loco.consistTypeId(), "empty per-car type means 'inherit', which the core models as null");
		assertEquals(10, loco.lengthM(), 1e-9);
		assertEquals(0.5, loco.couplingPadding1M(), 1e-9);
		assertEquals("loco", loco.vehicleId());
	}
}
