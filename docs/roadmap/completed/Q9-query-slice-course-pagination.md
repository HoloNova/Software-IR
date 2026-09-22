# 已完成工作单：Q9 G1 第一切片——Course 单实体分页查询端到端

- 状态：`DONE`（项目负责人 2026-09-21 回复“继续推进”，即验收通过并授权进入 G1 下一张工作单）
- 归档：2026-09-21 从 `docs/roadmap/ACTIVE_WORK.md` 移入 `docs/roadmap/completed/`
- 验收记录：负责人对执行结果回复“继续推进”。验收依据：
  1. 新语义（`view` 投影、`Page<T>`、`order by`、`Page … else`、`containsLiteral`）贯穿 Parser → Semantic → Lowering → Generator，每条规则落在唯一阶段并带精确码/数量/span 的反例；
  2. 生成工程在真实 MySQL 8.4.11 + HTTP 上完成 9 组请求、**40 项业务断言 0 失败**，含字面 `%`/`_` 的灵敏度对照、越界页、4 种非法分页 400、行指纹不变；
  3. 全量两个闸门 **BUILD SUCCESS**，合计 **620 run / 0 fail / 0 error / 5 skip**（554 → 620，增量 66 逐模块归因）；
  4. 三处实施期订正均已记录（分页不用 `PaginationInnerInterceptor`、声明错误只有 400 默认体、业务验证必须用 provisioned schema + advisory lock）。
- 所属阶段：**G1：单文件课程业务切片**（首张工作单）
- 方向来源：`docs/design/07-validation-and-direction-roadmap.md` 的 BIZ-05（查询部分）、GEN-01/02 与实现基线
- 前置工作：G0（Q1–Q8）已完成门闭合，见 `docs/qualification/CURRENT_QUALIFICATION.md` 第 0 节

## 当前资格基线（沿用，不由本单改变）

- 实现快照：`24eec6d`（`main` 与 `origin/main` 一致）。
- 默认离线 Reactor：冻结与完成两种形式均 `BUILD SUCCESS`；Surefire **554 run / 0 fail / 0 error / 5 skip**（Q9 开始时的基线；完成后为 620/0/0/5，见交接记录；5 项 skip 全为 Linux 上不适用的 Windows junction 用例）。
- 外部 MySQL conformance：MySQL 8.4.11 参考环境五场景 `QUALIFIED`（只对该参考环境元组成立）。
- 当前资格权威：`docs/qualification/CURRENT_QUALIFICATION.md` 第 0 节。

## 目标

在**现有单文件 SIR 链**上完成 G1 的第一个端到端切片：一个课程实体加一条分页查询，从 SIR 源代码贯穿 Parser、Resolve、Type、Validate、Normalize、Spring Boot Lowering、Generator、真实 MySQL 与真实 HTTP，并产出独立业务断言。

具体要证明的四件事：

1. **查询契约成立**：根实体单表查询支持显式排序、根分页（page/size/total）、响应投影和字面量字符串匹配（`%`/`_` 按字面量，不按通配符）。
2. **决策归属成立**：排序、分页默认值与边界、`LIKE` 转义策略、投影列、SQL 次数预算全部由 Lowering 决定并写入 Lowered IR；Generator 只渲染，不读 AST/Normalized/SIR，不访问磁盘。
3. **端到端成立**：生成的 Spring Boot 工程在真实 MySQL 上启动，HTTP 返回正确结果，非法分页参数返回 400 且不产生越界语义。
4. **反例成立**：新语义的非法输入在唯一所属阶段报出精确诊断；GEN-01 字节确定性对新增产物同样成立；GEN-02 的模型校验能挡住故意冲突的 Lowered 输入。

本单**只做查询切片**。G1 的其余内容拆为后续工作单（见"G1 内部顺序"），不得在本单提前实现。

### G1 内部顺序（本单只做 Q9）

| 工作单 | 范围 | 对应设计完成门 |
|---|---|---|
| **Q9（本单）** | Course 单实体 + `SearchCourses` 分页/投影/排序/字面量过滤查询端到端 | BIZ-05（查询与分页部分）、GEN-01、GEN-02 |
| Q10（未立项） | Course 写侧 CRUD、PATCH 三态、version 冲突、结构化字段错误 | BIZ-01、BIZ-02、BIZ-03、BIZ-04 |
| Q11（未立项） | 关联过滤（EXISTS 语义）、根分页不重复、关联批量读取与 SQL 次数预算；G1 收口 | BIZ-06 |

## 输入 SIR（本单的目标文本）

新 fixture `course-catalog.sir`（内容如下，作为本单的冻结输入）：

```text
sir 0.1

software CourseCatalog {
  metadata {
    displayName "课程目录";
    namespace "com.example.coursecatalog";
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
    }

    view CourseSummary from Course {
      field code: String;
      field name: String;
      field capacity: Int32;
    }

    input SearchCoursesInput {
      field keyword: String where notBlank, length(1, 50);
      field page: Int32;
      field size: Int32;
    }

    error InvalidPageParam;

    capability SearchCourses {
      input SearchCoursesInput;
      output Page<CourseSummary>;
      fails InvalidPageParam;
      requires readonly;
      expose query;

      workflow {
        find Course
          where item.name containsLiteral input.keyword
          order by code ascending, id ascending
          Page input.page, input.size else InvalidPageParam
          as courses;
        return courses;
      }
    }
  }
}
```

说明（均为本单要实现的语义，不是已实现能力）：

