package io.kcg.sir.application.api;

/**
 * The four levels of evidence about one candidate project, in the order they are earned. Each level
 * proves only itself: a later level is never implied by an earlier one.
 *
 * <ul>
 *   <li>{@code STATIC}: the sources compile and the target generates its files. Not that Java builds.
 *   <li>{@code BUILD}: those exact files build into a runnable artifact. Not that it can start or reach a database.
 *   <li>{@code START}: the artifact starts against an isolated database and answers its readiness check.
 *       Not that it does what was asked.
 *   <li>{@code BUSINESS}: an oracle written from the original requirement, not from the candidate, accepts the
 *       observed behaviour. Not that untested requirements hold.
 * </ul>
 */
public enum VerificationStage {
    STATIC, BUILD, START, BUSINESS
}
