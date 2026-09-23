# 已完成工作单：Q11 G1 第三切片——关联过滤（存在性语义）与关联读取（批量、预算）

- 状态：`DONE`（项目负责人 2026-09-23 回复“确认”，即验收通过）
- 归档：2026-09-23 从 `docs/roadmap/ACTIVE_WORK.md` 移入 `docs/roadmap/completed/`
- 验收记录：负责人对执行结果回复“确认”。验收依据：
  1. 两处新 SIR 表面（存在性谓词 `any(<实体>, <条件>)`、view 嵌套 view）贯穿 Parser → Semantic → Lowering → Generator，每条规则落在唯一阶段并带精确码/数量/span 的反例（Semantic 17 项 + Lowering 13 项 + Generator 6 项 + Parser 10 项）；
  2. 生成工程在真实 MySQL 8.4.11 + HTTP 上完成四组请求、**36 项业务断言 0 失败**：`total` 按根实体算且每个根只出现一次、只被两条不同报名分别满足条件的课程不入选、根过滤不等于投影过滤、每个关联一条批量读取（三十条关联行语句数不变）、一次请求恰为一个只读事务、越界页 2 条读取、非法页 400 且 0 读取、前后整行指纹不变；
  3. 语句次数预算为**确定性实测**：4（2 关联）/ 4 / 2（空页）/ 0（非法页），计数取自 `performance_schema` 摘要增量、原始 SQL 取自 general log，两者都经 control 账号读取；
  4. Q9 与 Q10 场景在同一棵树上回归 **40/40** 与 **61/61**（`WorkflowRenderer` 分页路径被重构后既有切片行为未变）；
  5. 全量两个闸门 **BUILD SUCCESS**，合计 **749 run / 0 fail / 0 error / 5 skip**（702 → 749，差量 47 逐模块归因于本单），无新增 skip/exclude；
  6. 八项实施期订正（C1–C8）均已记录证据与口径依据。
- 验收裁决记录（2026-09-23，负责人回复“确认”，未要求在验收中返工）：
  - C1–C8 全部接受，含 C4（预算按“投影使用点”计数）、C5（本目标要求带关联投影的 find 必须分页）、C7（计数口径从 general log 改为摘要增量 + general log 仅作原始 SQL 证据）；
  - 本单的范围外事项继续作为独立项保留：错误信封码名与设计 02 的逐字对齐、`SpringBootModelLowerer.java` 反编译形态重写（两项均为既有独立项）；
  - 新增独立项登记：非分页 find 的关联读取（C5）、深度 3 层及以上嵌套、预算按关系计划去重的替代口径、`in`/`isNull`/when-present 可选过滤/按关联字段排序。
- 所属阶段：**G1：单文件课程业务切片**（第三张工作单；G0 完成门见 `docs/qualification/CURRENT_QUALIFICATION.md` 第 0 节）
- 前置状态：Q10（Course 写侧）已完成、验收并归档（见 [`Q10-course-write-slice.md`](Q10-course-write-slice.md)），全量 702/0/0/5
- 方向来源：`docs/design/07-validation-and-direction-roadmap.md` 的 **BIZ-06**；`docs/design/02-sir-language-and-modules.md` §7（查询/分页/投影、关联查询深度与存在性语义）、§8（关系与模块边界）；`docs/design/03-compiler-and-generated-backend.md` §8（查询生成策略：EXISTS 或去重根、按根 ID 批量读取、SQL 次数预算、items 顺序）
- **设计取向（负责人 2026-09-23 明确）**：**SIR 只表示软件层面的语义**——关系中有什么、业务要筛什么、响应返回什么；EXISTS/JOIN/`IN`、SQL 条数、事务隔离、批量分组一律由 **Lowering 决策并写入 Lowered IR**，Generator 只渲染，不做判断。
- 版本快照：实现与验证在 HEAD=`92c505c` + 本单未提交工作区上完成；证据绑定该树的 SIR/DDL/seed 摘要（`b5e6bd5b…`/`8ae2eb53…`）与生成工程摘要（combined sha256 `b143ae3e…`）。按项目规则，提交需负责人显式授权，本单收口只产生文档改动

## 当前资格基线（Q11 开始时的实际状态）

- 实现快照：`92c505c`（消息 `pause`）。Q10 的验收归档文档改动留在工作区，未提交（按负责人 2026-09-18 规则）。
- 默认离线 Reactor：冻结与完成两种形式均 `BUILD SUCCESS`；Surefire **702 run / 0 fail / 0 error / 5 skip**（5 项 skip 全为 Windows junction）。
- 外部 MySQL conformance：MySQL 8.4.11 参考环境五场景 `QUALIFIED`；Q9 场景 40 项、Q10 场景 61 项业务断言 `PASSED`（均为 opt-in）。
- **参考环境当前未启动**（2026-09-23 核对：`docker ps` 无容器）。T5 需要先恢复参考环境；恢复不了则如实记 `NOT_RUN`。
- 当前资格权威：`docs/qualification/CURRENT_QUALIFICATION.md` 第 0 节、1.9、1.10。

## 关系能力现状（开工前实测，不是推测）

| 事实 | 依据 |
|---|---|
| 关系今天只有“子侧”一种表达：实体字段 `field course: Ref<Course>;` | `sir-toolchain-application/src/test/resources/valid/campus-market-minimal.sir` 的 `field seller: Ref<User>;` |
| 该字段落成外键列（`course_id`），Java 侧是 identity 存储类型（Long） | `SpringBootModelLowerer` 的 `REFERENCE_ID` 持久化形状与 `*_id` 列名；`LoweredJavaType.EntityReference`；`TypeRenderer` |
| `Ref<X>` 还可作 actor 类型与 capability 输出（响应体为实体本体） | `SpringBootModelLowerer` 的 `TransportPlan.ResponseRepresentation.ENTITY_BODY` |
| 查询表达式只有 `item.字段`、`input.字段`、`actor`、字面量、`now()`，运算符 `== != < <= > >=`、`containsLiteral`、`and/or/not`、`.present` | `Sir.g4` 的 `expression` 规则族（`orExpression` → … → `postfixExpression`） |
| **没有**关系遍历、没有 `exists`/`any`/`some` 谓词、没有 `in`/`isNull` | 同上；且 `any`/`exists`/`some` 当前不是关键字，现有 fixture 未把它们用作标识符（已核对，注释中的英文 `exists` 除外） |
| `find` 渲染为 `selectCount` + `selectList(... LIMIT ...)`，即 2 条 SQL；无 JOIN、无批量补查 | `WorkflowRenderer`（`LambdaQueryWrapper` + `selectCount`/`selectList`） |
| 投影字段必须与源实体字段同名同类型、且不得带约束；**没有**嵌套/关联对象投影 | `TypePass.typeViewFields`；`ValidatePass.validateViewFields` |
| 实体字段没有“集合关系”概念：持久化形状判定只区分引用/枚举/标量 | `SpringBootModelLowerer` 的 `PersistenceShape` 判定 |
| Lowered IR 中**不存在**任何 SQL 次数预算或关联读取计划的概念 | `sir-lowering-spring-boot`/`sir-lowering-api` 生产源中无预算/计划类型 |