- `view <Name> from <Entity> { field ...; }`：响应投影声明；字段名必须绑定到 `from` 实体的同名字段，声明类型必须与被绑定字段类型一致，字段顺序即投影顺序。
- `Page<T>`：内建容器类型，只允许作为 `expose query` 能力的 `output`，其元素类型只允许 `view`。
- `find` 的两个新子句均属于该 `find` 步骤：`order by`（根实体字段白名单 + 方向白名单）与 `Page <pageField>, <sizeField> else <Error>`（分页来源与非法值错误）。
- 带 `Page` 子句的 `find` 结果变量类型为 `Page<view>`；不带该子句的 `find` 保持现状（`List<entity>`）。
- `containsLiteral`：字符串字面量包含匹配，`%`、`_`、`\` 按字面量处理。

## 勘察结论（2026-09-18，均直接读代码与运行记录得到，无假设）

### 1. 已实现且可直接复用

1. **单文件语法**：`sir 0.1` + `metadata`/`target`/`declarations`；声明类别为 `enum`、`entity`（`persistent` + `identity` + `field ... where 约束`）、`input`、`error`、`capability`（`actor`/`input`/`output`/`fails`/`requires`/`expose`/`workflow`）。见 `sir-parser/src/main/antlr4/io/kcg/sir/internal/Sir.g4`。
2. **类型系统**：`Boolean/Int32/Int64/Decimal/String/Uuid/Date/DateTime/Unit` 与 `Optional<T>`、`List<T>`、`Ref<E>`、声明类型；`TypeRules.isAssignable/isComparable/isNumeric/isBoolean`。
3. **约束**：`notBlank`、`email`、`length(min,max)`、`min`、`max`，含参数个数、适用类型、数值常量与 min/max 冲突校验（`ValidatePass`）。
4. **Workflow 步骤与校验**：`validate/load/find/create/update/persist/return`；create 必填字段完整性（`SIR-FLOW-001`）、identity 不可绑定或修改、`fails` 引用与升序（`SIR-FLOW-003`/`SIR-SYMBOL-001`）、`requires` 顺序、query⇒`readonly` 且 `atomic`⇔`readonly` 互斥（`SIR-VALID-001`）、`return` 唯一且在末尾（`SIR-FLOW-002`）。
5. **`find` 谓词**：`==`/`!=`/`<`/`<=`/`>`/`>=` + `and`/`or`/`not` + 括号；左端必须是 `item.<field>`；操作数类型必须相等（`SIR-TYPE-001`）；渲染为 MyBatis-Plus `LambdaQueryWrapper` 链。见 `WorkflowRenderer`。
6. **Resolve-once 与稳定身份**：名称只解析一次；`SymbolId` 不含序号（字符串编码软件名/种类/名称），`ReferenceRole` 带允许目标种类；Normalize 不改 AST、不重解析名称、不产生 `sir://unknown`。
7. **Lowering 决策面**：profile `V0_2`、路由 `/api/<kebab(capability)>`、query⇒GET、command⇒POST、输入绑定 `@ModelAttribute`/`@RequestBody`、事务 `NONE`/`REQUIRED`/`READ_ONLY`、actor 传输计划（`EXPLICIT_EXTERNAL_OR_LOCAL_FIXED` + `kcg-actor-local` + `@RequestAttribute actorId`）、`error` 一律映射为 HTTP 400、artifact 角色 `ENUM/ENTITY_MODEL/MAPPER/REQUEST_DTO/EXCEPTION/SERVICE/CONTROLLER`、`persist` 的 INSERT/UPDATE 由 provenance 决定。
8. **Lowered IR 校验（GEN-02 既有部分）**：IR/Profile 版本匹配、MavenProject 与 ApplicationMain 形态、路由/方法/事务一致性、artifact 归属唯一性与"每个 artifact 恰好一个文件"、workflow 末尾唯一 Return 等（`SpringBootIrValidation`）。
9. **Generator 边界**：纯渲染、无磁盘访问、只消费 `SpringBootLoweredModel`；已生成 `pom.xml`、`Application`、Enum、Entity、Mapper、DTO、Exception、Service、Controller；Locale/工作目录/换行/UTF-8 字节确定性已建立；完整生成工程可在冻结依赖下 `--offline` 编译。
10. **Application**：`ToolchainApplication.execute(ToolchainRequest)` 串起读取→编译→生成→Graph→预检→文件事务；canonical manifest 与 Bundle/CURRENT/Journal/显式恢复为现有能力。
11. **真实 MySQL/HTTP 基础设施（测试侧）**：conformance harness 已有自有 schema 生命周期（控制连接建/删，运行凭据无 CREATE/DROP 权限）、DDL fixture 应用、生成工程 Maven 构建、Spring Boot 进程启动、业务端点 readiness 轮询、HTTP 断言（readiness/assertion/invalid/secondary/removed）、DB 变更断言、schema 清单、凭据脱敏与证据封存，并在 MySQL 8.4.11 参考环境上产出 `QUALIFIED`。

### 2. 现有实现与 G1 目标的能力对照

