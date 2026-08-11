# Stage E P0-3 Credential / Evidence Breach Hard Gate 最终设计

状态：Implemented and accepted（2026-07-30）

> 完成记录：`CredentialBreachHandlingTest`、`EvidenceDirectoryTest` 与
> `StrongFileIdentityTest` 的定向运行共 42 tests、0 failures、0 errors；随后默认
> 完整 Reactor 通过（`sir-toolchain-application` 1049 tests、0 failures、0 errors、
> 20 个既有 Windows 条件 skip）。Windows 强文件身份改用进程内 `FileIdInfo` 读取；
> 无法取得稳定身份时保持 fail-closed，不再启动外部 `fsutil` 子进程。两次全新的
> opt-in 外部资格运行均为 `QUALIFIED`，最终 sanitized evidence 均通过 exact-tree/
> secret-scan hard gate，且在允许的 run-specific 字段规范化后结构一致。该结论只绑定
> 已记录的本地参考环境，不泛化为生产或所有环境资格。

> 完成记录：`CredentialBreachHandlingTest`、`EvidenceDirectoryTest` 与
> `StrongFileIdentityTest` 的定向运行共 42 tests、0 failures、0 errors；随后默认
> 完整 Reactor 通过（`sir-toolchain-application` 1049 tests、0 failures、0 errors、
> 20 个既有 Windows 条件 skip）。Windows 强文件身份改用进程内 `FileIdInfo` 读取；
> 无法取得稳定身份时保持 fail-closed，不再启动外部 `fsutil` 子进程。两次全新的
> opt-in 外部资格运行均为 `QUALIFIED`，最终 sanitized evidence 均通过 exact-tree/
> secret-scan hard gate，且在允许的 run-specific 字段规范化后结构一致。该结论只绑定
> 已记录的本地参考环境，不泛化为生产或所有环境资格。
范围：仅 Stage E test-only conformance harness 的 credential/evidence hard gate
实现位置：仅 `sir-toolchain-application/src/test/java/io/kcg/sir/application/conformance/**` 与必要的 `sir-toolchain-application/src/test/resources/conformance/**`

## 1. 结论

P0-3 采用唯一方案：**创建身份令牌 + 关闭后最终身份 + evidence root 强身份 + 未封印报告（unsealed report）+ 发布后全树扫描封印**。

所有 evidence 文件都经一个统一的 owned-file 生命周期写入。流文件在 `CREATE_NEW` 后只登记为 pending creation token；只有 redactor/delegate 成功 flush、close，且 root、raw/normalized path chain 与文件强身份重证通过后，才进入 finalized inventory。pending token 不是“最终 inventory entry”，不得被当作 clean 或 qualified evidence；它只授权在 credential breach 中删除仍与本次创建身份相同的文件。不得继续使用 size/mtime 作为身份替代，也不得吞掉 close、finalization 或 inventory registration 异常。

最终报告先作为本次运行拥有的临时文件写入并 force，再以 no-replace atomic move 发布为 `REPORT_UNSEALED`；只有发布后的最终 scanner 覆盖包括该报告在内的完整、精确、已拥有树并返回 clean，报告才转为 `REPORT_SEALED`，运行才可能进入 `QUALIFIED`。credential-breach 的 minimal report 也遵守同一流程。

本设计不改变 ADR-017、Stage E 场景语义、Apply、Target 或任何生产代码。

## 2. 当前代码事实与三条根因

### A. streaming identity 仍未形成失败闭环

`EvidenceWriter.openStream()` 当前已尝试把 `inventory.register` 移至 wrapper 的 `close()`，但实现仍在 `finally` 中注册：delegate flush/close 失败时也可能记录不完整文件；注册异常被吞掉后，会留下 persistent evidenceRoot 中存在但 inventory 不认识的文件。现有 fallback identity 是 size + lastModifiedTime，不是对象身份；它既不能证明文件未被替换，也不能作为安全删除授权。

因此，问题不是简单地“把 register 从 open 移到 close”，而是必须冻结 pending creation 与 finalized identity 两个不同事实，并让关闭或最终登记失败进入 hard failure。

### B. evidenceRoot 没有创建时强身份，也没有完整链路复验