结论：本单新增的表面在**查询与投影侧**（一句存在性谓词、一层到两层的关联投影），不在写入侧；EXISTS、批量读取、预算、隔离级别全部是 Lowering 的决策。

## 目标

在现有单文件 SIR 链上完成 G1 的第三条端到端切片：让“父实体 + 子实体/关联实体”的关系可以被**筛选**和**读取**，并在真实 MySQL 与真实 HTTP 上证明设计对 BIZ-06 的要求。

具体要证明的四件事：

1. **存在性过滤成立**：一条关联记录都不匹配的根记录不出现；多条关联记录匹配同一个根时，根**只出现一次**，`total` 是**根实体数**而不是关联记录数；谓词内的多个条件在**同一条**关联记录上求值（不会退化成多个独立存在性条件）。
2. **关联读取成立**：声明的关联投影按**根 ID 批量读取**（对多一次、对一一次），集合内容与顺序确定，空页不发起批量读取，空集合为 `[]`。
3. **预算成立**：一次请求的 SQL 语句数 = `2 + 关联投影数`（基础 count + 根分页 两次，空页为 2），且**不随记录数增长**；预算写入 Lowered IR 并在真实 MySQL 上实测。
4. **决策归属成立**：存在性计划、批量读取计划、集合排序、语句预算、读事务隔离全部由 Lowering 决定并写入 Lowered IR；Generator 只渲染，不读 AST/Normalized/SIR，不访问磁盘。

本单只做**关系过滤 + 一层到两层的关联读取**。关联写入、嵌套保存、删除行为与级联、关联集合自身分页、按关联字段排序、权限与归属过滤均不在本单范围（见“禁止范围”）。

### G1 内部顺序（本单只做 Q11）

| 工作单 | 范围 | 对应完成门 | 状态 |
|---|---|---|---|
| Q9 | Course 单实体 + 分页/投影/排序/字面量过滤查询端到端 | BIZ-05（查询与分页部分）、GEN-01、GEN-02 | **已完成并归档** |
| Q10 | Course 写侧：Create、Get、PATCH 三态、version 冲突、结构化字段错误 | BIZ-01、BIZ-02、BIZ-03、BIZ-04 | **已完成、验收并归档** |
| **Q11（本单）** | 关联过滤（存在性语义）、根不重复、关联批量读取与 SQL 次数预算 | BIZ-06 | **`AWAITING_ACCEPTANCE`**：T1–T6 已完成（全量 749/0/0/5、关联场景 36 项断言 0 失败、Q9/Q10 同树回归 40/40 与 61/61），等待验收 |
| Q12（未立项） | 路由模板（`/api/courses/{id}` 形态的 `@PathVariable` 绑定）与资源式命名 | 无（设计 §10 未冻结路由形态） | 未立项 |

## 设计原则对照（负责人 2026-09-23 点名的三条）

1. **新业务主要靠组合已有能力**：关系本身已经在模型里——子实体的 `field course: Ref<Course>` 既是外键也是关系声明，不需要在父实体再声明一遍，也不引入关系声明表、`many`/`one`、`via` 这类平行机制。新增的只有查询/投影侧的两种用法：存在性谓词与嵌套投影。实体字段、写入语义（哪些字段能建、能改、有没有列）、DTO、路由都不变。
2. **普通需求不变绕口**：列表页最常见的两件事——“按子表条件筛父表”和“带出子表概览”——在 SIR 里就是一个谓词加一个嵌套字段，没有 JOIN/EXISTS/IN/子查询/深度等实现词。
3. **修一种实现问题，一类业务共同受益**：关系解析、存在性计划、批量读取计划与语句预算都按“任意父子/关联实体”实现（多对多就是显式关联实体，如 `Enrollment` 同时挂 `Course` 与 `Student`），不是课程-报名硬编码。被修掉的是一类通病：N+1 补查、JOIN 造成的根重复、`total` 虚高。

## 输入 SIR（本单的目标文本）

新 fixture `course-enrollment.sir`（内容如下，作为本单的冻结输入；**不修改 Q9 的 `course-catalog.sir` 与 Q10 的 `course-admin.sir`**）：

```text
sir 0.1

software CourseEnrollment {
  metadata {
    displayName "Course Enrollment";
    namespace "com.example.courseenrollment";
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
    enum EnrollmentStatus {
      ACTIVE,
      CANCELLED
    }

    entity Student persistent {
      identity id: Int64 generated auto;
      field studentNo: String where notBlank, length(1, 16);
      field name: String where notBlank, length(1, 100);
    }

    entity Course persistent {
      identity id: Int64 generated auto;
      field code: String where notBlank, length(1, 32);
      field name: String where notBlank, length(1, 100);
      field capacity: Int32 where min(1);
    }

    entity Enrollment persistent {
      identity id: Int64 generated auto;
      field course: Ref<Course>;
      field student: Ref<Student>;
      field status: EnrollmentStatus;
    }

    view StudentSummary from Student {
      field id: Int64;
      field studentNo: String;
      field name: String;
    }

    view EnrollmentSummary from Enrollment {
      field id: Int64;
      field student: StudentSummary;
      field status: EnrollmentStatus;
    }

    view CourseEnrollmentItem from Course {
      field id: Int64;
      field code: String;
      field name: String;
      field capacity: Int32;
      field enrollments: List<EnrollmentSummary>;
    }

    input SearchCourseEnrollmentsInput {
      field page: Int32;
      field size: Int32;
    }

    error InvalidPage;

    capability SearchCourseEnrollments {
      input SearchCourseEnrollmentsInput;
      output Page<CourseEnrollmentItem>;
      fails InvalidPage;
      requires readonly;
      expose query;
      workflow {
        find Course
          where any(Enrollment, course == item and status == EnrollmentStatus.ACTIVE)
          order by code ascending
          page input.page, input.size else InvalidPage
        as courses;
        return courses;
      }
    }
  }
}
```

