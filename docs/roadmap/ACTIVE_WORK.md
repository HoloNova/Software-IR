# 当前工作单：Q10 G1 第二切片——Course 写侧（Create + PATCH 三态 + version 冲突 + 结构化字段错误）

- 状态：`AWAITING_ACCEPTANCE`（负责人 2026-09-21 回复“按照你推荐的方案即可”，即 D0–D16 全部按推荐项批准；实现已完成，见文末交接记录）
- 所属阶段：**G1：单文件课程业务切片**（G0 已完成门见 `docs/qualification/CURRENT_QUALIFICATION.md` 第 0 节）
- 前置状态：Q9（Course 单实体分页查询端到端）已完成并归档（见 [`completed/Q9-query-slice-course-pagination.md`](completed/Q9-query-slice-course-pagination.md)），全量 620/0/0/5
- 方向来源：`docs/design/07-validation-and-direction-roadmap.md` 的 **BIZ-01、BIZ-02、BIZ-03、BIZ-04**；`docs/design/02-sir-language-and-modules.md` §6（CRUD 精确语义）、§10（HTTP 契约表）；`docs/design/03-compiler-and-generated-backend.md` §7（PATCH 与并发更新实现契约）
- 裁决记录（负责人 2026-09-21 回复“按照你推荐的方案即可”）：D0 **不拆单**；D1 采用 `input X patch of Course`；D2 Profile 冻结信封 `{<identity>, expectedVersion, changes}`（identity 在顶层，不引入路由模板）；D3 字段级 `versioned`；D4 version 初始值 **0**；D5 生成显式 `@Update` 条件语句（不用拦截器）；D6 `persist … else` 仅 versioned UPDATE；D7 允许码集 400/404/409；D8 `.present` + `validate … else` 表达空变更；D9 patch 候选校验为权威路径、create 亦生成同一机制；D10 统一错误信封；D11 写响应强制 `view`；D12 成功状态码由 workflow 形状决定（insert → 201）；D13 新增 `course-admin.sir` 与独立 DDL/seed，不动 Q9 产物；D14 顺序 stale + 屏障并发竞态；D15 允许跨模块最小扩展；D16 路由模板不纳入（登记 Q12）。
- 版本快照：按负责人 2026-09-18 规则，执行期间不提交 Git

## 当前资格基线（Q10 开始时的实际状态）

- 实现快照：`24eec6d`（`main` 与 `origin/main` 一致；Q9 的实现与文档改动仍在工作树，未提交）。
- 默认离线 Reactor：冻结与完成两种形式均 `BUILD SUCCESS`；Surefire **620 run / 0 fail / 0 error / 5 skip**（5 项 skip 全为 Windows junction）。
- 外部 MySQL conformance：MySQL 8.4.11 参考环境五场景 `QUALIFIED`；Q9 业务切片场景 `PASSED`（40 项断言，opt-in）。
- 当前资格权威：`docs/qualification/CURRENT_QUALIFICATION.md` 第 0 节与 1.9 节。

## 目标

在**现有单文件 SIR 链**上完成 G1 的第二条端到端切片：同一个课程业务对象的**写侧**。SIR 源代码贯穿 Parser、Resolve、Type、Validate、Normalize、Spring Boot Lowering、Generator，生成工程在真实 MySQL 与真实 HTTP 上完成创建、读取、局部更新与并发冲突验证，并产出独立业务断言。

要证明的四件事：

1. **写契约成立**：创建（POST）返回 201 与声明的响应投影；读取（GET）返回 200 与投影，未找到返回 404；局部更新（PATCH）严格区分"未提供 / 提供 null / 提供值"三态，并在成功后按 1 递增 version。
2. **错误契约成立**：非法长度、缺必填、未知请求字段、非法分页之外的写侧非法输入一律 400 且**没有写入**；响应体携带**字段路径**与**稳定错误码**；版本冲突返回 409；未找到返回 404。
3. **并发成立**：同一 `expectedVersion` 的两次更新中恰好一次成功、另一次 409，且数据库值不被丢失更新（version 只递增一次）。
4. **决策归属成立**：三态语义、信封形状、候选校验、条件更新语句、影响行数判定、错误码与状态码全部由 Lowering 决定并写入 Lowered IR；Generator 只渲染，不读 AST/Normalized/SIR，不访问磁盘。

本单**只做写侧与对应错误契约**。关联过滤（BIZ-06）、业务 DELETE、唯一性约束、认证授权（BIZ-07）、报名并发（BIZ-08..12）、路由模板均不在本单范围（见"禁止范围"）。

### G1 内部顺序（本单只做 Q10）

| 工作单 | 范围 | 对应设计完成门 | 状态 |
|---|---|---|---|
| Q9 | Course 单实体 + 分页/投影/排序/字面量过滤查询端到端 | BIZ-05（查询与分页部分）、GEN-01、GEN-02 | **已完成并归档** |
| **Q10（本单）** | Course 写侧：Create、Get、PATCH 三态、version 冲突、结构化字段错误 | BIZ-01、BIZ-02、BIZ-03、BIZ-04 | `SPEC_REVIEW` |
| Q11（未立项） | 关联过滤（EXISTS 语义）、根分页不重复、关联批量读取与 SQL 次数预算 | BIZ-06 | 未立项 |
| Q12（未立项，本轮新登记） | 路由模板（`/api/courses/{id}` 形态的 `@PathVariable` 绑定）与资源式命名 | 无（设计 §10 未冻结路由形态） | 未立项 |

## 输入 SIR（本单的目标文本）

