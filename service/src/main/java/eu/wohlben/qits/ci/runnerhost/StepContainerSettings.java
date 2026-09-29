package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.platformaccess.cli.PlatformAccessCliBinary;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>What a step container is made of</b>: the host-authored {@link #BOOTSTRAP} it runs, the paths
 * that bootstrap writes, and every deployment fact a step's workload spec is composed from — its
 * address plane ({@link #internalPlane}), the settings that are not addresses ({@link
 * #workloadSettings}), and the daemon build a run pins ({@link #daemonVersion}, {@link
 * #resolveBinaryUrl}).
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

  private static final Logger LOG = Logger.getLogger(StepContainerSettings.class);

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
   * <p><b>It is also where the registry push credential becomes a file.</b> The last block writes
   * {@code $DOCKER_CONFIG/config.json} from {@code $QITS_CI_REGISTRY_AUTH_CONFIG} when both are set,
   * which is how a step gets a small file that is <em>not</em> in the clone and therefore not in any
   * build context — see {@link #REGISTRY_AUTH_DIR}. Both variables are absent unless this run
   * commissioned a credential, so the block does nothing on a deployment with no oidc client, and it
   * stays zero-interpolation like the rest of this text: the credential is a value the shell reads
   * from its own environment, never a word in this string.
   *
   * <p><b>It is where a step's platform credential becomes usable at all, and there is exactly ONE
   * token exchange in this text.</b> Under the commission guard it writes {@link
   * #PUBLISH_TOKEN_COMMAND} — an executable script that exchanges {@code
   * $QITS_COMMISSIONED_CLIENT_ID}/{@code $QITS_COMMISSIONED_CLIENT_SECRET} for an access token at
   * {@code $QITS_GIT_AUTH_TOKEN_URL} and prints the raw token, nothing else — and the Git credential
   * helper beside it <em>calls</em> that script rather than carrying a second copy of the exchange.
   * The two callers want opposite failure behaviour and that is where they differ: the script itself
   * fails loudly, because a publish that quietly loses its credential is the failure it exists to
   * prevent, while the Git helper swallows the failure with {@code exit 0}, because a missing
   * credential must not break an unrelated fetch.
   *
   * <p><b>The token is handed over TWICE, as a variable and as a command, and both are needed.</b>
   * {@code qits.idp.token-ttl-seconds} is 3600 and a step's {@code timeout-seconds} may be 3600 too,
   * so a token minted at container start can be expired by the time a long step reaches its publish
   * — which is always the last thing it does. A short recipe reads {@code $QITS_PUBLISH_TOKEN}; one
   * far from its container's start runs {@code $QITS_PUBLISH_TOKEN_COMMAND} and gets a fresh one. A
   * mint that fails at bootstrap is a line on stderr and not a refusal: the daemon has to start
   * whatever happens, or the step reports nothing at all, and the variable is then simply unset.
   * Nothing echoes the value, here or anywhere: it is a secret, and this text runs under no
   * {@code set -x}.
   *
   * <p><b>The audience and the endpoint are the git-named pair on purpose.</b> {@code
   * $QITS_GIT_AUTH_AUDIENCE} is {@link #CONTAINER_GIT_AUDIENCE}, {@code qits-platform} — one
   * audience for every service on the platform, not a fact about git — and {@code
   * $QITS_GIT_AUTH_TOKEN_URL} is the idp's plain token endpoint, which git's helper was merely the
   * first caller of. A second variable carrying the same two values under a better name would be one
   * more thing to keep in step for no new fact, so the names stay historical and this paragraph is
   * the correction.
   *
   * <p><b>It is also where every {@code mvn deploy} on the estate gets its credential, with no
   * recipe change anywhere.</b> Beside the two handovers above it writes {@link
   * #DEPLOY_SETTINGS_FILE} — a {@code <server id=qits>} whose {@code Authorization} header is this
   * run's bearer, plus a re-declared {@code maven-default-http-blocker} mirror — and <em>appends</em>
   * {@code -gs} for it to {@code MAVEN_ARGS}. The id is {@code qits} because that is what {@code
   * -DaltDeploymentRepository="qits::default::…"} names, and it is an HTTP header rather than a
   * {@code <username>}/{@code <password>} pair because Maven does not authenticate preemptively, so
   * a password would never be sent. The blocker is re-declared because {@code -gs} <em>replaces</em>
   * Maven's {@code conf/settings.xml}, whose only live element it is. Two facts measured on Maven
   * 3.9.12: the merge with a {@code -s} user settings keeps both files' servers, and {@code
   * MAVEN_ARGS} beats an explicit {@code -gs} on the command line — so this silently overrides the
   * seven recipes that pass their own, which is accepted because both carry a valid credential and
   * every maven-deploy step is capped at 1800s against a 3600s TTL. A mint that failed writes no
   * file and leaves {@code MAVEN_ARGS} untouched, so a deploy runs exactly as it does today rather
   * than with an empty {@code Bearer }.
   *
   * <p><b>A step on the EDGE plane holds a token instead of a pair, and gets the same four things
   * from it</b> (epic qits-441). {@code $QITS_TOKEN} is this run's {@code ci-run} token, and when it
   * is set the text ahead of the pair's branch writes: the daemon download's {@code Authorization:
   * Bearer} (the one line both branches share, as a {@code "$@"} that is empty without a token, so
   * an internal step's download is the command it always was); a publish command that prints the
   * token rather than exchanging anything; a git helper answering {@code oauth2}/token for the host
   * {@code $QITS_CI_REPOSITORY_URL} names and nobody else; {@link #DEPLOY_SETTINGS_FILE} with the
   * bearer on every server id the estate's settings use ({@code qits}, {@code qits-maven-network},
   * {@code qits-central-proxy}); and an {@code _authToken} per npm registry host appended to {@code
   * ~/.npmrc}. The docker document is the registry block above, composed from the token in
   * {@link StepWorkloadSpecs}. The two branches never meet: an edge step carries no pair and an
   * internal one no token, and the pair's branch below is byte for byte what it was.
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
   * subject (username) on the EDGE plane only, so the repo's own credential profile resolves rather
   * than reading two empty environment expressions and sending an unauthenticated Basic the edge's
   * 401 challenge turns into exactly this failure. Never set on INTERNAL: that plane's mirror reads
   * are anonymous, and the two variables must stay unset there.
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
      # The edge plane's twin of the exchange below, with nothing to exchange: this run's ci-run
      # token IS the credential, handed over as $QITS_TOKEN and deleted when the run closes. So this
      # prints it — raw, one line, nothing else — and a recipe that runs $QITS_PUBLISH_TOKEN_COMMAND
      # works unchanged on either plane. It fails loudly for the reason the exchange does.
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
      # git host's public name — for the exfiltration reason the helper below states. The edge reads
      # the password of git's Basic as a qits_tok_ and introspects it; the user is a formality.
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
        printf '[credential]\n\thelper = /tmp/qits-git-credential\n' > "$GIT_CONFIG_GLOBAL"
        QITS_PUBLISH_TOKEN=$QITS_TOKEN
        export QITS_PUBLISH_TOKEN
        # Maven's credential for every platform repository a step dials through the edge, every one
        # a 401 without it: `qits` (the deploy), `qits-maven-network` (the hosted registry) and
        # `qits-central-proxy` (the mirror's central) — each server id this estate's settings name.
        # A header rather than a <password> for the client branch's reason: maven does not
        # authenticate preemptively. The blocker is re-declared for that branch's reason too.
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
        # And npm's: one _authToken per registry HOST the two npm roots name, APPENDED so an image's
        # own ~/.npmrc survives. A home that cannot be written costs npm its credential and says
        # so; it never costs the step its daemon.
        if [ -n "$HOME" ] && [ -d "$HOME" ] && [ -w "$HOME" ]; then
          npm_seen=
          for npm_root in "$QITS_NPM_REGISTRY_URL" "$QITS_NPM_PROXY_URL"; do
            npm_host=${npm_root#*://}
            npm_host=${npm_host%%/*}
            [ -n "$npm_host" ] || continue
            [ "$npm_host" != "$npm_seen" ] || continue
            npm_seen=$npm_host
            (umask 077; printf '//%s/:_authToken=%s\\n' "$npm_host" "$QITS_TOKEN" >> "$HOME/.npmrc") \\
              || echo "qits-ci: could not write $HOME/.npmrc, so npm holds no platform credential" >&2
          done
        else
          echo "qits-ci: the home directory is not writable, so npm holds no platform credential" >&2
        fi
      fi
      if [ -n "$QITS_COMMISSIONED_CLIENT_ID" ] && [ -n "$QITS_COMMISSIONED_CLIENT_SECRET" ]; then
        cat > /tmp/qits-publish-token <<'EOF'
      #!/bin/sh
      # The ONE token exchange a step container has. It prints this run's own access token on
      # stdout and nothing else, so a step can authenticate to a platform service — the artifacts
      # store above all, which no longer accepts an anonymous publish.
      #
      # It FAILS LOUDLY, unlike the Git helper below that calls it: a publish which silently loses
      # its credential is the failure this script exists to prevent, while a Git fetch that cannot
      # be authenticated must not be broken by this helper's absence.
      if command -v curl >/dev/null 2>&1; then
        response=$(curl -fsS --connect-timeout 2 --max-time 10 -u "$QITS_COMMISSIONED_CLIENT_ID:$QITS_COMMISSIONED_CLIENT_SECRET" \\
          -H 'Content-Type: application/x-www-form-urlencoded' --data "grant_type=client_credentials&audience=$QITS_GIT_AUTH_AUDIENCE" "$QITS_GIT_AUTH_TOKEN_URL") || {
          echo "qits-ci: the token endpoint could not be reached, so no token was minted" >&2
          exit 1
        }
      else
        # BusyBox wget knows neither --user nor --password, so the wget arm authenticates
        # with a composed Basic header. base64 may wrap long input; tr joins it.
        auth=$(printf '%s:%s' "$QITS_COMMISSIONED_CLIENT_ID" "$QITS_COMMISSIONED_CLIENT_SECRET" | base64 | tr -d '\\n')
        response=$(wget -qO- -T 10 --header "Authorization: Basic $auth" \\
          --post-data="grant_type=client_credentials&audience=$QITS_GIT_AUTH_AUDIENCE" "$QITS_GIT_AUTH_TOKEN_URL") || {
          echo "qits-ci: the token endpoint could not be reached, so no token was minted" >&2
          exit 1
        }
      fi
      token=$(printf '%s' "$response" | sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\\([^"]*\\)".*/\\1/p')
      if [ -z "$token" ]; then
        echo "qits-ci: the token endpoint answered without an access_token" >&2
        exit 1
      fi
      printf '%s\\n' "$token"
      EOF
        chmod 0700 /tmp/qits-publish-token
        cat > /tmp/qits-git-credential <<'EOF'
      #!/bin/sh
      # This helper deliberately answers only qits-githost. Git may invoke it for any remote in a
      # repository (including a repository-authored submodule), and handing its machine credential
      # to an arbitrary host would be an exfiltration vulnerability.
      [ "$1" = get ] || exit 0
      host=
      protocol=
      while IFS= read -r line && [ -n "$line" ]; do
        case "$line" in host=*) host=${line#host=};; protocol=*) protocol=${line#protocol=};; esac
      done
      [ "$host" = "$QITS_GIT_AUTH_HOST" ] || exit 0
      case "$protocol" in http|https) ;; *) exit 0;; esac
      # The exchange itself is the script above — one implementation of it in this text, never two.
      # Its failure is swallowed here on purpose: a missing credential must not break an unrelated
      # fetch, which is the opposite of what a publish wants.
      token=$(/tmp/qits-publish-token 2>/dev/null) || exit 0
      [ -n "$token" ] || exit 0
      printf 'username=oauth2\\npassword=%s\\n\\n' "$token"
      EOF
        chmod 0700 /tmp/qits-git-credential
        printf '[credential]\n\thelper = /tmp/qits-git-credential\n' > "$GIT_CONFIG_GLOBAL"
        # And the token as a VARIABLE, beside the command that re-mints it. Both, because
        # qits.idp.token-ttl-seconds and a step's own timeout-seconds are both 3600: a token minted
        # here can be expired by the time a long step reaches its publish, which is always the last
        # thing it does. A short recipe reads $QITS_PUBLISH_TOKEN; one far from its container's
        # start runs $QITS_PUBLISH_TOKEN_COMMAND and gets a fresh one.
        #
        # A mint that fails HERE is a warning and not a refusal: the daemon has to start whatever
        # happens, or the step reports nothing at all. The variable is then simply unset, and the
        # command is still there to be run. The value is never echoed — it is a secret.
        if QITS_PUBLISH_TOKEN=$(/tmp/qits-publish-token); then
          export QITS_PUBLISH_TOKEN
          # AND THE SAME TOKEN AS MAVEN'S CREDENTIAL FOR THE ARTIFACTS STORE, so that every
          # `mvn deploy` in every step authenticates without one recipe changing. The deployment
          # repository id is `qits` — what -DaltDeploymentRepository="qits::default::..." names —
          # and no .qits-maven-settings.xml on this estate declares a <server> with that id. It has
          # to be an Authorization HTTP header rather than a <username>/<password> pair, because
          # maven does not authenticate preemptively: a password would simply never be sent.
          #
          # THE BLOCKER IS RE-DECLARED ON PURPOSE. -gs REPLACES maven's conf/settings.xml, whose
          # only live element is the maven-default-http-blocker mirror. Omitting it here would
          # silently remove the block on plain-http external repositories for every maven run in
          # the step, which is a security regression handed out estate-wide.
          #
          # MAVEN_ARGS IS APPENDED TO, NEVER ASSIGNED: a step image may already set it, and
          # clobbering it would break that image's builds.
          #
          # MEASURED ON MAVEN 3.9.12 (the version every wrapper here pins):
          #   - MAVEN_ARGS="-gs A" together with an explicit -s user.xml yields effective settings
          #     carrying BOTH files' servers. The merge works.
          #   - MAVEN_ARGS BEATS THE COMMAND LINE: with MAVEN_ARGS="-gs A" and an explicit -gs B
          #     on the command line, the effective settings carried A. So this injection silently
          #     overrides the seven recipes that mint a token and pass their own -gs. That is
          #     ACCEPTED: both paths carry a valid credential for the same store. Theirs is minted
          #     at the call site, this one at container start, and every maven-deploy step on the
          #     estate is capped at timeout-seconds: 1800 against an idp TTL of 3600 — so the
          #     ambient token has at least 1800s of headroom whenever a step reaches its publish.
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
        ' "$QITS_PUBLISH_TOKEN" > /tmp/qits-deploy-settings.xml)
          MAVEN_ARGS="${MAVEN_ARGS:+$MAVEN_ARGS }-gs /tmp/qits-deploy-settings.xml"
          export MAVEN_ARGS
        else
          # No file is written and MAVEN_ARGS is left alone, so a deploy runs exactly as it does
          # today rather than against an empty `Bearer `. One line on stderr, never the token.
          echo "qits-ci: no publish token at container start; a step may re-mint one with /tmp/qits-publish-token, and no maven deploy settings were written" >&2
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
   * invocation, one freshly minted access token for this run's commissioned client on stdout.
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
   * The maven global settings {@link #BOOTSTRAP} writes and appends to {@code MAVEN_ARGS}: one
   * {@code <server id=qits>} carrying this run's token as an {@code Authorization} header, and the
   * re-declared {@code maven-default-http-blocker} mirror that {@code -gs} would otherwise remove.
   *
   * <p>Under {@code /tmp} for {@link #REGISTRY_AUTH_DIR}'s reason — outside the checkout, so it is
   * in no build context and confuses no {@code git status} — mode 0600, and gone with the
   * container. Written only when the mint succeeded: no file at all is better than a file carrying
   * an empty bearer, because the second would break a publish that works today.
   *
   * <p>The path is spelled here <em>and</em> typed into {@code BOOTSTRAP}, because that text
   * interpolates nothing at all; this constant is what a caller and a test may name.
   */
  static final String DEPLOY_SETTINGS_FILE = "/tmp/qits-deploy-settings.xml";

  @ConfigProperty(name = "qits.ci.network")
  String network;

  @ConfigProperty(name = "qits.ci.container-git-url")
  String containerGitUrl;

  /** The only token endpoint a step's Git credential helper may call. */
  @ConfigProperty(name = "quarkus.oidc-client.qits.auth-server-url")
  String idpUrl;

  /**
   * The audience a step container's Git helper asks the idp for: {@code qits-platform}, one audience
   * for every service (service-client-identity-plan.md, C4). The git host accepts it because C1
   * widened every receiver's audience check.
   *
   * <p>A constant, not a config key. {@code qits.ci.container-git-audience} existed only to carry the
   * old {@code <env>-qits-githost} value, and a leftover {@code QITS_CI_CONTAINER_GIT_AUDIENCE}
   * deployment entry kept handing step containers that old audience after the shipped default had
   * moved. Nothing reads that key now, so the entry shows as orphaned in qits-configuration (C10)
   * and a person can remove it. qits-workspaces' {@code
   * WorkspaceContainerFactory.CONTAINER_TOKEN_AUDIENCE} is the same decision.
   */
  static final String CONTAINER_GIT_AUDIENCE = "qits-platform";

  @ConfigProperty(name = "qits.ci.container-daemon-url")
  String containerDaemonUrl;

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

  @ConfigProperty(name = "qits.ci.daemon-binary-url-template")
  String daemonBinaryUrlTemplate;

  @ConfigProperty(name = "qits.ci.memory-limit")
  String memoryLimit;

  @ConfigProperty(name = "qits.ci.pids-limit")
  long pidsLimit;

  @ConfigProperty(name = "qits.ci.cpus")
  String cpus;

  @ConfigProperty(name = "qits.ci.oom-score-adj", defaultValue = "1000")
  Integer oomScoreAdj;

  /**
   * qits-artifacts' registry coordinates, injected into every step container so a publish script
   * names no deployment fact of its own. Receiver-named on purpose: they are the artifacts service's
   * address and image namespace, one spelling shared with qits-cd, which derives its pull references
   * from the same two values. Neither is dialled by <em>this</em> process — see {@link
   * StepWorkloadSpecs}.
   */
  @ConfigProperty(name = "qits.artifacts.registry-host")
  String artifactsRegistryHost;

  @ConfigProperty(name = "qits.artifacts.image-repository")
  String artifactsImageRepository;

  /**
   * Every registry host the run's credential is written into the docker {@code config.json} for.
   *
   * <p><b>The docker client sends a login per host, so one entry is one host's worth of auth.</b>
   * That was enough while a step both pulled and pushed against {@code
   * qits.artifacts.registry-host}; it stopped being enough when a step image started arriving from
   * the mirror vhost, because a document naming only the push registry leaves the pull
   * unauthenticated and the build dies on a 401 nothing in the pipeline mentions.
   *
   * <p><b>This key is not the whole document, and it is deliberately not asked to be.</b> Its
   * default is {@code qits.artifacts.registry-host}; {@link #authHosts} adds {@link
   * #buildkitRegistryHost} on top, because that is the host a converted recipe's push actually goes
   * to and a login is picked by hostname. What this key is for is the hosts only a deployment
   * knows: behind the edge it sets both vhosts — {@code
   * registry.dev.localhost:8080,mirror.dev.localhost:8080} — and every entry shares the one
   * commissioned pair, because it is one identity at one idp whatever hostname fronts it.
   */
  @ConfigProperty(name = "qits.ci.docker-auth-hosts")
  List<String> dockerAuthHosts;

  /**
   * The platform-wide kill switch for building through the platform-owned buildkitd, and the fleet
   * half of a two-owner arrangement: the runner owns its builder and fills {@code BUILDKIT_HOST}
   * into a build step's container when the key is absent; this switch is what an operator flips when
   * the build plane must go back to the host docker NOW ({@code QITS_CI_BUILDKIT_ENABLED=false}).
   *
   * <p><b>Off is loud, not silent.</b> This service then sends {@code BUILDKIT_HOST} <em>empty</em>
   * — the mirror pair's off value — and the runner defers to a present key, so a converted
   * recipe's first {@code buildctl} fails naming its missing builder instead of quietly building
   * through the socket it still holds. An unconverted recipe reads neither variable and is
   * untouched, which is what makes the switch safe to flip mid-fleet.
   */
  @ConfigProperty(name = "qits.ci.buildkit.enabled")
  boolean buildkitEnabled;

  /**
   * The registry as the PLATFORM BUILDER resolves it — what a converted recipe composes its {@code
   * --output name=…,push=true} reference from, as {@code $QITS_BUILD_REGISTRY}. It is not {@code
   * qits.artifacts.registry-host}: that one is the host daemon's view (a deployment fact of the
   * socket path's kind), while this is an alias on the platform network, where buildkitd lives and
   * pushes from.
   */
  @ConfigProperty(name = "qits.ci.buildkit.registry-host")
  String buildkitRegistryHost;

  /**
   * qits-artifacts' npm registry roots — the hosted repository {@code @qits/*} is published to, and
   * the pull-through cache of npmjs every install resolves through. Same receiver-naming rule as the
   * two above, and injected into every step container for the same reason: a repository's pipeline
   * writes its {@code ~/.npmrc} from these and spells no address of its own.
   *
   * <p><b>Who dials them is the opposite of {@code registry-host}'s answer</b> — the step container
   * itself, over qits-net, with no docker socket and no host daemon in the path. See {@link
   * StepWorkloadSpecs}.
   */
  @ConfigProperty(name = "qits.artifacts.npm.hosted-url")
  String artifactsNpmHostedUrl;

  /**
   * The environment label qits-deployments injects into every container ({@code QITS_ENVIRONMENT}),
   * read directly rather than through a dotted key: this is the one address on the internal plane
   * that is no longer a deployment fact — see {@link #internalNpmProxyUrl()}.
   */
  @ConfigProperty(name = "QITS_ENVIRONMENT", defaultValue = "dev")
  String environment;

  /**
   * qits-artifacts' hosted Maven repository root. Dialled by the step container over qits-net, like
   * the npm roots above, and injected so Maven release pipelines and dependency bump handlers never
   * hard-code a deployment address.
   */
  @ConfigProperty(name = "qits.artifacts.maven.registry-url")
  String artifactsMavenRegistryUrl;

  /**
   * qits-platform-mirror's Maven Central pull-through, injected into every step container in
   * <b>both</b> address planes — each naming the mirror by the route its own network can reach, and
   * both on {@code /mirror/maven}, which is the mirror's own route.
   *
   * <p><b>The step-url is dialled by the step container itself</b>, over qits-net (a userflows
   * mvnw), exactly like the npm proxy: {@code http://qits-platform-mirror:8080/mirror/maven/central},
   * the in-network alias, read anonymously.
   *
   * <p><b>The build-url is a docker-build arg, and it ships NON-EMPTY</b> —
   * {@code http://mirror.dev.localhost:8080/mirror/maven/central}. A pipeline passes it as
   * {@code --build-arg QITS_MAVEN_CENTRAL_URL} to a {@code docker build --network host}, so the
   * maven resolve inside a {@code RUN} sits in the HOST network namespace and resolves that vhost
   * the way the {@code FROM} lines resolve theirs — to 127.0.0.1 on the host, which is the edge. The
   * edge byte-caches those reads, which are anonymous and immutable, so the build plane gains the
   * cache rather than merely reaching one. The route has to be {@code /mirror} and never {@code
   * /artifacts}: the edge sends {@code /artifacts} to the hosted registry on every vhost, so a build
   * asking there 404s — and an early {@code /artifacts} build-url answering <em>401</em> is how the
   * edge was known to be reachable from a build at all. It must equally never be a qits-net alias,
   * which a host-netns {@code RUN} cannot resolve.
   *
   * <p><b>That supersedes the reading of 2026-09-01</b>, when this shipped empty because no mirror
   * address was believed resolvable from a build. What that measurement was missing is the edge
   * vhost above; both planes have carried the mirror since 2026-09-03.
   *
   * <p><b>Off is injected as EMPTY, never as absence.</b> Every {@code .qits-maven-settings.xml}
   * activates its central-proxy profile only on a non-empty {@code QITS_MAVEN_CENTRAL_URL} (measured
   * on Maven 3.9: an empty environment value does not activate a property-presence profile), so an
   * empty value means that build resolves Maven Central directly — which is what {@code
   * enabled=false} puts <b>both</b> planes in, the bootstrap lever for a platform whose mirror is
   * not up yet.
   */
  @ConfigProperty(name = "qits.mirror.maven-central.enabled")
  boolean mavenCentralMirrorEnabled;

  // Optional, not a defaulted String: SmallRye's String converter treats an empty property value as
  // null and fails a non-Optional injection point at boot (SRCFG00040) — and empty is a value this
  // key really takes, since blanking it is how a deployment whose build plane cannot reach the edge
  // turns that plane off. Optional absorbs both empty and absent as Optional.empty, which the
  // injection below maps to an empty QITS_MAVEN_CENTRAL_MIRROR_URL (build plane resolves direct).
  @ConfigProperty(name = "qits.mirror.maven-central.build-url")
  Optional<String> mavenCentralMirrorBuildUrl;

  @ConfigProperty(name = "qits.mirror.maven-central.step-url")
  String mavenCentralMirrorStepUrl;

  /**
   * qits-artifacts' docs repository root, including the {@code docs} namespace segment. Dialled by
   * the step container over qits-net like the npm and maven roots, and injected so a release
   * pipeline publishing its documentation names no deployment address.
   *
   * <p>The namespace is part of the value rather than the step's to choose: there is one docs
   * repository, seeded on first boot, and a pipeline that got to name one could publish into a
   * namespace nothing serves.
   */
  @ConfigProperty(name = "qits.artifacts.docs.url")
  String artifactsDocsUrl;

  /**
   * qits-artifacts' ROOT — scheme and authority, no path — configured value for {@code
   * $QITS_ARTIFACTS_URL}. Read this field directly only inside {@link #resolvedArtifactsUrl()};
   * everywhere else, call that method.
   *
   * <p><b>Blank by default and read as {@link Optional}</b>, not a plain {@code String}: SmallRye
   * fails a non-{@code Optional} injection on a blank value (SRCFG00040), and blank is the value
   * this key now ships.
   *
   * <p><b>An explicit value here still wins.</b> When it is blank, {@link #resolvedArtifactsUrl()}
   * derives the answer instead of shipping a name that resolves nowhere — see that method.
   */
  @ConfigProperty(name = "qits.artifacts.url")
  Optional<String> artifactsUrl;

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
   * qits-workspaces' root, injected into every step container so the release train's maintenance
   * step names no deployment fact of its own. Scheme, host and port only — the path is the caller's,
   * and a step spells {@code /workspaces/api/branches/release} itself.
   *
   * <p>Dialled by the step container, like the npm pair and unlike {@code registry-host}: an
   * ordinary HTTP call over qits-net, no socket and no host daemon in the path.
   */
  @ConfigProperty(name = "qits.ci.workspaces-url")
  String workspacesUrl;

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
      String secret,
      String daemonBinaryUrl,
      int stepTimeoutSeconds,
      boolean docker,
      boolean build,
      String user,
      Map<String, String> env) {}

  /**
   * Says once, at boot, that every step container would get an empty {@code $QITS_ARTIFACTS_URL} —
   * this is the one moment a misconfigured deployment can say so once instead of once per step. It
   * reads nothing but configuration. (The boot reap of this owner's step containers that used to
   * share this observer is gone with the in-process executor, qits-506: a runner's own boot sweep
   * removes what a previous life of it left behind.)
   */
  void onStart(@Observes StartupEvent event) {
    if (resolvedArtifactsUrl().isBlank()) {
      LOG.warnf(
          "qits.artifacts.url is blank and neither qits.artifacts.maven.registry-url (%s) nor "
              + "qits.artifacts.docs.url (%s) names a usable origin; every step container gets "
              + "an EMPTY $QITS_ARTIFACTS_URL",
          artifactsMavenRegistryUrl, artifactsDocsUrl);
    }
  }

  /**
   * The run-pinned download url for the daemon binary. The version and the url move together — one
   * template with a {@code {version}} placeholder rather than two free values that can disagree —
   * and the version is resolved once per run so a deploy landing mid-run cannot make step 3 speak a
   * different protocol than step 1.
   */
  public String resolveBinaryUrl(String version) {
    return daemonBinaryUrlTemplate.replace("{version}", version == null ? "" : version);
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
        mavenCentralMirrorEnabled,
        artifactsCliPackage,
        artifactsCliVersion(),
        buildkitEnabled,
        memoryLimit,
        pidsLimit,
        cpus,
        oomScoreAdj);
  }

  /**
   * The addresses of a step on qits-net — this bean's own keys, exactly the ones it has always read,
   * resolved the way it has always resolved them. A runner's step whose row says {@code INTERNAL} is
   * on this plane — the platform host's own {@code localhost} runner among them; the edge plane is
   * derived from this one ({@link StepAddressPlane#edge}), which is how it keeps every path.
   */
  public StepAddressPlane internalPlane() {
    return StepAddressPlane.internal(
        containerDaemonUrl,
        containerGitUrl,
        idpUrl,
        artifactsRegistryHost,
        buildkitRegistryHost,
        artifactsNpmHostedUrl,
        internalNpmProxyUrl(),
        artifactsMavenRegistryUrl,
        mavenCentralMirrorBuildUrl == null ? "" : mavenCentralMirrorBuildUrl.orElse(""),
        mavenCentralMirrorStepUrl,
        artifactsDocsUrl,
        resolvedArtifactsUrl(),
        workspacesUrl,
        authHosts(),
        network);
  }

  /**
   * qits-platform-mirror's own npm pull-through cache, on qits-net — code-derived rather than a
   * deployment fact, because the address is never anything but this: {@code
   * <environment>-qits-platform-mirror:8080}, the same alias every other in-network address in this
   * class composes off {@code QITS_ENVIRONMENT}, plus {@link StepAddressPlane#NPM_PROXY_PATH}, the
   * path the EDGE plane has always composed its own npm proxy from. A leftover deployment config
   * entry for this address (formerly {@code qits.artifacts.npm.proxy-url}) is therefore never read.
   */
  String internalNpmProxyUrl() {
    return "http://" + environment + "-qits-platform-mirror:8080" + StepAddressPlane.NPM_PROXY_PATH;
  }

  private static String value(String text) {
    return text == null ? "" : text;
  }

  /**
   * The value every step container reads as {@code $QITS_ARTIFACTS_URL} — the ONE place that
   * value is decided.
   *
   * <ol>
   *   <li>{@link #artifactsUrl} ({@code qits.artifacts.url}), when a deployment set it.
   *   <li>Otherwise, the scheme and authority of {@link #artifactsMavenRegistryUrl} ({@code
   *       qits.artifacts.maven.registry-url}) — set on every live deployment, so this is the
   *       normal arm.
   *   <li>Otherwise, the scheme and authority of {@link #artifactsDocsUrl} ({@code
   *       qits.artifacts.docs.url}).
   *   <li>Otherwise, empty, with one WARN at startup naming the three keys — see {@link
   *       #onStart}.
   * </ol>
   *
   * <p>Cheap and pure, so it is safe to call every time a workload spec is built rather than
   * caching a value computed once: three config strings in, one derived string out, no I/O.
   */
  String resolvedArtifactsUrl() {
    String explicit = artifactsUrl == null ? null : artifactsUrl.orElse(null);
    if (explicit != null && !explicit.isBlank()) {
      return explicit;
    }
    String fromMaven = originOf(artifactsMavenRegistryUrl);
    if (fromMaven != null) {
      return fromMaven;
    }
    String fromDocs = originOf(artifactsDocsUrl);
    if (fromDocs != null) {
      return fromDocs;
    }
    return "";
  }

  /**
   * The scheme and authority of {@code url} — {@code http://host:8080} out of {@code
   * http://host:8080/some/path} — or {@code null} when {@code url} is blank or has no scheme or
   * authority to read.
   */
  private static String originOf(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    try {
      URI uri = URI.create(url);
      if (uri.getScheme() == null || uri.getRawAuthority() == null) {
        return null;
      }
      return uri.getScheme() + "://" + uri.getRawAuthority();
    } catch (RuntimeException badUrl) {
      return null;
    }
  }

  /**
   * The hosts of the document, in the order they were configured, blanks dropped and duplicates
   * collapsed — a repeated host would be a duplicate JSON key, which is legal and useless.
   *
   * <p>An unset list means the registry host alone, which is both the shipped default's value and
   * the tolerance the hand-wired instances in the tests rely on: an unset field is a test's silence
   * rather than a wiring failure.
   *
   * <p><b>{@link #buildkitRegistryHost} is added on top, and that is where a push GOES.</b> The two
   * are separate config keys for an unchanged reason — one registry, two network positions — but
   * one document: a converted recipe composes its push reference from {@code $QITS_BUILD_REGISTRY},
   * so a document without that host leaves buildctl holding no login for the only address it
   * addresses. Harmless for exactly as long as the store lets an anonymous {@code /v2} publish
   * through, and every image push on the estate the moment it answers one with a Bearer challenge.
   * It follows {@link #buildkitEnabled} rather than the key alone: off, {@code $QITS_BUILD_REGISTRY}
   * is sent empty and a login for that alias would be an entry for an address nothing addresses.
   * The two keys may legitimately hold one value, which the duplicate check collapses.
   */
  private List<String> authHosts() {
    List<String> hosts = new ArrayList<>();
    for (String each : dockerAuthHosts == null ? List.<String>of() : dockerAuthHosts) {
      String host = value(each).trim();
      if (!host.isEmpty() && !hosts.contains(host)) {
        hosts.add(host);
      }
    }
    if (hosts.isEmpty()) {
      hosts.add(value(artifactsRegistryHost));
    }
    // buildctl reads the same document over its session, and it picks a login by hostname exactly
    // as the docker CLI does — so the registry the platform builder pushes to is named too, or a
    // converted recipe's push meets a Bearer challenge with no login to exchange.
    if (buildkitEnabled) {
      String buildRegistry = value(buildkitRegistryHost).trim();
      if (!buildRegistry.isEmpty() && !hosts.contains(buildRegistry)) {
        hosts.add(buildRegistry);
      }
    }
    return hosts;
  }

  /**
   * The smart-HTTP url of a repository, as reachable from inside a step container: {@code
   * <base>/git/<projectId>/<repoName>} when the run carries the public coordinate, and the
   * id-addressed {@code <base>/git/<repoId>} when it does not.
   *
   * <p>{@code /git} is the codebase's second-level segment for the git wire protocol, so it lives
   * here; the configured base names only which service hosts it. It is the daemon's {@code
   * $QITS_CI_REPOSITORY_URL} — a value the container clones from, never a word in a command line.
   *
   * <p><b>The name-addressed form is the public clone address</b>, and after the identity cutover it
   * is the only one a step container can use: the id route belongs to qits-projects alone. The id
   * arm is the compatibility fallback for a run whose push was id-addressed, and on a pre-cutover
   * platform — where the storage id is the name — it produces the same URL it always did.
   */
  String cloneUrl(CiRepoRef repo) {
    return StepWorkloadSpecs.cloneUrl(containerGitUrl, repo);
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