| 能力 | 现状 | 本单处理 |
|---|---|---|
| 单实体 + 字段 + 约束 | 已实现 | 复用 |
| 单实体列表查询 | 已实现（`find` ⇒ `selectList`，全量返回） | 复用其谓词与渲染基础 |
| 查询过滤 `eq/ne/lt/le/gt/ge` + `and/or/not` | 已实现 | 复用 |
| 查询过滤 `containsLiteral`（字面 `%`/`_`/`\`） | 未实现 | **新增** |
| `in` / `isNull` / `when ... present` 条件省略 | 未实现 | 不在本单（登记为缺口；`present` 见 D10） |
| 根分页（page/size/total/越界/非法 400） | 未实现（无分页概念，无 count） | **新增** |
| 排序（白名单 + 追加唯一键） | 未实现 | **新增** |
| 响应投影（独立响应 DTO） | 未实现（响应是实体本身） | **新增** |
| 关联（`Ref`）读取与过滤 | 部分（`Ref<E>` 只落 `*_id` 列；无 EXISTS、无批量读取） | 不在本单（BIZ-06 ⇒ Q11） |
| 写侧 CRUD（create/load/update/persist） | 步骤与渲染已存在，但**未在真实 MySQL 上端到端验证** | 不在本单（BIZ-01/02 ⇒ Q10） |
| 字段校验（notBlank/length/min/max/email） | 已实现（DTO 注解 + `@Valid` ⇒ 400） | 复用 |
| 结构化字段错误响应（字段路径 + 稳定错误码） | 未实现（Spring 默认错误体） | 不在本单（BIZ-02 ⇒ Q10） |
| 未知请求字段拒绝 | 未实现（Spring Boot 默认忽略未知属性） | 不在本单（BIZ-02 ⇒ Q10） |
| PATCH 三态（缺席/显式 null/新值） | 未实现 | 不在本单（BIZ-03 ⇒ Q10） |
| version 乐观锁与 409 冲突 | 未实现（实体无 version 概念） | 不在本单（BIZ-04 ⇒ Q10） |
| 业务 DELETE | 未实现 | 不在 G1 完成门内（登记为缺口） |
| 错误到 HTTP 的多种映射 | 只有 400（`ErrorDeclaration` 固定 `BAD_REQUEST`） | 本单只用 400；404/409/401/403 属 Q10/G5 |

### 3. 必须一并处理的结构性依赖（当前会在新种类上直接失败）

- `sir-toolchain-application` 的 `SpringBootProjectGraphInputFactory.symbolKindOf` 与 `mapDeclarationRole` 对未登记种类**抛 `IllegalStateException`**。
- `sir-change` 的 `SemanticProjection.ofFullDeclaration` 对未登记种类**抛 `IllegalStateException`**；`PlannerCore` 的声明种类标签对未知种类返回 `UNKNOWN`。
- `sir-project-graph` 的 `ArtifactRole.DeclarationRole` / `ProjectRole` 是封闭枚举；canonical 编码用**名称**而非序号，追加值对既有文档兼容（旧文档仍可解码；新值在旧读者上报 `SIR-GRAPH-COMPAT-009`）。
- `SymbolIdFactory` 用字符串拼装身份（`sir://<software>/<kind>/<name>/...`），新增 `SymbolKind.VIEW` 与字段形状是追加式改动，不影响既有 ID。

因此本单**必须**触碰 `sir-project-graph`、`sir-change` 与 `sir-toolchain-application` 的少量生产代码；范围见"允许修改范围"，且只允许"新增种类分发 + 角色值追加"，不允许改变既有行为。

### 4. 明确不属于 G1、禁止在本单混入

- **G2**：持久 `declarationId`、多文件源清单、`import`/模块实例绑定、改名/移动保持身份、旧 Bundle 身份兼容。
- **G3**：产品化 `INITIALIZE`/`UPDATE`、schema history、漂移检测、DDL 中断与恢复。
- **G4**：`ReleaseManifest`、完整源码包、Docker/Compose、部署执行器。
- **G5**：登录/会话/CSRF/角色/数据归属、选课并发、名额事务（BIZ-07..13）。
- **G6**：Web 平台、任务队列、Worker 租约、发布协调。
- 既有的暂停方向：Redis Extension、第二 Target、Constraint VM、PSG `REFERENCES` 扩展、Snapshot V2、Change IR v0.7、Java 反向解析、新的增量编译系统。

**测试侧建库声明**：本单的 MySQL schema 由**测试夹具 DDL** 创建，harness 不是产品能力。本单**不得**以任何措辞把测试建库描述成产品已实现数据库初始化或更新；证据与文档必须写明 `schemaSource = TEST_FIXTURE_DDL`，产品 `INITIALIZE`/`UPDATE` 仍为 **未实现**（G3）。

## 允许修改范围

生产代码（仅限下列模块与内容）：

| 模块 | 允许改动 |
|---|---|
| `sir-parser` | Grammar 新产生式与新词法单元（`view`、`from`、`order`、`ascending`、`descending`、`Page`、`containsLiteral`）；新 AST 类型与 `AstDeclaration`/`AstStep` 等 sealed permits；`AstIdFactory` 结构路径；解析诊断（新语法不合法时）；Parser 测试 |
| `sir-semantic` | `SymbolKind.VIEW`；新 `ReferenceRole`；`SymbolIdFactory` 新身份形状；`ResolvePass`/`TypePass`/`ValidatePass`/`NormalizePass` 的新规则；`NormalizedView`/`NormalizedViewField`/分页与投影表达；`ReferenceSiteBindings` 契约；Semantic 测试 |
| `sir-lowering-spring-boot` | `SpringBootDeclaration.ViewDeclaration`；`SpringBootWorkflow.FindStep` 的排序/分页/投影决策；Lowering 侧分页与字符串匹配计划；`SpringBootIrValidation` 新规则；`SpringBootTargetProfile`（如需要新能力声明）；Lowering 测试 |
| `sir-generator-spring-boot` | 新 renderer（响应 DTO、分页响应、分页运行时配置）；`ServiceRenderer`/`ControllerRenderer`/`WorkflowRenderer` 的分页与投影渲染；`GenerationEngine` dispatch；Generator 测试 |
| `sir-project-graph` | `ArtifactRole.DeclarationRole`/`ProjectRole` 追加值；canonical 排序与规则对新角色的处理；Graph 测试 |
| `sir-change` | 新声明种类在 `SemanticProjection` 与 `PlannerCore` 中的分发；Change 测试 |
| `sir-toolchain-application` | `SpringBootProjectGraphInputFactory` 的新种类映射；conformance 测试侧（新 DDL/seed 夹具、新场景与断言、证据记录）；Application 测试 |
| `docs/` | 本工作单、`CURRENT_QUALIFICATION.md`、`TEST_COVERAGE_INVENTORY.md`、`PROJECT_STATUS.md`、`REMAINING_WORK.md` |

