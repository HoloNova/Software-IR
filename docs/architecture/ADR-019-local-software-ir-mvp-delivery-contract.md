# ADR-019: Local Software IR MVP Delivery Contract

- Status: Proposed
- Date: 2026-08-04
- Scope: local Java 21 lifecycle entry for the existing Spring Boot Target V0_2
- Decision owners: architecture main Agent and user

## 1. Decision status

This ADR proposes, but does not authorize implementation of, the local Software IR MVP. Implementation may start only after the architecture main Agent accepts this ADR and the accompanying design specification and dispatches bounded execution work.

The single chosen scope is to extend the existing `kcg-cli` with the thinnest local lifecycle entry for `generate`, `register`, `apply`, and `recover`, while preserving the existing `context` and `plan` contracts. The CLI delegates all compilation, graph, baseline, transaction, and recovery decisions to the existing Application APIs. Two narrowly scoped Application API adaptations are permitted because the current public APIs do not safely close the CLI workflow:

1. registration from an exact base SIR without requiring the caller to manufacture a Snapshot V1 file; and
2. Apply binding to the exact candidate SIR bytes inspected by the CLI, closing the candidate-file TOCTOU window.

No other production semantics are changed.

## 2. Context and problem

The repository already has the complete in-process chain, authoritative Baseline Bundle V1, read-only planning, UPDATE/CREATE/DELETE Apply, explicit Recovery, Stage E target qualification, and Stage F read-only `kcg context` and `kcg plan`. It does not yet expose a supported local product path that can:

```text
SIR
-> generate
-> register B0
-> context / plan
-> apply candidate
-> build and run the generated project
-> prove HTTP / MySQL behavior
-> recover or apply another candidate
```

The missing entry cannot be filled by shell code that writes snapshots, Bundles, `CURRENT`, or journals. Those are Application-owned state. Nor can Apply trust a previously returned plan or an inspected candidate path: ADR-014 requires Apply to rebuild all evidence, and a path can be replaced between inspection and Apply.

The MVP is deliberately local and narrow: one machine, one user workflow, Java 21, one Spring Boot Target V0_2, and the `campus-market` reference project. It is a feasibility proof, not a production distribution or platform.

## 3. Decision drivers

1. Preserve Resolve-once, target-neutral Core, Lowered IR decision authority, pure Generator, and Application-owned file system boundaries.
2. Reuse ADR-014, ADR-015, and ADR-016 without redefining baseline, transaction, or recovery semantics.
3. Preserve Stage F `context` and `plan` command bytes and behavior.
4. Make all stateful operations fail closed under lock contention, candidate drift, output drift, crash residue, and ambiguous recovery.
5. Provide a real, repeatable local proof through Maven, Spring, HTTP, validation, and MySQL without promoting the Stage E harness format to a product protocol.
6. Avoid implicit current directories, `latest`, names, interactive prompts, hidden defaults, and caller-managed infrastructure files.

## 4. Chosen architecture

### 4.1 Product boundary

`kcg-cli` is an adapter, not an owner of compiler or transaction semantics. It may parse strict arguments, turn them into typed Application requests, invoke one public Application method, and render canonical JSON. It must not:

- compile or generate independently;
- serialize or persist a ProjectGraph snapshot;
- read or write `CURRENT`, Bundle contents, `LOCK`, or transaction journals;
- inspect output files to recreate PROTECT;
- accept an old `ChangePlan` as Apply authority;
- infer SymbolId or AstNodeId from text, names, spans, ordering, or identifier strings.

The logical command name is `kcg`. Packaging as a native launcher, fat JAR, installer, shell script, or operating-system package is outside this ADR. Implementations and tests use the already supported Stage F launch mechanism.

### 4.2 Application API adaptations

#### Generated-baseline registration

Add an Application-owned request equivalent to:

```java
GeneratedBaselineRegistrationRequest(
    Path baseSirFile,
    SourceId sourceId,
    Path outputRoot,
    Path stateRoot)
```

and a `ChangeExecutionApplication.registerGeneratedBaseline(...)` operation returning the existing registration result family.

Under the same create-capable `stateRoot/LOCK` and journal gate used by registration, the Application must:

1. read the exact base SIR bytes once;
2. compile them through the shared `SirCompilation`;
3. build and validate the ProjectGraph;
4. serialize Canonical Snapshot V1 in memory;
5. derive the exact source digest, GraphVersion, graph canonical digest, Snapshot format, target profile, Lowered IR version, and output manifest;
6. verify the complete generated manifest against `outputRoot` using existing registration protection rules; and
7. publish the immutable B0 Bundle and `CURRENT` using the existing registration core.

No temporary caller-visible snapshot file is created. Existing `register(ChangeBaselineRegistrationRequest)` remains source and behavior compatible.

#### Digest-bound Apply

Evolve `ChangeApplyRequest` compatibly with an optional expected candidate SIR SHA-256 value. Preserve the existing five-argument constructor and its existing semantics. Add a named factory for the digest-bound form. The CLI must only use the digest-bound form.

While holding the Apply lock, `ChangeExecutionApplication.apply(...)` must:

1. read candidate raw bytes once;
2. compute SHA-256 over those exact bytes;

3. compare it with the request value before UTF-8 decoding, compilation, Planner invocation, PROTECT, transaction-directory creation, or any output mutation; and
4. return a single stable binding diagnostic on mismatch.

The proposed diagnostic is `SIR-APP-CHANGE-BIND-009`. Mismatch produces no plan, no PROTECT execution, no journal, no Bundle, no `CURRENT` change, and no output change. The digest is not a command-line input: it is internally taken from the second, locked-independent context inspection immediately before Apply request construction. This binds Apply to the candidate content whose target selection the caller confirmed while retaining Apply's mandatory full rebuild.

### 4.3 Command protocol versions

Existing `context` and `plan` retain the accepted `KCG-CLI-CHANGE-PLANNING-V1` JSON protocol without byte or semantic change.

The four lifecycle commands use `KCG-CLI-LOCAL-MVP-V1`. A version-reporting command may list both supported protocol domains, but must not reinterpret old records. Stage E evidence is not a CLI response schema.

## 5. CLI contract

### 5.1 Rules common to every command

- Options are long-form, case-sensitive, and may occur exactly once.
- Unknown, duplicate, missing, empty, or positional arguments are usage failures.
- Paths must be caller-supplied absolute paths. No current-directory resolution, home expansion, environment substitution, `toRealPath`, URL decoding, trimming, case folding, or separator guessing is permitted.
- Syntax parsing performs lexical normalization only where the existing typed path guard requires it; the Application performs raw and normalized chain, symlink, containment, identity, and FileStore validation.
- `source-id` must already be the canonical `SourceId` representation. The CLI rejects input if construction would alter the caller's exact text.
- Digests are lowercase 64-character hexadecimal values. Transaction IDs are lowercase 32-character hexadecimal values.
- No command reads interactive input.
- No command accepts a config file, response file, `latest`, name-based target, or default working tree.
- Product commands consume no environment variables. The test-only real-environment harness has a separately frozen whitelist and credential boundary derived from ADR-017.
- Success and domain failures produce exactly one UTF-8 canonical JSON object followed by LF on stdout. Key ordering, array ordering, numeric format, escaping, and omission rules are deterministic and Locale-independent.
- stderr is empty for represented outcomes. It is reserved for sanitized launcher/JVM failures that prevent JSON rendering; it must not contain credentials, full environments, or reconstructed command lines.

Stable process exits remain:

| Exit | Meaning |
|---:|---|
| 0 | successful terminal result, including idempotent `NoChanges` or successful Recovery |
| 2 | command syntax or usage error |
| 3 | deterministic domain rejection or failed validation/protection |
| 4 | `RecoveryRequired` |
| 70 | sanitized unexpected internal failure before a structured result can be rendered |

### 5.2 `generate`

```text
kcg generate \
  --source-sir <absolute-file> \
  --source-id <canonical-workspace-relative-source-id> \
  --output-root <absolute-directory> \
  --conflict-policy fail-if-exists
```

Required options are exactly those shown. The only accepted conflict policy is `fail-if-exists`; it is explicit and has no default. The command targets the frozen Spring Boot Target V0_2 and calls `ToolchainApplication.execute(...)`. It does not register a baseline.