读法：“查课程，条件是**存在一条报名**，它属于这门课且状态为 ACTIVE；列表项带出这门课的报名概览，每条概览带学生摘要。”

## 新增 SIR 表面（逐条：语法、语义、唯一所属阶段、生成影响）

### S1 存在性谓词 `any(<Entity>, <conditions>)`

- **语法**：新增关键字 `any` 与谓词形式 `any(<实体名>, <布尔表达式>)`；可出现在 `find ... where` 的任意布尔位置（与 `and/or/not`、比较、`containsLiteral` 组合）。
- **语义**：
  1. 第一个参数必须是**实体**（不是 view/input/enum）；第二个参数是以该实体字段为基准的布尔表达式。
  2. 条件**必须显式给出与根实体的连接**：形如 `<该实体的 Ref<根实体>> == item`（或反向 `item == <字段>`）。该比较既是业务陈述（“报名属于这门课”），也是 Lowering 的相关子查询连接列；缺少连接条件报诊断（唯一连接由作者写清，不做隐式推导）。
  3. 条件以**同一条关联记录**为单位求值：`any(Enrollment, course == item and status == ACTIVE and student == input.student)` 表示“存在一条报名同时满足三个条件”，不得拆成多个独立存在性判断。
  4. 条件内裸名解析为该实体的字段；`item` 仍指根实体；`input`/`actor`/字面量/`now()` 照旧。被引用实体自身不得再写 `any`（深度限制见 S2）。
  5. 结果只决定根记录是否出现，**不改变根的基数**：不会因为匹配多条关联记录而重复根，也不会把关联列带进响应（响应仍只由投影决定）。
- **唯一所属阶段**：Parser（`any` 关键字与谓词形式）；Resolve（实体绑定 + 条件内字段绑定，新 `ReferenceRole`）；Type（连接比较的两侧类型：`Ref<根实体>` 与根实体变量；条件必须是布尔；其余比较沿用既有类型规则）；Validate（只允许出现在 `find` 的 `where`；`validate`/`create`/`update`/patch 载荷中出现即报错；不得对 `Ref` 字段赋值；本单不允许 `any` 嵌套 `any`）；Normalize（`ExistsExpression`，保留“同一记录”语义）；Lowering（相关 EXISTS 计划、参数绑定、连接列）。
- **生成影响**：渲染为参数化的**相关 EXISTS 子查询**（形态由 Lowering 计划决定），不是 JOIN；根查询的 `selectCount`/`selectList` 语义不变。

### S2 关联投影（view 嵌套 view，一层到两层）

- **语法**：**不新增语法**。复用既有字段类型位置：`field <name>: <ViewName>;`（对一）与 `field <name>: List<ViewName>;`（对多）。
- **语义**：
  1. 嵌套投影必须落在一条**已存在的关系**上：**对一**（`field x: ChildView`）要求本 view 的源实体自身持有指向嵌套 view 源实体的 `Ref<Child>`（外键在本侧）；**对多**（`field xs: List<ChildView>`）要求嵌套 view 的源实体持有指向本 view 源实体的 `Ref<父>`（外键在子侧）。方向不成立或缺少对应 `Ref` 即诊断。
  2. **深度口径**：以根 view 记 0 层，本单允许到 **2 层**（设计 02 §7“最大默认深度 2”）：`CourseEnrollmentItem（0）→ enrollments: List<EnrollmentSummary>（1）→ student: StudentSummary（2）`。第 3 层报诊断。
  3. 若该方向所需的 `Ref` 字段**不唯一**（同一对实体之间存在两个以上候选，例如 `Enrollment` 同时有 `course` 与 `waitlistCourse` 都指向 `Course`），本单不做消歧语法，直接报诊断（等真实需求再加 `via`，见 D5）。
  4. 外键 id 投影（`field student: Ref<Student>`）是既有行为，**不产生额外查询**。
  5. 对多集合的顺序固定为**子实体 identity 升序**（本单不加 SIR 语法）；空集合为 `[]`；关联行缺失（外键指向不存在的行）按声明投影返回，不用空对象掩盖。
- **唯一所属阶段**：Parser（无新语法，仅确认 `List<ViewName>` 可解析）；Resolve（嵌套 view 符号绑定与关系判定）；Type（关系存在、方向一致、深度 ≤ 2）；Validate（嵌套字段不得带约束；不得出现在写入侧绑定之外的位置——写响应仍强制 `view`，与 Q10 一致）；Normalize（`NormalizedView.nested`）；Lowering（每个嵌套投影一条批量读取计划 + 投影映射 + 排序）；Generator（嵌套 DTO、批量查询、按根 ID 分组、`[]`）。
- **生成影响**：新增嵌套 DTO 类型；每个嵌套投影渲染为**一条** `WHERE <外键列> IN (<当页根 ID 集合>)` 查询并分组（对一为按 identity 集合查询后回填）；不生成 JOIN。

### Lowering 决策面（写入 Lowered IR，供断言与证据核对）

- `ExistsPlan`：根实体、被引用实体、连接列（子侧外键）、条件与参数绑定。
- `AssociationReadPlan`：每个嵌套投影一条——方向（对一/对多）、子表、外键列、父 ID 参数、排序键、目标 DTO 映射；深度位置。
- `StatementBudget`：`2 + 关联投影数`；空页为 `2`；口径为“同一 HTTP 请求内该查询路径的语句数”（不含连接准备等）。
- `ReadTransaction`：查询路径在**同一只读事务**内完成 count/page/批量读取，隔离级别由 Profile 固定并写入 IR（设计 03 §8 要求一致快照；具体级别在此实现，不在 SIR 表达）。
- **IR 校验（GEN-02 式）**：预算与关联计划不一致、存在性计划缺连接列、嵌套深度 > 2、批量计划与投影 DTO 不一致 → 在 Generator 前拒绝，并有“同一校验器通过合法输入”的配对对照与灵敏度探针。

## 传输与错误契约

| 契约 | 形态 | 归属 |
|---|---|---|
| 路由与状态码 | 不变（沿用 Q9/Q10：查询 200、非法分页 400） | 既有 |
| 分页响应 | `PageResponse` 的 `total`/`page`/`size`/`records` 不变；`total` 是根实体数 | 既有 + Lowering（count 计划） |
| 越界页 | 空 `records` + 真实 `total`，不是 404 | 既有 |
| 关联集合 | 出现在投影声明的位置；空为 `[]`；顺序确定（identity 升序） | Lowering 计划 + Generator 渲染 |
| 关联缺失（悬空外键） | 按声明投影返回，不用空对象掩盖 | Lowering 计划 |
| SQL 次数预算 | 写入 Lowered IR；真实环境实测 | Lowering 决策 + 场景证据 |
| 读事务隔离 | Profile 固定并写入 IR | Lowering/Profile |
| 写入侧 | 本单不改变任何写入契约（Q10 的 201/200/400/404/409 与错误信封保持不变） | — |