允许新增测试资源：`course-catalog.sir` 按现有惯例落到相关模块的 `src/test/resources/valid/`。

不在允许范围：`kcg-cli` 生产代码（除非全量闸门证明必须，且先报告）、`sir-lowering-api`（除非证明必须）、任何 POM 与依赖变动。

## 明确禁止

1. 未获批准前修改任何生产代码或测试代码（本单已执行完成并进入 `AWAITING_ACCEPTANCE`，该限制现适用于后续新工作单）。
2. 实现 PATCH 三态、`expectedVersion`、乐观锁、业务 DELETE、`in`/`isNull`、`when present`（除 D10 另行裁定）、关联过滤/EXISTS/批量读取。
3. 实现 G2 持久身份与多文件、G3 数据库迁移、G4 交付包、G5 认证与选课、G6 Web 平台。
4. 在模板（renderer）里补业务语义；Generator 读取 AST/Normalized/SIR/SymbolTable 或访问磁盘。
5. 绕过 Parser/Semantic/Lane/Lowering 边界，或为适配示例文本而放宽既有校验。
6. 按名称重新解析语义；破坏 Resolve-once 或稳定 binding。
7. 删除、弱化、跳过既有测试；新增 surefire 排除或 `@Disabled`；通过 `-Dmaven.test.failure.ignore` 制造绿色结论。
8. 删除或覆盖工作区既有改动与受保护注释；`git reset`/`restore`/`checkout`/`clean`/`stash`；提交或推送。
9. 把测试侧 DDL/seed 称为产品数据库初始化，或把 harness 的 `QUALIFIED` 当作业务切片证据。
10. 扩大范围到无关模块或顺手重构。

## 每个新增语义与诊断的唯一所属阶段

| 规则 | 唯一阶段 | 诊断码（新增，沿用既有前缀约定） |
|---|---|---|
| 新语法形式不合法（缺 `as`、子句顺序错、`view` 缺 `from`） | Parser | 既有 `SIR-PARSE-*` 家族 |
| `view` 的 `from` 目标不存在或不是 entity | Resolve | `SIR-SYMBOL-*` |
| `view` 字段名在 from 实体上不存在 | Resolve | `SIR-SYMBOL-*` |
| `view` 字段声明类型与被绑定实体字段类型不一致 | Type | `SIR-TYPE-001` |
| `order by` 字段不是根实体字段 / 类型不可比较 | Type | `SIR-TYPE-001` |
| `Page<p>, <s>` 的 p、s 类型不是 `Int32` | Type | `SIR-TYPE-001` |
| `containsLiteral` 操作数不是 `String`×`String` | Type | `SIR-TYPE-001` |
| `Page<T>` 的 `T` 不是 `view` | Type | `SIR-TYPE-001` |
| `Page` 出现在非 `expose query` 能力的 `output` | Validate | `SIR-VALID-001` |
| 带 `Page` 子句的 `find` 根实体 ≠ `Page` 元素 `view` 的 `from` 实体 | Validate | `SIR-VALID-001` |
| `Page` 的 page 字段与 size 字段为同一字段 | Validate | `SIR-VALID-001` |
| `Page ... else E` 的 `E` 未在该能力 `fails` 中声明 | Validate | `SIR-FLOW-003` |
| `order by` 重复字段 | Validate | `SIR-VALID-001` |
| 能力 `output` 为 `Page<V>` 但工作流没有产生 `Page<V>` 的步骤（找不到对应 `find`） | Validate | `SIR-VALID-001` |
| `view` 字段带 `where` 约束 | Validate | `SIR-VALID-001`（响应 DTO 无校验语义，见 D9） |
| 带 `Page` 子句的 `find` 结果变量被当作 `List`/实体使用 | Type | `SIR-TYPE-001` |
| `Page` 输出与查询 GET + `readonly` + 事务一致性（Profile 能力） | Lowering | `SIR-LOWER-FEATURE-001` |
| 分页默认值/边界与 `LIKE` 转义策略未落实（Lowered 模型自校验） | Lowering | `SIR-LOWER-IR-001` |
| 投影列不是根实体的持久列 / 分页列与投影冲突 | Lowering | `SIR-LOWER-TYPE-001` |
| 生成前模型自洽（路由/参数/artifact 一一对应、每个 artifact 恰好一个文件） | Lowering（`SpringBootIrValidation`） | `SIR-LOWER-IR-001` |

排序、分页默认值与边界（page 默认 1、size 默认 20、size∈[1,100]、page∈[1,10000]、不自动夹紧）与 `LIKE` 转义策略都是 **Lowering 决策**，写入 Lowered IR 并由 Lowering 自校验，不得留给 Generator 或模板临时决定。

