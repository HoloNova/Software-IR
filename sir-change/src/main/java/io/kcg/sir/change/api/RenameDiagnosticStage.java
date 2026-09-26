package io.kcg.sir.change.api;

/**
 * Where a rename diagnostic was decided. The stages follow the order in which a rename has to be
 * proven: the request names a base, the identity says there is one declaration, the target says the
 * base holds it, the mapping says old and new artifacts are one to one, the path says the three file
 * sets are exclusive, the physical stage says the target can carry the rename at all, and the stale
 * stage says the plan still matches the graphs.
 */
public enum RenameDiagnosticStage {
   REQUEST,
   IDENTITY,
   TARGET,
   MAPPING,
   PATH,
   PHYSICAL,
   STALE;
}
