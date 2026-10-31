# SIR 数据首批工具

范围：冻结题目与输入、少量教师采样、保留失败与反馈修复、复用 `kcg check` 做静态筛选、只导出有四门证据的训练答案。不实现第二套 SIR 语义，不训练模型，不租 GPU，不在脚本里构建/启动应用或数据库。

首题沿用已采用的双 Token 登录。该业务家族固定属于 `train`，是公开开发题，不是盲测集。现有参考答案标为 `existing_reference`；教师输出标为 `teacher`。初始采样、反馈修复与独立题目不能混算。

## 安装与命令

Python 3.11+，Java 21；Python 依赖见 `requirements.txt`，可选的CPU分词计数依赖在 `requirements-profile.txt`。安装到自己的虚拟环境，不向项目 Java 模块添加依赖。检查器使用当前模块的 `target/classes` 与 ANTLR 4.13.2；先按项目资源约束编译受影响模块，工具不会自动运行 Maven。

```bash
python -m pip install -r experiments/slm/requirements.txt
python -m experiments.slm.data --help

# 每批使用一个尚不存在的新目录；保留数据不要只放在会被 mvn clean 删除的 target 中。
python -m experiments.slm.data prepare --bundle datasets/sir/pilots/next-run

# 只从环境读取 DEEPSEEK_API_KEY，不支持将密钥作为命令行参数。
# 默认单次调用禁用自动重试。一次命令最多5次，每次最多16384输出tokens。
python -m experiments.slm.data generate --bundle datasets/sir/pilots/next-run --count 3 --max-tokens 6144

python -m experiments.slm.data check --bundle datasets/sir/pilots/next-run --maven-repo "$HOME/.m2/repository"
```

本机 Maven 仓库实际是 `D:/maven-repo`，Windows 检查时显式传入该值。命令参数是 argv，不拼接 shell。CLI 的进程退出码 0 不等于每个样本通过，必须核对每行 `ok`、`stage` 和诊断。

## 业务门归档（archive-gate）

归档只需要 Python 3.11+、Git 和已认证的 `gh`，不需要 Java/Maven，不构建或启动应用。显式选择批次与本轮检查文件，不能靠候选 ID 扫库或猜最新轮次：

```bash
python -m experiments.slm.data archive-gate \
  --bundle BUNDLE --checked CHECKED --dry-run RUN_ID
python -m experiments.slm.data archive-gate \
  --bundle BUNDLE --checked CHECKED RUN_ID
```

`BUNDLE` 必须在本仓库 `datasets/sir/`，`CHECKED` 必须在该批次；两者对应输入和候选须已进入受测提交。命令只读一次已完成的 `slm-business-gate.yml` 运行，不轮询、不自动 fetch/重试，固定通过 `gh api --hostname github.com` 访问本项目。先核对 GitHub ZIP 摘要、受测 SHA 的 Git blob、冻结输入/候选/生成绑定、原附件、业务报告及 JUnit；`environment.test_revision` 对题目版本，不当 Git SHA。当前 Java 可以比受测提交更新，但不能据此重写旧资格。

新目录为 `BUNDLE/runtime/SAMPLE/github-RUN_ID-attempt-ATTEMPT/`：原 ZIP、报告、日志、JUnit 保持原字节；自有 `MANIFEST.sha256` 和增量外层清单记录实际文件。相同归档精确重复不覆盖，内容冲突拒绝，不同 attempt 分开。清单中断报 `ARCHIVE_WRITTEN_INDEX_PENDING`，原件保留，重跑同一命令核验后补完；不会通过全目录重算将损坏旧数据合法化。`--dry-run` 也完整下载/校验，但仅在系统临时目录写文件，不改变数据、清单或 Git 暂存区。

归档成功不等于四门通过，更不等于已进入 Git/训练集。失败结果也如实保存，**命令不自动 export**；成功者仍显式运行本页原 `export` 命令，选定原 runtime JSON 和尚不存在的输出。历史批次的 export/student 仍要求匹配冻结工具链，应在对应的已提交 LF 快照运行，不放宽 `read_bundle`。stdout 每 run 一行 JSON，含完整文件列表和被忽略的附件；提交前必须按明确路径显式加入必要的日志/ZIP，并按暂存 Git Blob 核对哈希，不能只证明本机存在。本工具不执行 git add/commit/push。

### 采集器来源