新 fixture `course-admin.sir`（内容如下，作为本单的冻结输入；**不修改 Q9 的 `course-catalog.sir`**）：

```text
sir 0.1

software CourseAdmin {
  metadata {
    displayName "Course Admin";
    namespace "com.example.courseadmin";
  }

  target {
    language java 21;
    framework spring_boot;
    persistence mybatis_plus;
    database mysql;
    build maven;
    interface rest;
  }

  declarations {
    entity Course persistent {
      identity id: Int64 generated auto;
      field code: String where notBlank, length(1, 32);
      field name: String where notBlank, length(1, 100);
      field description: Optional<String> where length(0, 500);
      field capacity: Int32 where min(1);
      field version: Int64 versioned;
    }
    view CourseDetail from Course {
      field id: Int64;
      field code: String;
      field name: String;
      field description: Optional<String>;
      field capacity: Int32;
      field version: Int64;
    }
    input GetCourseInput {
      field id: Int64;
    }
    input CreateCourseInput {
      field code: String where notBlank, length(1, 32);
      field name: String where notBlank, length(1, 100);
      field description: Optional<String> where length(0, 500);
      field capacity: Int32 where min(1);
    }
    input UpdateCourseInput patch of Course {
      field id: Int64;
      field name: String;
      field description: Optional<String>;
      field capacity: Int32;
    }
    error CourseNotFound status 404;
    error EmptyChange;
    error StaleVersion status 409;
    capability GetCourse {
      input GetCourseInput;
      output CourseDetail;
      fails CourseNotFound;
      requires readonly;
      expose query;
      workflow {
        load Course by input.id as course else CourseNotFound;
        return course;
      }
    }
    capability CreateCourse {
      input CreateCourseInput;
      output CourseDetail;
      requires atomic;
      expose command;
      workflow {
        create Course {
          code: input.code;
          name: input.name;
          description: input.description;
          capacity: input.capacity;
        } as created;
        persist created;
        return created;
      }
    }
    capability UpdateCourse {
      input UpdateCourseInput;
      output CourseDetail;
      fails CourseNotFound;
      fails EmptyChange;
      fails StaleVersion;
      requires atomic;
      expose command;
      workflow {
        validate input.name.present or input.description.present or input.capacity.present else EmptyChange;
        load Course by input.id as course else CourseNotFound;
        update course {
          name: input.name;
          description: input.description;
          capacity: input.capacity;
        }
        persist course else StaleVersion;
        return course;
      }
    }
  }
}
```

## 新增 SIR 表面（逐条：语法、语义、唯一所属阶段、生成影响）

### S1 版本字段标记 `versioned`

- **语法**：`field <name>: <type> versioned;`（字段级标记；`fieldDecl` 增加可选 `VERSIONED` 关键字）。
- **语义**：一个实体最多一个 `versioned` 字段；类型必须是 `Int64`（不可选、不可为 `List`/`Ref`）；该字段是乐观并发版本，由框架在 INSERT 时初始化、在成功 UPDATE 时按 1 递增。`versioned` 只允许出现在**实体**的字段上：`view` 字段、patch 载荷字段以及其它声明种类出现该标记均报诊断（与既有“view 字段不得带约束”同性质）。
- **既有语法约定（本单沿用，不得偏离）**：`fieldDecl` 为 `field <name>: <type> [where …] ;`；`create <Entity> { … } as <var>`；`update <var> { … }`（目标是变量，不是实体名）；`persist <var> [else <Error>]`；`load <Entity> by <expr> as <var> else <Error>`。
- **唯一所属阶段**：Parser（语法）；Resolve（字段符号与标记登记）；Type（类型必须 `Int64`）；Validate（每实体最多一个；`create`/`update` 都不得绑定该字段；`versioned` 实体的 `update` 必须来自 patch 载荷；`versioned` 实体的 `persist` 必须声明 `else`）；Normalize（版本字段随实体进入归一模型）；Lowering（初始值、条件更新计划）。
- **生成影响**：实体类对该字段额外渲染 `@Version` 注解（MyBatis-Plus 注解包，仅作列语义声明，**不依赖** `OptimisticLockerInnerInterceptor` 参与并发控制）；INSERT 由创建步骤写入初始值；UPDATE 由条件语句递增。

### S2 错误状态码 `error <Name> status <code>;`

- **语法**：`errorDecl` 增加可选 `status` 子句（整数常量）。
- **语义**：该声明错误的 HTTP 状态码；省略时默认 400（保持既有行为）。本单允许的码集：**400 / 404 / 409**。
- **唯一所属阶段**：Parser（语法）；Resolve（无新引用）；Type（常量必须是整数且属于允许集合，否则 `SIR-TYPE-001`）；Validate（同一 capability 的 `fails` 集合内不得出现两个同码同名的冲突声明——按名唯一已由既有规则保证）；Lowering（写入 `ErrorDeclaration.httpStatus`）；Generator（渲染 `@ResponseStatus`）。
- **生成影响**：生成的异常类 `@ResponseStatus` 使用声明的码；异常类改为继承生成的公共基类（见 S4），使错误响应体统一走信封。

### S3 持久化失败通路 `persist <Entity> else <Error>;`

