# KCG-Code 剩余重建工作单

> 日期：2026-08-11
> 写给谁：项目负责人，也就是以后需要决定“下一步让 Agent 做什么”的你
> 当前结论：能可靠找回的东西基本已经找完；剩余内容主要需要依据现有代码、历史行为和架构约束重新建设。

## 先说人话版结论

项目已经不是“空文件夹”了。现在有完整的九模块 Maven 工程，主源码能够编译，当前没有被排除的测试也能运行。换句话说，项目骨架、主要实现和不少关键行为都已经救回来了。

但它还不能叫“完整恢复”。主要原因不是源码完全缺失，而是我们无法证明部分反编译源码和灾前行为完全一致：一些旧测试、测试资源和外部资格测试框架没有完整原文，只能重新写。

以后不建议继续花大量时间搜索磁盘或期待找回原文件。更现实的做法是：把现在的版本保存为灾后基线，然后按照本工作单逐块补测试、补资源、重新确认行为。

## 现在已经保住了什么

- 九个 Maven 子模块和父工程都在。
- Parser、Semantic、Lowering、Generator、Project Graph、Change、Application 和 CLI 的主源码都能参与完整 Reactor 编译。
- 沙箱外执行标准离线命令能够成功：

  ```powershell
  mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
  ```

- 当前实际运行证据是 345 项通过、0 failure、0 error、10 skip。
- Parser 43 项、Semantic 103 项、Lowering 36 项已经形成相对可靠的恢复基线。
- Application 的路径、junction、文件事务和部分 DELETE Recovery 高风险路径已有新增回归测试。
- 灾难经过、恢复来源、缺失范围和验收边界都已经写入 `.memory/` 与 `docs/recovery/`。
- 当前版本可以作为一个“灾后不完整恢复快照”保存到 Git 和远端，避免再次全部丢失。

这里的关键词是“可以继续重建”，不是“已经恢复到灾前原样”。

## 为什么剩余内容只能重新建设

剩余缺口大致有三种：

1. 历史只留下了测试名称和测试数量，没有留下源码正文。
2. 历史任务日志输出有长度上限，部分大文件在固定位置被截断。
3. 某些 SIR fixture 只留下文件名或很短的预览，无法可靠推回原内容。

如果根据文件名硬猜原测试，写出来的测试很容易迎合当前反编译代码，反而会制造“看上去全绿”的假安全感。因此后续可以重写等价测试，但必须标明它是灾后新测试，不能伪称原件。

---

## 工作包 0：保住当前版本

### 目的

先把现在救回来的全部内容纳入 Git 并推送远端。以后即使继续重建时改坏了，也能够回到这个灾后基线。

### 当前状态

- 本地 Git 已初始化，分支为 `main`。
- 远端目标为 `git@github.com:HoloNova/Software-IR.git`。
- 远端已有一个只包含 `.gitignore`、`README.md` 和 MIT `LICENSE` 的初始化提交，需要正常合并，不能强推覆盖。

### 完成标准

- 当前源码、测试、文档和 `.memory` 全部进入版本控制。
- `target/`、本地 Maven 仓库、IDE 文件和受保护 Agent 目录不进入提交。
- 本地 `main` 通过普通 Git 历史衔接远端 `main`。
- 推送成功，远端 `main` 能看到灾后快照。
- 不使用 force push，不保存任何凭据。

### 状态

本工作单创建后立即执行。

---

## 工作包 1：先把权威文档之间的矛盾消掉

### 为什么优先做

现在最危险的不是少一条注释，而是未来 Agent 可能读到两套相反的 Recovery 方向，然后把正确代码“修”成错误代码。

### 已知冲突

重建上下文和旧工作计划写的是：

- `CURRENT=B0` 向前恢复；
- `CURRENT=B1` 向后验证或清理。

当前生产实现、RQ-10 新增测试和 2026-08-04 的 MVP 设计采用的是：

- `CURRENT=B0` 说明新基线还没发布，因此执行向后补偿，回到 B0；
- `CURRENT=B1` 说明新基线已经发布，因此只允许向前验证和清理。

从事务语义和较新的设计文档看，当前代码的方向更合理，但这件事必须通过架构复核正式确认，不能只靠直觉改文档。

### 要做的事

