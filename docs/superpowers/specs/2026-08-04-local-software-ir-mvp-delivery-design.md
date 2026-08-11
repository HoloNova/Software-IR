# Local Software IR MVP Delivery Design

- Date: 2026-08-04
- Status: Proposed design specification
- Governing decision: ADR-019
- Target: local Java 21, Spring Boot Target V0_2, `campus-market`

## 1. Authorization and outcome

This specification freezes implementation boundaries only. It does not authorize implementation. The architecture main Agent must independently accept ADR-019 and this specification before assigning any execution slice.

The sole intended terminal proof is:

```text
MVP_FEASIBLE
```

for one recorded local reference-environment tuple. The other harness results are `FAILED` and `NOT_RUN`. Product commands retain their own structured result families and do not emit those harness statuses.

## 2. Current API facts and required gaps

### 2.1 Reused without semantic change

- `ToolchainApplication.execute(ToolchainRequest)` is the only generation entry. It runs the shared compilation, preflight, graph construction, and transactional output write.
- `ChangeExecutionApplication.register(...)`, `plan(...)`, `inspectChangePlanningContext(...)`, `apply(...)`, and `recover(...)` own Baseline Bundle, lock, transaction, and Recovery behavior.
- `ChangeBaselinePlanningRequest.digestBound(...)` already compares candidate raw-byte SHA-256 before decode/recompile/Planner/PROTECT.
- `kcg context` and `kcg plan` already provide strict opaque target selection and canonical JSON.
- ADR-014/015/016 define UPDATE, CREATE, DELETE, transaction journals, `CURRENT`, rollback, and Recovery.

### 2.2 Gap G1: initial registration input

The existing registration request requires a caller-supplied Snapshot V1 file. `ToolchainApplication` returns an in-memory graph but does not persist a snapshot. Making the CLI serialize and publish a temporary snapshot would give the product adapter file-system responsibility for an Application baseline input and create an avoidable handoff.

Required adaptation: Application constructs the Snapshot V1 bytes from the exact base compilation inside the registration lock and immediately feeds the existing registration publication path.

### 2.3 Gap G2: Apply candidate TOCTOU

The current Apply request does not bind candidate raw bytes to the fresh context that selected the target. The candidate path can change after context inspection but before Apply reads it.

Required adaptation: the CLI passes the context-returned candidate SHA-256 through a digest-bound Apply request; Apply compares it to the exact bytes it read while holding the operation lock before any semantic or physical work.

No other API gap authorizes a redesign.

## 3. Proposed public Application models

Names may be adjusted only to repository naming conventions; fields and semantics are fixed.

```java
public record GeneratedBaselineRegistrationRequest(
        Path baseSirFile,
        SourceId sourceId,
        Path outputRoot,
        Path stateRoot) {
}
```

```java
public ChangeBaselineRegistrationResult registerGeneratedBaseline(
        GeneratedBaselineRegistrationRequest request)
```

The result reuses the existing registration sealed hierarchy and receipt. No new Bundle format or receipt field is required.

`ChangeApplyRequest` becomes logically:

```java
public record ChangeApplyRequest(
        Path stateRoot,
        String expectedBaselineId,
        Path candidateSirFile,
        Path outputRoot,
        ChangeSet changeSet,
        Optional<String> expectedCandidateSirSha256Hex) {

    public ChangeApplyRequest(
            Path stateRoot,
            String expectedBaselineId,
            Path candidateSirFile,
            Path outputRoot,
            ChangeSet changeSet) {
        this(stateRoot, expectedBaselineId, candidateSirFile,
                outputRoot, changeSet, Optional.empty());
    }

    public static ChangeApplyRequest digestBound(/* six exact values */) {
        // validated construction
    }
}
```

The implementation must use the repository's existing SHA-256 value validation conventions. It must not parse or synthesize SymbolId/AstNodeId strings.

## 4. Generated-baseline registration algorithm

The new method follows:

