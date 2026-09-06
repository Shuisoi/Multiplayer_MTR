package org.mtr.core.mmtr.job;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.junit.jupiter.api.Test;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.servlet.SystemMapServlet;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web data plane: job registry persistence + Simulator store + CRUD endpoints.
 */
public final class MmtrJobRegistryTests {

	private static MmtrConsistJob sampleJob(String id) {
		final MmtrConsistJob job = new MmtrConsistJob();
		job.jobId = id;
		job.depotId = 42;
		job.sidingId = 2585812553643736373L;
		job.startTimeOfDayMs = 7L * 3_600_000;
		job.repeatDaily = true;
		final MmtrCarSpec car = new MmtrCarSpec();
		car.vehicleId = "loco";
		car.length = 10;
		car.capacity = 100;
		job.cars.add(car);
		final MmtrJobStep step = new MmtrJobStep();
		step.stepId = "s1";
		step.type = MmtrJobStep.StepType.MOVE_TO;
		step.targetId = 7;
		step.dueTimeOfDayMs = 7L * 3_600_000 + 15 * 60_000;
		job.steps.add(step);
		return job;
	}

	@Test
	public void registryPersistsAndReloadsJobs() {
		final Path dir = Paths.get("build/mmtr-registry-test");
		final Path file = dir.resolve("mmtr-jobs.json");
		deleteIfExists(file);
		final MmtrJobRegistry registry = new MmtrJobRegistry();
		registry.put(sampleJob("J1"));
		registry.save(file);
		assertTrue(java.nio.file.Files.exists(file), "job file must be written");

		final MmtrJobRegistry reloaded = MmtrJobRegistry.fromFile(file);
		assertEquals(1, reloaded.jobs.size());
		assertEquals("J1", reloaded.jobs.get(0).jobId);
		assertEquals(2585812553643736373L, reloaded.jobs.get(0).sidingId, "64-bit id must survive the file round trip");
	}

	@Test
	public void simulatorStoreAndCrudEndpoints() {
		deleteIfExists(Paths.get("build/mmtr-crud-test/test/mmtr-jobs.json"));
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-crud-test"), false);
		assertEquals(0, sim.getMmtrJobRegistry().jobs.size());

		sim.upsertMmtrJob(sampleJob("J-A"));
		assertNotNull(sim.mmtrJobScheduler, "upserting a job must attach a scheduler");
		assertEquals(1, sim.getMmtrJobRegistry().jobs.size());

		final ObjectArrayList<Simulator> simulators = new ObjectArrayList<>();
		simulators.add(sim);
		final SystemMapServlet servlet = new SystemMapServlet(new ObjectImmutableList<>(simulators));

		// GET list
		final JsonObject[] listResult = {null};
		servlet.getContent("mmtr-jobs", "", new Object2ObjectAVLTreeMap<>(), new JsonReader(new JsonObject()), sim, json -> listResult[0] = json);
		assertNotNull(listResult[0]);
		assertTrue(listResult[0].has("jobs"));
		assertEquals(1, listResult[0].getAsJsonArray("jobs").size());
		assertEquals("J-A", listResult[0].getAsJsonArray("jobs").get(0).getAsJsonObject().get("jobId").getAsString());

		// POST upsert (second job)
		final JsonObject upsertPayload = org.mtr.core.tool.Utilities.getJsonObjectFromData(sampleJob("J-B"));
		final JsonObject[] upsertResult = {null};
		servlet.getContent("mmtr-jobs-upsert", "", new Object2ObjectAVLTreeMap<>(), new JsonReader(upsertPayload), sim, json -> upsertResult[0] = json);
		assertNotNull(upsertResult[0]);
		assertTrue(upsertResult[0].get("ok").getAsBoolean());
		assertEquals(2, sim.getMmtrJobRegistry().jobs.size());

		// POST delete
		final JsonObject deletePayload = new JsonObject();
		deletePayload.addProperty("jobId", "J-A");
		final JsonObject[] deleteResult = {null};
		servlet.getContent("mmtr-jobs-delete", "", new Object2ObjectAVLTreeMap<>(), new JsonReader(deletePayload), sim, json -> deleteResult[0] = json);
		assertNotNull(deleteResult[0]);
		assertTrue(deleteResult[0].get("ok").getAsBoolean());
		assertEquals(1, sim.getMmtrJobRegistry().jobs.size());
		assertEquals("J-B", sim.getMmtrJobRegistry().jobs.get(0).jobId);

		// Status feed
		final JsonObject[] statesResult = {null};
		servlet.getContent("mmtr-job-states", "", new Object2ObjectAVLTreeMap<>(), new JsonReader(new JsonObject()), sim, json -> statesResult[0] = json);
		assertNotNull(statesResult[0]);
		assertEquals(1, statesResult[0].getAsJsonArray("states").size());
		assertEquals("J-B", statesResult[0].getAsJsonArray("states").get(0).getAsJsonObject().get("jobId").getAsString());
		assertEquals("PENDING", statesResult[0].getAsJsonArray("states").get(0).getAsJsonObject().get("state").getAsString());
		assertEquals(1, statesResult[0].getAsJsonArray("states").get(0).getAsJsonObject().get("totalSteps").getAsInt());
		// Job-editor reference pickers (world pickers can be empty on an empty test world)
		final JsonObject[] refsResult = {null};
		servlet.getContent("mmtr-job-references", "", new Object2ObjectAVLTreeMap<>(), new JsonReader(new JsonObject()), sim, json -> refsResult[0] = json);
		assertNotNull(refsResult[0]);
		assertTrue(refsResult[0].has("depots"));
		assertTrue(refsResult[0].has("sidings"));
		assertTrue(refsResult[0].has("platforms"));
		assertEquals(0, refsResult[0].getAsJsonArray("sidings").size());
	}

