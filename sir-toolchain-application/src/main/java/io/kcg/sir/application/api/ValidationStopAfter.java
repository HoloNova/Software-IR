package io.kcg.sir.application.api;

/** Last requested static stage; neither project building nor application execution is implied. */
public enum ValidationStopAfter { PARSE, SEMANTIC, LOWERING, GENERATION }
