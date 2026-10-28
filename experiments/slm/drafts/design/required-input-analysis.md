# 非可选输入必填：现状、影响与替代方案

## 0. 结论与口径

基线：`main` 的 `0d12cbb583c844fa077b765af269a02ce39087b2`。本报告只读源码、测试、语言参考和 Git 历史；不实施、不运行 Maven、不启动生成应用，不把下列源码推导写成当次 HTTP 实测。

**可以统一，但不是给所有 DTO 字段简单加 `@NotNull`。**当前非可选并不等于统一的请求必填：金额靠精度伴随的 `NotNull`，`Name/Code` 靠 `NotBlank`，算术等依赖靠能力级检查，分页靠步骤内默认，PATCH 靠信封和字段存在性。建议的长期方向是统一普通输入的必填契约，同时明确分页默认和 PATCH 三态；这是一项新协议，不是声称现状已经如此。【依据：下文 M:657–668、A:15–37,57–90、D:90–104,209–235、W:66–80,478–484,700–708。】

必须区分两种方案：**字面方案 A0**“一切非 Optional 输入字段都必填”，会取消现在非 Optional 分页字段的缺省；**兼容方案 A1**“普通非 Optional 输入必填，已有分页默认和 PATCH presence 按明确契约处理”，保留这些能力，但不是无例外的 A0。未被能力使用的普通非可选字段，在两者中仍应必填；若不希望客户端传，就拆分输入或声明 Optional，不能偷偷按使用情况豁免。【依据：语言参考 R:391–446,1954；算术共享输入回归 RT:28–31,124–139；未使用字段清单见 §4.3。】

### 引用缩写

下面所有 `缩写:行号` 都指此表中的完整仓库路径；测试和资源另用完整路径，或在小节声明目录前缀。范围内每项现状或建议均列出对应依据；“建议”不冒充当前规则。

