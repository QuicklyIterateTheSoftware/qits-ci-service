package eu.wohlben.qits.ci.githost;

import java.util.Optional;

/**
 * The access token carried on qits-ci's reads from another platform service: the one {@code qits}
 * named oidc client's bearer (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4). {@link
 * IdpGitHostBearer} is the sole implementation, and it is shared rather than copied — {@code
 * githost/HttpGitHostRepoListing} and {@code projects/HttpProjectsRepoListing} inject it exactly as
 * {@code HttpGitConfigSource} does, which is why this interface is public rather than package-private.
 */
@FunctionalInterface
public interface GitHostBearer {
  Optional<String> token();
}
