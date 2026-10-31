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

<a id="slm-prompt-v7-initial"></a>

## 第十四阶段：v7 定向首轮采样（2026-10-29）

负责人确认 v7 的三处差异与首轮 14 次调用预算后，已提交规则手册及说明（`6c54c42c`）。没有改动已使用的 v6、默认 prompt、冻结 task、编译器或 oracle。采样使用 `96f536261671d9a1f20a1f68cc3567c6bf2c90c3` 的 LF 字节一致环境，工具链摘要仍为 `7275f533d442297d7c43b62317168e09736be9c369d9811c480e8aefad070d54`；没有重跑构建、真库、全量测试或启动应用。

**14 次尝试收到 13 份答案，静态 0/13 通过；另 1 次连接失败，不伪装成教师答错或用量 0。**8 份选课、4 份 CRUD 从零写属于 train；2 份乘法题属于 development，不混入训练集。每份只调用 `generate --count 1 --max-tokens 8192`，SDK 自动重试关闭；14 份参考均在当前检查器静态通过。所有新批次在 `datasets/sir/pilots/*-g7/`，完整结构化汇总见 `datasets/sir/research/2026-10-29/v7-initial-sampling.json`。

| 批次（均为 g7） | 候选短 ID | 初始结果 | 输入 tokens（其中缓存命中） | 输出 tokens | 已知合计 |
| --- | --- | --- | ---: | ---: | ---: |
| course-selection-change | `e8e641739dc2` | PARSE FAILED | 8,111（768） | 2,745 | 10,856 |
| course-selection-change-p1 | `713a0908b68a` | PARSE FAILED | 8,127（3,584） | 2,659 | 10,786 |
| course-selection-change-p2 | `f63c8bf2e0ee` | PARSE FAILED | 8,169（3,584） | 2,722 | 10,891 |
| course-selection-change-p3 | `55445a6ca094` | PARSE FAILED | 8,177（3,584） | 2,677 | 10,854 |
| course-selection-scratch | `06c2dd85ffe4` | PARSE FAILED | 6,883（3,584） | 3,401 | 10,284 |
| course-selection-scratch-p1 | `d3101cf2a472` | PARSE FAILED | 6,935（3,584） | 3,274 | 10,209 |
| course-selection-scratch-p2 | `ffa8a15cd83f` | PARSE FAILED | 7,069（3,584） | 3,247 | 10,316 |
| course-selection-scratch-p3 | `78d1e7234e75` | PARSE FAILED | 7,081（3,584） | 3,264 | 10,345 |
| crud-access-scratch | `ff7db77818e2` | SEMANTIC FAILED | 5,297（3,584） | 1,468 | 6,765 |
| crud-access-scratch-p1 | `20555d93f3e9` | PARSE FAILED | 5,306（3,584） | 1,360 | 6,666 |
| crud-access-scratch-p2 | `003afdbc8edf` | SEMANTIC FAILED | 5,352（3,584） | 1,418 | 6,770 |
| crud-access-scratch-p3 | `53c88a097865` | SEMANTIC FAILED | 5,383（3,584） | 1,406 | 6,789 |
| multiplication-change | 仅保留未获响应请求 | APIConnectionError，HTTP=None | 未知 | 未知 | 未知 |
| multiplication-scratch | `cb04c46a81ee` | PARSE FAILED | 9,468（3,584） | 6,541 | 16,009 |
| **13 份已返回用量合计** | | **PARSE 10／SEMANTIC 3** | **91,358（43,776）** | **36,182** | **127,540** |

**失败原因按诊断与原始源码交叉核对。**前三份选课改写把字段约束写成 `where min(1) and max(7)`，约束应以逗号分隔；选课改写 p3、三份选课从零写及乘法从零写中可见把保留字 `target`／`order` 当变量名。选课从零写 p1 把 `exists` 步骤错误嵌进 `validate`；CRUD 从零写 p1 的分页 `find` 缺少 `else 错误`，因此在 `as` 才触发语法诊断。三份 CRUD 语义失败涉及显式绑定框架管理的 version、版本实体行内加法，以及 `fails` 名称顺序。这些是当前诊断已定位的问题，不声称修掉第一处后就没有其他错误；没有手改源码、给教师喂参考答案、修改解析器或放宽判卷。首轮未到业务门，不能据此评价新必填规则的真实业务通过率，也不把 13 份小样本当作受控版本对照实验。

第 13 次仅保存了 `request.json`，没有 `response.json` 或 SDK usage；CLI 返回 `teacher request failed: APIConnectionError HTTP=None; no automatic retry`。另以 `verification/collection-status.json` 记录这一事实，保留未获响应的请求指纹，不补造正常采样记录；该请求是否发生服务端计费未知，127,540 仅是已知合计。

**本轮边界。**新增 API 尝试 14 次，已知响应 13 份；反馈修复 0 次，业务门派发 0 次，新增合格答案 0 条。累计合格教师答案仍为 **45 条**，学生 v2 保持当时的 44 条，未训练。待负责人批准后，13 份失败候选各修一轮，预计输入 **148,100**、输出 **35,800** tokens；这是依据前次 prompt/completion 与新增诊断、DDL 反馈估算，缓存命中未知。网络失败的乘法改写若单独补采一次，另估输入 **12,900**、输出 **5,500**；两项合计约输入 **161,000**、输出 **41,300**（**202,300**），不包含补采后继续修复。未批准前不追加付费调用。

<a id="slm-prompt-v7-repair"></a>

## 第十五阶段：v7 一轮反馈修复与网络失败题补采（2026-10-29）

负责人确认第二笔预算后，沿用第十四阶段的 LF 字节一致环境与冻结输入，对 13 份失败候选各执行一次 `repair --feedback experiments/slm/prompts/repair-general-v1.md --max-tokens 8192`，并对先前连接失败的乘法改写题补采一次。**14 次请求均收到响应；13 份修复中 3 份静态通过，补采的 1 份语义失败。**没有第二轮修复、自动重试、手改候选或修改规则手册／解析器／oracle。完整汇总见 `datasets/sir/research/2026-10-29/v7-repair-sampling.json`。

13 份修复结果写入各批次 `checked-r1.jsonl`，补采结果写入 `multiplication-change-g7/checked-resample.jsonl`。旧 `checked.jsonl`、冻结输入、父候选、请求／响应及前次连接失败记录均原字节保留；`attempts.jsonl`／`candidates.jsonl` 仅追加。逐份使用原 `validate_candidates` 验证候选与原始响应、请求、题目和工具链的绑定，并核对修复消息严格由原对话、父答案、原反馈、原始诊断和题目 DDL 组成，没有追加人工修法或参考答案。14 批工具链与 oracle 的并集共 **493 个输入文件**逐项匹配提交 `d439f60f3f2dce5e33aeccd14bdaff6b13b80248` 的 Git blob；未重新编译、运行全量测试或启动应用／数据库。

| 批次（均为 g7） | 新候选短 ID | 本次静态结果 | 输入 tokens（其中缓存命中） | 输出 tokens | 合计 |
| --- | --- | --- | ---: | ---: | ---: |
| course-selection-change | `aaa219a02ae2` | PARSE FAILED，原样交回 | 12,044（10,752） | 2,745 | 14,789 |
| course-selection-change-p1 | `0cfdb95ee0db` | PARSE FAILED，原样交回 | 11,977（10,752） | 2,659 | 14,636 |
| course-selection-change-p2 | `9ebe1732f239` | PARSE FAILED，原样交回 | 12,083（10,880） | 2,722 | 14,805 |
| course-selection-change-p3 | `05dd265870d4` | SEMANTIC FAILED | 12,532（10,752） | 2,694 | 15,226 |
| course-selection-scratch | `4e00239550e2` | SEMANTIC FAILED | 11,489（10,240） | 3,397 | 14,886 |
| course-selection-scratch-p1 | `5a999074e6f7` | PARSE FAILED | 11,561（10,112） | 3,266 | 14,827 |
| course-selection-scratch-p2 | `793f0aae8863` | PARSE FAILED | 11,521（10,240） | 3,254 | 14,775 |
| course-selection-scratch-p3 | `36183be29931` | SEMANTIC FAILED | 11,549（10,240） | 3,276 | 14,825 |
| crud-access-scratch | `4a6b10922574` | GENERATION PASSED，58 文件 | 7,874（6,656） | 1,453 | 9,327 |
| crud-access-scratch-p1 | `221962c44f5e` | PARSE FAILED，原样交回 | 7,435（6,656） | 1,360 | 8,795 |
| crud-access-scratch-p2 | `976fd70bd19f` | GENERATION PASSED，58 文件 | 8,109（6,656） | 1,412 | 9,521 |
| crud-access-scratch-p3 | `3a97ea60acea` | GENERATION PASSED，55 文件 | 7,555（6,784） | 1,401 | 8,956 |
| multiplication-scratch（dev） | `fa008e7f0e4a` | SEMANTIC FAILED | 18,144（16,000） | 6,587 | 24,731 |
| multiplication-change（dev，补采） | `06dece4fca65` | SEMANTIC FAILED | 12,828（12,672） | 5,481 | 18,309 |
| **本轮合计** | | **通过 3／解析失败 6／语义失败 5** | **156,701（139,392）** | **41,707** | **198,408** |

预计总量 202,300，实际已知总量 198,408；输入与输出分别报告，不把高缓存命中等同于零成本。两阶段累计尝试 28 次、收到 27 份响应，已知输入 248,059、输出 77,889、合计 325,948；首轮连接错误的那次用量仍未知，不计为 0。

**错误推进而非循环修到通过。**前三份选课改写仍使用 `where min(1) and max(7)`，CRUD p1 的分页仍漏 `else`，四份新答案的源码哈希与父候选完全相同；保留新请求和响应，但不送业务门。选课改写 p3 修复保留字后暴露 `UserView` 重复定义；选课从零写仍有错误的查询表达式、可选值／身份类型、`any` 缺关联条件、未声明字段／错误及 `fails` 顺序问题。乘法从零写暴露输出类型、错误声明、锁条件和更新守卫问题；补采的乘法改写有 `each` 未声明失败与 `total` 绑定类型错误。两份乘法答案各自保留一条 SIR-WARN-001，判失败依据是 ERROR，不是告警。源码原样交回的事实不推断为服务端缓存故障，报错改善也不在本轮擅自实施。

**业务门边界。**本次仅有上述 3 份 CRUD 候选具备送 `CrudAccessBusinessConformanceIT` 的静态前提，均无诊断。提交批次后按样本分别派发，运行证据由负责人通知结果后再归档；本报告当前 BUILD／START／BUSINESS 均为 **NOT_RUN**，不能把静态通过记成合格。合格教师答案仍为 **45 条**，学生 v2 保持 44 条；本轮不新增训练输出、不启动训练，dev 答案不进入 train。业务门完成并核对原始附件后，再导出合格答案并统一生成下一版学生数据。

<a id="slm-prompt-v7-qualified"></a>

## 第十六阶段：v7 业务门归档与学生格式 v3（2026-10-29）

负责人要求继续后，一次性查看第十五阶段派发的三个指定业务门，均已 completed/success；没有定时轮询或重复派发。受测源码统一为 **`e1a51bb775aaeab52d30b9dd3d1758f74e4c0a7d`**。随后用原归档器下载两类 artifact，核对 ZIP 摘要与 GitHub 记录、候选／task／oracle／工具链／生成摘要绑定、每个运行附件哈希和无 CI 口令，再用原 `export` 导出，不能只按工作流绿色计合格。