1. 对照当前 `ChangeDeleteRecoveryEngine`、CREATE/UPDATE RecoveryEngine、ADR-014/015/016、2026-08-04 MVP 设计和能够找到的历史补丁。
2. 写一份很短的决策记录，明确 B0/B1 的唯一合法方向和术语。
3. 同步修改 `AGENTS.md`、重建上下文、工作计划和相关注释。
4. 补一组跨 DELETE/CREATE/UPDATE 的方向测试，避免只验证 DELETE 的一部分。

### 完成标准

- 全仓搜索 `CURRENT=B0`、`CURRENT=B1` 时不再出现相反解释。
- 生产代码、测试、ADR 和项目记忆表达一致。
- 方向不明确时必须 fail closed，不能猜。

### 规模

中等，但风险最高，建议最先交给 Agent。

---

## 工作包 2：重建 Generator 测试

### 现在是什么情况

Generator 有 22 个主源码文件，目前只有 4 项测试；灾前记录是 47 项。现有测试只能证明几个重点回归，没有覆盖完整生成结果。

### 要重新确认的内容

- `pom.xml`、Application、Enum、Entity、Mapper、DTO、Exception、Service、Controller 是否都能正确生成。
- 相同 Lowered IR 是否每次产生完全相同的路径、顺序和字节。
- import 排序、大小写、Locale、换行和 UTF-8 是否稳定。
- Unit response、actor identity、复合 Find、Create/Update/Persist 等路径是否正确渲染。
- 生成出来的项目能否用 Java 21 和冻结依赖离线编译。
- Generator 是否仍然只消费 Lowered IR，不读取 AST、SIR 或磁盘。

### 推荐做法

1. 先从当前公开 API 构造最小 Lowered Model。
2. 为每种产物建立小而明确的断言，不要一开始就生成超大 golden 文件。
3. 再增加一个完整校园二手交易 fixture，检查完整文件集合和离线编译。
4. 新 golden/snapshot 必须写明“灾后新基线”，不要冒充灾前原文。

### 完成标准

- Generator 主体行为有系统测试，不再只有 4 项零散回归。
- 同输入重复运行和不同 Locale 下结果一致。
- 至少一个完整生成工程能够离线编译。
- 找不到的历史 47 项逐类用等价契约替代，而不是追求凑数字。

### 规模

中到大。

---

## 工作包 3：重建 Project Symbol Graph 测试

### 现在是什么情况

PSG 有 30 个主源码文件，当前独立测试数量是 0；灾前记录是 94 项。Application 的少量集成测试会间接经过 PSG，但不足以证明 PSG 自己的契约。

### 要重新确认的内容

- DECLARES、LOWERS_TO、OWNS_ARTIFACT、GENERATES_FILE 四类边。
- 节点和边的稳定 ID、顺序、重复拒绝和确定性。
- 图及所有公开集合不可变。
- Graph builder 不访问文件系统，不重新按名字解析符号。
- canonical serialization/digest 在相同输入下稳定。
- Application 的 Graph failure 必须发生在写盘前并返回 `NO_CHANGES`。
- 不得趁机实现 `REFERENCES`、Graph persistence 或 Snapshot V2。

### 完成标准

- PSG 不再是零测试模块。
- 四类边、重复身份、不可变性、确定性和 canonical digest 都有直接测试。
- 至少有一条 Parser → Semantic → Lowering → Generator → PSG 的真实集成链。

### 规模

大，但边界清楚，适合单独交给一个 Agent。

---

## 工作包 4：重建 Application conformance harness

### 现在是什么情况

磁盘上有 46 个 conformance 测试文件，但整个包被 POM 从测试编译和运行中硬排除。核心原因是：

- `ConformanceSuite.java` 在第 403 行附近截断；
- `SpringBootTargetConformanceIT.java` 的历史恢复证据也不完整；
- 历史输出在同一个 40KB 边界反复截断，继续搜索很难得到剩余正文。

当前 `clean verify` 能通过，是因为这些文件没有参与 testCompile，而不是因为 conformance 已恢复。

### 要做的事