| 缩写 | 文件 |
|---|---|
| R | `docs/SIR_LANGUAGE_REFERENCE.md` |
| P | `docs/design/12-feature-semantics-and-semantic-types.md` |
| M | `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/SpringBootModelLowerer.java` |
| A | `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/ArithmeticInputs.java` |
| I | `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/SpringBootIrValidation.java` |
| L | `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/model/SpringBootDeclaration.java` |
| D | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/DtoRenderer.java` |
| C | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/ControllerRenderer.java` |
| W | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/WorkflowRenderer.java` |
| E | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/ExpressionRenderer.java` |
| V | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/ValidationSupportRenderer.java` |
| T | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/TypeRenderer.java` |
| S | `sir-semantic/src/main/java/io/kcg/sir/semantic/feature/SemanticTypes.java` |
| RT | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/ArithmeticInputRuntimeTest.java` |
| TP | `sir-semantic/src/main/java/io/kcg/sir/semantic/internal/TypePass.java` |

行内只写 Java 文件名的引用，指本报告已给出完整路径的同名文件。

## 1. 现状：null 在哪一层处理

### 1.1 请求从解码到写入

1. **普通 DTO 全用包装/引用类型。**整数为 `Integer/Long`，布尔为 `Boolean`，引用降为目标主键的包装类型，时间、枚举、文本也都是对象。普通非 Optional 字段不初始化，未传和 JSON `null` 都留下 null；不存在统一的“未传数字取 0、布尔取 false”。只有 Optional 字段初始化为 `Optional.empty()`。【T:17–26,49–59；D:209–235。】
2. **DTO 注解先处理已有约束。**M 目前只在非 Optional 字段具有 `precision` 时额外加 `NOT_NULL`；并未按非 Optional 全量加。C 按输入是否含约束决定 `@Valid`，含请求体路径变量的情况先写入路径值，再手动校验，避免把 URL 已提供的字段误判缺失。【M:657–668；D:171–185,238–259；C:152–187,280–299。】
3. **工作流前再执行能力级依赖检查。**A 收集正式 input 绑定上的实际依赖，M 生成有来源和字段路径的计划，W 调用 `ValidationSupport.notNull`，返回 `400 INVALID_REQUEST`，字段码 `NotNull`。并非全 DTO 必填。【A:15–37,57–90；M:704–708；L:92–104；W:66–80；V:57–61。】
4. **不只是加减乘。**当前收集器也覆盖取负、`daysBetween/max/min/addDays` 的请求参数，以及 `repeat` 的次数；数值和时间依赖均可能被收集。比较本身不把输入升级为必填。遍历覆盖分支/循环体，因此短路不能免除已收集的必填。【A:17–28,60–79；R:1954,1967；RT:103–108。】
5. **持久化前验证的是目标候选及其已有约束。**普通 create/update 都把请求值赋给实体；PATCH 合并后检查目标约束。`min/max/email/length/timeZone` 接受 null，不能代替必填；整数边界校验先把 null 安全转换成 null，不补 0。【W:664–680,695–708,988–1036,1116–1153；V:75–108；`sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/TimeZoneValidationRenderer.java`:24–30。】
6. **最后才可能由 Java 或数据库拒绝。**普通比较未必安全，直接写入也没有普遍的非空检查。数据库非空性另按实体 Optional 决定；它不等于请求层已经返 400。异常 advice 只专门转换声明失败、载荷校验和解码失败，没有把任意 NPE/数据库异常伪装成 `NotNull`。【E:240–288；`sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/api/SpringBootDeploymentPlanner.java`:41–44,74–75；`sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/ApiExceptionAdviceRenderer.java`:42–70。】

### 1.2 各类型的实际边界

下表讨论**普通 input** 的未传/显式 null；带额外约束、涉及 Optional 的比较和具体工作流可能改变结果。尤其不能概括成“所有非可选数值比较都必定 500”：例如 null 作为查询参数可能只得不到匹配，未使用字段可能根本未被读取。【E:240–288；W:309–326；RT:124–139。】

| 类型 | 当前前置处理 | 未被前置检查拦住时 | 源码依据 |
|---|---|---|---|
| `Count/Quantity/Id/Serial/PageNo/PageSize` 及同底层别名 | 没有全局必填；`Quantity` 只有 min(1)。算术、取负、内置函数或 repeat 的依赖另加能力级检查 | 与非可选数值字面量作大小比较会拆箱，null 可导致 NPE→500；仅传给查询可能空结果/声明失败；直接写没有通用 NotNull，结果取决于目标约束、数据库及写入策略 | S:60–68；A:57–79；E:240–288；W:1000–1036,1149–1153 |
| `Money`、带 `precision` 的非可选 Decimal/别名 | 已有 DTO `@NotNull`；目标候选也带 NOT_NULL | 正常 HTTP 入口缺值已返 400；Optional 金额不在此列。单独绕过 Controller 不能把 DTO 注解当服务层检查 | S:79–81；M:657–668；D:238–259；W:1116–1121 |
| `Name/Code`，以及显式 `where notBlank` 的文本 | 已有 `@NotBlank`，拒绝 null、空串、纯空白 | 当前字段码是 `NotBlank`（DTO）或 `notBlank`（候选检查），**不是统一 NotNull** | S:70–73；D:240；V:64–70 |
| 普通 `Text/Remark/Email`，只有 length/email/timeZone 的文本或别名 | 未全局必填；length/email/timeZone 不拒绝 null | 相等可安全返回 true/false；大小比较可因 `compareTo` 接收 null 而 500；可进入查询或写入，不保证得到 400 | S:69,74–75；V:75–108；E:240–288；TimeZoneValidationRenderer:24–30 |
| `Secret` 或其别名的请求值 | `Secret` 自身不带 notBlank/NotNull | 摘要函数主动拒绝 null（非结构化必填错误）；密码匹配遇 null 返回 false，可以转为作者声明的认证失败，不能一概写 500 | S:76；`sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/SecretDigestRenderer.java`:47–60 |
| `Ref<Entity>` | DTO 是主键值；通常没有必填。整数主键的 `input.ref.id` 被算术消费时已有依赖检查，报真实 HTTP 属性如 `entryId` | 引用相等使用 `Objects.equals`，null 不一定报错；按 id/load 或关联查询可能只报声明的未找到；写入外键/非空列可能失败 | T:23；E:254–256；RT:86–98；W:309–326 |
| 枚举 | DTO 是枚举对象，无统一非空约束；非法枚举文本和合法 JSON null 不是同一问题 | `==/!=` 用 Java 枚举比较，null 可产生普通真假，不必 500；存库是否失败看目标列/策略 | T:20；E:245–246；D:230–235 |
| `Token`/UUID | 包装引用类型，无统一非空约束 | 相等用 `Objects.equals`，load 或写入表现依用途；不会自动造 UUID 充当客户端值（实体自动主键是另一条规则） | T:54–55；E:247–264；W:664–680 |
| `Moment/Day` | 对象类型，无统一必填；被相关内置函数消费时可进入 A 的时间必需计划 | 普通大小比较调用 `compareTo`，null 可 500；相等可安全比较；`month` 转换另有显式缺值检查 | T:56–57；A:48–49,61–75；E:247–275；`sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/CalendarStepRenderer.java`:22–34 |
| `Flag` | `Boolean`，没有隐式 false，也没有全局必填 | 相等使用 `Objects.equals`；作为布尔条件/与或的操作数需要拆箱，null 可 500 | T:50；E:247–264；RT:28–31,46–49 |
| DTO 所承载的 `List<T>` | 容器是引用类型，不自动初始化为空集合，也没有统一 NotNull | 容器 null 不等于空列表；不应把本次规则扩成未经设计的“所有集合元素也必填” | T:22；D:230–235；`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorEntityDtoContractTest.java`:131–145 |
| `Optional<T>` | 初始化为空；元素约束仍保留，Optional 文本使用可接受空值的自定义 notBlank | 缺省/null 是空；有值后校验内容；可选比较按空值表，不能参与算术 | D:209–225；R:111,1956–1964 |

因此，选课的 `input.weekday - 1 >= 0` 是在当前协议下借运算触发必填，而不是数值范围要求必须这样写；普通 `min/max` 不能完成这件事。【`sir-toolchain-application/src/test/resources/topics/course/project.sir`:117–120；`experiments/slm/drafts/course-selection/failure-analysis.md` §1；A:61–76；V:102–108。】

### 1.3 哪些会保留/采用默认值

| 情况 | 当前实际行为 | 依据 |
|---|---|---|
| 普通输入未传数值、布尔、枚举、时间 | DTO 保持 null，不是 0/false/枚举首值/当前时间 | D:230–235；T:49–59 |
| 分页字段未传或绑定为 null | `find`/分组分页步骤在局部变量中采用 page=1、size=计划 defaultSize，再检查上下界；**不回写 input**。所以同一输入再参加算术仍要求显式传 | W:478–484；W 的 `renderGroupedSum` 自 370 行起；R:1954；RT:124–139 |
| `create` 省略有 default 的实体字段 | Lowering 给本次 create 补一份默认表达式；显式绑定了 `input.x` 就不再补 default，不能靠该实体 default 自动修复 null 请求 | M:1232–1253；R:311,1297 |
| PATCH 未传某个 changes 字段 | 通过 `.has(property)` 保留原值，不是输入 default | D:111–149；W:700–708 |
| PATCH `id/expectedVersion` 未传 | 信封已有 `@NotNull`；changes 子字段不能因此全加必填。`changes` 对象本身在 DTO 中没有同样的注解，不能声称现状对它已有统一 NotNull | D:90–104；C:280–286 |

## 2. 为什么当初只处理算术依赖

这是**有记录的迭代范围选择**，不是找不到非 Optional 信息，也不是证明“比较/写入 null 应当 500”。

| 历史证据 | 当时的考虑 |
|---|---|
| `git log -S ArithmeticInputs -- sir-lowering-spring-boot` 定位引入提交 `85bfc8f9e7da3cf25c28e905766601a7c1f4b861`（2026-10-21），提交说明“按能力校验实际算术输入并保留分页与 PATCH 协议” | 精确乘法落地时先修自动拆箱/valueOf 的输入失败，不扩大为全字段输入契约。该提交版 P 的 §21.1、§21.3、§21.5 已包含下面的取舍，非事后补写的理由 |
| P:704 | DTO 数字全加 NotNull 会破坏 page/size 缺省；`Quantity.min` 不代表必填 |
| P:727–731 | 只检查本能力实际算术依赖；同一个 DTO 可由不同能力使用；正式 INPUT binding、字段 ID、路径、来源和顺序由 Lowering 计划提供，Generator 不扫描源码猜依赖；短路也不免除必填 |
| P:729,751 | 不在该轮统一非数值字段政策；PATCH absent/null 不变；明确拒绝“DTO 所有数字统一加 NotNull”这个替代方案 |
| A:11,26–27,61；RT:15,124–139 | 当前注释及真实生成代码运行测试仍把这项政策描述为“能力局部、非共享 DTO 全局变化”；repeat 次数不能缺值，内置函数无参数值无法计算 |
| 后续提交 `36bde24`（2026-10-24）、`4be06e7`（2026-10-25）；A:26–27,48–49,61–75；P:1608,1694 | 同一计划后来扩至内置函数的数值/时间参数和 repeat 次数，不再是仅有最初四种算术运算 |
| 更早金额提交 `a54c351`（2026-10-21）；M:657–668；P:638 | precision 已伴随非 Optional NotNull；金额规则并未发明整个语言的统一 null 协议 |

复核历史可用：`git log -S ArithmeticInputs -- sir-lowering-spring-boot`、`git show 85bfc8f -- docs/design/12-feature-semantics-and-semantic-types.md`、`git log --follow -- sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/ArithmeticInputs.java`。本轮已读取这些历史，不用旧设计代替当前源码；当前行为见 §1。

## 3. 若采用统一必填：语义与模块影响

### 3.1 先把新契约定清楚

| 边界 | 建议的 A1 行为（非现状声明） | 为什么/已有依据 |
|---|---|---|
| 普通 input 非 Optional 字段 | 不论是否比较、存库、做运算、在短路分支或暂未被使用，缺省/null 都在工作流前返回 400 INVALID_REQUEST + 实际 HTTP 字段路径 + NotNull | 消除靠表达式形状猜必填；复用 A/L/W 的正式绑定与 V.notNull。未使用字段的兼容性见 §4.3 |
| Optional 字段 | 继续允许缺省/null，不因统一规则而自动解包；有值才做内容约束 | R:111,1956；D:209–225 |
| 分页 | A0 要求传；A1 保留框架分页默认，但豁免必须绑定**本能力中实际分页参数及其默认计划**，不是按 `page/size` 名字猜。被另一个步骤用作算术/其他必需值时仍必填；不能把分页局部默认隐式回写 input | W:478–484；R:1954；P:729；RT:55–59,124–139 |
| 共享 input | A1 不承诺所有共享 DTO 老请求无变化：普通非可选但未使用的字段仍要求传。需要按能力拆输入或将真正可缺省字段改 Optional；纯分页例外只处理已声明的分页使用 | RT:28–31,124–139；§4.3 源码清单 |
| PATCH 信封 | 保留 id/expectedVersion 必填；明确 changes 对象的结构要求及缺失字段码，不机械把生成信封每个值当成普通 input 字段 | D:90–104；L 中 PatchSpec；R:402–446 |
| PATCH 子字段 | **未传保留；传 null 且目标 Optional 才可清空；传 null 且目标非可选应 400 NotNull，路径 changes.x**。按目标字段类型和 `.has` 判断，不能因 input patch 字段是 Count 就要求每次请求都传它 | D:111–149；W:700–708,988–1036；I:745–747。当前 min-only 非可选候选未普遍补 NotNull，不能假设它已覆盖所有显式 null |
| 带 default 的实体字段 | create 未绑定仍用实体 default；绑定请求字段后，该请求字段依其自身类型决定必填。不要把实体 default 偷变成 HTTP DTO default；input 语法本来也没声明字段 default | R:311,391–402,1297；M:1232–1253 |
| 路径参数 | 路径覆盖输入后才判必填；按目标属性报错，Ref 继续报 `xxxId`，不能报源码中的错误名字 | C:166–187；RT:86–98,110–122 |
| 错误优先级 | 建议解码成功、路径合并后先收集缺值错误，再做内容约束，最后才执行工作流；否则 Name 仍先报 NotBlank，不满足“null 统一 NotNull”。不改变鉴权过滤器、未知 JSON 字段、格式错误的既有处理顺序 | C:152–187；D:240–259；ApiExceptionAdviceRenderer:42–70 |

这不会自动把所有业务前置校验改为 NotNull：Optional 月份/区间仍可由 `month ... else BadPeriod` 或作者的 `validate ... else BadRange` 报声明错误；非空、空白、范围、合法枚举文本、缺值是不同问题。【R:1710,1956；`CalendarStepRenderer.java`:22–34；§4.1 Calendar/GroupedSum 依据。】

### 3.2 文件级影响（只列设计，不写代码）

以下按“使用能力级正式计划，兼容分页/PATCH”的 A1 估计，不宣称已经完成。**既有错误信封和 notNull 原语可复用；Parser 不需要仅为默认必填新增关键字。**【L:92–104；V:57–61；M:166–172；R:391–402。】

| 模块/文件 | 大概改动与必要性 | 当前落点 |
|---|---|---|
| Lowering：M | 从正式输入声明生成普通字段必需值计划；区分 Optional、分页默认、patch identity/changes。不能改通用 `lowerProperty` 给所有 persistent 字段乱加请求约束 | M:576–578,657–668,704–708 |
| Lowering：A | 将“算术依赖”从决定普通字段是否必填降为特殊消费的依据；保留分页字段确实参与算术/函数/repeat 时的强制要求。可重命名/拆成通用输入计划器，具体文件名在实施设计定，不再照业务题名写分支 | A:15–37,57–90 |
| Lowered IR：L | 将 RequiredArithmeticInput/能力字段推广为有字段符号、输入符号、真实路径、origin 的通用计划；明确分页与 PATCH 的条件，不只换 record 名字 | L:38–104 |
| Lowered IR 校验：I | 独立验证计划完整、不重复、顺序和 owner/path 正确；校验 Optional 不入普通必填、分页豁免有默认依据、PATCH change 不能无条件必填 | I:728–749,465–497 |
| Generator：W | 用现有 notNull/invalidRequest 渲染新计划；PATCH 根据 present 验证非可选目标的显式 null，避免 null 进入比较、getter 调用或写入后才炸；不重新解析 SIR | W:66–80,700–708,988–1036；V:57–61 |
| Generator：C | 为“null 必须报 NotNull”明确并调整必填与 Bean Validation 的顺序；请求体、查询绑定、路径注入都覆盖。仅服务前置补检查不足以覆盖 Name 已被 @Valid 先拦的情况 | C:152–187,280–299 |
| Generator：D（依最终顺序方案） | 普通 DTO 保持包装类型与 Optional 初始化；不以共享 DTO 的全量 @NotNull 取代能力计划。若信封或校验分组/顺序需要改，在这里同步表达，不破坏 changes.has | D:90–149,209–235 |
| Semantic/Parser | A1 默认策略本身不要求新语法或新 AST。若选择 §6 的显式约束方案，才扩约束白名单、验证、类型规则/写回等；不能只让 Generator 识别一个字符串 | R:391–402；`sir-semantic/src/main/java/io/kcg/sir/semantic/internal/SemanticTypeExpander.java`:60–70,100；M:671–685 |
| Application/CLI | 编译编排原则上不变，但生成文件内容和源→生成工程一致性变了：旧工作区要按工具链重新生成，不是手改生成工程 | `MAIN.md`:111；`sir-toolchain-application/src/test/java/io/kcg/sir/application/SemanticTypeDeclarationTest.java`:47–52 |
| 文档/测试 | §4、§5 所列资源、生成契约、运行测试和语言参考同步；本次不改 | §4–§5 |

**数据库结构通常不用因“请求变必填”而改变。**实体 Optional 与 schema nullable 已是另一套明确投影，不能把输入协议变更等同于给全部列做迁移。【`SpringBootDeploymentPlanner.java`:41–44,74–75；R:311。】

## 4. 既有测试和参考输入：逐项盘点

### 4.1 Application 下已存在的缺省/null 用例

本表目录前缀均为 `sir-toolchain-application/src/test/java/io/kcg/sir/application/conformance/`。这是源码中明确写出的请求/断言，不是本轮运行结果。A1 应保留“有意可缺省”的语义，并补新的普通必填用例。

| 文件与行号 | 依赖什么 | A0/A1 影响 |
|---|---|---|
| `QuerySliceBusinessConformanceIT.java`:201–208 | 只有 keyword、不传 page/size 仍 200，默认分页 | A0 必须改协议/断言；A1 必须保留 |
| `QuerySliceBusinessConformanceIT.java`:255–257 | keyword 本身缺失返回 400 | A1 保留 400，但统一字段码时检查原 NotBlank 与新 NotNull 的差异，不能只看状态 |
| `CourseSelectionContractTest.java`:100 | 生成分页代码仍含 input.page 为 null 的分支 | A0 若取消默认要改；A1 保留 |
| `CourseSelectionChecks.java`:98–106 | 四个数值逐一 missing/null，400 且点名真实 NotNull path | A1 应直接满足，不再依赖差值表达式；不能删这些边界断言 |
| `GroupedSumChecks.java`:34–35,40–41,92–94 | 分组默认分页；缺 since 走 BadGroupRange；label 是可缺省筛选值 | 分页同上；Optional 区间/label 必须继续可解码，由作者规则拒绝或跳过筛选，不能误变通用 NotNull |
| `CalendarLedgerChecks.java`:26–34,79–83 | 自然月查询不传页参数；缺 month 返回 BadPeriod；时区 null 返回 INVALID_REQUEST | A1 保留默认页；Optional month 不升级必填；时区的具体字段码需增加核对 |
| `WriteSliceBusinessConformanceIT.java`:204–241 | 只改 capacity 或 name，其余 changes 缺席；description=null 清空 | 两方案都必须专门处理 PATCH，否则失去局部更新；非可选显式 null 应另补用例 |
| `WriteSliceBusinessConformanceIT.java`:295–309 | id/expectedVersion 缺失已有 400 | 保留信封必填和字段路径，不能迁移时退回工作流后 NPE |
| `CrudAccessBusinessConformanceIT.java`:76,104–117 | stock 不由请求传而保留服务端 default；仅修改 body 的 PATCH | 不要把被 writeExclude 去掉的实体字段发明为请求必填；PATCH 继续按 presence |
| `MoneyBusinessConformanceIT.java`:75–100 | optional 金额可缺省/显式 null；balance 缺席保持、rebate=null 清空、非可选 balance=null 拒绝 | 保留 Optional/PATCH/已有金额 NotNull；不能把“已做金额”误写成所有类型都覆盖 |
| `G9TopicChecks.java`:23–31,49–63,86–90 | publisher/description/condition=null 合法；status/createdAt 来自服务端；不传筛选 condition 仍可浏览 | 保留 Optional/default/排除字段政策；不把筛选缺席升级成非法请求 |
| `MultiplicationBusinessConformanceIT.java`:71–74 | quantity missing/null 已经 400 | 新机制应继续拒绝，且有必要补非算术字段路径/无写入断言，不能只留算术样例 |

没有从 `BearerCredentialBusinessConformanceIT`、`DualTokenBusinessConformanceIT`、`GuardedTransitionBusinessConformanceIT` 的“未携带认证头”推导“输入字段可缺省”：它们不是同一个协议。也不把默认 HTTP 状态、缺源码文件、缺测试前提等搜索命中计成输入默认。【上述文件分别 :129,:157,:163–165；对应请求仍携带 text/id 或凭据值。】

### 4.2 Application `.sir` 中需保留的分页/PATCH/Optional/default

下表路径均相对 **`sir-toolchain-application/src/test/resources/`**；每项尾部数字是文件行号，能力行可定位整个分页步骤，字段行可定位缺省/Optional/default 的声明。逐文件列出，不把只有静态编译的参考文件说成已有 HTTP 必填验收。功能模板展开产生的字段另见表后说明。

| 文件 | 已有契约位置 |
|---|---|
| `deployment/dual-token/project.sir` | Note.pinned default :26 |
| `deployment/dual-token-v2/project.sir` | Note.pinned/state default :26–28 |
| `deployment/dual-token-v3/project.sir` | 分页能力 :105,122,138,152,166；Optional 筛选/评分输入 :54–66,101；default :21,27–28,44 |
| `topics/blog/project.sir` | 分页能力 :110,182,202,278,366；Post/Comment default :46–55 |
| `topics/booking/project.sir` | 分页能力 :97,204,228；User.role default :22 |
| `topics/course/project.sir` | 分页能力 :140,320,343；实体 default :22,48,52,58,71 |
| `topics/ledger/project.sir` | 分页能力 :85,111,181,216,263；Optional 区间 :134–140,176–177；Optional month :259,277 |
| `topics/library/project.sir` | 分页能力 :128,386,401,418；实体 default :24–25,50,56–62,70 |
| `topics/market/project.sir` | 分页能力 :84,184,201；Optional 筛选输入 :77–79；实体 default :43–44,54–55 |
| `topics/shop/project.sir` | 分页能力 :120,255,557,579；Optional NewProduct.description :91；实体 default :24,51,56–58,350 |
| `topics/tasks/project.sir` | 分页能力 :198,222,239,259；Optional NewTask.dueAt :84、TaskQuery 筛选 :193–194；实体 default :61–64 |
| `valid/account-registration-feature.sir` | User.role default :22；功能展开输入见 §4.4 |
| `valid/account-registration-written.sir` | User.role default :22 |
| `valid/calendar-month.sir` | Optional month/zone :7；缺值交由 month 的 else 处理 |
| `valid/course-admin-enrollment-add-list-refs.sir` | 分页 :128；Optional description :90,97；PATCH :94；额外未使用字段见 §4.3 |
| `valid/course-admin-enrollment-filter-any.sir` | 分页 :159；Optional description :88,95；PATCH :92 |
| `valid/course-admin-enrollment-readonly.sir` | 分页 :128；Optional description :90,97；PATCH :94 |
| `valid/course-admin-enrollment-remove-update.sir` | 分页 :143；Optional description :90,97；PATCH :94 |
| `valid/course-admin-enrollment-tighten.sir` | 分页 :161；Optional description :90,97；PATCH :94 |
| `valid/course-admin-enrollment.sir` | 分页 :159；Optional description :88,95；PATCH :92 |
| `valid/course-admin.sir` | Optional description :43,49；PATCH :46 |
| `valid/course-catalog.sir` | 分页 :39；page/size :35–36 |
| `valid/course-enrollment.sir` | 分页 :76 |
| `valid/crud-access.sir` | 实体 default :11,22,35；排除请求字段 :28,45；展开输入见 §4.4 |
| `valid/crud-feature.sir` | 实体 default :31,40；展开输入见 §4.4 |
| `valid/crud-written.sir` | 分页 :114,203,292,382；Optional 输入 :63,78,248,261；PATCH Note :75；实体 default :31,40 |
| `valid/decimal-group.sir` | 分组分页 :33–50；Optional since/until :28–29；实体 default :15 |
| `valid/default-value.sir` | Ticket default :22–27；input 只声明 title :30–32，不能把其余实体字段变为请求必填 |
| `valid/dual-token-written.sir` | RefreshToken.used default :176；具体输入随其声明自身类型处理 |
| `valid/entity-scope.sir` | Note.removed default :22 |
| `valid/g9-topic-conformance.sir` | 分页 :44,171,429,444,461；Optional 筛选 :38–40；实体 default :27–28,67–68,93,99–105,113 |
| `valid/money.sir` | Optional 金额 :18,30；PATCH :27；Optional 聚合条件 :130,184、month :319,337；分页 :188,204,323；实体 default :14,121,169,179 |
| `valid/multi-course/project.sir` | 分页 :110；Optional description :53,59；PATCH :56；模块实体不是额外 HTTP 输入 |
| `valid/multiplication.sir` | Course.enrolled default :105；共享 Numbers 见 §4.3 |
| `valid/related-request-values.sir` | 分页 :38–47；note 是非 Optional Text :33，不是可缺省筛选承诺，若统一必填会收紧缺值请求 |
| `valid/shop-conformance.sir` | 分页 :105,235,523,545；Optional NewProduct.description :76；实体 default :13,38,43–45,323,663；共享 Numbers 见 §4.3 |
| `valid/time-zone.sir` | OptionalZoneInput.zone :13；普通 ZoneInput 与 Optional 的区别需保留 |

这里只标记**声明允许缺省的形态和既有默认机制**，不把每个实体 default 都当作“HTTP 已测试不传”：决定其是否进入 DTO 的仍是 input/功能展开。模板参数的默认时长也不是 HTTP 字段默认。【R:311,391–402,685；`sir-toolchain-application/src/test/java/io/kcg/sir/application/FeatureUseTest.java`:48–58,89–93。】

### 4.3 共用输入但字段未使用：不能漏掉的兼容面

目录前缀同 §4.2。下表逐项列出普通 input 声明中，具体能力未读取的非 Optional 字段；它说明新契约可能比现状更严格，**不代表每一项都有现成 HTTP 成功请求或都需要修改源码**。

| 文件/字段声明 | 能力位置 | 影响 |
|---|---|---|
| `valid/course-admin-enrollment-add-list-refs.sir`:102–103 的 page/size | `ListCourseRefs`:148 | 该能力返回非分页列表，不消费这两个字段；如果统一普通字段必填，不能因字段名字像分页就无依据豁免 |
| `valid/money.sir`:18 的 Amount.id | `CreateWallet`:39 | 创建并不读 id；会新增必填要求，宜拆成真正的创建输入。现有 Money IT 的创建请求确实带 id，不能声称它一定失败（IT:86–88） |
| `valid/money.sir`:20 的 Movement.peer | `DebitBalance`:82 | 扣款不读 peer；统一后它仍必填，宜拆输入或明确客户端契约 |
| `valid/multiplication.sir`:19–20 的 Numbers.quantity/left/right | `Wide`:34 | Wide 只用 price/wide；新规则会收紧这些未消费字段 |
| `valid/shop-conformance.sir`:577–578 的 Numbers.quantity/left/right | `Wide`:592 | 与上一项相同，组合 fixture 也要一起检查，不能只改单个原始资源 |
| `valid/campus-market-actorless-readonly-base.sir`:33；`valid/campus-market-two-capabilities-remove-publish-goods.sir`:42；`valid/campus-market-two-capabilities.sir`:59 的 title | 分别 SearchGoods :36,:45,:62 | title 虽未被工作流读取，已经 `where notBlank`，旧 HTTP 校验本就拒绝缺值；新增政策主要改变 null 的字段码，不应把它算成新增必填字段 |
| `valid/campus-market-minimal.sir`:48；`valid/campus-market-minimal-add-search-goods.sir`:48 的 title | ListAvailableGoods :73；后者还含 SearchGoods :85 | 同样已经 notBlank；模型共享输入与实际使用无关的现象原本就存在 |

这也解释为什么不建议通过“扫描 getter 有没有被用到”决定普通字段必填：会使必填性继续跟实现细节变化。应由声明建立普通契约，确需兼容的分页/patch 由明确机制表达。【P:727–731；上述逐项声明/能力；`MAIN.md`:43–48。】

### 4.4 模板和其他模块的回归范围

| 位置 | 必须考虑的影响 |
|---|---|
| `sir-toolchain-application/src/test/java/io/kcg/sir/application/FeatureUseTest.java`:48–58；资源 `valid/dual-token-feature.sir`、`valid/dual-token-written.sir`、`valid/account-registration-feature.sir`、`valid/account-registration-written.sir` | use 与展开文本须保持生成结果等价；不能只收紧手写 input 而漏模板生成的普通输入 |
| `sir-toolchain-application/src/test/java/io/kcg/sir/application/CrudAccessContractTest.java`:201–206；资源 `valid/crud-feature.sir`、`valid/crud-written.sir`、`valid/crud-access.sir` | Crud 生成输入包含分页、patch、排除 default/服务端字段等组合；按展开后的正式输入判断，不给 feature 名做必填特例 |
| `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/ArithmeticInputRuntimeTest.java`:28–31,69–139 | 已有空共享 DTO 的 Bean Validation 成功和默认分页断言；新普通必填应修改“无关普通字段仍可缺省”的期待，同时保留专门分页默认、短路、路径注入、Ref 实际路径的覆盖，不能整个测试删除 |
| `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorEntityDtoContractTest.java`:126–145,166–205 | 精确注解块/NotNull 数量与生成顺序的契约；若选全 DTO 注解方案会广泛改动，能力计划方案也要证明没有错误污染共享 DTO |
| `sir-lowering-spring-boot/src/test/java/io/kcg/sir/lowering/springboot/MultiplicationLoweringTest.java`:30–36,61–74,111 | RequiredArithmeticInput 变通用计划后，完整性/错字段/缺项等 IR 硬化测试应同步，不只改 expected 字符串 |
| `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/OptionalPayloadRuntimeTest.java`:34–71 | 缺省 Optional 的查询绑定与比较真值表必须不变，不能把空值误绑定成 Optional 对象或升级为普通必填 |

这些是应调整/保留的范围，不是“当前一定失败”的实测清单。新回归还应覆盖非算术整数、普通文本、引用、UUID、枚举、时间、Flag、集合容器各自的 missing/null/合法值；同时断言没有进入工作流/写库，及 Name 的 null 为 NotNull、空白仍为内容约束。范围由 §1 的类型矩阵和 C/W 的入口顺序直接导出；重活按 `AGENTS.md`:57–81 交同 SHA CI，本轮不执行。

## 5. 语言参考哪些段落要改

| R 的章节/行号 | 采用统一策略后需要改什么 |
|---|---|
| §2 :84–111 | 明确类型可选性、请求必填、实体可空的关系；Name/Code 不再是读者唯一看得到的缺值拒绝示例。补“非空不等于非空白” |
| §3.2 :255 | `timeZone` 自身不管 null；当前“非可选就必填”的句子尚未由通用实现支持。若采 A1，明确它依赖新输入契约；若采数值-only，必须纠正该句，不能继续泛化 |
| §3.2 :311；§5.4 :1291–1297 | 区分 create 省略绑定的 default/静态完整性，和 HTTP 请求字段未传；不要让实体 default 自动吞掉请求 null |
| §3.3 :391–446 | 主定义普通必填、Optional 缺省/null、PATCH 的 absent/null/present、信封必填；说明统一输入规则不要求每个 changes 子字段每次都传 |
| §4.1 :726–760 | 输入声明成为接口契约；补共享输入的未使用非可选字段也要求传，不按 getter 是否被使用猜测 |
| §4.3 :895–974 | 请求体/查询参数、路径注入后检查、400 INVALID_REQUEST 和实际字段路径；明确解码错误、必填错误与业务失败的区别 |
| §5.3 :1113–1290；§5.11 :1639–1690 | 明确 find/分组分页默认与一般必填规则的关系；如果采 A0 就必须取消/迁移旧缺省承诺，不能两头同时写 |
| §5.12 :1691–1710 | Optional month/zone 缺值仍走声明的 BadPeriod；普通非可选输入若被新边界拦截，说明错误优先级，不能把两者混写 |
| §5.14 :1848–1904；§6 :1954,1967 | 用统一必填取代“只有算术依赖要求有值”；保留 repeat/函数/分页消费、Optional 不可算术、短路和 PATCH 的独立规则 |
| §6 :1956–1964 | Optional 空比较真值表不变；明确这不为普通非可选字段提供缺值默认，也不等于 PATCH .present |

**不能靠修改手册把尚未实现的 A1 宣告为现状。**已起草 v6 仍描述当前能力级检查；如批准语言变更，需在代码及验证完成后更新作者手册/生成契约，与 R 保持一致。【`MAIN.md`:9–17；R:1954；本次任务范围。】

## 6. 替代方案与三条设计原则

评价依据是 `AGENTS.md`:53–55：新业务能否组合已有能力；普通需求是否变绕口；一处修复能否让一类业务受益。下表中的新写法/新计划均为**建议**，不是已有语法。

| 方案 | 优点 | 代价/限制 | 三原则评价 |
|---|---|---|---|
| A0：字面所有非 Optional 输入必填，无分页例外 | 作者规则最简单，覆盖各类型，不需要靠运算触发 | 旧非 Optional page/size 会变必传；共享输入未使用字段也收紧；PATCH 必须另按 presence 解释。若想靠 Optional<PageNo> 保默认，现有分页要求 Int32，不能只改声明而不改语义/Lowering/Generator（TP:918–929） | ①可组合；②普通字段简化，但原先缺省分页变繁琐；③所有普通输入受益，兼容成本最大 |
| **A1：普通非可选必填 + 明确的分页默认/PATCH presence（推荐）** | 不增加业务专用语法；从边界统一拒绝缺值，保留现有常用默认和局部更新；复用 notNull、正式绑定、能力计划 | 要公开写出例外和错误顺序；共享输入中的普通未使用字段仍有迁移；需防止把豁免按名字猜测。依据 §3.1、P:727–731、W:478–484,700–708 | ①复用普通输入/Optional/Page/patch 组合；②去掉数值差值绕法，普通请求清楚；③数值、文本、引用、时间等共同受益，较符合三条 |
| B：只给所有非 Optional 数值输入补必填 | 缩小业务可见范围，直接补比较/存库整数的缺口；金额已有基础 | 仍要处理分页和 PATCH，同样不能直接全 DTO 加注解；时间、Text、枚举、Ref、Flag 继续不一致。Ref.id 的数值存储不应按 Java Long 误归为普通数字；来源类型/绑定必须明确。依据 S:60–81、M:657–668、RT:86–98,124–139 | ①复用现有数值计划；②数值需求不绕，但别的类型仍绕；③一类数值受益，作为短期收敛可行，但不宜冒称完整类型政策 |
| C：新增显式约束，例如拟议 `where notNull` | 作者逐字段选择，原接口不自动收紧；可保持分页默认；适于渐进迁移 | 当前不支持，需补约束语义、展开/写回、Lowering、DTO/候选与错误码；每个本来非可选的字段再写一次 required 信息，模型容易漏。Optional 与 notNull 冲突、PATCH 何时检查也要定义。依据 R:391–402、M:671–685、SemanticTypeExpander:60–70 | ①是通用约束组合；②普通必填更啰嗦且与 Optional 重叠；③所有明确标注处受益，未标注处仍可能 500 |
| D：保持旧协议，手工写 `Optional<T>` + `validate x is present else ...`；数值另靠运算触发 | 不需要改编译器，可由作者选择声明错误；Optional 空比较已存在 | 算术不能用 Optional，即使判过空也不能解包；差值/乘一只是为了必填，普通输入变绕口，错误码也可能不是字段级 NotNull。依据 R:1954–1956、A:61–76、course failure-analysis §1 | ①能组合一部分已有能力；②明显绕口；③逐能力逐字段修，不是共同收益，不能作长期方向 |

**建议结论：优先讨论 A1，而非直接全局 DTO 注解或永久数值特例。**先把分页默认、PATCH present-null、普通共享输入和错误码顺序写成同一输入契约，再实施通用 Lowering 计划与独立校验。若当前只能接受更小行为变化，B 可作为明确的数值补洞，但分页/PATCH 仍不能省；不建议为了规避实现问题增加每道业务的减法写法。【依据：§1–§4 的现状；P:704,727–731,751；`AGENTS.md`:53–55；`MAIN.md`:40–48,103–104。】

## 7. 本轮边界

只新增本报告；没有实现方案，没有修改现有 `.sir`、手册或源码；没有运行 Maven、模型 API、生成应用或数据库。上述“会影响/应测试”都是静态分析和建议，正式验收仍需覆盖获授权实现的同 SHA CI 证据，不能以历史测试代码或本报告代替通过结果。【`AGENTS.md`:57–81；`MAIN.md`:9–17,159–180。】
