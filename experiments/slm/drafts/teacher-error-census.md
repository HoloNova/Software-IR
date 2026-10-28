# 教师模型错误统计

## 口径与总览

统计输入为仓库 `b38fa2fa0dafe667d15eccf3ab86a1af56cdafda` 的 `datasets/sir/pilots/`、`datasets/sir/runs/`。只读取现存数据和源码，没有运行 Maven、重判或调用模型 API。P 表示 `datasets/sir/pilots`，R 表示 `datasets/sir/runs`；下文路径用 P/R 缩写。

- 共扫描 46 份 `checked*.jsonl`，其中教师记录 93 行。排除 `origin=existing_reference`，按候选 id 去重是 **47 份教师响应**，对应 46 种源码；不是 93 次独立生成。每轮 checked 文件会重复携带父候选，runs 也会重导入同一批响应。
- 静态主表统一使用 `toolchain_core_sha256=51d9566d725403473bcfcb3cac229575919c630ade17bb619e317996ca00b163`（下称 C）。同一 id、同一工具链的重复记录已核对源码与诊断一致。C 下 **20 份通过、27 份失败，103 条 ERROR，17 种 code**。
- 另有旧工具链 `907fdf89982636138a5aa35e33f3fbfe0d22516610f1175573bf01ec58f8c2a4`（H）上的 5 份历史评估，4 份失败、15 条 ERROR。跨工具链按 `(候选 id, 工具链)` 统计是 **52 次不同静态评估、118 条 ERROR**；不能把它们当 52 份教师响应，也不能将旧通过资格移给 C。
- 47 份中有 34 份初次生成、9 份编译器诊断修复、4 份运行反馈修复。初次静态通过 **11/34（32.4%）**；包含修复响应为 **20/47（42.6%）**。这些是有题目选择偏差的小样本，不是独立随机盲测。
- 现存 GitHub 业务门有 **19 次，14 通过、5 失败，共 37 项失败断言**；另有旧工具链下 1 次真实业务失败、14 项失败断言。全历史合计 20 次教师业务执行、6 份失败报告、51 项失败断言。参考答案的报告不计入。旧报告单列，不冒充当前 CI 资格。

每个短 ID 都是完整候选 id 最后 12 位，附录逐个给出 `task_id` 和 checked 文件行号；**完整 id = `<task_id>-teacher-<短ID>`**。原 SIR 路径为对应 checked 所在批次的 `samples/<完整id>/candidate.sir`。下文例子的行列号直接取诊断，源码片段与该文件核对。

## 一、所有静态失败，按诊断 code 计数

“条数”数 ERROR 诊断项，不数根因；“候选数”数 C 下含该 code 的不同 id。一行同时触发类型与流程规则、或解析器连带报错，仍按原始诊断分别计数。H 列只加历史评估，不参与后面的当前通过率。


