package eu.wohlben.qits.ci.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.dto.CiRunnerDto;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.CiRunnerPlane;
import eu.wohlben.qits.ci.entity.RunnerCapabilities;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.error.CiException;
import eu.wohlben.qits.ci.error.ConflictException;
import eu.wohlben.qits.ci.error.ForbiddenException;
import eu.wohlben.qits.ci.error.NotFoundException;
import eu.wohlben.qits.ci.mapper.CiRunnerMapper;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The runners: every rule about a {@link CiRunner}'s state, and every write to one.
 *
 * <p><b>What is NOT here is qits-idp.</b> A runner's lifecycle is interleaved with three calls to
 * qits-idp — a registration token commissioned at create and at every rotation, a client
 * commissioned at registration, both given back at decommission — and every one of them is HTTP, so
 * they are the service module's ({@code CiRunnerController}) and never this module's, which holds no
 * {@code java.net.http}. What this class offers the caller is the two halves around each call: a
 * check that can refuse <em>before</em> anything is commissioned, and a write that records what was
 * commissioned <em>after</em>. Nothing here holds a transaction across the network; each method is
 * its own {@code requiringNew}, so a slow idp holds no connection.
 *
 * <p><b>The name rule is {@link #NAME}</b>, a lower-case word of at most 64 characters: it becomes
 * part of a context id at qits-idp and of whatever an operator types, so it is kept to the one shape
 * that is safe in both. A taken name is a 409 — checked before a token is commissioned, and again by
 * {@code uq_ci_runner_name} for the race the check cannot see.
 *
 * <p><b>A runner holding a {@code RUNNING} run cannot be deleted</b> (409). A finished run naming it
 * never holds it up: {@code ci_run.runner_id} carries no foreign key, so a decommissioned runner
 * leaves its history exactly where it was.
 *
 * <p><b>Every write that changes what a runner is announces it, once, after it commits</b> — created,
 * registered, changed, deleted, through {@link RunnerAnnouncements}. After, because a subscriber that
 * reads the runner back must find what it was told; here rather than in the controller, because this
 * is where the commit is, and a write refused by any check (400, 403, 404, 409) throws before it
 * reaches the announcement and so announces nothing. {@link #replaceRegistrationToken}, {@link
 * #recordHello} and {@link #touchSeen} announce nothing of their own: a rotated token is a secret's
 * bookkeeping, and what a {@code Hello} changes is the connection's fact, which the socket registry
 * announces.
 */
@ApplicationScoped
public class CiRunners {

  /** A runner's name: a lower-case letter, then up to 63 lower-case letters, digits and hyphens. */
  public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

  /** The widest description a runner keeps — {@code ci_runner.description}'s width. */
  public static final int DESCRIPTION_MAX = 1024;

  /** The slots a runner is created with when the request names none — the column's default. */
  public static final int DEFAULT_SLOTS = 1;

  @Inject CiRunnerRepository runners;

  @Inject CiRunRepository runs;

  @Inject CiRunnerMapper mapper;

  @Inject CiRunnerPresence presence;

  @Inject RunnerAnnouncements announcements;

  /** 400 unless {@code name} is a runner name — see {@link #NAME}. */
  public static void requireName(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new BadRequestException(
          "A runner name is a lower-case letter followed by at most 63 lower-case letters, digits"
              + " and hyphens ([a-z][a-z0-9-]{0,63})");
    }
  }

  private static void requireSlots(Integer slots) {
    if (slots != null && slots < 0) {
      throw new BadRequestException("slots is at least 0 — a runner with 0 slots is drained");
    }
  }

  private static void requireDescription(String description) {
    if (description != null && description.length() > DESCRIPTION_MAX) {
      throw new BadRequestException(
          "A runner description is at most " + DESCRIPTION_MAX + " characters");
    }
  }

  /**
   * Everything a create can be refused for, asked <b>before</b> a registration token is
   * commissioned for it: a malformed name, slots or description (400) and a name already taken
   * (409). The same name can still be taken between this and {@link #create} — that race is the
   * constraint's, and {@link #create} answers it with the same 409.
   */
  public void requireCreatable(String name, String description, Integer slots) {
    requireName(name);
    requireSlots(slots);
    requireDescription(description);
    boolean taken =
        QuarkusTransaction.requiringNew().call(() -> runners.findByName(name).isPresent());
    if (taken) {
      throw nameTaken(name);
    }
  }

  /**
   * Records a runner whose registration token has already been commissioned. {@code id} is minted by
   * the caller, because the token's context id at qits-idp is this runner's id and it has to exist
   * before the row does. {@code plane} is the caller's to decide — which default applies depends on
   * whether a public domain is known, a fact of the service module's — and null is {@code
   * INTERNAL}, the column's own default.
   *
   * @throws ConflictException when the name was taken in between
   */
  public CiRunner create(
      UUID id,
      String name,
      String description,
      Integer slots,
      CiRunnerPlane plane,
      String registrationTokenId,
      String registrationTokenSubject) {
    requireCreatable(name, description, slots);
    CiRunner created;
    try {
      created =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    CiRunner runner = new CiRunner();
                    runner.id = Objects.requireNonNull(id, "id");
                    runner.name = name;
                    runner.description = blankToNull(description);
                    runner.slots = slots == null ? DEFAULT_SLOTS : slots;
                    runner.plane = plane == null ? CiRunnerPlane.INTERNAL : plane;
                    runner.registrationTokenId = registrationTokenId;
                    runner.registrationTokenSubject = registrationTokenSubject;
                    runner.createdAt = Instant.now();
                    runners.persist(runner);
                    runners.flush();
                    return runner;
                  });
    } catch (CiException refused) {
      throw refused;
    } catch (RuntimeException collided) {
      // The one constraint a fresh uuid can collide with is the name's; anything else is not a 409
      // and is rethrown as it came.
      boolean taken =
          QuarkusTransaction.requiringNew().call(() -> runners.findByName(name).isPresent());
      if (taken) {
        throw nameTaken(name);
      }
      throw collided;
    }
    announcements.announce(
        "runner " + created.name + " created",
        announcer ->
            announcer.onRunnerCreated(
                created.id.toString(),
                created.name,
                created.slots,
                created.plane.name(),
                created.description,
                created.createdAt));
    return created;
  }

  private static ConflictException nameTaken(String name) {
    return new ConflictException("A runner named " + name + " already exists");
  }

  /** Every runner, by name. */
  public List<CiRunner> list() {
    return QuarkusTransaction.requiringNew().call(runners::listByName);
  }

  /** One runner, or 404. */
  public CiRunner get(UUID id) {
    CiRunner runner = QuarkusTransaction.requiringNew().call(() -> runners.findById(id));
    if (runner == null) {
      throw new NotFoundException("No runner " + id);
    }
    return runner;
  }

  /**
   * Changes what an operator may change about a runner. A null leaves the value as it is; slots 0 is
   * allowed and drains the runner; a blank description clears it.
   */
  public CiRunner patch(UUID id, Integer slots, String description) {
    return patch(id, slots, description, null);
  }

  /**
   * {@link #patch(UUID, Integer, String)}, and the runner's plane with it. Whether the plane asked
   * for can be composed is the caller's check, made before this; a plane change reaches the runner's
   * next run, never the middle of one.
   */
  public CiRunner patch(UUID id, Integer slots, String description, CiRunnerPlane plane) {
    requireSlots(slots);
    requireDescription(description);
    record Patched(CiRunner runner, List<String> changed, Instant at) {}
    Patched patched =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  CiRunner runner = found(id);
                  // What moved, in RunnerChanged's order — a value set to what it already was is
                  // not a change, so a PATCH that repeats the row announces nothing.
                  List<String> changed = new ArrayList<>();
                  if (slots != null && slots != runner.slots) {
                    runner.slots = slots;
                    changed.add("slots");
                  }
                  if (plane != null && plane != runner.plane) {
                    runner.plane = plane;
                    changed.add("plane");
                  }
                  if (description != null
                      && !Objects.equals(blankToNull(description), runner.description)) {
                    runner.description = blankToNull(description);
                    changed.add("description");
                  }
                  return new Patched(runner, List.copyOf(changed), Instant.now());
                });
    CiRunner runner = patched.runner();
    if (!patched.changed().isEmpty()) {
      announcements.announce(
          "runner " + runner.name + " changed",
          announcer ->
              announcer.onRunnerChanged(
                  runner.id.toString(),
                  runner.name,
                  runner.slots,
                  runner.plane.name(),
                  runner.description,
                  patched.changed(),
                  patched.at()));
    }
    return runner;
  }

  /**
   * 409 unless a registration token may be issued for this runner right now — which is while it is
   * unregistered. A registered runner has spent its registration and holds its own client; a token
   * for it would open a door that answers 409.
   */
  public CiRunner requireUnregistered(UUID id) {
    CiRunner runner = get(id);
    if (runner.registered()) {
      throw registeredAlready(runner);
    }
    return runner;
  }

  /**
   * Swaps in a freshly commissioned registration token and answers the id of the one it replaced,
   * or null, so the caller can give that one back.
   *
   * @throws ConflictException when the runner registered in between
   */
  public String replaceRegistrationToken(UUID id, String tokenId, String tokenSubject) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              CiRunner runner = found(id);
              if (runner.registered()) {
                throw registeredAlready(runner);
              }
              String previous = runner.registrationTokenId;
              runner.registrationTokenId = tokenId;
              runner.registrationTokenSubject = tokenSubject;
              return previous;
            });
  }

  /**
   * The register door's check, asked before a client is commissioned. 404 for no such runner, 403
   * when the caller's {@code sub} is not this runner's registration token subject — which is also
   * the answer to a caller presenting no subject at all — and 409 when the runner is already
   * registered.
   *
   * <p><b>The subject stays on the row after registration, and that is what makes the 409
   * reachable.</b> The token is deleted at qits-idp once the register door has answered, but a JWT
   * minted for it at the edge outlives the deletion by up to its own lifetime; a replay inside that
   * window is the right token for a runner that has already registered, and 409 says exactly that.
   */
  public CiRunner requireRegistrable(UUID id, String callerSubject) {
    CiRunner runner = get(id);
    if (callerSubject == null
        || callerSubject.isBlank()
        || !callerSubject.equals(runner.registrationTokenSubject)) {
      throw new ForbiddenException("This registration token is not this runner's");
    }
    if (runner.registered()) {
      throw registeredAlready(runner);
    }
    return runner;
  }

  /**
   * Records the client the register door commissioned, and what the runner said about itself.
   *
   * @throws ConflictException when the runner registered in between — the caller then gives its
   *     own freshly commissioned client back
   */
  public CiRunner markRegistered(UUID id, String clientId, String capabilities) {
    Objects.requireNonNull(clientId, "clientId");
    CiRunner registered =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  CiRunner runner = found(id);
                  if (runner.registered()) {
                    throw registeredAlready(runner);
                  }
                  Instant now = Instant.now();
                  runner.clientId = clientId;
                  runner.capabilities = capabilities;
                  runner.registeredAt = now;
                  runner.lastSeenAt = now;
                  return runner;
                });
    JsonNode said = RunnerCapabilities.decode(registered.capabilities);
    announcements.announce(
        "runner " + registered.name + " registered",
        announcer ->
            announcer.onRunnerRegistered(
                registered.id.toString(),
                registered.name,
                registered.clientId,
                said != null && said.path("docker").isBoolean()
                    ? said.path("docker").booleanValue()
                    : null,
                textOf(said, "arch"),
                textOf(said, "os"),
                registered.registeredAt));
    return registered;
  }

  private static ConflictException registeredAlready(CiRunner runner) {
    return new ConflictException("Runner " + runner.name + " is already registered");
  }

  /**
   * The registered runner a commissioned client belongs to, or empty — the runner socket's whole
   * question about a dial. An unregistered row has no client and can never match, which is what
   * keeps a declared-but-unregistered runner off the socket.
   */
  public java.util.Optional<CiRunner> findByClientId(String clientId) {
    if (clientId == null || clientId.isBlank()) {
      return java.util.Optional.empty();
    }
    return QuarkusTransaction.requiringNew().call(() -> runners.findByClientId(clientId));
  }

  /**
   * What a runner said in its {@code Hello}: its capabilities replace what it registered with, and
   * it is heard from now. The runner's own word again, stored as {@link #markRegistered} stores it —
   * a host whose docker went away between registration and this connection says so here, and the
   * claim reads the newer answer. Null leaves the registered answer standing.
   *
   * @return the row as it now is, or null for a runner deleted while it dialled
   */
  public CiRunner recordHello(UUID id, String capabilities) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              CiRunner runner = runners.findById(id);
              if (runner != null) {
                if (capabilities != null) {
                  runner.capabilities = capabilities;
                }
                runner.lastSeenAt = Instant.now();
              }
              return runner;
            });
  }

  /** Stamps the runner as heard from now. A runner that no longer exists is not an error here. */
  public void touchSeen(UUID id) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRunner runner = runners.findById(id);
              if (runner != null) {
                runner.lastSeenAt = Instant.now();
              }
            });
  }

  /**
   * Deletes the row and answers what it held, so the caller can give the runner's client and token
   * back at qits-idp. 409 while a {@code RUNNING} run carries the runner.
   *
   * <p>The row goes <b>first</b> and the credentials after, deliberately: the row is what every door
   * and the socket check a runner against, so once it is gone the credentials open nothing here, and
   * a qits-idp that could not be reached leaves leftovers the commission reconciler reaps by that
   * same absence. The other order would leave a live row whose client had been deleted.
   */
  public CiRunner delete(UUID id) {
    record Deleted(CiRunner runner, Instant at) {}
    Deleted deleted =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  CiRunner runner = found(id);
                  long held = runs.countRunningOnRunner(id);
                  if (held > 0) {
                    throw new ConflictException(
                        "Runner " + runner.name + " holds " + held + " running run(s)");
                  }
                  runners.delete(runner);
                  return new Deleted(runner, Instant.now());
                });
    CiRunner gone = deleted.runner();
    announcements.announce(
        "runner " + gone.name + " deleted",
        announcer -> announcer.onRunnerDeleted(gone.id.toString(), gone.name, deleted.at()));
    return gone;
  }

  /** The runner as an operator reads it — with its presence and its held runs. */
  public CiRunnerDto view(CiRunner runner) {
    long held =
        QuarkusTransaction.requiringNew().call(() -> runs.countRunningOnRunner(runner.id));
    return mapper.toDto(runner, presence.connected(runner.id), held, versions(runner.id));
  }

  /** Every runner as an operator reads it, by name; one count query for all of them. */
  public List<CiRunnerDto> views() {
    record Read(List<CiRunner> runners, Map<UUID, Long> held) {}
    Read read =
        QuarkusTransaction.requiringNew()
            .call(() -> new Read(runners.listByName(), runs.countRunningByRunner()));
    return read.runners().stream()
        .map(
            r ->
                mapper.toDto(
                    r,
                    presence.connected(r.id),
                    read.held().getOrDefault(r.id, 0L),
                    versions(r.id)))
        .toList();
  }

  /** The presence's version facts, never null — a seam answering null knows nothing. */
  private CiRunnerPresence.Versions versions(UUID runnerId) {
    CiRunnerPresence.Versions versions = presence.versions(runnerId);
    return versions == null ? CiRunnerPresence.Versions.UNKNOWN : versions;
  }

  /**
   * The runs with their runner's name attached, for every run that carries a runner. One read for
   * the whole list, and none at all when no run carries one — which is every listing today.
   */
  public List<CiRunDto> withRunnerNames(List<CiRunDto> dtos) {
    Set<UUID> ids =
        dtos.stream().map(CiRunDto::runnerId).filter(Objects::nonNull).collect(Collectors.toSet());
    if (ids.isEmpty()) {
      return dtos;
    }
    Map<UUID, String> names =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Map<UUID, String> byId = new HashMap<>();
                  for (CiRunner runner : runners.list("id in ?1", ids)) {
                    byId.put(runner.id, runner.name);
                  }
                  return byId;
                });
    return dtos.stream()
        .map(dto -> dto.runnerId() == null ? dto : dto.withRunnerName(names.get(dto.runnerId())))
        .toList();
  }

  /** {@link #withRunnerNames(List)} for one run. */
  public CiRunDto withRunnerName(CiRunDto dto) {
    return dto == null ? null : withRunnerNames(List.of(dto)).get(0);
  }

  private CiRunner found(UUID id) {
    CiRunner runner = runners.findById(id);
    if (runner == null) {
      throw new NotFoundException("No runner " + id);
    }
    return runner;
  }

  /**
   * One string a runner said about its host, off what it registered with — null when it said
   * nothing there, or said something that is not a string. Its word, read and never corrected.
   */
  private static String textOf(JsonNode said, String field) {
    return said != null && said.path(field).isTextual() ? said.path(field).textValue() : null;
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text;
  }
}
