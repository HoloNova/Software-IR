# 当前工作单：Q15 实体级片段与能力集合解耦（删除能力保持纯删除）

- 状态：**`DONE`**（2026-09-23 负责人验收通过；两条全量闸门与业务回归按负责人指示集中到提交前那一次运行，见「验收记录」）
- 所属阶段：**G1：单文件课程业务切片**（前置修复单；解除 Q13 的 BLOCK-2）
- 前置状态：G0 已闭合；Q9/Q10/Q11 已完成、验收并归档；Q14（变更层投影对齐）已完成、验收并归档（[`completed/Q14-change-layer-projection-parity.md`](completed/Q14-change-layer-projection-parity.md)）；**Q13（G1 变更闭环）因 BLOCK-2 暂停**（[`Q13-g1-change-loop.md`](Q13-g1-change-loop.md)）
- 版本快照：按既有规则执行期间不提交 Git；同一批未提交改动**只做一次提交**（负责人 2026-09-23 指示），全量与业务回归集中到提交前一次运行

## 为什么需要这一单（BLOCK-2 的实测根因）

Q13 的删除轮（移除 `UpdateCourse`）被 `SIR-CHANGE-IMPACT-202` 拒绝：幸存文件 `CourseMapper.java` 变了。从零生成基线与候选后对比该文件，候选少了 12 行——正是乐观锁的两个辅助方法：

```java
@Select("SELECT * FROM course WHERE id = #{id} FOR UPDATE")
Course selectByIdForUpdate(@Param("id") Long id);

@Update("UPDATE course SET name = …, description = …, capacity = …, version = version + 1 WHERE id = … AND version = #{expectedVersion}")
int updateIfVersionMatches(@Param("candidate") Course candidate, @Param("expectedVersion") long expectedVersion);
```

根因在 `MapperRenderer`：它**扫描全部能力工作流**寻找针对该实体的 `ConditionalUpdate`（`conditionalUpdate(ctx, entityDecl)`），找到才发射这两个方法，SQL 的 SET 列表与版本守卫都取自那个能力的计划（类 javadoc 明确写了这个取舍）。于是删掉最后一个做条件更新的能力，`Course` 实体没变、它的 mapper 文件却变了，而变更层的影响模型是"删除能力只删该能力自己的产物、幸存文件不得变化"（Q3 的删除 fixture 特意回避了这个情形，注释里写明了）。

**影响面**：G1 门禁"继续生成 SIR/修改 SIR"里**删除写能力**这一整类变更不成立；Q10 C7 的结论（"支撑产物必须是无条件产物"）在**能力依赖片段**上还没有对应处理。

## 设计取向

**不变性原则**：一个**实体级**产物（实体自己的 mapper）的内容必须只由该实体的声明决定，不由"当前有哪些能力"决定。同一实体、同一目标下，有无某个能力，它的 mapper 必须逐字节相同。这条原则是 R1 的本质，也是"删除能力 = 纯删除"能成立的前提。

**取舍（已定，记录原因）**：`updateIfVersionMatches` 的 SET 列表改为**该实体的全部变更列**（持久列去掉 identity 与 version 列），而不再是"该能力绑定的列"。理由是只有"按实体声明"的推导才与能力集合无关。行为上仍然正确：候选行是先从库里读出来再与载荷合并的，未被载荷绑定的列写回的是**它读到的值**（不变量式重写），因此这是一次语义等价的写；代价是既有 fixtures 生成的 SQL 文本会变化（例如 Q10 的 `course` 多出 `code = #{candidate.code}`），需要重跑写侧证据确认行为不变。

**范围克制**：本单只处理 mapper 这一个实体级片段。同族的 **`Application.java` 的 actor 传输片段**（Q3 fixture 注释记录的同类情形：actor 集合变化会改变该文件内容）**不在本单**，登记为独立项，避免一次改动横跨多个渲染器。

## 改动面

| # | 位置 | 改什么 | 为什么 |
|---|---|---|---|
| F1 | `sir-lowering-spring-boot` 的 `SpringBootDeclaration.VersionSpec` | 增加 `long versionIncrement`（校验 ≥1），由 `lowerVersionSpec` 从 `writePolicy.versionIncrement()` 填入 | 版本如何递增是**实体版本规格**的一部分；不放进模型，生成器在没有能力时就无法得知道增量，只能猜或硬编码 |
| F2 | `sir-generator-spring-boot` 的 `MapperRenderer` | 删除 `conditionalUpdate(ctx, entityDecl)` 的能力扫描；改为：实体声明里 `version()` 存在即发射两个方法，表名/identity/版本列取自实体声明，SET 列表取实体的变更列，增量取 `VersionSpec.versionIncrement()` | 让该产物只依赖实体声明（不变性原则） |
| F3 | `sir-generator-spring-boot` 的生成契约测试 | 更新既有断言（SQL 文本随 F2 变化），并新增**不变性探针** | 见测试计划 T1 |