- **语法**：`persistStep` 增加可选 `else IDENT`。
- **语义**：`persist` 的声明失败模式。对 **versioned 实体**：条件更新影响行数 ≠ 1 时抛出该错误（版本冲突）。对**非 versioned 实体的 INSERT/UPDATE**：本单**不支持** `else`，报"未支持"诊断（唯一性约束是独立工作；不虚构触发路径）。versioned 实体的 `persist` **必须**声明 `else`（冲突不得被静默吞掉）。
- **唯一所属阶段**：Parser（语法）；Resolve（错误名绑定，新 ReferenceRole `PERSIST_FAILURE`）；Type（无表达式）；Validate（错误必须在 `fails` 集合内；versioned/非 versioned 与 `else` 的组合规则）；Normalize（`PersistStep.failure`）；Lowering（`PersistStep.failure` + 影响行数判定计划）。
- **生成影响**：条件更新后检查影响行数，`!= 1` 抛声明错误（404/409 由 S2 决定）。

### S4 结构化字段错误信封与错误基类

- **语法**：**不新增 SIR 语法**。信封与字段错误码词汇是 Profile 冻结契约，与 `PageResponse` 同级。
- **语义**：所有声明错误与非预期读入错误都以同一信封返回：

```json
{
  "code": "REQUEST_INVALID",
  "message": "request validation failed",
  "fields": [
    { "path": "changes.capacity", "code": "MIN", "message": "must be greater than or equal to 1" }
  ]
}
```

- `code`：声明错误用其 SIR 名称（如 `StaleVersion`、`CourseNotFound`、`EmptyChange`）；载荷/类型/未知字段错误用 Profile 固定码 `REQUEST_INVALID` 或 `UNKNOWN_FIELD`。
- `fields[].path`：bean validation 失败用 DTO 属性路径；候选校验失败用 SIR 字段名（patch 场景加 `changes.` 前缀）；未知字段用请求中的键路径。
- `fields[].code`：约束种类词汇，与 IR 的 `ConstraintKind` 一一对应（`NOT_BLANK`、`EMAIL`、`SIZE`、`MIN`、`MAX`、`DECIMAL_MIN`、`DECIMAL_MAX`），保证两条校验路径使用同一词汇。
- `message` **不是契约**（IT 只断言状态码、`code` 与 `fields[].path`/`code`）。
- **唯一所属阶段**：Lowering（信封 artifact 声明 + 约束码词汇 + 候选校验计划）；Generator（渲染信封、异常基类、`@RestControllerAdvice`、Jackson 严格配置）。
- **生成影响**：新增项目级 artifact：错误信封 DTO、声明错误基类（携带 `code` 与 `fields`）、`@RestControllerAdvice`（映射生成的基类异常、`MethodArgumentNotValidException`、`HttpMessageNotReadableException`）、Jackson 配置（开启未知字段拒绝）。**不新增依赖**（`spring-boot-starter-validation` 已在冻结依赖集内）。

### S5 局部更新载荷 `input <Name> patch of <Entity> { ... }`

- **语法**：`inputDecl` 增加可选 `patch of <Entity>` 形式。
- **语义**：
  1. 载荷字段必须与实体字段**同名同类型**（identity 与 version 字段的声明见下）；**不得声明 `where` 约束**（约束的权威来源是实体字段）。
  2. 载荷**必须**声明目标 identity 字段（名称自由，类型必须等于实体 identity 类型）；**不得**声明 version 字段。
  3. 传输形状为 Profile 冻结信封：`{ "<identityName>": <值>, "expectedVersion": <版本>, "changes": { <其余载荷字段> } }`；identity 字段与 `expectedVersion` 位于顶层，其余字段位于 `changes`。
  4. 三态：`changes` 中**缺席**的字段保持旧值；显式 `null` 清空（仅当实体字段可空，否则候选校验 400）；出现值则替换。
  5. `changes` 缺席或为空对象 → 由 SIR 声明（`validate … else`）判定为 400；未知键 → `UNKNOWN_FIELD` 400；`changes` 中试图写 identity/version（未声明为可变更字段）→ 未知字段 400。
  6. 使用 patch 载荷的 capability 的 HTTP 方法是 **PATCH**，路由与路径参数形态**不变**（仍是 `/api/<kebab-capability>`），因此本单不引入路由模板。
- **唯一所属阶段**：Parser（语法与 AST）；Resolve（源实体引用 + 字段绑定，新 ReferenceRole `PATCH_SOURCE_ENTITY`）；Type（同名同类型、identity 类型一致）；Validate（无约束、必须声明 identity、不得声明 version、patch 载荷只能被 `expose command` 的非 readonly capability 使用、使用 patch 载荷的 capability 不得含 `find`）；Normalize（`NormalizedInputKind.PATCH`）；Lowering（`PatchPlan`：信封属性名、identity 字段、版本期望、变更绑定、空变更错误、候选校验）。
- **生成影响**：patch 载荷渲染为信封 DTO（顶层 identity + `expectedVersion` + 嵌套 `changes` DTO）；`changes` DTO 每个字段带确定性 presence 标记；服务端按 presence 逐字段应用，然后执行候选校验与条件更新。

### S6 存在性表达式 `<ref>.present`

- **语法**：成员访问后增加可选 `PRESENT` 后缀（`postfix` 形式），产生布尔值。
- **语义**：仅对 **patch 载荷字段**合法（其它引用报诊断）；`input.x.present` 为真当且仅当请求的 `changes` 中出现了 `x`（显式 null 视为出现）。
- **唯一所属阶段**：Parser（语法）；Resolve（引用绑定，新 ReferenceRole `PATCH_FIELD_PRESENCE`）；Type（结果类型 `Boolean`；左侧必须解析为 patch 载荷字段）；Validate（非 patch 字段报 `SIR-VALID-001`）；Normalize（`PresentExpression`）；Lowering（presence 取用计划）；Generator（渲染 presence 判定，取自 IR 的 presence 属性名）。
- **生成影响**：`validate` 条件渲染为对 presence 属性的读取；不引入反射或动态属性访问。

