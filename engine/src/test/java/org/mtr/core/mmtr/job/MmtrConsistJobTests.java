package org.mtr.core.mmtr.job;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model layer of the web-driven job orchestration: consist jobs round-trip through JSON
 * (including 64-bit in-game ids as strings) and keep their ordered step list with per-step
 * deadlines.
 */
public final class MmtrConsistJobTests {

	@Test
	public void roundTripsJobWithStepsAndStringIds() {
		final long bigSidingId = 2585812553643736373L; // beyond JS safe integer
		final long platformId = 2585812553643736999L;

		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = "J-07";
		job.depotId = 42;
		job.sidingId = bigSidingId;
		job.startTimeOfDayMs = 7L * Utilities.MILLIS_PER_HOUR;
		job.repeatDaily = true;

		final MmtrCarSpec car = new MmtrCarSpec();
		car.vehicleId = "loco";
		car.length = 10;
		car.width = 2;
		car.capacity = 100;
		car.bogie1Position = 0;
		car.bogie2Position = 5;
		car.couplingPadding1 = 0.5;
		car.couplingPadding2 = 0.5;
		job.cars.add(car);

		final MmtrJobStep arrive = new MmtrJobStep();
		arrive.stepId = "s1";
		arrive.type = MmtrJobStep.StepType.MOVE_TO;
		arrive.targetId = platformId;
		arrive.dueTimeOfDayMs = 7L * Utilities.MILLIS_PER_HOUR + 15 * Utilities.MILLIS_PER_MINUTE;
		job.steps.add(arrive);

		final MmtrJobStep serve = new MmtrJobStep();
		serve.stepId = "s2";
		serve.type = MmtrJobStep.StepType.SERVE;
		serve.targetId = platformId;
		serve.dueTimeOfDayMs = arrive.dueTimeOfDayMs + 3 * Utilities.MILLIS_PER_MINUTE;
		job.steps.add(serve);

		// 换端: an in-place step with no target - it must survive the round trip like any other.
		final MmtrJobStep changeEnds = new MmtrJobStep();
		changeEnds.stepId = "s3";
		changeEnds.type = MmtrJobStep.StepType.CHANGE_ENDS;
		changeEnds.dueTimeOfDayMs = serve.dueTimeOfDayMs + 2 * Utilities.MILLIS_PER_MINUTE;
		job.steps.add(changeEnds);

		// Through the same JsonObject path the web CRUD / config file uses.
		final JsonObject json = Utilities.getJsonObjectFromData(job);
		final MmtrConsistJob parsed = new MmtrConsistJob(new JsonReader(json));

		assertEquals(job.jobId, parsed.jobId);
		assertEquals(42, parsed.depotId);
		assertEquals(bigSidingId, parsed.sidingId, "64-bit siding id must survive string round trip");
		assertEquals(job.startTimeOfDayMs, parsed.startTimeOfDayMs);
		assertTrue(parsed.repeatDaily);
		assertEquals(1, parsed.cars.size());
		assertEquals("loco", parsed.cars.get(0).vehicleId);
		assertEquals(3, parsed.steps.size());
		assertEquals(MmtrJobStep.StepType.MOVE_TO, parsed.steps.get(0).type);
		assertEquals(platformId, parsed.steps.get(0).targetId, "64-bit target id must survive");
		assertEquals(MmtrJobStep.StepType.SERVE, parsed.steps.get(1).type);
		assertTrue(parsed.steps.get(1).dueTimeOfDayMs > parsed.steps.get(0).dueTimeOfDayMs, "steps keep their order");
		assertEquals(MmtrJobStep.StepType.CHANGE_ENDS, parsed.steps.get(2).type, "换端 step survives the round trip");
		assertEquals(0, parsed.steps.get(2).targetId, "换端 has no target");
	}

	@Test
	public void defaultsForPartialPayload() {
		final JsonObject partial = new JsonObject();
		partial.addProperty("jobId", "J-empty");
		final MmtrConsistJob job = new MmtrConsistJob(new JsonReader(partial));
		assertEquals("J-empty", job.jobId);
		assertEquals(0, job.depotId);
		assertTrue(job.steps.isEmpty());
		assertTrue(job.repeatDaily, "daily repeat is the default for a consist job");
	}
}