| code | C 条数 | C 候选数 | H 追加条数 | 一句解释 | C 逐候选贡献 | 真实例子（候选:行:列） |
| --- | --- | --- | --- | --- | --- | --- |
| SIR-PARSE-001 | 26 | 12 | 6 | 语法不合规定；本批主要有保留字作变量、修饰顺序、输入 default、把约束当表达式和虚构 return 写法，也包含连带报错。 | `6ec3685a54cd`×3、`ef4ab1db87b9`×3、`eb0ce1da4119`×2、`a9030f28af53`×3、`1df6bb81718e`×2、`75d6da4889cd`×2、`e473808f70e8`×2、`82872fe06240`×2、`182e9f50beb7`×1、`f4b4a6071888`×2、`2f2f819780a2`×2、`4fe6870d899c`×2 | `6ec3685a54cd`:311:18：`validate target.published == false else CoursePublished;`<br>`ef4ab1db87b9`:310:18：`validate target.published == false else CoursePublished;` |
| SIR-VALID-001 | 13 | 3 | 1 | 通用语义形状不合法；本批为普通查询比较形状、fails 排序或 query 缺 readonly。 | `524cb80d7495`×11、`6702e9b70653`×1、`e2c157051e10`×1 | `524cb80d7495`:315:35：`exists Prerequisite where targetCourse == targetCourse and required == required as dup;`<br>`6702e9b70653`:160–161：`fails InvalidRefreshToken; fails ExpiredRefreshToken;` |
| SIR-PARSE-003 | 10 | 7 | 3 | 多余语法或实体成员顺序错误；同一处非法名字/顺序可能连带产生多条。 | `6ec3685a54cd`×2、`ef4ab1db87b9`×2、`a9030f28af53`×2、`75d6da4889cd`×1、`f4b4a6071888`×1、`2f2f819780a2`×1、`4fe6870d899c`×1 | `6ec3685a54cd`:310:42：`load Course by input.courseId as target for update else CourseNotFound;`<br>`ef4ab1db87b9`:309:42：`load Course by input.courseId as target for update else CourseNotFound;` |
| SIR-TYPE-001 | 9 | 5 | 4 | 绑定、返回或函数参数类型不匹配；本批含框架版本字段、令牌行当 Token 值及错误返回实体。 | `9b916df2eb27`×1、`25e7a4ca946e`×1、`ab5caa38a15d`×2、`3f240deb1301`×1、`6702e9b70653`×4 | `9b916df2eb27`:187:11：`version: 1;`<br>`25e7a4ca946e`:203:16：`return old;` |
| SIR-FLOW-007 | 8 | 2 | 0 | 新行尚未 persist 就读主键或当引用使用，UUID 也不能例外。 | `6702e9b70653`×4、`ef9ddc30eb79`×4 | `6702e9b70653`:146:24：`accessToken: access;`<br>`ef9ddc30eb79`:146:24：`accessToken: access.id;` |
| SIR-VALID-004 | 8 | 6 | 0 | 版本字段初始化/更新、版本化 persist 或 persist else 的使用不合法。 | `9b916df2eb27`×1、`bd642066c9d7`×2、`c10a33a33557`×1、`ab5caa38a15d`×2、`036fa8e447e7`×1、`3f240deb1301`×1 | `9b916df2eb27`:183:9：`create Post as made {`<br>`bd642066c9d7`:66:33：`persist credential else Invalid;` |
| SIR-SYMBOL-002 | 5 | 3 | 0 | 引用了未声明的类型或实体/输入不存在的字段。 | `524cb80d7495`×2、`ab5caa38a15d`×2、`e52b33f4295b`×1 | `524cb80d7495`:212:14：`output TokenPairView;`<br>`ab5caa38a15d`:48:13：`field expectedVersion: Version;` |
| SIR-FLOW-003 | 4 | 1 | 0 | 工作流使用的错误没有列入当前能力的 fails，并非一定没有全局 error。 | `524cb80d7495`×4 | `524cb80d7495`:257:61：`load RefreshToken by input.refreshToken as old else Forbidden;` |
| SIR-VALID-003 | 4 | 2 | 0 | patch 输入不满足结构要求，或使用了自带规则的字段类型。 | `ab5caa38a15d`×1、`e52b33f4295b`×3 | `ab5caa38a15d`:46:5：`input UpdateCourseInput patch of Course {`<br>`e52b33f4295b`:48:19：`field name: Name;` |
| SIR-FLOW-004 | 3 | 1 | 0 | query/readonly 中出现 update、persist、create 等写步骤。 | `e2c157051e10`×3 | `e2c157051e10`:201:9：`update old when old.used == false else InvalidRefreshToken {` |
| SIR-LEX-001 | 3 | 1 | 0 | 使用 SIR 没有的字符；本批三处均为 Java 风格三元表达式的问号。 | `75ff6c7c1924`×3 | `75ff6c7c1924`:94:36：`name: input.name.present ? input.name : course.name;` |
| SIR-VALID-007 | 3 | 2 | 0 | 版本化实体不能使用更新守卫或本字段原子增量。 | `ab5caa38a15d`×2、`e52b33f4295b`×1 | `ab5caa38a15d`:98:16：`update course when course.version == input.expectedVersion else StaleVersion {`<br>`e52b33f4295b`:94:16：`update course when course.version == input.expectedVersion else StaleVersion {` |
| SIR-FLOW-001 | 2 | 2 | 0 | 变量未定义，或 .present 用在普通输入而非 patch 字段。 | `524cb80d7495`×1、`036fa8e447e7`×1 | `524cb80d7495`:407:35：`exists Prerequisite where targetCourse == course and not any(CompletedCourse, student == student and course == item.required) as missing;`<br>`036fa8e447e7`:55:18：`validate input.name.present else EmptyChange;` |
| SIR-VALID-006 | 2 | 2 | 1 | load 按字段查询的方式不合法：主键不写字段名，非主键必须声明 unique。 | `0214246b5d17`×1、`6702e9b70653`×1 | `0214246b5d17`:123:29：`load AccessToken by id: input.token as access else BadToken;`<br>`6702e9b70653`:205:27：`load TokenPair by accessToken: old.id as pair else InvalidRefreshToken;` |
| SIR-TYPE-004 | 1 | 1 | 0 | any 没有受支持的、指向正确根行的外键/主键连接。 | `524cb80d7495`×1 | `524cb80d7495`:407:66：`exists Prerequisite where targetCourse == course and not any(CompletedCourse, student == student and course == item.required) as missing;` |
| SIR-VALID-002 | 1 | 1 | 0 | 把 Version 标记放在输入字段；版本标记只用于实体字段。 | `ab5caa38a15d`×1 | `ab5caa38a15d`:48:13：`field expectedVersion: Version;` |
| SIR-VALID-009 | 1 | 1 | 0 | 凭据认证主体不一致：把令牌实体当 actor，混入没有对应 credential 的认证方式。 | `1115da26ef0d`×1 | `1115da26ef0d`:109:7：`actor Ref<AccessToken>;` |



C 合计 103 条，H 追加 15 条，总计 118 条。17 种 code 均在表中，没有把提示文本中的诊断名误算成真实诊断。修复轮是否成功看下一节的父子轨迹，不能凭诊断条数下降就算成功。

### 历史评估的完整追溯（H，不重复扩大样本量）


| 短 ID | H 记录 | 结果 | 诊断贡献 |
| --- | --- | --- | --- |
| f4b4a6071888 | P/dual-token-v1/checked-r1.jsonl:2 | FAILED | SIR-PARSE-003×1、SIR-PARSE-001×2 |
| 2f2f819780a2 | P/dual-token-v1/checked-r1.jsonl:3 | FAILED | SIR-PARSE-003×1、SIR-PARSE-001×2 |
| 4fe6870d899c | P/dual-token-v1/checked-r1.jsonl:4 | FAILED | SIR-PARSE-003×1、SIR-PARSE-001×2 |
| 6702e9b70653 | P/dual-token-v1/checked-r1.jsonl:5 | FAILED | SIR-TYPE-001×4、SIR-VALID-001×1、SIR-VALID-006×1 |
| ef9ddc30eb79 | P/dual-token-v1/checked-r2.jsonl:6 | PASSED | 0 条 |



其中 `ef9ddc30eb79` 在 H 上曾静态通过，但 C 新增的主键持久化时点检查给出 4 条 SIR-FLOW-007；同一源码重检不是“教师又生成了一份更差答案”。`6702e9b70653` 则由 H 的 6 条变成 C 的 10 条。附录保留 C 结果，H 不被覆盖掉。

## 二、业务失败按根因归类

业务失败以 `runtime/<完整id>/gate-run.json` 的 failed_checks 为主，与实际报告及候选源码交叉核对。同一错误响应导致多个后续断言失败，只算一个根因类，表中的断言条数仍保留原值。