```text
VALIDATE REQUEST
-> ACQUIRE create-capable stateRoot lock
-> JOURNAL GATE
-> READ exact base SIR bytes once
-> COMPILE through shared SirCompilation
-> REQUIRE Spring Boot Target Profile V0_2 and Lowered IR V0_2
-> BUILD + VALIDATE ProjectGraph V0_1
-> SERIALIZE Canonical Snapshot V1 in memory
-> RE-SERIALIZE/LOAD validation as required by existing registration core
-> DERIVE ChangeBaseRevision and descriptor
-> VERIFY complete outputRoot manifest
-> PUBLISH immutable Bundle
-> ATOMIC CURRENT
-> RETURN receipt
```

Mandatory properties:

1. A raw base source digest is computed over the exact bytes compiled.
2. The Snapshot bytes saved in the Bundle are the exact in-memory serializer output used for validation.
3. Manifest entries are taken from the same compilation and checked against physical output under existing safe path rules.
4. Target Profile, Lowered IR version, GraphVersion, and Snapshot version are derived, never caller supplied.
5. Reusing the existing registration core must not weaken its outputRoot binding, journal gate, Bundle id, durability, or diagnostics.
6. A failed call does not publish `CURRENT`. Non-authoritative staged material follows existing registration cleanup rules.
7. The CLI never observes or supplies snapshot bytes.

The old snapshot-backed `register` method and its tests remain unchanged.

## 5. Digest-bound Apply algorithm

The CLI-side sequence is:

```text
parse exact apply arguments
-> inspectChangePlanningContext(candidate path)
-> require returned contextId == expected-context-id
-> require exactly one target matching target-key
-> build typed ChangeSet from returned receipt and opaque typed target
-> take candidateSha256Hex from this context result
-> construct ChangeApplyRequest.digestBound(...)
-> invoke ChangeExecutionApplication.apply
```

The Application-side sequence begins:

```text
VALIDATE REQUEST
-> ACQUIRE create-capable stateRoot lock
-> JOURNAL GATE
-> READ exact candidate bytes once
-> SHA-256 exact raw bytes
-> COMPARE expected digest
-> only then decode / recompile / plan / protect / transact
```

On mismatch, return one primary `SIR-APP-CHANGE-BIND-009` diagnostic. The diagnostic may identify the request field but must not print candidate contents. It must be emitted before:

- UTF-8 decoding;
- base or candidate compilation;
- `ChangePlanner` invocation;
- PROTECT;
- creation of `transactions/<id>`;
- staging or backup;
- Bundle publication;
- output changes.

The legacy five-argument request follows its existing behavior for Java callers. No command-line flag exposes `expectedCandidateSirSha256Hex`.

## 6. CLI parser contract

### 6.1 Command set

```text
kcg context ...     # unchanged
kcg plan ...        # unchanged
kcg generate ...
kcg register ...
kcg apply ...
kcg recover ...
```

No aliases, abbreviations, combined lifecycle command, interactive wizard, or implicit default exists.

### 6.2 Exact syntax

```text
kcg context --state-root ABS --output-root ABS --candidate-sir ABS

kcg plan --state-root ABS --output-root ABS --candidate-sir ABS \
  --expected-context-id SHA256 --target-key SHA256 \
  --change-ir-version VERSION --operation TOKEN

kcg generate --source-sir ABS --source-id SOURCE_ID \
  --output-root ABS --conflict-policy fail-if-exists

kcg register --base-sir ABS --source-id SOURCE_ID \
  --output-root ABS --state-root ABS

kcg apply --state-root ABS --output-root ABS --candidate-sir ABS \
  --expected-context-id SHA256 --target-key SHA256 \
  --change-ir-version VERSION --operation TOKEN

kcg recover --state-root ABS --output-root ABS \
  --transaction any|TX_ID
```

Backslash wrapping above is illustrative only; no shell continuation semantics belong to the CLI.

For `plan` and `apply`, `VERSION` is exactly one of `V0_1` through `V0_6`, subject to the existing operation/version compatibility matrix. `TOKEN` is exactly one of:

```text
modify-capability-workflow
add-capability
remove-capability
modify-input-field-constraints
modify-unreferenced-input-field-type
modify-actorless-readonly-capability-exposure
```