**不改**：`SpringBootIrValidation` 对条件更新的校验（它校验的是能力计划，不是 mapper）、`ConditionalUpdate` 记录本身（能力的计划仍然需要它来生成 service 侧代码）、其它渲染器、SIR 语法、变更层。

## 测试计划

**T1 不变性探针（生成器契约测试，本单的核心证据）**
- 同一个含 `versioned` 实体的源，**一处有**做条件更新的能力、**一处没有**（能力被删掉）：生成的 `CourseMapper.java` 必须**逐字节相同**。
- 反面探针（灵敏度）：把实体的 `versioned` 标志去掉，mapper 里的两个方法必须消失；`versionIncrement` 变化时 SQL 的增量必须跟着变（若无法从公开 API 变更增量，则该断言以降低层 IR 的事实为准）。

**T2 与既有证据的差异登记**
- Q10/写侧切片生成的 mapper SQL 文本变化（SET 列表多出未绑定列）必须**如实记录**，并在提交前那一次批量运行里由写侧业务场景 IT 重新确认行为不变（201/200/400/409 语义不变）。
- 生成工程的离线编译仍须通过（mapper 是 Java 源码，改动不引入新依赖）。

**T3 集成（Q13 的删除轮）**
- Q13 的链在 F1–F2 之后应能完成 R3a（删 `UpdateCourse`）与 R3b（删 `CreateCourse`，最后一个写能力），并且**支撑产物与实体级产物都不被删**；其结论回填 Q13，不在本单预先假定通过。

## 允许范围

- `sir-lowering-spring-boot` 的 `SpringBootDeclaration.VersionSpec` 与 `SpringBootModelLowerer.lowerVersionSpec`（最小改动）。
- `sir-generator-spring-boot` 的 `MapperRenderer` 与其契约测试、必要的新增测试。
- 文档：本单、Q13 暂存件、路线图镜像、资格报告与覆盖清单对应小节。

## 禁止范围（明确不做）

- 不改 SIR 语法、语义规则、变更词汇（`ChangeOperation`）与 `PlannerCore` 的判定规则。
- 不改 `ConditionalUpdate` 记录的语义、不改 service/controller/advice 的渲染；不处理 `Application.java` 的 actor 片段（登记为独立项）。
- 不新增依赖、不加 skip/exclude、不以降 skip 或改断言口径的方式让闸门变绿；不得为了让计划通过而放宽 `SIR-CHANGE-IMPACT-202`。

## 定向验证命令