1. 不再追求逐字找回截断部分，依据现存 46 个辅助类型重新设计最小可运行的 suite 编排。
2. 先让整个 conformance 包能够 testCompile。
3. 保留凭据脱敏、证据目录 ownership、schema 清理、advisory lock、进程退出和 fail-closed 规则。
4. 把“默认离线测试”和“显式 opt-in 外部 MySQL qualification”分开。
5. testCompile 成功后删除 POM 中的硬排除。
6. 没有外部环境时只报告 `NOT_RUN`，不能写 `QUALIFIED`。

### 完成标准

- 46 个 conformance 文件全部参加 testCompile。
- 默认 `clean verify` 不需要真实凭据或数据库也能安全结束。
- 外部资格测试只能通过显式 opt-in 启动。
- 外部运行产生的证据不泄漏密码、连接串或生成源码正文。
- POM 不再依靠排除整个包制造绿色构建。

### 规模

大，而且需要非常谨慎。

---

## 工作包 5：重建 Change 测试和缺失 SIR fixture

### 现在是什么情况

Change 模块当前有 22 项测试，历史约有 15 个测试类、约 306 项。12 个历史测试类只留下名字和 Surefire 数量，没有源码正文。

另外有 20 多个 Change 时代 SIR 文件缺失，例如：

- `campus-market-candidate.sir`
- `campus-market-minimal.sir`
- `campus-market-two-capabilities.sir`
- `v01-c.sir` 到 `v06-c.sir`
- CREATE/DELETE/约束变更相关 candidate

这些资源同时阻断 Change、Application 和 CLI 测试。

### 推荐做法

1. 不按历史文件名猜内容；先根据每个测试想验证的 Change 操作重新定义最小 base/candidate 对。
2. 每个 fixture 文件顶部或邻近文档说明它是灾后重写，以及它要制造什么精确差异。
3. 按 Change SIR v0.1-v0.6 分组恢复：修改 workflow、添加 capability、删除 capability、修改字段约束、修改未引用字段类型等。
4. 每组同时覆盖合法计划、错误版本、错误 target、closure、重复/冲突和 canonical 表示。
5. 测试必须使用 SymbolId/AstNodeId 精确定位，不允许名称模糊匹配。

### 完成标准

- 缺失 fixture 有经过审查的新版本，且 base/candidate 差异清楚。
- Change v0.1-v0.6 都有成功与失败路径。
- Application 中当前 assumption skip 的 6 项恢复运行。
- CLI 工作流类不再因为 `@BeforeAll` assumption 整类跳过。

### 规模

大，建议拆成 v0.1-v0.3 和 v0.4-v0.6 两轮。

---

## 工作包 6：决定 CLI 到底恢复到哪个边界

### 现在是什么情况

当前 CLI 主源码只支持只读的 `context` 和 `plan`。历史后期曾有：

- `generate`
- `register`
- `context`
- `plan`
- `apply`
- `recover`

当前对 `generate/register/apply/recover` 的冒烟结果都是 `unknown command`，所以完整本地 MVP 流程没有恢复。

### 这里需要你先做一个产品决定

选择之一：

1. **先接受只读 CLI V1。** 把 `context/plan` 作为当前正式边界，写清其他命令暂未恢复。
2. **恢复完整本地 MVP。** 重新接回 generate/register/apply/recover，但 CLI 只能做参数适配和 JSON 渲染，不能成为第二套状态权威。

从“尽量恢复灾前能力”的目标看，最终应选第二种；但必须在 Change fixture 和 Application 安全测试恢复以后做。

### 完成标准（如果恢复完整 MVP）

- 六个命令和 `--help/--version` 都有参数、退出码和 canonical JSON 测试。
- CLI 只能调用 typed Application API。
- CLI 不直接读写 Bundle、CURRENT、LOCK 或 Journal。
- Maven exec 路径可运行完整 generate → register → context/plan → apply → recover 流程。
- thin JAR 仍可留作已知发行问题，不要把它混进业务验收。

### 规模

中到大，依赖工作包 4 和 5。

---

## 工作包 7：补齐高风险文件事务和 Recovery 场景

### 已经有的证据

- 路径非法字符、Windows 保留名、trailing dot/space。
- junction/reparse point 防护。
- manifest 重复路径拒绝。
- DELETE 的若干 B0 中断状态和幂等恢复。

### 仍缺的证据