## 测试先行顺序（每步先写失败测试，再改生产代码）

1. **Parser**：新语法正例解析两次产生相等 AST 与稳定 `AstNodeId`；反例（缺 `as`、`order by` 位置错、`view` 缺 `from`、`Page` 缺 `else`）报解析诊断且带精确 span。
2. **Resolve / Type / Validate**：按上表逐条先写反例测试（精确码、数量、span），确认 RED，再实现规则；另加重名/重复绑定、`fails` 未声明的反例。
3. **Normalize**：断言归一化模型携带 view 字段到实体字段的稳定绑定、根实体、排序键与方向、分页来源字段与错误符号、`containsLiteral` 的匹配节点；断言不出现按名称再解析的痕迹。
4. **Lowering**：断言 Lowered 查询计划（GET、`READ_ONLY`、投影列顺序、排序列 + 追加唯一键、分页默认与边界、`LIKE` 转义策略、SQL 次数预算 = count + page 两次）与新增 artifact/角色；再写 GEN-02 反例：手工构造冲突的 Lowered 模型，断言在 Generator 之前失败。
5. **Generator**：断言新产物集合与内容契约（响应 DTO、分页响应、分页运行时配置、service 的 `selectPage` 与转义 helper、controller 的 `@ModelAttribute` 与分页校验）；然后断言 GEN-01：同输入在不同 Locale 与工作目录下路径、顺序、字节完全一致。
6. **端到端（Application + conformance）**：先让生成工程在冻结依赖下离线编译通过，再跑真实 MySQL + HTTP 场景，最后补 harness 自身的失败路径（seed 缺失、DDL 不匹配、断言期望与实际不符）。

## 真实 MySQL / HTTP 验证

复用现有 conformance harness（测试侧，opt-in，默认构建不跑 `*IT`）：

- 新增场景（拟名 `BIZ-COURSE-SEARCH`）：自有 schema → 应用 Course 表 DDL → 应用 seed 数据 → 生成并应用工程 → Maven 构建 → 启动应用 → readiness → HTTP 断言 → DB 断言 → 清理 → 证据封存。
- **DDL 与 seed 均为测试夹具**，与 `course-catalog.sir` 的实体映射一一对应；产物中必须记录 `schemaSource = TEST_FIXTURE_DDL`。产品 `INITIALIZE`/`UPDATE` 仍为未实现。
- **独立业务断言**（期望值由人手写在场景定义里，不由生成产物推导）：seed 至少包含中文课程名、含字面量 `%` 的名称、含字面量 `_` 的名称、以及一条同名不同 code 的记录用于验证 tiebreaker。
- 断言项：
  1. `keyword` 命中项集合、顺序（code 升序 + id 升序）与 `total` 正确；
  2. 中文查询命中正确，`total` 与 items 数一致（含越界页时 items 为空但 total 不变）；
  3. 字面量 `%`、`_` 匹配按字面量：**并设灵敏度对照**——同一 seed 下若不做转义，未转义查询会多命中记录，测试必须用该对照证明转义真实生效（不允许"永远通过"的假绿）；
  4. 不同分页参数（size=1/2、第二页、超出最后一页）行为正确；
  5. 非法分页参数（`page=0`、`page=10001`、`size=0`、`size=101`）返回 **400**，且不返回分页数据；
  6. `unknown` 查询参数不改变结果（本单不要求拒绝未知参数，见 D12 的口径）。
- **不允许**用单元测试、生成工程离线编译或 harness 自身的 `QUALIFIED` 代替上述逐条业务断言；报告必须分别列出"模块测试""生成工程编译""真实 MySQL/HTTP"三类证据。

## 完成门

1. `course-catalog.sir` 在 Parser → Normalize 全绿，且所有新增诊断在唯一所属阶段报出，反例断言含精确码、数量与 span。
2. Normalized 与 Lowered 模型均携带排序、分页、投影与字面量匹配决策；Generator 未读取 AST/Normalized/SIR/SymbolTable，未访问磁盘（沿用既有边界闸门，必要时扩展其禁止集合）。
3. 生成工程在冻结依赖下离线编译通过，且包含响应 DTO、分页响应、分页运行时配置与分页查询实现。
4. GEN-01 对新增产物成立：不同 Locale 与工作目录下路径、顺序、字节完全一致。
5. GEN-02 对新增决策成立：故意冲突的 Lowered 输入在 Generator 之前被拒绝，且有灵敏度对照。
6. `BIZ-COURSE-SEARCH` 场景在 MySQL 8.4.11 参考环境上通过，六类断言全部有当次输出；场景报告记录 `schemaSource = TEST_FIXTURE_DDL`。
7. 定向模块测试 0 fail / 0 error；`sir-project-graph`、`sir-change`、`sir-toolchain-application` 既有测试无回归。
8. 两条全量闸门 BUILD SUCCESS；与基线 **554 / 0 / 0 / 5** 的差量逐条归因（新增测试必须全部来自本单）。
9. 文档同步：本工作单交接记录、`CURRENT_QUALIFICATION.md` 新增一节、`TEST_COVERAGE_INVENTORY.md`、`PROJECT_STATUS.md`、`REMAINING_WORK.md`；并如实登记未实现项（PATCH/version/DELETE/关联/`when present`/`in`/`isNull`/产品 `INITIALIZE`）。
10. `git diff --check` 无输出；`git status --short` 报告实际工作树状态。

## 失败、跳过与 NOT_RUN 口径