新生成/修复行保留 `collector_sha256`，另用 `collector_reference` 记录完整 Git commit 和首方 Python 源依赖闭包的路径/原字节哈希，不再向每个批次复制三份程序。**写请求及付费调用之前**核对当前源与已提交 blob，未提交源或 CRLF/LF 不同均拒绝，应使用已提交 LF 快照；无关文档/输出目录不阻断。不会自动提交，也不会执行引用指向的历史程序。

旧 collectors 副本保留原样并按旧格式验证。旧导新在源批次副本已经提交且原哈希一致时，记录它们的 Git 引用、不再复制；新导新携带已验证引用。缺对象/错哈希在创建目的批次前拒绝；import 仍重冻当前工具链/oracle、四门重置 NOT_RUN，原始请求/响应/源码/父子关系和旧资格不改。旧无来源字段的受支持行继续兼容。详见[设计与实施记录](drafts/design/archive-gate-design.md)。

离线 Python 合同可统一执行并保存原日志/计数，不访问 GitHub/教师或运行 Java：

```bash
python -m experiments.slm.tests.run_suite --evidence target/slm-python-tests-local
```

证据目录必须不存在；CI 的 verify 已接入同一入口并上传 `slm-python-contracts`，以对应受测 SHA 的结果验收。

## 反馈修复

先从 `checked.jsonl` 选择一个 `static=FAILED` 的样本 ID，再显式调用一次修复。下面使用本轮实际 ID；新一批应改为那一批的 ID。

```bash
python -m experiments.slm.data repair \
  --bundle datasets/sir/pilots/dual-token-v1 \
  --sample-id auth-dual-token-v1-teacher-f4b4a6071888

# 检查结果写到新文件，不覆盖首轮结果。
python -m experiments.slm.data check \
  --bundle datasets/sir/pilots/dual-token-v1 \
  --maven-repo "$HOME/.m2/repository" \
  --output datasets/sir/pilots/dual-token-v1/checked-next.jsonl
```

没有自动“修到通过”的循环。每轮原始答案、完整 SDK 响应、请求、用量、源哈希和 `parent_id` 都保留。修复反馈包括实际诊断、语法规则澄清和题目原有 DDL，不提供完整参考答案。SDK 序列化的完整响应不是 HTTP 原始字节；`raw_response_sha256` 指保存的 `response.json`。API 模型别名与 `system_fingerprint` 也不是开源权重的固定 revision。

## 题型

一道业务题出两种题型，共用同一个判卷 IT 与接口合同：改写题的 `base_source` 指向“已有软件”，prompt 末尾附上它，要求返回修改后的完整文档；从零写题的 `base_source` 为 `null`，批次仍冻结一个空的 `base.sir`（保持每个批次的冻结输入相同），prompt 只有需求和接口合同。同一道题的两种题型属于同一 family，必须进同一个 train/dev/blind 集合。

判卷用的数据库表结构是固定的，就像真实项目里已有的数据库。题目写了 `ddl_source`（必须是 `oracle.files` 中的一个，随批次冻结）时，prompt 在接口合同之后附上这份 DDL；没写的旧题目 prompt 逐字不变。接口合同只写用户会说明的东西：namespace、能力与实体名、每个接口的路由与请求/响应字段、错误、枚举、分页和业务常量；参考答案的实体/视图字段、表结构和判卷用的种子、探针、并发数等测试细节不写进合同。

`split` 取 `train`、`development`（dev）或 `test`（blind）。blind 的意思是不给教师看、不用于训练和调参；题目和参考答案本身都在公开仓库里，谈不上保密。`exposure` 是早期的说明文字，没有代码读取，与 `split` 重复，因此改为可选，新题不再写；`dual-token-v1.json` 保留原样，以免已有批次的 `import --task` 同题检查失效。`oracle.behaviors` 同理改为可选：它同样没有代码读取，每项检查对应哪句需求见各题的 `traceability.md`。

## Prompt 版本

`prompts/` 里每个版本是一个单独的文件，改 prompt 就新建文件，不改已用过的文件：已有批次的 `system.md` 和候选里的 `messages` 都是旧文件的原文，改了旧文件，这些批次就无法复现。`prepare` 默认用 `sir-0.3-auth.md`（v1），`repair` 默认用 `repair-auth-v1.md`；要用新版本时显式传入，只接受 `prompts/` 里的 `.md` 文件。`import` 不看这两个参数，直接冻结来源批次自己的 `system.md`。