## 测试先行顺序（每步必须先写失败测试再实现）

**T1 Parser**：`any` 关键字与谓词形式（正例：`course-admin`/`course-enrollment` 全量可解析；反例：`any` 后缺实体、缺 `,`、空条件、`)` 缺失、`any` 当作标识符使用），以及 `List<ViewName>` 字段类型；含精确码、数量与 span。
**T2 Semantic**：Resolve/Type/Validate/Normalize 的 S1–S2 规则，逐条反例——`any` 第一个参数不是实体、缺连接条件、连接列不是 `Ref<根实体>`、条件引用未知字段、条件内对 `Ref` 字段赋值、`any` 出现在 `validate`/`create`/`update`/patch 载荷、`any` 嵌套 `any`、嵌套投影方向不成立（对一缺本侧 `Ref`、对多缺子侧 `Ref`）、该方向的 `Ref` 不唯一（歧义）、嵌套深度 3、嵌套字段带约束、对多集合投影到实体（非 view）。每条反例断言精确码、数量与 span。
**T3 Lowering**：`ExistsPlan`/`AssociationReadPlan`/`StatementBudget`/`ReadTransaction` 的形状与一致性校验；空页预算；GEN-02 式损坏模型拒绝（缺连接列、深度 3、预算与计划不一致、批量计划与 DTO 不一致）与配对正例、灵敏度探针；Q9/Q10 既有 IR 校验回归。
**T4 Generator**：存在性渲染（相关子查询、参数绑定、不生成 JOIN、不重复根）、批量渲染（每个嵌套投影恰好一条 `IN` 查询、按根 ID 分组、集合顺序、空页不查询）、嵌套 DTO 与 `[]`；GEN-01 字节确定性覆盖新产物；离线编译矩阵纳入新切片。
**T5 Application / 真实环境**：新增 fixture `course-enrollment.sir` 与 `course-enrollment-ddl.sql`、`course-enrollment-seed.sql`；新增 opt-in IT `RelationSliceBusinessConformanceIT`（断言见“完成门”），证据目录与 Q9/Q10 同级（`evidence/relation-slice-*/relation-slice-report.txt`）。
**T6 文档与闸门**：资格报告新节、覆盖清单新组、状态文件、两条全量命令与 `git diff --check`。

## 允许修改的范围

- `sir-parser`：`Sir.g4`（`any` 关键字与谓词）、AST 节点、`SirAstBuilder`、语法测试与 fixture。
- `sir-semantic`：Resolve/Type/Validate/Normalize、`ReferenceRole`、Normalized 模型（`ExistsExpression`、`NormalizedView.nested`）、语义测试。
- `sir-lowering-spring-boot`：查询/存在性/关联读取计划的模型与 Lowerer、IR 校验、Profile 的读事务与预算决定、Lowering 测试。
- `sir-generator-spring-boot`：存在性渲染、批量读取渲染、嵌套 DTO 渲染、确定性矩阵与离线编译矩阵扩展、生成器契约测试。
- `sir-project-graph`：`ArtifactRole` 追加新角色值（嵌套 DTO）与 validator 复算。
- `sir-change`：新声明/步骤种类的分发（不得改既有 operation 语义）。
- `sir-toolchain-application`：`SpringBootProjectGraphInputFactory` 新种类映射、conformance 测试侧新增 IT 与 fixture。
- `docs/`：本工作单、路线图、资格与覆盖文档。

## 禁止范围（明确不做）

- `in`/`isNull`、when-present 可选过滤、按关联字段排序、关联集合自身分页、游标分页。
- 关联写入与嵌套多实体保存（设计 02 §8“首轮不做嵌套多实体自动保存”）、DELETE/归档、删除行为与级联。
- 权限、角色、归属过滤与 401/403（G5）；报名并发与名额（BIZ-07..13）。
- 深度 3 及以上；关系消歧语法（`via`，见 D5）。
- 路由模板与 `@PathVariable`（Q12）；产品 INITIALIZE/UPDATE（G3）、数据库迁移、Docker/源码包（G4）、Web 平台（G6）。
- **产品 schema 生命周期**：本单 DDL 与 seed 仍是测试侧 fixture（`schemaSource=TEST_FIXTURE_DDL`），不得在文档中声称已实现 INITIALIZE/UPDATE。
- 新 POM 依赖、新 Maven 仓库条目、新 skip/exclude；在 Renderer 里补语义逻辑。

## 定向验证命令

```bash
# 各模块定向（按 T1–T4 阶段分别运行）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-parser -am test
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-semantic -am test
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-lowering-spring-boot -am test
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-generator-spring-boot -am test
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-project-graph,sir-change -am test

# 真实 MySQL + HTTP 关联场景（opt-in；需要先恢复参考环境，schema 从 KCG_CONF_SCHEMA_NAME 派生）
source /root/kcg-conformance/env.sh
mvn -B -Dmaven.repo.local=/root/.m2/repository -o \
  -pl sir-toolchain-application -am \
  -Dtest=io.kcg.sir.application.conformance.RelationSliceBusinessConformanceIT \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkcg.relation-conformance.enabled=true \
  -Dkcg.conformance.work-parent=/root/kcg-conformance/work \
  -Dkcg.conformance.evidence-parent=/root/kcg-conformance/evidence \
  -Dkcg.conformance.maven-executable=/usr/bin/mvn \
  -Dkcg.conformance.maven-repo=/root/.m2/repository test

# 两条全量闸门（与 Q9/Q10 相同，均需 BUILD SUCCESS）
# 按 AGENTS.md“测试资源约束”：优先由 GitHub CI 承担（证据需绑定 SHA + run URL）；
# 仓库尚未建立 workflow，因此在本单执行期间，全量只在确有必要且预算过资源余量后于本机执行
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true
```

## 完成门