- 任何模块出现 fail 或 error 即为本单未完成，不得以"大部分通过"结项；唯一允许保留的 skip 是既有的 5 项 Windows junction 用例，且不得新增 skip。
- conformance 场景为 opt-in。参考环境不可用时如实记 **`NOT_RUN`**（不是 PASS，也不是 QUALIFIED），并说明阻断原因；本单的 G1 完成门在 `NOT_RUN` 下**不成立**。
- 现有五场景矩阵的结果不得复用于本单结论；必须给定当次运行的场景列表与终端结果。
- 未覆盖项（Windows 平台、第二卷/挂载点、thin JAR/发行包、完整 CLI 写生命周期）沿用既有登记，不在本单改变状态。

## 验证命令

```bash
# 定向：新语义所在的五个模块（先跑，逐步扩大）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean \
  -pl sir-parser,sir-semantic,sir-lowering-spring-boot,sir-generator-spring-boot \
  -am test

# 定向：跨模块分发与既有契约回归
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean \
  -pl sir-project-graph,sir-change,sir-toolchain-application -am test

# 真实 MySQL + HTTP（先 source 出参考环境变量；凭据不入库）
source /root/kcg-conformance/env.sh
mvn -B -o -Dmaven.repo.local=/root/.m2/repository \
  -pl sir-toolchain-application -am \
  -Dkcg.conformance.enabled=true \
  -Dtest=SpringBootTargetConformanceIT \
  -Dkcg.conformance.work-parent="$KCG_CONF_WORK_PARENT" \
  -Dkcg.conformance.evidence-parent="$KCG_CONF_EVIDENCE_PARENT" \
  -Dkcg.conformance.schema-name="$KCG_CONF_SCHEMA_NAME" \
  -Dkcg.conformance.maven-executable="$(command -v mvn)" \
  -Dkcg.conformance.maven-repo=/root/.m2/repository \
  -Dkcg.conformance.expected-mysql-server-uuid="$KCG_CONF_SERVER_UUID" \
  -Dkcg.conformance.server-port="$KCG_CONF_SERVER_PORT" test

# ① 冻结形式（失败即停）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify
# ② 完成形式（忽略失败，使 Reactor 走到 kcg-cli）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true

git diff --check
git status --short
```

参考环境取值以 `/root/kcg-conformance/env.sh` 的当次内容为准（MySQL 8.4.11，宿主端口 33306，应用端口 18080）；更换 MySQL 版本、宿主文件系统或端口都必须重新记录环境元组。

## 需要负责人裁决的事项（推荐项已标注）

- **D1（关键）响应契约是否包含投影声明 `view`**：
  - **(a) 推荐**：包含。新增 `view <Name> from <Entity>` 声明与响应 DTO 产物，`SearchCourses` 返回 `Page<CourseSummary>`。理由：设计 02 §6/§7 要求 ListItem/Detail 拥有独立 DTO 契约，BIZ-01 的"返回投影"需要有投影可返；Q10 的 Create/Patch/Detail DTO 可沿用同一模式。
  - (b) 不包含：本单返回 `Page<Ref<Course>>`（实体本身作为 items），把 DTO 声明推给 Q10。改动更小（可省掉 `SymbolKind.VIEW` 与 Change/Graph 的声明种类分发），但 Q10 将改变查询响应形状并产生返工。
- **D2 `Page<T>` 的元素类型**：推荐只允许 `view`（保持"响应不直接暴露实体"的一致性）；备选是同时允许 `entity`。
- **D3 分页实现方式（已被「实施期订正 1」取代）**：推荐 MyBatis-Plus `PaginationInnerInterceptor` + `mapper.selectPage(Page, Wrapper)`（一次调用即 count + page 两次 SQL，与设计 03 §8 的预算一致）；禁止 `.last("LIMIT …")` 之类拼接。
  - 订正后的实际实现：`selectCount` + `selectList(... .last("LIMIT offset, size"))`，`offset = (page-1)*size`，两者都是 `mybatis-plus-core` 既有 API；`LIMIT` 的两个值是已通过生成守卫校验的 `int`，不拼接任何字符串参数。理由与证据记录在本文档开头的「实施期订正 1」。
- **D4 分页运行时配置落点**：
  - **(a) 推荐**：新增项目级 artifact（如 `persistence/MybatisPlusConfiguration.java`）并在 `ArtifactRole.ProjectRole` 追加一个值；职责清晰、条件生成（仅有分页查询时才出现）。
  - (b) 注入现有 `Application.java`：少一个新 `ProjectRole`，但把持久化配置混进应用主类（该类已承担 actor 传输）。
- **D5 排序契约**：推荐同时要求"`order by` 显式声明 + Lowering 追加根实体 identity 升序作为最终 tiebreaker（若已显式声明则不重复）"，并把它写入 Lowered IR；只允许根实体字段与 `ascending`/`descending` 两种方向。
- **D6 分页默认值与边界**：推荐由 Profile/Lowering 固定（page 默认 1、size 默认 20、size∈[1,100]、page∈[1,10000]、非法不夹紧），不在 SIR 里新增约束语法。若希望 SIR 可声明这些数值，需要额外语法，属扩大范围。
- **D7 非法分页的错误通路**：推荐在 `find` 上用 `Page <pageField>, <sizeField> else <Error>` 显式声明错误符号（复用既有 `fails` 与 400 映射）；备选是 Lowering 向 DTO 注入 `@Min/@Max`，但会把能力级分页边界泄漏到共享输入 DTO。
- **D8 `containsLiteral` 的 SQL 落法**：
  - **(a) 推荐**：生成的代码对绑定值转义 `\`、`%`、`_`，并用显式 `ESCAPE '\'` 的 `LIKE`（MyBatis-Plus `.apply("<col> LIKE {0} ESCAPE '\\\\'", escaped)`），不依赖服务端默认 `sql_mode`；转义 helper 生成为该 service 的私有静态方法（不新增项目级工具 artifact）。
  - (b) 用 `.like(col, escaped)` 依赖 MySQL 默认反斜杠转义：代码更短，但转义语义依赖服务端配置，Lowering 的决策性变弱。
