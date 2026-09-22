package io.kcg.sir.application.conformance;

import io.kcg.sir.application.api.ChangeApplyOutcome;
import io.kcg.sir.application.api.ChangeBaselineReceipt;
import io.kcg.sir.application.api.ChangeOutputManifest;
import io.kcg.sir.application.api.ExecutionManifest;
import java.util.Objects;

/**
 * The structured materialization evidence for one conformance scenario.
 *
 * <p>Replaces the former "manifest is non-null" determinism check: an IG scenario
 * yields the initial-generation manifest, and an APPLY scenario yields both the B0
 * and B1 receipts and manifests plus the apply outcome, which together are enough
 * to assert the full transactional contract.
 *
 * <p>Sealed so the verifier must handle exactly these two shapes; an unknown
 * implementation is impossible rather than merely unlikely.
 */
public sealed interface MaterializationEvidence
        permits MaterializationEvidence.IgEvidence, MaterializationEvidence.ApplyEvidence {

    /**
     * Evidence for an initial-generation scenario.
     *
     * @param manifest the ExecutionManifest of the generated project
     */
    record IgEvidence(ExecutionManifest manifest) implements MaterializationEvidence {
        public IgEvidence {
            Objects.requireNonNull(manifest, "manifest");
        }
    }

    /**
     * Evidence for an Apply scenario.
     *
     * @param b0Receipt  the baseline receipt registered before the apply
     * @param b0Manifest the output manifest of the B0 state
     * @param b1Receipt  the baseline receipt published by the apply
     * @param b1Manifest the output manifest of the B1 state
     * @param outcome    the apply outcome reported by the Application layer
     */
    record ApplyEvidence(
            ChangeBaselineReceipt b0Receipt,
            ExecutionManifest b0Manifest,
            ChangeBaselineReceipt b1Receipt,
            ChangeOutputManifest b1Manifest,
            ChangeApplyOutcome outcome) implements MaterializationEvidence {
        public ApplyEvidence {
            Objects.requireNonNull(b0Receipt, "b0Receipt");
            Objects.requireNonNull(b0Manifest, "b0Manifest");
            Objects.requireNonNull(b1Receipt, "b1Receipt");
            Objects.requireNonNull(b1Manifest, "b1Manifest");
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}