## 传输与错误契约（Profile 冻结）

| 契约 | 形态 | 归属 |
|---|---|---|
| 写侧成功状态码 | create+insert → **201**；patch/update → **200**；get → **200** | Lowering（按 workflow 形状决定），Generator 渲染 |
| 未找到 | `error <Name> status 404` | SIR 声明（S2） |
| 版本冲突 | `error <Name> status 409` | SIR 声明（S2）+ `persist … else`（S3） |
| 载荷/类型/未知字段错误 | 400 + 信封（S4） | Profile（Lowering 声明 artifact） |
| 空变更 | 400 + 信封，`code` = 声明的错误名 | SIR（S5/S6）+ 声明错误 |
| 写成功响应体 | **必须是声明的 `view` 投影**（不把 Entity 当响应体） | SIR `output` + Lowering 投影计划 |
| patch 信封属性名 | `expectedVersion`、`changes` | Profile（写入 `PatchPlan`） |
| patch 顶层必填项 | 载荷声明的 identity 字段与 `expectedVersion` 均为必填；缺失 → 400 信封（字段路径分别为该 identity 名与 `expectedVersion`） | Profile + 生成的信封 DTO 注解 |
| version 初始值 | **0**（INSERT 时写入） | Profile（`SpringBootWritePolicy`，与 `SpringBootQueryPolicy` 同级） |
| 未知字段策略 | 拒绝（Jackson `FAIL_ON_UNKNOWN_PROPERTIES`） | Profile |
| 401/403/500 与 traceId | **不在本单**（认证属 G5；非预期错误保持 Spring 默认行为并在文档登记） | — |

## 测试先行顺序（每步必须先写失败测试再实现）

**T1 Parser**：S1–S3、S5、S6 的语法与 AST；正例（`course-admin.sir` 全量可解析）+ 反例（缺 identity 声明、`patch` 后缺 `of <Entity>`、`status` 后非整数、`persist` 后缺错误名、`present` 用于非成员引用等）含精确码、数量与 span。
**T2 Semantic**：Resolve/Type/Validate/Normalize 的 S1–S6 规则，逐条反例（每实体两个 versioned 字段、versioned 类型非 Int64、patch 字段类型不一致、patch 声明 version 字段、patch 字段带约束、patch 缺 identity、patch 用于 readonly/query、`create`/`update` 绑定 version、versioned update 非 patch、versioned persist 缺 `else`、非 versioned INSERT 使用 `else`、`persist … else` 的错误未在 `fails` 中、`status` 码不在允许集合、`.present` 用于非 patch 字段）。
**T3 Lowering**：新 IR 形状与一致性校验（版本规格与初始值、patch 计划、presence 绑定、条件更新计划、候选校验计划、信封 artifact、成功状态码、错误状态码、写响应投影），并保留 Q9 的 IR 校验；GEN-02 式"故意冲突的 Lowered 输入被拒"含灵敏度对照。
**T4 Generator**：信封 DTO/`changes` DTO/presence 标记、advice 与异常基类、Jackson 严格配置、候选校验渲染、条件更新语句渲染（显式 `SET` 含 null、`WHERE id = ? AND version = ?`、`version = version + 1`、影响行数检查）、201 与投影渲染、GEN-01 字节确定性对新增产物成立。
**T5 Application / 真实环境**：新增 fixture `course-admin-ddl.sql`（含 `version` 列）、`course-admin-seed.sql`；新增 opt-in IT `CourseWriteBusinessConformanceIT`，断言见"完成门"。
**T6 文档与闸门**：资格报告新节、覆盖清单新组、状态文件、两条全量命令与 `git diff --check`。

## 允许修改的范围

- `sir-parser`：`Sir.g4`、AST 节点、`SirAstBuilder`、语法测试与 fixture。
- `sir-semantic`：Resolve/Type/Validate/Normalize、`ReferenceRole`、`SymbolKind`（如需）、Normalized 模型、语义测试。
- `sir-lowering-spring-boot`：新 Profile 策略（`SpringBootWritePolicy`）、声明/步骤/表达式/artifact 模型、Lowerer、IR 校验、Lowering 测试。
- `sir-generator-spring-boot`：新 Renderer（信封/异常基类/advice/patch DTO/条件更新）、既有 Renderer 的扩展、生成器契约测试、字节确定性与离线编译测试。
- `sir-project-graph`：`ArtifactRole` 追加新角色值（信封、异常基类、advice/配置）与 validator 复算。
- `sir-change`：新声明/步骤种类的分发（不得改既有 operation 语义）。
- `sir-toolchain-application`：`SpringBootProjectGraphInputFactory` 新种类映射、conformance 测试侧新增 IT 与 fixture。
- `docs/`：本工作单、路线图、资格与覆盖文档。

## 禁止范围（明确不做）

- 关联读取、`EXISTS` 过滤、根分页去重、关联 SQL 次数预算（BIZ-06，属 Q11）。
- 唯一性约束、非 versioned 写入的 `else` 触发路径、重复键错误码（独立工作）。
- 业务 DELETE、状态机/domainAction、归档语义。
- `in`/`isNull`/可选查询过滤/`when` 守卫（查询侧后续工作）。
- 路由模板与 `@PathVariable`（Q12）、资源式复数路由命名。
- 认证、会话、CSRF、角色与归属过滤（G5；BIZ-07）。
- G2 持久身份/多文件、G3 数据库迁移与 DDL 生成、G4 Docker/源码包、G6 Web 平台。
- **产品 schema 生命周期**：本单的 DDL 与 seed 仍是测试侧 fixture（`schemaSource=TEST_FIXTURE_DDL`）；产品 INITIALIZE/UPDATE 仍未实现，不得在文档中声称已实现。
- 新 POM 依赖、新 Maven 仓库条目、新 skip/exclude。
- 在 Renderer 里补语义逻辑（一切决策来自 Lowered IR）。