### 6.3 Argument validation order

For deterministic single-diagnostic behavior:

1. command token;
2. unknown or positional token;
3. duplicate option in lexical input order;
4. missing required options in the command's documented order;
5. empty values;
6. lexical value format;
7. absolute path requirement;
8. cross-field path relationships;
9. typed request construction.

Application diagnostics begin only after CLI validation succeeds. A single root cause yields one primary diagnostic; subordinate diagnostics may be included only when the existing Application result already defines them.

### 6.4 Path rules

- Every file/root path must be absolute in caller text.
- The CLI uses `Path.of(raw)` and absolute/normalized comparison only as required for typed construction; it does not access the file system to canonicalize paths.
- No implicit `cwd`, user home, drive-relative path, UNC reinterpretation, or environment expansion.
- The Application revalidates raw and normalized chains with `NOFOLLOW_LINKS`, symlink/reparse protection, containment, type, identity, and FileStore rules.
- `stateRoot` and `outputRoot` must be distinct and neither may contain the other.
- Source files must not be inside Application-owned `stateRoot` transaction or Bundle trees.

### 6.5 Environment and streams

Product commands read no environment variables. JVM/runtime environment necessarily exists but must not influence semantic output, target choice, paths, versions, Locale-sensitive ordering, or diagnostics.

stdout contains one canonical JSON record and LF. stderr remains empty for all structured outcomes. Unexpected launcher errors use stderr only after redaction and exit 70. stdin is never read.

## 7. JSON schemas

All lifecycle records begin with:

```json
{"protocol":"KCG-CLI-LOCAL-MVP-V1","command":"...","outcome":"..."}
```

The actual output is one line. Examples below are structural and do not prescribe sample identity values.

### 7.1 Generate success

```json
{
  "protocol": "KCG-CLI-LOCAL-MVP-V1",
  "command": "generate",
  "outcome": "GENERATED",
  "sourceId": "...",
  "outputRoot": "...",
  "targetProfile": "spring-boot-java21-mybatis-plus-mysql-rest-v0.2",
  "loweredIrVersion": "V0_2",
  "graphVersion": "V0_1",
  "graphCanonicalDigest": "...",
  "files": [
    {
      "relativePath": "...",
      "artifactId": "...",
      "ownerSymbolId": "...",
      "byteCount": 0,
      "sha256": "..."
    }
  ],
  "diagnostics": []
}
```

Files are ordered by relativePath using the project's canonical comparator. IDs are rendered as opaque values supplied by typed APIs, never parsed for behavior.

### 7.2 Registration outcomes

`REGISTERED` and `ALREADY_REGISTERED` include a canonical `baseline` object with baselineId, sourceId, base source digest, GraphVersion, canonical graph digest, Snapshot format, normalized outputRoot, Target Profile, Lowered IR version, and manifest digest/count fields available from the existing receipt. `RECOVERY_REQUIRED` includes only the stable handle and diagnostics necessary to call Recovery. `FAILURE` includes deterministic diagnostics and no invented receipt.

### 7.3 Apply outcomes

`APPLIED` includes `applyOutcome` (`FILES_AND_BASELINE` or `BASELINE_ONLY`), previous baselineId, and the authoritative new receipt. `NO_CHANGES` includes the still-current receipt. `FAILURE` includes `effect` (`NO_CHANGES` or `ROLLED_BACK`) and diagnostics. `RECOVERY_REQUIRED` includes the recovery handle and does not claim the effective baseline.

The response must not include candidate source, generated file bytes, staging/backup locations, secrets, Java stack traces, or full process environments.

### 7.4 Recovery outcomes

`RECOVERED` means forward verification/cleanup completed with current B1. `ROLLED_BACK` means backward recovery completed with current B0. `FAILURE` represents a proven non-ambiguous failure whose current state is still known. `RECOVERY_REQUIRED` means proof remains insufficient or recovery was blocked. Result field meanings map directly from the current sealed Application result; the CLI does not invent direction from files.

