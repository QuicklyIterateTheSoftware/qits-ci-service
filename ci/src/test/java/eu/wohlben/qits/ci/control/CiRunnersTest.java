package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.dto.CiRunnerDto;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.entity.RunnerCapabilities;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.error.ConflictException;
import eu.wohlben.qits.ci.error.ForbiddenException;
import eu.wohlben.qits.ci.error.NotFoundException;
import eu.wohlben.qits.ci.mapper.CiRunMapper;
import eu.wohlben.qits.ci.mapper.CiRunnerMapper;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The runners' domain rules against a real database: the name rule, the taken name, the
 * registration state machine, the refusal to delete a runner that is executing something, and the
 * shape an operator reads a runner in. Nothing here talks to qits-idp — that half is the service
 * module's {@code CiRunnerControllerTest} — so what is asserted is only what a row can decide.
 */
@QuarkusTest
public class CiRunnersTest extends CiTestSupport {

  @Inject CiRunners service;

  @Inject RecordingRunnerSignals signals;

  @Inject RecordingRunnerEvents events;

  @Inject CiRunnerRepository runnerRows;

  @Inject CiRunnerMapper runnerMapper;

  @Inject CiRunMapper runMapper;

  @BeforeEach
  void wipeRunners() {
    QuarkusTransaction.requiringNew().run(() -> runnerRows.deleteAll());
  }

  private CiRunner create(String name) {
    return service.create(
        UUID.randomUUID(), name, null, null, null, "token-" + name, "tok-ci-runner-registration-" + name);
  }

  @Test
  public void theNameRuleAdmitsALowerCaseWordAndNothingElse() {
    for (String good : List.of("a", "runner-1", "build-host-7", "a" + "b".repeat(63))) {
      CiRunners.requireName(good);
    }
    for (String bad :
        List.of(
            "",
            "1runner",
            "-runner",
            "Runner",
            "runner_1",
            "runner.1",
            "run ner",
            "a" + "b".repeat(64),
            "runner/../x")) {
      assertThrows(BadRequestException.class, () -> CiRunners.requireName(bad), bad);
    }
    assertThrows(BadRequestException.class, () -> CiRunners.requireName(null));
    // And the rule is what create applies before it writes anything.
    assertThrows(BadRequestException.class, () -> create("Not-A-Name"));
    assertEquals(0, QuarkusTransaction.requiringNew().call(() -> runnerRows.count()));
  }

  /**
   * A row that still stores the retired {@code INTERNAL} — what a runner declared before qits-515
   * carried, and what a restored backup older than V28 could still hold — reads as EDGE rather than
   * failing the enum mapping, which would cost every read of the runner table. V28 normalised every
   * row that existed at its own boot; this row is inserted after it, by raw SQL, to stand in for one
   * a later restore brings back.
   */
  @Test
  public void aRowStillStoringTheRetiredInternalPlaneReadsAsEdge() {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                runnerRows
                    .getEntityManager()
                    .createNativeQuery(
                        "insert into ci_runner (id, name, slots, plane, created_at) values"
                            + " (?1, 'left-internal', 1, 'INTERNAL', current_timestamp)")
                    .setParameter(1, id)
                    .executeUpdate());