1. **新语法/语义的诊断归属**：S1–S2 每条规则落在唯一阶段，反例断言精确码、数量与 span。
2. **GEN-02 式拒绝**：缺连接列、嵌套深度 3、预算与计划不一致、批量计划与投影 DTO 不一致的 Lowered 输入在 Generator 前被拒，并有配对正例与灵敏度探针。
3. **GEN-01**：新增产物下相同完整输入重复生成、切换 Locale/工作路径的字节一致。
4. **生成工程离线编译**：`GeneratedProjectOfflineCompilationTest` 覆盖新切片，`--offline` 编译通过（无新依赖）。
5. **BIZ-06 核心（根不重复）**：一门课有多条匹配的关联记录（含 1 条不匹配状态）时，`records` 中该课程**只出现一次**；`total` 等于满足条件的**课程数**而不是报名记录数；`records` 顺序与根排序一致。
6. **存在性语义正确**：“存在一条报名同时满足多个条件”成立——构造只被“两条不同记录分别满足不同条件”的数据，断言该课程**不出现**（防止把条件拆成多个独立存在性判断）。
7. **关联读取**：对多集合内容与顺序正确（identity 升序）、对一嵌套内容正确、空集合为 `[]`、无报名课程返回空集合；只投影外键 id 时不产生额外查询。
8. **SQL 次数预算**：一次请求的语句数 = `2 + 关联投影数`（本 fixture 为 4）；空页为 2；把数据量放大（例如报名记录从 3 条增到 30 条）后语句数**不变**。测量手段与原始证据（general log 片段或摘要差量）写入报告。
9. **读事务一致性**：count/page/批量读取在同一只读事务内完成（IR 声明与生成代码一致，需有直接证据）。
10. **失败与不写入口径**：非法分页 400 且无写入（沿用 Q10），每次失败请求后做整行指纹比对。
11. **场景证据口径**：报告记录 `schemaSource=TEST_FIXTURE_DDL`、`productInitializeImplemented=false`、`productUpdateImplemented=false`、advisory lock 取放、schema DROP 后缺席证明、workRoot 清理；证据文件与 Q9/Q10 同级。
12. **两条全量闸门** `BUILD SUCCESS`（优先取 GitHub CI 结果，证据含 commit SHA + run URL；CI 未建立且本机不可行时记 `NOT_RUN` 并说明）；与基线 **702 / 0 / 0 / 5** 的差量逐模块归因（新增测试必须全部来自本单）；**不得新增 skip 或 exclude**。
13. **文档同步**：资格报告新节（含有效边界）、覆盖清单新组、`PROJECT_STATUS.md`、路线图；`git diff --check` 无输出。

## 失败 / 跳过 / NOT_RUN 口径

- 定向或全量出现任何 fail/error（除既有 5 项 Windows junction skip 外）即本单未完成；不得以降 skip、加 exclude、加 `@Disabled`、改断言口径的方式“通过”。
- 参考环境不可用（容器、端口、凭据、schema 锁不可得）或 **SQL 次数测量手段不可行**时，业务场景与预算断言记为 **`NOT_RUN`**，此时完成门 5–9 不成立、G1 不为完成；`NOT_RUN` 必须与通过明确区分，不得合并统计。
- 预算断言必须是**确定性**断言（固定语句数，不做统计平均）；不做全量之外的额外压测。
- 生成工程不得为“让 SQL 变少”而改变可见行为（例如把两次查询合并成一次 JOIN 而破坏根不重复）；任何此类改动都要先改 Lowering 计划并同步断言。

## 需要负责人裁决的设计选择（D0–D5）

| # | 决策 | 推荐 | 备选与代价 |
|---|---|---|---|
| D0 | 是否把本单拆成两张（过滤+去重分页 / 关联读取+预算） | **不拆**：两者共用同一套关系解析与批量计划；拆开会出现“能按关联筛、但带不出关联”的中间态，Q12 之后仍要返工 | 拆分：单张更小，但关系语义要评审两次，验收场景（列表页）不完整 |
| D1 | 存在性谓词的连接条件形态 | **显式写出** `course == item`：既是业务陈述，也让 Lowering 有明确连接列；同一实体被引用两次时由作者写清是哪一条 | 隐式推导（子实体唯一 `Ref` 指向根）：写起来更短，但歧义时要额外诊断规则，读者也看不出连的是哪条关系 |
| D2 | 关联投影范围 | **对多 + 对一、深度到 2 层**：`Course → enrollments（对多）→ student（对一）`，两个方向共用同一套批量机制 | 只做一层对多：列表页只能显示学生 id，验收场景偏弱，对一批量机制留到下一单 |
| D3 | 深度口径与集合顺序 | 根 view 记 0 层、上限 **2 层**（与设计 02 §7 一致）；对多集合固定 **identity 升序**，本单不加排序语法 | 深度按“嵌套层数”另计：口径歧义，容易和设计表述对不上；给集合加 `order by`：表面更大，等真实需求 |
| D4 | SQL 次数测量手段 | 开工第一步实测：**MySQL general log 窗口**（参考库 control 账号有 `ALL PRIVILEGES`，可开日志并统计目标 schema 语句）或 `performance_schema` 摘要增量，取可行者并写进报告 | 只做间接证据：BIZ-06 的“预算满足”无法判定，不采用 |
| D5 | 同一对实体之间有两个 `Ref`（读侧无法判定用哪条）时的消歧 | **本单不引入语法，直接诊断**；出现真实需求再加 `via <field>` | 现在就加 `via`：为尚未出现的需求造语法 |

## 实施期订正协议

执行中若发现推荐方案与冻结依赖、既有契约或参考环境冲突，按 Q9/Q10 的做法处理：**保持原意图、记录证据、写入本工作单的“实施期订正”节，并继续保持禁止范围不变**；不得为让用例通过而放宽完成门。

## 实施期订正

**C1（2026-09-23，T1 发现）本单输入 SIR 的分页子句必须用大写 `Page`**：“输入 SIR”一节里的 fixture 原文写的是小写 `page input.page, input.size else InvalidPage`，而 Q9 冻结的语法里分页子句的关键字是大写 `Page`（小写 `page` 是普通标识符）。真实解析证据：`SIR-PARSE-001: 遇到不符合 SIR v0.1 语法的内容：page`（`RelationGrammarTest.theRelationSliceSourceParsesWithoutDiagnostics` 首次运行）。修正：fixture 改用 `Page input.page, input.size else InvalidPage`（与 `course-catalog.sir` 一致），**不改语法**（语法形态属 Q9 已验收范围，本单不重开）。本单“输入 SIR”一节保留原文以便对照。

## 开工前的准备（批准后第一步）

### 已完成：参考环境与 SQL 次数测量手段核对（2026-09-23，D4）