The success outcome is `GENERATED` and includes protocol, outcome, canonical sourceId, normalized absolute outputRoot, fixed target profile ID, Lowered IR version, GraphVersion, graph canonical digest, and a manifest sorted by relative path. The graph and digest are evidence only and are not registration authority.

The command may create only `outputRoot` content through `ToolchainApplication` and its existing `FileTransaction`. Failure follows the existing all-or-nothing generation contract.

### 5.3 `register`

```text
kcg register \
  --base-sir <absolute-file> \
  --source-id <canonical-workspace-relative-source-id> \
  --output-root <absolute-directory> \
  --state-root <absolute-directory>
```

The CLI constructs `GeneratedBaselineRegistrationRequest` and invokes the new Application adapter. It never accepts a snapshot, source digest, graph digest, manifest, baseline ID, or Target version from the caller.

Outcomes are:

- `REGISTERED`: B0 is durably published and `CURRENT` identifies it;
- `ALREADY_REGISTERED`: the exact authoritative baseline is already current and no state changes were required;
- `RECOVERY_REQUIRED`: an unfinished or uninterpretable transaction blocks registration;
- `FAILURE`: one stable diagnostic and no newly authoritative baseline.

The response includes the existing registration receipt fields, including baselineId and the exact outputRoot binding. Receipts are evidence and subsequent request material, not substitutes for Bundle contents.

### 5.4 `context`

```text
kcg context \
  --state-root <absolute-directory> \
  --output-root <absolute-directory> \
  --candidate-sir <absolute-file>
```

The accepted Stage F syntax, existing-only lock behavior, target discovery, JSON bytes, and zero-persistent-write contract are unchanged. In particular, missing `stateRoot/LOCK` fails closed and must not create it.

### 5.5 `plan`

```text
kcg plan \
  --state-root <absolute-directory> \
  --output-root <absolute-directory> \
  --candidate-sir <absolute-file> \
  --expected-context-id <lowercase-sha256> \
  --target-key <lowercase-sha256> \
  --change-ir-version <V0_1|V0_2|V0_3|V0_4|V0_5|V0_6> \
  --operation <accepted-stage-f-operation-token>
```

The accepted Stage F syntax and behavior remain unchanged. It obtains a second context, matches `expected-context-id`, selects exactly one opaque `target-key`, builds a typed `ChangeSet`, and uses `ChangeBaselinePlanningRequest.digestBound(...)`. It never accepts a user-supplied candidate digest and never performs persistent writes.

The accepted operation tokens, also reused unchanged by `apply`, are:

```text
modify-capability-workflow
add-capability
remove-capability
modify-input-field-constraints
modify-unreferenced-input-field-type
modify-actorless-readonly-capability-exposure
```

### 5.6 `apply`

```text
kcg apply \
  --state-root <absolute-directory> \
  --output-root <absolute-directory> \
  --candidate-sir <absolute-file> \
  --expected-context-id <lowercase-sha256> \
  --target-key <lowercase-sha256> \
  --change-ir-version <V0_1|V0_2|V0_3|V0_4|V0_5|V0_6> \
  --operation <accepted-stage-f-operation-token>
```

The operation tokens and target-key meanings are exactly those accepted by `plan`; no new Change IR operation is added.

The CLI performs a fresh `inspectChangePlanningContext` call, requires exact contextId equality, requires exactly one matching target-key, obtains the candidate SHA-256 from that context result, constructs the `ChangeSet` from the returned authoritative receipt and typed opaque identities, and calls the digest-bound Apply request. It does not pass or trust an earlier plan.

Outcomes are:

- `APPLIED` with `FILES_AND_BASELINE` or `BASELINE_ONLY` and the authoritative new receipt;
- `NO_CHANGES` with the current authoritative receipt;
- `FAILURE` with `NO_CHANGES` or `ROLLED_BACK` effect classification;
- `RECOVERY_REQUIRED` with a recovery handle and no claim that output equals either baseline.

All UPDATE/CREATE/DELETE family separation and ADR-014/015/016 physical safety rules remain unchanged.

### 5.7 `recover`

```text
kcg recover \
  --state-root <absolute-directory> \
  --output-root <absolute-directory> \
  --transaction <any|lowercase-32-hex-transaction-id>
```

Recovery is explicit. `any` maps only to the existing `RecoveryHandle.any()` behavior; an exact ID maps only to that transaction. No command invokes Recovery automatically.