`EvidenceDirectory.create()` 只在创建后读取一次 NOFOLLOW 类型；`EvidenceOwnershipInventory` 只保存 normalized root Path。`proveParentChain()` 虽重新检查 root 是目录，却没有与创建时 root identity 比较，也没有逐级比较创建时 raw/normalized parent-chain identity。当前 Windows reparse 检查还会把读取失败、权限失败和不支持混在一起返回 false，形成 fail-open。

因此 root 被真实目录替换、symlink/junction/reparse 替换或父链身份漂移后，scanner、删除或 report publish 可能从已失效的 root Path 继续工作。

### C. report 还不是可撤回的最终扫描对象

当前主路径已在 publish 后调用 scanner，但 `publishReport()` 在 move 后立即把报告登记为 published / non-deletable。若发布后扫描发现报告含 secret，breach handler 无权删除它。报告的 temp create、move 及最终 scan 也未由同一 root/path identity protocol 包围。

因此“发布后调用了 scanner”不等于“只有 clean report 才能成为 persistent final evidence”。

## 3. 唯一实现契约

### 3.1 强身份与状态模型

`EvidenceDirectory.create` 在运行专属 root 经直接 `readAttributes(..., NOFOLLOW_LINKS)` 抛出 `NoSuchFileException` 证明缺失并单级创建后，必须保存不可变 `EvidenceRootProof`：

- caller parent、raw absolute root、normalized absolute root；
- raw 与 normalized 路径链中每个既存目录的 absolute Path、provider/file-store 关联、NOFOLLOW directory type 与非空 `fileKey`；
- root 自身的 NOFOLLOW directory type、非空 `fileKey`；
- Windows/DOS provider 上明确读取且为 false 的 `dos:reparsePoint`；非 DOS provider 才允许“不适用”，属性读取错误、权限错误或 SecurityException 一律不是“false”。

Reference environment 若不能提供可重复比较的非空 `fileKey`，P0-3 证明不成立；不得退回 size、mtime、digest、路径相等或 marker 内容。root 已创建后才发现该能力缺失时，结果为 `FAILED(HARNESS_CREDENTIAL_BOUNDARY)`，不是 `NOT_RUN`。

文件使用两类记录：

1. `PendingCreation`：严格路径、CREATE_NEW 后的 NOFOLLOW regular-file type、非空 fileKey、root proof generation；只证明“本次运行创建过这个文件对象”，不证明最终内容。
2. `FinalizedEntry`：成功 flush/close 后，在相同 fileKey 上捕获的 byteCount 与 SHA-256，并记录用途 `EVIDENCE`、`REPORT_TEMP`、`REPORT_UNSEALED` 或 `REPORT_SEALED`。size/mtime 可记录为诊断外的辅助字段，但不得参与或替代强身份授权。

允许的单向转换为：

```text
ABSENT_PROVED
  -> PENDING_CREATED
  -> FINALIZED_EVIDENCE

ABSENT_PROVED
  -> PENDING_CREATED(REPORT_TEMP)
  -> FINALIZED_REPORT_TEMP
  -> REPORT_UNSEALED
  -> REPORT_SEALED

PENDING_CREATED | FINALIZED_* | REPORT_UNSEALED
  -> DELETE_INTENT
  -> ABSENT_PROVED_AFTER_DELETE
```

任何失败转换都保留原状态和 stable failure，不允许伪造后继状态。`REPORT_SEALED` 不可由 breach path 删除；但它只有在 scanner 已证明 clean 后才存在。一个 run 不允许覆盖、复用或第二次发布同名报告。

### 3.2 streaming close/finalize

`openStream(relativePath)` 必须：

1. 严格解析 relative path：非 absolute、非空、无 `.`/`..`、无空组件；raw resolve 与 normalized resolve 都必须位于各自 root 下并指向同一 lexical target。
2. 在创建任何父目录、证明 target 缺失以及 CREATE_NEW 前后，重证 root 与完整 raw/normalized chain。
3. target 缺失只由一次直接 NOFOLLOW `readAttributes` 抛出的 `NoSuchFileException` 证明；其他异常 fail-closed。
4. CREATE_NEW 后立即读取 NOFOLLOW regular-file type、非 reparse 与非空 fileKey，保存 `PendingCreation`；此时不得创建 finalized inventory entry。
5. 返回的 redactor wrapper 在 `close()` 中严格执行：flush redactor → close delegate → root/chain proof → NOFOLLOW open/read → creation fileKey equality → 完整 SHA-256/byteCount → 再次 root/chain/fileKey proof → finalize entry。
6. flush、close、proof、hash 或 finalize 任一步失败均必须向调用方传播非 secret-bearing 的结构化失败，并触发 `FAILED(HARNESS_CREDENTIAL_BOUNDARY)`；不得在 `finally` 中假定成功，不得吞异常。