v2（2026-10-25）补的是首批暴露的两处提示不足：实体成员的固定顺序；新建的行要先 persist 才有主键（编译器从 `bca868b` 起报 `SIR-FLOW-007`）。

`sir-0.3-general-v1.md`（2026-10-28）是不限于鉴权的通用版本，供课程、资金、商店、图书馆等题使用：写法全部取自 `docs/SIR_LANGUAGE_REFERENCE.md` 的已测试示例，另写明表名/列名与实体/字段的对应（题目附 DDL 时用得上）和成功状态码规则（工作流里有 `create` 就是 201，否则 200）。用 `prepare --system-prompt experiments/slm/prompts/sir-0.3-general-v1.md` 选用；默认仍是 v1。

`sir-0.3-general-v2.md`（2026-10-28）在 general-v1 上补两条试采样暴露的规则：输入字段不能写 `default`，分页缺省值与上限由生成器提供（`SpringBootQueryPolicy`）；`patch of` 输入只写主键和可改字段，PATCH 请求体外层的 `expectedVersion`/`changes` 由生成器包装（`SpringBootWritePolicy`）。

`sir-0.3-general-v3.md`（2026-10-28）再补四条：约束只写在字段的 `where` 里、不能当表达式；`at` 路径会自动加 `/api`；patch 字段用去掉规则的类型（`Name` 写成 `Text`、`Quantity` 写成 `Count`）；带 Version 的实体不能写守卫更新或原子加减。

`sir-0.3-general-v4.md`（2026-10-28）再补一条：patch 字段在 `update` 里直接绑定 `input.字段`，没传的字段自动保留原值，SIR 没有 `? :` 三元表达式。

`sir-0.3-general-v5.md`（2026-10-28）补 v4 跑完 train 集暴露的六条：按主键加载不写字段名；`expose command` 后只能接 `put`／`delete`；`persist … else` 只用于版本冲突和 unique 冲突；变量名也不能用保留字；字段修饰顺序给出例子；`.present` 只用于 patch 输入。

`sir-0.3-general-v6.md`（2026-10-28）按三份只读分析（`drafts/prompt-audit-general-v5.md`、`drafts/teacher-error-census.md`、`drafts/course-selection/failure-analysis.md`）修订，逐条改动与依据见 `drafts/prompt-v6/changes.md`：改正“守卫更新只有一个赢家”的错误说法；补算术输入必填（min/max 不管 null）、Optional 空比较、find/exists/count 与 any 的作用域和两种连接、凭据 actor 是 subject 实体、scope 对所有读取生效、引用声明必须闭合；易错点前加四项自查清单。篇幅约 9,000 字。

`sir-0.3-general-v7.md`（2026-10-29，负责人已确认）仅同步已由 verify #198/#199 验收的新行为：普通非 Optional 输入一律必填，带 notBlank 的文本仍报 NotBlank；只有本能力中的分页参数保留默认值，同一输入在不分页的能力中仍必填；同步函数参数的前置检查说明，不再为触发必填教减法绕弯写法。依据语言参考 §3.3 与 §6，旧版本不改；使用时显式传 `--system-prompt experiments/slm/prompts/sir-0.3-general-v7.md`，默认 prompt 不变。