| 根因 | 候选及 prompt | 失败断言数 | 直接证据与错误写法 | 连带结果 |
| --- | --- | --- | --- | --- |
| 额外增加鉴权，导致退出根本没执行 | c9ab97cd12d2 / v5 / 从零 / 静态修复 | 3 | 候选:108–120 给 Logout 加 requires authenticated 和 owner==actor；BearerCredentialBusinessConformanceIT.java:157–163 明确用无 Authorization、含 token 的请求退出 | 退出 401，旧令牌仍可发帖，最终多出一条 note；不是三种独立错误 |
| 隐藏行只在列表过滤，详情漏了同一规则 | b6e7a5435998 / v5 / 从零 / 静态修复 | 3 | 候选 Post:28–34 没有 scope；GetPost:193–202 直接 load/return；ListPost:212 才过滤 state != "hidden" | 三种身份的隐藏文章详情都得到 200 而非 404；没有证据把它改名为版本或权限先后顺序问题 |
| 数值必填检查缺失 | dbbbac2d45bc / v5 / 改写 / 静态修复 | 24 | 候选:255–258、273 只有 min/max 和直接大小比较；CourseSelectionChecks.java:96–105 逐项检查缺失/null 的四个字段 | 4 字段×2 变体×3 断言；同一个 500 连带缺顶层 code 与 fields。详见 course-selection/failure-analysis.md |
| 关联存在谓词漏状态条件 | c6167c316f44 / v4 / 从零 / 初次 | 6 | 候选:68 是 any(Enrollment, course == item)，没有 status == EnrollmentStatus.ACTIVE | 仅 CANCELLED 的课程也入选，导致 total、入列集合、末页和空页总数连带错误；不是六种分页缺陷 |
| 退出流程凭空 create，成功码变成 201 | bd870a834f77 / auth / 改写 / 静态修复 | 1 | 候选:193–212 的 Logout 仍 create TokenPair（205 行）；生成器按是否含 create 判成功码 | 只确认退出返回 201 而非 200，不应把它解释成刷新并发失败 |



上述 5 份报告共 37 项失败。当前报告中没有独立证实“权限检查先后顺序错”或“版本并发实现错”：例如 `d6b3dce267c8` 的 CRUD 门、`51ab2996a20e`/`be9817697402` 的版本写入门、`1dff8d4a8a1c`/`857fcfe57b22` 的一次性轮换门都通过。静态没过、业务未运行的候选不能据此算业务正确。

### 没有 gate-run.json 的旧业务失败也不能漏掉

`ef9ddc30eb79` 的历史证据在 `R/dual-token-lab-v1/runtime/auth-dual-token-v1-teacher-ef9ddc30eb79/dual-token-slice-1a1721082e4-62691/`：`slm-runtime.json:4–8` 绑定 H 工具链与源码，`dual-token-slice-report.txt:25,45–58` 为 19 通过、14 失败。候选:145–151、180–187 在构造 TokenPair 后才 persist access/refresh，先复制了尚未生成的 UUID。`logs/spring.stdout.log:31–40` 直接记录 `INSERT INTO token_pair (access_expires_at, refresh_expires_at)` 缺令牌列，以及 `Field 'access_token' doesn't have a default value`。

这是 **1 个主键时点根因、14 项连带失败**：登录 500 后没有令牌，鉴权、刷新和六路并发刷新自然跟着失败，不能另外统计成已经证明的并发守卫错误。该历史样本在 C 下已经被静态门拒绝，不纳入当前 19 次 CI 的分母；历史全量再加它，才是 20 次执行、51 项失败断言。

## 三、按 prompt 版本与题型看通过率

版本通过 messages 中 system 内容与 `experiments/slm/prompts/*.md` 的全文匹配，不从目录名猜版本。修复响应沿用首条 system 的版本；追加的 repair 提示不冒充新 general 版本。`auth-dual-token-v1` 虽然名字没有 change，却有 base_source 且需求开头为“在给定软件上增加”，见 `experiments/slm/tasks/dual-token-v1.json:9–11`，所以归为改写。

### 初次生成（不把修复响应混入初次通过率）


| prompt | 题型 | 初次响应数 | 静态通过/初次 | 业务通过/已运行 | 初次候选 ID | 四门通过 ID |
| --- | --- | --- | --- | --- | --- | --- |
| auth | 改写 | 3 | 0/3（0.0%） | —（无样本） | `f4b4a6071888`、`2f2f819780a2`、`4fe6870d899c` | — |
| auth | 从零 | 0 | —（无样本） | —（无样本） | — | — |
| auth-v2 | 改写 | 3 | 0/3（0.0%） | —（无样本） | `75d6da4889cd`、`25e7a4ca946e`、`e473808f70e8` | — |
| auth-v2 | 从零 | 0 | —（无样本） | —（无样本） | — | — |
| general-v1 | 改写 | 2 | 1/2（50.0%） | 1/1（100.0%） | `07b9ea18ef67`、`ab5caa38a15d` | `07b9ea18ef67` |
| general-v1 | 从零 | 1 | 0/1（0.0%） | —（无样本） | `82872fe06240` | — |
| general-v2 | 改写 | 1 | 0/1（0.0%） | —（无样本） | `e52b33f4295b` | — |
| general-v2 | 从零 | 1 | 0/1（0.0%） | —（无样本） | `182e9f50beb7` | — |
| general-v3 | 改写 | 1 | 0/1（0.0%） | —（无样本） | `75ff6c7c1924` | — |
| general-v3 | 从零 | 1 | 1/1（100.0%） | 1/1（100.0%） | `cadd5a148bb8` | `cadd5a148bb8` |
| general-v4 | 改写 | 7 | 5/7（71.4%） | 5/5（100.0%） | `6ec3685a54cd`、`d6b3dce267c8`、`bd642066c9d7`、`905d204a57e4`、`d3c1df26ed80`、`b38e01722cc0`、`51ab2996a20e` | `d6b3dce267c8`、`905d204a57e4`、`d3c1df26ed80`、`b38e01722cc0`、`51ab2996a20e` |
| general-v4 | 从零 | 7 | 3/7（42.9%） | 2/3（66.7%） | `0214246b5d17`、`eb0ce1da4119`、`1df6bb81718e`、`1dff8d4a8a1c`、`c6167c316f44`、`bd4698114bcb`、`036fa8e447e7` | `1dff8d4a8a1c`、`bd4698114bcb` |
| general-v5 | 改写 | 2 | 0/2（0.0%） | —（无样本） | `ef4ab1db87b9`、`c10a33a33557` | — |
| general-v5 | 从零 | 5 | 1/5（20.0%） | 1/1（100.0%） | `1115da26ef0d`、`a9030f28af53`、`9b916df2eb27`、`561bdfe9ead2`、`3f240deb1301` | `561bdfe9ead2` |