```bash
# 开发期间（按需）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-lowering-spring-boot,sir-generator-spring-boot -am test
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-toolchain-application -am \
  -Dtest=ChangeLoopPlanningContractTest,ChangeProjectionParityApplicationTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

## 完成门

1. F1–F3 落地；`MapperRenderer` 不再引用任何能力工作流（能力集合与该产物无关），可用架构式断言固定（源码扫描或 IR 事实检查）。
2. **T1 不变性探针通过**：同一实体在有/无条件更新能力时 mapper 逐字节相同。
3. T1 反面探针通过：去掉 `versioned` 后两个方法消失；增量随实体版本规格变化。
4. 既有写侧生成契约测试按新口径更新并通过；**没有**因为改动而降低断言强度（SQL 的 SET 列表、`FOR UPDATE`、版本守卫仍然逐项断言）。
5. T3：Q13 的 R3a/R3b 能完成（结论回填 Q13；若仍失败，按 D6 停轮报告，不放宽规则）。
6. 生产代码改动仅限上述 F1–F2 两个文件（`git status` 可核对）。
7. 提交前那一次批量运行：两条全量闸门 BUILD SUCCESS，与基线差量逐模块归因；Q9/Q10/Q11 三个业务场景与 Q13 的变更闭环场景结论如实记录（写侧 SQL 文本变化必须由行为断言确认语义不变）。
8. 文档同步：本单状态、Q13 的解除说明、路线图镜像、资格报告与覆盖清单；`git diff --check` 无输出。

## 结果与证据（2026-09-23）

**本单新增的生产改动**：`sir-generator-spring-boot` 的 `MapperRenderer.java`（重写为实体驱动）、`sir-lowering-spring-boot` 的 `SpringBootDeclaration.java`（`VersionSpec` 增加 `versionIncrement` 组件）与 `SpringBootModelLowerer.lowerVersionSpec` 的一行（填入该增量）。`git status` 里 `WorkflowRenderer.java`/`SpringBootModelLowerer.java` 的其余改动属 Q11，不是本单。

| 门 | 结果 | 证据 |
|---|---|---|
| F1 | 落地 | `VersionSpec` 增加 `long versionIncrement`（构造器校验 ≥1），`lowerVersionSpec` 从 `writePolicy.versionIncrement()` 填入——版本如何递增现在是实体版本规格的一部分，生成器无需能力即可得知 |
| F2 | 落地 | `MapperRenderer` 删除了能力工作流扫描（`conditionalUpdate(ctx, entityDecl)`）；`version()` 存在即发射两个方法，表名/identity/版本列/SET 列表/增量全部取自实体声明；SET 列表改为**实体的变更列**（持久列去掉 version 列） |
| T1（不变性探针，核心证据） | 3/3 通过 | `GeneratorEntityArtifactInvarianceTest`：同一实体**有/无**条件更新能力时 `CourseMapper.java` **逐字节相同**；有版本列的实体总是带两个辅助方法；无版本列的实体不带（灵敏度反面探针） |
| T1（既有契约按新口径更新） | 17/17 通过 | `GeneratorWriteSliceContractTest`：SQL 文本更新为实体变更列（多出 `code = #{candidate.code}`），结构性断言全部保留（`FOR UPDATE`、版本守卫、identity 参数、`version = version + 1`、版本绝不取自请求） |
| T3（Q13 的删除轮） | **通过** | Q13 的契约测试：R1→R2→R3a（删 `UpdateCourse`）→R3b（删最后一个写能力 `CreateCourse`）→R4 全部 apply 成功，支撑产物与实体级产物都存活，且**增量应用结果与从零生成逐相对路径、逐字节相同**——BLOCK-2 解除 |
| 定向回归 | 无新增失败 | 受影响模块：parser 79/0、semantic 176/0、lowering-api 4/0、lowering-spring-boot 82/0、**generator 77/0**（+3 为本单）、project-graph 72/0、change 33/0、application 222 项中仅 Q13 自身的重放用例失败（C5，见 Q13） |

## 实施期订正（C1–C2）

- **C1（SET 列表口径，已在取值表记录）**：既有的 `GeneratorWriteSliceContractTest.theSetListFollowsTheChangeSetAndNeverTheRequestVersion` 断言"SET 列表严格跟随载荷变更集、`code` 不得出现"，与 R1 的不变性要求直接冲突（唯一与能力集合无关的推导就是实体变更列）。该用例按 D3 更新：**保留**"只有一条 UPDATE""版本绝不取自请求""identity 不是 SET 列"这三条结构断言，并把"SET 列表 = 实体变更列"写成显式期望。语义正确性依据：候选行先从库里读出再与载荷合并，未绑定列写回的是它读到的值。
- **C2**：Q13 的删除轮**实测通过**（T3），因此 BLOCK-2 的解除不需要 R2/R3；R2（让影响模型承认操作可改幸存文件）保留在 Q13 的记录里作为后续独立项候选。另注意 Q13 的重放用例（C5）仍失败，那是 Q13 自身的链形状问题，与本单无关。

## 完成门状态

1–6 已满足；**门 7 的批量运行按负责人指示集中到提交前一次执行**（AGENTS.md「测试资源约束」），该运行同时覆盖 Q9/Q10/Q11 三个业务场景与 Q13 的变更闭环场景，并须确认写侧 SQL 文本变化在行为上等价（201/200/400/409 语义不变）。在此之前不声称门 7 为绿。

## 独立项（登记，不在本单）

- **`Application.java` 的 actor 传输片段**与 mapper 属同族（Q3 fixture 注释记录：actor 集合变化会改写该文件内容）。课程切片无 actor，因此不影响 Q13；登记为独立项，未来若出现"删除唯一带 actor 的能力"的变更，会以同样的 `SIR-CHANGE-IMPACT-202` 形式暴露。

## 验收记录（2026-09-23）

负责人 2026-09-23 回复"确认"，本单验收通过。逐条对照：

