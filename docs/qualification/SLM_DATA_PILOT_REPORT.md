# SIR 数据首批闭环报告

2026-10-25，主会话执行。负责人确认“先做数据”，并委托小模型基座调研。没有训练学生模型、租用GPU、自动提交/推送或触发CI。

## 实际完成

- 冻结公开双Token题目、接口、SIR 0.3、业务家族和train归属；原有参考答案明确标为existing_reference，不冒充新教师输出或盲测。
- 新增 `experiments/slm` 的prepare/import/generate/repair/check/export。API密钥只从环境读取；单次采样有数量/token上限，禁用自动重试；保留完整SDK响应、请求、用量、源哈希、收集器快照及父子关系。
- 外部候选复用原 `DualTokenBusinessConformanceIT`、DDL、seed、HTTP/JDBC断言与清理。测试适配器经生产安全读源入口加载候选，不在输入失败时退回参考答案；共享harness增加已读不可变快照入口。Parser/Semantic/Lowering/Generator/CLI生产代码未改。
- 静态校验调用现有CLI完整GENERATION。WARNING保留；解析通过不算完整静态成功；运行证据与题目/oracle/工具链/源码/生成摘要均须绑定，附件须实际存在且哈希一致，才导出正式训练答案。

## 第一阶段真实采样结果（当时尚未运行真库；后续见下文）

教师API：`deepseek-flash`，只读模型列表当时显示DeepSeek-V4.1-Flash；响应仍回传别名，system_fingerprint为`e72d3c7f663d3e1383b00b3884cb4501`。这不是本地DeepSeek-Coder-1.3B学生权重，也不等于固定开源revision。

| 数据 | 数量 | 结果 |
| --- | ---: | --- |
| 既有参考种子 | 1 | 完整静态生成通过；旧CI资格不自动移植给新候选 |
| 初始真实教师候选 | 3 | 全部在PARSE失败，先暴露credential放在field之前 |
| 第一轮反馈修复 | 1 | 解析通过，SEMANTIC拒绝实体行绑定到Token标量、fails次序等 |
| 第二轮反馈修复 | 1 | 完整GENERATION通过，45个生成文件 |
| 新候选真实BUILD/START/BUSINESS | 0次执行 | **NOT_RUN** |
| 正式可训练业务答案 | **0** | 空导出是门的正确行为，不是任务成功率 |

修复分支：`f4b4a6071888 → 6702e9b70653 → ef9ddc30eb79`（完整ID均以`auth-dual-token-v1-teacher-`开头）。两次修复是同一道题的一条轨迹，不算两道新题，也不能把1/5当小模型泛化成功率。采用原始诊断、公开语法规则澄清和题目既有DDL，没有手改SIR答案或放宽oracle；完整参考答案没有发给教师。

五次实际API调用合计：input **15651 tokens**、output **5763 tokens**、total **21414 tokens**。保存缓存命中用量，不以token总数冒充实际账单金额。本轮不再自动扩大调用。

最终静态通过候选：

- ID：`auth-dual-token-v1-teacher-ef9ddc30eb79`。
- source SHA256：`a5cd4c7155a309916fd111531cbd46f7fea5981b0395e0ad37955b71e606122a`。
- generated digest（KCG checker域）：`4cbb1dff526bd13a0b2324020922da538210f05245eab4dafb9c4e6e60a8123d`。

该候选登出返回TokenPairView而非参考的LoggedOutView，且创建/持久化编排也不完全相同。静态通过不证明接口行为、主键赋值时序、并发或事务正确；当前保持候选状态，未评为业务正确或规范答案。不为收样本而改原语言/测试规则。

## 数据与复现

持久数据入口：`datasets/sir/pilots/dual-token-v1/`，避免只放在会被Maven clean删除的target。保留task/system/base/reference、manifest、6条候选记录、5次SDK响应和请求、2轮父子关系、三批静态结果、空SFT导出及索引。原始运行目录仍在 `target/slm-data/dual-token-pilot-v1/`；不重复执行API或检查来搬运数据。

方法与参考环境完整调用模板见 [实验README](../../experiments/slm/README.md)。显式import支持在Linux重新冻结工具链/oracle并导入原教师响应，保留原指纹与原始数据，将所有门重置NOT_RUN，不带走旧静态资格；实际5个响应的CLI导入已验证，新增API调用0次。真实判题需要本轮适配器源码和完整参考环境，Windows安全目录读取限制不放宽。代码/任务/oracle变动导致哈希失配时，必须建新批次，不改旧manifest冒充同一实验。数据目录的Git属性关闭换行转换，以保留原始字节哈希。

## 定向验证与边界

- Python按模块最新结果 **31项通过**（corpus17、dataset_io7、pipeline7）：拒绝静态冒充业务、源码/附件篡改、重复JSON键/NaN、缺失输入、截断响应、跨split同族/同源码泄漏；完整SDK响应绑定、参考种子不自动晋级、一次反馈修复与无密钥数据路径；导入不继承旧宿主指纹/通过资格，拒绝被改过的响应。先取得缺模块RED，再取得四处防篡改/输入边界的真实运行RED，补齐后转绿。
- Java **4类23项通过，0失败/错误/跳过**：DualTokenCandidateSupportTest 8、BusinessSliceCompilationTest 6、BusinessSliceHarnessEvidenceTest 5、KcgCheckTest 4。先以缺少候选适配器/快照重载取得编译RED，再实现；包括静态合法但TTL错误的候选不被替换成参考，以及编译成功也只能产出运行门NOT_RUN证据。
- 实际候选经真实CLI校验，不用模拟检查器冒充产品通过。部分Python测试用mock教师和证据形状验证传输/资格规则，这些不计入五次真实API样本。
- 本机只做静态校验与生成文件的测试物化，没有构建/启动生成应用、运行MySQL/HTTP IT、全量门或浏览器。覆盖率未测。AFT诊断请求桥超时，不能记为诊断清洁；Java编译以本轮Maven和实际XML为准。
- XML哈希、日志及Python RED/GREEN在 `target/slm-data-verification/`，关键摘要随数据verification子目录保存（新增导入测试首次因测试漏import json报错，修正后仅复跑pipeline7项，未重复已通过的另外24项；原日志保留）；不与#184的1970项累加。业务账本83/1/0不变。

