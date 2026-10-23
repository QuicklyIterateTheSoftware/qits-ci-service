package eu.wohlben.qits.ci.targethost;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonBinary;
import eu.wohlben.qits.platformaccess.cli.PlatformAccessCliBinary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * <b>What a CI lease's container is made of</b> (epic qits-1481, "CI in a slot"): the host-authored
 * {@link #BOOTSTRAP} its daemon starts through, the paths that bootstrap writes, and every
 * deployment fact a lease's spec is composed from that is not an address — the slot's limits
 * ({@code qits.ci.slot.*}), the platform's image namespace, the qits CLI a release step fetches, and
 * the daemon build a run pins ({@link #daemonVersion}, {@link #resolveBinaryUrl}). {@link
 * CiSlotSpecs} is the composition; {@link CiSlotAddresses} holds the addresses.
 *
 * <p>This was {@code runnerhost/StepContainerSettings}, the settings each per-step container of a
 * CI runner was composed from. The per-step container went with the runner: a run is one lease,
 * one container runs every step, and the container is the target's ({@code qits-system-runner}),
 * started from the spec qits-system relays. What did not move is the rule this class has always
 * stated: <b>qits-ci still executes nothing.</b> A step's script reaches the container only as a
 * {@code RunStep} frame on the socket that container's own daemon dialled, and {@link #BOOTSTRAP}
 * interpolates nothing at all.
 *
 * <p><b>The bootstrap is the container's command, and it travels as an environment variable.</b>
 * {@code LeaseSpec{image, env, user, limits}} carries no entrypoint, so {@link CiSlotSpecs} puts
 * the text below into the spec's env as {@link #BOOTSTRAP_ENV}, and the {@code
 * qits/build-images/ci-slot} image's entrypoint runs {@code /bin/sh -c "$QITS_CI_BOOTSTRAP"}. It
 * runs as user {@code 1001} with {@code HOME=/work/home} (set by the target, never by the spec),
 * and writes only under {@code /tmp}, {@code /work} and {@code $HOME}; the daemon it becomes puts
 * the run's worktree at {@code /work/<runId>}.
 */
@ApplicationScoped
public class CiSlotSettings {

  /**
   * The container's entrypoint: fetch the daemon, make it executable, become it. A {@code static
   * final String}, host-authored, with nothing interpolated into it ever — the four values it needs
   * arrive as environment variables the shell expands inside the container, so a repository cannot
   * reach this text no matter what it declares.
   *
   * <p>Written for {@code /bin/sh} rather than bash, because the image contract is the repository's
   * choice and {@code sh} is the only shell an arbitrary image reliably has. It probes {@code wget}
   * then {@code curl} — one or the other is the downloader half of the contract — and says so
   * explicitly when neither is present, because <b>this text's stdout is the whole diagnosis of a
   * container that never registers</b>. That is why each failure arm names the url it could not
   * fetch instead of letting a bare non-zero exit stand: by the time the host notices, the only
   * thing anyone can read is the lease container's own log on its target.
   *
   * <p><b>The fetch RETRIES, and the reason is measured rather than defensive.</b> qits-artifacts
   * serves this binary and deploys {@code update_order: stop-first}, so it refuses connections for a
   * window on <em>every</em> deploy — the one service the bootstrap depends on before it can do
   * anything at all is also one that is deliberately absent now and then. On 2026-09-15 at 01:05 UTC
   * a step container landed 7 seconds into such a window, got {@code Connection refused} from
   * busybox wget, exited, and the release-request run went red with {@code NEVER_STARTED} a minute later:
   * a one-attempt fetch turned a routine redeploy into a rejected release request on somebody's
   * commit. The loop is explicit shell rather than a downloader flag because the platform's step
   * images are Alpine, so the {@code wget} that is probed first is <b>busybox wget</b>, which has
   * neither a retry nor a {@code --tries}; the {@code curl} arm is never reached there.
   *
   * <p><b>The budget: 10 attempts, 12 seconds apart — about 108 seconds of retrying for a refused
   * connection</b>, which must stay inside {@code qits.ci.daemon-register-timeout-seconds}, the
   * deadline the host gives the same container to become a daemon. The two numbers move together:
   * a deadline below this budget makes the retry pointless, because qits-ci gives up while the
   * container is still trying. Both literals are typed into this text — it is a {@code static final
   * String} with <b>zero interpolation</b>, and a Java constant folded in here would be the end of
   * that property.
   *
   * <p><b>The accepted limit.</b> The budget is only bounded that tightly for connections that are
   * <em>refused</em>, which is the measured failure. Attempts that instead hang to their own
   * per-attempt timeouts ({@code -T 20}, {@code --connect-timeout 10 --max-time 120} — new, and the
   * reason a hung attempt cannot make the retry meaningless) can make the loop outlast the host's
   * deadline. In that case qits-ci gives up first and reports {@code NEVER_STARTED} exactly as it
   * does today, and the container is reaped either way: the loop's worst case is bounded by the
   * host, not by itself.
   *
   * <p><b>The fetch presents the run's token.</b> The binary is downloaded from qits-artifacts'
   * public name, which refuses an anonymous read, so both downloader arms send {@code
   * Authorization: Bearer $QITS_TOKEN} — as a {@code "$@"} that is empty when the variable is, which
   * is a step this service never launches ({@code CiLeases} asks for no lease without a token).
   *
   * <p><b>It is also where the registry credential becomes a file.</b> It writes {@code
   * $DOCKER_CONFIG/config.json} from {@code $QITS_CI_REGISTRY_AUTH_CONFIG} when both are set, which
   * is how a step gets a small file that is <em>not</em> in the clone and therefore not in any build
   * context — see {@link #REGISTRY_AUTH_DIR}. It stays zero-interpolation like the rest of this
   * text: the credential is a value the shell reads from its own environment, never a word in this
   * string.
   *
   * <p><b>{@code $QITS_TOKEN} is the step's only platform credential, and this text turns it into
   * the four things a step uses it through.</b> It is this run's {@code ci-run} token, commissioned
   * at the run's first step and deleted when the run closes. There is no token exchange in this
   * text and no client pair: the branch that wrote a minting script from {@code
   * $QITS_COMMISSIONED_CLIENT_ID}/{@code _SECRET} and {@code $QITS_GIT_AUTH_*} was deleted with the
   * internal plane (qits-515). What is written:
   *
   * <ul>
   *   <li>{@link #PUBLISH_TOKEN_COMMAND}, which prints the token, raw and on one line, so a recipe
   *       that runs {@code $QITS_PUBLISH_TOKEN_COMMAND} gets it; {@code $QITS_PUBLISH_TOKEN} is
   *       exported with the same value.
   *   <li>A git credential helper answering {@code oauth2}/token for the host {@code
   *       $QITS_CI_REPOSITORY_URL} names and nobody else — git may invoke a helper for any remote
   *       in a repository, a repository-authored submodule included, and handing the credential to
   *       an arbitrary host would be an exfiltration vulnerability. The edge reads the password of
   *       git's Basic as a {@code qits_tok_} and introspects it. The same global config raises
   *       {@code http.postBuffer} to 64 MiB, qits-githost's {@code max-pack-size}: a request body
   *       past git's 1 MB default is streamed through libcurl's read callback, and the step images'
   *       libcurl 7.88.1 over HTTP/2 intermittently asks that callback for more after it returned
   *       EOF — git-remote-https then blocks on a pipe send-pack has finished writing, the body
   *       never ends, and the push hangs until the step times out (qits-887). A buffered body
   *       goes with a {@code Content-Length} and never takes that path.
   *   <li>{@link #DEPLOY_SETTINGS_FILE}, with the bearer as an {@code Authorization} header on every
   *       server id the estate's settings use ({@code qits}, {@code qits-maven-network}, {@code
   *       qits-central-proxy}), plus a re-declared {@code maven-default-http-blocker} mirror, and
   *       {@code -gs} for it <em>appended</em> to {@code MAVEN_ARGS}. A header rather than a {@code
   *       <password>} because Maven does not authenticate preemptively; the blocker is re-declared
   *       because {@code -gs} <em>replaces</em> Maven's {@code conf/settings.xml}, whose only live
   *       element it is. Measured on Maven 3.9.12: the merge with a {@code -s} user settings keeps
   *       both files' servers, and {@code MAVEN_ARGS} beats an explicit {@code -gs} on the command
   *       line.
   *   <li>An {@code _authToken} for {@code registry.qits.$QITS_DOMAIN} and {@code
   *       mirror.qits.$QITS_DOMAIN}, the two npm hosts, appended to {@code ~/.npmrc}.
   * </ul>
   *
   * <p><b>{@link #DEPLOY_SETTINGS_FILE}'s {@code -gs} is not the only settings file in play, and a
   * live run (ef26d331, qits-stt-service, {@code quarkus-bom} 401) measured that {@code -gs} losing
   * to a repository's own {@code -s .qits-maven-settings.xml}.</b> Every repository declares that
   * file's own {@code <server id=qits-maven-network>}/{@code <server id=qits-central-proxy>} reading
   * {@code ${env.QITS_MAVEN_AUTH_USR}}/{@code ${env.QITS_MAVEN_AUTH_PSW}} — a plain credential
   * profile, not the header form {@code -gs} writes — and on Maven 3.9.12 a {@code -s}/{@code -gs}
   * merge for one server id keeps the {@code -s} file's entry. {@code -gs} alone therefore never
   * reaches those two ids on a step that also passes its own {@code -s}, which every archetype does.
   * {@link CiSlotSpecs#compose} sets both variables to this run's token (password) and its
   * subject (username), so the repo's own credential profile resolves rather than reading two empty
   * environment expressions.
   *
   * <p>{@code exec} rather than a plain call, so the daemon is PID 1 and the removal signals the
   * process that owns the step rather than a shell wrapping it.
   *
   * <p><b>It is never part of a command line built here.</b> No string on this side of the wire is
   * built by concatenating it with anything; it is the ci-slot container's own command, run as
   * {@code /bin/sh -c BOOTSTRAP} (see the class javadoc).
   */
  /** The lease-spec variable that carries {@link #BOOTSTRAP} to the ci-slot image's entrypoint. */
  public static final String BOOTSTRAP_ENV = "QITS_CI_BOOTSTRAP";

  /** Where the daemon puts the run's worktree ({@code <WORK_ROOT>/<runId>}), made ready here. */
  static final String WORK_ROOT = "/work";

  static final String BOOTSTRAP =
      """
      set -e
      set --
      # The run's worktree is /work/<runId> and the home the target sets is under /work too; both
      # are made ready here, as the build user, and a root that cannot be written is the daemon's
      # to report, not this text's.
      mkdir -p /work 2>/dev/null || true
      if [ -n "$HOME" ] && [ ! -d "$HOME" ]; then
        mkdir -p "$HOME" 2>/dev/null || true
      fi
      if [ -n "$QITS_TOKEN" ]; then
        set -- --header "Authorization: Bearer $QITS_TOKEN"
      fi
      attempt=1
      while :; do
        if command -v wget >/dev/null 2>&1; then
          downloader=wget
          if wget -q -T 20 "$@" -O /tmp/qits-ci-daemon "$QITS_CI_DAEMON_BINARY_URL"; then
            break
          fi
        elif command -v curl >/dev/null 2>&1; then
          downloader=curl
          if curl -fsS --connect-timeout 10 --max-time 120 "$@" -o /tmp/qits-ci-daemon "$QITS_CI_DAEMON_BINARY_URL"; then
            break
          fi
        else
          echo "qits-ci: this image has neither wget nor curl, so the ci daemon cannot be fetched" >&2
          exit 127
        fi
        if [ "$attempt" -ge 10 ]; then
          echo "qits-ci: $downloader could not fetch $QITS_CI_DAEMON_BINARY_URL after $attempt attempts" >&2
          exit 1
        fi
        echo "qits-ci: $downloader could not fetch $QITS_CI_DAEMON_BINARY_URL (attempt $attempt), retrying" >&2
        attempt=$((attempt + 1))
        sleep 12
      done
      chmod +x /tmp/qits-ci-daemon
      if [ -n "$QITS_CI_REGISTRY_AUTH_CONFIG" ] && [ -n "$DOCKER_CONFIG" ]; then
        mkdir -p "$DOCKER_CONFIG"
        printf '%s' "$QITS_CI_REGISTRY_AUTH_CONFIG" > "$DOCKER_CONFIG/config.json"
      fi
      if [ -n "$QITS_TOKEN" ]; then
        cat > /tmp/qits-publish-token <<'EOF'
      #!/bin/sh
      # There is nothing to exchange: this run's ci-run token IS the credential, handed over as
      # $QITS_TOKEN and deleted when the run closes. So this prints it — raw, one line, nothing
      # else — for a recipe that runs $QITS_PUBLISH_TOKEN_COMMAND. It fails loudly, because a
      # publish which silently loses its credential is the failure this script exists to prevent.
      if [ -z "$QITS_TOKEN" ]; then
        echo "qits-ci: this step holds no QITS_TOKEN, so there is no token to print" >&2
        exit 1
      fi
      printf '%s\\n' "$QITS_TOKEN"
      EOF
        chmod 0700 /tmp/qits-publish-token
        cat > /tmp/qits-git-credential <<'EOF'
      #!/bin/sh
      # Answers only the host this step clones from — the authority of $QITS_CI_REPOSITORY_URL, the
      # git host's public name. Git may invoke this helper for any remote in a repository (including
      # a repository-authored submodule), and handing the run's credential to an arbitrary host
      # would be an exfiltration vulnerability. The edge reads the password of git's Basic as a
      # qits_tok_ and introspects it; the user is a formality.
      [ "$1" = get ] || exit 0
      githost=${QITS_CI_REPOSITORY_URL#*://}
      githost=${githost%%/*}
      host=
      protocol=
      while IFS= read -r line && [ -n "$line" ]; do
        case "$line" in host=*) host=${line#host=};; protocol=*) protocol=${line#protocol=};; esac
      done
      [ -n "$githost" ] && [ "$host" = "$githost" ] || exit 0
      case "$protocol" in http|https) ;; *) exit 0;; esac
      [ -n "$QITS_TOKEN" ] || exit 0
      printf 'username=oauth2\\npassword=%s\\n\\n' "$QITS_TOKEN"
      EOF
        chmod 0700 /tmp/qits-git-credential
        # http.postBuffer at qits-githost's own max-pack-size (64M), so every push it would accept
        # leaves git as ONE buffered POST with a Content-Length. At git's 1 MB default a larger
        # push is streamed through a curl read callback instead, and the images' libcurl (7.88.1)
        # over HTTP/2 can call that callback again after it returned EOF: git-remote-https then
        # blocks reading a pipe send-pack will never write to again, the request body never ends,
        # and the push hangs until the step's timeout (qits-887).
        printf '[credential]\n\thelper = /tmp/qits-git-credential\n[http]\n\tpostBuffer = 67108864\n' > "$GIT_CONFIG_GLOBAL"
        QITS_PUBLISH_TOKEN=$QITS_TOKEN
        export QITS_PUBLISH_TOKEN
        # Maven's credential for every platform repository a step dials through the edge, every one
        # a 401 without it: `qits` (the deploy), `qits-maven-network` (the hosted registry) and
        # `qits-central-proxy` (the mirror's central) — each server id this estate's settings name.
        # A header rather than a <password>, because maven does not authenticate preemptively: a
        # password would simply never be sent. THE BLOCKER IS RE-DECLARED ON PURPOSE: -gs REPLACES
        # maven's conf/settings.xml, whose only live element is the maven-default-http-blocker
        # mirror, and omitting it would silently remove the block on plain-http external
        # repositories for every maven run in the step. MAVEN_ARGS IS APPENDED TO, NEVER ASSIGNED:
        # a step image may already set it.
        (umask 077; printf '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
        <servers>
          <server>
            <id>qits</id>
            <configuration>
              <httpHeaders>
                <property>
                  <name>Authorization</name>
                  <value>Bearer %s</value>
                </property>
              </httpHeaders>
            </configuration>
          </server>
          <server>
            <id>qits-maven-network</id>
            <configuration>
              <httpHeaders>
                <property>
                  <name>Authorization</name>
                  <value>Bearer %s</value>
                </property>
              </httpHeaders>
            </configuration>
          </server>
          <server>
            <id>qits-central-proxy</id>
            <configuration>
              <httpHeaders>
                <property>
                  <name>Authorization</name>
                  <value>Bearer %s</value>
                </property>
              </httpHeaders>
            </configuration>
          </server>
        </servers>
        <mirrors>
          <mirror>
            <id>maven-default-http-blocker</id>
            <mirrorOf>external:http:*</mirrorOf>
            <name>Pseudo repository to mirror external repositories initially using HTTP.</name>
            <url>http://0.0.0.0/</url>
            <blocked>true</blocked>
          </mirror>
        </mirrors>
      </settings>
      ' "$QITS_TOKEN" "$QITS_TOKEN" "$QITS_TOKEN" > /tmp/qits-deploy-settings.xml)
        MAVEN_ARGS="${MAVEN_ARGS:+$MAVEN_ARGS }-gs /tmp/qits-deploy-settings.xml"
        export MAVEN_ARGS
        # And npm's: one _authToken per npm HOST, APPENDED so an image's own ~/.npmrc survives. The
        # hosts are code under $QITS_DOMAIN (qits-731): the registry one serves the hosted @qits
        # scope and the mirror one the npmjs cache, and npm keys a token by the host it dials,
        # whichever path under it. A home that cannot be written costs npm its credential and
        # says so; it never costs the step its daemon.
        if [ -z "$QITS_DOMAIN" ]; then
          echo "qits-ci: this step was told no QITS_DOMAIN, so npm holds no platform credential" >&2
        elif [ -n "$HOME" ] && [ -d "$HOME" ] && [ -w "$HOME" ]; then
          for npm_host in "registry.qits.$QITS_DOMAIN" "mirror.qits.$QITS_DOMAIN"; do
            (umask 077; printf '//%s/:_authToken=%s\\n' "$npm_host" "$QITS_TOKEN" >> "$HOME/.npmrc") \\
              || echo "qits-ci: could not write $HOME/.npmrc, so npm holds no platform credential" >&2
          done
        else
          echo "qits-ci: the home directory is not writable, so npm holds no platform credential" >&2
        fi
      fi
      # Every maven call in every step retries a failed download, a connect timeout included
      # (qits-1169): an edge or mirror redeploy must not fail a build. Maven 3.9 reads MAVEN_ARGS on
      # every invocation, so this one line covers builds, releases and automations. Measured on
      # Maven 3.9.12 / resolver 1.9.25: the default transport never retries a connect timeout or a
      # refused connection, so the transport is wagon. In wagon-http 3.5.3, `nonRetryableClasses`
      # counts only with handler class `default`, and an EMPTY list falls back to the stock list
      # (connect timeouts and refusals included), so it names UnknownHostException alone. The 503
      # strategy is off unless its class is named. Wagon's connect timeout is
      # max(connectTimeout, requestTimeout), so both are set. Wagon sends server credentials and
      # httpHeaders as the default transport does. APPENDED, so an image's own MAVEN_ARGS is kept;
      # outside the token block, because a step with no token downloads too.
      #
      # The three aether.connector.http.retryHandler.* flags are for a different fork: a Quarkus
      # @QuarkusTest surefire fork, whose bootstrap resolver (quarkus-bootstrap-maven-resolver
      # 3.40.1, resolver 1.9.27) carries no wagon and uses the Apache HttpClient transport
      # instead. It reads them because surefire 3.5 promotes the build's user properties to the
      # fork's system properties, which BootstrapMavenContext copies into its session config. 502
      # and 504 are what the edge answers while it redeploys. httpclient hard-codes connect
      # timeouts and refused connections as non-retryable, and resolver 1.9 offers no key to
      # change that, so in the fork only the 60s connect timeout covers a short connect gap. The
      # parent's wagon transport ignores these keys.
      MAVEN_ARGS="${MAVEN_ARGS:+$MAVEN_ARGS }-Dmaven.resolver.transport=wagon -Dmaven.wagon.http.retryHandler.class=default -Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.http.retryHandler.nonRetryableClasses=java.net.UnknownHostException -Dmaven.wagon.http.serviceUnavailableRetryStrategy.class=standard -Dmaven.wagon.http.serviceUnavailableRetryStrategy.maxRetries=5 -Dmaven.wagon.http.serviceUnavailableRetryStrategy.retryInterval=5000 -Daether.connector.connectTimeout=60000 -Daether.connector.requestTimeout=60000 -Daether.connector.http.retryHandler.count=5 -Daether.connector.http.retryHandler.interval=5000 -Daether.connector.http.retryHandler.serviceUnavailable=429,502,503,504"
      export MAVEN_ARGS
      exec /tmp/qits-ci-daemon
      """;

  /**
   * The flags {@link #BOOTSTRAP} appends to {@code MAVEN_ARGS} in every step, so every maven call
   * retries a failed download, a connect timeout included (qits-1169). The text is typed into
   * {@code BOOTSTRAP} too, because that text interpolates nothing; {@code CiSlotSettingsTest}
   * asserts the two agree. Why each flag is there is told beside the line in {@code BOOTSTRAP}. The
   * trailing three {@code aether.connector.http.retryHandler.*} flags are not for the build's own
   * wagon transport at all: a {@code @QuarkusTest} surefire fork's HttpClient-transport resolver
   * reads them, which no wagon flag can reach.
   */
  static final String MAVEN_RETRY_ARGS =
      "-Dmaven.resolver.transport=wagon"
          + " -Dmaven.wagon.http.retryHandler.class=default"
          + " -Dmaven.wagon.http.retryHandler.count=5"
          + " -Dmaven.wagon.http.retryHandler.nonRetryableClasses=java.net.UnknownHostException"
          + " -Dmaven.wagon.http.serviceUnavailableRetryStrategy.class=standard"
          + " -Dmaven.wagon.http.serviceUnavailableRetryStrategy.maxRetries=5"
          + " -Dmaven.wagon.http.serviceUnavailableRetryStrategy.retryInterval=5000"
          + " -Daether.connector.connectTimeout=60000"
          + " -Daether.connector.requestTimeout=60000"
          + " -Daether.connector.http.retryHandler.count=5"
          + " -Daether.connector.http.retryHandler.interval=5000"
          + " -Daether.connector.http.retryHandler.serviceUnavailable=429,502,503,504";

  /**
   * Where the registry push credential lands inside a step container, and the whole reason it can
   * land anywhere at all.
   *
   * <p><b>Outside the worktree, on purpose.</b> The daemon checks the repository out under {@code
   * /work/<runId>} and a publishing step builds from there, so a credential written
   * into the checkout would be inside the build context — one {@code COPY . .} away from being
   * baked into a published image, and one {@code git status} away from confusing the step's own
   * script. {@code /tmp} is neither, and it is gone with the container.
   *
   * <p>It is a directory rather than a file because {@code DOCKER_CONFIG} names a directory: the
   * docker CLI reads {@code config.json} inside it.
   */
  static final String REGISTRY_AUTH_DIR = "/tmp/qits-ci-registry-auth";

  /**
   * The script {@link #BOOTSTRAP} writes and {@code $QITS_PUBLISH_TOKEN_COMMAND} names: one
   * invocation, this run's {@code ci-run} token on stdout.
   *
   * <p>Under {@code /tmp} for {@link #REGISTRY_AUTH_DIR}'s reason — outside the checkout, so it is
   * in no {@code build} context and confuses no {@code git status} — and gone with the container.
   *
   * <p>The path is spelled here <em>and</em> typed into {@code BOOTSTRAP}, because that text
   * interpolates nothing at all; this constant is what a caller and a test may name, and
   * {@code CiSlotSettingsTest} asserts the two still agree.
   */
  static final String PUBLISH_TOKEN_COMMAND = "/tmp/qits-publish-token";

  /**
   * The maven global settings {@link #BOOTSTRAP} writes and appends to {@code MAVEN_ARGS}: three
   * {@code <server>} entries carrying this run's token as an {@code Authorization} header, and the
   * re-declared {@code maven-default-http-blocker} mirror that {@code -gs} would otherwise remove.
   *
   * <p>Under {@code /tmp} for {@link #REGISTRY_AUTH_DIR}'s reason — outside the checkout, so it is
   * in no build context and confuses no {@code git status} — mode 0600, and gone with the
   * container. Written only when the step holds a token: no file at all is better than a file carrying
   * an empty bearer.
   *
   * <p>The path is spelled here <em>and</em> typed into {@code BOOTSTRAP}, because that text
   * interpolates nothing at all; this constant is what a caller and a test may name.
   */
  static final String DEPLOY_SETTINGS_FILE = "/tmp/qits-deploy-settings.xml";

  /**
   * Which daemon binary a run started right now downloads: the version of the protocol dependency
   * this reactor pins, or {@code qits.ci.daemon-version-override} when a person set one.
   */
  @Inject CiDaemonPins pins;

  /** The lease's memory cap ({@code docker --memory}). */
  @ConfigProperty(name = "qits.ci.slot.memory")
  String memory;

  /** The lease's memory plus swap ({@code docker --memory-swap}); equal to the memory is no swap. */
  @ConfigProperty(name = "qits.ci.slot.memory-swap")
  String memorySwap;

  /** The lease's pid limit: every step of the run shares it. */
  @ConfigProperty(name = "qits.ci.slot.pids-limit")
  long pidsLimit;

  /** The lease's CPU share ({@code docker --cpus}). */
  @ConfigProperty(name = "qits.ci.slot.cpus")
  String cpus;

  /** The lease container's OOM score: a CI lease is the first thing the kernel may kill. */
  @ConfigProperty(name = "qits.ci.oom-score-adj", defaultValue = "1000")
  Integer oomScoreAdj;

  /** The platform's image namespace, {@code $QITS_IMAGE_REPOSITORY} in every lease. */
  @ConfigProperty(name = "qits.artifacts.image-repository")
  String artifactsImageRepository;

  /**
   * The daemon package a release-phase step downloads the qits CLI from — {@code qits}, which also
   * answers to the name {@code qits-publish}. <b>The package, and only the package.</b>
   *
   * <p><b>The VERSION is a pinned dependency now, not a resolution.</b> This key used to be the
   * whole of what qits-ci said about the CLI, and the composed prelude read qits-artifacts' own
   * listing (its {@code latestVersion} field) at every step start to find out what to download —
   * which made one CLI release a shared, unversioned, unreviewed input to every release on the
   * platform at once. On 2026-09-13 one bad CLI broke all of them, with nothing changed in any
   * consumer's tree and no line anybody could revert. The version comes from {@code
   * eu.wohlben.qits:qits-platform-access-cli-binary} instead ({@link #artifactsCliVersion()}), so
   * the pom decides, the dependency has to resolve for this reactor to build, and a bad CLI is one
   * repository's red gate. {@code QitsCliPinIT} is what proves the pinned coordinate really exists.
   *
   * <p><b>Blank is still the off state, and still sends the variable EMPTY rather than a package
   * with nothing behind it.</b> Empty is the off state every mirror pair here already uses: a
   * composed release-phase prelude skips the fetch, and a composed postlude that needs the CLI fails
   * on its own {@code :?} guard naming this key, rather than a curl error nobody can place. The
   * version travels regardless — it is a constant, not a deployment fact, and there is nothing for a
   * deployment to switch off about it.
   *
   * <p>The shipped default and {@link PlatformAccessCliBinary#DAEMON_NAME} are the same string, and
   * {@code ArtifactsCliPackageDefaultTest} is what keeps them from drifting.
   */
  @ConfigProperty(name = "qits.ci.artifacts-cli-package")
  String artifactsCliPackage;

  /**
   * The emergency door over the pin, and it is normally UNSET.
   *
   * <p>Set, it is the version every release-phase step on this deployment downloads, whatever the
   * pom says — which is what an operator needs at 03:00 when a pinned CLI turns out to be broken and
   * the fix is a release of this repository they cannot wait for. Unset or blank, the pinned
   * dependency's constant stands, which is the ordinary state and the one every test here is in.
   *
   * <p>{@link Optional} with no shipped default, {@code WorkspaceContainerFactory.imageVersion()}'s
   * shape exactly: absent is the ordinary state, and setting one pins a CLI nothing gated.
   */
  @ConfigProperty(name = "qits.ci.artifacts-cli-version-override")
  Optional<String> artifactsCliVersionOverride;

  /**
   * Which qits CLI a release-phase step runs: the pinned dependency's version, unless an operator
   * has deliberately overridden it.
   *
   * <p>A method rather than a field resolved at injection, so a test can call it — and so the
   * environment builder below has one expression rather than a ternary nobody can name.
   *
   * <p><b>It can never answer blank.</b> {@link PlatformAccessCliBinary} refuses a missing or
   * unfiltered version at class-init, so the constant is either a real calver or the class does not
   * load at all; the override is only consulted when it is non-blank. That is what lets {@code
   * QITS_ARTIFACTS_CLI_VERSION} be relied on downstream as always-present-and-non-empty, and what
   * makes the composed prelude's {@code :?} guard a statement about an OLD qits-ci rather than about
   * a misconfiguration here.
   */
  String artifactsCliVersion() {
    return artifactsCliVersionOverride
        .filter(value -> !value.isBlank())
        .orElse(PlatformAccessCliBinary.VERSION);
  }

  /**
   * The run-pinned path of the daemon binary in qits-artifacts' daemons store, {@code
   * /artifacts/daemons/qits-ci-daemon/<version>}, under no origin: {@link CiSlotAddresses} puts it
   * under the registry's public name when a lease is composed.
   */
  public String resolveBinaryUrl(String version) {
    return CiSlotAddresses.daemonBinaryPath(CiDaemonBinary.DAEMON_NAME, version);
  }

  /** The daemon version a run accepted right now pins — never blank (see {@link CiDaemonPins}). */
  public String daemonVersion() {
    return pins.answer().version();
  }

  /** Every deployment fact a lease's spec is composed from that is not an address. */
  public CiSlotSpecs.Settings settings() {
    return new CiSlotSpecs.Settings(
        artifactsImageRepository,
        artifactsCliPackage,
        artifactsCliVersion(),
        memory,
        memorySwap,
        pidsLimit,
        cpus,
        oomScoreAdj);
  }
}