## 定向验证命令

```bash
# 各模块定向（按 T1–T4 阶段分别运行）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-parser -am test
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-semantic -am test
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-lowering-spring-boot -am test
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-generator-spring-boot -am test
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-project-graph,sir-change -am test

# 真实 MySQL + HTTP 写侧业务场景（opt-in；需要参考环境）
source /root/kcg-conformance/env.sh
mvn -B -Dmaven.repo.local=/root/.m2/repository -o \
  -pl sir-toolchain-application -am \
  -Dtest=io.kcg.sir.application.conformance.CourseWriteBusinessConformanceIT \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkcg.course-write-conformance.enabled=true \
  -Dkcg.conformance.work-parent=/root/kcg-conformance/work \
  -Dkcg.conformance.evidence-parent=/root/kcg-conformance/evidence \
  -Dkcg.conformance.maven-executable=/usr/bin/mvn \
  -Dkcg.conformance.maven-repo=/root/.m2/repository \
  -Dkcg.course-write-conformance.server-port=18081 test
```

两条全量闸门（与 Q9 相同，均需 BUILD SUCCESS）：

```bash
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true
```

## 完成门

1. **新语法/语义的诊断归属**：S1–S6 每条规则落在唯一阶段，反例断言精确码、数量与 span。
2. **GEN-02 式拒绝**：故意冲突的 Lowered 输入（缺 patch 计划、presence 属性缺失、条件更新计划与实体版本不一致、候选校验计划与实体约束不一致、信封 artifact 缺位）在 Generator 前被拒，并有"同一校验器通过合法输入"的配对对照。
3. **GEN-01**：新增产物下相同完整输入重复生成、切换 Locale/工作路径的字节一致。
4. **生成工程离线编译**：`GeneratedProjectOfflineCompilationTest` 覆盖新切片，`--offline` 编译通过（无新依赖）。
5. **BIZ-01（创建与读取）**：POST 创建返回 **201** + 声明的 `CourseDetail` 投影；独立 JDBC 核对插入值（含 `version=0`）与返回 id 一致；GET 返回 **200** + 同一投影；GET 不存在的 id 返回 **404** 且信封 `code=CourseNotFound`。
6. **BIZ-02（非法输入无写入）**：长度越界、缺必填、未知顶层字段、`changes` 内未知字段、identity 类型错误 → **400** + 信封含 `fields[].path` 与稳定 `code`；每种情况后独立 JDBC 核对**行数与行内容均未变化**。
7. **BIZ-03（PATCH 三态）**：`changes` 缺席字段保持旧值；显式 `null` 清空可空字段（`description`）；出现值替换；每次成功更新 `version` 按 1 递增；对非空字段显式 `null` → 400 且无写入。
8. **BIZ-04（版本冲突）**：顺序两次使用同一 `expectedVersion`：一次 200、一次 **409** 且数据库中该行的所有业务列与 version 未被第二次请求改动；**并发**（`CyclicBarrier` 同步启动两个线程）同一 `expectedVersion`：恰好一次 200、一次 409，version **只递增一次**，无丢失更新。
9. **空变更**：`changes` 缺失或为空对象 → 400 且无写入（按 SIR 声明的 `EmptyChange`）。
10. **候选校验的权威性（灵敏度对照）**：构造能通过边界注解但违反实体约束的输入时确实被拒（patch 路径为权威路径；create 路径以生成器契约测试 + patch 业务证据共同覆盖），且**删除候选校验后该用例会失败**（对照实验在测试内以"损坏的 Lowered 模型"形式表达）。
11. **写响应投影**：所有写侧成功的响应体只包含声明字段（无 Entity 泄漏），并断言 `version` 出现在投影中。
12. **场景证据口径**：报告记录 `schemaSource=TEST_FIXTURE_DDL`、`productInitializeImplemented=false`、`productUpdateImplemented=false`、advisory lock 取放、schema DROP 后缺席证明、workRoot 清理；证据文件与 Q9 同级（`evidence/course-write-*/`）。
13. **两条全量闸门** BUILD SUCCESS；与基线 **620 / 0 / 0 / 5** 的差量逐模块归因（新增测试必须全部来自本单）；**不得新增 skip 或 exclude**。
14. **文档同步**：资格报告新节（含有效边界）、覆盖清单新组、`PROJECT_STATUS.md`、路线图；`git diff --check` 无输出。

## 失败 / 跳过 / NOT_RUN 口径

- 定向或全量出现任何 fail/error（除既有 5 项 Windows junction skip 外）即本单未完成；不得以降 skip、加 exclude、加 `@Disabled`、改断言口径的方式"通过"。
- 真实环境不可用（容器、端口、凭据、schema 锁不可得）时业务场景记为 **`NOT_RUN`**，此时完成门 5–11 不成立、G1 不为完成；`NOT_RUN` 必须与通过明确区分，不得合并统计。
- 并发用例必须是"恰好一次成功"的确定性断言（条件更新语义保证结果与交错顺序无关）；若出现两次都成功或两次都 409，即为实现缺陷，不得改用重试掩盖。
- 不做全量之外的额外压测；顺序与并发各一组即可，不引入 flaky 计时断言。

## 需要负责人裁决的设计选择（D0–D16）