`sir-0.3-general-v8.md`（2026-10-29，已批准并完成博客首轮试采）在 v7 上澄清多约束逗号、Ref 与 HTTP 主键/字段名映射、普通查询与 any 的不同作用域、containsLiteral 的非 Optional 右值，并强化声明闭合自查；不改变语言规则。已用原 CI 检查器核对 3 个正例完整生成、8 个反例按预期拒绝；该例证核对未调用模型，不能据此声称通过率提升。逐项依据与边界见 [v8 改动说明](drafts/prompt-v8/changes.md)，编译器四处文案随后已获确认并本机实施，7 类 125 项定向通过、14 条 CLI 边界对照及 189 个生成文件逐字节一致，详见[实施记录](drafts/design/query-diagnostics-design.md#local-verification)；随后 `2e4fa74b` 的 [verify #202](https://github.com/HoloNova/Software-IR/actions/runs/40877385610) 已通过，上传 Surefire 为 293 XML／2031 项、零失败／错误／跳过，其中新增诊断回归 22 项，博客参考业务 425/0；原始证据见 `datasets/sir/research/2026-10-29/blog-v8-ci202/`。旧版本和冻结批次不改；使用时仍须显式 `prepare --system-prompt experiments/slm/prompts/sir-0.3-general-v8.md` 新建批次，采样预算另批。

`sir-0.3-general-v9.md`（2026-10-31）在 v8 上只同步 v8 之后实施的三条规则：any 内同一字段自比较被拒、查重后新建唯一行的 persist 必须写 else（`47bf67c`）、containsLiteral 两侧接受可选文本（`e6f3b1d`），删去 v8 第 95 行的旧限制。本机编译的检查器核对 3 个正例完整生成、2 个反例按预期拒绝；未调用模型。逐项依据见 [v9 改动说明](drafts/prompt-v9/changes.md)。旧版本和冻结批次不改；使用时须显式 `prepare --system-prompt experiments/slm/prompts/sir-0.3-general-v9.md`。

`sir-0.3-general-v10.md`（2026-10-31）在 v9 上只加一条“按引用筛选”：可选的引用筛选字段写 `Optional<Ref<实体>>`，不能写 `Optional<Id>` 与 Ref 比较，可选 Ref 的 HTTP 字段名不带 `Id`。起因是 fitness 从零题 g11 两份教师答案都写成 `Optional<Id>`（`SIR-TYPE-001`）。依据见 [v10 改动说明](drafts/prompt-v10/changes.md)；旧版本和冻结批次不改，使用时须显式 `prepare --system-prompt experiments/slm/prompts/sir-0.3-general-v10.md`。

```bash
python -m experiments.slm.data prepare --bundle datasets/sir/pilots/dual-token-v2 \
  --system-prompt experiments/slm/prompts/sir-0.3-auth-v2.md
python -m experiments.slm.data repair --bundle datasets/sir/runs/dual-token-recheck-v2 \
  --sample-id auth-dual-token-v1-teacher-ef9ddc30eb79 \
  --feedback experiments/slm/prompts/repair-auth-v2.md
```

测试：`PYTHONPATH=$PWD:$PWD/experiments/slm/tests python -m unittest experiments.slm.tests.test_pipeline experiments.slm.tests.test_corpus experiments.slm.tests.test_dataset_io`（在仓库根目录运行；`test_dataset_io` 从 tests 目录导入 `test_corpus`）。

## 博客新题（参考已验收，两题已冻结）

`content.blog` / `train` 的七能力裁剪参考已在 `66c276915d825f4d1914da721b1ddf4e0ceda2de` 的 [verify #200](https://github.com/HoloNova/Software-IR/actions/runs/40839225636) 四门通过：55 个生成文件，业务 425/0，四轮各六个真实连接／事务竞争。base 完整生成 47 个文件且缺四个新增 Controller；这是静态负例，不冒称已运行 base 的 HTTP 验收。原始 ZIP、报告、日志、JUnit 与源码摘要在 `datasets/sir/research/2026-10-29/blog-reference-ci200/`。

正式定义为 `tasks/blog-change-v1.json`、`blog-scratch-v1.json`；原需求／合同不变，共用 38 项 oracle 闭包及真实参考证据。用原 `prepare` 在与 Git blob 一致的 LF 快照冻结 `datasets/sir/pilots/blog-change-g7/`、`blog-scratch-g7/`，冻结时每批只有 1 份 `existing_reference`，由原静态绑定函数配合该 CI 的 standalone CLI 检查通过；批次运行门不继承参考资格。摘要协议差异与完整证据见[第十八阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-blog-reference-frozen)。

已获批的 v7 首轮两题各采 1 次，2 份均静态失败，新结果为各批 `checked-initial.jsonl`；实际输入 13,927（缓存 7,168）、输出 2,983，共 16,910 tokens。初始记录见 `datasets/sir/research/2026-10-29/blog-v7-initial/` 和[第十九阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-blog-v7-initial)。

负责人随后批准两份各一轮静态反馈重答，实际共 22,846 tokens；新 `checked-r1.jsonl` 两份均 SEMANTIC FAILED（改写 2 条、从零 18 条 ERROR），没有派发业务门、追加追修或导出。博客累计 4 次 API／39,756 tokens，合格教师仍 48，学生 v3 未变。失败轨迹仅归档，不作为当前学生 SFT 的正确答案；本阶段已按一轮上限停止。记录见 `datasets/sir/research/2026-10-29/blog-v7-repair/` 和[第二十阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-blog-v7-repair)。

v8 已按批准预算在新 `blog-change-g8/`、`blog-scratch-g8/` 各初采一次，共 17,889 tokens；改写候选 `906a864f71bf` 完整生成 57 文件，无诊断；从零候选 `718c0d88dafa` 为 SEMANTIC FAILED，3 条诊断（能力缺少 `fails Forbidden`、两处 Optional 文本匹配）。原始响应、静态输出与下一步预算见 `datasets/sir/research/2026-10-29/blog-v8-initial/`，完整记录见[第二十二阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-blog-v8-initial)。两份小样本不构成整体通过率提升的证明；尚无新增博客合格答案，学生 v3 未改。

`1aa6f5fe` 归档后，改写候选已送[业务门 #65](https://github.com/HoloNova/Software-IR/actions/runs/40981352192)，配套 [verify #203](https://github.com/HoloNova/Software-IR/actions/runs/40981119034) 由 push 自动触发；后续负责人通知 #65 失败、#203 成功；已核对精确 SHA 与候选全部绑定，#65 的 STATIC／BUILD／START PASSED、BUSINESS FAILED（401/25），失败证据归档到改写批次 `runtime/`。从零候选另获批一轮反馈重答，实际 11,631 tokens；新子候选 `215ebacdb9b3` 仅增加 `fails Forbidden`，仍有两条 Optional 的 containsLiteral 错误，按一轮上限停止。v8 合计 3 次 API／29,520 tokens，合格仍 48，学生 v3 未改。记录在 `datasets/sir/research/2026-10-30/blog-v8-repair/` 和[第二十三阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-blog-v8-repair)。

改写父候选另获批一次原 `repair --runtime-evidence`，实际 24,257 tokens；新子候选 `af2c42118880` 退回解析失败（不支持的内联 exists，3 条诊断），按一轮运行反馈额度停止，未送新业务门。原父候选的 25 条业务失败由两个根因构成：any 内 `tag == tag` 是字段自比较，以及先查后插未将唯一冲突映射为 TagAttached。没有手改题目／候选／判卷，v8 合计 4 次 API／53,777 tokens，合格仍 48、学生 v3 不变。原始记录见 `datasets/sir/research/2026-10-30/blog-v8-runtime-repair/` 和[第二十四阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-blog-v8-runtime-repair)。

## 新静态检查后的博客 g9 重查

`47bf67c8` 的 [verify #204](https://github.com/HoloNova/Software-IR/actions/runs/41019419975) 已通过：关联字段自比较使用 `SIR-VALID-001`，此前 EXISTS 查过的唯一新行 persist 缺 else 使用 `SIR-VALID-004`，不改变 any 名称优先级，也不强制所有唯一新建写 else。两类新回归 55 项通过，上传总计 2086 项零失败／错误／跳过，博客参考业务 425/0；边界与证据见[静态安全检查设计](drafts/design/static-query-safety-design.md#ci-204-accepted)。

按原 import 将 g8 两批导入 `blog-change-g9`、`blog-scratch-g9`，保留题目、v8、原始请求／响应／源码和父子关系，重新冻结新工具链并重置四门。#204 原 standalone CLI 实际检查：参考两份完整生成；`906a864f71bf` 现在被 A/B 静态拦下（3 条语义错误），从零父子为 6／5 条（原 Optional／fails 诊断仍保留），运行修复子候选仍为 3 条解析错误。新批次运行门全 NOT_RUN，API 调用 0 次，无新合格答案或训练。结果和另待批准的三份静态反馈预算见[第二十五阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-blog-g9-recheck)；历史额度不因 import 清零，不自动续修。

### g9 新静态反馈一轮

三份指定候选的费用随后获批，原 repair 各调用一次，共 **39648 tokens**（输入35020，其中缓存29568；输出4628）。改写 `bba0b5b48fc4` 消除 A/B、静态完整生成57文件；从零 `275138cf0c55`／`216042934560` 仍有3条语义错误，且新源码相同。三份结果保存在各批 `checked-r1-<父短ID>.jsonl`，不覆盖原 checked，不继续追修失败者。只把改写新子候选提交后送 BlogBusinessConformanceIT，当前运行门NOT_RUN、合格仍48、学生v3不变，未训练。审批、真实请求／响应、用量与父子差异见[第二十六阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-blog-g9-feedback)；参考的CI成功仍不借给教师。

### g9 首条博客资格与学生 v4

受测 `c6a993aa` 的[业务门 #66](https://github.com/HoloNova/Software-IR/actions/runs/41031591392)已四门全过、业务426/0，改写 `bba0b5b48fc4` 由原归档器核对ZIP／绑定／附件并 export 1条。累计合格 **49条（26鉴权、22课程、1博客）**；原student只包装新增1条，与封存v3的48条原字节合成v4，49条完整来源审计通过。审计发现旧44批次因 `*.log` 漏纳176份原日志，本轮从原ZIP按原哈希恢复并显式提交，不改旧源码或资格；不是重跑历史门。本轮模型调用0次、未训练，详见[第二十七阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-blog-g9-qualified)。

## 通用静态修复

`prompts/repair-general-v1.md` 是不限题型的静态反馈前缀：说明这是同一道题的修复、按编译器诊断逐条改、需求与接口不变，不提示具体改法；诊断原文与 DDL 由 `repair` 自动附上。用法：`repair --bundle … --sample-id … --feedback experiments/slm/prompts/repair-general-v1.md`。每个失败候选只修一轮。

## 运行反馈修复

静态已通过、但业务门失败的候选，用 `repair --runtime-evidence <slm-runtime.json>` 修复（默认反馈 `prompts/repair-runtime-v1.md`）。证据须与该候选的四个绑定哈希和生成摘要一致、附件哈希一致，且四门为 PASSED/PASSED/PASSED/FAILED；只把判卷报告里失败的检查和 HTTP 记录发给教师，通过的检查与日志不发，不提示改法。新候选记 `generation_mode: runtime-feedback-repair` 和所用证据的哈希。

## 学生训练数据

`export` 产出的是教师原样输入：带着各版本的长规则手册，修出来的答案还带着完整修复经过。学生要把规则学进模型，上线时也只拿到题目，所以用 `student` 重新包装：

```bash
python -m experiments.slm.data student \
  --bundle datasets/sir/pilots/*-g4 datasets/sir/pilots/*-g5 … \
  --output datasets/sir/student/train-student-v1.jsonl
```

每条是固定的短 system（`prompts/student-v1.md`）+ 与教师逐字节相同的题目（需求、合同、表结构、已有软件）+ 最终合格 SIR，不带修复经过。哪些答案合格只由 `export` 决定：`student` 只读各批次的 `train-teacher.jsonl`，按其 `.index.json` 核对文件哈希、答案哈希和样本源码，不重新判卷；只收 train 批次，dev/test 批次直接拒绝。index 记录每条来自的批次、样本、业务门 run 和字数。

`student` 仍会通过 `read_bundle` 校验当前源码与每批的工具链／oracle；不同工具链的历史合格批次不能直接混跑，也不能改 manifest 或跳过校验。跨版本追加时，保留已有学生封存文件的原始字节，用原命令只包装与当前源码一致的新合格批次，再合并记录和索引；合并必须核对新旧导出索引／候选源码／题目／运行绑定、同题源码去重、family/split 隔离和完整来源覆盖。v3 实例见[数据报告第十六阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-prompt-v7-qualified)，其 index 的 `assembly` 保存基座及增量来源哈希。不得借包装重算或改写历史资格。

每版学生数据都须重新测完整序列长度，不能沿用旧版本上限；只用冻结 revision 的分词器，不下载或运行权重，不截断。当前v5共57条，v4的49条原字节保留（重测与v4记录逐条一致）；三分词器实测最长9,340／7,141，详见 `datasets/sir/student/train-student-v5.tokens.json`。

## 跨环境导入

原批次指纹绑定实际源码字节，不能靠换行转换或修改manifest搬到Linux。先在目标环境编译CLI/测试适配器，再执行：

```bash
python -m experiments.slm.data import \
  --from-bundle datasets/sir/pilots/dual-token-v1 \
  --bundle datasets/sir/runs/dual-token-cloud-v1
python -m experiments.slm.data check \
  --bundle datasets/sir/runs/dual-token-cloud-v1 \
  --maven-repo "$HOME/.m2/repository"
```

import验证原冻结文件、响应/请求/源码及metadata，保持原批次不变，重新冻结本环境工具链/oracle；`imported_from`保留原指纹。只保留教师原始响应与校验过的采集器来源；新导入记录使用Git引用、不再复制程序，旧副本不改。不调用API，不迁移任何PASSED或生成摘要。当前参考另作为种子重新加入。必须在新批次重做静态和实际业务门，再导出；不要把`from-bundle`当运行批次。

判卷文件改名或增删后，旧批次 `task.json` 里的 `oracle.files` 会指向不存在的文件，普通 import 冻结不了。这时加 `--task experiments/slm/tasks/<题目>.json` 用当前定义导入：除 `oracle.files` 外必须与来源批次的题目完全相同，base 与参考答案的字节也必须与来源批次冻结的一致，否则拒绝（那已是另一道题，教师答的不是它）。`import.json` 的 `task_replaced` 记录新旧题目哈希。

## 参考环境中的真实验收

本机不执行。只在已经配置好受限 MySQL 权限、Maven 依赖和资源限制的 Linux CI/参考环境运行。继续使用原有 `DualTokenBusinessConformanceIT`、DDL、种子、锁、HTTP/JDBC 断言和清理，不另起一套判题。普通 CI 不设置候选属性，仍检查原参考答案。

外部候选只接受单文件 `candidate.sir`，通过生产 `ProjectSourceReader` 的有界、目录句柄及 NOFOLLOW 读取。不支持安全目录句柄的 Windows 会拒绝，不能为实验降低读取要求。源码必须包括本轮测试适配器，不能拿旧 CI 的固定夹具结果代替候选验收。

下面是完整调用模板；先按 `nproc`、`free` 确定外层限制，设置 `MEMORY_LIMIT`、`CPU_QUOTA`，并沿用已有 `KCG_CONF_CONTROL_JDBC_URL`、`KCG_CONF_CONTROL_USERNAME/PASSWORD`、`KCG_CONF_RUNTIME_USERNAME/PASSWORD`。密码不进参数或数据文件。

```bash
: "${MEMORY_LIMIT:?Set a safe scope limit after checking free}"
: "${CPU_QUOTA:?Set a safe scope quota after checking nproc}"
: "${MAVEN_REPO:?Set the absolute frozen Maven repository path}"
REPO="$(pwd)"
BUNDLE="$REPO/datasets/sir/runs/dual-token-cloud-v1"
CANDIDATE="$BUNDLE/samples/auth-dual-token-v1-teacher-ef9ddc30eb79"

MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2' \
systemd-run --scope -q -p "MemoryMax=$MEMORY_LIMIT" -p "CPUQuota=$CPU_QUOTA" -- \
  mvn -B -o -pl sir-toolchain-application -am test \
  -Dtest=DualTokenBusinessConformanceIT -Dsurefire.failIfNoSpecifiedTests=false \
  '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2' \
  -Dkcg.dual-token-conformance.enabled=true \
  -Dkcg.query-conformance.schema-name=kcg_conf_run \
  -Dkcg.conformance.work-parent="$REPO/ci-work" \
  -Dkcg.conformance.evidence-parent="$REPO/ci-evidence" \
  -Dkcg.conformance.maven-executable="$(command -v mvn)" \
  -Dkcg.conformance.maven-repo="$MAVEN_REPO" \
  -Dkcg.conformance.maven-xmx-mb=384 \
  -Dkcg.conformance.application-xmx-mb=512 \
  -Dkcg.conformance.child-metaspace-mb=128 \
  -Dkcg.slm.candidate-root="$CANDIDATE" \
  -Dkcg.slm.bundle-root="$BUNDLE" \
  -Dkcg.slm.repo-root="$REPO"
```

共用同一 schema 时串行运行。环境不齐为 `NOT_RUN`；测试会先检查开关及环境，不会因此自动启动数据库。失败不是自动扩大权限或创建另一 schema 的理由。候选运行会在该次 `ci-evidence` 子目录产出 `slm-runtime.json`，绑定候选、题目、oracle、工具链和生成摘要；同目录日志及业务报告也有哈希。

实际参考环境已在负责人指定的`ssh lab`上跑通（原生systemd scope，不是Forgejo Actions或ML默认镜像）：参考35/0、四门通过；教师`ef9ddc30eb79`静态/构建/启动通过、业务19/14失败，故新教师导出仍0条。Linux批次在`datasets/sir/runs/dual-token-lab-v1/`，原采样批次不回写。本次临时参考目录后按负责人要求删除，历史证据保留。工具使用指南仅以独立Prompt文本提供：Forgejo仅局域网直连，任务通过公网ML API派发，不依赖SSH。

## 导出与边界

```bash
# 没有真实运行证据时，正确结果是0条，不是把静态候选当训练答案。
python -m experiments.slm.data export \
  --bundle datasets/sir/pilots/dual-token-v1 \
  --checked datasets/sir/pilots/dual-token-v1/checked-r2.jsonl \
  --output datasets/sir/pilots/dual-token-v1/train-new.jsonl

# 有真实证据时，使用实际运行的新bundle与它的checked.jsonl，
# 额外传 --runtime-evidence /absolute/run-directory/slm-runtime.json
```

四门均 `PASSED`、绑定一致、证据附件实际存在且哈希一致，才导出为 TRL conversational prompt/completion；索引另存，不把元数据当语言模型标签。同题相同生成摘要去重；development/test/public_regression 不进入训练。完整修复历史会保留在 prompt 中，失败答案不作为 completion；训练前必须按实际分词长度分桶，不能静默截断。

每批 JSON/JSONL 有16 MiB预算，批量静态接口另有10000行上限。本工具是小批试跑，不是大规模调度平台。训练/开发/盲测按业务家族隔离，不以改名视为新题；冲突检查覆盖传入批次，汇合多批数据时还须检查合并索引。当前没有冻结盲测集，也未开始小模型评估。

本轮结果见 [首批报告](../../docs/qualification/SLM_DATA_PILOT_REPORT.md)，模型与云 GPU 建议见 [基座调研](../../docs/qualification/SLM_MODEL_SELECTION.md)。

## 报修/排班新题：参考已验收，十六题已冻结

`maintenance.work-order` / `staffing.shift-roster` 均为train，参考在 `5fe4c8339a927fb526c004c276beb4c1c8892996` 的 [verify 41194982453](https://github.com/HoloNova/Software-IR/actions/runs/41194982453) 通过：报修599/0、排班870/0，六组真实锁等待屏障全部观测到，完整流程含独立部署成功。原ZIP、两题日志/报告、JUnit与输入Git blob绑定见 [参考与冻结证据](../../datasets/sir/research/2026-10-31/bounded-reference-ci41194982453/README.md)；原失败和修复历史保留，不当当前阻塞。

正式定义在 `tasks/maintenance-work-order-{change,scratch}[-p1|-p2|-p3]-v1.json`、`staffing-shift-roster-{change,scratch}[-p1|-p2|-p3]-v1.json`，各8题。固定公开合同、原题需求、SIR/DDL/seed及同一判卷闭包共用；变体逐条映射见 `requirements-equivalence.json`，没有增加业务、换名导入dev/test或给教师看种子/并发探针。

原prepare在精确LF快照、v10手册下冻结16个 `pilots/<题号去掉-v1>-g13/`，每批只1份existing_reference，全部由该CI的原CLI完整静态生成通过、无诊断；报修55、排班56文件，两个base仅静态生成47/50文件，未运行base HTTP负例。原样保留16次检查输入/输出、冻结清单与MANIFEST；运行门全NOT_RUN，不移植参考资格。工具链450份输入及39/40项oracle闭包与受测Git blob原字节一致；没有重新编译工具链或启动业务应用。

冻结检查时未提交、推送、触发CI、调用模型、采样、export或变更学生v5。负责人现已确认先提交/普通推送，再对四道原题各初采1次；12份变体不采。每次最大8192输出tokens、零自动重试，四次初采保守总预算18万tokens，实际输入大小与估算方法见 `sampling-plan.json`。失败修复不在本次费用授权内。将来采样/送门须在包含新定义与批次的已提交LF快照操作，不能直接使用Windows CRLF工作区或借参考资格。

### g13 原题初采结果

`fdbb83a5` 已提交并普通推送上述冻结输入/原件，未改变工具链，skip ci。随后按授权对四道原题各初采1次deepseek-flash，实际42213 tokens；两份排班静态GENERATION通过，两份报修PARSE失败（擅写三元表达式、动态days）。[原请求、响应、用量与检查记录](../../datasets/sir/research/2026-10-31/bounded-g13-initial/README.md)及各原题 `checked-initial.jsonl` 尚未额外提交。零自动重试/修复，12份变体不采，未送业务门，各运行门NOT_RUN，学生v5与合格答案数不变；修复费用及送门须另行授权。

### g13 一轮反馈及送门范围

负责人已批准两份报修各一轮反馈、提交/普通推送及候选业务CI。通用repair-general-v1配原诊断/DDL，实际两次28206 tokens；初采加修复6次70419。两个报修子仍失败，按一轮上限封存；只送排班两份静态通过教师，分别使用原checked-initial.jsonl。先发布原件/哈希再派发slm-business-gate；结果必须由本次CI与archive-gate绑定。原件见[一修及送门记录](../../datasets/sir/research/2026-10-31/bounded-g13-repair/README.md)，学生v5和12变体不动，不export、训练或追加修复。