- 参考环境：容器 `kcg-conformance-mysql`（镜像 `mysql:8.4`，此前 `Exited`）`docker start` 后 1 秒内可连；MySQL **8.4.11**，`server_uuid=c8295431-b30f-11f1-85f5-82329a62fe01`（与 `env.sh` 一致）；control JDBC 主机端口 `127.0.0.1:33306`（`env.sh` 的 `KCG_CONF_SERVER_PORT=18080` 是应用端口，不是 MySQL 端口）。核对完成后已把容器停回 `Exited`。
- **general log（`log_output=TABLE`）可用**：control 账号（`ALL PRIVILEGES`）可开关日志与 `TRUNCATE mysql.general_log`；运行时用户 3 条语句产生 10 行日志（含 Connect/Quit），可按 `user_host` 区分用户并看到原始 SQL 文本 → 作为“语句数 + SQL 形态”的原始证据。
- **`performance_schema.events_statements_summary_by_digest` 可用**：`statements_digest=YES`；按 `SCHEMA_NAME` 归因成立（默认库为 `mysql` 的语句归到 `SCHEMA_NAME='mysql'`）；无默认库的连接级语句（如 `SELECT @@version_comment`）`SCHEMA_NAME` 为 `NULL`。
- **结论（写入 T5 证据口径）**：预算计数取 **`SCHEMA_NAME = KCG_CONF_SCHEMA_NAME` 的摘要增量**（自动排除连接准备语句），并用 **general log 片段**给 SQL 形态作原始证据；计数窗口必须与其它活动隔离（沿用 advisory lock 与顺序执行），窗口内的并发语句不计入预算。
- 未做：真实请求路径（count/page/批量）的语句数实测——那属于 T5，必须在生成工程真实运行后测。

### T3 完成（2026-09-23）

- 变更（Lowered IR 新增三处决策面）：`SpringExpression.ExistsPredicate`（相关子查询文本 + 按序绑定的 `ExistsArgument` 取值：Text/Integral/Decimal/Flag）、`SpringBootDeclaration.ViewRelationPlan`（基数、目标 view/实体、取键属性、比较属性、索引属性、集合排序属性）、`SpringBootWorkflow.StatementBudget`（页读取数 + 关联读取数，含 `total()`/`emptyPageStatements()`）；`SpringBootModelLowerer` 新增 `lowerExistsPredicate`（相关子查询：`SELECT 1 FROM <关联表> <别名> WHERE <别名>.<外键列> = <根表>.<根身份列> AND <其余条件>`，枚举成员以持久化文本绑定、字面量一律绑定为参数）、`withoutConnection`（把已变成连接的比较从条件里摘掉，避免重复问同一个问题）、`lowerViewRelation`（对一按本侧外键取键、按目标身份索引；对多按本侧身份取键、按子侧外键过滤、按目标身份升序）、`lowerStatementBudget`/`associationReadCount`。
- 拒绝面（Generator 之前）：`SpringBootInputValidator` 新增存在性条件可译性检查（条件里只允许关联实体列、根 item 列、字面量与枚举成员，用 and/or/not 组合；工作流变量、`containsLiteral`、函数等一律 `SIR-LOWER-FEATURE-001`，不静默丢弃）与「嵌套投影必须有分页」检查；`SpringBootIrValidation` 新增 IR 自洽检查：预算必须等于「页读取数 + 投影里实际嵌套的关联数」、关系计划必须投影它声明的那个 view、索引属性必须等于读取所用属性、对一不得带排序、对多必须带排序。
- 测试（先红后绿）：新增 `RelationSliceLoweringTest` 13 项——相关子查询文本逐字断言、对多/对一计划的键与索引断言、预算断言（4 = 2+2）、IR 自洽、以及五个损坏模型（预算少算关联、预算谎称一次页读取、按别的属性索引、子查询占位符没有对应取值、不可译条件）+ 一个正例（无关联的 find 预算为 2/0/2，证明「只投影标量与外键不产生额外查询」）。
- 模块结果：`sir-lowering-spring-boot` **80 run / 0 fail / 0 error / 0 skip**（基线 69，新增 11；另有 2 项并入既有类的断言）。

### T4 完成（2026-09-23）

- 变更（Generator 只渲染）：`WorkflowRenderer.renderPredicateNode` 新增 `ExistsPredicate` 分支（`.apply("EXISTS (<子查询>)", <按序取值>)`，取值按 IR 的类型渲染，枚举文本/整数/小数/布尔分别处理）；分页路径改为「先读页，再按投影逐处批量读取」，新增 `readAssociations`（每个投影使用点一条：取键 → 空键短路 → 一次 `selectList(... in(属性, 键列表) ...)` → 建索引；对多 `groupingBy`、对一 `toMap`）与 `renderProjectionRow`（递归渲染嵌套投影：对多 `getOrDefault(...).stream().map(...).toList()`、对一 `Optional.ofNullable(index.get(...)).map(...).orElse(null)`），全部变量名与 lambda 名确定性生成。
- 测试（先红后绿）：新增 `GeneratorRelationSliceContractTest` 6 项（EXISTS 文本与绑定值、每个关联一条批量读取、对一按身份读取与索引、投影不产生 N+1、`@Transactional(readOnly = true)`、嵌套 view 各自成为响应类型）；`GeneratorDeterminismMatrixTest` 新增关联切片探针（同一输入在 en-US/ISO-8859-1 与 tr-TR/UTF-8、不同工作目录下的生成摘要逐字节一致）。
- 模块结果：`sir-generator-spring-boot` **73 run / 0 fail / 0 error / 0 skip**（基线 67，新增 6；确定性矩阵 5 项含新探针）。

### T5 完成（2026-09-23）