- **D9 `view` 字段是否允许 `where` 约束**：推荐**禁止**并由 Validate 报错（响应 DTO 没有请求校验语义，保留会让读者以为响应会被校验）；备选是允许但忽略（不推荐，属静默行为）。
- **D10 `when <expr> present` 条件谓词是否并入本单**：
  - **(a) 推荐**：不并入。本单的 `keyword` 为必填，`when present` 与"可选过滤"在 Q10/Q11 立项时一并设计，并在文档登记为缺口。
  - (b) 并入：一次闭合"可选参数条件省略"，但会显著扩大 Parser/Semantic/Normalize/Lowering/Generator 改动面与反例矩阵。
- **D11 新 fixture 与场景资源的落位**：推荐沿用现有惯例（按模块复制 `src/test/resources/valid/course-catalog.sir`，DDL/seed 落在 `sir-toolchain-application/src/test/resources/conformance/mysql/`），**零 POM 改动**；备选是新建共享测试资源目录（需要 POM 改动，属扩大范围）。
- **D12 场景扩展方式与未知参数口径**：推荐新增独立场景 `BIZ-COURSE-SEARCH` 与第二份 DDL/seed fixture，不改动现有五场景的语义；本单**不**要求拒绝未知查询参数（Spring 的 `@ModelAttribute` 会忽略），如需拒绝请在 D1x 追加裁定，因为它会牵动 Q10 的 BIZ-02 错误契约。
- **D13 跨模块最小扩展的授权确认**：因新增声明种类，本单必须触碰 `sir-project-graph`（角色值追加）、`sir-change`（种类分发）、`sir-toolchain-application`（Graph 输入映射）的少量生产代码。推荐**允许**，但严格限定为"新增种类分发 + 角色值追加"，不得改变既有行为；若负责人选择 D1(b)，此范围可缩减。

## 报告与文档同步

- 执行证据写入本工作单的"交接记录"与 `docs/qualification/CURRENT_QUALIFICATION.md` 新增一节（命令、run/fail/error/skip、场景结果、环境元组、schemaSource）。
- `docs/qualification/TEST_COVERAGE_INVENTORY.md` 增加本单覆盖组与未覆盖项。
- `docs/PROJECT_STATUS.md` 只在有当次证据后更新能力结论；未实现项（PATCH/version/DELETE/关联/产品 INITIALIZE）保持"未实现"。
- `docs/roadmap/REMAINING_WORK.md` 增加 G1 增量序列指针（Q9/Q10/Q11）。
- 面向负责人的聊天报告保持简短：只报完成项、当次命令与计数、未验证范围、以及是否需要裁决。

## 交接记录（2026-09-18 执行完成）

- **状态**：`IN_PROGRESS` → `AWAITING_ACCEPTANCE`（实现、测试、真实环境验证与文档均已完成，等待负责人验收；未提交 Git）
- **裁决执行**：D1(a)–D13 按推荐项执行；其中 D3（分页实现）与 D7（错误响应体）按“实施期订正 1/2”调整，schema 与 advisory lock 按“实施期订正 3”调整，保持原意图不变。

### 修改文件

生产代码（7 个模块，均无 POM/依赖改动）：

| 模块 | 文件 |
|---|---|
| `sir-parser` | `Sir.g4`、`AstDeclaration/AstNode/AstTypeRef/AstFindStep/AstBinaryOperator`、新增 `AstViewDecl/AstViewField/AstFindOrder/AstFindOrderKey/AstFindPage/AstPageTypeRef`、`SirAstBuilder` |
| `sir-semantic` | 新增 `PageType`、`NormalizedView`、`NormalizedViewField`；`SirType`、`DeclaredType`、`SymbolKind`、`SymbolTable`、`ReferenceRole`、`SymbolIdFactory`、`ResolvePass`、`TypePass`、`ValidatePass`、`NormalizePass`、`NormalizedDeclaration`、`NormalizedStep` |
| `sir-lowering-spring-boot` | 新增 `profile/SpringBootQueryPolicy`；`SpringArtifact`、`ProjectArtifact`、`SpringBootDeclaration`、`SpringBootWorkflow`、`SpringExpression`、`TransportPlan`、`SpringBootModelLowerer`、`SpringBootInputValidator`、`SpringBootIrValidation` |
| `sir-generator-spring-boot` | 新增 `ViewDtoRenderer`、`PageResponseRenderer`；`WorkflowRenderer`、`ServiceRenderer`、`ResponseTypeRenderer`、`TypeRenderer`、`ExpressionRenderer`、`GenerationContext`、`GenerationEngine` |
| `sir-project-graph` | `ArtifactRole`（追加 `VIEW_DTO`、`PAGE_RESPONSE`）、`ProjectGraphValidator` |
| `sir-change` | `SemanticProjection`、`PlannerCore`（新声明种类分发） |
| `sir-toolchain-application` | `SpringBootProjectGraphInputFactory`（新种类映射） |