| # | 决策 | 推荐 | 备选与代价 |
|---|---|---|---|
| D0 | 是否把本单拆成两张（create+错误契约 / PATCH+version） | **不拆**：设计把 version 与三态绑在同一个 PATCH 契约里（02 §6、03 §7），拆开会出现"PATCH 已实现但版本语义未定"的中间态 | 拆分：单张更小，但 PATCH 契约需两次评审、Q11 顺延 |
| D1 | patch 载荷的 SIR 形态 | **`input X patch of Course { … }`**：显式 DTO 契约（02 §6 要求 Patch 有独立 DTO），同时决定三态与 HTTP 方法 | `expose command patch` + 自动三态：表面更小，但没有独立载荷契约，且与实体字段集合绑定 |
| D2 | patch 信封与目标 identity 的位置 | **Profile 冻结 `{ "<identity>": …, "expectedVersion": …, "changes": { … } }`**，identity 由载荷声明为顶层 | 路由模板 `PATCH /api/courses/{id}`：更 REST，但引入路由解析/`@PathVariable`/冲突诊断，属 Q12 |
| D3 | versioned 标记语法 | **字段级 `versioned`**（单字段标记，最多一个） | 实体级 `versioned by <field>`：需新增字段引用点与解析，收益不明显 |
| D4 | version 初始值 | **0**（INSERT 写入；首次成功 PATCH 后为 1） | 1：同样可行，但与"未更新过的行"语义不如 0 直观；需在 Profile 中固定并断言 |
| D5 | 条件更新实现 | **生成显式 `@Update` 语句**：`SET <列>=#{…}`（含 null 显式清空）、`WHERE id=? AND version=?`、`version = version + 1`、返回影响行数 | `UpdateWrapper`（SET 与 null 策略分散）/ MP `@Version` + `OptimisticLockerInnerInterceptor`（隐式拦截、原子性表面化不足） |
| D6 | `persist … else` 的适用范围 | **仅 versioned UPDATE**；INSERT 的 `else` 报"未支持"诊断 | 让 INSERT 的 `else` 映射重复键：需要唯一约束表面，属独立工作 |
| D7 | `error … status` 允许码集 | **400（默认）/ 404 / 409** | 放宽到任意码：与设计 §10 表不一致，且未实现语义的码会变成"声明了但不生效" |
| D8 | 空变更 400 的表达 | **S6 `.present` + `validate … else EmptyChange`**：语义写在 SIR 里 | Profile 隐式检查：违反"不得只在模板里补语义" |
| D9 | 候选校验的权威范围 | **对 patch 生成候选校验（权威路径）；create 亦生成同一机制（边界注解保留为快速路径）**；消息文本不作为契约 | 仅 patch 生成：create 与 patch 的校验权威不一致，后续跨字段约束要返工 |
| D10 | 错误信封与码词汇 | **统一信封 `{code, message, fields:[{path, code, message}]}`**；`code` = 声明错误名或 `REQUEST_INVALID`/`UNKNOWN_FIELD`；字段码 = `ConstraintKind` 词汇 | 各错误自定义响应体：客户端无法统一处理 |
| D11 | 写响应是否强制使用 `view` | **强制**（设计 02 §6：不把 Entity 当请求/响应体）；既有 `ENTITY_BODY` 兼容路径保留不动 | 允许 Entity 响应：与设计冲突 |
| D12 | 成功状态码来源 | **由 Lowering 按 workflow 形状决定**（insert → 201，其余 200），不加 `expose` 语法 | 新增 `expose command created` 之类语法：表面更大，收益低 |
| D13 | 新 fixture 与 Q9 产物 | **新增 `course-admin.sir` 与新 DDL/seed**，Q9 的 `course-catalog.sir`、IT 与证据**不改动** | 扩展现有切片：会改写 Q9 已验证的输入与其证据哈希 |
| D14 | 并发证明方式 | **顺序 stale（确定性）+ `CyclicBarrier` 双线程竞态（恰好一成功一 409）** | 只做顺序：无法证明"无丢失更新" |
| D15 | 跨模块最小扩展授权 | **允许**：`sir-project-graph` 追加角色值、`sir-change` 新种类分发、`sir-toolchain-application` Graph 映射（与 Q9 同模式） | 不改这些模块：新声明种类会让三处直接抛异常，无法生成 |
| D16 | 路由模板是否纳入 | **不纳入**，登记为 Q12 | 纳入：本单规模与风险显著上升，且设计未冻结资源式路由命名 |

## 实施期订正协议

执行中若发现推荐方案与冻结依赖、既有契约或参考环境冲突，按 Q9 的做法处理：**保持原意图、记录证据、写入本工作单的"实施期订正"节，并继续保持禁止范围不变**；不得为让用例通过而放宽完成门。

**与 D 裁决的差异（仅在实现细节层，意图不变）**：D9 的“create 亦生成同一机制”已落实为同一 `ValidationSupport` 候选校验；D10 的信封字段码词汇实际为 Bean Validation 的约束码（`notBlank|email|length|min|max|NotNull`），信封级码为 `INVALID_REQUEST`，未使用 D10 表格里的 `REQUEST_INVALID`/`UNKNOWN_FIELD` 命名——原因是 `ApiExceptionAdvice` 需要同时承载声明失败与解码失败，单一 `INVALID_REQUEST` 码更小且与 C5 的未知属性路径规则一致；若负责人要求与设计 02 的码名逐字一致，这是一个需要单独裁决的命名问题。

## 实施期订正（执行中发现，均已记录证据并保持禁止范围不变）

以下八项在 SPEC_REVIEW 阶段无法确定，是在真实 MySQL 运行、生成工程离线编译或冻结依赖核对中暴露的；前三项是**真实运行发现的生成代码缺陷**，不是设计偏好。