## 下一步

参考环境对照与最终候选已实际运行，结果见下文。下一步把真实运行错误反馈给教师，生成新的有界修复子候选并重新验收；不得手改旧答案冒充模型修复。现有`repair`命令只接静态失败，运行反馈须显式保留原证据及新的parent轨迹，不伪造static失败。通过后再冻结下一批初始prompt的实体顺序/物理形状/生成身份时点说明，扩成更多需求组合。首轮3个初始失败同时暴露了参考提示不充分，不能据此断言教师或学生无能力；应分别记录模型、提示、工具链与环境归因。

训练/开发/盲测按需求家族分开，当前只有公开训练种子题，尚未建立盲测集、测小模型基线或启动训练。[基座与云GPU建议](SLM_MODEL_SELECTION.md)已按官方资料和实际长度给出；不为扩样本重开已验收的语言功能。

## 第二阶段：lab 真实参考环境（2026-10-25）

负责人指定Forgejo与`ml.campuslink.vip`所在的`ssh lab`机器作为可用测试环境。已验证Forgejo的`holonova`身份能创建/清理私有空仓库及Issue；Actions runner为空。ML签名API真实执行了成功、非零失败、超时、运行中取消与产物下载，实测cgroup限额。**本节SIR业务实际走SSH原生systemd scope，不是Forgejo Actions或ML API的默认Python镜像**，不等同于已通过公网ML API完成同一业务验收。

原生环境为4核/约7GiB、Java/Javac21.0.12.1、Maven3.9.14、MySQL8.4.11。隔离目录`/home/labadmin/sir-reference/20261025-pilot`，scope上限2304MiB/150%CPU，构建与两次IT串行，外层Maven256/384MiB、JUnit384MiB、生成Maven256MiB、应用384MiB并分别限metaspace。只构建受影响依赖并执行原DualTokenBusinessConformanceIT，不运行全量门或浏览器。

准备阶段保留两次真实失败：只装JRE导致缺`javac/ct.sym`，补同版本headless JDK后解决；第一次源码打包遗漏两个未跟踪测试适配器，补齐后逐文件核对1284份完整输入。源码清单SHA256为`60c59c1828dd99bda4b954deac67e3c4e6b64e872a0f3f7fec07f7aa5abeb86d`。这些是环境/传输问题，不记为教师业务失败；未修改生产源码、候选、DDL、seed或oracle。

目标环境执行import并重做真实静态检查，不迁移原通过资格；本轮教师API调用**0次**。随后同一oracle实际运行：

| 样本 | Static | Build | Start | Business | 检查 |
| --- | --- | --- | --- | --- | --- |
| 既有参考 | PASSED | PASSED | PASSED | PASSED | 35通过/0失败 |
| 教师`ef9ddc30eb79` | PASSED | PASSED | PASSED | FAILED | 19通过/14失败 |

参考run ID为`dual-token-slice-1a1720f89b4-17144`，教师为`dual-token-slice-1a1721082e4-62691`。教师登录HTTP500的首发根因已由真实应用日志和生成代码交叉核对：LoginService先执行`pair.setAccessToken(access.getId())`与`pair.setRefreshToken(refresh.getId())`，随后在persist步骤才为令牌赋`UUID.randomUUID()`；pair已复制null，INSERT token_pair省略了令牌列，MySQL报`Field 'access_token' doesn't have a default value`。后续认证/刷新等14处失败包含该首错的连带结果，不能当14个独立根因，也不能把部分拒绝路径PASS当功能正确。这里只验明失败，不手改模型答案或放宽oracle。

持久证据在`datasets/sir/runs/dual-token-lab-v1/`：两份runtime证据绑定同题、同源、同oracle和生成摘要，附真实JUnit XML、业务/HTTP记录、Maven/Spring日志；verification保存完整输入清单、运行脚本、准备失败、生成Java和oracle源码快照。传回原始归档SHA256为`b08e9a0b130fc72d1bfe26c59adc5afe297c1876f9f1f877d8942eaa3a16dec4`，逐附件哈希检查通过。

实际调用现有导出器：`train-teacher.jsonl`仍为**0条新教师合格答案**；`reference-seed-qualified.jsonl`为1条已实际验收的既有参考，明确单列，不混入新模型数据或小模型成绩。最终检查证明本轮scope/HTTP端口18184、随机schema、两个临时账号与private凭据文件全部清理，保留平台原有MySQL/worker。未提交、推送、触发GitHub或Forgejo CI，未启动模型训练；业务账本83/1/0不变。

后续按负责人要求，已删除`docs/LAB_PLATFORM_GUIDE.md`和部署机的`/home/labadmin/sir-reference`临时目录；上文是历史运行环境，不再作为后续可复用入口。已归档的原始业务证据保留，结论不变。工具操作指南仅以独立Prompt文本提供：Forgejo仅局域网直接访问，任务通过公网ML API派发，不依赖SSH。

## 第三阶段：编译器补漏后的复查与 prompt v2（2026-10-25）

首批暴露的两处编译器缺口已在 `bca868b` 修复，CI #187（`3650f3b`）通过：新建的行在 persist 之前读主键、当作引用的值或经含主键的视图返回，报 `SIR-FLOW-007`；实体成员写错顺序时，报错直接给出固定顺序和该挪到哪里。工具链摘要因此改变，首批与 lab 批次都不能沿用，旧目录保持原样。

复查批次 `datasets/sir/runs/dual-token-recheck-v2/` 用 `import` 从首批导入5份教师原始响应，冻结当前工具链与 oracle，所有门重置为 NOT_RUN，再用真实 CLI 做完整静态检查。新增 API 调用0次。