| 批次 | 候选短 ID | GitHub 业务门 | 四门 | 业务检查 | 导出 |
| --- | --- | --- | --- | --- | --- |
| crud-access-scratch-g7 | `4a6b10922574` | [#62 / 40801380578](https://github.com/HoloNova/Software-IR/actions/runs/40801380578) | 全 PASSED | 48/0 | 1 条 |
| crud-access-scratch-p2-g7 | `976fd70bd19f` | [#63 / 40801384180](https://github.com/HoloNova/Software-IR/actions/runs/40801384180) | 全 PASSED | 48/0 | 1 条 |
| crud-access-scratch-p3-g7 | `3a97ea60acea` | [#64 / 40801387816](https://github.com/HoloNova/Software-IR/actions/runs/40801387816) | 全 PASSED | 48/0 | 1 条 |

三个批次的 `runtime/<sample>/` 保存 `slm-runtime.json`、原始业务报告、4 份 Maven/Spring 日志、JUnit 报告与 `gate-run.json`，`train-teacher.jsonl` 各 1 条。汇总在 `datasets/sir/research/2026-10-29/v7-business-gate-summary.json`，每个 `gate-run.json` 记录运行 URL、受测 SHA、artifact ID 和 ZIP 哈希。本机生成的 gate 元数据统一为 LF；原始 CI 附件不改字节。日志受 `*.log` 忽略规则影响，必须显式纳入 Git 并按实际 Git blob 核对；原始日志的行尾空白作为证据格式例外保留，不清洗。**本轮新增 3 条，累计合格教师答案 48 条（鉴权 26、课程 22）**；v7 此轮未使选课从零写或 dev 乘法题合格，不追加修复或放宽判卷。

**学生 v3 的跨工具链包装。**旧 44 条绑定 `51d9566d…`，新 4 条（第十三阶段选课改写 1 条及本阶段 CRUD 3 条）绑定 `7275f533…`，历史 oracle 也有不同版本。`student` 会经 `read_bundle` 校验当前工具链／oracle，不能直接在新源码下混跑，更不能改 manifest 或跳过校验。因此保留已封存 `train-student-v2.jsonl` 的 44 条原始字节，仅在 LF 快照中用原 `student --bundle` 包装上述 4 个新合格批次为增量，再拼成 `train-student-v3.jsonl`；新 index 的 `assembly` 记录基座哈希、原字节保留数、增量批次及增量哈希。v2 及其 index/token 报告不改，旧成功不冒充新候选或新工具链的证据。

合成后逐条核对 48 条的 teacher 导出文件及索引哈希、冻结 task/base/contract 输入、原候选源码、完整题目、短 system、运行证据绑定和四门结果；与所有非空教师导出的 ID 集合完全一致，按同题／源码去重且全部 split=train，无 dev/test 或修复过程混入。v3 的 `corpus_sha256=a123b56fdb1bbe92a65a30a5c37e12ee7d1cf132cb5bd9cf8b29486c67245578`。这只是已验收答案的训练格式，不是学生模型训练或评测成绩。

使用 `profile_tokens.measure`、冻结的三个 Hugging Face revision 和原缓存分词器在 CPU 上实测，关闭截断／padding；核对模型元信息、chat 配置与既有分词器摘要，没有下载或执行模型权重：

| 分词器 | 冻结 revision | 48 条平均长度（四舍五入） | 最长完整训练序列 |
| --- | --- | ---: | ---: |
| deepseek-coder-1.3b-instruct | `e063262dac8366fc1f28a4da0ff3c50ea66259ca` | 2,733 | **9,311** |
| Qwen2.5-Coder-3B-Instruct | `488639f1ff808d1d3d0ba301aef8c11461451ec5` | 2,141 | **7,102** |
| Qwen3-4B-Instruct-2507 | `cdbee75f17c01a7cc42f958dc650907174af0554` | 2,141 | **7,102** |

最长项均为选课改写 p1 `e61b238d030f`，不能沿用 v2 的 3,874／3,042 上限；当前实测仍小于 16K，完整逐条结果在 `train-student-v3.tokens.json`。本次归档／包装／计数 API 调用 **0 次**，没有 Maven 构建、全量测试、本机业务 IT、应用／数据库进程或训练。第十五阶段费用统计不变：已知累计 325,948 tokens，首轮连接错误用量仍未知。

<a id="slm-blog-oracle-local"></a>

## 第十七阶段：博客七能力参考与业务判卷实现（2026-10-29，待 CI）

负责人确认七能力 IT 的结构后，按 `experiments/slm/drafts/blog/spec.md` 新增裁剪参考 `valid/blog.sir`、完整七表 DDL/seed、仅删 Publish/AttachTag/PostsByTag/LikePost 的 base、两份原始题面、合同及逐项[追溯表](../../experiments/slm/drafts/blog/traceability.md)。题面与批准规格逐字一致，合同逐对象一致，SQL 语句一致；不修改完整 `topics/blog/project.sir`，不保留隐藏的 Crud 展开、登录或评论，也不扩语言或追加隐性评分。

**判卷实现。**`BlogBusinessConformanceIT` 复用原 BusinessSliceHarness／SlmCandidateSupport，对参考或教师运行同一编译、构建、启动、业务流程，只启用真实 external Bearer 身份。BlogPublicationChecks、BlogTagChecks、BlogLikeChecks 覆盖规格 B/P/Q/T/L；BlogAssertions 做精确 JSON key／数值类型／嵌套与 JDBC 对照，BlogFixture 从独立只读连接比较七表所有列和行，成功写也限制完整数据库差异。所有辅助类仅属本题测试，不改变共享 Harness、生产编译器或公共 HttpAssertionClient。

**真实竞争与失败边界。**四轮各重置同一 seed、使用六个独立 HTTP 客户端及 ready/start latch。控制端持有目标 post 行锁，BlogLockWaits 追踪本 schema 的真实等待链，必须同时观测六个不同运行时连接和事务，不能用一个等待或重复边冒充。分别检查发布唯一赢家、重复标签、同用户重复赞、不同用户全成功与最终 likes=COUNT(like)。请求 30 秒、结果收集 35 秒、锁观测 5 秒；超时或异常不转成 409，也不继续重置可能还有在途请求的数据。时间线、目标／连接／事务和锁图写入原业务报告，候选模式沿用原 `slm-runtime.json` 附件哈希。自审额外用单项真实 RED 复现了“future 已完成但结果收集超过截止仍被接受”，现已拒绝该情况并保留晚到响应用于排查。

**本机定向。**Windows，Java 21，Maven 3.9.14，`D:/maven-repo`；资源采样为 18 个逻辑处理器、总内存 24093 MiB、可用 4674 MiB。Maven 与测试 fork 均显式设置 `-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2`，移除 JAVA_TOOL_OPTIONS，没有 Maven -T。最终 `-pl sir-toolchain-application -am -Dtest=BlogReferenceContractTest,BlogOracleTest` 重新编译全部 **177 个测试源**，当次 Surefire XML 为 **5 + 11 = 16 项，0 失败／0 错误／0 跳过**，封存在 `target/blog-verification/final/`。覆盖裁剪参考和 base 完整静态生成、仅删四能力、路由／创建状态、实际 Spring external 环境绑定、DDL 声明、精确 JSON、完整快照、真假六事务／间接锁等待及超时反例。源码级覆盖率未测量，不据此声称业务 HTTP/MySQL 已通过。两次 AFT 静态诊断请求超时，未把它记作诊断绿灯。

**保留环境阻断。**另一次附带的 SlmCandidateSupportTest 回归共有 9 项：7 通过、1 失败、1 错误；两项根因均为 Windows 返回 `SIR-APP-SOURCES-READ-001: filesystem does not support secure directory-relative source reads`，在候选安全读源阶段即被拒绝。本轮不改生产读源规则、不加跳过或把那次 24 项总运行记成通过，完整 XML/文本与 Maven 输出保留在 `target/blog-verification/pre-deadline-review/`，对应 Linux 验证仍待 CI。最初缺资源的 RED、oracle 编译期 RED、截止边界 RED 分别留在 `red/`、`oracle-red-compile.txt`、`deadline-red/`。

**CI 与正式题目的界线。**verify.yml 已接入 BlogBusinessConformanceIT／kcg.blog-conformance.enabled，并单独要求唯一博客报告的正式第二行 `verdict=PASSED`；NOT_RUN、缺失或重复报告以及响应中伪造的后续 verdict 都不能补位。slm-business-gate.yml 已增加对应选项与属性映射。工作流 YAML／内嵌 Python、两份题面／SQL／合同和上述证据守卫的正反例已校验；检查脚本在 `target/blog-verification/check_materials.py`。当前参考的构建／启动／真实业务均 **NOT_RUN**，没有提交或推送本批代码、没有派发新 CI，也未创建正式 blog task 或冻结批次。待负责人明确授权提交推送、参考在同 SHA 的 verify 取得四门证据后，再核对 base 负例、填写真实 reference_evidence 并冻结 task；不能引用旧完整博客的结果。API 调用 **0 次**，合格答案与学生 v3 仍为 **48 条**，没有训练或 UI 改动。


<a id="slm-blog-reference-frozen"></a>

## 第十八阶段：博客参考 CI 验收与两题冻结（2026-10-29）

负责人通知 CI 完成后，一次性核对 **`66c276915d825f4d1914da721b1ddf4e0ceda2de`** 的 [verify #200 / 40839225636](https://github.com/HoloNova/Software-IR/actions/runs/40839225636)：两个 job 均 success，参考业务报告固定第二行为 `verdict=PASSED`，完整生成 55 文件、离线构建成功、匿名真实路由 readiness=200，**业务 425/0**。四轮都记录了各六个不同运行时连接／事务连到持锁控制连接的等待图、ready/start/end 和实际 HTTP；应用退出、自有 schema 删除、advisory lock 释放均有 PASS。不是仅据工作流绿色推导业务通过。

**原始证据。**`datasets/sir/research/2026-10-29/blog-reference-ci200/` 保存 GitHub run/jobs/artifacts 元数据、三个原始 ZIP、博客原始报告和四个 Maven/Spring 日志、四类完整 JUnit XML及摘要；下载时逐 ZIP 核对 GitHub SHA256，确认无 CI 口令标记。报告中的参考／DDL／seed 三个 SHA256 与受测 Git blob 一致。三个 ZIP 的 artifact ID 分别为 conformance-evidence=`12881819635`、surefire-reports=`12881989005`、standalone-cli=`12881744793`，完整摘要见归档 metadata。上传 Surefire 实际为 **292 XML／2009 项，0 失败／0 错误／0 跳过**；选择归档的 BlogReferenceContractTest=5、BlogOracleTest=11、BlogBusinessConformanceIT=1、SlmCandidateSupportTest=9，全部通过。此前 Windows 阻断的两项在同 SHA Linux 正常完成，不需要降低安全读源要求。原始字节不清洗；日志行尾空白保留为证据格式例外。

**base 正反边界。**CI 的 `baseCompilesAndRemovesOnlyTheFourNewCapabilities` 已验证两边完整静态生成、仅删四能力及 Controller 缺失；又用该 CI 原 standalone CLI 的核心 API 在内存实际生成参考 55／base **47** 文件，确认 Publish、AttachTag、PostsByTag、LikePost 四个 Controller 只在参考出现。base 并非空壳，创建／搜索／详情仍保留；缺四条新增路由说明无法满足相应成功检查，但 **base 的真实 HTTP 负例为 NOT_RUN**，不虚构应用已运行或业务失败报告。辅助脚本最初误填 base=43，现以真实生成文件集和缺失 Controller 判断；43 不是题目要求或验收标准。

**两种生成摘要不能直接比较。**冻结检查曾因把两个既有协议直接相等而阻断。`BusinessSliceHarness.digestOfGeneratedFiles` 按生成顺序拼接 path/NUL/content/NUL，报告的旧 combinedSha256 为 `f2c63163afd0badafea272fb5e244f0ae82cc2683d1f5e7e3448f4269fc85e82`；CLI `check.digest` 使用生产 `ValidationHashes.generated` 的版本前缀、排序和长度帧，为 `95ebf3692504bd9552da79e02c7db3dc2c68dd3ac52b8abfebecec397a2c73cf`。已复用同 CI 核心的 ProjectCompilation 对同一文件集同时计算两者，各自精确匹配 CI 报告与 CLI 输出，证明不是生成产物变化。验证 helper、来源摘要和双协议证明已归档；不修改生产算法、旧报告、候选或 manifest，也不另写编译语义。后续候选仍绑定版本化摘要，不能把旧 combinedSha256 填进去。

**正式题目与闭包。**新增 `experiments/slm/tasks/blog-change-v1.json`、`blog-scratch-v1.json`，ID 分别为 `content-blog-change-v1`／`content-blog-scratch-v1`，family=`content.blog`、split=`train`，原需求、合同、base／参考不变。reference_evidence 只指上述本题受测 commit／run，kind 仍为 existing-reference-only-not-candidate-qualification。共用 **38 项 oracle**（35 个 Java + DDL/seed/参考），以 token-desk 的公共依赖闭包为底、替换为博客入口与全部 helper；逐文件核对存在性和受测 Git blob，还将这 35 个源单独对 CI 核心 JAR／外部依赖编译，排除所有既有 test-classes、禁用隐式补编和注解处理，验证没有遗漏测试辅助源。不是一次生成工程构建或新增业务验收。全题库现 73 份定义，既有 task 不改。

**跨平台冻结与静态检查。**从受测 commit 使用命令级 `core.autocrlf=false`／`core.eol=lf` 导出独立快照，**448 个核心输入 + 38 个 oracle 输入**均逐字节匹配 Git blob；未修改用户工作区源码换行或 Git 配置。用原 `prepare` 和已批准 v7，冻结 `datasets/sir/pilots/blog-change-g7/`、`blog-scratch-g7/`；再用原 `_check_rows`／`bind_static` 与该 CI 的 standalone CLI 做完整静态生成，避免依赖本机增量 class。工具链仍为 `7275f533d442297d7c43b62317168e09736be9c369d9811c480e8aefad070d54`，博客 oracle 为 `b19875a18b071c0921ad34072f9de4c8c10b668db69fb5415eb3b401d6a7c843`；两批参考均无诊断、55 文件、版本化摘要与上述相同。每批仅 1 份 existing_reference，BUILD/START/BUSINESS 按 prepare 规则保持 NOT_RUN，不将参考的既有成功直接写成候选资格。该规则同样适用于以后生成的教师答案。

本机没有 Maven／全量测试／真库／应用／训练或新模型调用，只运行了有界内存静态检查和 oracle 子集编译；Java 子进程均限制 heap 256 MiB、metaspace 128 MiB、ActiveProcessorCount=2 并已结束。合格教师答案仍为 **48 条**，学生 v3 不变。下一步建议两题各初采 1 次，deepseek-flash／v7，按既有 v7 实际用量拟合并留余量：改写预计输入 **8,000**、输出 **2,000**，从零预计输入 **7,500**、输出 **2,000**，合计 **19,500 tokens**；每次输出上限 8192，缓存未知、不自动重试，修复不在这笔预算内。完整方法和逐项估算在 sampling-plan.json，状态为待负责人批准，尚不发送请求。

收尾的两份新 task 定向 AFT 请求也在 150 秒后因 bridge busy/under load 超时，未将其记为诊断通过；本阶段的结构依据为实际 JSON Schema／家族隔离／冻结字节核对和独立 oracle 子集编译。原始日志的空白例外只限已验证哈希的 Maven/Spring stdout，其他新增文本须通过正常差异检查。


<a id="slm-blog-v7-initial"></a>

## 第十九阶段：博客 v7 首轮两题采样（2026-10-29）

负责人确认第十八阶段 **19,500 tokens** 的预计预算后，两题各初采 1 次，实际请求均为 deepseek-flash／v7、temperature=0.6、thinking=disabled、max_tokens=8192、max_retries=0。只执行获批的 **2 次**，没有自动重试、反馈修复或额外采样；两份响应均 finish_reason=stop。沿用原 generate_one／采集器，不手改教师答案。

在既有 LF 源码快照中调用原 `read_bundle`／`validate_candidates`，每次发送前核对 **486 项**核心／oracle 输入与本轮基线 `26ffe06dfddbf26a6f58ae5a942c5a53883d95b6` 的 Git blob；只向主工作区原批次追加记录，不 import 新题、不改 task.json／manifest.json／system／base／reference。静态检查复用原 `_check_rows`／`bind_static` 和 verify #200 的原 standalone CLI，先逐字节比对 JAR 与已归档 ZIP 成员；没有使用本机增量 class，也没有 Maven 构建或本机业务 IT。原 checked.jsonl 参考结果保留，新教师结果另写 **checked-initial.jsonl**。

| 题型 | 候选短 ID | 输入 | 其中缓存命中 | 输出 | 合计 | 静态结果 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 改写 | `e6e632222754` | 7,319 | 3,584 | 1,474 | 8,793 | SEMANTIC FAILED，5 条 ERROR |
| 从零写 | `6ed907a7bdaa` | 6,608 | 3,584 | 1,509 | 8,117 | PARSE FAILED，1 条 ERROR |
| **合计** | **2 份** | **13,927** | **7,168** | **2,983** | **16,910** | **0/2 静态通过** |

改写候选在 exists 的 PostTag／Like 比较里写成 `post == post`、`tag == tag`、`user == actor`，未按查询作用域引用 item；实际诊断为 4 条 SIR-VALID-001 和 1 条 SIR-FLOW-001（未定义 user）。从零候选的 `where length(1, 100) notBlank` 约束直接相邻，在 notBlank 处得到 SIR-PARSE-001。只记录本轮实际诊断，不把未执行的后续语义或业务阶段推测成失败，也不为这些答案改判卷器或手册。

原请求、SDK 序列化完整响应、usage／fingerprint、源码、candidate／attempt 和采集器快照均在原两个 g7 批次；新建的 `research/2026-10-29/blog-v7-initial/` 保存逐次记录、汇总及下一轮预算依据。响应／请求／源码／冻结输入及采集器摘要已逐项核对；所有候选仍是 initial、parent_id=null，原参考和旧检查字节不变。CLI 命令的重复元数据输出保留在本机 target，权威原始响应与 attempt 不省略。openai-python 2.24.0 SDK 序列化响应不是 HTTP 原始字节，也不把模型别名当权重 revision。

**没有派发业务门。**两份均未过静态门，BUILD／START／BUSINESS 全为 NOT_RUN，新增合格教师 **0 条**，累计仍 **48 条**，学生 v3 不变，未训练。实际总用量比预计少 2,590 tokens，不将差额视为可自行追加调用的授权。

下一步拟各做 **1 次**原 `repair-general-v1.md` 静态反馈修复，使用 checked-initial.jsonl 的实际诊断及题目已有 DDL，不附参考答案、不改题。仅复用已归档 13 次 v7 静态修复的新增消息字符／实际输入增量估算，留 10% 输入余量、按 500 向上取整，输出留 20% 且至少 2,000：改写预计输入 **11,500**／输出 **2,000**，从零预计输入 **10,000**／输出 **2,000**，共 **25,500 tokens**。每份输出上限仍为 8192，缓存未知、不自动重试；repair-plan.json 为 **待负责人批准**，本轮未发送修复请求。

本阶段收尾对新汇总 JSON 的一次定向 AFT 请求仍因 bridge busy/under load 在 150 秒后超时，未记为诊断通过；实际完成的检查为原采集器绑定／响应与源码哈希、旧冻结字节保持、四份清单及暂存差异检查，没有以工具超时或 CLI 退出码 0 冒称候选通过。


<a id="slm-blog-v7-repair"></a>

## 第二十阶段：博客候选各一轮静态反馈重答（2026-10-29）

负责人确认 **25,500 tokens** 的预计预算，并明确当前没有学生模型训练后，用原 `repair-general-v1.md` 对两份初始候选各请求 **1 次**反馈重答。这里重答的是教师的 SIR 候选，不是题目、编译器、判卷器或已验收数据；失败答案及报错保留为研究轨迹，不作为当前学生 SFT 的正确 completion。原教师导出可保留反馈上下文，但 student 包装会去掉修复过程；本轮没有任何候选达到导出资格。

沿用同一 LF 快照、原 repair／generate_one／_check_rows／bind_static 和 verify #200 原 CLI，显式选择各批 checked-initial.jsonl；发送内容逐项核对为原题消息、父答案、原始静态诊断和题目已有 DDL，没有加入参考答案或人工修改的提示。每次前后仍核对 486 项核心／oracle 的 Git blob、冻结输入及原 JAR 字节，SDK 参数为 deepseek-flash、max_tokens=8192、max_retries=0；两份均正常返回 stop。没有自动重试或在一轮后继续追修。

| 题型 | 父 → 子候选短 ID | 输入 | 其中缓存 | 输出 | 合计 | 子候选静态结果 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 改写 | `e6e632222754` → `0a94bc34bfcb` | 10,642 | 8,704 | 1,479 | 12,121 | SEMANTIC FAILED，2 条 ERROR |
| 从零写 | `6ed907a7bdaa` → `e7c30fe85dd5` | 9,219 | 8,064 | 1,506 | 10,725 | SEMANTIC FAILED，18 条 ERROR |
| **合计** | **2 次重答** | **19,861** | **16,768** | **2,985** | **22,846** | **0/2 静态通过** |

改写子答案纠正了两个 exists 的 item 字段引用，但同时把 any 内的关联字段改成 `item.post`，此处 item 仍为根 Post；实际得到 SIR-TYPE-004（缺合法根关联比较）和 SIR-SYMBOL-002（Post.post 不存在）。从零子答案仅移除了字段约束里相邻的 notBlank，解析因此通过，随后暴露引用投影／create 绑定类型不匹配、三个输入类型未定义及其路径连带诊断、Optional 搜索值类型和查询作用域等 18 条 ERROR。父子源码差异已归档；后者是原有问题在越过解析门后变得可见，不能仅按“1 条变 18 条”认定教师新引入了 18 个独立缺陷。

原始父答案、旧请求／响应和初始检查不变，两个新答案均确有源码变化，parent_id／请求／响应／源码／采集器哈希已核对。结果另存 **checked-r1.jsonl**；新汇总、逐次用量及教师改动的零上下文 patch 在 `datasets/sir/research/2026-10-29/blog-v7-repair/`，补充字节清单不改冻结的 manifest.json。报告准确保留本轮 ERROR；CLI 退出码 0 不等于候选成功。

**按每个失败候选只反馈一轮的上限停止。**两份 BUILD／START／BUSINESS 仍全为 NOT_RUN，不派发业务门、不导出、不追加 API 请求，也不改题或放宽验收。博客初采加本轮共 **4 次 API**：输入 **33,788**（缓存 **23,936**）、输出 **5,968**，合计 **39,756 tokens**；本轮比预计少 2,654，不将余额转作额外授权。新增合格 **0**，累计合格教师仍 **48 条**，学生 v3 原字节不变，没有训练、Maven 或本机真库／应用运行。

交接 4.5 的参考验收、base 静态负例、正式 task 冻结、初采及每份一轮反馈均已有实际记录，但博客 family 仍无合格教师答案，不把“流程已执行”冒称业务数据已覆盖。4.6 的任务板 spec 在本次核对的工作区和远端 main 中仍未到位，待另一端提交后再审核；其他待负责人决定的语言／prompt 设计及学生训练不自行开始。

收尾对本轮逐次 JSON 的一次定向 AFT 请求仍在 150 秒后因 bridge busy/under load 超时，未计诊断通过；正式结论来自原编译器的实际诊断和原始记录绑定／字节检查。此轮只有数据与配套说明变更，不以分析工具不可用为由改候选、改判卷或补跑本机全量测试。


<a id="slm-blog-v8-prepared"></a>

## 第二十一阶段：博客 v8 新批次准备（2026-10-29，待 CI 通知与采样预算）

负责人授权先准备批次、待其通知 CI 结果；本阶段**没有查询 CI，也没有把“默认通过”写成验收证据**。从 `2e4fa74be63e694b580d991c6a54f60d7d2ce2eb` 使用命令级 `core.autocrlf=false`／`core.eol=lf` 导出独立源码快照（排除历史 datasets），核对全部 **1440 个文件**与 Git blob 一致；不修改用户的 Git 配置或现有文件换行。

复用该快照的原 `prepare` 创建 `datasets/sir/pilots/blog-change-g8/`、`blog-scratch-g8/`，显式选择已批准 v8。每批只有 1 条 existing_reference，**STATIC／BUILD／START／BUSINESS 全部 NOT_RUN**，没有教师答案、请求或响应。本次仅准备输入，未运行检查器，不沿用 #200 的静态或业务资格。task.json、base、参考、user 消息与 g7 一致，oracle 的 38 项原始输入不变；任务中的历史 reference_evidence 仍保留 #200，不修改题目哈希。新工具链绑定 `7009975336558548cedcdaf0b722240946dc927ac678c7b6895f97d42db1b1e1`，oracle 仍为 `b19875a18b071c0921ad34072f9de4c8c10b668db69fb5415eb3b401d6a7c843`；每批 448 项核心输入、38 项 oracle、238 项实验输入全部核对 Git blob。v8 原文摘要为 `f18c4abdcf187f4d36d8d78cc7a78fb5d2265014864062bfd79dbb5169f7890f`。

准备脚本、字节核对和预算见 `datasets/sir/research/2026-10-29/blog-v8-preparation/`。预算以同题 v7 的真实输入 7319／6608 tokens 为基线，加 v8 新增／替换行的 2548 字符（估算每字符一个 token，不扣被删文字），再留 10% 余量并向上取整到 500；这是偏保守的估算，不是服务端精确分词，也不承诺缓存。两题各初采 1 次，deepseek-flash：改写预计输入 **11000**／输出 **2000**，从零预计输入 **10500**／输出 **2000**，总计 **25500 tokens**。每次输出硬上限 8192，2000 只是预计用量；不自动重试，修复不在本次预算内。

本阶段未启动 Maven、应用、数据库或训练，模型调用 **0 次**，未提交或推送。旧 g7、全部 prompt／task、学生数据和用户 `.gitignore` 的本机字节保持不变，清单记录前后校验。合格教师仍 **48 条**、学生数据仍 v3。下一步必须先收到负责人 CI 通知并核对同 SHA 证据，以及取得本轮初采费用确认；之后才使用新批次调用教师，失败候选最多一轮反馈重答且费用另批。

收尾独立核验：三份完整清单覆盖 25 个新增文件，无遗漏或被 Git 忽略项；180 个受保护文件的原始哈希未变，JSON／JSONL 解码、准备脚本语法、候选绑定与 LF／空白检查通过，`git diff --check` 通过。针对归档准备脚本的 AFT 检查因 bridge busy/under load 在 150 秒后超时，未将其记为诊断通过；上述核验不代表编译或四门验收。


<a id="slm-blog-v8-initial"></a>

## 第二十二阶段：诊断 CI 验收与博客 v8 首轮初采（2026-10-29）

负责人通知上轮 CI 通过并授权继续后，按前报 **25500 tokens** 预算执行两题各一次新初采；不是继续追修 g7，也没有开始学生训练。

**先核对同 SHA CI。**[verify #202 / 40877385610](https://github.com/HoloNova/Software-IR/actions/runs/40877385610) 的 head_sha=`2e4fa74be63e694b580d991c6a54f60d7d2ce2eb`，两个 job 均 success。下载并核对 GitHub SHA256 的三份原始 ZIP：standalone-cli（artifact `12898943095`）、conformance-evidence（`12898763565`）、surefire-reports（`12898628741`）。上传 Surefire 为 **293 XML／2031 项、0 失败／错误／跳过**；新增 QueryDiagnosticMessageTest 22 项，博客契约 5 项、oracle 11 项、业务入口 1 项、候选适配器 9 项均通过。博客参考报告正式 verdict=PASSED，55 个生成文件、构建成功、readiness=200、业务 **425/0**；四轮六连接／六事务竞争以及应用退出、schema 删除、锁释放证据完整。报告的参考／DDL／seed 摘要逐项与受测 Git blob 匹配；原始 ZIP、元数据、相关完整 XML 和博客日志归档于 `datasets/sir/research/2026-10-29/blog-v8-ci202/`。原始日志行尾空白作为证据格式例外保留，不清洗。

**原收集器与原检查器。**继续使用第二十一阶段冻结的 g8 两批及 LF 快照；采样前逐项核对 486 个核心／oracle 源码与 Git blob、冻结文件和候选绑定。使用 #202 原 standalone CLI JAR（与下载 ZIP 成员逐字节一致），先分别静态检查两批既有参考，均无诊断、完整生成 55 文件，写入各自 `checked-reference.jsonl`；未把 CI 参考的 BUILD/START/BUSINESS 写入批次候选。之后调用原 `generate --model deepseek-flash --count 1 --max-tokens 8192`，temperature=0.6、thinking disabled、max_retries=0，再由原 `_check_rows`／`bind_static` 检查新教师，原始结果写 `checked-initial.jsonl`。无手改候选或反馈、无本机 Maven／真库／应用；静态 Java 限制 heap 256 MiB、metaspace 128 MiB、ActiveProcessorCount=2，进程已退出。

| 题型 | 教师短 ID | 输入 tokens | 缓存命中 | 输出 tokens | 静态结果 | 构建／启动／业务 |
| --- | --- | ---: | ---: | ---: | --- | --- |
| 改写 | `906a864f71bf` | 7777 | 256 | 1500 | GENERATION PASSED；无诊断、57 文件 | NOT_RUN |
| 从零 | `718c0d88dafa` | 7066 | 4096 | 1546 | SEMANTIC FAILED；3 条 ERROR | NOT_RUN |

共 **2 次 API、2 份响应**，输入 **14843**（缓存 **4352**）、输出 **3046**、合计 **17889 tokens**，均以 finish_reason=stop 结束。总量低于预计，但预计值不是硬计费上限。本次无网络失败、重试或修复。

**实际错误与比较边界。**从零答案在全局声明了 Forbidden，却未在 CreatePost 中写 `fails Forbidden`，其 load 因而报 SIR-FLOW-003；FeedInput.keyword 仍为 Optional<Text>，即使使用 `is none or ...`，两个 containsLiteral 的右值也不会变成非可选，产生两条 SIR-TYPE-001。这些规则已在 v8 写明。相较 g7 首轮，此份新答案已经使用约束逗号、Ref 投影并补齐输入声明，改写候选也通过全部静态阶段；但每题只有一个新样本，不能据此证明整体通过率或归因于单一 Prompt 修改。PowerShell 展示的中文乱码仅发生在终端渲染；原始 CLI UTF-8、checked 与拟反馈内容的中文已核对完整，未改写诊断。

**资格和后续。**合格教师仍 **48 条**、博客合格仍 **0 条**，学生 v3 不变。静态通过的 `906a864f71bf` 已准备 BlogBusinessConformanceIT 派发参数，但批次尚未提交，故业务门 dispatch_count=0；须负责人明确确认提交／推送后再按远端精确字节派发，不能拿参考 425/0 代替它。唯一失败候选拟用原 `repair-general-v1.md` 反馈重答一轮，预算根据历史 13 次反馈的实际新增 token／字符中位数估算，预计输入 **11000**、输出 **2000**，合计 **13000 tokens**，输出硬上限 8192，不自动重试，**预算待确认、尚未调用**。

原始请求／响应／collector、用量、静态输出、原始证据及下一步参数见 `datasets/sir/research/2026-10-29/blog-v8-initial/`；父批次／task／manifest／system 不改，准备阶段的封存记录也不追改。收尾验证原始响应解码等于候选源码，checked 等于原 bind_static 对原 CLI 输出的绑定、请求等于原 request_payload，180 个受保护旧文件字节未变。当前未提交、推送、派发 CI 或追加模型调用。


<a id="slm-blog-v8-repair"></a>

## 第二十三阶段：博客 v8 从零候选的一轮反馈重答（2026-10-30）

**前批归档与运行门边界。**负责人允许 `.gitignore` 一起提交后，105 项归档以 **`1aa6f5fe01d6f23315b3817b7ec43383419b6f57`** 提交并普通推送，其中 `.gitignore` 只新增 `.pi/`。逐 Blob 核对远端 105 个归档与 486 个核心／oracle 输入，再仅派发改写候选 `906a864f71bf` 的 [slm-business-gate #65](https://github.com/HoloNova/Software-IR/actions/runs/40981352192)；同 SHA 的 [verify #203](https://github.com/HoloNova/Software-IR/actions/runs/40981119034) 因 push 自动触发。派发后仅一次确认两者已启动，尚无验收结论；本阶段按约定等负责人通知，**CI 状态查询 0 次**，不能将 #202 参考成功借给教师。

**仅一次获批请求。**负责人明确批准上一轮提出的 **13000 tokens** 预算后，对 `blog-scratch-g8` 的父候选 `718c0d88dafa` 调用原 `repair` 一次；沿用 v8、原 `repair-general-v1.md`、完整父答案、三条真实静态诊断及冻结 DDL，不改变题目、接口或判定。输出上限 8192，temperature=0.6、thinking disabled、自动重试 0；没有以“确认”扩为其他候选或第二轮修复。继续在与 Git blob 一致的 `2e4fa74b` LF 快照中读取原实现，并在调用前核对当前 `1aa6f5fe` 的 486 个核心／oracle 源码仍与批次冻结一致。

| 父候选 | 新子候选 | 实际输入 | 缓存命中 | 实际输出 | 合计 | 静态结果 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| `718c0d88dafa` | `215ebacdb9b3` | 10080 | 8448 | 1551 | **11631** | SEMANTIC FAILED，2 条 SIR-TYPE-001 |

API 返回 finish_reason=stop，原始响应和候选源码均留存。使用 **CI #202 原 standalone CLI** 在本机仅做完整静态检查，结果写新 `checked-r1.jsonl`，首轮 `checked-initial.jsonl` 不覆盖。消息、SourceSpan、诊断顺序均来自原 CLI；UTF-8 原文及实际发出的反馈无乱码。运行门 BUILD／START／BUSINESS 全部 **NOT_RUN**，没有派发这个失败子候选。Java 检查进程限制 heap 256 MiB、metaspace 128 MiB、ActiveProcessorCount=2，运行后退出；未启动 Maven、生成应用或数据库。

**实际改动只是一行。**逐字节父子差异显示，教师仅在 CreatePost 增加 `fails Forbidden;`，解决 SIR-FLOW-003；FeedInput.keyword 和两个 containsLiteral 表达式原样保留，所以 Optional 右值的两条 SIR-TYPE-001 仍然存在。原请求确实包含全部三条诊断与新版中文解释，不是漏传了后两条，也不是终端渲染乱码导致反馈损坏。已知规则仍未被遵守；不为这份答案手改类型、放宽语义或追加付费追修。

**归档及资格。**原始请求／响应在批次 `samples/`，调用记录、stdout／stderr、父子零上下文补丁、来源／用量及核对脚本在 `datasets/sir/research/2026-10-30/blog-v8-repair/`。核对新请求精确等于原 request_payload、消息前缀保留父对话、后两条消息为完整父答案与原反馈，响应解码精确等于子源码；两个账本只追加一条，重新封存汇总清单前核对 27 个既有批次文件及 180 个受保护旧文件原字节不变；随后仅更新数据汇总 `MANIFEST.sha256` 的文件索引，冻结 `manifest.json` 不改，改写批次与学生数据不改。候选和原始输出不清洗；本机原始 stdout 的 CRLF 按证据格式例外保留。

v8 两次初采加这次反馈共 **3 次 API**，输入 **24923**（缓存 **12800**）、输出 **4597**，合计 **29520 tokens**。新增合格 **0**，总数仍 **48**，博客合格 **0**，学生 v3 不变；失败轨迹仅作研究记录，不作为当前 SFT 的正确答案，也没有训练。此父候选已用完一轮反馈预算，后续先等改写候选的真实 CI 结果；本批修复归档尚未提交／推送，不自动开启新采样或继续追修。


<a id="slm-blog-v8-runtime-repair"></a>

## 第二十四阶段：博客改写候选的业务失败归档与单轮运行反馈（2026-10-30）

**正式区分两个 CI。**负责人通知后，一次性核对 `1aa6f5fe01d6f23315b3817b7ec43383419b6f57` 的 [slm-business-gate #65 / 40981352192](https://github.com/HoloNova/Software-IR/actions/runs/40981352192) 为 failure、[verify #203 / 40981119034](https://github.com/HoloNova/Software-IR/actions/runs/40981119034) 为 success。#203 是工具链／固定参考验收，不赋予新教师资格；本阶段只核对其 run 元数据，不另报它的测试数量。#65 的 `slm-runtime.json` 正式结果是 **STATIC／BUILD／START PASSED，BUSINESS FAILED**，生成 57 文件，候选生成摘要与初采静态检查精确一致，真实 HTTP readiness=200。业务 **401 通过／25 失败**，不是构建、环境或字节绑定失败。

**两个根因，不是 25 个独立缺陷。**父候选 `906a864f71bf` 的 PostsByTag 使用 `any(PostTag, post == item and tag == tag)`；ResolvePass 的既有规则先绑定 any 实体字段，两边 tag 均为 PostTag.tag，外层加载的同名变量没有参与筛选。按标签分页、空标签、发布后筛选等共 **14 条断言失败**。AttachTag 则先 exists 再插入且 `persist link` 没有 else；六个并发请求实际为 `[500,500,500,201,500,500]`，五次日志均为 `Duplicate entry '111-22' for key 'post_tag.blog_post_tag'`，没有转成合同要求的 409／TagAttached，产生 **11 条断言失败**。v8 已说明 any 字段作用域、atomic 不自动锁范围及 persist else 的唯一冲突用途；同名遮蔽可另提通用易用性设计，但本轮不改 Prompt、语法、诊断或判卷。

**失败证据照样归档。**复用调查时已下载的两份原始 ZIP，不重复网络请求；逐 ZIP 校验 GitHub SHA256，逐成员核对解包字节，逐附件核对 runtime 哈希与同候选的 task/oracle/toolchain/source/generated_digest。正式保存于 `datasets/sir/pilots/blog-change-g8/runtime/content-blog-change-v1-teacher-906a864f71bf/`，包括原报告、四日志、完整 JUnit、原始 ZIP、GitHub 元数据和 gate-run.json；业务入口 XML 为 1 test／1 failure／0 error／0 skip，与 FAILED 一致。ZIP artifact `12944723337`（evidence）摘要 `204e191bf41cf4fffd57031b93b210e13bc073cf52733e03d495c553d5f496dd`，`12945101637`（surefire）摘要 `e710bacfd1d3697127c9134d4c55da44e4604e9b02d0263d5fdad71742057fe6`。原始日志不清洗；含 CI 环境口令行的 Actions 原始聚合 failed.log 仅留本机调查目录，不放进数据归档。没有导出这个失败父候选为合格答案。

**一次获批运行反馈。**负责人确认 **26000 tokens** 预算及统一归档提交方案后，对父候选仅调用原 `repair --runtime-evidence` 一次；system 仍 v8、反馈前缀仍 `repair-runtime-v1.md`，只附原报告的失败断言和 HTTP 记录、完整父答案及冻结 DDL，没有加入人工改法或参考答案。原选择器验证血缘，`runtime_feedback` 绑定原 run_id 与原 evidence_sha256；在精确 LF 快照中复核 486 项核心／oracle 与当前受测 Git blob 一致。自动重试 0、输出上限 8192、temperature=0.6、thinking disabled；没有调用其他候选或扩采。

| 父候选 | 新子候选 | 输入 tokens | 缓存命中 | 输出 tokens | 合计 | 静态结果 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| `906a864f71bf` | `af2c42118880` | 22799 | 9088 | 1458 | **24257** | PARSE FAILED，3 条 SIR-PARSE-001 |

原始响应 finish_reason=stop，源码确实变化，非截断或原样返回。教师在 AttachTag 添加 `persist link else TagAttached`，但在 PostsByTag 将 any 改成了 `... and exists PostTag where item.post == item and item.tag == tag`。exists 是独立工作流步骤，不是可嵌入 find 条件的这一种表达式，原 CI #202 检查器因此在 PARSE 阶段拒绝。这里只能说源码增加了冲突处理，**不能宣称并发问题已验收修复**；新候选 BUILD／START／BUSINESS 全 **NOT_RUN**，不送业务门、不再追修。

**收口。**本次原请求精确匹配原 runtime repair 的四条消息，保留父对话、父答案与真实失败反馈；响应解码等于候选源码，原 CLI 输出经 bind_static 得到同一 checked 记录，UTF-8 中文完整。旧候选／请求／原始检查不改，两批账本各自仅追加已获批的一条修复记录；冻结 manifest.json／task／system 不变，汇总 MANIFEST.sha256 按新增证据重封，180 个受保护旧文件及学生数据保持原字节。完整审批、预算依据、原始 stdout／stderr、父子零上下文补丁、收集器／检查器绑定与核对脚本在 `datasets/sir/research/2026-10-30/blog-v8-runtime-repair/`。

v8 累计 **4 次 API**，输入 **47722**（缓存 **21888**）、输出 **6055**，合计 **53777 tokens**。新增合格 **0**，仍 **48 条**、博客合格 **0**、学生 v3 不变，未训练。从零分支已经一轮静态反馈失败，改写分支本轮运行反馈也失败，均按批准额度停止；本轮按授权与既有待提交修复记录一起归档提交，不新派业务门、不手改答案或继续消耗模型费用。除原静态检查器的有界 JVM 外，没有本机 Maven／应用／数据库或全量测试。


<a id="slm-blog-g9-recheck"></a>

## 第二十五阶段：新静态规则 CI 验收与博客 g9 重查（2026-10-30）

**4.1 同 SHA 验收完成。**负责人通知 CI 完成后，一次性核对 `47bf67c8f3d95fbb81d50b1b927c73bba1d0db21` 的 [verify #204 / 41019419975](https://github.com/HoloNova/Software-IR/actions/runs/41019419975)：gates-and-business-it 与 standalone-deployment 两个 job 均 success，上传 Surefire 为 **295 XML／2086 项，0 失败／0 错误／0 跳过**。新增 A/B 两类分别 29／26 项，原 QueryDiagnosticMessageTest 22 项及候选适配器 9 项全通过；博客裁剪参考四门通过、55 文件、业务 **425/0**，四轮各六连接／六事务等待图、应用退出、schema 删除和锁释放均有原始证明。不是把本机定向或工作流绿色替代参考的业务报告。

**参考证据归档。**`datasets/sir/research/2026-10-30/blog-g9-ci204/` 保存精确 run/jobs/artifacts 元数据、三份原始 ZIP、博客原报告及四份原日志、七类 JUnit XML和摘要。三份 ZIP 的 GitHub digest 均匹配：conformance-evidence=`12960962949`、surefire-reports=`12960414439`、standalone-cli=`12960753406`；参考、DDL、seed 摘要逐项匹配受测 Git blob。复用已提交的 CI #202 归档校验逻辑，仅替换 run/commit/目标目录和选择的测试类，不新增另一份一次性归档脚本；recipe 记录来源与参数。解包检查拒绝逃逸路径、链接及 CI 口令标记，不归档 Actions 原聚合日志。原始 ZIP、日志和 XML 字节不清洗，日志行尾空白保留为证据格式例外。该证据只证明既有参考，不赋予教师资格。

**4.2 重新导入，不搬运旧门。**从受测 `47bf67c8` 用命令级 `core.autocrlf=false`／`core.eol=lf` 导出独立快照，**1444 个文件**逐项匹配 Git blob；再用该快照的原 `import` 把 `blog-change-g8`、`blog-scratch-g8` 导入新 `blog-change-g9`、`blog-scratch-g9`。每批 1 份当前参考 + 2 份原教师，四门最初全部 NOT_RUN。4 份教师的源码、messages、原始 request/response、模型/用量及 parent_id 保留；旧运行门、静态结果和生成摘要均不移植。原收集器快照仍由既有 import 原样核验与复制，本轮不提前改收集器协议或删除冻结副本，归档命令整合仍属于 4.4。

每批 **448 项核心输入／38 项 oracle／239 项实验输入**均与受测 Git blob 一致。新工具链摘要 `bb77b8b40efd6ceb38e8688dbb58243754aa30d3e9db815fe62ff7ac6b2f0eab`；oracle 仍为 `b19875a18b071c0921ad34072f9de4c8c10b668db69fb5415eb3b401d6a7c843`。task、base、reference、v8 system 的冻结字节与 g8 完全相同，题目仍保留 #200 的原 reference_evidence，未修改题目哈希迁就新 CI。

**实际重查。**复用原 `_check_rows`／`bind_static`，以 #204 原 standalone CLI 做完整 GENERATION 上限检查；JAR 摘要为 `d23339bf47b6f4ff574e1dcd82bc33f7c7127fae5882b0a1daa89b5fea648a9a`，逐字节对照原 artifact。没有本机重新构建、另写语义、手改候选或放宽检查。两份既有参考无诊断、均生成 55 文件；教师结果如下：

| 候选短 ID | 来源与旧结果 | 新结果 | 完整诊断 |
| --- | --- | --- | --- |
| `906a864f71bf` | 改写初采；旧静态通过，#65 业务 401/25 | SEMANTIC FAILED，3 ERROR | PostTag 写入漏 else（B，L184）；PostTag.tag 自比较（A，L198）；Like 写入漏 else（B，L222） |
| `af2c42118880` | 改写运行反馈；旧 PARSE FAILED | PARSE FAILED，3 ERROR | 原内联 exists 引出的 PostTag／where／右花括号三条解析错误，尚未到 A/B 检查阶段 |
| `718c0d88dafa` | 从零初采；旧 SEMANTIC FAILED，3 ERROR | SEMANTIC FAILED，6 ERROR | 原缺 `fails Forbidden` 1 条 + Optional containsLiteral 2 条；新增 PostTag 写入漏 else、tag 自比较、Like 写入漏 else 各 1 条 |
| `215ebacdb9b3` | 从零静态反馈；旧 SEMANTIC FAILED，2 ERROR | SEMANTIC FAILED，5 ERROR | 原 Optional containsLiteral 2 条；新增 A/B 同上三条 |

**不是新增了运行失败事实。**Like 的 B 命中属于负责人已确认的“即使持父行锁也要声明冲突错误”限制；#65 的点赞并发原本通过，不能把本轮静态错误改写成 #65 新增业务失败。A/B 新消息的 ERROR、完整位置、顺序和原消息均保留在 `checked.jsonl`／`checks/`；没有仅摘新规则而隐去原类型/能力错误。两个新批次 BUILD／START／BUSINESS 仍全部 **NOT_RUN**，没有候选可派发业务门，也没有导出新 SFT。

**完整性与资源。**重查前记录 3717 个既有数据／prompt／task 文件摘要，导入与重查后逐项一致；收尾仅允许本次说明入口及其总清单更新，旧 g7/g8、学生文件、冻结题目和历史资格不改。重查子进程剥离模型密钥及 Java 注入选项；两次 Java 串行，heap 256 MiB、metaspace 128 MiB、ActiveProcessorCount=2，均已退出。新批次来源、输入闭包、checker 和原输出哈希见 `research/2026-10-30/blog-g9-recheck/checkout-verification.json`，前后结果见 `summary.json`。本轮模型调用 **0 次**，未运行 Maven、应用、数据库或训练；合格教师仍 **48 条**、博客 **0 条**、学生 v3 不变。

**下一步仅报预算，未发送请求。**按交接所列 `906a864f71bf`、`718c0d88dafa`、`215ebacdb9b3` 三份各做一次新的静态反馈，沿用 v8、`repair-general-v1` 和新检查的全部诊断。预计输入分别 **11500／11500／14500**，每份输出预计 **2000**，合计输入 **37500**、输出 **6000**、总计 **43500 tokens**。方法为各父候选的真实输入用量，加原答案及新增反馈字符数乘已有校准中位比率 0.2321409948899781，再留 10% 并向上取整到 500；不是服务端精确分词，不承诺缓存，每次输出上限 8192、不自动重试。215 的上下文已含上一轮反馈，故预计更长。

这是针对新工具链诊断的**另批新预算**，不是清除历史修复次数或自动续费。三份中有从零父子关系，仍只对应两道题，不作为三个独立任务或通过率统计。原解析退化子候选不在本轮付费提案中；费用确认前不调用。计划及来源哈希在 `repair-plan.json`。4.3 Parser 改进和 4.4 归档命令尚未实施，仍须各自设计确认。


<a id="slm-blog-g9-feedback"></a>

## 第二十六阶段：博客 g9 新静态反馈一轮（2026-10-30）

负责人确认第二十五阶段提出的 **43500 tokens／3 次调用**预算后，按原计划分别对 `906a864f71bf`、`718c0d88dafa`、`215ebacdb9b3` 各做一次新静态反馈。使用原 `repair --feedback repair-general-v1.md`、冻结 v8、父对话／父源码、新检查的全部真实诊断及原 DDL；没有加入手写修复指令或参考答案。旧修复次数、父子关系与旧失败检查不覆盖，未修复原解析退化子候选，也不自动重试或续修。

调用前逐项核对 486 个 core/oracle 文件与精确 Git blob 和 LF 快照一致，使用 #204 原 JAR（`d23339bf47b6f4ff574e1dcd82bc33f7c7127fae5882b0a1daa89b5fea648a9a`）。每次先保存独占审批／请求标记，再由原 CLI 发请求；SDK 的 max_retries=0，输出上限 8192，三次均 finish_reason=stop。模型密钥仅从环境读取，不进入请求文件、参数、日志或数据；Java 注入选项剥离，未修改编译器／prompt／题目／判卷。

| 父候选 → 新子候选（短 ID） | 输入 | 其中缓存命中 | 输出 | 合计 | 静态结果 |
| --- | ---: | ---: | ---: | ---: | --- |
| `906a864f71bf → bba0b5b48fc4` | 10809 | 9088 | 1510 | 12319 | GENERATION PASSED，无诊断，57 文件 |
| `718c0d88dafa → 275138cf0c55` | 10678 | 9088 | 1559 | 12237 | SEMANTIC FAILED，3 ERROR |
| `215ebacdb9b3 → 216042934560` | 13533 | 11392 | 1559 | 15092 | SEMANTIC FAILED，3 ERROR |
| **总计** | **35020** | **29568** | **4628** | **39648** | **1/3 静态通过，0 新合格** |

**实际改动。**改写子候选补上 `persist link else TagAttached`、`persist made else AlreadyLiked`，把标签加载变量改名 `chosenTag` 并在 any 内写 `tag == chosenTag`，共四处局部改动，全部来自教师原始响应。版本化生成摘要为 `56aef98b45e9b72623351da01454271e8ef0bfd7f21f125c2851ff6ec0d540d0`；此前的 A/B 已在该答案上消除，但静态通过不证明业务正确。

两份从零子候选都补了两处冲突处理，初采分支另外补了 `fails Forbidden`；仍保留 Optional containsLiteral 两条及 `tag == tag` 自比较一条。两份新源码**逐字节相同**，摘要均为 `1a0bee361a35cfd7e9a6669ef06d22bb8428e46df859640a7721031712fadb2f`，不是两次原样交回父源码，也不是两个独立任务。各自原始请求／响应、用量和父子记录仍保留，不因重复输出再调用模型；两条失败分支按本轮额度停止。

**收口与送门。**请求精确等于原 repair 组装的父 messages + 父完整源码 + 通用前缀／实际诊断／原 DDL；响应解码等于落盘源码，原 stdout 经 bind_static 精确得到各 `checked-r1-<父短ID>.jsonl`。两个原 candidates 账本仅追加 1／2 条，两个 attempts 文件是本轮新增，不伪装成已有账本；原冻结 manifest、task、system、reference、旧 checked 和全部旧样本保持字节不变。322 个受保护旧文件（排除两批允许追加的 candidates 及汇总 MANIFEST）逐项一致；学生 v3、48 条资格不改。原始 CLI stdout 的 Windows 行尾保持原字节，格式例外只用于原始输出，不清洗迁就 diff 检查。

审批、逐次调用记录、三份零上下文差异和用量汇总见 `datasets/sir/research/2026-10-30/blog-g9-repair/`。仅 `bba0b5b48fc4` 进入 `business-gate-plan.json`，按既有授权提交／推送并核对远端字节后派发一次 BlogBusinessConformanceIT；不派两份静态失败者，结果等负责人通知，不轮询。当前三份新候选 BUILD／START／BUSINESS 均 **NOT_RUN**，尚无新训练资格，不能借 #204 的参考结果填候选门。

本阶段没有本机 Maven、数据库、应用、全量测试或训练，只运行有界静态 JVM，未手改教师答案。实际总量低于估算，三次额度已结束，不追加费用。博客 v8 手册跨 g8/g9 累计 **7 次 API／93425 tokens**；这次有一份通过不构成总体通过率提升证明。合格总数仍 **48**、博客合格 **0**，待该新候选自己的四门结果。


<a id="slm-blog-g9-qualified"></a>

## 第二十七阶段：博客首条合格答案、历史附件补全与学生 v4（2026-10-30）

**候选自己的四门已通过。**负责人通知结果后核对 `c6a993aaafa64e7cde11f16c8196c6773de25542` 的 [slm-business-gate #66 / 41031591392](https://github.com/HoloNova/Software-IR/actions/runs/41031591392)：候选为 `content-blog-change-v1-teacher-bba0b5b48fc4`，四门全 PASSED，业务 **426/0**，不是复用 #204 参考的 425/0。原报告固定第二行 verdict=PASSED，四轮各六个运行时连接／事务的真实锁等待均有记录，应用退出、schema 删除和锁释放均通过。真实环境为 Java 21.0.13、MySQL 8.4.13；生成 57 文件，版本化摘要 `56aef98b45e9b72623351da01454271e8ef0bfd7f21f125c2851ff6ec0d540d0` 与原静态结果一致。

**复用既有归档器。**从精确 LF 快照执行原 `docs/tmp/archive_gate_runs.py`，仅为该进程补上 Windows Git 自带 unzip/grep 的 PATH；归档／绑定／附件校验和 export 全部走原实现，不新建业务门归档脚本。对 ZIP 另做路径逃逸、大小写重复、链接及 CI 口令标记预检。两个原始 ZIP 均匹配 GitHub digest：证据 artifact=`12965663274`，SHA256=`35b8c3d8a162b99f7a64cd71480012d9df4b37f86c9d5b1c58fa5fe5ab78ed17`；Surefire artifact=`12966497381`，SHA256=`93c372d7ac593e23abf310caf5e24f55f46899fe7baceed402024b819dd6d920`。runtime 绑定、原始报告／四日志哈希和上传 JUnit 均已核对；原始 ZIP／API 元数据及操作来源在 `datasets/sir/research/2026-10-30/blog-g9-gate66/`。原始日志不清洗，本机生成 gate-run.json 单独保存为 LF。

原 `export` 已向 `blog-change-g9/train-teacher.jsonl` 导出 **1 条**，索引明确 all_four_gates_required=true。**累计合格教师答案 49 条（鉴权26、课程22、博客1）**。从零两条静态失败分支仍封存；本阶段模型调用 **0 次**，上一阶段 39648 tokens 不变，也未再派发 CI 或重跑已通过门。

**来源审计发现并恢复历史漏件。**初次 v4 审计因旧 bearer g1 的 maven.stdout.log 不存在而停止，没有跳过错误或宣称成功。逐项核查确认 **44 个旧合格批次各缺4份日志，共176份**，明确路径命中 `.gitignore:14:*.log`，Git 无对应 Blob。不是旧业务结果变成失败，也不否定当时已验收的资格，而是旧归档漏纳附件；此前仅用工作区／导出索引不能证明所有日志已入 Git。

按原 gate-run.json 的 artifact ID 下载44份原证据 ZIP，每份先匹配当时登记的 ZIP SHA256，并确认 ZIP 内 slm-runtime.json 与现有文件逐字节相同，再仅恢复缺失的四份日志。176份内容均匹配原 evidence 的逐项 SHA256，无 CI 口令标记；**没有覆盖旧文件、改写旧摘要或重新判卷**。恢复文件本轮显式纳入 Git，原字节／行尾空白保留；完整 ID／ZIP 摘要／路径／文件摘要在 `historical-log-recovery.json`。44份临时下载ZIP留 target，不另复制为44套归档；恢复的原始日志及原证据已足以逐项验证。

**学生 v4。**仍按跨工具链规则保留 `train-student-v3.jsonl` 的 **48条原始字节**；只在新工具链 LF 快照用原 student 命令包装新增1条，再合成 `train-student-v4.jsonl`。index 的 assembly 保存基座／增量／导出索引摘要，历史资格不重算。49条逐一核对教师导出索引、原候选源码、冻结 task/base/system、完整题目、运行绑定及全部附件，ID集合与所有非空教师导出完全一致，split均为train、同题/源码无重复；短system + 原题 + 最终合格SIR，不含教师长手册或修复过程。恢复漏件之后整套来源审计通过，旧v1/v2/v3及其索引、分词报告均未改变。

固定既有 revision、tokenizer.json 和 chat template，在CPU重新实测49条，旧48条的逐条长度与v3报告完全一致：

| 分词器 | 新博客完整序列 | v4最长完整序列 |
| --- | ---: | ---: |
| deepseek-coder-1.3b | 6581 | 9311 |
| Qwen2.5-Coder-3B | 5144 | 7102 |
| Qwen3-4B | 5144 | 7102 |

分词未截断、未下载／执行权重，也不是模型训练或能力评测。汇总、49条来源证明与 v4 文件摘要见 `student-v4-verification.json` 及 `student/train-student-v4.{index,tokens}.json`。本次仅数据／证据与说明收口，无 Maven、本机业务 IT、应用／数据库或新模型调用；下一项 4.3 Parser 统计与设计、4.4 归档工具整合仍各自等待确认，不在本轮修改生产代码。

题库覆盖按 task ID 另计：73份定义中已有 **48题**至少一份合格答案，仍有 **25题**没有（train19、development2、test4）；49条答案不等于49道独立题。该统计不改变分组、不启动test采样或学生评测。


<a id="slm-parser-diagnostics-design"></a>

## 第二十八阶段：历史 Parser 错误统计与三类提示设计（2026-10-30，待确认）

负责人确认开始交接 4.3 后，本阶段只统计、核对原解析器机制并形成[设计](../../experiments/slm/drafts/design/parser-diagnostics-design.md)，**尚未实施新 Parser 消息**。统计固定到 `33c2f4bc09e88b91631066ae14f8edd70bb19fae`，从 Git Blob 读取而非本机换行；不触碰旧批次、冻结 manifest 或资格。

**去重结果。**163 份 checked JSONL 共 394 行（参考 152、教师 242），其中 95 行教师检查含 SIR-PARSE-001，累计 214 条该诊断。去掉重复检查和跨工具链导入后，为 **48 个保留响应身份、39 种源码、53 个候选/core 组合**。逐一按首个可定位根因分类，后续恢复报错不当独立根因；214 条 stdout 副本和 56 条研究摘要副本另列载体，不重复计数。按响应身份，前三类是 **关键字名字 12、约束分隔 11、分页漏 else 8**，内联查询 5 排第四。若按源码字节，前三则为 12、6、5，分页为 4；两种口径公开，不以重复返回源码宣称独立样本或整体改进。响应 ID 数不是计费调用总数。

**基线机制。**复用已核对哈希的 verify #204 原 CLI（受测 47bf67c8，ANTLR 4.13.2），确认其 sir-parser 子树与本次统计基线一致。只增加观察 listener，保留原 DefaultSirParser、原 ParserDiagnosticListener 和默认恢复，对 39 种历史源码加 27 个机制对照的 **66 项公共 Diagnostic 完整记录逐项相等**。这是只读观察，不是新消息的测试 GREEN。关键字类中 6 份首条已有专用提示，另 6 份因 extraneous 分支较早而仍为通用消息；不能声称全部 12 份都是新增缺口。也确认“缺尺寸”恢复后可能在 AS 期待 ELSE，必须检查已完成子表达式，不能仅凭 token 猜缺词；当前规则的本次 exception 又不能当成旧损坏一律排除。

**建议实施范围。**只在现有错误回调改善三类消息：名字槽位的关键字（保留 001/003 各自错误码）、约束尾部的逗号分隔说明、已完整页码/尺寸之后的分页 else 说明。保留语法、默认恢复、数量/顺序/SourceSpan/related/fixes、missing 的零宽位置、EOF 和错误数量上限；不自动改名/补词，不改变合法输入，不混入排名第四的内联 exists 提示。明确列了上下文词、合法修饰词、缺分号、约束参数错误、缺尺寸/错误名等反例，等待负责人确认后才写新行为测试与产品代码。

**可复核材料。**`experiments/slm/drafts/parser-diagnostics/` 保存逐源码人工分类、可重算统计脚本及输入哈希、两份只读辅助类、27 项控制输入、66 项观察和来源摘要。观察器首次因误用原 CLI 不含的 Jackson 而未编译、未进入 Parser；改为复用原 CLI 的 StrictJson/JsonStringEncoder，不增加依赖；原失败 stderr 在 target 保留，不计成功。只编译观察辅助类、串行运行受限 Java 进程（heap 256 MiB、metaspace 128 MiB、ActiveProcessorCount=2），无 Maven/真库/应用运行。

本阶段模型调用 **0 次**，合格答案仍 **49 条**、学生 v4 不变、未训练。新 Parser 实施/定向 RED-GREEN/新 SHA CI 均 **NOT_RUN**；未提交或推送，也未开始交接 4.4。历史 checked 记录继续保留原消息，之后工具链若改动仍按原 import/check 流程绑定。


<a id="slm-parser-diagnostics-local"></a>

## 第二十九阶段：三类 Parser 提示的本机实施与兼容验证（2026-10-30）

负责人确认第二十八阶段设计后，保留 `ParserDiagnosticListener` 原错误码/位置分支，只增加一次包内 `ParserDiagnosticHints.refine` 调用。助手在明确的名字槽位、约束尾部和分页 AS/ELSE 边界替换文字，不消费 token、不修改解析树、语法或恢复；未命中保留原消息。project/fragment/expansion 共用入口，EOF、missing 零宽位置和 50 条上限均不改。不修改 Prompt、题目、判卷或历史候选。

**TDD。**新增 `TargetedSyntaxDiagnosticTest` 的 **67 项 RED：37 项仅因旧文案失败，另外 30 项边界通过**，0 错误/跳过；生产代码改后，新类与十类受影响旧测试合计 **11 类/135 项全通过，0 失败/错误/跳过**，测试字节未为 GREEN 改动。覆盖关键字额外 token、四类声明的约束分隔、分页 find/sum/avg、合法修饰/上下文词、恢复链、Unicode/LF/CRLF、三入口及诊断上限。135 已包含新增 67，不能再相加；未运行覆盖率，不声称 80%。

**精确新旧对照。**沿用已核对哈希的 CI #204 原 CLI，只覆盖本次 GREEN 实际编译的两个 class；公共 API/AST 共享类型，旧/新 parser internals 隔离，在同一进程直接 equals AST/Diagnostic 记录。**39 历史 +27 机制对照 +49 合格源码 +5 参考 =120 项**接受结果/AST 值/诊断数量与顺序保持一致，除消息外的所有 Diagnostic 字段逐条相等。24 项输入中的 28 条消息按事先许可名单变化，未许可变化 0；没有改写旧 checked 来形成此结论。

**合法完整生成。**另用生产 CLI check 的 GENERATION 对照 49 条合格源码及五份参考，**54/54 完整成功且完整 JSON 结果相等，1633 个生成文件的数量与版本化摘要一致**。这只是静态回归，不重写原资格、不替代四门。

实际仍为 Windows/Java 21.0.11/Maven 3.9.14，18 CPU、24093 MiB、开跑前可用 3860 MiB；1444 个基线文件在独立 LF 快照逐项核对 Git Blob。Maven heap 384 MiB/metaspace 192 MiB、测试 fork及辅助 JVM heap 256 MiB/metaspace 128 MiB，全部 ActiveProcessorCount=2，串行无 `-T`；未跑全量、数据库或应用。两次 AFT 检查超时，fresh 诊断缺失，不冒称通过。命令、原始本机日志/XML、受测哈希、对照来源和已知缺口详见[实施记录](../../experiments/slm/drafts/design/parser-diagnostics-design.md#local-verification)及 `target/parser-diagnostics-implementation/`；它们不是同 SHA CI 证据。

本轮模型调用 **0 次**，合格仍 **49 条**、学生 v4、未训练。语言参考未改，不额外运行其专用测试；新 SHA CI/真实业务 **NOT_RUN**。尚未提交或推送，交接 4.4 尚未开始。


<a id="slm-archive-gate-local"></a>

## 第三十阶段：统一归档工具与采集器来源（2026-10-31，本机完成、待新源码CI）

负责人确认4.4设计并通知上轮CI通过后，单次核对`2282ad2e`的[verify #205](https://github.com/HoloNova/Software-IR/actions/runs/41061629787)：受测SHA正确、两个job均success。仅保存元数据，不虚报未下载的XML数量；旧CI不代替本次Python改动的验收。

实现`data.py archive-gate --bundle BUNDLE --checked CHECKED [--dry-run] RUN...`，显式批次/轮次，按受测Git blob、原ZIP摘要、候选/生成绑定、附件、报告及JUnit核验，原字节归档、精确重复不覆盖、清单中断可续完。不借参考答案/其他批次成功，不把题目test_revision当Git SHA；归档不隐式export，不改原四门资格规则。新采集/修复在写请求及付费调用前冻结已提交的采集器Git引用，保留collector_sha256与完整首方源依赖哈希；新导入不再复制程序，旧副本原样保留并验证。不新增Python依赖，CLI/测试用法见实验README，细节见[设计与实施记录](../../experiments/slm/drafts/design/archive-gate-design.md#local-verification)。

本机离线TDD已完成，最终与CI相同入口执行 **139项，零失败/错误/跳过**；标准库trace统计九个文件可执行行总覆盖 **86.66%**，各自均超过80%，不冒充分支覆盖。真实旧材料仅dry-run：#65仍为 **401/25失败**、#66为 **426/0通过**；3963个Git跟踪数据文件及暂存区前后未变。这不是新业务验收或新资格。测试只在临时Git仓库/ZIP执行，外部GH/付费API被替换，指纹/附件/收录检查走真实实现。

verify已接入轻量Python套件并上传`slm-python-contracts`，等待提交后的同SHA结果；本轮 **CI NOT_RUN**，没有本机Maven、应用/数据库、模型调用或训练。AFT诊断超时无fresh快照、独立类型检查未运行，未声称全量检查全绿。合格仍 **49条**、学生 **v4**。尚未提交推送，也未执行4.5临时目录清理。

<a id="slm-v9-g10"></a>

## 第三十一阶段：v9 对 19 道无合格题的采样、修复与业务门（2026-10-31）

`e6f3b1d`（containsLiteral 接受可选文本）的 [verify #207](https://github.com/HoloNova/Software-IR/actions/runs/41159460142) 通过。负责人确认 v9（`e5a7a5a`）后，对 train 集 19 道尚无合格答案的题各新建 `*-g10` 批次，用 v9 各采一次；静态失败的 15 份各按 `repair-general-v1.md` 修一轮，不自动重试。检查器为本机按当时源码编译的 kcg-cli。

| 轮次 | 次数 | 输入（缓存命中） | 输出 | 合计 | 静态通过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 初采 | 19 | 125,529（78,592） | 33,507 | 159,036 | 4 |
| 修复 | 15 | 153,141（133,760） | 31,345 | 184,486 | 7 |
| **合计** | 34 | 278,670（212,352） | 64,852 | **343,522** | **11** |

初采失败中，选课 7 份有 6 份把 `target` 用作名字，新 Parser 提示（`2282ad2`）准确指出它是关键字，修复后多数改对。仍失败的 8 份按一轮额度封存：选课从零写 4 份与改写 p2、CRUD 从零写 p1（给 version 写 default）、token-desk 从零写 p2、write-slice 从零写 p1（把 expectedVersion 当实体字段）。

11 份静态通过者提交为 `fd37af7` 后在同一 SHA 派发业务门，用 `archive-gate` 逐个核验归档：

| 批次 | 业务门 | 业务检查 |
| --- | --- | --- |
| `bearer-credential-scratch-p1-g10` | [#67](https://github.com/HoloNova/Software-IR/actions/runs/41160572070) | 36/0 |
| `bearer-credential-scratch-p3-g10` | [#68](https://github.com/HoloNova/Software-IR/actions/runs/41160574073) | 36/0 |
| `blog-scratch-g10` | [#69](https://github.com/HoloNova/Software-IR/actions/runs/41160576074) | 426/0 |
| `course-selection-change-g10` | [#70](https://github.com/HoloNova/Software-IR/actions/runs/41160577979) | 152/0 |
| `course-selection-change-p3-g10` | [#71](https://github.com/HoloNova/Software-IR/actions/runs/41160580129) | 152/0 |
| `crud-access-change-p2-g10` | [#72](https://github.com/HoloNova/Software-IR/actions/runs/41160582049) | 44/4 |
| `crud-access-change-p3-g10` | [#73](https://github.com/HoloNova/Software-IR/actions/runs/41160584205) | 44/4 |
| `dual-token-g10` | [#74](https://github.com/HoloNova/Software-IR/actions/runs/41160586680) | 35/0 |
| `query-slice-scratch-p3-g10` | [#75](https://github.com/HoloNova/Software-IR/actions/runs/41160589625) | 41/0 |
| `relation-slice-scratch-p2-g10` | [#76](https://github.com/HoloNova/Software-IR/actions/runs/41160591769) | 41/0 |
| `token-desk-scratch-p1-g10` | [#77](https://github.com/HoloNova/Software-IR/actions/runs/41160594424) | START FAILED |

**8 份四门通过并导出**，合格教师答案 **49 → 57 条**（鉴权 29、课程 26、博客 2）。博客从零写首次合格，使用的正是可选关键字写法 `input.keyword is none or ... containsLiteral input.keyword`，这也是 `e6f3b1d` 在真实 MySQL 上的第一份证据；选课改写原题与 p3 首次合格。失败三份：CRUD 改写 p2/p3 同样的 4 项（角色绕过归属、被拒修改仍写库、本人修改返回 409），token-desk 从零写 p1 启动报 `KCG-ACTOR-STARTUP-001`（给合同不要求登录的接口加了认证）。失败证据照样归档，不再追修。

学生数据 **v5**：v4 的 49 条原字节保留，追加 8 条，共 57 条；固定 revision 的三个分词器重测，保留行与 v4 记录逐条相同，最长完整序列 **9,340／7,141 tokens**（v4 为 9,311／7,102）。73 道题中 56 道已有合格答案，**17 道仍无**（train 11、development 2、test 4）。未训练。

<a id="slm-maintenance-staffing-frozen"></a>
## 第三十二阶段：报修/排班参考验收与十六题冻结（2026-10-31）

负责人要求在参考CI通过后继续下一步。本轮只新增两family的正式task定义、原题等义变体与仅参考冻结批次，归档已执行CI证据并同步状态；不再改Java/工具链、业务oracle、SIR、DDL、seed或原需求，不碰fitness/events，不调用模型，也不提交、推送或触发新CI。

参考提交 `5fe4c8339a927fb526c004c276beb4c1c8892996` 的 [verify 41194982453](https://github.com/HoloNova/Software-IR/actions/runs/41194982453) 完整通过：maintenance 599/0（55生成文件，X01–X03屏障6/6/2），staffing 870/0（56文件，屏障6/3/6）；两项本次模块JUnit各1/0/0/0。冻结全量门、独立验证、CLI和独立部署均success，不混算Surefire ZIP里datasets的历史报告。

原ZIP/元数据、报告/原日志、JUnit、六份SIR/DDL/seed Git blob绑定与artifact SHA256存于[参考证据目录](../../datasets/sir/research/2026-10-31/bounded-reference-ci41194982453/README.md)。39/40项oracle依赖分别独立编译通过，禁用隐式源码和test-classes；仅证明闭包完整，不把这次编译冒称重跑业务。旧d31af6af失败及后续59项本机回归在两题implementation §5/§6保留，最新参考状态为§7；PATCH合同的一处检查顺序旧注记按已验收行为纠正，不放宽判卷或改题面。

两family各8题：change/scratch原题2份，另有各3份p1/p2/p3等义表述。所有原条款恰出现一次，ASCII标识符及数字不变；逐条映射在 `requirements-equivalence.json`，词组替换与独立条款重排只改善表述，不声称机械校验能证明自然语言等义。固定合同不包含状态、资格、种子或评分探针，依旧附原DDL。

原prepare使用已提交v10手册与精确LF源码快照；450份工具链输入、39/40项oracle原字节均匹配上述受测Git blob。16个 `datasets/sir/pilots/{maintenance-work-order,staffing-shift-roster}-{change,scratch}[-p1|-p2|-p3]-g13/` 各只有1份existing_reference，全部用该CI的原standalone CLI静态生成通过、无诊断；报修55文件、排班56文件。两份base额外仅作静态生成47/50文件，base HTTP负例NOT_RUN。各批运行门保持NOT_RUN，不继承参考业务资格。

本轮工作区共93题（train87/development2/test4；原已提交77题加本轮16题）；合格教师仍57条、覆盖56题，学生v5原字节不变，未训练。这里包含此前另一端已创建的4份fitness/events定义，但本轮不修改其文件或资格。新增16题及冻结证据尚未提交，不能以5fe4c833冒称这些新定义已进入该次CI。

模型调用0、新教师候选0、新四门合格答案0、导出0。建议后续先对四道原题各初采1次deepseek-flash，每次最大8192输出tokens、无自动重试；按完整prompt的138267 UTF-8字节加最大输出32768及余量，提出18万tokens的保守预算，仅是估算，不是分词器实测或实际用量，不包含失败修复。`sampling-plan.json` 记录四份输入规模；冻结核对时预算/Git操作尚未授权；负责人随后已确认先提交/普通推送，再执行上述四次初采，不含失败修复，不自动采12份变体。

<a id="slm-maintenance-staffing-initial"></a>
## 第三十三阶段：g13 四道原题初采与静态检查（2026-10-31）

负责人确认先提交/推送，再按18万tokens保守预算对四道原题各初采1次，零自动重试，不包含失败修复。`fdbb83a5d3a38a3bce9c3504f1a7aa54e5a0c4a1` 已普通推送main，296文件只含task/data/docs及原始附件，所有11份被忽略的原日志/ZIP均显式纳入并按暂存Git blob核对；生产代码、工具链、oracle和学生文件不变。提交标记skip ci，不重跑同源码参考全量门。原始CRLF元数据按字节保留，空白检查使用一次性cr-at-eol配置，不改全局配置或清洗原件。

付费前在独立Git身份的LF导出目录验证8份collector首方源码与fdbb83a5的Git blob完全一致，collector_sha256为 `66dfb137a02619b145e67be0e9392aae4f42c06b6add6de7c4090a9e18140216`。复用原collector与原CI的standalone CLI，不改程序、题面、合同、SIR/base或冻结manifest。

4次实际模型均deepseek-flash，thinking禁用，finish_reason全stop；无网络失败、重试或截断。**input 35198、output 7015、total 42213 tokens**；输入缓存命中16896、未命中18302。不把保守18万估算当实际花费，不将137787未用额度自动分配修复或变体。

| 原题 | 新教师后缀 | 总tokens | 静态 |
|---|---|---:|---|
| maintenance-work-order-change-v1 | 66485d05ad4a | 11427 | PARSE失败；156–159行擅写三元表达式，4×SIR-LEX-001 |
| maintenance-work-order-scratch-v1 | 1b3afbe59ca1 | 10676 | PARSE失败；243行动态days时间写法，SIR-PARSE-001 |
| staffing-shift-roster-change-v1 | 23ed7f759fcd | 10440 | GENERATION通过，56文件，无诊断 |
| staffing-shift-roster-scratch-v1 | 2027a6702825 | 9670 | GENERATION通过，57文件，无诊断 |

[采样原件及摘要](../../datasets/sir/research/2026-10-31/bounded-g13-initial/README.md)含原请求/响应/usage/collector引用、四次调用边界和本次原始CLI输出。四批 `checked-initial.jsonl` 保留原参考行并绑定本次教师静态结果，不覆盖此前checked.jsonl或失败候选。控制台可能乱码，原UTF-8诊断正确，不转写原件。

新增候选4，静态2通过/2失败；全部运行门NOT_RUN，未修复、未送业务门，12份变体不采，不export、不新增合格答案或训练记录，学生v5仍57条/56题原字节不变。本次新增采样记录和结果说明尚未额外提交/推送。下一步两份报修各修一轮及后续业务门需另获明确授权，不因初采低于预算而自动调用。

<a id="slm-maintenance-staffing-repair-gate"></a>
## 第三十四阶段：g13 一轮静态反馈及两份排班候选送门（2026-10-31）

负责人确认两份报修各修一轮、静态复检、提交/普通推送初采和修复原件，再将静态通过者送候选CI并归档。本轮修复预算12万tokens、8192最大输出/次、零自动重试；不改工具链/题面/合同/判卷，不扩采12份变体、不export或训练。

使用原repair并显式选择已提交的通用repair-general-v1.md，避免默认鉴权专用说明；只附原始诊断和原DDL，不给完整参考或人工修法。八份首方程序与fdbb83a5的Git blob原字节一致。两次实际deepseek-flash均finish_reason=stop，input24380/output3826/total**28206 tokens**，缓存命中22016、未命中2364。连同初采，6次累计**70419 tokens**。

改写子候选3a0cc743ec66与父66485d05ad4a源码完全相同，仍4×SIR-LEX-001/PARSE失败。从零子e2bcf34bd576修正days写法，进入SEMANTIC后失败：load的FacilityNotFound缺本能力fails声明（SIR-FLOW-003），以及patch输入不存在expectedVersion字段（SIR-SYMBOL-002）。按一轮上限停止，两份均不送门。原件、调用边界和精确诊断见[一修记录](../../datasets/sir/research/2026-10-31/bounded-g13-repair/README.md)，两个报修批次新增checked-r1.jsonl，保留全部父/子失败轨迹。

临时后处理器在两次付费结束后读取参考行不存在的parent_id键而中断，原响应已完整保存；独立离线脚本只续做新子候选静态和复制，没有重新付费或重发。原collector与工具链不改。

本轮提交发布初采/一修和原始静态输出，数据提交skip ci；随后只将两份排班教师23ed7f759fcd、2027a6702825按各自checked-initial送StaffingShiftRosterBusinessConformanceIT。业务门独立验证构建/启动/业务/真实并发，不移植参考资格。run/head_sha及结果另由实际CI与archive-gate证据绑定；派发前均NOT_RUN，不预报通过。学生v5仍57条/56题不变，新增合格项以随后真实归档为准。

### 第三十四阶段结果：排班两份教师独立四门通过

初采/一修原件已由 `46671b9a089d7d56b4518e19d7098dea118ac180` 提交普通推送，86文件纯data/docs、skip ci。随后在该精确SHA仅派发两份排班：[改写#94 / 41203150293](https://github.com/HoloNova/Software-IR/actions/runs/41203150293)、[从零#95 / 41203161844](https://github.com/HoloNova/Software-IR/actions/runs/41203161844)。两份STATIC/BUILD/START/BUSINESS全PASSED，均 **871/0**，各三轮真实锁等待屏障为6/3/6个不同连接/事务。生成56/57文件，分别绑定自己的CLI生成摘要，不借参考或另一候选资格；只计本次实际模块JUnit，不混入Surefire ZIP附带的历史报告。

原archive-gate已校验run/head_sha、受测Git blob、四项绑定、生成摘要、GitHub ZIP摘要、原日志/报告附件及JUnit，原件在两个排班g13批次的runtime/<teacher>/github-<run>-attempt-1。[汇总/核验记录](../../datasets/sir/research/2026-10-31/bounded-g13-gate/README.md)保存完整派发与归档对账。

归档最后的全库清单验证最初被另一工作线10次fitness/events运行的300份未纳Git原件阻断。没有删清单、改校验或触碰其主工作区；在独立LF快照按原artifact元数据下载20个ZIP，恢复并逐项匹配这300份原SHA256，完整校验全库后再运行未修改的archive-gate。仅回写本轮两份排班原件与原生清单增量。旧缺项及隔离处理详见上述记录，仍应由原工作线另补，不冒称本轮已修复主工作区历史缺项。

本轮新增四门合格教师2份，**尚未export/student包装**。已导出57条/学生v5原字节不变；本轮只增记排班2份待导出资格，其他工作线已有待补原件/待导出候选，不自动重算或继承。学生v5仍覆盖56题，93题中37题不在当前学生集覆盖中；该数不是全库尚无合格答案的题数。报修失败父子全部封存，零追加追修；模型累计6次70419 tokens，不扩采、不训练、不改工具链。

<a id="slm-student-v6"></a>

## 第三十五阶段：fitness/events 的 g11–g12 收口、排班导出与学生 v6（2026-10-31）

**g11（fitness.check-in、events.registration 原题，手册 v9）。**四个批次各采 2 份，静态失败的 6 份各修一轮，共 14 次调用：输入 114,064（缓存 92,672）、输出 17,040，合计 **131,104 tokens**。最终 6 份四门通过并导出：fitness 改写 2、events 改写 2、events 从零 2（运行 ID 见各批次 `train-teacher.index.json`）。fitness 从零 2 份两轮仍静态失败（SIR-TYPE-001，可选筛选写成 `Optional<Id>` 与 Ref 比较），由此新增手册 v10（可选 Ref 筛选写法）。

**g12（fitness 从零，手册 v10）。**采 2 份均静态通过，但业务门全部失败；各按运行证据修一轮后仍失败，共 4 次调用、**54,478 tokens**（输入 49,393，缓存 25,216；输出 5,085）。原因相同：需求要求 month 缺少返回 400 BadPeriod，候选把 month 声明为必填 Text，按“普通非 Optional 输入一律必填”缺值在工作流之前就返回 400 INVALID_REQUEST。参考答案声明 `Optional<Text>`，v10 没写这一点，于是出手册 **v11**（`828d3a7`、`ac7129c`）：需求要求缺值报指定错误时字段声明为 Optional；时间平移数量只能是字面量、变量天数用 addDays（报修从零题在此语法失败）。同时 `536c75f` 让 SIR-LEX-001 写出被拒字符（`?` 时说明没有 `? :`）、变量天数平移提示改用 addDays，只改消息文字，sir-parser 222 项本机通过，CI 结果以负责人通知为准。

**排班导出。**第三十四阶段归档的两份排班教师（业务门 #94/#95，各 871/0）在受测工具链对应的 `918c1fb` 检出中 `export`，各 1 条，并登记批次与顶层清单。

**补回漏提交的证据。**g11 三批与 g12 共 10 次业务门的归档，清单已登记、文件却没进仓库的 300 份原件（原始 ZIP 20、JUnit 240、日志 40；`*.log`/`*.zip` 被 .gitignore 忽略）已从 GitHub 重新下载 20 个原始 artifact，逐个核对 GitHub 摘要与清单哈希后还原（`7573f52`）；未改清单与资格。顶层及 g11–g13 各批次清单 `sha256sum -c` 全部通过。

**学生 v6。**v5 的 57 条原字节保留，追加 g11 的 6 条（在 g11 导出时的 `22a4c65` 检出中包装，导出文件与 main 逐字节相同）和排班 2 条（在 `918c1fb` 检出中包装），共 **65 条、13 个 family**（鉴权 29、课程 26、活动报名 4、博客 2、健身 2、排班 2）。固定 revision 的三个分词器重测，保留行与 v5 记录逐条相同；最长完整序列仍为 **9,340／7,141 tokens**，新增 8 条最长 7,863／6,106。93 道题中 61 道已有合格答案，**32 道仍无**（train 26、development 2、test 4）。未训练。

<a id="slm-student-v7"></a>

## 第三十六阶段：v11 统一采样 g14–g15、归档修复与学生 v7（2026-10-31）

**采样。**手册 v11 与 `536c75f` 之后的工具链：g14 为 fitness 从零、报修两道原题与六道改写、排班六道改写共 15 题；g15 为新写的 fitness/events 12 道需求改写题（`e91882b`，逐句对照见各目录 `paraphrase-trace.md`）。每题一份，静态失败者各修一轮；业务失败且尚未修过的 fitness 7 份各按运行证据修一轮。

| 轮次 | 次数 | 输入（缓存） | 输出 | 合计 |
| --- | ---: | ---: | ---: | ---: |
| g14 初采 / 静态修复 | 15 / 8 | 133,389（66,048） / 99,555（89,600） | 26,514 / 15,448 | 159,903 / 115,003 |
| g15 初采 / 静态修复 | 12 / 4 | 88,228（53,760） / 37,668（33,664） | 14,696 / 4,772 | 102,924 / 42,440 |
| fitness 运行期修复 | 7 | 127,498（59,392） | 9,006 | 136,504 |
| **合计** | 46 | | | **556,774** |

**业务门与合格。**排班改写 6/6（#98–#103，各 871/0）、events 改写 6/6（#104–#109，各 626/0）、fitness 从零 p1 的修复子候选 1（#116，778/0）四门通过并导出，共 **13 条**。报修 8 份全部失败：静态修一轮后仍有 5 份把 `expectedVersion` 写进局部更新输入或实体（手册已写“不写进 input”），从零 p2 业务 598/2（批量关闭未锁设施）。fitness 7 份的 MyMonthAverage 都写成 `sum`，返回总时长；运行期修复后只有 1 份改成 `avg`，另 6 份只多写 `count`、仍返回总和，封存未送门。由此起草手册 **v12**（`b791941`，待确认）：单独写出 `avg`，并说明 SIR 没有除法。

**归档工具修复（`c24db1c`）。**业务门的 surefire 上传路径会把 `datasets/` 下已归档的历史 JUnit 一起带上，使已归档过的题再次判卷时 `archive-gate` 因“不止一个业务 IT 套件”拒绝（g14 排班与 fitness 7 份），缺报告时还可能拿历史报告顶替。现在选套件时跳过 ZIP 内的 `datasets/`，两个 workflow 的上传都排除 `datasets/**`；Python 合同 141 项通过。g14 那 8 次运行已带上的嵌套历史文件按原样归档（2,480 个文件，只对应 46 个仓库里已有的 blob）。

**学生 v7。**v6 的 65 条原字节保留，追加上述 13 条，共 **78 条、13 个 family**（鉴权 29、课程 26、活动报名 10、排班 8、健身 3、博客 2）。三分词器重测，保留行与 v6 记录逐条相同，最长仍 **9,340／7,141 tokens**。105 道题中 74 道已有合格答案，**31 道仍无**（train 25、development 2、test 4；train 中报修 8 道全无、fitness 6 道）。未训练。