- 真实环境场景：`RelationSliceBusinessConformanceIT`（`sir-toolchain-application`，opt-in）在 MySQL 8.4.11 参考环境上启动生成应用，对 `/api/search-course-enrollments` 做四组独立请求，**36 项断言全部通过、0 失败**；生成工程 22 个文件（combined sha256 `b143ae3e…`）、离线 `mvn clean verify` 成功；证据 `/root/kcg-conformance/evidence/relation-slice-1a0cd62f220-3526/relation-slice-report.txt`。
- 场景数据（fixture seed 七门课、三名学生、三十七条报名）：CS101（两条 ACTIVE）、CS102（一条 ACTIVE + 一条 CANCELLED）、ART101（只有 CANCELLED）、PHY101（无报名）、MAT101（一条 ACTIVE）、ENG101（只有 CANCELLED）、ZOO101（三十条 ACTIVE）。
- 断言要点：`total=4` 且每条根只出现一次（join 式实现会给出 total=5、CS101 重复两次）；ART101/PHY101 不出现（存在性语义）；**ENG101 不出现**——它「有报名」且表里「存在 ACTIVE 报名」，但没有**同一条**报名同时满足两者，故该断言专门排除「把一个存在性谓词拆成两个独立判断」的实现；CS102 的嵌套集合仍包含那条 CANCELLED 报名（根过滤 ≠ 投影过滤）；ZOO101 的三十条关联行全部投影且语句数不变。
- 语句预算实测（D4 口径）：`page=1&size=10` → **4** 条 SELECT（count + page + enrollment 批量 + student 批量），`page=2&size=2`（含 ZOO101 三十条关联）→ **4**，越界页 `page=99` → **2**（空页跳过批量读取），非法页 `page=0` → **0**（分页校验先于任何读取，400 `InvalidPage`）。计数取自 `performance_schema` 摘要中归属于本次 schema 的 `SELECT` 摘要增量，原始 SQL 取自 general log 的运行时账号片段；报告同时记录两者（原始 SQL 形如 `... WHERE ((EXISTS (SELECT 1 FROM enrollment enrollment_rel WHERE enrollment_rel.course_id = course.id AND enrollment_rel.status = 'ACTIVE'))) ...` 与 `... WHERE (course_id IN (1,2,5,7)) ORDER BY id ASC`）。
- 事务一致性（直接证据）：`performance_schema.events_transactions_summary_global_by_event_name` 的增量显示一次请求恰为 **1 个只读事务**（transactions=1、readOnly=1），即 count、page 与批量读取在同一只读事务内完成。
- 只读性：四次请求前后 fixture 行数一致（7→7），整行指纹与 seed 逐字符相同。


### 实施期订正（追加）

- **C2 存在性条件的可译范围由目标声明，不由语义层收窄**：语义层允许 `any(...)` 里出现任何布尔表达式；`spring-boot` 目标只能把「关联实体列 / 根 item 列 / 字面量 / 枚举成员 + and/or/not」译成一条相关子查询，其余形态（工作流变量、`containsLiteral`、函数等）在 Lowering 之前以 `SIR-LOWER-FEATURE-001` 拒绝并保留原 span，不静默丢弃条件。这是既有「目标能力」模式（同码已用于非持久实体等），不是本单新增的语义限制。
- **C3 相关子查询放在表达式树里，而不是 `FindStep` 的独立计划列表**：原方案设想 `FindStep.existsPlans` 与谓词并列；实现改为 `SpringExpression.ExistsPredicate`（子查询文本 + 按序绑定的取值），因为谓词本身就是那棵树，且 MyBatis-Plus 的嵌套 `and(...)`/`not(...)` 消费者共用同一个 wrapper 参数表，`{0}` 形式的占位符在任何嵌套层级都能正确绑定。预算是 `FindStep.StatementBudget` 的组件，随 find 一起进入 IR 校验。
- **C4 预算按“投影使用点”计数，不按“关系计划”去重**：同一个 view 在两个位置被投影时，每个位置各读一次（计数与渲染都按路径走，二者一致）。去重需要把多条父行集合合并后再读，代价与收益不匹配；这一口径已写进 `StatementBudget` 的注释，并由 IR 校验与渲染共同遵守。
- **C5 本目标要求嵌套投影所在 find 必须分页**：批量读取以「已加载的行」为起点，非分页 find 没有这一段行集合；带关联投影的非分页 find 在 Lowering 之前以 `SIR-LOWER-FEATURE-001` 拒绝（登记为后续候选：非分页关联读取需要一套不同的读取形状）。
- **C6 语句计数与事务证据必须走 control 账号，且事务证据用只读计数**：实测 MySQL 8.4.11 下 `kcg_conf_runtime` 对 `performance_schema.events_statements_summary_by_digest` 无 `SELECT` 权限（`ERROR 1142`），故计数与日志均由 control 账号连接执行（仅测试侧使用，不交给应用）；`events_transactions_summary_global_by_event_name` 在该版本**没有** `COUNT_COMMIT`/`COUNT_ROLLBACK` 列（只有 `COUNT_STAR`/`COUNT_READ_ONLY`），故事务证据取「事务数 + 只读事务数」增量。
- **C7 计数口径从 general log 改为摘要增量**：D4 预案把 general log 当作计数来源；实测发现一次请求还会写入事务簿记语句（`SET autocommit` 等），若按其计数会把事务开销算成查询成本。最终口径为：**计数**取 `performance_schema` 摘要中归属本 schema 的 `SELECT` 增量，**原始 SQL 证据**取 general log 的运行时账号片段（正是它证明了 `EXISTS` 的文本与 `IN (...)` 的键列表）。报告同时给出两者，且计数另有「每一条被计入的摘要都必须提到 fixture 表」的守门断言。


### T6 完成（2026-09-23）

- 两条全量闸门（本机、离线、`-B -o clean verify`）：**冻结形式与完成形式均 BUILD SUCCESS**，合计 **749 run / 0 fail / 0 error / 5 skip**（基线 702 → +47，全部来自本单：parser +10、semantic +17、lowering +13、generator +7；5 项 skip 仍全为 Windows junction，本单未新增任何 skip/exclude）。模块明细：parser 79、semantic 176、lowering-api 4、lowering-spring-boot 82、generator 74、project-graph 72、change 22、application 210(+5 skip)、cli 30。
  - 与「CI 优先」口径的关系：仓库仍无 `.github/workflows`、历史上没有任何 run，故本单全量证据来自本机（按 AGENTS.md「测试资源约束」：离线、单构建、无 `-T`、串行，两次各约 2 分钟）；CI 证据待 workflow 建立后补充。
  - **订正 C8**：Q10 记录的「冻结形式预期停在 sir-toolchain-application」在当前树上不再成立——两种形式都跑到 `kcg-cli` 并 BUILD SUCCESS。