| 样本 | 首批静态结果 | 复查结果 |
| --- | --- | --- |
| 既有参考 | PASSED | PASSED |
| 3个初始候选 | PARSE 失败，“存在多余的语法元素” | PARSE 失败，提示“这个 field 要写在 credential 之前” |
| 第一轮修复 `6702e9b70653` | SEMANTIC 失败 | SEMANTIC 失败，另加 `SIR-FLOW-007` |
| 第二轮修复 `ef9ddc30eb79` | PASSED（后在真库业务 19/14） | SEMANTIC 失败，只有 `SIR-FLOW-007`（4处：两个能力里先读 access.id、refresh.id 再 persist） |

也就是说，lab 批次里花了构建、启动和真库才发现的错误，现在在静态门几秒内就能发现，并可直接走普通 `repair`。

prompt v2 是新文件（`prompts/sir-0.3-auth-v2.md`、`prompts/repair-auth-v2.md`），补实体成员顺序和“先 persist 再读主键”；v1 保持不变，仍是 `prepare`/`repair` 的默认值，`import` 直接冻结来源批次的 `system.md`。Python 测试35项通过（原31项 + 4项 prompt 选择与导入）。

尚未执行（需要 `DEEPSEEK_API_KEY`，本容器没有）：对 `ef9ddc30eb79` 用 v2 反馈做一次 repair；用 v2 新建初始采样批次。静态通过的新候选仍需要真实业务门，而可复用的业务门执行环境尚未建立，所以新教师合格训练答案仍是0条。

## 第四阶段：v2 反馈修复与 v2 初始采样（2026-10-26）

第三阶段留下的两项 API 调用已执行，共4次：对 `ef9ddc30eb79` 用 v2 反馈做1次 repair，用 v2 system prompt 新建批次并初始采样3份。均为 `deepseek-flash`，system_fingerprint 仍是 `e72d3c7f663d3e1383b00b3884cb4501`；没有自动重试，也没有在失败后继续修。

| 调用 | 样本 | input | 其中缓存命中 | output | total |
| --- | --- | ---: | ---: | ---: | ---: |
| repair（v2 反馈） | `bd870a834f77` | 9915 | 8064 | 1199 | 11114 |
| 初始采样 | `75d6da4889cd` | 1635 | 0 | 1047 | 2682 |
| 初始采样 | `25e7a4ca946e` | 1635 | 1408 | 1130 | 2765 |
| 初始采样 | `e473808f70e8` | 1635 | 1408 | 1049 | 2684 |
| 合计 | 4次 | 14820 | 10880 | 4425 | 19245 |

修复结果在 `datasets/sir/runs/dual-token-recheck-v2/checked-r1.jsonl`：`bd870a834f77` 通过完整静态生成（GENERATION，45个文件）。它和父候选 `ef9ddc30eb79` 只差两处：两个能力里把 `persist access; persist refresh;`（Refresh 里连同 `persist old;`）挪到读 `access.id`、`refresh.id` 之前，其余逐字相同。所以登出仍返回 TokenPairView，与参考的 LoggedOutView 不同；这一点静态门不判断，要由业务门决定。修复轨迹现为 `f4b4a6071888 → 6702e9b70653 → ef9ddc30eb79 → bd870a834f77`，仍是同一道题的一条轨迹。注意这次修复的对话前缀是首批的 v1 system prompt，只有最后一轮反馈用 v2。

- ID：`auth-dual-token-v1-teacher-bd870a834f77`。
- source SHA256：`2a81772ab3856285a092c6e9610ab4f91f8572f9a1a9a14e2fd8078458f439bd`。
- generated digest（KCG checker域）：`7010109bfef704a3f860d069c9a0bdec307ea863c03b62ef1c1a1272925240b6`。

v2 初始批次 `datasets/sir/pilots/dual-token-v2/` 的3份候选全部静态失败（参考仍通过），但失败点各不相同，不再是首批那样三份同错：

| 样本 | 阶段 | 原因 |
| --- | --- | --- |
| `75d6da4889cd` | PARSE | AccessToken 仍把 credential 写在 field 之前；报错已直接给出固定顺序和该挪到哪里。v2 补的成员顺序提示对这一份没起作用 |
| `25e7a4ca946e` | SEMANTIC | 解析和主键时序都过了；Logout 声明输出 TokenPairView 却 `return old`（RefreshToken 行），`SIR-TYPE-001` 返回类型不匹配。根上是业务设计错，登出不该返回令牌对 |
| `e473808f70e8` | PARSE | 先 persist 再读 id 做对了，但自创了 `return pair { ... }` 这种 SIR 没有的内联构造返回写法 |

3份里有2份明确按“先 persist 再读主键”写，没有一份出现 `SIR-FLOW-007`；成员顺序仍有1/3写错。样本只有3份，这只是观察，不能当 v1→v2 的通过率对比。

业务门：本轮所有候选的 BUILD/START/BUSINESS 都是 **NOT_RUN**。可复用的业务门执行环境尚未建立（计划第5步），lab 一次性目录已删除。所以 `bd870a834f77` 只是“静态通过的候选”，没有证明登录能成功、令牌轮换或并发正确；新教师合格训练答案仍记 **0条**，本轮也没有运行 `export`。

执行环境：本轮在云端容器（4核/15GiB），没有 systemd，无法用 `systemd-run --scope`。本地 Maven 仓库为空，`mvn -o compile` 因缺 `spring-boot-dependencies:3.5.3` 失败，改为联网单次执行 `mvn -pl kcg-cli -am compile`（`MAVEN_OPTS=-Xmx2g`，不加 `-T`）补齐检查器需要的9个模块 classes 和 ANTLR jar；检查器本身仍按 `dataset_io.checker_command` 限 `-Xmx256m`。调用前 Python 35项单测通过。未运行 Java 测试、全量门或任何业务 IT，未提交、推送或触发 CI；业务账本83/1/0不变。