Outcomes are `RECOVERED`, `ROLLED_BACK`, `FAILURE`, or `RECOVERY_REQUIRED`, preserving the current Application result semantics. A successful response includes the authoritative current receipt when available. Recovery may touch only paths whose ownership and identity are proven by the existing journal and Bundle contracts.

## 6. Authoritative state and write boundaries

| Material | Authority | Consumer meaning |
|---|---|---|
| caller's base/candidate SIR bytes | caller | actual source material; digest never replaces missing bytes |
| generated output tree | `outputRoot` under Application protection | current physical generated files plus untracked user files that must remain untouched |
| immutable Bundle V1 | Application under `stateRoot` | exact source, Snapshot V1, descriptor, target versions, and manifest for one baseline |
| `CURRENT` | Application | sole baseline linearization point |
| `LOCK` | Application | common cross-JVM operation serialization gate |
| journal/staging/backup | Application transaction | recovery proof; never a public interchange format |
| context/plan/receipt JSON | evidence | useful for the next explicit command, never write authorization |

`stateRoot` is bound to one normalized absolute `outputRoot`. Migration is outside the MVP. `stateRoot` and `outputRoot` must not contain one another. All Bundle operations participate in the same lock and unfinished-journal gate.

| Command | Allowed persistent mutation |
|---|---|
| `generate` | generated files under `outputRoot`, solely through `ToolchainApplication` |
| `register` | `stateRoot/LOCK`, immutable B0 Bundle, and `CURRENT`, solely through registration |
| `context` | none; existing-only lock acquisition |
| `plan` | none; existing-only lock acquisition and read-only PROTECT |
| `apply` | proven planned output files plus transaction/Bundle/`CURRENT` state through Apply |
| `recover` | only journal-proven transaction, output, Bundle, and `CURRENT` state through Recovery |

Lock contention is a structured failure with no domain mutation. An unfinished journal blocks generate-independent Bundle operations until explicit Recovery. Repeated successful commands are deterministic: registration may report already registered; Apply of the exact current source reports `NO_CHANGES`; Recovery is idempotent after completion. Any ambiguous crash or external mutation returns `RECOVERY_REQUIRED` rather than guessing.

## 7. Lifecycle state machine

```text
UNGENERATED
  -- generate --> GENERATED_OUTPUT
  -- failure --> UNGENERATED

GENERATED_OUTPUT
  -- register --> CURRENT(B0)
  -- registration failure --> GENERATED_OUTPUT

CURRENT(Bn)
  -- context/plan --> CURRENT(Bn)
  -- apply no changes --> CURRENT(Bn)
  -- apply + CURRENT publish --> CURRENT(Bn+1)
  -- apply safe rollback --> CURRENT(Bn), FAILURE(ROLLED_BACK)
  -- crash/ambiguous failure --> RECOVERY_REQUIRED

RECOVERY_REQUIRED
  -- explicit recovery, CURRENT=Bn --> CURRENT(Bn)
  -- explicit recovery, CURRENT=Bn+1 --> CURRENT(Bn+1)
  -- proof failure --> RECOVERY_REQUIRED
```

Building and running the generated Spring project do not change KCG baseline state. They are acceptance activities controlled by the test-only harness.

## 8. Failure contract

| Condition | Result | Persistent effect claim |
|---|---|---|
| bad/duplicate/missing CLI option | exit 2 | command not invoked |
| external prerequisite absent before any acceptance side effect | `NOT_RUN` in test-only acceptance | no lifecycle operation started |
| missing create-capable registration lock path parent or invalid path | structured failure | no authoritative baseline |
| context/plan missing existing LOCK | exit 3, fail closed | exact-tree zero persistent writes |
| stale contextId or candidate digest mismatch | exit 3 | no Planner, PROTECT, transaction, Bundle, CURRENT, or output mutation |
| output tracked-file drift | exit 3 | no Apply mutation |
| lock contention | exit 3 | no domain mutation |
| safely rolled-back Apply failure | exit 3, `FAILURE/ROLLED_BACK` | CURRENT remains old baseline |
| incomplete/ambiguous transaction | exit 4, `RECOVERY_REQUIRED` | no consistency claim until Recovery |
| Recovery proof fails | exit 4 | preserve evidence; do not guess |
| lifecycle/E2E assertion fails after side effects began | `FAILED` | clean or recover owned state; retain sanitized evidence |

