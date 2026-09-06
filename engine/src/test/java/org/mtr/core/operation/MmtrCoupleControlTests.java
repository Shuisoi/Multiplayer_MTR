package org.mtr.core.operation;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** MmtrCoupleControl DTO round trip. */
public final class MmtrCoupleControlTests {

	@Test
	public void roundTripPreservesFields() {
		final UUID driver = UUID.fromString("00000000-0000-0000-0000-00000000000a");
		final MmtrCoupleControl control = new MmtrCoupleControl(42, 7, 2).setDriverUuid(driver);
		final JsonObject json = Utilities.getJsonObjectFromData(control);
		final MmtrCoupleControl parsed = new MmtrCoupleControl(new JsonReader(json));
		assertEquals(42, parsed.getHeadVehicleId());
		assertEquals(7, parsed.getTailVehicleId());
		assertEquals(2, parsed.getCutAfterCarIndex());
		assertEquals(driver, parsed.getDriverUuid());
	}

	@Test
	public void defaultDriverIsNull() {
		final MmtrCoupleControl control = new MmtrCoupleControl(42, 7, -1);
		final JsonObject json = Utilities.getJsonObjectFromData(control);
		final MmtrCoupleControl parsed = new MmtrCoupleControl(new JsonReader(json));
		assertNull(parsed.getDriverUuid());
	}
}