1. **F1–F2**：`VersionSpec` 携带 `versionIncrement`（构造器校验 ≥1）、`MapperRenderer` 改为实体驱动且不再引用任何能力工作流；SET 列表 = 实体变更列。
2. **T1 不变性探针 3/3**：同一实体在有/无条件更新能力时 `CourseMapper.java` 逐字节相同；有版本列必有辅助方法；无版本列必无（反面探针）。
3. **T1 既有契约 17/17**：SQL 文本按新口径更新，结构断言保留；原"`code` 不得出现"的断言按 D3 改为显式期望"SET 列表 = 实体变更列"，未降低强度。
4. **T3 通过**：Q13 的 R1→R2→R3a→R3b→R4 全部 apply 成功，支撑产物与实体级产物存活，"增量 == 从零"逐字节成立——BLOCK-2 解除。
5. **定向回归**：受影响模块 0 新增失败（parser 79、semantic 176、lowering-api 4、lowering-spring-boot 82、generator 77、project-graph 72、change 33；application 222 项中仅 Q13 自身重放用例失败，属 Q13 的 C5）。
6. **范围**：本单新增生产改动只有 `MapperRenderer.java`、`SpringBootDeclaration.java` 与 `SpringBootModelLowerer.lowerVersionSpec` 一行。
7. **门 7（批量运行）**：按负责人指示集中到同一批改动的提交前那一次执行；本单可验收内容已由定向证据覆盖。

## 裁决记录（2026-09-23）

1. **R1 采纳**（负责人"确认"推荐项）：实体级片段按实体声明发射；R2（影响模型承认操作可改幸存文件）保留为后续独立项候选，R3（缩小删除轮范围）不再需要。
2. **C1 接受**：SET 列表口径改为实体变更列，既有写侧契约断言按 D3 更新（保留结构性断言）。
3. **C2 接受**：实测证明 Q13 的删除轮通过，无需 R2/R3。
4. **独立项登记**：`Application.java` 的 actor 传输片段与 mapper 同族（actor 集合变化会改写该文件），本单不处理。

## 失败 / NOT_RUN 口径

- 任何 fail/error（除既有 5 项 Windows junction skip）即本单未完成；不得以降 skip、加 exclude 或改断言口径的方式"通过"。
- 若不变性探针显示**其它**幸存文件也随能力集合变化（例如 `Application.java` 的 actor 片段在这个切片上也被触发），记录为新的同族项并报告，不自行扩大本单范围。
- 参考环境不可用时，T3 与写侧行为回归记 `NOT_RUN`，完成门 5/7 的对应部分不成立。

## 需要负责人裁决的设计选择（D0–D3）

| # | 决策 | 推荐 | 备选与代价 |
|---|---|---|---|
| D0 | SET 列表口径 | **实体的全部变更列**（唯一与能力集合无关的推导） | 保留"能力绑定列"：无论怎么组合都会在能力变化时改变文件，BLOCK-2 无法解除 |
| D1 | 版本增量放哪 | **放进 `VersionSpec`**（实体版本规格） | 放进目标 profile 并让生成器读 profile：跨层引入 target 依赖，且 mapper 仍不只看实体 |
| D2 | 本单是否一并处理 `Application.java` 的 actor 片段 | **不处理**，登记为独立项 | 一并处理：改动横跨多个渲染器，风险与审阅面显著变大 |
| D3 | 既有生成契约断言的更新幅度 | **只更新 SQL 文本事实，保留全部结构性断言**（`FOR UPDATE`、版本守卫、identity 参数、注解） | 顺带弱化断言：不接受 |

## 实施期订正协议

执行中若发现推荐方案与冻结依赖、既有契约或参考环境冲突，按 Q9–Q14 的做法处理：**保持原意图、记录证据、写入本单的"实施期订正"节，禁止范围不变**；不得为让用例通过而放宽完成门。

## 交接记录

**状态：`DONE`（2026-09-23 验收归档）。** 负责人 2026-09-23 在 Q14 验收的同时"确认"BLOCK-2 的推荐项 **R1**，并在本单完成后再次回复"确认"通过验收。实施：F1 → F2 → F3/T1 探针（3 项）→ 定向回归 → T3（Q13 的删除轮与"增量 == 从零"实测通过，BLOCK-2 解除）。**Q13 已回到 `ACTIVE_WORK.md` 继续**（先修其重放用例的链形状 C5，再补变更闭环业务场景 IT 与最后收口）。R2（让影响模型承认操作可改幸存文件）与 R3（缩小删除轮范围）作为备选记录在 Q13 的 BLOCK-2 节。