测试与 fixture（新增）：`sir-parser` `QueryGrammarTest` + `GrammarBoundaryTest` 追加 + `test/resources/valid/course-catalog.sir`；`sir-semantic` `QuerySliceSemanticsTest`；`sir-lowering-spring-boot` `QuerySliceLoweringTest` + `test/resources/valid/course-catalog.sir`；`sir-generator-spring-boot` `GeneratorQuerySliceContractTest` + `test/resources/valid/course-catalog.sir`；`sir-toolchain-application` `QuerySliceBusinessConformanceIT`、`test/resources/valid/course-catalog.sir`、`test/resources/conformance/mysql/course-catalog-{ddl,seed}.sql`；并扩展 `TypedReferenceSiteContractTest`、`LoweredIrHardeningTest`、`GeneratedProjectOfflineCompilationTest`、`GeneratorDeterminismMatrixTest`。

### 实际运行的命令与结果

| 命令 | 结果 |
|---|---|
| `mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-parser -am test` | 55 / 0 / 0 / 0 |
| 同上 `-pl sir-semantic` | 131 / 0 / 0 / 0 |
| 同上 `-pl sir-lowering-spring-boot` | 48 / 0 / 0 / 0（含 `sir-lowering-api` 4） |
| 同上 `-pl sir-generator-spring-boot` | 48 / 0 / 0 / 0 |
| 同上 `-pl sir-project-graph,sir-change` | 72 / 0 / 0 / 0 与 22 / 0 / 0 / 0 |
| 同上 `-pl sir-toolchain-application -am test` | 210 / 0 / 0 / 5（5 skip = Windows junction） |
| `source /root/kcg-conformance/env.sh && mvn … -pl sir-toolchain-application -am -Dtest=io.kcg.sir.application.conformance.QuerySliceBusinessConformanceIT -Dkcg.query-conformance.enabled=true … test` | `verdict=PASSED`、**40 项断言 0 失败**；证据 `evidence/query-slice-*/query-slice-report.txt` + `logs/`（应用日志、Maven 日志） |
| 全量① frozen：`mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify` | **BUILD SUCCESS**，十模块全部完成 |
| 全量② completion：`… -o clean verify -Dmaven.test.failure.ignore=true` | **BUILD SUCCESS**，十模块全部完成 |
| 两者合计计数 | **620 run / 0 fail / 0 error / 5 skip**（554 → 620，增量 66：parser +12、semantic +28、lowering +16、generator +10；无新增 skip） |
| `git diff --check` | 无输出（无尾随空白/冲突标记） |

### 场景验证细节（可复现）

- 参考环境：MySQL 8.4.11（容器 `kcg-conformance-mysql`，宿主端口 33306）、应用端口 18080、离线仓库 `/root/.m2/repository`、Java 21.0.12；schema 取 `KCG_CONF_SCHEMA_NAME=kcg_conf_run`。
- 流程：SIR 编译（0 诊断）→ 生成 10 个文件（combined sha256 `9a2f5c33…`）→ 生成工程离线 `mvn clean verify` 成功 → 启动 fat JAR → readiness 200 → 9 组 HTTP 断言 + 独立 JDBC 核对 → 停进程 → 删 schema 并证明缺席 → 释放 advisory lock → 删 workRoot。
- 关键断言：第 1/2 页顺序与 `total=4`；越界页空且 `total` 不变；省略参数回落 1/20；`%` 只命中 2 条（不转义会命中 7 条）；`9_9` 不命中 `919`；响应只含 `code`/`name`/`capacity`；`page=0/10001`、`size=0/101` 与空/缺关键字均 400 且无记录；行指纹 7 → 7。

### 未完成项与残余风险

- **G1 未完成**：写侧 CRUD 业务验证（BIZ-01/02）、PATCH 三态（BIZ-03）、`version` 乐观锁与 409（BIZ-04）、关联读取与 `EXISTS` 过滤（BIZ-06）、业务 DELETE、字段级结构化错误码仍未实现/未验证；登记为 Q10/Q11。
- 分页实现未使用 `PaginationInnerInterceptor`（订正 1）；`LIMIT` 子句为字符串拼接（拼接值仅为已校验的 `int`）。
- `Page<T>` 只允许 `view` 元素；`containsLiteral` 只支持实体字符串字段 vs 输入字段；无 `in`/`isNull`/`when present`、无关联排序、无游标分页。
- 业务场景为 opt-in IT：**默认全量 620 计数不含它**；环境不可得时如实 `NOT_RUN`（此时完成门不成立）。
- 证据封存采用运行期“流式重编护 + 最终扫描”之外，本单 IT 只用流式重编护（与 harness 的 `SpringApplicationProcess` 泵一致）；未跑 `EvidenceSecretScanner` 对日志再做一次终扫（日志中的凭据片段已被重编护，已见 `***REDACTED***`）。
- 本轮未提交 Git（按工作单约定），生产改动集中待验收。

## 报告与文档同步（已完成）

- `docs/qualification/CURRENT_QUALIFICATION.md`：新增 1.9 节；第 2 节模块表与总数 554 → 620；第 0 节加“本节之后的进展”指针与数字口径说明。
- `docs/qualification/TEST_COVERAGE_INVENTORY.md`：新增 Q9 覆盖组与未覆盖边界；第 1 节模块行更新。
- `docs/PROJECT_STATUS.md`：能力、全量计数与当前优先工作节更新。
- `docs/roadmap/REMAINING_WORK.md`：G1 增量序列中 Q9 标为待验收。
- `docs/roadmap/README.md`：当前工作单状态改为待验收。
