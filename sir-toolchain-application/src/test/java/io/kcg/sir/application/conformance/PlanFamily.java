package io.kcg.sir.application.conformance;

/**
 * The three pure Apply plan families the conformance matrix proves one
 * representative operation for.
 *
 * <p>"Pure" is the contract: an UPDATE plan may only change existing files, a
 * CREATE plan may only add files, and a DELETE plan may only remove files. The
 * verifier rejects a delta that mixes families, because a mixed delta would mean
 * the scenario no longer proves the family it claims to prove.
 */
public enum PlanFamily {
    UPDATE,
    CREATE,
    DELETE
}
