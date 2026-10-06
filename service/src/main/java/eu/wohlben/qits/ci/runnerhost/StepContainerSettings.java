package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonBinary;
import eu.wohlben.qits.platformaccess.cli.PlatformAccessCliBinary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * <b>What a step container is made of</b>: the host-authored {@link #BOOTSTRAP} it runs, the paths
 * that bootstrap writes, and every deployment fact a step's workload spec is composed from — the
 * settings that are not addresses ({@link #workloadSettings}), the spellings its address plane
 * recognises the platform's image stores by ({@link #imageSpellings}, {@link #plane}), and the
 * daemon build a run pins ({@link #daemonVersion}, {@link #resolveBinaryUrl}).
 *
 * <p><b>It starts nothing, and this process holds no docker socket and calls no orchestrator</b>
 * (qits-506). This class was {@code CiDaemonLauncher}, which asked qits-containers to put each step
 * container at a place and to remove it again, and reaped this owner's containers at boot. That
 * whole vocabulary is deleted with the in-process executor: the runner that holds a run starts each
 * step's container with its own host's {@code docker run} when {@link RunnerStepRunner} sends it a
 * {@code Launch}, removes it ({@code docker rm}) on a {@code Reap}, and its own boot sweep removes
 * what a previous life of it left behind. What survived is what the spec is composed from, which is
 * what this class is named for now; {@link StepWorkloadSpecs} is the composition.
 *
 * <p><b>qits-ci still executes nothing.</b> The step's script never appears in a spec assembled
 * here: it reaches the container as the reply on the socket that container's own daemon dialled, and
 * executes as that daemon's child inside the sandbox. {@link #BOOTSTRAP} is a compile-time constant
 * with <b>zero interpolation</b>, and it travels as JSON rather than as an argv — {@code entrypoint}
 * is {@code ["/bin/sh"]} and {@code args} is {@code ["-c", BOOTSTRAP]}, two list elements the runner
 * hands docker one at a time. Zero interpolation is therefore preserved <em>by construction</em>:
 * there is no string this text is concatenated into on either side of the wire.
 */
@ApplicationScoped
public class StepContainerSettings {

  /**
   * The label every step container still carries, as an <b>extra</b> label on the spec.
   *
   * <p>It selects nothing — the runner stamps and selects by its own {@code qits.ci.runner} labels,
   * and refuses a spec that reaches into that namespace. It stays because it is what a person reading
   * {@code docker ps} during a build has to go on.
   */
  static final String RUN_LABEL = "qits.ci.run";

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
   * thing it can ask for is the container log tail the runner sends back with its {@code Reaped}.
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
   * is a step this service never launches ({@code RunnerStepRunner} refuses one without a token).
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
   * {@link StepWorkloadSpecs#compose} sets both variables to this run's token (password) and its
   * subject (username), so the repo's own credential profile resolves rather than reading two empty
   * environment expressions.
   *
   * <p>{@code exec} rather than a plain call, so the daemon is PID 1 and the removal signals the
   * process that owns the step rather than a shell wrapping it.
   *
   * <p><b>It reaches the container as one list element, never as part of a command line.</b> {@link
   * StepWorkloadSpecs#compose} puts it in {@code args} beside {@code -c}, the runner hands both to
   * docker unsplit, and no string on either side of the wire is built by concatenating it with
   * anything.
   */
  static final String BOOTSTRAP =
      """
      set -e
      set --
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
      exec /tmp/qits-ci-daemon
      """;

  /**
   * Where the registry push credential lands inside a step container, and the whole reason it can
   * land anywhere at all.
   *
   * <p><b>Outside the clone, on purpose.</b> The daemon checks the repository out at {@code
   * /workspace} and a publishing step runs {@code docker build} from there, so a credential written
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
   * {@code StepContainerSettingsTest} asserts the two still agree.
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
   * {@link #daemonVersion()} delegates to it entirely, and this class reads no version key itself.
   *
   * <p>It was a <b>ladder</b> until the pin retirement — a durable table of candidate versions
   * adopted off {@code SoftwareRelease} frames and probed in throwaway containers, with an adopted
   * rung outranking the deployment's own pin. That is why this is still an injected collaborator
   * rather than a config field: the answer is one place's to give, and which sources it has is that
   * place's business.
   */
  @Inject CiDaemonPins pins;

  @ConfigProperty(name = "qits.ci.memory-limit")
  String memoryLimit;

  @ConfigProperty(name = "qits.ci.pids-limit")
  long pidsLimit;

  @ConfigProperty(name = "qits.ci.cpus")
  String cpus;

  @ConfigProperty(name = "qits.ci.oom-score-adj", defaultValue = "1000")
  Integer oomScoreAdj;

  /**
   * The registry host a run's pinned step image is addressed under — {@code CiRunService} builds the
   * pinned reference with it and {@code HttpImagePins} recognises a platform image by it. Read here
   * as a <em>spelling</em> only: a step image naming this host is the platform's own, and is pulled
   * from the registry's public name ({@link #imageSpellings}). It is never handed to a step.
   */
  @ConfigProperty(name = "qits.artifacts.registry-host")
  String artifactsRegistryHost;

  /** The platform's image namespace, {@code $QITS_IMAGE_REPOSITORY} in every step container. */
  @ConfigProperty(name = "qits.artifacts.image-repository")
  String artifactsImageRepository;

  /**
   * The machine spellings of the registry and of the mirror that the estate's committed files use —
   * {@code registry.<env>.localhost:<port>}, {@code mirror.<env>.localhost:<port>}. {@code
   * RunnerRegistryMirrors} reads the same two keys for the table a runner's builder rewrites by.
   */
  @ConfigProperty(name = "qits.ci.runner.registry-mirrors.registry-hosts")
  Optional<List<String>> registrySpellings;

  @ConfigProperty(name = "qits.ci.runner.registry-mirrors.mirror-hosts")
  Optional<List<String>> mirrorSpellings;

  /**
   * The environment label qits-deployments injects into every container ({@code QITS_ENVIRONMENT}).
   * Read for one thing: the qits-net aliases of qits-artifacts and qits-mirror in this
   * environment, which are two more spellings an image reference may carry. No alias is ever
   * handed to a step.
   */
  @ConfigProperty(name = "QITS_ENVIRONMENT", defaultValue = "dev")
  String environment;

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
   * Everything one step container is started with. Ids and names only — never entities.
   *
   * <p>{@code docker} is the step's own declaration, arriving from the repository's config by way of
   * the step seam. It is the single input that changes the sandbox, and it changes it in exactly one
   * way: the runner mounts its host's docker socket. See {@link StepWorkloadSpecs#compose}.
   *
   * <p>{@code user} is the step's other declaration, and the only other thing about a step that
   * reaches the sandbox. Empty means the image's own default; a name means {@code --user}, which is
   * the only place a step's user can be set — the container runs {@code --cap-drop=ALL} and can
   * neither {@code su} nor {@code chown} once it is running. The parser refuses it beside
   * {@code docker}, so the two never arrive together.
   *
   * <p>{@code stepTimeoutSeconds} is the step's own deadline as the pipeline declared it; zero
   * means it declared none.
   */
  public record LaunchSpec(
      String runId,
      int stepIndex,
      CiRepoRef repo,
      String branch,
      String sha,
      String image,
      String daemonId,
      String daemonBinaryUrl,
      int stepTimeoutSeconds,
      boolean docker,
      boolean build,
      String user,
      Map<String, String> env) {}

  /**
   * The run-pinned path of the daemon binary in qits-artifacts' daemons store, {@code
   * /artifacts/daemons/qits-ci-daemon/<version>}. The version is resolved once per run so a deploy
   * landing mid-run cannot make step 3 speak a different protocol than step 1. It is a path and no
   * origin: {@link StepAddressPlane#daemonBinaryUrl} puts it under the registry's public name when a
   * step is composed. ({@code qits.ci.daemon-binary-url-template}, which spelled the store's
   * qits-net alias around it, is deleted — qits-515.)
   */
  public String resolveBinaryUrl(String version) {
    return StepAddressPlane.daemonBinaryPath(CiDaemonBinary.DAEMON_NAME, version);
  }

  /**
   * The daemon version a run started right now would pin, and it is <b>never blank</b>: the pinned
   * protocol dependency's own version, which refuses to exist rather than resolve to {@code ""}.
   *
   * <p><b>Delegates entirely, and the delegate has changed twice.</b> This class read
   * {@code qits.ci.daemon-version} directly to begin with, with a boot-time check (long since
   * deleted, {@code daemonVersionComplaint}) warning when the value could not be the sha256 the old
   * digest-addressed template needed. It then read a pin ladder, which could still answer blank when
   * every adopted candidate had been rejected and no pin was configured — a state a whole readiness
   * check existed to report. It now reads a constant off the classpath, so the blank case has no
   * way to arise and nothing downstream needs to defend against it.
   */
  public String daemonVersion() {
    return pins.answer().version();
  }

  /**
   * Every deployment fact a step container's spec is composed from that is not an address, read off
   * this bean's own configuration at the moment of asking — so a suite that sets a field on a
   * hand-wired instance is composed with it.
   */
  public StepWorkloadSpecs.Settings workloadSettings() {
    return new StepWorkloadSpecs.Settings(
        artifactsImageRepository,
        artifactsCliPackage,
        artifactsCliVersion(),
        memoryLimit,
        pidsLimit,
        cpus,
        oomScoreAdj);
  }

  /**
   * The hosts an image reference names the platform's two stores by: {@code
   * qits.artifacts.registry-host}, the machine spellings of {@code
   * qits.ci.runner.registry-mirrors.*}, and the two qits-net aliases of this environment. Used to
   * recognise an image as the platform's and move it to the public name — by {@link #plane} for a
   * step's own image, by {@code RunnerRegistryMirrors} for the table a runner's builder gets.
   */
  public StepAddressPlane.ImageRegistries imageSpellings() {
    List<String> registry = new ArrayList<>();
    registry.add(artifactsRegistryHost);
    registry.addAll(registrySpellings == null ? List.of() : registrySpellings.orElse(List.of()));
    registry.add(environment + "-qits-artifacts:8080");
    List<String> mirror =
        new ArrayList<>(mirrorSpellings == null ? List.of() : mirrorSpellings.orElse(List.of()));
    mirror.add(environment + "-qits-mirror:8080");
    return StepAddressPlane.ImageRegistries.of(registry, mirror);
  }

  /** A step's addresses, from the public origin of each service — see {@link StepAddressPlane}. */
  public StepAddressPlane plane(StepAddressPlane.EdgeOrigins origins) {
    return StepAddressPlane.of(origins, imageSpellings());
  }

  /**
   * One name shape, shared by the {@code Launch} and the {@code Reap} a runner is sent: the one
   * string that means "this step of this run" and nothing else. It is derived from the whole {@code
   * runId} plus the step index and is deterministic, so a teardown addresses exactly what the launch
   * named, and it is the name a person reads in {@code docker ps} on the runner's host.
   *
   * <p><b>The leading characters of {@code runId} are a human hint, never the whole name.</b> Two
   * different run ids that happen to share their first 8 characters must never collide on the
   * resulting container name -- which a blind 8-character prefix does not guarantee, and which is
   * exactly the incident this guards against: every probe run id used to start with the literal
   * {@code "daemon-probe-"} constant, so its first 8 characters were always {@code "daemon-p"} and
   * two concurrent probes always named the same container. A short disambiguator derived from the
   * <em>whole</em> {@code runId} rides alongside the hint instead, so a shared prefix is no longer
   * enough to collide -- see {@code StepContainerSettingsTest} for the worked example, including the
   * case this incident actually hit.
   *
   * <p>{@code Integer.toHexString(runId.hashCode())} is deterministic: the same {@code runId} always
   * names the same container, which matters because the runner's {@code Reap} has to address what
   * its {@code Launch} put there. Its output is hex digits only, already inside
   * docker's container-name charset ({@code [a-zA-Z0-9][a-zA-Z0-9_.-]*}), so nothing further needs
   * sanitizing.
   */
  public static String containerName(String runId, int stepIndex) {
    String shortRun = runId.length() > 8 ? runId.substring(0, 8) : runId;
    String disambiguator = Integer.toHexString(runId.hashCode());
    return "qits-ci-" + shortRun + "-" + disambiguator + "-" + stepIndex;
  }
}
