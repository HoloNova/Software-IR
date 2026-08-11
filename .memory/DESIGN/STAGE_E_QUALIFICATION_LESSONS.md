---
created: 2026-07-30
updated: 2026-07-30
importance: high
confidence: confirmed
source: experiment
status: reference
---

# Stage E Qualification Lessons

## Historical verified outcome

The 2026-07-30 Stage E run was accepted for its declared local MySQL reference-environment tuple.
ADR-016 and ADR-017 are accepted. The default Reactor passed, and two fresh
opt-in conformance runs each reached `QUALIFIED` after all five scenarios,
real advisory-lock checks, cleanup, and sanitized-evidence sealing completed.
This is a dated method and evidence record, not the current checkout's qualification and not a production, all-platform, or future-environment qualification. Current status is maintained only in `docs/qualification/CURRENT_QUALIFICATION.md`.

## Non-negotiable completion gate

A Stage E completion claim requires every item below. A default Maven pass is
not a substitute for any external item.

1. Frozen ADR and implementation specification are accepted before code starts.
2. Focused behavioral tests prove each new fail-closed branch through its real
   runtime entry point; source-text inspection and mock-only proof are not
   sufficient.
3. One default module/Reactor regression completes without implicitly running
   opt-in `*IT`.
4. Two fresh external runs use distinct schema names, workRoots, evidenceRoots,
   and ports; each must terminate as `QUALIFIED`.
5. Each external run proves all scenarios, real dual-JDBC advisory locks,
   Spring-process termination, schema absence after DROP, safe workRoot
   cleanup, and final sanitized-evidence sealing.
6. Evidence from the two runs may differ only in explicitly permitted
   run-specific fields. Any cleanup warning, ownership uncertainty, leaked
   secret representation, or residual process makes the run `FAILED`, never
   `QUALIFIED`.
7. The main agent independently reads the critical runtime paths, key tests,
   `git diff --check`, and `git status --short` before accepting the phase.

## Execution and review rules

- Do not report "complete" merely because new classes compile or default
  tests pass. Report the exact terminal qualification state and whether the
  external IT was actually executed.
- Convert every claimed contract into a checklist row: runtime entry point,
  fail-closed branch, behavioral test, and final acceptance evidence.
- Obtain or provision the isolated reference tuple before the final review
  cycle. Do not defer MySQL credentials, server identity, schema namespace,
  work/evidence parents, Maven executable/repository, and ports until after
  harness implementation.
- Run targeted tests while repairing one contract. Run the full Reactor only
  after the focused gate is stable, and rerun the exact same command only for
  documented Windows filesystem/process flakes.
- Preserve historical residue, caller parents, `.conformance-runs`, `.claude`,
  and user files. Never make qualification pass by adopting or deleting old
  workRoots, evidence, schemas, or unrelated files.

## Stage E-specific failure patterns

- An opt-in `*IT` not run is `NOT_RUN`, not a passing qualification.
- Evidence safety requires exact-tree inventory reconciliation, no pending
  streams, root/child identity proof before and after traversal, and a sealed
  report; a successful content-only secret scan is insufficient.
- On Windows, `BasicFileAttributes.fileKey()` may be absent. Strong identity
  must use an in-process native file identifier or fail closed; do not depend
  on a hanging external helper process.
- Control-connection identity, server UUID, advisory-lock ownership, exact
  SchemaName, marker token, and inventory proof are complementary. No one of
  them independently authorizes DROP.

## Boundary for follow-on work

Stage E does not authorize Stage F implementation. Any next stage must first
choose one minimal goal, freeze its own ADR/specification and acceptance matrix,
and obtain explicit user authorization. Change SIR v0.6 remains the completed
current Change SIR series; a v0.7+ operation needs its own ADR and versioned
contract.