同一原语也必须用于 `writeBytes` 和 report temp；不得保留三套不同的创建/登记语义。

若 close/finalize 失败，pending file 可能含 secret。breach handler 只有在 root/chain 仍成立、target 仍为 NOFOLLOW regular non-reparse 且 fileKey 等于 `PendingCreation.fileKey` 时才可删除；内容变化不使同一创建对象失去删除授权。若 fileKey 不同、对象未知或证明异常，则绝不删除并以 hard failure 停止。

### 3.3 root、raw/normalized chain 与 scanner

以下每个操作的前后都必须调用同一个 `EvidenceRootProof.reprove()`，而不是各自实现近似检查：scanner 遍历、dirty discovery、breach deletion、report temp create/write/finalize、atomic publish、publish 后 final scan。

重证规则：

- root 自身必须与创建记录保持相同非空 fileKey、NOFOLLOW directory type、非 symlink、非 other、非 reparse；
- raw 与 normalized parent chain 从创建时可信 anchor 到目标 parent 逐组件重读，目录类型和 fileKey 必须与创建/首次受控创建记录相同；
- 本次运行创建的 evidence 子目录必须在创建后加入链路记录；未知目录或身份漂移不是可接管对象；
- Windows junction/reparse 必须显式拒绝；SecurityException、权限错误、I/O、未知 provider/type 全部 fail-closed；
- scanner 不得依赖一次 `Files.walk(root)` 后继续信任路径。它按 canonical relative path 排序、NOFOLLOW 枚举，每个目录和文件在打开前后重证；遍历前后再次重证 root；
- 最终树的 path set 必须恰好等于 finalized inventory 加一个可选的 `REPORT_UNSEALED`。missing、unknown、pending、symlink、junction、reparse、special、identity drift 或扫描 I/O 均是不 clean，不得忽略。

scanner 与 dirty discovery 返回给内部逻辑的是 deterministic opaque handles；任何报告、stdout/stderr、异常或测试失败消息不得打印 dirty filename、relative path、secret、JDBC URL、command 或 environment。

### 3.4 report 原子发布与封印

`publishReport` 必须只接受已在内存生成且经 allowlist renderer 输出的 bytes，并执行：

```text
root/chain proof
-> temp absence proof
-> CREATE_NEW temp as PendingCreation
-> sanitized bytes write/flush/close
-> temp finalized identity + force file
-> root/chain proof + final target absence proof
-> same-file-store no-replace ATOMIC_MOVE
-> temp direct NOFOLLOW absence proof
-> final NOFOLLOW regular/non-reparse + same fileKey/digest proof
-> REPORT_UNSEALED
-> full exact-tree final scan (including report)
-> root/chain proof
-> REPORT_SEALED
```

ATOMIC_MOVE 不得带 `REPLACE_EXISTING`。不支持 atomic move、target 并发出现或任何 proof 失败均 fail-closed。final scan 发现 report dirty 时，`REPORT_UNSEALED` 仍可按同一 owned deletion contract 删除并做直接 NOFOLLOW absence proof；不得提前标为 published/non-deletable。

正常 clean path 也必须执行发布后 final scan，且只有 `REPORT_SEALED`、workRoot/schema/process cleanup 全部成功时才可 `QUALIFIED`。

## 4. Credential breach 唯一状态机

检测到 secret 或 evidence proof/scan 不确定性后，立即把 primary terminal category 固定为 `HARNESS_CREDENTIAL_BOUNDARY`，使用一个稳定、不含动态文本的 message key。后续错误不得把它改成 `NOT_RUN`、`QUALIFIED` 或其他 primary kind。

```text
DETECT_OR_PROOF_FAILURE
-> RECORD_STABLE_CREDENTIAL_BOUNDARY
-> DISCOVER_DIRTY_OPAQUE_HANDLES
-> PROVE_ROOT_AND_COMPLETE_CHAINS
-> PROVE_OWNED_DIRTY_OBJECTS
-> DELETE_ONLY_PROVED_OWNED_DIRTY_OBJECTS
-> DIRECT_NOFOLLOW_ABSENCE_PROOF_EACH
-> EXACT_TREE_RESCAN
-> BUILD_FIXED_MINIMAL_REPORT
-> ATOMIC_PUBLISH_AS_REPORT_UNSEALED
-> FINAL_EXACT_TREE_SCAN_INCLUDING_REPORT
-> SEAL_MINIMAL_REPORT
-> FAILED(HARNESS_CREDENTIAL_BOUNDARY)
```