- 同树业务回归（均在 HEAD=`92c505c` + 本单未提交工作区上运行）：Q9 `QuerySliceBusinessConformanceIT` **PASSED 40/40**（`/root/kcg-conformance/evidence/query-slice-1a0cd67e8fd-39548/query-slice-report.txt`）；Q10 `WriteSliceBusinessConformanceIT` **PASSED 61/61**（`…/write-slice-1a0cd6876b4-2401/write-slice-report.txt`）；本单 `RelationSliceBusinessConformanceIT` **PASSED 36/36**（`…/relation-slice-1a0cd62f220-3526/relation-slice-report.txt`）。
- 文档同步：本工作单、`docs/qualification/CURRENT_QUALIFICATION.md`（新增 §1.11、§2 模块表按完成形式重测）、`docs/qualification/TEST_COVERAGE_INVENTORY.md`（新增 Q11 覆盖组）、`docs/PROJECT_STATUS.md`、`docs/roadmap/README.md`；`git diff --check` 无输出。
- 完成门逐条：①✅ 新规则唯一阶段 + 码/数量/span（T2 的 17 项）；②✅ 五个损坏模型 + 配对正例（预算少算关联、预算谎称页读取数、索引属性不符、子查询缺取值、不可译条件）+ 非分页关联拒绝；③✅ 确定性矩阵新增关联切片探针（跨 cwd/Locale/Charset 字节一致）；④✅ 生成工程离线 `mvn clean verify` exit 0（22 个文件）；⑤✅ 根不重复、`total` 等于课程数、顺序与根排序一致；⑥✅ ENG101 反例专门排除「一个谓词拆成两个判断」；⑦✅ 对多三十条、对一、空集合、越界空页、只投影标量/外键零额外读取（预算 2/0/2）；⑧✅ 语句数 4/4/2/0（摘要增量计数 + general log 原始 SQL 双证据）；⑨✅ 一次请求恰为 1 个只读事务；⑩✅ 非法页 400 `InvalidPage` 且 0 读取、失败后整行指纹不变；⑪✅ 报告含 `schemaSource`/`productInitializeImplemented`/`productUpdateImplemented`/advisory lock 取放/schema DROP 缺席证明/workRoot 清理；⑫✅ 两种形式 BUILD SUCCESS + 差量归因；⑬✅ 文档同步 + `git diff --check` 干净。
- 本单未做（明确留出）：错误信封码与主设计逐字对齐、`SpringBootModelLowerer.java` 反编译形态重写（两项均为既有独立项）、非分页关联读取与更深嵌套（见订正 C4/C5）、`in`/`isNull` 等过滤算子与按关联字段排序。


### 其余准备

1. 恢复参考环境（MySQL 8.4.11 容器、`/root/kcg-conformance/env.sh` 凭据、advisory lock）；D4 手段已核对，T5 直接用上述口径。
2. 记录开工时基线（`git status --short`、全量 702/0/0/5 的既有证据引用）。

## 交接记录

**状态：`AWAITING_ACCEPTANCE` → `DONE`（2026-09-23 完成并交接 → 2026-09-23 验收通过并归档）。** 负责人回复“当前工作单 q11 已批准”，D0–D5 按推荐项批准；T1–T6 按顺序完成：T1 Parser → T2 Semantic → T3 Lowering → T4 Generator → T5 真实环境场景 → T6 文档与闸门。两条全量闸门与三个业务场景（本单 + Q9/Q10 同树回归）均通过。验收时负责人回复“确认”，未要求返工；生产代码、测试与文档改动**尚未提交 Git**（按本项目规则，提交需负责人显式授权）。

### T1 完成（2026-09-23）

- 变更：`Sir.g4` 新增保留字 `ANY : 'any'` 与 `anyPredicate`（挂在 `primaryExpression`，故可与 `and/or/not`、比较、`containsLiteral` 自由组合）；新增 AST 节点 `AstAnyExpression`（`entity` 名字引用 + `conditions` 表达式 + span）并加入 `AstExpression` 的 permits 列表；`SirAstBuilder.primaryExpression` 构建该节点。
- 测试（先红后绿）：新增 `RelationGrammarTest`（10 项）与 fixture `sir-parser/src/test/resources/valid/course-enrollment.sir`；首次运行因 `AstAnyExpression` 不存在而编译失败（红），实现后 10/10 通过。
- 模块结果：`sir-parser` **79 run / 0 fail / 0 error / 0 skip**（基线 69，新增 10）。
- 下游影响检查：`sir-semantic`、`sir-lowering-api`、`sir-lowering-spring-boot`、`sir-generator-spring-boot`、`sir-project-graph`、`sir-change`、`sir-toolchain-application`、`kcg-cli` 在 `-DskipTests` 下 `BUILD SUCCESS`（新增的 sealed 子类型没有打断任何穷举 switch，说明新节点目前落到各阶段的兜底分支——这正是 T2 要显式接管的地方）。
- 订正：C1（fixture 的分页子句必须用大写 `Page`）。

### T2 完成（2026-09-23）

- 变更：新增三个 ReferenceRole（`EXISTS_SOURCE_ENTITY`、`EXISTS_CONDITION_FIELD`、`VIEW_RELATION_FIELD`）；`NormalizedExpression` 新增 `ExistsExpression`（实体 + 连接字段 + 条件树）；`NormalizedViewField` 新增 `relation`（基数 + 目标 view）；新增内部帮助类 `ExistenceConnection`（一处推导“条件里哪个字段把关联实体接回根 item”）；`ResolvePass` 新增 `resolveAnyExpression` 与“条件内裸名按被引用实体字段绑定”、`bindProjectedRelation`（按方向找唯一 `Ref` 并绑定或报 SIR-SYMBOL-002/003）；`TypePass` 新增 `typeAnyExpression`（条件必须 Boolean + 必须含与 item 的连接比较）、关联投影的关系字段目标校验、并让绑到字段的裸名可被定型；`ValidatePass` 新增存在性谓词位置规则（SIR-FLOW-005）与嵌套规则（SIR-FLOW-006）、投影嵌套深度上限（SIR-VALID-005）；`NormalizePass` 产出 `ExistsExpression` 与 view 的 relation。
- 测试（先红后绿）：新增 `RelationSliceSemanticsTest` 17 项（1 法规 + 16 反例，反例断言码、数量与 span 覆盖文本）；`TypedReferenceSiteContractTest` 的合同源扩展到覆盖三个新角色（Account 加 `owner: Ref<User>`、新增 `UserAccounts` 嵌套投影 view、`SearchUsers` 的 where 换成 `any(...)`），其 AST 收集器学会新节点。
- 模块结果：`sir-parser` 79 + `sir-semantic` **176 run / 0 fail / 0 error / 0 skip**（语义基线 159，新增 17）。
- 观察（非本单引入、登记为后续候选）：`List<Entity>` 会被既有类型规则报两次 SIR-TYPE-001（一次在元素、一次在列表）；本单测试以“两条覆盖文本”精确断言，未改动既有行为。