初次合计 34 份，静态通过 11 份，11 份均有当前业务证据，其中 10 份四门通过。auth 两版本没有从零样本，不应写成“从零 0%”。v5 主要针对先前失败的难题补采，v4/v5 的题目分布不同，不能把 1/7 与 8/14 直接当成 prompt 升级因果效果。

### 包含修复响应的全记录（不是独立题目通过率）


| prompt | 题型 | 全部响应数 | 静态通过/全部 | 业务通过/已运行 | 四门通过/全部响应 | 四门通过 ID |
| --- | --- | --- | --- | --- | --- | --- |
| auth | 改写 | 7 | 1/7（14.3%） | 0/1（0.0%） | 0/7（0.0%） | — |
| auth | 从零 | 0 | —（无样本） | —（无样本） | —（无样本） | — |
| auth-v2 | 改写 | 3 | 0/3（0.0%） | —（无样本） | 0/3（0.0%） | — |
| auth-v2 | 从零 | 0 | —（无样本） | —（无样本） | —（无样本） | — |
| general-v1 | 改写 | 2 | 1/2（50.0%） | 1/1（100.0%） | 1/2（50.0%） | `07b9ea18ef67` |
| general-v1 | 从零 | 1 | 0/1（0.0%） | —（无样本） | 0/1（0.0%） | — |
| general-v2 | 改写 | 1 | 0/1（0.0%） | —（无样本） | 0/1（0.0%） | — |
| general-v2 | 从零 | 1 | 0/1（0.0%） | —（无样本） | 0/1（0.0%） | — |
| general-v3 | 改写 | 1 | 0/1（0.0%） | —（无样本） | 0/1（0.0%） | — |
| general-v3 | 从零 | 1 | 1/1（100.0%） | 1/1（100.0%） | 1/1（100.0%） | `cadd5a148bb8` |
| general-v4 | 改写 | 7 | 5/7（71.4%） | 5/5（100.0%） | 5/7（71.4%） | `d6b3dce267c8`、`905d204a57e4`、`d3c1df26ed80`、`b38e01722cc0`、`51ab2996a20e` |
| general-v4 | 从零 | 7 | 3/7（42.9%） | 2/3（66.7%） | 2/7（28.6%） | `1dff8d4a8a1c`、`bd4698114bcb` |
| general-v5 | 改写 | 5 | 3/5（60.0%） | 1/2（50.0%） | 1/5（20.0%） | `857fcfe57b22` |
| general-v5 | 从零 | 11 | 6/11（54.5%） | 4/6（66.7%） | 4/11（36.4%） | `efbdfe58ef70`、`3062e8f8e07b`、`561bdfe9ead2`、`be9817697402` |



这里业务分母只取有当前 gate-run 的候选；NOT_RUN 不作失败，也不作通过。全记录当前静态通过 20 份，其中 19 份运行、14 份四门通过；唯一静态通过但没有新业务门的是原样输出的 `1cc5c4027a2d`。H 的 `ef9ddc30eb79` 有历史业务失败，但不混进 C 分母。

## 四、修复轮效果

### 编译器诊断修复

共 9 次修复响应，涉及 7 条初始失败轨迹；按 C 统一复查，6/9（66.7%）修复响应静态通过，3/9 未通过。若只数每轮当时的静态结果则是 7/9：差别正是 H 曾放行的 `ef9ddc30eb79`，不能据此授予当前资格。按轨迹终点看，7 条中 6 条最终静态通过，选课从零轨迹仍失败。静态通过不等于业务合格：这 6 份均已送当前业务门，只有 `857fcfe57b22`、`be9817697402` 四门通过。


| 父 → 子 ID | prompt | 静态阶段/诊断条数（C） | 子静态结果 | 子业务结果（C） | 说明 |
| --- | --- | --- | --- | --- | --- |
| 1115da26ef0d → c9ab97cd12d2 | general-v5 | SEMANTIC / 1 → GENERATION / 0 | PASSED | FAILED | 已产生不同源码 |
| ef4ab1db87b9 → dbbbac2d45bc | general-v5 | PARSE / 5 → GENERATION / 0 | PASSED | FAILED | 已产生不同源码 |
| a9030f28af53 → 524cb80d7495 | general-v5 | PARSE / 5 → SEMANTIC / 19 | FAILED | NOT_RUN | 解析通过后暴露语义错误，不能仅按条数认定改坏 |
| 9b916df2eb27 → b6e7a5435998 | general-v5 | SEMANTIC / 2 → GENERATION / 0 | PASSED | FAILED | 已产生不同源码 |
| c10a33a33557 → 857fcfe57b22 | general-v5 | SEMANTIC / 1 → GENERATION / 0 | PASSED | PASSED | 已产生不同源码 |
| 3f240deb1301 → be9817697402 | general-v5 | SEMANTIC / 2 → GENERATION / 0 | PASSED | PASSED | 已产生不同源码 |
| f4b4a6071888 → 6702e9b70653 | auth | PARSE / 3 → SEMANTIC / 10 | FAILED | NOT_RUN | 解析修好，进入语义阶段；仍未完成 |
| 6702e9b70653 → ef9ddc30eb79 | auth | SEMANTIC / 10 → SEMANTIC / 4 | FAILED | NOT_RUN | H 曾通过，C 仍拒绝主键时点；不是新生成退化 |
| ef9ddc30eb79 → bd870a834f77 | auth | SEMANTIC / 4 → GENERATION / 0 | PASSED | FAILED | 已产生不同源码 |