若初始 scan 只是抛出 I/O/proof 错误而无法安全识别 dirty 对象，不得猜测或删除；直接进入 FAILED，且不声称发布了 sanitized evidence。

若 dirty 集合包含 unknown、sealed、identity drift、symlink/junction/reparse/special，或 root/chain/deletion/absence/re-scan/publish/final scan 任一步失败：

- 结果仍是 `FAILED(HARNESS_CREDENTIAL_BOUNDARY)`；
- 不得发布或保留一个声称“sanitized/complete”的 report；若 unsealed report 已产生，只能在其 ownership proof 成立时删除并证明缺失；
- 不得删除未知文件、caller parent、历史 evidence、外部 sentinel 或任何未由本次 run 创建且持续追踪的对象；
- 不得把异常文本、路径、文件名、secret 表示、JDBC URL、username/password、完整 command/environment 写入结果或 evidence；
- 若 root proof 已失效，禁止继续沿该 root 扫描、打开、移动或删除任何对象。

### 4.1 minimal failure report 精确格式

Credential breach 的最小报告必须是固定字段、固定顺序、UTF-8、LF 结尾的 canonical JSON；唯一允许字段为：

```json
{
  "conformanceContractRevision": "<harness-owned validated constant>",
  "result": "FAILED",
  "failureKind": "HARNESS_CREDENTIAL_BOUNDARY",
  "messageKey": "PERSISTENT_EVIDENCE_CREDENTIAL_BREACH",
  "evidenceDisposition": "SANITIZED_MINIMAL_FAILURE_REPORT"
}
```

上述值除 contract revision 外均为固定 literal；contract revision 必须来自 harness-owned、严格验证的常量，不得直接抄 caller/environment 文本。禁止 schemaName、run token、scenario outcomes、失败列表、数量、时间、server UUID、版本元组、baseline ID、路径、文件名、exception class/message/stack、SQL、JDBC URL、username、password、command、environment 或任意 caller text。

若最小报告无法完成 publish 后 final scan，不得声称 minimal failure evidence 已安全发布；能证明 owned 时删除 unsealed report，不能证明时保留对象但只返回 stable hard failure。

## 5. Secret 表示边界

Redactor 与 scanner 必须继续共享一个 `SecretCatalog`，并对下列表示逐一以真实 bytes 验证：

- control/runtime username 与 password 原文；
- UTF-8 percent-encoded form；
- JDBC user-info `user:password@` 及 encoded form；
- 完整 control/runtime JDBC URL；
- `password=`、`passwd=`、`pwd=`；
- Spring datasource 与 `KCG_CONF_*` username/password/JDBC URL config-key form。

测试与最终报告不得打印构造出的 representation。断言只能使用 opaque case id、clean/dirty 布尔值、stable failure kind/key 和 NOFOLLOW absence proof。

## 6. 最小真实行为测试矩阵

测试必须调用真实文件 API、`EvidenceWriter.openStream()`、scanner 和 `ConformanceSuite` breach path；禁止用 source-text inspection 证明行为，mock/fake 只能补充 fault timing，不能替代真实文件副作用与 NOFOLLOW proof。