`NOT_RUN` is never a product command outcome and never disguises a started lifecycle failure. It is restricted to the opt-in MVP acceptance harness before generation, registration, Apply, Spring start, or schema mutation.

## 9. MVP feasibility acceptance

The single reference workflow uses Java 21, the current Spring Boot Target V0_2, a recorded Maven/JDK/OS/MySQL environment tuple, and run-specific `campus-market` fixtures.

The harness must prove, through real product/Application entry points:

1. generate the B0 project from initial SIR and register the exact B0;
2. `context` returns authoritative opaque targets;
3. `plan` returns a pure UPDATE plan;
4. Apply changes exactly the planned tracked files and publishes B1;
5. the generated project passes Maven verification;
6. a known business endpoint proves Spring Context availability;
7. a valid HTTP request succeeds and MySQL state matches the assertion;
8. an invalid Input returns 400 and causes no database write;
9. the next `context` and `plan` bind to the new `CURRENT`;
10. a real test-injected crash leaves a durable journal, and the real CLI Recovery path restores or completes it idempotently;
11. a pure AddCapability CREATE and a pure RemoveCapability DELETE are each applied and behaviorally verified;
12. cross-process lock contention, context/candidate drift, and tracked output drift each fail closed;
13. schema, process, runtime account, workRoot, and credential/evidence cleanup satisfy ADR-017 ownership rules.

The representative sequence is:

- B0 initial `campus-market`;
- UPDATE an existing Input constraint and verify valid/invalid PublishGoods behavior;
- CREATE the established actorless readonly SearchGoods capability and verify both capabilities;
- DELETE PublishGoods, verify SearchGoods survives and the removed route is unavailable;
- separately inject one recoverable crash window and prove repeated Recovery is idempotent.

This is a risk-minimal path, not a Cartesian product of every operation and every runtime assertion.

The run is `MVP_FEASIBLE` only when all required cells pass and all owned cleanup completes. It is `NOT_RUN` only when a declared external prerequisite is unavailable before any lifecycle or environment mutation. Every failure after the side-effect boundary is `FAILED`. `MVP_FEASIBLE` proves that the specified local lifecycle works in the recorded environment; it does not prove production security, HA, performance, portability, or general application correctness.

## 10. Implementation slices and gates

### Slice M1: Application input binding

- Allowed production module: `sir-toolchain-application` only.
- Add generated-baseline registration and digest-bound Apply request/API behavior.
- Preserve legacy registration and five-argument Apply request behavior.
- Tests: exact source/snapshot/manifest equivalence; candidate replacement before Apply; mismatch before decode/Planner/PROTECT; lock and journal gates; no-write assertions; compatibility tests.
- No Spring or MySQL required.
- Exit gate: all new Application tests and the existing registration/UPDATE/CREATE/DELETE/Recovery tests pass with unchanged formats.

### Slice M2: CLI bootstrap

- Allowed production module: `kcg-cli` only, plus only the POM wiring strictly required by that module.
- Add `generate` and `register`, lifecycle protocol rendering, strict arguments, and result mapping.
- Tests invoke real Application implementations in temporary roots; source-text inspection and mock-only tests are insufficient.
- Preserve `context`/`plan` golden JSON and exits byte-for-byte.
- No Spring or MySQL required.
- Exit gate: generated manifest and registered receipt exactly match Application state, with CLI unable to write Bundle internals.

### Slice M3: CLI mutation and recovery

- Allowed production module: `kcg-cli` only after M1.
- Add `apply` and `recover`; Apply must use fresh context plus digest-bound request.
- Tests cover all three pure plan families, NoChanges, rollback, RecoveryRequired, exact recovery handle, candidate/context/output drift, and real cross-JVM lock contention.
- No Spring or MySQL required.
- Exit gate: CLI results exactly map current Application results and all fail-closed/no-write boundaries hold.

### Slice M4: opt-in local MVP acceptance