### 7.5 Failure JSON

Usage failures use the existing Stage F usage protocol conventions and exit 2. Domain failures use a sorted diagnostics array containing stable code, stage/domain, severity, and sanitized message fields already exposed by the API. Ordering is code, then stable typed location/path, then message only where the current API requires it. Locale, absolute temporary root, clock, PID, random transaction IDs, and hash-map iteration must not reorder diagnostics.

## 8. State and operation table

| Command | Lock mode | Journal gate | Reads | Writes | Forbidden direct access |
|---|---|---|---|---|---|
| generate | none in baseline system | n/a | source SIR | outputRoot through FileTransaction | stateRoot/CURRENT/Bundle/journal |
| register | existing ADR create-capable exclusive | required | base SIR, full generated output | LOCK, B0 Bundle, CURRENT | CLI access to all state internals |
| context | existing-only exclusive | required | CURRENT Bundle, candidate | none | creating missing LOCK |
| plan | existing-only exclusive | required | CURRENT Bundle, candidate, output protection | none | creating missing LOCK or using old plan |
| apply | create-capable exclusive | required | CURRENT Bundle, candidate, output | transaction, planned output, B1 Bundle, CURRENT | external bytes/plan/protect evidence |
| recover | create-capable exclusive | Recovery interprets gate | CURRENT, Bundle, journal, proven output | proven rollback/cleanup/state | guessing ownership/direction |

Registration, planning, Apply, and Recovery all bind exact normalized absolute outputRoot to stateRoot. A context/plan operation never repairs a missing `LOCK`; it fails closed with exact-tree zero persistent writes. Lifecycle write commands retain the current create-capable lock contract.

## 9. Physical and semantic protection

1. Generation retains `PREFLIGHT -> GRAPH -> WRITE` and existing conflict handling.
2. Registration verifies every manifest entry and does not reject unrelated untracked files merely for being present.
3. Apply rebuilds base and candidate from CURRENT Bundle plus exact candidate bytes; reconstructs the graph; invokes the current ChangePlanner; executes current PROTECT; and binds plan entries one-to-one to current generated bytes.
4. UPDATE, CREATE, and DELETE remain mutually exclusive.
5. CREATE remains no-clobber and only `NoSuchFileException` proves absence.
6. DELETE retains all NOFOLLOW, hard-link identity, backup, and absence proofs.
7. Any tracked output drift before transaction fails closed. Untracked user files remain unread and untouched except when their exact path blocks a planned CREATE, which is an existing conflict.
8. A plan, CLI JSON, receipt, digest, or graph summary alone never authorizes a write.

## 10. Product outcome mapping

| Application result | CLI outcome | Exit |
|---|---|---:|
| generation success | GENERATED | 0 |
| registration success/idempotent | REGISTERED/ALREADY_REGISTERED | 0 |
| planning context or plan success | unchanged Stage F outcome | 0 |
| Apply Applied/NoChanges | APPLIED/NO_CHANGES | 0 |
| Apply Failure | FAILURE | 3 |
| Apply RecoveryRequired | RECOVERY_REQUIRED | 4 |
| Recovery completed backward/forward | ROLLED_BACK/RECOVERED | 0 |
| Recovery still required | RECOVERY_REQUIRED | 4 |
| any domain validation/protection rejection | FAILURE | 3 |
| CLI usage rejection | USAGE_ERROR | 2 |
| unrepresentable internal crash | no JSON guarantee; sanitized stderr | 70 |

## 11. Implementation slicing

### 11.1 M1: Application registration and candidate binding

Allowed:

- `sir-toolchain-application/src/main/java/io/kcg/sir/application/api/**` only for the new request, compatible Apply request evolution, and public method;
- `sir-toolchain-application/src/main/java/io/kcg/sir/application/internal/**` only to reuse registration core and insert the digest gate;
- matching `sir-toolchain-application/src/test/**`.

Forbidden:

- Parser, Semantic, Lowering, Generator, PSG, Snapshot implementation, `sir-change`;
- Bundle/descriptor/journal codecs or format versions;
- transaction algorithms and Recovery direction;
- old API removal or behavior changes.