1. `openStream` 写入 scanner 已知但 writer redactor 未知的 secret，成功 close 后调用真实 breach handler：文件能以最终 identity 删除，直接 NOFOLLOW absence proof成功，剩余树 re-scan clean，minimal report 发布后再扫 clean，终态严格 FAILED。
2. 分别覆盖 Maven-style stdout、Maven-style stderr、Spring-style stdout、Spring-style stderr 的 streamed path；至少跨多个 write/flush 边界，验证 close 后 byteCount/digest 对应最终 bytes。
3. flush/close/final identity registration 失败不得被吞；pending 文件只有 creation fileKey 匹配时可删，否则保留并 hard fail。
4. evidenceRoot 被替换为 symlink、Windows junction/reparse（平台能力不满足时用明确 conditional skip）、以及被替换为另一个真实目录造成 identity drift；外部 sentinel 始终存在且内容不变，任何操作不得穿越 replacement root。
5. dirty child 为 symlink/junction/reparse/special、dirty unknown file、owned file identity drift、parent identity drift、scanner 真实 I/O failure：均不误删、不发布完整安全证据、终态 FAILED。
6. scanner I/O failure 必须通过真实文件系统 race/权限/对象替换触发；package-private hook 可固定故障窗口，但不得只让 mock scanner 返回 false/throw 来代替实际 NOFOLLOW/read failure。
7. minimal failure report 在 publish hook 后注入 secret，必须由发布后 final scan发现；若 ownership 可证则删除 unsealed report并证明缺失，终态仍 FAILED。
8. clean full report 发布后必须由 final scanner 覆盖并转为 sealed，只有此时 clean run 才能 QUALIFIED；在 final scan 前观察到的 report 永远是 unsealed。
9. raw、encoded、user-info、config-key、username、password、完整 JDBC URL 每类都执行 leak → detect → owned delete → absence → re-scan；测试输出不包含 secret。
10. 对所有 failure injection 断言 `FAILED(HARNESS_CREDENTIAL_BOUNDARY)` 永不变成 NOT_RUN 或 QUALIFIED。
11. 未知文件、历史 evidence、caller parent、外部 sentinel 的 NOFOLLOW attributes 与 bytes 在 breach 前后相同。
12. canonical ordering、不同 Locale、重复执行使用全新 run-specific root 时结果形状一致。

不得以现有 `CredentialBreachHandlingTest` 的旧测试数或旧 BUILD SUCCESS 作为验收。本轮实现后必须报告实际测试数。

## 7. 实施与验收边界

### 7.1 允许修改

- `sir-toolchain-application/src/test/java/io/kcg/sir/application/conformance/**`
- 必要时 `sir-toolchain-application/src/test/resources/conformance/**`

### 7.2 禁止修改

- 所有 `src/main/**`；
- 根/模块 POM、插件、profile、Surefire/Failsafe；
- 既有 C/D/E Apply driver、`Change*`、`B0B1*` 及其 fixture/resource/assertion；
- `src/test/resources/valid/**`；
- 所有 docs；
- `.claude/**`、`.trae/**`、`.conformance-runs/**`、用户文件；
- MySQL server/data directory、历史 schema/workRoot/evidenceRoot；
- 任何 Git 写操作。

### 7.3 测试顺序

1. TDD 定向运行相关默认 `*Test`，直至 hard-gate 行为稳定。
2. 稳定后只运行一次完整 `sir-toolchain-application` 模块回归。
3. 再只运行一次完整 Reactor。
4. Windows 临时目录或 ATOMIC_MOVE 明确偶发错误，只允许原样重跑同一命令；不得改实现来掩盖，报告首次与重跑 exit code。
5. 之后运行两次全新的完整 opt-in external IT。每次必须使用新 SchemaName、新 work/evidence run root、新端口；不得复用旧 IT 结果。

每次 external IT 都必须独立证明：五场景全部满足矩阵、Maven exit 0、真实双 JDBC advisory-lock、schema 最终 INFORMATION_SCHEMA 精确缺失、workRoot cleanup、Spring process exit/业务端口关闭、runtime account cleanup、persistent evidence exact-tree final scan clean且 report sealed。任一 credential/evidence warning 禁止 QUALIFIED。

两次 evidence 结构化比较只允许规范化以下 run-specific 字段：SchemaName、run-specific work/evidence path、与 root 绑定的 B0/B1 baseline ID。其他字段必须一致；不得把 target runtime MySQL 依赖版本替换成 harness JDBC driver 版本。

### 7.4 完成门

全部门同时满足才算 P0-3 返工完成：

- 三条根因均有真实行为测试；
- 定向测试、模块回归、完整 Reactor 均真实 exit 0，并报告准确测试数/skip；
- 两次新 external IT 均真实 exit 0 且最终 `QUALIFIED`；
- 没有 credential、URL、完整 command/environment 出现在 stdout/stderr、异常、测试报告摘要或 persistent evidence；
- 只修改允许目录；
- 最终报告逐命令记录真实 exit code、准确测试数、运行终态；未完成时报告唯一 stable failure category，不引用旧轮成功证据。

本设计不授权 Stage F、生产 API、CLI、POM/插件修改或任何超出 P0-3 的重构。