### 运行反馈修复

共 4 次：2 次产生新源码且四门通过，1 次原样输出，1 次由静态通过改成静态失败。四门修好为 2/4（50.0%）；不能只用“送判的两份都通过”掩盖未改动与退化。


| 父 → 子 ID | 静态变化 | 源码/业务结果 | 结论 |
| --- | --- | --- | --- |
| c9ab97cd12d2 → efbdfe58ef70 | PASSED → PASSED | 改了 Logout，新的 CI 36/0 | 修好 |
| b6e7a5435998 → 3062e8f8e07b | PASSED → PASSED | 补隐藏行拒绝，新的 CI 48/0 | 修好 |
| dbbbac2d45bc → 1cc5c4027a2d | PASSED → PASSED | source_sha256 同为 c252b63f41c237f2576a557b416c3dc0ca6e05bed9000818345f60745b2a0f71，逐字节相同；新业务 NOT_RUN | 没改，不是修复成功 |
| bd870a834f77 → e2c157051e10 | PASSED → FAILED | 只把 Logout 的 expose command 改成 expose query，仍含写步骤；新增 1 条 SIR-VALID-001 和 3 条 SIR-FLOW-004；业务 NOT_RUN | 明确改坏：修状态码却破坏接口与只读规则 |



13 次修复中只有 `1cc5c4027a2d` 未改变源码。`524cb80d7495` 从解析 5 条到语义 19 条是“推进但没修完”；`ef9ddc30eb79` 的旧通过被新检查拒绝是工具链变化；二者都不能冒称与 `e2c157051e10` 同样的退化。父子关系与 generation_mode 见附录各自的 checked 行和父 id。

## 五、对照 v5：哪些已写却仍违反，哪些没写到

以下区分“这批 v5 响应实际见过该规则”和“历史错误已被 v5 补写”。不把使用 auth/v1/v2 的旧候选说成读过 v5。


| v5 已有规则 | v5 原文位置 | 仍违反的 v5 候选 | 判断 |
| --- | --- | --- | --- |
| 保留字不能作变量，明确点名 target | sir-0.3-general-v5.md:12 | ef4ab1db87b9、a9030f28af53 | 两份初次响应仍把 target 当变量；不是手册没列保留字 |
| create 不绑定 Version；由框架处理 | sir-0.3-general-v5.md:69，另见38 | 9b916df2eb27:187、3f240deb1301:59 | 两份初次响应仍手写 version:1 / version:0；2 个同类根因、4 条类型/规则诊断 |
| persist else 只有版本冲突或新建行唯一冲突两类 | sir-0.3-general-v5.md:71 | c10a33a33557:66 | 把普通守卫更新后的 persist 也加 else；手册已明确“守卫更新后也一样” |
| 工作流所用错误必须写入 fails | sir-0.3-general-v5.md:56 | 524cb80d7495:257、336、401、431 | 同一份修复响应漏 4 处；不是全局 error 缺失 |
| 普通查询比较 item.字段 在左、右侧不读 item | sir-0.3-general-v5.md:67 | 524cb80d7495 的 11 条 SIR-VALID-001 | 规则写了 find，但没有把 exists/count 同一作用域说明白，属于已有形状规则被违反兼有表述缺口 |



历史中重复出现的实体成员顺序（`f4b4a6071888`、`2f2f819780a2`、`4fe6870d899c`、`75d6da4889cd`）、早读主键（`6702e9b70653`、`ef9ddc30eb79`）、patch 输入规则（`ab5caa38a15d`、`e52b33f4295b`）和三元表达式（`75ff6c7c1924`）都在 v5 有针对性的通用说明，但这些候选当时用的是 auth/auth-v2/general-v1～v3，不能拿它们证明“v5 这几条仍无效”。


| v5 缺失或不完整的通用规则 | 现有失败证据 | 应补内容与权威依据 |
| --- | --- | --- |
| 数值范围不等于必填；算术依赖触发 NotNull | dbbbac2d45bc 的 24 项；1cc5c4027a2d 原样返回 | 语言参考:1954；解释算术输入在工作流前校验，不发明 notNull 语法 |
| 凭据 actor 应是 subject 实体，不能混用认证方式 | 1115da26ef0d 的 SIR-VALID-009 | 语言参考:389；不只是“authenticated 配 actor”，还要与 credential 主体一致 |
| find/exists/count 普通字段与 any 内裸字段的作用域不同 | 524cb80d7495 的自比较、未定义变量与关联错误 | 语言参考:1121、1289、1544；列清两种 any 连接、禁止隐式多级 JOIN，查询以需要直接筛选字段的实体为根 |
| scope 对所有读取路径生效；只在 List 过滤不覆盖 Get | b6e7a5435998 的 3 项 | 语言参考:342–349、2166、2196；公开读取也不能绕过实体默认范围，或各路径明确做等效检查 |
| 所有引用声明须闭合，实体不会自动生成同名视图 | 524cb80d7495 的 TokenPairView | 语言参考:115、450；use 确实展开的声明另算，手写实体时应显式给输出视图 |
| 鉴权要求从需求来，不能擅自给 token 参数接口再加 Bearer 前置条件 | c9ab97cd12d2 的 3 项 | v5:93 只有总的合同遵守要求；建议通用地分开“凭据参数”和“当前身份”，不要写成只针对 Logout 的提示 |