    assertEquals(CiRunnerPlane.EDGE, service.get(id).plane);
    assertEquals(CiRunnerPlane.EDGE, service.views().get(0).plane());
    // A write of the row stores the one word there is.
    service.patch(id, 2, null);
    assertEquals(
        "EDGE",
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    runnerRows
                        .getEntityManager()
                        .createNativeQuery("select plane from ci_runner where id = ?1")
                        .setParameter(1, id)
                        .getSingleResult()));
  }

  @Test
  public void aRunnerIsCreatedWithOneSlotOnTheEdgePlaneAndUnregistered() {
    CiRunner created = create("fresh");

    CiRunner read = service.get(created.id);
    assertEquals("fresh", read.name);
    assertEquals(CiRunners.DEFAULT_SLOTS, read.slots);
    assertEquals(CiRunnerPlane.EDGE, read.plane);
    assertEquals("token-fresh", read.registrationTokenId);
    assertEquals("tok-ci-runner-registration-fresh", read.registrationTokenSubject);
    assertFalse(read.registered());
    assertNull(read.registeredAt);
    assertNotNull(read.createdAt);
  }

  @Test
  public void aTakenNameIsAConflictBeforeAndAtTheWrite() {
    create("taken");

    // The check a caller asks before commissioning anything...
    assertThrows(ConflictException.class, () -> service.requireCreatable("taken", null, 1));
    // ...and the write itself, which the constraint backs.
    assertThrows(ConflictException.class, () -> create("taken"));
    assertEquals(1, QuarkusTransaction.requiringNew().call(() -> runnerRows.count()));
  }

  @Test
  public void slotsZeroDrainsARunnerAndNegativeSlotsAreRefused() {
    CiRunner runner = create("drainable");

    CiRunner drained = service.patch(runner.id, 0, "draining for maintenance");
    assertEquals(0, drained.slots);
    assertEquals("draining for maintenance", drained.description);

    // A null leaves a value alone; a blank description clears it.
    CiRunner kept = service.patch(runner.id, null, "");
    assertEquals(0, kept.slots);
    assertNull(kept.description);

    assertThrows(BadRequestException.class, () -> service.patch(runner.id, -1, null));
    assertThrows(
        BadRequestException.class, () -> service.requireCreatable("fine", null, -1));
    assertThrows(NotFoundException.class, () -> service.patch(UUID.randomUUID(), 1, null));
  }

  @Test
  public void theStepMemoryLimitIsTheRunnersSizeGrammarAndAtLeastDockersFloor() {
    // What the runner's RunnerArgv.SIZE accepts, at or above docker's 6 MiB floor.
    for (String good :
        List.of("6g", "6G", "6144m", "6M", "6291456", "6291456b", "6144k", "64g", "999999999g")) {
      CiRunners.requireStepMemoryLimit(good);
    }
    // Null and blank are the platform default; which of "leave" or "clear" is the caller's.
    CiRunners.requireStepMemoryLimit(null);
    CiRunners.requireStepMemoryLimit("  ");
    for (String bad :
        List.of(
            "6gb", "6 g", "1.5g", "-1g", "g", "six", "6t", "0", "0g", "5m", "6291455", "100k",
            "1234567890123456", "999999999999999g")) {
      assertThrows(
          BadRequestException.class, () -> CiRunners.requireStepMemoryLimit(bad), bad);
    }
    // And the rule is what create applies before it writes anything.
    assertThrows(
        BadRequestException.class, () -> service.requireCreatable("fine", null, 1, "lots"));
    assertThrows(
        BadRequestException.class,
        () ->
            service.create(
                UUID.randomUUID(), "fine", null, 1, null, "5m", "token-fine", "tok-fine"));
    assertEquals(0, QuarkusTransaction.requiringNew().call(() -> runnerRows.count()));
  }

  @Test
  public void aStepMemoryLimitIsKeptSetClearedAndAnnouncedAndNullMeansThePlatformDefault() {
    // Created without one: null, which is qits.ci.memory-limit.
    CiRunner plain = create("default-cap");
    assertNull(service.get(plain.id).stepMemoryLimit);
    assertNull(service.view(service.get(plain.id)).stepMemoryLimit());

    // Created with one: kept verbatim (trimmed), and on the operator's read.
    CiRunner big =
        service.create(
            UUID.randomUUID(), "big-cap", null, 2, CiRunnerPlane.EDGE, " 6g ", "token-big", "tok-big");
    assertEquals("6g", service.get(big.id).stepMemoryLimit);
    assertEquals("6g", service.view(service.get(big.id)).stepMemoryLimit());
    assertEquals("6g", runnerMapper.toDto(service.get(big.id), false, 0).stepMemoryLimit());

    events.reset();
    // Null leaves it, whatever else the patch moves.
    CiRunner kept = service.patch(big.id, 3, null, null, null);
    assertEquals("6g", kept.stepMemoryLimit);
    // A new value is set and announced by name.
    CiRunner raised = service.patch(big.id, null, null, null, "8192m");
    assertEquals("8192m", service.get(big.id).stepMemoryLimit);
    assertEquals("8192m", raised.stepMemoryLimit);
    // The same value again is no change and announces nothing.
    service.patch(big.id, null, null, null, "8192m");
    // Blank clears it back to the platform default.
    CiRunner cleared = service.patch(big.id, null, null, null, "");
    assertNull(cleared.stepMemoryLimit);
    assertNull(service.get(big.id).stepMemoryLimit);
    assertEquals(
        List.of(
            "RunnerChanged [slots]",
            "RunnerChanged [stepMemoryLimit]",
            "RunnerChanged [stepMemoryLimit]"),
        events.of(big.id.toString()));

    // A malformed value is refused and changes nothing, slots included.
    assertThrows(
        BadRequestException.class, () -> service.patch(big.id, 1, null, null, "a lot"));
    assertEquals(3, service.get(big.id).slots);
    assertNull(service.get(big.id).stepMemoryLimit);
  }

  @Test
  public void theRegistrationSubjectDecidesAndARegisteredRunnerIsAConflict() {
    CiRunner runner = create("registering");
    String subject = "tok-ci-runner-registration-registering";

    // Another token's subject, and no subject at all, are the same refusal.
    assertThrows(
        ForbiddenException.class,
        () -> service.requireRegistrable(runner.id, "tok-ci-runner-registration-other"));
    assertThrows(ForbiddenException.class, () -> service.requireRegistrable(runner.id, null));
    assertThrows(
        NotFoundException.class, () -> service.requireRegistrable(UUID.randomUUID(), subject));

    service.requireRegistrable(runner.id, subject);
    CiRunner registered = service.markRegistered(runner.id, "dyn-runner-client", "{\"arch\":\"amd64\"}");
    assertTrue(registered.registered());
    assertNotNull(registered.registeredAt);
    assertNotNull(registered.lastSeenAt);

    // The right token, replayed after the door answered: the runner is registered, and says so.
    assertThrows(ConflictException.class, () -> service.requireRegistrable(runner.id, subject));
    assertThrows(
        ConflictException.class, () -> service.markRegistered(runner.id, "dyn-second", null));
    // And no registration token can be issued for it any more.
    assertThrows(ConflictException.class, () -> service.requireUnregistered(runner.id));
    assertThrows(
        ConflictException.class,
        () -> service.replaceRegistrationToken(runner.id, "token-2", "tok-2"));
    assertEquals("dyn-runner-client", service.get(runner.id).clientId);
  }

  @Test
  public void aReplacedRegistrationTokenAnswersTheOneItReplaced() {
    CiRunner runner = create("rotating");

    String previous = service.replaceRegistrationToken(runner.id, "token-2", "tok-2");

    assertEquals("token-rotating", previous);
    CiRunner read = service.get(runner.id);
    assertEquals("token-2", read.registrationTokenId);
    assertEquals("tok-2", read.registrationTokenSubject);
    // The old subject no longer opens the door.
    assertThrows(
        ForbiddenException.class,
        () -> service.requireRegistrable(runner.id, "tok-ci-runner-registration-rotating"));
  }

  @Test
  public void aRunnerHoldingARunningRunCannotBeDeletedAndItsHistoryOutlivesIt() {
    CiRunner runner = create("busy");
    String running = insertRun(runner.id, CiRunStatus.RUNNING);
    String finished = insertRun(runner.id, CiRunStatus.SUCCESS);

    ConflictException refused = assertThrows(ConflictException.class, () -> service.delete(runner.id));
    assertTrue(refused.getMessage().contains("busy"), refused.getMessage());
    assertEquals(1L, service.view(service.get(runner.id)).heldRuns());
    assertEquals(List.of(), signals.of(runner.id), "a refused delete tells the runner nothing");

    finish(running);
    CiRunner deleted = service.delete(runner.id);
    // The delete is what tells a connected runner to decommission itself — before the caller gives
    // its credentials back, which happens after this returns.
    assertEquals(List.of("deleted"), signals.of(runner.id));

    assertEquals("busy", deleted.name);
    assertThrows(NotFoundException.class, () -> service.get(runner.id));
    // No foreign key: both runs are still there, still naming the runner that is gone.
    for (String id : List.of(running, finished)) {
      CiRun run = QuarkusTransaction.requiringNew().call(() -> runs.findById(id));
      assertEquals(runner.id, run.runnerId);
    }
  }

  @Test
  public void theMapperShapesARunnerWithoutAnyCredentialHandle() throws Exception {
    CiRunner runner = create("mapped");
    service.markRegistered(runner.id, "dyn-mapped", RunnerCapabilities.encode(
        new ObjectMapper().readTree("{\"arch\":\"arm64\",\"docker\":true}")));
    insertRun(runner.id, CiRunStatus.RUNNING);

    CiRunnerDto dto = service.views().get(0);

    assertEquals(runner.id, dto.id());
    assertEquals("mapped", dto.name());
    assertEquals(1, dto.slots());
    assertEquals(CiRunnerPlane.EDGE, dto.plane());
    assertEquals("arm64", dto.capabilities().get("arch").asText());
    assertTrue(dto.capabilities().get("docker").asBoolean());
    assertTrue(dto.registered());
    // Nobody is connected until the runner socket exists to say otherwise.
    assertFalse(dto.connected());
    assertEquals(1L, dto.heldRuns());
    assertNotNull(dto.lastSeenAt());
    assertNotNull(dto.createdAt());

    // The hand-in arguments are what the mapper reports, and nothing credential-shaped is a
    // component of the DTO at all.
    CiRunnerDto connected = runnerMapper.toDto(service.get(runner.id), true, 7);
    assertTrue(connected.connected());
    assertEquals(7L, connected.heldRuns());
    String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(connected);
    for (String secretHandle : List.of("dyn-mapped", "token-mapped", "tok-ci-runner")) {
      assertFalse(json.contains(secretHandle), json);
    }
  }

  @Test
  public void anUnregisteredRunnerMapsAsUnregisteredWithNoCapabilities() {
    CiRunner runner = create("bare");

    CiRunnerDto dto = service.view(runner);

    assertFalse(dto.registered());
    assertNull(dto.capabilities());
    assertNull(dto.lastSeenAt());
    assertEquals(0L, dto.heldRuns());
  }

  @Test
  public void capabilitiesMustBeAnObjectOfBoundedSizeAndReadBackNeverThrows() throws Exception {
    ObjectMapper json = new ObjectMapper();
    assertNull(RunnerCapabilities.encode(null));
    assertThrows(
        IllegalArgumentException.class, () -> RunnerCapabilities.encode(json.readTree("[1,2]")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RunnerCapabilities.encode(
                json.readTree("{\"x\":\"" + "y".repeat(RunnerCapabilities.MAX_CHARS) + "\"}")));
    assertNull(RunnerCapabilities.decode("not json"));
    assertNull(RunnerCapabilities.decode("[1]"));
    assertNull(RunnerCapabilities.decode(null));
  }

  @Test
  public void aRunCarriesItsRunnersIdAndTheBoundaryAttachesItsName() {
    CiRunner runner = create("named");
    String held = insertRun(runner.id, CiRunStatus.RUNNING);
    String orphan = insertRun(UUID.randomUUID(), CiRunStatus.SUCCESS);
    String plain = insertRun(null, CiRunStatus.SUCCESS);

    List<CiRunDto> dtos =
        service.withRunnerNames(
            List.of(dto(held), dto(orphan), dto(plain)));

    assertEquals(runner.id, dtos.get(0).runnerId());
    assertEquals("named", dtos.get(0).runnerName());
    // A runner that is gone leaves its id on the run and no name.
    assertNotNull(dtos.get(1).runnerId());
    assertNull(dtos.get(1).runnerName());
    // A run no runner held carries neither.
    assertNull(dtos.get(2).runnerId());
    assertNull(dtos.get(2).runnerName());
  }

  private CiRunDto dto(String runId) {
    return runMapper.toDto(QuarkusTransaction.requiringNew().call(() -> runs.findById(runId)));
  }

  private String insertRun(UUID runnerId, CiRunStatus status) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = id;
              run.repoId = "runner-repo";
              run.branch = "main";
              run.commitSha = "c".repeat(40);
              run.status = status;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = ".config/qits/ci-event-runner.yml";
              run.triggerEventId = UUID.randomUUID().toString();
              run.createdAt = Instant.now();
              run.startedAt = Instant.now();
              if (status != CiRunStatus.RUNNING) {
                run.finishedAt = Instant.now();
              }
              run.runnerId = runnerId;
              runs.persist(run);
            });
    return id;
  }

  private void finish(String runId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = runs.findById(runId);
              run.status = CiRunStatus.SUCCESS;
              run.finishedAt = Instant.now();
            });
  }
}