**C1 实体可空字段不能用 `java.util.Optional` 承载（产品缺陷，已修）。**
冻结依赖里既没有 MyBatis 的 `OptionalTypeHandler`，也没有 MyBatis-Plus 的等价物（`mybatis-3.5.19.jar` 只有 `OptionalUtil`，仅用于结果反射）。首次真实运行的直接证据：
- 插入路径：`IllegalStateException: Type handler was null on parameter mapping for property 'description' ... for the javaType (java.util.Optional)`（HTTP 500）。
- 条件更新路径：`TypeException: Error setting non null for parameter #2 ... Cannot convert class java.util.Optional to SQL type requested`（HTTP 500）。
因此**实体的可空字段一律映射为普通可空 Java 属性**（`String description`），`Optional<T>` 只保留在载荷与视图（DTO/View）里，两者在边界处显式转换：载荷→实体 `x.orElse(null)`，实体→视图 `Optional.ofNullable(x)`。这与冻结的传输契约不冲突：请求体与响应体仍是 `Optional` 语义（null ↔ 空值）。若维持原方案，任何可空列的写入都会 500，属于不可交付状态。

**C2 条件更新后响应必须报告已提交的版本（产品缺陷，已修）。**
`@Update ... version = version + 1` 只改数据库行，内存里被合并的实体仍是加载时的版本。首次运行证据：数据库行已是 `version=1`，响应体仍为 `"version":0`。修复：影响行数为 1 后显式 `candidate.setVersion(expectedVersion + 1L)`，与语句的语义（`WHERE version = expectedVersion` 后 `+1`）一致。

**C3 载荷约束写在 `Optional` 字段上会让 Hibernate Validator 抛错（产品缺陷，已修）。**
`@Size(...) private java.util.Optional<String> description;` 触发 `jakarta.validation.UnexpectedTypeException: HV000030: No validator could be found for constraint 'jakarta.validation.constraints.Size' validating type 'java.util.Optional<java.lang.String>'`（HTTP 500，而非 400）。修复：可空载荷字段的约束改写为**容器元素约束**（`private java.util.Optional<@Size(min = 0, max = 500) String> description;`），既保留校验又符合 Bean Validation 2.0 语义。

**C4 patch 信封的必填成员由 `@NotNull` + `@Valid` 落实（原方案描述不足，已按原意补齐）。**
S8c 写的"必填"在 SIR 里无法表达（载荷字段本身可空），因此落在生成的信封 DTO 上：`id` 与 `expectedVersion` 加 `@NotNull`，控制器对 patch 载荷加 `@Valid`。缺失成员的请求因此得到 400 且 `fields[].path` 分别为 `id` / `expectedVersion`；此前会先撞上版本预检报 409、或身份解析报 404。

**C5 未知变更属性的拒绝点从载荷收集改为解码器拒绝（原方案实现不足，已按原意修正）。**
S8b 要求"未知属性 400 且不写入"。若载荷用 `@JsonAnySetter` 收集未知属性并留待业务层判断，请求会先被 `validate ... else EmptyChange` 拦成 `EmptyChange`，未知属性名根本不会出现在响应里（首次运行证据：`{"code":"EmptyChange",...}` 而非带路径的字段错误）。因此改为：patch 载荷不再吞掉未知属性，交由 `fail-on-unknown-properties` 拒绝，`ApiExceptionAdvice` 用 Jackson 的引用路径拼出完整路径（`changes.code`），而不是只报属性名。

**C7 支撑文件必须是无条件的 target 产物（结构约束，已修）。**
把"校验原语只在存在写入能力时生成"作为设计会破坏 Q6 的变更影响规则：删除最后一个写能力时，`ValidationSupport` 在候选中消失但它不属于被删闭包，apply 直接以 `SIR-CHANGE-IMPACT-203 survivor Artifact removed from candidate (outside closure): lir://.../validation-support/project` 拒绝（首次证据：`DeleteFaultMatrixTest` 17 项失败，`result=Failure[failedStage=PLAN, disposition=NO_CHANGES]`）。修复：错误契约（信封/基类/advice）与校验原语、`application.yml` 全部无条件生成。随之更新的既有契约：`ToolchainProjectGraphIntegrationTest` 的计数 35/34 → 45/44、project 级 artifact 2 → 7（改为按 lowering 实际产物集合断言角色与 origin）、`GeneratorOutputContractTest` 三个 fixture 的冻结文件清单加入 `ValidationSupport.java`。

**C8 `SpringBootModelLowerer.java` 是反编译产物（既有状态，本单只做最小修复）。**
该文件在 `24eec6d` 中即为 CFR 反编译文本（文件头 `Decompiled with CFR 0.152.`、无注释、`this.` 前缀、冗余泛型），Q9 与本单的改动都在该文本上继续。本单一次批量文本编辑切坏了注释块，使文件内同时存在"截断副本 + 完整副本"，编译随即失败（`';' expected` at 228）。修复：保留从第二个 `package` 行到 EOF 的完整副本，丢弃截断副本；随后用 `sir-lowering-spring-boot` 全量测试（69 项 0 失败）与两个业务 IT（61/40 项 0 失败）证明行为等价。**本单不把该文件重写为手写源码**：重写会把 Q10 的审阅边界从"写侧新增语义"扩大到整文件，属独立清理项。