另外，v5:72 把“守卫下不能读目标其他字段”写成绝对规则，漏掉 `load … for update` 后的例外；v5:39–40 对嵌套深度、计数视图返回来源的说明也不完整。这些需要在手册审计中修正，但本批没有直接证据把 37 项业务失败归因于它们。

建议优先补齐数值缺值、查询作用域/关联、认证主体和统一可见性这几条通用机制；对已经明写仍重复违反的 Version、保留字、fails、persist else，用编译器反馈检查修复是否真的改变了代码。所有新答案仍须真实业务门验收；本分析没有执行这些后续工作。

## 附录 A：47 份教师响应的逐 ID 账本（C）

此表覆盖所有初次与修复响应。JSONL 行号从 1 开始；每行包含完整 messages、sir、generation_mode、parent_id、static_result。SIR 可按文首路径公式定位；JSONL 与 candidate.sir 已逐字节核对。业务 NOT_RUN 仅指 C 的当前证据，历史 H 另见前表。


| 短 ID | task_id（完整 ID 的前缀） | prompt/题型/阶段 | 静态原始记录 | 父短 ID | 静态（诊断数） | 业务 |
| --- | --- | --- | --- | --- | --- | --- |
| f4b4a6071888 | auth-dual-token-v1 | auth/改写/初次 | R/dual-token-recheck-v2/checked.jsonl:2 | — | FAILED（3） | NOT_RUN |
| 2f2f819780a2 | auth-dual-token-v1 | auth/改写/初次 | R/dual-token-recheck-v2/checked.jsonl:3 | — | FAILED（3） | NOT_RUN |
| 4fe6870d899c | auth-dual-token-v1 | auth/改写/初次 | R/dual-token-recheck-v2/checked.jsonl:4 | — | FAILED（3） | NOT_RUN |
| 6702e9b70653 | auth-dual-token-v1 | auth/改写/静态修复 | R/dual-token-recheck-v2/checked.jsonl:5 | f4b4a6071888 | FAILED（10） | NOT_RUN |
| ef9ddc30eb79 | auth-dual-token-v1 | auth/改写/静态修复 | R/dual-token-recheck-v2/checked.jsonl:6 | 6702e9b70653 | FAILED（4） | NOT_RUN |
| bd870a834f77 | auth-dual-token-v1 | auth/改写/静态修复 | R/dual-token-recheck-v2/checked-r1.jsonl:7 | ef9ddc30eb79 | PASSED（0） | FAILED |
| e2c157051e10 | auth-dual-token-v1 | auth/改写/运行修复 | R/dual-token-gate-v1/checked-r1.jsonl:8 | bd870a834f77 | FAILED（4） | NOT_RUN |
| 75d6da4889cd | auth-dual-token-v1 | auth-v2/改写/初次 | P/dual-token-v2/checked.jsonl:2 | — | FAILED（3） | NOT_RUN |
| 25e7a4ca946e | auth-dual-token-v1 | auth-v2/改写/初次 | P/dual-token-v2/checked.jsonl:3 | — | FAILED（1） | NOT_RUN |
| e473808f70e8 | auth-dual-token-v1 | auth-v2/改写/初次 | P/dual-token-v2/checked.jsonl:4 | — | FAILED（2） | NOT_RUN |
| 07b9ea18ef67 | auth-bearer-credential-change-v1 | general-v1/改写/初次 | P/bearer-credential-change-g1/checked.jsonl:2 | — | PASSED（0） | PASSED |
| 82872fe06240 | course-query-slice-scratch-v1 | general-v1/从零/初次 | P/query-slice-scratch-g1/checked.jsonl:2 | — | FAILED（2） | NOT_RUN |
| ab5caa38a15d | course-write-slice-change-v1 | general-v1/改写/初次 | P/write-slice-change-g1/checked.jsonl:2 | — | FAILED（10） | NOT_RUN |
| 182e9f50beb7 | course-query-slice-scratch-v1 | general-v2/从零/初次 | P/query-slice-scratch-g2/checked.jsonl:2 | — | FAILED（1） | NOT_RUN |
| e52b33f4295b | course-write-slice-change-v1 | general-v2/改写/初次 | P/write-slice-change-g2/checked.jsonl:2 | — | FAILED（5） | NOT_RUN |
| cadd5a148bb8 | course-query-slice-scratch-v1 | general-v3/从零/初次 | P/query-slice-scratch-g3/checked.jsonl:2 | — | PASSED（0） | PASSED |
| 75ff6c7c1924 | course-write-slice-change-v1 | general-v3/改写/初次 | P/write-slice-change-g3/checked.jsonl:2 | — | FAILED（3） | NOT_RUN |
| 0214246b5d17 | auth-bearer-credential-scratch-v1 | general-v4/从零/初次 | P/bearer-credential-scratch-g4/checked.jsonl:2 | — | FAILED（1） | NOT_RUN |
| d6b3dce267c8 | auth-crud-access-change-v1 | general-v4/改写/初次 | P/crud-access-change-g4/checked.jsonl:2 | — | PASSED（0） | PASSED |
| 1df6bb81718e | auth-crud-access-scratch-v1 | general-v4/从零/初次 | P/crud-access-scratch-g4/checked.jsonl:2 | — | FAILED（2） | NOT_RUN |
| bd642066c9d7 | auth-guarded-transition-change-v1 | general-v4/改写/初次 | P/guarded-transition-change-g4/checked.jsonl:2 | — | FAILED（2） | NOT_RUN |
| 1dff8d4a8a1c | auth-guarded-transition-scratch-v1 | general-v4/从零/初次 | P/guarded-transition-scratch-g4/checked.jsonl:2 | — | PASSED（0） | PASSED |
| b38e01722cc0 | auth-token-desk-change-v1 | general-v4/改写/初次 | P/token-desk-change-g4/checked.jsonl:2 | — | PASSED（0） | PASSED |
| bd4698114bcb | auth-token-desk-scratch-v1 | general-v4/从零/初次 | P/token-desk-scratch-g4/checked.jsonl:2 | — | PASSED（0） | PASSED |
| 6ec3685a54cd | course-course-selection-change-v1 | general-v4/改写/初次 | P/course-selection-change-g4/checked.jsonl:2 | — | FAILED（5） | NOT_RUN |
| eb0ce1da4119 | course-course-selection-scratch-v1 | general-v4/从零/初次 | P/course-selection-scratch-g4/checked.jsonl:2 | — | FAILED（2） | NOT_RUN |
| 905d204a57e4 | course-query-slice-change-v1 | general-v4/改写/初次 | P/query-slice-change-g4/checked.jsonl:2 | — | PASSED（0） | PASSED |
| d3c1df26ed80 | course-relation-slice-change-v1 | general-v4/改写/初次 | P/relation-slice-change-g4/checked.jsonl:2 | — | PASSED（0） | PASSED |
| c6167c316f44 | course-relation-slice-scratch-v1 | general-v4/从零/初次 | P/relation-slice-scratch-g4/checked.jsonl:2 | — | PASSED（0） | FAILED |
| 51ab2996a20e | course-write-slice-change-v1 | general-v4/改写/初次 | P/write-slice-change-g4/checked.jsonl:2 | — | PASSED（0） | PASSED |
| 036fa8e447e7 | course-write-slice-scratch-v1 | general-v4/从零/初次 | P/write-slice-scratch-g4/checked.jsonl:2 | — | FAILED（2） | NOT_RUN |
| 1115da26ef0d | auth-bearer-credential-scratch-v1 | general-v5/从零/初次 | P/bearer-credential-scratch-g5/checked.jsonl:2 | — | FAILED（1） | NOT_RUN |
| c9ab97cd12d2 | auth-bearer-credential-scratch-v1 | general-v5/从零/静态修复 | P/bearer-credential-scratch-g5/checked-r2.jsonl:3 | 1115da26ef0d | PASSED（0） | FAILED |
| efbdfe58ef70 | auth-bearer-credential-scratch-v1 | general-v5/从零/运行修复 | P/bearer-credential-scratch-g5/checked-r2.jsonl:4 | c9ab97cd12d2 | PASSED（0） | PASSED |
| 9b916df2eb27 | auth-crud-access-scratch-v1 | general-v5/从零/初次 | P/crud-access-scratch-g5/checked.jsonl:2 | — | FAILED（2） | NOT_RUN |
| b6e7a5435998 | auth-crud-access-scratch-v1 | general-v5/从零/静态修复 | P/crud-access-scratch-g5/checked-r2.jsonl:3 | 9b916df2eb27 | PASSED（0） | FAILED |
| 3062e8f8e07b | auth-crud-access-scratch-v1 | general-v5/从零/运行修复 | P/crud-access-scratch-g5/checked-r2.jsonl:4 | b6e7a5435998 | PASSED（0） | PASSED |
| c10a33a33557 | auth-guarded-transition-change-v1 | general-v5/改写/初次 | P/guarded-transition-change-g5/checked.jsonl:2 | — | FAILED（1） | NOT_RUN |
| 857fcfe57b22 | auth-guarded-transition-change-v1 | general-v5/改写/静态修复 | P/guarded-transition-change-g5/checked-r1.jsonl:3 | c10a33a33557 | PASSED（0） | PASSED |
| ef4ab1db87b9 | course-course-selection-change-v1 | general-v5/改写/初次 | P/course-selection-change-g5/checked.jsonl:2 | — | FAILED（5） | NOT_RUN |
| dbbbac2d45bc | course-course-selection-change-v1 | general-v5/改写/静态修复 | P/course-selection-change-g5/checked-r2.jsonl:3 | ef4ab1db87b9 | PASSED（0） | FAILED |
| 1cc5c4027a2d | course-course-selection-change-v1 | general-v5/改写/运行修复 | P/course-selection-change-g5/checked-r2.jsonl:4 | dbbbac2d45bc | PASSED（0） | NOT_RUN |
| a9030f28af53 | course-course-selection-scratch-v1 | general-v5/从零/初次 | P/course-selection-scratch-g5/checked.jsonl:2 | — | FAILED（5） | NOT_RUN |
| 524cb80d7495 | course-course-selection-scratch-v1 | general-v5/从零/静态修复 | P/course-selection-scratch-g5/checked-r1.jsonl:3 | a9030f28af53 | FAILED（19） | NOT_RUN |
| 561bdfe9ead2 | course-relation-slice-scratch-v1 | general-v5/从零/初次 | P/relation-slice-scratch-g5/checked.jsonl:2 | — | PASSED（0） | PASSED |
| 3f240deb1301 | course-write-slice-scratch-v1 | general-v5/从零/初次 | P/write-slice-scratch-g5/checked.jsonl:2 | — | FAILED（2） | NOT_RUN |
| be9817697402 | course-write-slice-scratch-v1 | general-v5/从零/静态修复 | P/write-slice-scratch-g5/checked-r1.jsonl:3 | 3f240deb1301 | PASSED（0） | PASSED |