- DELETE 从真实 B0 到真实 B1 的完整发布和中断矩阵。
- CREATE V2 RecoveryEngine 的主要路径。
- UPDATE V1 RecoveryEngine 的主要路径。
- CURRENT 切换前后、Bundle 发布前后、Journal 关闭前后的故障注入。
- 第二卷或卷挂载点条件下 `isOther` 和 same-volume 证明。
- 不能补偿时是否真正保留了足够的权威恢复材料。

### 完成标准

- CREATE、UPDATE、DELETE 都有端到端故障注入矩阵。
- 每个中断点明确选择向前、向后或 `RECOVERY_REQUIRED`。
- 不存在自动恢复或猜测恢复方向。
- DELETE backup 继续只使用同卷 hard link，不增加 copy/move fallback。
- 所有 I/O、权限、symlink、文件类型不确定性都 fail closed。

### 规模

大，安全优先级与 conformance 相同。

---

## 工作包 8：补文档，但只补已经重新确认的事实

### 要做的事

- 恢复或重写缺失的 ADR-002、ADR-004、ADR-014 至 ADR-018。
- 明确标注“历史原文恢复”“依据代码重写”“灾后新增”。
- 清理 `.memory/INDEX.md` 中重复段落和明显过时阶段描述。
- 更新当前架构说明，让 typed reference-site、Change、Application 和 CLI 状态与代码一致。
- 为高风险类补为什么这样做的注释，不做逐行翻译式注释。

### 完成标准

- 新 Agent 只读 `AGENTS.md`、重建上下文和当前工作单，就不会得到相反结论。
- 文档不再声称 392、1130、QUALIFIED 或 MVP_FEASIBLE 是当前灾后证据。
- 所有缺失功能都明确写“未实现”“未恢复”或“未验证”。

### 规模

中等，应伴随前面每个工作包逐步完成，不要最后一次性补。

---

## 工作包 9：最后一次真正的灾后资格验收

只有工作包 1 至 8 收口后，才做最终验收。

### 必须执行

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short
```

另外分别记录：

- 默认离线测试；
- Windows 条件跳过；
- 外部 MySQL conformance；
- CLI 冒烟；
- 完整本地 MVP 流程；
- 未覆盖平台。

### 完成标准

- 不再通过 POM 硬排除无法编译的测试包。
- 不再有因为缺失 fixture 导致的整类测试跳过。
- Generator 和 PSG 有直接、系统的行为测试。
- Recovery 契约和文档完全一致。
- 所有数字来自当次运行，而不是复制灾前记录。
- 外部环境没运行就写 `NOT_RUN`；只有真实运行成功才写 `QUALIFIED`。

完成这一步以后，才可以说“项目重建完成，可以进入正常的新功能开发”。

---

## 推荐执行顺序

不要一次把全部工作交给一个 Agent。推荐顺序是：

1. 工作包 1：Recovery 契约和文档冲突。
2. 工作包 2：Generator 测试。
3. 工作包 3：PSG 测试。
4. 工作包 5：Change fixture 和测试。
5. 工作包 4：conformance harness。
6. 工作包 7：CREATE/UPDATE/DELETE Recovery 矩阵。
7. 工作包 6：恢复或正式收缩 CLI 边界。
8. 工作包 8：文档随各阶段收口。
9. 工作包 9：最终资格验收。

每次只交付一个工作包，并要求 Agent 在报告中列出：

- 改了哪些文件；
- 哪些是历史恢复，哪些是灾后重写；
- 跑了什么命令；
- 通过、失败、跳过多少；
- 哪些事情仍然没做；
- 是否修改了生产行为。

## 暂时不要做的事情

在重建完成前，不开始：

- Redis Extension；
- 第二 Target；
- Constraint VM；
- PSG `REFERENCES` 新边；
- Snapshot V2；
- Change SIR v0.7；
- GUI、daemon、多用户；
- Java 反向解析；
- 新的增量编译系统。

这些都不是坏方向，但现在做会把“恢复旧能力”和“开发新能力”混在一起，让我们再次失去可信基线。

## 你以后判断一个阶段是否真的完成，可以只问四个问题

1. 测试是真的运行了，还是被排除/整类跳过了？
2. 结论来自当前命令，还是来自灾前数字？
3. 测试是在验证架构契约，还是只迎合当前反编译代码？
4. 失败时是否保留了安全、结构化、可恢复的状态？

四个问题都能得到明确答案，才进入下一阶段。