**C6 参考环境的 control JDBC URL 不得自带 schema（本机工具修复，未进入仓库）。**
`/root/kcg-conformance/provision-mysql.sh` 曾把 schema 写进 `KCG_CONF_CONTROL_JDBC_URL`，并被断言拒绝（"control JDBC URL must not select a schema"）。已改回不带 schema 的形式；同时脚本不再预建运行 schema（harness 自己做 CREATE/DROP 以证明"只删自己建的东西"），fixture DDL/seed 通过 `USE` 落在本次运行的 schema 内。

## 定向验证命令（Q10 实际执行）

```bash
# 1) 模块与生成器契约（含 GEN-01 确定性、离线编译）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-generator-spring-boot -am test

# 2) 真实参考环境业务场景（opt-in；schema 从 KCG_CONF_SCHEMA_NAME 派生）
source /root/kcg-conformance/env.sh
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-toolchain-application -am \
  -Dtest=WriteSliceBusinessConformanceIT -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkcg.write-conformance.enabled=true \
  -Dkcg.conformance.work-parent=/root/kcg-conformance/work \
  -Dkcg.conformance.evidence-parent=/root/kcg-conformance/evidence \
  -Dkcg.conformance.maven-executable=/usr/bin/mvn \
  -Dkcg.conformance.maven-repo=/root/.m2/repository test

# 3) Q9 场景回归（同一棵树，错误契约变更后必须重跑）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-toolchain-application -am \
  -Dtest=QuerySliceBusinessConformanceIT -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkcg.query-conformance.enabled=true \
  -Dkcg.conformance.work-parent=/root/kcg-conformance/work \
  -Dkcg.conformance.evidence-parent=/root/kcg-conformance/evidence \
  -Dkcg.conformance.maven-executable=/usr/bin/mvn \
  -Dkcg.conformance.maven-repo=/root/.m2/repository test

# 4) 两条全量闸门（见下节记录）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true
```

## 交接记录

**状态：AWAITING_ACCEPTANCE（2026-09-21）。**

修改范围（生产代码）：`sir-parser`（grammar/AST/builder：`versioned`、`error status`、`persist ... else`、`patch of`、`.present`）、`sir-semantic`（Resolve/Type/Validate/Normalize 新规则、`SymbolKind.VIEW`、13→18 个 ReferenceRole、`patchFieldBindings`）、`sir-lowering-spring-boot`（版本规格、patch 计划、条件更新、候选校验、错误信封 artifact、状态码、投影返回、`ProjectArtifact` 新变体）、`sir-generator-spring-boot`（信封/异常基类/advice/Jackson 严格、patch DTO 与变更集、候选校验渲染、条件更新渲染、201 与投影、可空边界转换、`PatchMapping`）、`sir-project-graph`（`ArtifactRole.VIEW`）、`sir-change`（新种类分发）、`sir-toolchain-application`（Graph 输入映射、业务 IT）。**零 POM、零依赖改动。**

测试与证据：
- 各模块在完成形式下全 0 fail 0 error 0 skip（application 的 5 项 skip 为 Windows junction）；Q10 新增测试类 `WriteGrammarTest` 14、`WriteSliceSemanticsTest` 21、`WriteSliceLoweringTest` 21、`GeneratorWriteSliceContractTest` 21，并把写侧切片并入确定性矩阵与离线编译矩阵。
- 真实 MySQL 8.4.11 + HTTP 业务场景 `WriteSliceBusinessConformanceIT`（**最终树**）：**verdict=PASSED，checksPassed=61 checksFailed=0**，`generatedFiles=22`（combined sha256 `575ad84d…`），`generatedProjectBuild=PASSED (exit 0)`，`schemaSource=TEST_FIXTURE_DDL`，`productInitializeImplemented=false`。证据：`/root/kcg-conformance/evidence/write-slice-1a0c4a9648b-38551/write-slice-report.txt`。
- Q9 场景回归 `QuerySliceBusinessConformanceIT`（**最终树**）：**verdict=PASSED，checksPassed=40 checksFailed=0**，`generatedFiles=15`（combined sha256 `1b3a7d6a…`）。证据：`/root/kcg-conformance/evidence/query-slice-1a0c4a9eb19-44308/query-slice-report.txt`。
- 两条全量闸门（最终树）：冻结形式与完成形式均 **BUILD SUCCESS**，合计 **702 run / 0 fail / 0 error / 5 skip**（各模块：parser 69、semantic 159、lowering-api 4、lowering-spring-boot 69、generator 67、project-graph 72、change 22、application 210/5 skip、cli 30）。
- 覆盖的 BIZ 断言：创建 201 + 投影 + 数据库版本 0；读取 200 / 未知 404；局部变更只动被点名列并 `version+1`；显式 null 清空、缺席字段保持；陈旧版本 409 且不写入；行 3 以存量版本 4 为准（`expectedVersion=0` 必须 409，`=4` 必须 200 且版本 5）；空变更集 400 `EmptyChange`；未知变更属性 400 且路径 `changes.code`；缺 `expectedVersion` / 缺 `id` 各 400 且路径点名；未知信封属性 400；不存在的身份 404；创建违反约束 400 且不落库；**并发同版本竞态恰好一成功一 409 且版本只 +1**；每次失败请求后都做整行指纹比对。

残余风险与未做项（本单明确不做）：路由模板与嵌套 `/courses/{id}`（Q12）、`in`/`isNull` 等过滤算子、关联读取/`EXISTS`（Q11）、其它 entity 的 DELETE、产品侧 `INITIALIZE`/`UPDATE`（G3，场景中 `schemaSource` 仍为 fixture）、字段错误码的跨模块冻结（当前 `notBlank|email|length|min|max|NotNull` 由 Profile 固定，尚未与主设计逐条对齐）。