## 附录 B：当前 19 次业务门证据

每项 gate-run 都含 checks_passed、checks_failed、failed_checks（失败时）、run_url、head_sha 和 artifact 元数据；同目录报告可核对每项断言。表内路径的 `<ID>` 是该行完整候选 id，展开规则同附录 A；短 id 对应关系唯一。


| 短 ID | 通过/失败断言 | gate-run 文件 | CI run | commit SHA |
| --- | --- | --- | --- | --- |
| bd870a834f77 | 34/1 | R/dual-token-gate-v1/runtime/<ID>/gate-run.json | [run 40330717774](https://github.com/HoloNova/Software-IR/actions/runs/40330717774) | f58b05e1d3f879a1e0861f9f77e97641f60961d0 |
| 07b9ea18ef67 | 36/0 | P/bearer-credential-change-g1/runtime/<ID>/gate-run.json | [run 40619277037](https://github.com/HoloNova/Software-IR/actions/runs/40619277037) | fcb8de95c3db9939655e136f8db562f3017a2313 |
| cadd5a148bb8 | 41/0 | P/query-slice-scratch-g3/runtime/<ID>/gate-run.json | [run 40619824442](https://github.com/HoloNova/Software-IR/actions/runs/40619824442) | c509c4c79fd092e75fd35004954edc054724bd72 |
| 51ab2996a20e | 62/0 | P/write-slice-change-g4/runtime/<ID>/gate-run.json | [run 40619980837](https://github.com/HoloNova/Software-IR/actions/runs/40619980837) | d445309679a574661fa0c72a7b29969e333c83da |
| d6b3dce267c8 | 48/0 | P/crud-access-change-g4/runtime/<ID>/gate-run.json | [run 40620875519](https://github.com/HoloNova/Software-IR/actions/runs/40620875519) | cd752b3b2896e84a9ebedec7957083a85ab00027 |
| c6167c316f44 | 35/6 | P/relation-slice-scratch-g4/runtime/<ID>/gate-run.json | [run 40620893330](https://github.com/HoloNova/Software-IR/actions/runs/40620893330) | cd752b3b2896e84a9ebedec7957083a85ab00027 |
| 1dff8d4a8a1c | 25/0 | P/guarded-transition-scratch-g4/runtime/<ID>/gate-run.json | [run 40621086545](https://github.com/HoloNova/Software-IR/actions/runs/40621086545) | 306cc67196cd3a438b2d4f4eaf41d740aad5ac51 |
| b38e01722cc0 | 34/0 | P/token-desk-change-g4/runtime/<ID>/gate-run.json | [run 40621089007](https://github.com/HoloNova/Software-IR/actions/runs/40621089007) | 306cc67196cd3a438b2d4f4eaf41d740aad5ac51 |
| bd4698114bcb | 34/0 | P/token-desk-scratch-g4/runtime/<ID>/gate-run.json | [run 40621091701](https://github.com/HoloNova/Software-IR/actions/runs/40621091701) | 306cc67196cd3a438b2d4f4eaf41d740aad5ac51 |
| 905d204a57e4 | 41/0 | P/query-slice-change-g4/runtime/<ID>/gate-run.json | [run 40621094499](https://github.com/HoloNova/Software-IR/actions/runs/40621094499) | 306cc67196cd3a438b2d4f4eaf41d740aad5ac51 |
| d3c1df26ed80 | 41/0 | P/relation-slice-change-g4/runtime/<ID>/gate-run.json | [run 40621097340](https://github.com/HoloNova/Software-IR/actions/runs/40621097340) | 306cc67196cd3a438b2d4f4eaf41d740aad5ac51 |
| 561bdfe9ead2 | 41/0 | P/relation-slice-scratch-g5/runtime/<ID>/gate-run.json | [run 40622055593](https://github.com/HoloNova/Software-IR/actions/runs/40622055593) | 94255509afaf3ba40917398986e068196f6297fc |
| c9ab97cd12d2 | 33/3 | P/bearer-credential-scratch-g5/runtime/<ID>/gate-run.json | [run 40622420133](https://github.com/HoloNova/Software-IR/actions/runs/40622420133) | fb668386c9a131bb62606515aee1fbaad3b7b920 |
| b6e7a5435998 | 45/3 | P/crud-access-scratch-g5/runtime/<ID>/gate-run.json | [run 40622422717](https://github.com/HoloNova/Software-IR/actions/runs/40622422717) | fb668386c9a131bb62606515aee1fbaad3b7b920 |
| 857fcfe57b22 | 25/0 | P/guarded-transition-change-g5/runtime/<ID>/gate-run.json | [run 40622425058](https://github.com/HoloNova/Software-IR/actions/runs/40622425058) | fb668386c9a131bb62606515aee1fbaad3b7b920 |
| dbbbac2d45bc | 128/24 | P/course-selection-change-g5/runtime/<ID>/gate-run.json | [run 40622427838](https://github.com/HoloNova/Software-IR/actions/runs/40622427838) | fb668386c9a131bb62606515aee1fbaad3b7b920 |
| be9817697402 | 62/0 | P/write-slice-scratch-g5/runtime/<ID>/gate-run.json | [run 40622430172](https://github.com/HoloNova/Software-IR/actions/runs/40622430172) | fb668386c9a131bb62606515aee1fbaad3b7b920 |
| efbdfe58ef70 | 36/0 | P/bearer-credential-scratch-g5/runtime/<ID>/gate-run.json | [run 40623376143](https://github.com/HoloNova/Software-IR/actions/runs/40623376143) | 67445b52e9efd70de421c40037a1e32fcaf853ca |
| 3062e8f8e07b | 48/0 | P/crud-access-scratch-g5/runtime/<ID>/gate-run.json | [run 40623378433](https://github.com/HoloNova/Software-IR/actions/runs/40623378433) | 67445b52e9efd70de421c40037a1e32fcaf853ca |



复核：全部累计数均由上述逐 ID 记录求和；原始重复行、参考种子、旧工具链资格与 NOT_RUN 均未混入当前通过率。
