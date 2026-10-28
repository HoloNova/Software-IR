# course-selection 教师答案失败原因分析

## 结论与证据范围

**改写题的 24 项业务失败只有一个已证实根因：创建课程的四个数值输入没有得到所要求的必填校验。** 不是先修、排课或座位并发各坏了一遍。从零写题修复后的 19 条静态诊断可归为四类；它尚未运行，不能把代码中疑似的业务问题当成已发生的业务失败。

分析基于仓库 `b38fa2fa0dafe667d15eccf3ab86a1af56cdafda` 保存的题目、候选、判卷证据与源码。只读核对，没有重新编译、运行 Maven、启动应用或调用模型 API。下文缩写均指下面的完整文件；冒号后是原文件行号。

| 缩写 | 文件 |
| --- | --- |
| C | `datasets/sir/pilots/course-selection-change-g5/samples/course-course-selection-change-v1-teacher-dbbbac2d45bc/candidate.sir` |
| S | `datasets/sir/pilots/course-selection-scratch-g5/samples/course-course-selection-scratch-v1-teacher-524cb80d7495/candidate.sir` |
| R | `sir-toolchain-application/src/test/resources/topics/course/project.sir` |
| T | `sir-toolchain-application/src/test/java/io/kcg/sir/application/conformance/CourseSelectionChecks.java` |
| P | `experiments/slm/prompts/sir-0.3-general-v5.md` |
| L | `docs/SIR_LANGUAGE_REFERENCE.md` |
| B | `datasets/sir/pilots/course-selection-change-g5/runtime/course-course-selection-change-v1-teacher-dbbbac2d45bc/course-selection-1a180634ab7-5103/course-selection-report.txt` |

`valid/course-selection.sir` 不存在；实际参考路径取自 `experiments/slm/tasks/course-selection-change-v1.json` 的 `reference_source`，即 R。`CourseSelectionBusinessConformanceIT.java:43–55` 准备并运行测试，具体断言在 T，不在 IT 包装类里。

## 一、改写题：24 个失败怎样归为一个根因

### 1. 先还原计数，避免把连带断言算成独立缺陷

B:25 为 `checksPassed=128 checksFailed=24`。T:96–105 对 `weekday`、`startPeriod`、`endPeriod`、`capacity` 各发送两种请求：显式 `null`、删除字段。每个请求检查三个结果：HTTP 400；顶层 `code=INVALID_REQUEST`；`fields` 包含原字段路径及 `code=NotNull`。

| 输入字段 | 输入变体 | 失败断言 | 报告位置 | 候选相关位置 | 参考相关位置 |
| --- | --- | --- | --- | --- | --- |
| weekday | null、缺失 | 每种 3 项，共 6 项 | B:154–159 | C:256、281 | R:99、117、127 |
| startPeriod | null、缺失 | 每种 3 项，共 6 项 | B:160–165 | C:257、273、282 | R:100、118–119、128 |
| endPeriod | null、缺失 | 每种 3 项，共 6 项 | B:166–171 | C:258、273、283 | R:101、118–119、129 |
| capacity | null、缺失 | 每种 3 项，共 6 项 | B:172–177 | C:255、280 | R:98、120、126 |
| 合计 | 4 字段 × 2 变体 | 8 请求 × 3 断言 = 24 项 | B:154–177 | 同一创建能力 | 同一输入校验机制 |

这些请求实际都得到 500 和 Spring 通用错误体，没有所需的业务错误结构。后两项失败是同一个 500 的连带结果，不能再分别归因为“错误码问题”和“字段路径问题”。T:112 的四类业务表不变检查通过，因此证据也不支持“这些失败请求污染了数据库”。

### 2. 候选错在哪里，参考为何有效

C:251–258 写了 `Quantity` 和 `min/max`，但 C:273 只比较节次大小：

```sir
field capacity: Quantity;
field weekday: Quantity where min(1), max(7);
field startPeriod: Quantity where min(1), max(12);
field endPeriod: Quantity where min(1), max(13);
// 工作流中：
validate input.startPeriod < input.endPeriod else InvalidSchedule;
```

**范围约束不等于 HTTP 数值必填校验，单纯比较也不会触发算术输入校验。** 不能因为类型没有写 `Optional` 就推断当前生成器会为每个整数字段生成 `NotNull`。

R:117–120 则让四个输入都实际参与合法性运算：

```sir
validate input.weekday - 1 >= 0 and input.weekday <= 7
  and input.startPeriod >= 1 and input.endPeriod <= 13
  and input.endPeriod - input.startPeriod > 0
  and input.capacity - 1 >= 0 else InvalidSchedule;
```