## 第五阶段：GitHub 上的可复用业务门（2026-10-26）

业务门从这一阶段起在 GitHub 上运行，不再依赖一次性的 lab 目录。12 个业务 IT 共用的 `BusinessSliceHarness` 现在能直接判教师候选（`4b57482`）：传入 `kcg.slm.*` 参数时编译候选而非参考答案，并核对候选所属题目冻结的参考答案与该 IT 判的参考答案逐字节相同，不同就在建库前拒绝。新增只能手动触发的 `.github/workflows/slm-business-gate.yml`（`0c4fb4a`），输入批次、样本 ID 和 IT，复用 verify 的 MySQL 8.4、账号与 Maven 缓存，只有业务门 PASSED 时 job 才为绿。`verify` 不再因 `datasets/**` 触发（`9074891`）。

判卷代码变了，旧批次的 `oracle_sha256` 随之失效；`DualTokenCandidateSupport` 改名后，旧批次的 `task.json` 也指向不存在的文件。`import --task`（`7f6dc33`）允许用当前题目定义导入，但除 `oracle.files` 外题目必须相同，base 与参考答案字节须等于来源批次冻结的。据此从复查批次导入 `datasets/sir/runs/dual-token-gate-v1/`（`f58b05e`，API 调用0次），静态结果与复查批次逐份一致。

