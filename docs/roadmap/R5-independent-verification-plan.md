# R5 计划：独立构建与真实验收接入

- 日期：2026-10-13。状态：**计划已写，实施中**。授权：负责人 2026-10-13 确认 `26e95f1` 的 CI 通过（[verify 运行 #42](https://github.com/HoloNova/Software-IR/actions/runs/38396035575)），并说"确认进入 R5 阶段"。
- 前置：[R4 报告](R4-version-apply-report.md)、[迁移路线 R5 一节](LAYERED_ARCHITECTURE_MIGRATION.md)、[设计 08 第 8 节](../design/08-layered-command-architecture.md)、[Q23 试跑报告](../qualification/Q23_REAL_EVALUATION_PILOT.md)。
- 目标（一句话）：对一份**具体的候选工程**，让调用方明确选择"只静态生成"还是"真实构建／启动／业务验证"，每一档各得一份独立、绑定到该候选的证据；并让"验证通过后再更新"成为一道可检查的门。

## 1. 我核对到的现状（读代码、查环境得到）

| 事实 | 位置 | 对 R5 的含义 |
| --- | --- | --- |
| 真实构建、启动、业务验证的全部代码都在**测试目录**，且主体 `BusinessSliceHarness`（1060 行）把"物料化、建库、构建、启动、HTTP 断言、报告、清理"揉在一起，类是包内可见 | `src/test/.../conformance/` | 不能直接宣布为产品服务（路线明确）。能复用的是**思路与已验证的细节**，不是类本身 |
| `MavenProjectRunner.cleanVerify` 和 `SpringApplicationProcess` 是 public 的，但**没有任何超时**：构建卡住就永远卡住 | 同上 | 资源合同缺口，新 runner 必须有超时与整棵进程树的终止 |
| 子进程环境用白名单从空 map 构造，数据库账号密码只走环境变量、不进命令行；日志经流式脱敏 | `ChildEnvironmentBuilder`、`StreamingSecretRedactor`、`SecretCatalog` | 这些做法已被 CI 验证，新 runner 沿用同样的合同 |
| 业务判断来自测试里手写的 HTTP + JDBC 断言（Q11 课程报名题），期望值**不由候选 SIR 推导**；正确样本 BUSINESS 通过，漏掉状态过滤的样本 BUILD、START 通过而 BUSINESS 失败 | `SlmGeneratedProjectEvaluationIT`、`conformance/slm/README.md` | 这是"独立需求 oracle"的现成做法，也是 R5 验收的现成反例 |
| 数据库互斥靠 MySQL `GET_LOCK`（名字由服务器 UUID 与 schema 名派生），schema 由测试创建并在结束时删除、证明已不存在；运行库账号只有指定 schema 的增删改查权限 | `BusinessSliceHarness`、`AdvisoryLockKey` | 数据库隔离合同已存在，但依赖 JDBC，而 `mysql-connector-j` 只在测试范围里（`pom.xml`）。产品代码不引入数据库驱动，数据库一侧留给端口 |
| 数据库初始化、迁移未交付：业务 IT 用测试 DDL 和种子数据 | Q23 报告、路线第 4 节 | 验证用的库只能是"已审定的测试库"，不声称产品会建库 |
| CI 已有 MySQL 8.4.11 服务、冻结的 Maven 仓库、构建缓存和证据产物上传；业务 IT 由系统属性显式开启 | `.github/workflows/verify.yml` | 真实验收的权威环境是 CI；新 IT 沿用同一套环境变量和开关 |
| 本机（Claude 会话）这次的环境：4 核、16 GB；Docker 守护进程可以手动起来，但拉取 `mysql:8.4` 镜像被限流（429）；apt 里有 MySQL 8.0.46；`~/.m2` 已缓存生成工程的依赖（spring-boot 3.5.3、mybatis-plus、mysql-connector-j 9.3.0） | 本次实测 | 本机可以真实**构建**；启动与业务验证需要数据库——本机只能用 8.0，不是参考环境，只能当预演，权威证据以 CI 的 8.4.11 为准 |
| `VersionPlan` 的候选回执已经带 `sourceSetSha256Hex` 与 `manifestDigest`；`SirValidationResult` 带 `sourceSha256` 与生成摘要 | `api/` | 证据可以绑定到"候选的源集合摘要 + 文件清单摘要"，不必新造标识 |

## 2. 要做什么，分四个单元（每个单元测过再提交）

| 单元 | 交付 | 为什么存在 |
| --- | --- | --- |
| **E1 验证合同与编排**（无进程） | `VerificationLevel`（STATIC／BUILD／START／BUSINESS，调用方明确选）；`StageResult`（PASSED／FAILED／NOT_RUN + 原因类别 + 分开的诊断）；`VerificationReport`（绑定源集合摘要、文件清单摘要、目标版本、资源合同、环境记录、四档结果）；三个端口 `ProjectBuilder`、`ApplicationHost`、`BusinessOracle`，加一个 `VerificationSandbox`（候选目录、隔离数据库的租约与清理证明）；`CandidateVerificationApplication` 按顺序编排，前一档没过后面一律 NOT_RUN，任何情况都清理并给出清理证明 | 先把"哪一档证明什么、什么算 NOT_RUN、证据绑定谁"固定下来，用假端口就能完整测试，不依赖机器环境。端口让 Application 不认识 Maven、MySQL，符合设计 08 第 10 节 |
| **E2 进程适配器** | `MavenProjectBuilder`、`SpringBootApplicationHost`：空环境白名单、仓库策略（本地仓库路径和离线开关由调用方给，不继承 `~/.m2`、不继承代理凭据）、构建／启动／就绪各自的**超时**、整棵子进程树终止并证明已退出、日志经脱敏落盘且**构建日志、应用日志、业务诊断分三处**、日志大小上限、JVM 内存上限 | 这是资源与清理合同的实现。旧测试 runner 没有超时，新的有；旧的一行不改，避免动到五个我在本机跑不了的 IT |
| **E3 验证后更新门** | `VerifiedVersionApplication`：要求先有一份 `VerificationReport`，其源集合摘要与文件清单摘要必须**等于**这次版本计划的候选，且要求的档位全部 PASSED，才调用 R4 的 `VersionApplyApplication.apply`；不匹配、过期、有 NOT_RUN 或 FAILED 一律拒绝并说明原因；验证全程只用隔离的候选目录和隔离库，不碰正式业务数据库 | 设计 08 第 8 节："证据匹配候选清单后才允许工程发布"。没有这道门，验证和发布只是两件不相干的事 |
| **E4 真实验收（IT，显式开启）** | 一个新 IT：用 E1–E3 的入口跑课程报名题的**正确样本**与**漏状态过滤样本**（数据库一侧用旧 harness 已验证的建库、互斥、清理，业务 oracle 复用 Q11 断言）；再加两个灵敏度用例：受控的"合法但 Java 编译失败"证明 BUILD 门有效，数据库不可达证明 NOT_RUN 被明确归因而不是当成失败；接入 `verify.yml`，证据产物上传 | 路线要求"STATIC／BUILD／START／BUSINESS 分开"并且有正反 oracle。这一步是 R5 唯一的真实证据，权威环境是 CI |

最终证据要回答：四档是否真的分开、反例是否只死在 BUSINESS、验证过的候选与被发布的候选是否是同一份、超时与失败后是否没有遗留进程／目录／schema。

## 3. 我替你选定的默认值（有限调整，事后说明）

1. **新代码放在 `sir-toolchain-application`，不新建模块**：端口和编排在 `api`，进程适配器在 `internal/execution`。理由：只有这一个调用方，模块拆分是多余的承诺；以后需要隔离再拆。
2. **产品代码不依赖数据库驱动**。数据库隔离通过 `VerificationSandbox` 端口交给调用方；具体 MySQL 实现留在测试里（旧 harness 已有）。代价：没有"产品自带的 MySQL 验证器"，这与"数据库初始化、迁移未交付"一致。
3. **旧 harness 与五个旧 IT 一行不改**。新 runner 是并列的新代码，重复了一部分思路（白名单环境、脱敏），但换来旧证据不被改动。后续可以另议是否让旧 harness 改用新 runner。
4. **默认只做 STATIC**。不选更高档位就不会启动任何进程；高档位必须显式选择。
5. **业务 oracle 由调用方给，系统不从 SIR 推导期望**。接口里 oracle 拿不到候选 SIR，只拿运行中的地址和数据库观察入口。
6. **同一候选的证据可复用的范围**：只有"源集合摘要、文件清单摘要、目标版本、构建环境记录"全部相同，BUILD／START 结果才视为同一份；BUSINESS 还要求 oracle 版本和种子数据相同。第一版**不做缓存**，只在报告里写清这些字段，缓存是以后的事。
7. **本机预演规则**：本机只做纯逻辑的单元测试和一次真实 BUILD；启动与业务验证不在本机冒充参考环境，权威证据来自 CI 的同 SHA 运行。若本机用 apt 的 MySQL 8.0 预演，报告里按实际版本记录，不写成参考环境。

## 4. 风险与我不确定的地方

- 进程树终止：`Process.destroy` 只杀直接子进程，Maven 会再起 JVM。需要用 `ProcessHandle.descendants()` 终止后代并确认退出。这是单元测试要用假脚本（睡眠的 shell 脚本）专门验证的点。
- 日志脱敏要在流上做，且不能因为一行跨缓冲区而漏掉；旧的 `StreamingSecretRedactor` 已处理这个问题，新实现要么复用其算法要么写同等的测试，我会先看它的实现再定。
- 新 IT 只有 CI 能给权威结果；我需要推送后等同 SHA 的运行。若 CI 红了，要按 CI 的日志修，而不是在本机反复重跑重活。
- 生成工程的真实构建在本机还没有跑过（依赖已缓存，但没有实测）；E2 前先跑一次，用实测时间决定默认超时。

## 5. 明确不做

- 完整的模型评测平台、Web／Docker 产品、数据库初始化与迁移、生产部署。
- 用"成功启动"代替业务验证（计划里 START 与 BUSINESS 是两档）。
- 构建缓存、跨请求复用构建产物。
- 修改旧 harness、旧 IT 或旧 Q23 `check` 协议。
- 真实 SLM 输出集合的批量评测与成功率统计（没有题目包与样本）。

## 6. 进度

| 单元 | 状态 |
| --- | --- |
| E1 验证合同与编排 | 已完成并提交（17 个测试，假端口；候选身份与已保存基线的身份逐位相同，构建看到的文件等于从零生成的目录） |
| E2 进程适配器 | 未开始 |
| E3 验证后更新门 | 未开始 |
| E4 真实验收 | 未开始 |