其中 `weekday - 1` 覆盖 weekday，`endPeriod - startPeriod` 覆盖两个节次，`capacity - 1` 覆盖容量。差别不只是数学上等价与否：**在当前 SIR 目标上，算术依赖会改变工作流前的输入检查计划。**

| 机制 | 直接依据 |
| --- | --- |
| 参与算术的非可选输入缺失/null 时，在工作流前返回 400 INVALID_REQUEST，字段码 NotNull | L:1954；内置函数的同类规则见 L 的“内置函数”段 |
| 收集工作流算术依赖；加减乘、取负及内置函数会把依赖标为必填，普通比较不会 | `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/ArithmeticInputs.java:15–38,57–78` |
| 将依赖降级成能力级输入检查计划 | `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/SpringBootModelLowerer.java:703–709` |
| 先生成算术输入的 notNull 检查，再生成工作流步骤 | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/WorkflowRenderer.java:49–77` |
| min/max 降级为范围约束；并没有给所有非 Optional 数值类型普遍补 NotNull | `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/SpringBootModelLowerer.java:653–683` |
| 普通 DTO 按已有约束输出注解，数值使用可为空的装箱类型 | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/DtoRenderer.java:171–223` |
| 生成的 NotNull 错误码确为 NotNull | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/ValidationSupportRenderer.java:57–60` |

C 的 startPeriod/endPeriod 还会进入普通整数比较；该比较直接生成 Java 大小比较，见 `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/ExpressionRenderer.java:263–275`。空装箱数值可能在此拆箱失败；weekday/capacity 则没有对应的前置必填检查。B 只保存了 HTTP 500，未保存本次服务异常栈，因此这里不把每一种输入的具体异常类型或 SQL 执行位置说成已由日志证实。

### 3. 是需求、手册还是教师的问题

需求原句在 `experiments/slm/drafts/course-selection/requirement-change.md:1`，也完整进入 task 的 `requirement`：

> 这些数值缺失或 null 返回 400 INVALID_REQUEST，fields 指明原字段路径及 NotNull，范围错误或空/倒序时段返回 400，不能落库。

`experiments/slm/drafts/course-selection/traceability.md` 将这句明确对应 T:102–104；测试没有临时增加要求。

| 判断对象 | 结论 | 理由 |
| --- | --- | --- |
| 需求不清楚？ | 否 | 字段、缺失/null、400、INVALID_REQUEST、原路径、NotNull 都已明说。 |
| v5 没讲或讲错？ | **漏讲关键通用机制** | P:15 讲 Quantity 至少为 1，P:31、36 讲约束，P:89 讲算术，但全篇没有 L:1954 的“算术依赖触发必填检查”；也未提醒数值 min/max 不负责 null。 |
| 教师已有规则却不遵守？ | **未满足明确业务合同，但不能说它违反了手册里一条已写出的 NotNull 编码规则** | C 给出了范围约束，却没有实现需求指定的缺值响应；现有手册不足以让作者可靠判断怎样得到它。 |
| 语言/生成器层面 | 暴露了不直观的表达机制 | 数学等价的“差值比较”和“直接大小比较”，对缺值的 HTTP 行为不等价；这应在通用手册中解释，而不是归咎于选课业务复杂。 |

B 中先修全集检查、相邻/重叠时段、同学生锁等待、三座位竞争、并发退课和晚失败回滚均通过（可见 B:47–66、106–149）。不能把它们列为这份改写答案的失败根因。

### 4. 已有运行反馈为何仍没有合格答案

`datasets/sir/pilots/course-selection-change-g5/checked-r2.jsonl:4` 的子候选 `course-course-selection-change-v1-teacher-1cc5c4027a2d` 标记为 `runtime-feedback-repair`，父为 `dbbbac2d45bc`，但两者 `source_sha256` 都是 `c252b63f41c237f2576a557b416c3dc0ca6e05bed9000818345f60745b2a0f71`，候选文件逐字节一致。它不是修好了又出现新问题，而是**没有改动**。该子候选没有新的业务门证据，不能称为“再次判卷失败 24 项”；可以确定的是旧问题没有被代码修改消除。

## 二、从零写题：19 条静态诊断的四类根因

诊断原始记录：`datasets/sir/pilots/course-selection-scratch-g5/checked-r1.jsonl:3`；候选为 S，父候选为 `course-course-selection-scratch-v1-teacher-a9030f28af53`。下表按诊断条数互斥分组，合计 19；不是声称有 19 个业务缺陷。

| 根因 | 诊断及条数 | S 中错误位置与写法 | 对照正确写法/位置 | 需求对应原句 | 归因 |
| --- | --- | --- | --- | --- | --- |
| S1：输出视图未声明 | SIR-SYMBOL-002 × 2 | S:212、231 使用 `output TokenPairView;`，但只声明了 TokenPair 实体，没有 TokenPairView | R:24–28 的 DualTokenLogin 会展开配套视图；手写时应像 `sir-toolchain-application/src/test/resources/valid/dual-token-written.sir:200–205` 声明四个令牌响应字段的视图 | 从零需求：“登录返回访问及刷新令牌，期限为 15 分钟与 7 天” | 业务要求清楚。P:39、56 说明视图和输出，但没明确强调“实体不会自动带同名 View，use 展开除外”；这是基本声明遗漏，补通用声明闭合检查比加选课提示合适。 |
| S2：全局有 error，却没列入当前能力 fails | SIR-FLOW-003 × 4 | Logout 的 S:257 用 Forbidden；RemovePrerequisite 的 S:336 用 CourseNotFound；Enroll 的 S:401 用 Forbidden；Drop 的 S:431 用 CourseNotFound；对应能力 fails 均缺该项 | R:223–227 的 Drop 列出 CourseNotFound；R:268–271 的 RemovePrerequisite 列出 CourseNotFound；R:178–191、232 用 StudentNotFound 承接加载学生。每个能力应为自己实际用到的错误列 fails，并排序 | 从零需求中的退出、先修配置、选退课均要求完整实现；需求不负责规定 SIR 声明语法。报错所需错误须按固定合同及对应失败语义选用，不能为过编译随意换错误 | **P:56 已明说，教师没遵守。** 不是 Forbidden 或 CourseNotFound 在全局不存在；编译器检查的是能力 fails 集合。 |
| S3：把当前查询行字段写成裸变量/自比较 | SIR-VALID-001 × 7，加 SIR-FLOW-001 × 1，共 8 | S:315 的 `targetCourse == targetCourse and required == required`；S:378 的 `student == student and course == course`；S:404 同样自比较；S:407 的 `targetCourse == course` 中裸 targetCourse 在该能力也未定义 | R:193 用 `item.student == actor and item.course == selected`；R:199–200 用根行 item 与 any 组合。普通 exists 根字段应写 `item.字段`，不能把 any 内的裸字段写法搬到外层 | “重复关系返回 409 PrerequisiteExists”；“重复为 409 CompletionExists”；“保存唯一的学生/课程选课记录”；“必须通过所有直接先修课程……不能用数量相等但课程不同的成绩集合冒充” | **主要是已有查询规则没遵守，手册也不完整。** P:67 写了 find 的 item 规则，P:75 给了 exists/count，但没明确说同一规则适用于它们；P:68 的 any 恰好使用裸字段，容易混淆两个作用域。 |
| S4：用跨引用取字段/不受支持的关联来表达跨表筛选 | SIR-VALID-001 × 4，加 SIR-TYPE-004 × 1，共 5 | S:409 的四处比较：裸 student、自 `item.course` 连读 weekday/startPeriod、把根行字段放右侧；S:407 的 `any(CompletedCourse, student == student and course == item.required)` 没有受支持的根行连接 | R:195–200 以 Course 为根：时段字段直接属于 item，用 `any(Enrollment, course == item and student == actor)` 关联选课；先修检查用 `any(Prerequisite, required == item and targetCourse == selected)` 与 `not any(CompletedCourse, course == item and student == actor)` | “同一学生同星期且半开节次区间重叠……首尾相接允许”；“必须通过所有直接先修课程……不能用数量相等但课程不同的成绩集合冒充” | **通用关联规则讲得不完整，同时已有比较形状规则被违反。** P:67 禁止不合形状的比较；P:68 只给一个正向 any 例子，没有说明两种允许连接、不能任意比较两表引用、不能靠多级字段自动 JOIN。 |

上述需求引文均来自 `experiments/slm/drafts/course-selection/requirement-scratch.md:1`，并进入 scratch task 的需求与接口合同。S1/S2 的诊断是语言层完整性要求，不宜硬说需求必须逐字写“声明视图”或“补 fails”。

### 编译器与语言依据

- 未定义类型检查：`sir-semantic/src/main/java/io/kcg/sir/semantic/internal/ResolvePass.java:1284–1292`。实体和视图是不同声明，不能仅凭命名推导。
- 能力级错误集合检查：`sir-semantic/src/main/java/io/kcg/sir/semantic/internal/ValidatePass.java:410–420`；语言解释在 L:730。
- 普通查询比较必须为 `item.直接字段` 在左、右侧不读取 item：同一 `ValidatePass.java:778–791`；L:1121。S:409 的 `item.course.weekday` 不是当前根实体的直接字段。
- exists/count 的 item 作用域：L:1537–1544；any 的连接形式与不能嵌套规则：L:1289。
- any 必须连接正确的根实体：`sir-semantic/src/main/java/io/kcg/sir/semantic/internal/TypePass.java:291–307`。`course == item.required` 不是手册例子中的“引用字段 == 根行 item”，也不是允许的“关联实体主键 == 根行引用”。不能把语法上能取成员当成目标可以生成任意关联查询。

### 为什么修复后从 5 条变成 19 条

父候选 a9030f28af53 的 5 条诊断在解析阶段；修复子候选将保留字变量 `target` 改名为 `targetCourse`，并删除普通 Name 的 `is present` 检查和两个不用的 count。对照父子 candidate 文件可见：TokenPairView 漏声明、四处 fails 漏项、主要查询写法都已在父候选存在。**19 比 5 大不等于新引入了 14 个错误：解析通过后才暴露语义错误。** 当前证据只能说这轮修复未完成，不能仅按诊断条数断言“改坏”。

### 静态修好后仍须验证的风险，不计入已观测失败

S:235–240 的 Refresh 只有检查旧令牌再普通更新，没有一次性状态守卫；S:316–318、379–381 的唯一写入没有 `persist … else` 兜底；S:431–435 的退课没有计数下限守卫；S:465 给管理员增加了需求没有授予的名单查询权限。此外，S:288 仍只有节次直接比较，面临改写题同类的数值缺值问题。它们分别关系到一次性刷新、并发重复写、计数不变量、教师归属和输入校验，但 **S 没过静态门，没有相应运行结果**。这也是只消灭编译器诊断还不够的原因。

## 三、建议：补哪些通用规则，哪些需要修复轮或更强模型

以下只是建议，不修改 v5、候选、任务或测试。

| 建议 | 可写成什么通用规则 | 本题关联 | 能否仅靠加手册保证解决 |
| --- | --- | --- | --- |
| 解释数值必填与运算依赖 | 数值 min/max 不负责 null；当前目标在工作流前对算术及内置函数依赖做 NotNull 检查；数学等价改写可能改变缺值响应。保持真实的范围/差值校验，不发明 `where notNull` 或给普通值使用 `is present` | 唯一的 24 项业务失败根因 | 值得补，但不能宣称尚未采样的新版手册一定修好 |
| 统一查询作用域说明 | find、exists、count 等普通谓词都用 `item.直接字段`；any 内裸名是关联实体字段，item 仍是外层根行；避免行变量与字段同名造成自比较误读 | S3 | 值得补通用正反例；仍要静态反馈确认 |
| 讲完整 any 连接与查询根选择 | 只用受支持的外键/主键连接；没有隐式多表 JOIN；需要读取哪张表的筛选字段，就优先以那张表为根，再用 any 关联；同一条关联记录的条件放同一个 any 中 | S4 | 可降低误用，不能替教师完成任意复杂关系设计 |
| 声明闭合与错误集合自查 | 除 use 确实展开的声明外，所有 output/input/view 都须有定义；逐能力收集 else 所用错误并列入排序的 fails | S1、S2 | S2 已有规则，再堆措辞收益有限；应交给静态修复反馈 |
| 保持业务合同，不只修编译 | 修复后逐项检查缺值、范围、权限、重复、并发和晚失败；对照响应结构，不用改错误码或加权限绕过测试 | S 的潜在问题、改写题修复无改动 | 需要运行反馈；不能用通用提示替代真实判卷 |
| 对无改动和多层错误设有界修复策略 | 父子 source_sha256 相同视为“未修复”；保留原轨迹，不重复把同代码当新进展。一次诊断修复仅清除解析错误时，继续给语义诊断而不宣称退化 | 1cc5c4027a2d、524cb80d7495 | 需要修复编排；若有限轮仍不能形成合法关联或仍原样输出，再比较更强模型 |

**推荐顺序：先补齐已证实缺失的通用机制，再做有界静态修复和真实运行反馈；不要只给选课题增加“这四个字段要写减法”的专用提示。** 已有规则反复违反、多个接口的声明/作用域不能同时保持一致，以及拿到运行反馈仍原样输出，才是需要更强模型的直接候选信号。现有样本不足以证明换模型必然成功，也不足以把问题都归为需求表达不清。