	@Test
	public void coupleStepsReferenceTargetJobByStableId() {
		// COUPLE targets another job by its string id (survives the daily respawn cycle), while
		// MOVE_TO/SERVE keep numeric 64-bit in-game ids - both must survive the file round trip.
		final MmtrConsistJob job = sampleJob("J-CPL");
		final MmtrJobStep couple = new MmtrJobStep();
		couple.stepId = "s2";
		couple.type = MmtrJobStep.StepType.COUPLE;
		couple.targetJobId = "FRT-D2-LOCOMOTIVE";
		couple.dueTimeOfDayMs = 8L * 3_600_000;
		job.steps.add(couple);

		final Path dir = Paths.get("build/mmtr-couple-test");
		final Path file = dir.resolve("mmtr-jobs.json");
		deleteIfExists(file);
		final MmtrJobRegistry registry = new MmtrJobRegistry();
		registry.put(job);
		registry.save(file);
		final MmtrJobRegistry reloaded = MmtrJobRegistry.fromFile(file);
		final MmtrJobStep move = reloaded.jobs.get(0).steps.get(0);
		assertEquals(MmtrJobStep.StepType.MOVE_TO, move.type);
		assertEquals(7L, move.targetId, "numeric world ids still round trip");
		final MmtrJobStep back = reloaded.jobs.get(0).steps.get(1);
		assertEquals(MmtrJobStep.StepType.COUPLE, back.type);
		assertEquals("FRT-D2-LOCOMOTIVE", back.targetJobId, "COUPLE keeps the stable job-id reference");
		assertEquals(0L, back.targetId, "COUPLE does not consume the numeric target slot");
	}

	@Test
	public void coupleStepAcceptsLegacyNonNumericTargetId() {
		// Older web editors wrote the COUPLE reference into "targetId" before the dedicated field
		// existed - a non-numeric value must migrate into targetJobId instead of silently dropping.
		final com.google.gson.JsonObject step = new com.google.gson.JsonObject();
		step.addProperty("stepId", "legacy");
		step.addProperty("type", "COUPLE");
		step.addProperty("targetId", "TRAILER-YARD-1");
		step.addProperty("dueTimeOfDayMs", 8L * 3_600_000);
		final MmtrJobStep parsed = new MmtrJobStep(new org.mtr.core.serializer.JsonReader(step));
		assertEquals("TRAILER-YARD-1", parsed.targetJobId);
		assertEquals(0L, parsed.targetId);
	}

	private static void deleteIfExists(Path path) {
		try {
			java.nio.file.Files.deleteIfExists(path);
		} catch (Exception ignored) {
		}
	}
}