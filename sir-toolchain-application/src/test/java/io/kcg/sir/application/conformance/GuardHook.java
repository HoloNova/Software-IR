package io.kcg.sir.application.conformance;

/**
 * Package-private functional interface for control-ownership guard
 * evaluation. The real implementation delegates to
 * {@link ControlOwnershipGuard#requireAtBoundary}. Tests inject a fake
 * that returns programmed results at specific boundaries.
 *
 * <p>This seam allows default {@code *Test} classes to verify that the
 * suite invokes the guard at every required boundary in the real
 * execution path, and that guard failure at any boundary prevents
 * subsequent operations.
 */
@FunctionalInterface
interface GuardHook {

    /**
     * Evaluate the guard at the given boundary.
     *
     * @param control             the control session
     * @param expectedServerUuid  the expected server UUID
     * @param boundary            the boundary being guarded
     * @return the guard result (passed iff all four conditions hold)
     */
    ControlOwnershipGuard.GuardResult evaluate(ControlSession control,
                                               String expectedServerUuid,
                                               ControlOwnershipGuard.Boundary boundary);
}