[slm-business-gate #1](https://github.com/HoloNova/Software-IR/actions/runs/40330717774)（`f58b05e`）判 `bd870a834f77`：

| Static | Build | Start | Business | 检查 |
| --- | --- | --- | --- | --- |
| PASSED | PASSED | PASSED | FAILED | 34通过/1失败 |

唯一失败是“logging out answers 200 (expected=200 actual=201)”。候选的 Logout 声明返回 TokenPairView，为凑这个返回值，在作废刷新令牌后新建了一行 TokenPair，访问令牌与刷新令牌都填成那个已作废的刷新令牌。生成器对会新建实体的能力返回 201（`ControllerRenderer` 的 `createsEntity`），参考答案的 Logout 只更新不新建、返回 LoggedOutView，因此是 200。这是候选的业务设计错误，需求原文要求登出“响应 200”，判卷成立。登录、同样的 401、访问令牌鉴权、过期、刷新与重放、六个并发刷新恰一个成功、登出后刷新被拒都已通过；相比 lab 批次父候选 `ef9ddc30eb79` 的19/14（登录 500），先 persist 再读主键的修复是有效的。

现有 IT 没有检查登出是否多写了行，这次多出的那行 TokenPair 没被发现。是否补检查属于改判卷标准，另行决定，本轮未改。

证据在 `runtime/auth-dual-token-v1-teacher-bd870a834f77/`：`slm-runtime.json`、报告、Maven/Spring 日志、JUnit XML，以及 `gate-run.json`（run URL、提交 SHA、两个 artifact 的 zip 哈希）。下载后核对：题目、判卷、工具链、源码四个哈希与批次一致，5 个附件哈希一致，不含 CI 数据库密码。现有导出器 `train-teacher.jsonl` 为 **0条**：新教师合格训练答案仍是0条。本轮未用运行反馈再修复；业务账本83/1/0不变。

## 第六阶段：业务门反馈修复与 22 份新题（2026-10-28）

`repair` 新增 `--runtime-evidence`（`def7973`）：只接受静态已通过、构建与启动通过而业务失败的候选，证据的绑定哈希、生成摘要与附件哈希必须一致；发给教师的只有判卷报告里失败的检查和 HTTP 记录，前缀为新文件 `prompts/repair-runtime-v1.md`，不提示改法。

对 `bd870a834f77` 用第五阶段的业务门证据修复1次：

| 调用 | 样本 | input | 其中缓存命中 | output | total |
| --- | --- | ---: | ---: | ---: | ---: |
| 业务门反馈修复 | `e2c157051e10` | 12480 | 10880 | 1199 | 13679 |

`system_fingerprint` 为 `bc19ba97cbe016479903222bb1578db6`，此前各次均为 `e72d3c7f663d3e1383b00b3884cb4501`，服务端模型可能已更新；请求的仍是 `deepseek-flash`。

结果在 `datasets/sir/runs/dual-token-gate-v1/checked-r1.jsonl`：`e2c157051e10` **静态失败**。教师只把 Logout 的 `expose command` 改成 `expose query`，以为 201 来自接口类型；但 query 必须只读，而登出要更新、persist 并（仍然）新建 TokenPair，于是报 `SIR-VALID-001`（query 必须声明 readonly）和3条 `SIR-FLOW-004`。真正原因（登出时新建了一行 TokenPair）没有找到。按约定不自动继续修复，业务门未运行；新教师合格训练答案仍是0条。修复轨迹现为 `f4b4a6071888 → 6702e9b70653 → ef9ddc30eb79 → bd870a834f77 → e2c157051e10`。

出题方面，另一个 Agent 交付了 11 道题的草稿（`224f6b0`，base、两种需求、接口合同、追溯表、备注）。核对：11 份 base 均通过完整静态生成，且都是参考答案只删不改；22 份需求不含 SIR 写法；追溯表共 717 项检查，未覆盖的 28 项全是夹具自检或总报告汇总。据此生成 22 份正式题目（`5916fd3`，每题改写与从零写各一）：鉴权与课程为 train，商店为 development，资金与图书馆为 test。`task.schema.json` 允许 `base_source` 为 null（从零写题），`exposure` 与 `oracle.behaviors` 改为可选（`d8d9c09`）。query-slice 与 relation-slice 的参考软件只有一个接口，改写题即“表结构已定，补上这个接口”。

题目的接口合同起初混入了参考答案的实体/视图字段、表结构和判卷用的种子、探针、并发数，商店改写题 prompt 达 4.4 万字（需求本身 604 字）。`b036f2a` 改为由新字段 `ddl_source` 在 prompt 中附上判卷 DDL，合同只留 namespace、能力与实体名、接口清单、错误、枚举、分页和业务常量，合同总量减半，商店改写题降到约 3.6 万字。被删字段对应的要求需求原文均已写明。

采样前还缺一份通用 system prompt：现有 `sir-0.3-auth*.md` 只讲双 Token 的写法，课程、资金、商店等题要用的分页、小数汇总、加锁等都没有。业务账本83/1/0不变。

## 第七阶段：通用 prompt 试采样与首批合格教师答案（2026-10-28）

`sir-0.3-auth*.md` 只讲双 Token，其他家族要用的分页、小数汇总、加锁等写法都没有，所以新写了通用 system prompt：写法全部取自语言参考的已测试示例，另写明表名/列名与实体/字段的对应和成功状态码规则（工作流里有 `create` 就是 201）。试采样只用 train 集的三道题，每轮失败都能对应到一条没写明的规则，补上后同样的错误不再出现：

| prompt | 题目 | 候选 | 静态 | 失败原因 |
| --- | --- | --- | --- | --- |
| general-v1 | bearer-credential 改写 | `07b9ea18ef67` | PASSED | |
| general-v1 | query-slice 从零写 | `82872fe06240` | FAILED | 输入字段写 `default` |
| general-v1 | write-slice 改写 | `ab5caa38a15d` | FAILED | 把 PATCH 请求体的 `expectedVersion`/`changes` 写进 patch 输入 |
| general-v2 | query-slice 从零写 | `182e9f50beb7` | FAILED | 约束当表达式用；`at` 路径重复 `/api` |
| general-v2 | write-slice 改写 | `e52b33f4295b` | FAILED | patch 字段用带规则的类型；对带 Version 的实体写守卫 |
| general-v3 | query-slice 从零写 | `cadd5a148bb8` | PASSED | |
| general-v3 | write-slice 改写 | `75ff6c7c1924` | FAILED | 自创三元表达式 `? :` |
| general-v4 | write-slice 改写 | `51ab2996a20e` | PASSED | |

8 次调用合计 input 29861、output 3807 tokens（各批次 `candidates.jsonl` 记有逐次用量）；`system_fingerprint` 均为 `bc19ba97cbe016479903222bb1578db6`。每条新规则都先在参考文档或生成器源码中核实（如分页缺省值见 `SpringBootQueryPolicy`，PATCH 包装见 `SpringBootWritePolicy`），并把新写法拼成完整文档经 `kcg check` 通过。

静态通过的三份送 GitHub 业务门：

| 候选 | run | Static | Build | Start | Business | 检查 |
| --- | --- | --- | --- | --- | --- | --- |
| bearer-credential `07b9ea18ef67` | [#2](https://github.com/HoloNova/Software-IR/actions/runs/40619277037)（`fcb8de9`） | PASSED | PASSED | PASSED | PASSED | 36/0 |
| query-slice `cadd5a148bb8` | [#3](https://github.com/HoloNova/Software-IR/actions/runs/40619824442)（`c509c4c`） | PASSED | PASSED | PASSED | PASSED | 41/0 |
| write-slice `51ab2996a20e` | [#4](https://github.com/HoloNova/Software-IR/actions/runs/40619980837)（`d445309`） | PASSED | PASSED | PASSED | PASSED | 62/0 |

三份通过的证据放入各批次的 `runtime/<样本>/`（`slm-runtime.json`、报告、日志、JUnit XML 与 `gate-run.json`）；下载的 zip 哈希与 GitHub 记录的 artifact 摘要一致，四个绑定哈希、生成摘要与附件哈希逐项核对，不含 CI 数据库密码。现有导出器在 `bearer-credential-change-g1/`、`query-slice-scratch-g3/` 与 `write-slice-change-g4/` 各导出 `train-teacher.jsonl` 1 条：**新教师合格训练答案首次为 3 条**（均为 train 集：鉴权改写、课程从零写、课程改写各一）。

这 3 条只说明流程可以产出四门合格的答案，不是教师或小模型的通过率：样本很少，prompt 在采样过程中逐版修改，题目只覆盖两个家族。导出的 prompt 里 system 约 6～7 千字，学生模型（1.3B，16K 上下文）训练时是否沿用、如何截短，留到训练阶段决定。业务账本83/1/0不变。

## 第八阶段：通用 prompt v4 跑完 train 集一轮（2026-10-28）

固定用 `sir-0.3-general-v4`，对 train 集此前未采的 13 份题目各采 1 次，中途不改 prompt（`*-g4` 批次）。13 次调用合计 input 57613（其中缓存命中 33280）、output 13133 tokens。

| 题目 | 候选 | 静态 | 业务门 | 检查 | 失败原因 |
| --- | --- | --- | --- | --- | --- |
| crud-access 改写 | `d6b3dce267c8` | PASSED | [#5](https://github.com/HoloNova/Software-IR/actions/runs/40620875519) PASSED | 48/0 | |
| guarded-transition 从零写 | `1dff8d4a8a1c` | PASSED | [#12](https://github.com/HoloNova/Software-IR/actions/runs/40621086545) PASSED | 25/0 | |
| token-desk 改写 | `b38e01722cc0` | PASSED | [#13](https://github.com/HoloNova/Software-IR/actions/runs/40621089007) PASSED | 34/0 | |
| token-desk 从零写 | `bd4698114bcb` | PASSED | [#14](https://github.com/HoloNova/Software-IR/actions/runs/40621091701) PASSED | 34/0 | |
| query-slice 改写 | `905d204a57e4` | PASSED | [#15](https://github.com/HoloNova/Software-IR/actions/runs/40621094499) PASSED | 41/0 | |
| relation-slice 改写 | `d3c1df26ed80` | PASSED | [#16](https://github.com/HoloNova/Software-IR/actions/runs/40621097340) PASSED | 41/0 | |
| relation-slice 从零写 | `c6167c316f44` | PASSED | [#11](https://github.com/HoloNova/Software-IR/actions/runs/40620893330) FAILED | 35/6 | 筛选写成 `any(Enrollment, course == item)`，漏了需求要求的 `status == ACTIVE`，只有已取消报名的课程也被查出（总数 6，应为 4） |
| bearer-credential 从零写 | `0214246b5d17` | FAILED | | | 按主键加载写了字段名 `by id:` |
| crud-access 从零写 | `1df6bb81718e` | FAILED | | | `expose command post at …`，只能写 put/delete |
| guarded-transition 改写 | `bd642066c9d7` | FAILED | | | 守卫更新后写 `persist … else` |
| course-selection 改写 | `6ec3685a54cd` | FAILED | | | 变量名用了保留字 `target` |
| course-selection 从零写 | `eb0ce1da4119` | FAILED | | | 字段修饰写成 `unique where`，顺序应为 where 在前 |
| write-slice 从零写 | `036fa8e447e7` | FAILED | | | 普通输入用 `.present`；没有 unique 的新行写 `persist … else` |

v4 这一轮：13 份中静态通过 7 份，四门通过 6 份。样本少、题目难度不一，只作参考。6 份静态失败都能对应到语言参考里已有、但 prompt 没写明或没给例子的规则，留给 v5；业务失败的那份是业务逻辑漏条件，属于真正的答错，业务门正确拦下。

判卷时发现 `slm-business-gate` 的并发组按分支设置，GitHub 每组只保留一个排队中的 run，连续触发 7 个时 #6～#10 被取消（未判任何东西，不留证据）。`306cc67` 改为按样本分组后重新触发，各 run 并行完成。

7 份证据都放入各批次 `runtime/`（含失败那份），下载的 zip 哈希与 GitHub 记录一致，绑定哈希、生成摘要与附件哈希逐项核对，不含 CI 密码。现有导出器在 6 个通过的批次各导出 1 条：**新教师合格训练答案累计 9 条**，全部为 train 集（鉴权 5 条、课程 4 条），覆盖 16 份 train 题目中的 9 份。尚无合格答案的 7 份：bearer-credential 从零写、crud-access 从零写、guarded-transition 改写、course-selection 两种题型、relation-slice 从零写、write-slice 从零写。业务账本83/1/0不变。

## 第九阶段：v5 补采与通用静态修复（2026-10-28）

对 v4 一轮未合格的 7 份题目用 `sir-0.3-general-v5` 各采 1 次（`*-g5`，input 33893、其中缓存命中 16128，output 9547 tokens）：只有 relation-slice 从零写静态通过。v5 明确写了的规则仍被违反（course-selection 两份再次用 `target` 当变量名，guarded-transition 再次在守卫更新后写 `persist … else`），另有 create 绑定 Version 字段、`actor` 写成 `Ref<AccessToken>`。继续加 prompt 规则的收益已经很小。

改为用编译器诊断修复：新增 `prompts/repair-general-v1.md`（只说明按诊断逐条改、需求与接口不变，不提示改法），对 6 份静态失败各修 1 轮（input 44730、其中缓存命中 38144，output 9053 tokens），5 份修复后静态通过；course-selection 从零写引用了未定义的 `TokenPairView`，按约定不再修。

| 题目 | 候选 | 来源 | 业务门 | 检查 | 结果 |
| --- | --- | --- | --- | --- | --- |
| relation-slice 从零写 | `561bdfe9ead2` | v5 初始 | [#17](https://github.com/HoloNova/Software-IR/actions/runs/40622055593) | 41/0 | 四门通过 |
| guarded-transition 改写 | `857fcfe57b22` | 静态修复 | [#20](https://github.com/HoloNova/Software-IR/actions/runs/40622425058) | 25/0 | 四门通过 |
| write-slice 从零写 | `be9817697402` | 静态修复 | [#22](https://github.com/HoloNova/Software-IR/actions/runs/40622430172) | 62/0 | 四门通过 |
| bearer-credential 从零写 | `c9ab97cd12d2` | 静态修复 | [#18](https://github.com/HoloNova/Software-IR/actions/runs/40622420133) | 33/3 | 业务失败：登出返回 401，登出后令牌仍有效 |
| crud-access 从零写 | `b6e7a5435998` | 静态修复 | [#19](https://github.com/HoloNova/Software-IR/actions/runs/40622422717) | 45/3 | 业务失败：公开读取没有遵守软删除 scope |
| course-selection 改写 | `dbbbac2d45bc` | 静态修复 | [#21](https://github.com/HoloNova/Software-IR/actions/runs/40622427838) | 128/24 | 业务失败：星期为空时 500 而非 400 等 |

6 份证据都放入各批次 `runtime/`，zip 哈希与 GitHub 记录一致，绑定哈希、生成摘要与附件哈希逐项核对，不含 CI 密码。修复出的候选，其静态结果在 `checked-r1.jsonl`，导出时以此文件为准。3 个通过的批次各导出 1 条：**新教师合格训练答案累计 12 条**（鉴权 6、课程 6），覆盖 16 份 train 题目中的 12 份。仍无合格答案的 4 份：bearer-credential 从零写、crud-access 从零写、course-selection 改写（均为业务失败）与 course-selection 从零写（修复后仍静态失败）。

修复轨迹是有用的数据：两份修复后的合格答案带着“初次失败 → 编译器诊断 → 修好”的完整记录（`generation_mode: feedback-repair`、`parent_id`）。业务失败的三份，可以用 `repair --runtime-evidence` 把判卷报告发回教师修一轮，未做。业务账本83/1/0不变。

## 第十阶段：业务失败候选的运行反馈修复（2026-10-28）

对第九阶段业务失败的 3 份候选，用 `repair --runtime-evidence`（`prompts/repair-runtime-v1.md`）把判卷报告中的失败检查和 HTTP 记录发回教师，各修 1 轮（input 34756、其中缓存命中 28160，output 4882 tokens），结果在各批次 `checked-r2.jsonl`，3 份均静态通过。

| 题目 | 候选（父候选） | 改动 | 业务门 | 检查 | 结果 |
| --- | --- | --- | --- | --- | --- |
| bearer-credential 从零写 | `efbdfe58ef70`（`c9ab97cd12d2`） | 登出不再要求登录，凭请求令牌作废 | [#23](https://github.com/HoloNova/Software-IR/actions/runs/40623376143) | 36/0 | 四门通过 |
| crud-access 从零写 | `3062e8f8e07b`（`b6e7a5435998`） | 公开读取时隐藏帖子返回 404 | [#24](https://github.com/HoloNova/Software-IR/actions/runs/40623378433) | 48/0 | 四门通过 |
| course-selection 改写 | `1cc5c4027a2d`（`dbbbac2d45bc`） | 与父候选逐字节相同，教师未作修改 | 未送 | — | 同一份代码重判结果不会变 |

两份通过的证据放入各批次 `runtime/`，zip 哈希与 GitHub 记录一致，绑定哈希、生成摘要与附件哈希逐项核对，不含 CI 密码；以 `checked-r2.jsonl` 导出，各 1 条。**新教师合格训练答案累计 14 条**（鉴权 8、课程 6），覆盖 16 份 train 题目中的 14 份。这两条答案带着“静态失败 → 编译器诊断修复 → 业务失败 → 运行反馈修复 → 通过”的完整修复链（`generation_mode: runtime-feedback-repair`，`parent_id` 逐级可追）。

仍无合格答案的只剩 course-selection 两份：改写题业务失败 24 项，运行反馈修复没有产出新代码；从零写题修复后仍静态失败。两份都是最大的题（改写题生成 95 个文件、判卷 152 项检查）。业务账本83/1/0不变。

## 第十一阶段：学生训练格式（2026-10-28）

导出的 `train-teacher.jsonl` 原样保存教师输入：每条带着约 7,500 字的规则手册（14 条分属 v1、v3、v4、v5 四个版本），修出来的 4 条还带着完整修复经过。学生要把规则学进模型，上线时也只拿到题目，所以新增 `student` 命令重新包装：固定短 system（`prompts/student-v1.md`，一段话）+ 与教师逐字节相同的题目（需求、合同、表结构、已有软件）+ 最终合格 SIR。合格与否仍只由 `export` 决定，`student` 只按 index 核对哈希，不重新判卷；只收 train 批次；修复经过不进入本文件，修复数据等第一版学生训练后再定。

结果：`datasets/sir/student/train-student-v1.jsonl` 共 14 条，来自 14 个批次，index 记录每条的批次、样本、业务门 run 与字数。用 `profile_tokens` 的同一套 chat template 与 tokenizer（`research/2026-10-25` 记录的修订版）实测训练序列长度，明细在 `train-student-v1.tokens.json`：

| 学生候选 | 学生格式最长 / 平均 | 教师原样格式最长 / 平均 |
| --- | --- | --- |
| deepseek-coder-1.3b-instruct | 3,874 / 2,535 | 13,432 / 6,908 |
| Qwen2.5-Coder-3B-Instruct | 3,042 / 1,999 | 10,818 / 5,618 |
| Qwen3-4B-Instruct-2507 | 3,042 / 1,999 | 10,818 / 5,618 |

全部远低于 16K。最长的是 crud-access 从零写，教师格式长是因为带着两轮修复经过。尚无合格答案的 course-selection 用参考答案估算：改写题训练序列 Qwen3 约 7.0K、deepseek-coder 约 9.1K，从零写约 5.9K / 7.6K，也在 16K 内。这是数据格式，不证明学生能力；业务账本83/1/0不变。

## 第十二阶段：规则手册 v6 与需求变体（2026-10-28）

**做了什么。**三份只读分析（`experiments/slm/drafts/prompt-audit-general-v5.md`、`teacher-error-census.md`、`course-selection/failure-analysis.md`）指出 v5 有 1 条错误规则、35 条不完整，教师初次静态通过率 32%（11/34）。据此起草并采用 `sir-0.3-general-v6`。同时为 train 集 16 份题目各写 3 种需求说法（`drafts/*/requirement-*-p[1-3].md`，逐条对照 `traceability.md` 核对，名字、数字、结尾句全部保留），生成 48 份新题：与原题只差 id 和 requirement，family、split 不变。

**采样。**48 份新题与 dev 集（shop.multiplication）2 题各用 v6 采 1 次（`pilots/*-g6`，input 277,478、其中缓存命中 158,976，output 56,551 tokens）。train 初次静态通过 29/48（60%）；dev 两题都失败。题目难度分布与第七至九阶段不同，60% 与 32% 不能直接比较。21 份静态失败各用 `repair-general-v1` 修 1 轮（input 200,711、其中缓存命中 170,112，output 40,830 tokens），7 份修好；5 份与父候选逐字节相同，其中 crud-access 从零写三份都漏了分页 `else` 错误、course-selection 改写两份约束间漏逗号，编译器只报“遇到不符合 SIR 语法的内容：as/max”，教师无从下手。

**业务门。**36 份静态通过的候选全部送 `slm-business-gate`（run #25–#60），30 份四门通过：

| 题目 | 改写（3 种说法） | 从零写（3 种说法） |
| --- | --- | --- |
| bearer-credential | 3 | 1 |
| guarded-transition | 3 | 3 |
| token-desk | 3 | 1 |
| write-slice | 3 | 2 |
| query-slice | 3 | 2 |
| relation-slice | 3 | 2 |
| crud-access | 1 | 0 |
| course-selection | 0 | 0 |

6 份失败：bearer-credential 从零写 p3 仍把登出做成需要登录（与第九阶段同一错误）；crud-access 改写 p2/p3 让其他管理员越权改到本人文章；bearer-credential 从零写 p1 与 token-desk 从零写 p2 给合同里不要求登录的接口加了认证，生成应用缺身份配置而启动失败；course-selection 改写 p1（修复后静态通过）**生成的 Java 编译失败**：候选把 `exists … as duplicate` 的变量命名为 `duplicate`，与生成器在 `persist … else` 中固定使用的 catch 变量同名。这是生成器缺陷，不是候选错误，单独处理。

36 份证据放入各批次 `runtime/`，72 个 zip 的哈希与 GitHub 记录一致，绑定、生成摘要与附件哈希逐项核对，不含 CI 密码。30 份各导出 1 条：**新教师合格训练答案累计 44 条**（鉴权 23、课程 21），train 16 份原题中仍只有 course-selection 两份没有任何合格答案。学生格式第二版 `datasets/sir/student/train-student-v2.jsonl` 共 44 条，最长训练序列 deepseek-coder-1.3b 3,874、Qwen3-4B 3,042 tokens。

**下一步。**生成器变量撞名需要修；course-selection 的必填问题由“非可选输入必填”改动处理（设计见 `experiments/slm/drafts/design/required-input-design.md`，已确认）。两项都会改变工具链摘要，此后的候选须在新工具链上重新检查。业务账本 83/1/0 不变。

<a id="slm-toolchain-recheck-v1"></a>

## 第十三阶段：必填规则、生成器变量与选课候选重查（2026-10-29）

**工具链修复已由 CI 验收。**`5cff5c313515fbd5a3593fc1f3ac2bba0b388920` 把普通非 Optional 输入统一纳入能力级必填检查，保留只作分页参数的默认值及 patch 三态，设计见 `experiments/slm/drafts/design/required-input-design.md`；[verify #198](https://github.com/HoloNova/Software-IR/actions/runs/40679190786) 为 success。`391eb87b1a4615c13b651672cd63dc629d1f386d` 将生成器在工作流方法中新增的局部变量统一为 `$` 前缀，避免与 SIR 作者变量撞名，新增 `GeneratedLocalNamesTest`（原生成器暴露六处重复定义）；[verify #199](https://github.com/HoloNova/Software-IR/actions/runs/40680535123) 为 success。本轮只核对 GitHub API 的受测 SHA、结论和运行 URL，不重跑这些验收或另报测试数量。

**重新冻结，不搬运旧资格。**从 `pilots/course-selection-change-p1-g6/` 导入新批次 `datasets/sir/pilots/course-selection-change-p1-g6t/`，保留两份教师的原始请求、响应、源码和父子关系；新增 API 调用 **0 次**，所有运行门重置为 NOT_RUN。两次修复改变了工具链摘要，后续候选须绑定新工具链；已有 44 条合格答案仍保留当时的独立证据，不追改其批次或资格。各 task 的 `reference_evidence` 保持原样，避免改变已经冻结的题目哈希；参考答案在新工具链上的 CI 证据另见 #199，而不是修改 task 冒充旧证据。

**跨平台字节检查。**实际宿主为 Windows 11，Java 21.0.11、Maven 3.9.14；18 个逻辑处理器、总内存 24093 MiB，编译前可用约 1268 MiB。工作区的 CRLF 会改变源码摘要，默认 `git archive` 也会应用本机换行设置；初次工作区检查和默认导出均未作为 CI 批次。最终用 `git -c core.autocrlf=false -c core.eol=lf archive HEAD` 从 `69237244` 导出独立快照，先逐项核对 479 个工具链/oracle 文件与 Git blob 完全一致，再在该快照中编译检查器并调用原 `import/check`。没有改写用户工作区的源码或 Git 配置，也没有手改冻结 manifest、候选或判卷规则。编译只到 `kcg-cli -am compile`，单进程限制 heap 384 MiB、metaspace 192 MiB、ActiveProcessorCount=2；没有本机真库、业务 IT 或全量测试。规范批次及字节核对记录在 `verification/checkout-verification.json`，临时 CRLF 探针保留于 `target/slm-handoff/`，不当长期证据。

新批次 `toolchain_core_sha256=7275f533d442297d7c43b62317168e09736be9c369d9811c480e8aefad070d54`；oracle 仍为 `9dea6bbcc7b9defc7807dfa1523ba976704a5b25e6dd6c3b2c95cf090d771d74`。`checked.jsonl` 的实际结果：

| 来源 | 候选短 ID | 静态结果 | 生成文件 | 构建／启动／业务 |
| --- | --- | --- | --- | --- |
| 既有参考 | reference | GENERATION PASSED，无诊断 | 95 | NOT_RUN |
| 原始教师 | `e7bb44f7a3e1` | PARSE FAILED，5 条诊断 | 0 | NOT_RUN |
| 一轮静态修复后的教师 | `e61b238d030f` | GENERATION PASSED，无诊断 | 95 | PASSED／PASSED／PASSED（业务门 #61） |

目标候选 `e61b238d030f` 的生成摘要为 `678a2311f9eb42508799d9d0b5d3624abd979b787ea89f9a0250a9f11e9459da`。负责人通知通过后已核对并归档 [slm-business-gate #61](https://github.com/HoloNova/Software-IR/actions/runs/40795909868)（受测提交 `bfd7d7684fdb98a8257fd559b3263717300e2a93`）：四门全 PASSED，业务 **152/0**，确认旧生成器构建失败已在同一候选上闭环。两个 artifact 的 ZIP 摘要均与 GitHub 记录一致，候选/题目/oracle/工具链/生成摘要及全部附件绑定通过，原始证据、JUnit 与 `gate-run.json` 已保存于该批次 `runtime/`；复用原归档器和 `export` 导出 `train-teacher.jsonl` **1 条**。归档在只含目标批次的临时工作目录执行，避免同一候选在旧/新批次中的副本造成歧义，没有修改归档器或证据。合格教师答案累计 **45 条**（鉴权 23、课程 22），首次有合格的选课改写答案；选课从零写仍无合格答案。学生格式 v2 保留当时的 44 条，待下一轮判卷结束统一出新版本。本次重判新增 API 调用仍为 0，未修改冻结 task、未训练模型。