- Test-only code/resources in `kcg-cli` and, only where crash hooks require it, the existing Application test domain. Minimal test-scope POM wiring is allowed; no production protocol is added.
- Reuse ADR-017 reference-environment ownership, advisory lock, credential redaction, evidence, cleanup, and `*IT` opt-in rules. Do not modify or replace accepted Stage E or Apply acceptance drivers.
- Run real CLI child processes, Maven, Spring, HTTP, and MySQL against fresh run-specific roots, schema, account, and ports.
- Exit gate: one fresh complete run reports `MVP_FEASIBLE`, then an independent rerun with new owned resources produces equivalent normalized evidence.

Slices are strictly ordered M1 -> M2 -> M3 -> M4. A slice does not authorize the next; each requires independent acceptance.

## 11. Compatibility

- Parser, Semantic, Lowering, Generator, PSG V0_1, GraphVersion V0_1, Snapshot V1, Bundle V1, journal V1/V2/V3, and Change SIR v0.1-v0.6 are unchanged.
- `ToolchainApplication` behavior is unchanged.
- Existing registration API remains available.
- Existing five-argument Apply request retains its behavior for current Java callers; the CLI never uses it.
- Existing `context` and `plan` syntax, JSON, exit codes, existing-only lock, and zero-write properties remain unchanged.
- Stage E qualification remains scoped to its recorded tuple and is not relabeled as MVP evidence.
- No generated Spring POM, actor transport, endpoint, or Target behavior is changed by this ADR.

## 12. Rejected alternatives

1. **CLI-generated snapshot file:** rejected because it moves canonical graph persistence and registration coupling outside Application and creates a partial handoff state.
2. **CLI direct Bundle/CURRENT/journal access:** rejected because it creates a second state authority and bypasses ADR-014 recovery proofs.
3. **Combined implicit generate-and-register command:** rejected because it hides two independently fail-able operations and weakens explicit source/output/state binding.
4. **Apply an old plan:** rejected because a plan is evidence, not authorization, and does not re-prove candidate bytes or physical state.
5. **Caller-provided candidate digest flag:** rejected because it would let the caller bind two independently supplied claims; the CLI must derive it from the fresh context result.
6. **Distribution/daemon/UI as MVP:** rejected because it adds unrelated compatibility and security surfaces before lifecycle feasibility is established.

## 13. Non-goals

- Change SIR v0.7 or any new change operation;
- mixed UPDATE/CREATE/DELETE plans;
- implicit or automatic Recovery;
- candidate SIR write-back;
- Java reverse parsing or user-region ownership;
- remote service, daemon, GUI, plugin, scheduler, authentication, multi-user or multi-tenant operation;
- second Target, Redis, Constraint VM, PSG REFERENCES, Snapshot V2, GraphVersion change, graph database, or incremental compilation;
- cross-FileStore or power-loss guarantees beyond current ADRs;
- installer, native image, public package repository, auto-update, public long-term command compatibility beyond the explicitly versioned records;
- production readiness, security certification, HA, load, performance, backup, or disaster recovery.

## 14. Risks and re-ADR triggers

| Risk | Mitigation | New ADR required when |
|---|---|---|
| CLI becomes a second state authority | Application-only adapters and black-box tests | CLI must inspect or mutate Bundle internals |
| candidate replacement after context | digest-bound Apply before decode/plan/protect | source comes from streams, remote stores, or mutable multi-file inputs |
| current APIs cannot bind registration atomically | one locked Application method | registration spans multiple targets or source units |
| acceptance harness leaks into product protocol | test-only evidence and launch surfaces | reports or commands become supported external API |
| one-target assumptions escape into Core | fixed CLI adapter to Target V0_2 | a second Target or target selection is introduced |
| local single-user assumptions fail | explicit roots and cross-JVM lock | shared/network/multi-user operation is required |
| generated files need mixed ownership | fail closed on untracked/unsupported cases | GENERATED/USER_OWNED/MIXED semantics are proposed |

Any change to Bundle, `CURRENT`, lock, journal, Recovery direction, Graph/Snapshot version, Change IR versions, command protocol compatibility, ownership model, or external credential boundary requires renewed architecture review and, where long-lived or irreversible, a new ADR.

## 15. Consequence

If accepted and implemented slice by slice, this contract supplies the smallest supported local lifecycle around the already proven compiler and change engine. Until all four slices and the real acceptance matrix pass, the project must not claim that the local MVP is complete or feasible.