Minimum tests:

1. generated-baseline registration produces the same canonical B0 identity and Bundle bytes as snapshot-backed registration for identical input;
2. exact source bytes, graph, Target versions, manifest, and outputRoot are bound;
3. source changes during/after read cannot mix source digest and compilation;
4. output drift, wrong target/profile, lock contention, and unfinished journal fail closed;
5. digest-bound Apply happy paths for UPDATE/CREATE/DELETE;
6. candidate replaced after context with another valid same-target SIR fails `BIND-009` before Planner/PROTECT;
7. invalid UTF-8 with digest mismatch still returns binding mismatch before decode;
8. no transaction/state/output delta on mismatch;
9. legacy request constructors and current test corpus remain unchanged.

No real Spring/MySQL is required. Gate: targeted tests, full application module regression, then complete Reactor because a public Application API changes.

### 11.2 M2: `generate` and `register`

Allowed:

- `kcg-cli/src/main/**` and `kcg-cli/src/test/**`;
- `kcg-cli/pom.xml` only if existing module dependency/test launching requires it.

Forbidden:

- all other production modules;
- root distribution/plugin/profile changes;
- modifications to accepted `context`/`plan` semantics or golden output.

Minimum tests:

1. exact parser grammar for both commands;
2. every missing/duplicate/unknown/relative-path/env/default rejection;
3. real `ToolchainApplication` generation with fail-if-exists;
4. real generated-baseline registration and idempotent repeat;
5. generation failure leaves no partial output under existing guarantees;
6. registration does not touch output or source and never writes a snapshot beside caller files;
7. canonical JSON golden tests under multiple Locale settings;
8. `context`/`plan` byte-golden regression.

No Spring/MySQL is required. Gate: targeted tests, full `kcg-cli` module regression, and focused application integration tests. Full Reactor is required once at slice completion because the public CLI surface changes.

### 11.3 M3: `apply` and `recover`

Allowed:

- `kcg-cli/src/main/**` and `kcg-cli/src/test/**`;
- minimal module POM test wiring only where unavoidable.

Minimum tests use real Application calls and real temporary files, not source-text assertions or mock-only coverage:

1. UPDATE, CREATE, DELETE pure-family success;
2. BASELINE_ONLY and true NoChanges;
3. exact effect/result/exit mapping;
4. stale contextId and unknown/duplicate target-key;
5. candidate swap between fresh context and Apply;
6. external tracked-output modification;
7. lock contention using a separate JVM;
8. RecoveryRequired response and explicit exact/any Recovery;
9. repeated Recovery idempotence;
10. CLI never reads or writes Bundle/CURRENT/journal directly;
11. old `context`/`plan` golden output unchanged.

No Spring/MySQL is required. Gate: targeted tests, `kcg-cli` regression, application transaction/recovery regression, and one full Reactor at slice completion.

### 11.4 M4: real local MVP conformance

Allowed test surfaces:

- `kcg-cli/src/test/java/**/mvp/**`;
- `kcg-cli/src/test/resources/mvp/**`;
- a narrowly scoped test-only bridge under `sir-toolchain-application/src/test/**` only for accepted crash hooks that production CLI cannot expose;
- minimal test-scope POM wiring.

Production source changes are forbidden. Accepted Stage E conformance code and C/D/E acceptance drivers may be invoked or used as design precedent but must not be replaced, rewritten, or made a public protocol.

The IT class is opt-in and excluded from default Surefire using the repository's existing `*IT` convention. It must launch the real CLI boundary in child processes rather than calling command handlers directly.

Gate: one full new run plus a second run with distinct schema/work/evidence roots and ports. The normalized evidence must match after replacing only schema name, run-specific paths, ports, and root-bound baseline IDs.

## 12. Real environment fixture

The fixture uses:

- exact JDK 21 vendor/version recorded at runtime;
- exact Maven version;
- OS name/version/architecture;
- Spring Boot Target Profile and Lowered IR version from current output;
- MySQL server UUID/version and connector/runtime versions as in ADR-017;
- harness JDBC version recorded separately;
- fresh run-specific absolute source, outputRoot, stateRoot, workRoot, evidenceRoot, schema, runtime account, and ports.

