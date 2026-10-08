package io.kcg.sir.application.api;

/** Project planning phases; intentionally has no WRITE/COMMIT/RECOVERY stage. */
public enum ProjectChangeStage { READ, PARSE, SEMANTIC, LOWERING, GENERATION, PREFLIGHT, GRAPH, PLAN }