Work/evidence/schema ownership, advisory locking, SchemaName construction, credential separation, log redaction, evidence scanning, process cleanup, and terminal cleanup semantics reuse ADR-017 exactly. The harness must not delete or adopt historical or caller-owned material.

`NOT_RUN` is allowed only if a declared prerequisite is absent before:

- `generate`;
- `register`;
- any Apply/Recovery;
- Spring/Maven child-process start;
- schema/account creation or mutation.

Once any of those side effects begins, every unsuccessful outcome is `FAILED`.

## 13. MVP scenario matrix

### 13.1 Initial B0

1. Run `kcg generate` on the initial `campus-market` SIR.
2. Assert only the expected generated manifest exists; no stateRoot exists due to generation.
3. Run `kcg register`.
4. Assert `CURRENT=B0`, Bundle manifest matches output byte-for-byte, and no journal remains.
5. Repeat register and assert idempotent outcome and exact state/output snapshots unchanged.

### 13.2 UPDATE

1. Run `context` against the established Input constraint candidate.
2. Select the exact target-key and run `plan`; require pure UPDATE.
3. Snapshot every tracked file and unrelated sentinel.
4. Run `apply`; require only planned file bytes change and `CURRENT=B1`.
5. Run Maven verification.
6. Start Spring using the accepted local-fixed actor transport configuration.
7. A known PublishGoods request returns success and exact MySQL row values.
8. Invalid Input returns 400 and the before/after database state proves zero write.
9. Stop Spring and prove process/port cleanup.
10. Run fresh `context`; require a different root-bound context and B1 receipt.
11. Run the same candidate through plan/apply and require true NoChanges.

### 13.3 CREATE

1. Use the established AddCapability candidate adding actorless readonly SearchGoods.
2. Require pure CREATE plan containing exactly Service and Controller additions.
3. Apply and require B0/B1 delta equals those additions; no overwrite.
4. Maven succeeds; known SearchGoods GET returns expected seeded/published MySQL data.
5. PublishGoods remains successful, including validation behavior.

### 13.4 DELETE

1. From the CREATE baseline, use the established RemoveCapability candidate removing PublishGoods.
2. Require pure DELETE plan containing exactly its Service and Controller.
3. Apply and require only those tracked files disappear and the survivor manifest is exact.
4. Maven succeeds; SearchGoods remains available and reads expected data.
5. The removed PublishGoods route is unavailable. A 404 alone is not Context evidence; Context was already proved by the surviving known endpoint.

### 13.5 Recovery

1. A test-only accepted crash hook triggers one durable UPDATE transaction window after a real CLI-driven plan context is formed.
2. The Apply process terminates without cleanup, leaving a valid Recovery handle.
3. A new real CLI process runs `recover`.
4. `CURRENT` selects the existing unique backward/forward direction; output and manifest match the selected baseline.
5. A second new CLI process repeats Recovery and proves idempotence.
6. No automatic Recovery path is exercised or added.

### 13.6 Adversarial gates

Independent cases prove:

- another JVM holds `stateRoot/LOCK`;
- candidate is replaced after the context used by the CLI but before Apply reads it;
- a tracked output file is externally modified before Apply;
- CREATE target appears concurrently;
- DELETE/UPDATE target identity or bytes drift;
- unfinished journal blocks registration/context/plan/apply;
- invalid or mismatched outputRoot is rejected.

Each case asserts canonical failure, exit, zero unproven mutation, and sentinel preservation.

## 14. Evidence layout and proof

The acceptance evidence is test-internal, sanitized, and not a public protocol. At minimum it records:

- reference-environment tuple;
- exact CLI protocol versions and canonical stdout JSON digests;
- each process exit code and accurate test/assertion counts;
- B0/B1/B2/B3 baseline receipts with run-root IDs normalized only for cross-run comparison;
- before/after tracked manifests and sentinel proofs;
- Maven exit and generated POM-derived Target dependency versions;
- Spring known-endpoint readiness and exit/port cleanup;
- HTTP status/response assertions;
- parameterized MySQL assertions and zero-write proof;
- Recovery direction and second-run idempotence;
- schema/work/evidence ownership and cleanup proofs;
- final credential scan.

No raw credentials, JDBC URLs with user-info, full commands, full environments, unredacted logs, candidate source text, or generated file contents are evidence fields.

## 15. Acceptance commands and test triggering

The implementation specification intentionally does not freeze repository commands that do not yet exist. Each execution slice must use current module names and offline repository conventions at implementation time and report the exact commands and exits.

Required levels are:

1. targeted unit/integration tests during each slice;
2. one default module regression when the slice stabilizes;
3. one complete Reactor after each public API/CLI slice, not after every edit;
4. opt-in external MVP IT only for M4 or when production Target/POM/transport/runtime behavior changes.

Pure CLI parsing or JSON-rendering changes do not mechanically require MySQL. Application transaction changes require transaction and crash-window regressions but not Stage E unless generated/runtime behavior changes. M4 always requires Maven, Spring, HTTP, and MySQL.

Windows-condition skips must be listed accurately and may not conceal a required lifecycle assertion. A transient Windows temporary-file or atomic-move failure may be verified by rerunning the exact same command once; repeated failure is a failure, not a skip.

## 16. Terminal classification

### MVP_FEASIBLE

All lifecycle, adversarial, runtime, database, Recovery, cleanup, redaction, and deterministic comparison requirements pass twice on fresh resources. This states only that the frozen local MVP is viable for the recorded tuple.

### FAILED

Any required assertion fails after the side-effect boundary, including:

- unexpected CLI/API result or exit;
- partial or extra file change;
- incorrect baseline transition;
- failure to fail closed under drift or contention;
- Recovery non-idempotence or ambiguity;
- Maven, Context, HTTP, validation, or MySQL assertion failure;
- process, schema, account, workRoot, or evidence cleanup failure;
- credential/evidence leakage;
- non-deterministic normalized evidence.

An implementation defect remains `FAILED` even if a manual workaround succeeds.

### NOT_RUN

Only a declared external reference-environment prerequisite failed before the first side effect. It is not success, acceptance, or a product result. Existing residue, ownership conflict, unavailable advisory lock, unavailable JDK/Maven/MySQL, or reserved port may qualify only at that pre-side-effect point.

## 17. Non-goals and stop conditions

The following stop implementation and require architecture review rather than a local workaround:

- the CLI would need direct Bundle/CURRENT/journal/snapshot writes;
- the existing transaction or Recovery direction must change;
- registration cannot atomically bind one exact source compilation to output and Bundle;
- Apply cannot compare candidate digest before semantic work;
- `context` or `plan` public bytes/zero-write contract would change;
- a new Change IR version/operation or mixed plan family is required;
- generated project behavior, POM, Target Profile, Lowered IR, PSG, or Snapshot must change;
- a supported public evidence/report format, installer, daemon, remote API, multi-user state, or second Target is requested;
- user-owned or mixed generated regions become necessary.

These conditions require a new or amended ADR. Execution Agents must not expand the slice to solve them.

## 18. Completion checklist

Before any claim of completion:

- [ ] ADR-019 and this specification are accepted by the architecture main Agent.
- [ ] M1 is independently accepted.
- [ ] M2 is independently accepted with existing `context`/`plan` unchanged.
- [ ] M3 is independently accepted for UPDATE/CREATE/DELETE and Recovery.
- [ ] M4 runs twice on fresh owned resources.
- [ ] Every exact exit code and accurate test count is recorded.
- [ ] Complete Reactor is green at the final code state.
- [ ] The reference-environment tuple and evidence boundaries are explicit.
- [ ] No credential, user file, historical root, or external schema was touched.
- [ ] No Git snapshot is created without separate user authorization.

Until every applicable item is satisfied, the only accurate statement is that the MVP design or implementation remains incomplete.
