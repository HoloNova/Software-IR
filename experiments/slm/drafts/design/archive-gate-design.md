# 4.4：业务门统一归档与采集器 Git 来源设计

状态：**负责人已确认；本机实施与离线验证完成，未提交，本次 CI NOT_RUN**。基线 `2282ad2e84776a9ed1f866488aed1dfa56872c48`。设计阶段仅按 CI 通过安排后续；实施前负责人正式通知 verify #205 通过，已单次核对精确 SHA 和两个作业均 success，见第9节。旧 CI 不代替本次 Python 改动的验收。

第1至7节保留已确认的设计与边界；第8节为设计阶段记录，第9节为后续实施事实。本次没有教师调用、训练或历史数据改写。

## 1. 为什么存在

交接 4.4 要求以 `data.py archive-gate` 取代阶段性归档脚本，未来批次不再复制采集器程序；既有冻结副本不删。归档的难点不是复制文件，而是不能将别的批次、工具链或参考答案的成功，误算为本候选的资格。

现有可复用能力已足够：`corpus.py` 的绑定/来源/四门规则，`dataset_io.py` 的严格 JSON、指纹与附件校验，`data.py` 的候选核验和 export。不引入新的 SIR 语义、判卷器、下载框架或 Python 依赖。

不直接将临时脚本原样搬入，原因均已在源码或材料中核对：

| 现状 | 不能直接保留的原因 |
| --- | --- |
| 按候选 ID 扫所有批次，要求唯一 | import 保留 ID，g8/g9 已有同 ID；必须由调用者明确选择批次 |
| 将 checked 文件名倒序视为最新结果 | 字符串次序不等于轮次，也不表达分支；不能替调用者猜 |
| 依赖 curl/unzip/grep | 本机 Windows 已遇到 PATH 问题；curl 异常还可能携带含鉴权参数的 argv |
| 只取一个名字匹配的 ZIP/报告，并将 JUnit 文件扁平复制 | 缺失、多份或重名时可能选错或覆盖；需唯一性与相对路径检查 |
| 目标固定为 runtime/<sample> | 重判和重跑会与已有证据冲突，不能覆盖旧结果 |
| 成功后自动 export，train-teacher 已存在就警告跳过 | 后续通过的答案可能被漏掉；归档和导出不应隐式捆绑 |
| snapshot_collector 写三份 Python，copy_collectors 强制读取并再复制 | 仅停止写副本会破坏后续 import；需要新来源表示与旧格式兼容 |
| export/student 经 read_bundle 验证当前工具链 | 新工具链不能直接替旧批次导出；归档不应错误地要求当前 Java 源与历史运行相同，也不能放宽 export |

额外确认：`slm-runtime.json.environment.test_revision` 是**题目 revision**，由 `SlmCandidateSupport.writeEvidence` 从 `candidate.task_revision` 写入；#66 的值是 `"1"`，不是 Git SHA。受测提交须取 GitHub run 的 `head_sha`，不能混用。

## 2. 范围与不做什么

本次做：正式归档入口、必要的可测试内部模块、新采集器 Git 引用与 import 兼容、离线定向测试、用法与 CI 测试接线。

本次不做：修改 Java/Parser/语法、重跑业务 IT、改题/Prompt、重新判定49条资格、合并学生数据、自动调用模型、自动提交/推送、创建通用任务平台。旧批次、副本、原始日志/ZIP与既有导出文件都不改。`docs/tmp` 留到 4.5 单独删除，不在本提交顺手清理。

只改 Python、相应工作流和文档，不改变现有 `core_inputs()` 所描述的 Java/POM 工具链输入，也不改变 oracle；不因此重冻历史 manifest。新批次照常记录新的实验输入和采集器来源。

## 3. 外部入口：显式归档，显式导出

拟新增：

```text
python -m experiments.slm.data archive-gate \
  --bundle BUNDLE --checked CHECKED [--dry-run] RUN_ID [RUN_ID ...]
```

- `--bundle` 和 `--checked` 必填；批次必须在本仓库 `datasets/sir/`，检查文件必须位于该批次且已随受测提交进入 Git。
- 每次只处理一个明确批次，可以给多个属于该批次的 run；每个 run 单独校验/提交本地归档，顺序执行。后一个失败不删除先前已完成的归档，返回非零并说明已完成的 run。
- 不从文件名猜最新检查轮次，不扫描同 ID 的其他批次，不自动 fetch、更不轮询未完成 run。
- `--dry-run` 仍须下载和完整验证，只在系统临时目录操作；不写数据目录/导出/清单/索引，不改变 Git。
- stdout 每 run 一条结构化摘要：run/attempt、SHA、sample、checked、四门、检查数、归档位置、是否本地重复、被忽略的必需附件。错误走 stderr，命令返回非零；不输出 token、环境变量或原始 HTTP 错误体。
- 归档成功表示**原始证据已核验并落盘**，不是“已进 Git”或“已增加训练答案”。失败候选也可归档；没有有效候选 verdict 时明确拒绝，不凭工作流颜色补造四门。

**导出保留为现有独立 export 命令，不新增自动导出开关。** 这是与临时脚本有意不同的行为：调用者明确选证据和一个尚不存在的输出，消除“输出已存在就跳过”的漏收。仍由原 `training_record`/export 决定四门、train split 和去重，归档器不另写收录算法。

```text
python -m experiments.slm.data export \
  --bundle BUNDLE --checked CHECKED \
  --runtime-evidence ARCHIVE/slice/slm-runtime.json --output BUNDLE/train-new.jsonl
```

export/student 原有当前工具链检查不放宽。历史批次在与其冻结输入匹配的 LF 源码快照中运行原导出/包装命令；如果输出已存在，显式换新输出而非追加、覆盖或默默跳过。

## 4. 单个 run 的核验与落盘

### 4.1 先确认来源，不借用当前源码

1. GitHub 固定仓库 `HoloNova/Software-IR`，使用已认证的 `gh api`，不通过 `gh auth token` 提取凭据、不将 Bearer token 放进 argv。仅用 Python 标准库处理 ZIP、散列与扫描，不依赖 curl/unzip/grep。
2. run 必须为用户指定 ID、正确仓库、`slm-business-gate.yml` 的已完成 workflow_dispatch；verify/reference 工作流、未完成、取消或缺少候选 verdict 的运行不当候选证据。
3. 分页取全 artifact 列表，要求目标 sample 的 evidence/surefire 恰好各一份、未过期、GitHub SHA256 可验证；同名冲突不挑“最新”。核对 artifact 自带 workflow run ID/head SHA。超时/下载失败不自动重试。
4. 以 **run.head_sha 的 Git blob** 核对受测 manifest、task、选定 checked、candidate 元数据/源码/请求/响应，以及冻结核心/oracle 输入。Git 对象本机不可读就提示先同步，不用当前 HEAD 冒充，不自动 fetch。
5. 复用现有核心输入集合规则，核对受测提交的集合与 manifest 一致，包括漏掉/额外新增的主源；再核对聚合指纹和各项字节。当前工作区只需目标冻结批次与该受测提交对应，不要求当前 Java 与历史工具链相同。
6. 复用 `validate_candidates` 核对目标行的 teacher 来源、样本/题目/family/split、源码与响应绑定；非目标历史行不因为缺少本次运行证据而被算作通过。当前文件和该提交的相应文件不一致就拒绝，不覆盖来“修好”。

Git 读取是共享的有界只读能力：同时服务受测提交核对和第5节采集器引用。提交 ID 只接受完整对象 ID，路径是非穿越的仓库相对路径，读取 blob 不 checkout、不运行历史代码。不会创建第二套语义/生成/资格规则。

### 4.2 ZIP、证据与原始字节

- 先校验压缩包原字节 SHA256 与 GitHub digest，再枚举解包；拒绝绝对/驱动器/穿越路径、符号链接、反斜线绕过、重复与大小写冲突路径。设置明确压缩/解压总量和文件数量上限；以现有 #65/#66 ZIP 复核可容纳，超限报错，不静默截断。
- 唯一定位 slm-runtime 与它登记的报告/日志，复用 `_check_runtime_binding`、`_require_runtime_provenance`、`verify_artifacts`。版本化 generated_digest 仍对 CLI static.digest，不能拿旧 combinedSha256 替换。
- 题目 revision 与 `environment.test_revision` 比较；Git SHA 单独来自 run。业务 verdict、四门、报告 checks 与 GitHub conclusion 相互核对。全部 PASSED 还须对应冻结 oracle 中业务 IT 的实际 JUnit 运行，不能跳过；oracle 没有 IT 字段，不给冻结 task 补字段，从其已列 Java IT 与 JUnit 全名做唯一匹配。
- 失败/NOT_RUN 不升级；不能以 JUnit exit=0、报告缺失或参考测试成功替代候选资格。不能解析检查数时显示明确错误，不猜0。
- 扫描解包内容的 CI 口令标记和传输层已知敏感值；发现则拒绝持久归档，不通过替换日志来破坏哈希。单独的 Actions 聚合 failed.log 不下载进数据目录；原 ZIP 若含敏感内容也不归档。
- 保存两份原 ZIP、GitHub run/artifact 元数据、完整 runtime 目录、JUnit。JUnit 保留模块相对路径，不扁平覆盖。日志、XML、报告、ZIP 全用二进制复制；只有本工具新建 JSON/清单采用 UTF-8/LF。

### 4.3 不覆盖、可复核的本地提交

新归档采用：

```text
BUNDLE/runtime/SAMPLE/github-RUN_ID-attempt-RUN_ATTEMPT/
  github/                   # 元数据和两份原 ZIP
  <slice-run-id>/            # 原 slm-runtime、报告、logs 原样保留
  junit/                    # 保留来源相对路径
  gate-run.json
  MANIFEST.sha256            # 此归档自有文件清单，不包含自身
```

`gate-run.json` 保留原消费者需要的 workflow/run_id/run_url/head_sha/inputs/artifacts/gates/checks 字段，增加 run_attempt/checked 及来源摘要。GitHub run ID 与 slice run_id 分开。旧 runtime 布局继续可读，不迁移、不覆盖。

先在同卷临时目录构建完整归档，核验完成再安装到不存在的目标。目标已存在时，仅在稳定身份、ZIP/文件哈希和原归档自有清单完全一致时返回 no-op；任何差异、残缺或未知文件都拒绝，不用覆盖/补丁式修复。GitHub 临时下载 URL、查询时间不参与身份，也不替换首次保存的元数据。

不宣称跨多个 run 或“目录+两层外部清单”的全局事务。下载/校验失败不安装目标，异常临时目录不能被当作成功。归档目录安装后若外部清单更新失败，明确报 `ARCHIVE_WRITTEN_INDEX_PENDING` 并返回非零，保留已核验原件；重跑只在自有文件精确匹配时补完本次清单步骤，不重写原档。明确区分未归档、已有证据冲突、归档已写但索引待完成、全部完成。

原数据目录 `** -text` 保留；不修改忽略规则来开放所有日志/ZIP。按**明确路径**列出被 Git 忽略的原始附件，输出同一份完整文件清单供提交前显式 `git add -f` 和逐 Blob 核对。归档器不自动改 Git index/commit/push，也不把本机文件存在说成已提交。批次总 MANIFEST 与 datasets 总清单按实际新增归档做增量更新：添加新归档条目，并更新本轮确实改变的上层清单摘要，历史证据条目/冻结 manifest.json 不改。先核对旧清单及其当前字节，再准备新清单并逐文件原子替换；无关条目有冲突就拒绝，不能用全目录重算将改坏的旧文件合法化。清单未完成的重跑只允许与此次归档及已知清单更新完全对应的增量。

## 5. 新采集器来源：Git 引用而非每批副本

### 5.1 为什么不能只保留一个提交号

`collector_sha256` 目前是三份源文件散列映射的 fingerprint，并不是 commit。文件可能有本机换行或未提交改动，仅记录 HEAD 不足以证明当时运行的字节；import 又依赖旧 collectors 目录。本次必须一起处理“新引用”和“旧格式入口”，否则只是少写文件却失去追溯/导入能力。

### 5.2 新记录

保留候选既有 `collector_sha256`，新增可选 `collector_reference`：格式版本、完整 Git commit，以及“逻辑文件名 → 仓库路径 + 原字节 SHA256”。`collector_sha256` 仍由逻辑名到 SHA256 的映射按原 fingerprint 算法得到，不改变旧 digest 定义。

- 原生采集列出实际第一方 Python 源依赖闭包，包含抽取后的采集器来源模块；如果 data.py 顶层引入归档模块也纳入。不能仍硬编码三文件而遗漏新依赖。
- 在写请求/调用付费接口**之前**验证运行源与已提交 blob 字节一致、引用可解析；dirty 或仅换行不同也不能假借 HEAD。提示在已提交的 LF 快照运行，不自动提交/转换。只限制采集器源，不因无关文档或新输出目录而要求整个工作区清零。
- 响应回来后记录已验证来源，不执行可能首次发现来源错误的延后快照。新候选不再生成 collectors/*.py。
- 只读取 Git 对象，绝不根据响应字段自动执行历史采集器。指纹匹配不是执行授权。

### 5.3 新旧 import/验证

| 来源 | 处理 |
| --- | --- |
| 已有旧批次、三文件副本 | 原目录不变；按原 inputs.json/collector_sha256 校验原字节 |
| 将旧候选导入新批次 | 原始请求/响应/源码/父子关系不变；定位源批次已提交副本的 Git 路径与哈希，给新导入行记录引用，不再复制程序 |
| 新 Git 引用候选再导入 | 校验引用、commit/blob 与 digest 后携带同一采集来源；不用当前代码替换来源 |
| 源未提交、引用缺失、摘要失配 | 在创建目的批次/付费调用前拒绝，说明缺哪个输入；不悄悄退回当前采集器，也不写新副本兜底 |

导入后的工具链/oracle 仍由现有 prepare/import 重冻，四门仍全部 NOT_RUN，旧 imported_from 指纹和不迁移资格规则不变。新字段只是采集来源，不参与更改题目、Java 工具链或资格标签。既有 candidate/1 行允许没有新字段；旧数据的读取保持兼容，涉及旧副本的操作仍按旧格式验证，不重写旧行/manifest/学生文件。

`validate_candidates` 接入来源校验时只按实际格式分派，不假设历史行已有新字段；旧/新篡改均拒绝。旧无 collector 字段的已支持输入维持现有契约，不借此另立资格标准。

## 6. 模块与替代方案

| 方案 | 结论与代价 |
| --- | --- |
| data.py 直接粘贴临时脚本 | 不选：平台依赖、猜批次、覆盖/漏收问题都保留，文件与测试入口继续膨胀 |
| 新平台/通用插件下载归档框架 | 不选：只有 GitHub 这一实际来源，增加配置和维护，不解决资格边界 |
| CLI 小入口 + 专用内部模块，复用已有规则 | **选择**：一个归档命令承载核验与落盘；内部隔离只读 gh/Git I/O，ZIP和资格用真文件测试 |
| 继续每批复制三程序 | 不选：有冻结价值，但新批次持续膨胀且将漏掉拆出的依赖；旧副本留作历史 |
| 只留 HEAD/只留摘要 | 不选：不能同时定位代码与证明实际字节；选择 commit + 文件映射 + collector_sha256 |

拟修改：

- `experiments/slm/data.py`：注册 archive-gate；采集前来源预检、import/validate 的新旧分派。export/student 不放宽、不隐式调用。
- 新 `experiments/slm/gate_archive.py`：run/artifact 核对、计划、原字节归档与幂等；不引入 SIR 语义。以小函数组织，若接近800行再按已出现的职责拆，不先搭框架。
- 新 `experiments/slm/collector_provenance.py`：实际采集源与历史引用验证，替代继续复制程序的实现。
- `dataset_io.py`：共享有界 Git blob 读取/输入集合核对等真实双消费者能力；现有默认工作区校验与 export 语义保留。
- `experiments/slm/tests/`：新归档/来源测试，补现有 pipeline。生产 I/O在内部 seam 注入，单测禁止访问 GitHub/教师。
- `experiments/slm/README.md` 与实施记录：命令、Git引用格式、忽略附件、历史导出方式；`.github/workflows/verify.yml` 加入轻量 Python 定向套件，避免只有 Java CI 绿却未运行 Python。

当前 requirements 已固定 openai 2.24.0/jsonschema 4.26.0；不新增 Python 包，不装分词器/权重。CLI所需 gh/Git 显式列为工具要求。

## 7. 验收矩阵（本机离线结果见第9节，新源码 CI NOT_RUN）

先 RED 后 GREEN，只跑 Python 受影响套件；不在本机跑 Maven、业务 IT、数据库或应用。

| 类别 | 必验 |
| --- | --- |
| 正常归档 | 成功/业务失败的真形状 ZIP；元数据/绑定/原附件/JUnit原字节一致；失败不导出，成功也不偷偷导出 |
| 指定对象 | 错工作流、仓库、未完成、错 run/SHA/sample、重复 artifact、过期/缺失、错 checked/批次/源，均拒绝 |
| 资格与报告 | generated_digest/四门/task revision/检查数矛盾、无或多个报告、缺 IT/skip，不伪造 PASSED；旧 combinedSha256 不冒充版本化摘要 |
| 档案输入 | GitHub ZIP hash 错、路径穿越/驱动器/符号链接/大小写重名、限额、原始敏感串、附件缺失/改动均拒绝且无完成目标 |
| 重复与部分完成 | 同 run/attempt 精确重复 no-op；不同 attempt 分目录；同目标不同内容拒绝；索引更新中断按自有清单精确续完；后一个 run 失败保留先前完成结果 |
| 原字节与 Git | CRLF/空白/空日志不清洗；显式忽略附件提示；自有清单核验，与实际暂存/提交 Blob逐一对应，不仅测本机存在 |
| dry-run/I/O | 临时目录外输入树/index不变；API分页/超时/失败不重试；stderr 不泄露鉴权；Linux/Windows路径策略相同 |
| 采集来源 | 已提交源可定位；未提交/CRLF不一致在 mock教师调用前失败；完整依赖纳入；新候选不生成脚本副本 |
| 旧格式与 import | 旧副本真实摘要核对；旧导新/新导新不复制程序；旧数据字节不变、四门重置、付费调用为零；损坏引用在创建目标前失败 |
| 收录不放宽 | 原 corpus/dataset_io/pipeline/export/student 回归；不以来源字段新增或归档成功改变49条旧资格 |

用临时微型 Git 仓库与 ZIP/JSON 文件执行行为测试，mock仅替换外部 gh/付费调用，不把哈希/附件/资格检查 mock 掉。使用本机既有 #65/#66 原 ZIP只作离线读取对照；产物写 target，不改两份原档、不额外拉取运行。临时 Git fixture 不改项目分支/index/配置。

源码提交后，CI运行同套 Python 测试；不为文档设计单独触发 CI。verify #205 的正式通知及一次性 readback 已完成，但不代替本次新源码 CI。4.4正式收口后才按4.5另行清理临时交接目录。

## 8. 设计阶段实际结果

完成源码、冻结结构、原 #66 metadata/证据与忽略/换行规则的只读核对；仅新增本设计。一次 AFT scoped inspect 在150秒超时，没有 fresh 诊断，不记作通过。不运行新单测、构建、归档、外部查询或模型调用；合格仍49条、学生v4、未训练。

负责人已确认上述方案并授权实施；提交/推送仍待另行确认。


<a id="local-verification"></a>

## 9. 本机实施与验证（2026-10-31）

### 9.1 已实现与核对

实现显式 `archive-gate --bundle --checked [--dry-run] RUN...`；归档和 export 分开，成功也不隐式收录。`gate_archive.py` 编排明确对象，`gate_artifacts.py` 核验便携 ZIP、原附件、报告与 JUnit，`archive_index.py` 管理精确清单及两层增量更新，`git_source.py` 供归档/采集器共用只读 Git blob，`collector_provenance.py` 处理新引用和旧副本。按真实职责拆分，不引入平台框架或 Python 包。所有新生产文件均低于800行。

GitHub固定 `gh api --hostname github.com`，鉴权不进argv、不提取token，不暴露原stderr；下载和JSON读取有字节/时长上限，失败不自动重试，超限/超时终止并回收自有gh进程。Git读取禁用replace对象，不checkout/fetch，不执行引用程序。GitHub run/head_sha和collector引用均拒绝显式null、空串或缩写，不把坏输入回落成HEAD。

归档支持明确失败结论、精确重复no-op、分attempt目录、未知文件/原字节冲突拒绝、外层清单中断续完；摘要仍用版本化generated_digest，不拿旧combined摘要替代。原ZIP、日志、JUnit不清洗。新采集在写请求/付费调用前核对完整首方Python依赖闭包及Git原字节；旧导新/新导新保留来源引用、不写脚本副本，旧资格/四门重置及export规则未放宽。

旧CI核对为 [verify #205](https://github.com/HoloNova/Software-IR/actions/runs/41061629787)，SHA=`2282ad2e84776a9ed1f866488aed1dfa56872c48`，`gates-and-business-it`与`standalone-deployment`均success。仅读取run/jobs/artifacts元数据，未下载该CI的XML/ZIP，**不另报测试数量**。readback在`target/archive-gate-implementation/ci205/`；这不是本次新源码的CI结果。

### 9.2 TDD与失败处置

| 阶段 | 实际结果 | 根因与处理 |
| --- | --- | --- |
| 初始RED | 62项，61失败、0错误/跳过 | 缺archive-gate CLI/归档/来源实现；原有参数拒绝检查1项通过 |
| 首次GREEN范围 | 105项，0失败、10错误 | 8项遇到Git仅允许`-z`配合stdin；另2项无效路径在边界前严格resolve，先抛文件不存在；据原Git错误改为显式路径+UTF-8行输出、先判边界 |
| 同范围修正 | 105项全通过 | 原来源/四门/export/student回归一并通过 |
| 新边界范围 | 40项，1失败、0错误/跳过 | collector显式null提交被当成HEAD；补明确完整commit要求。分页/中断/ZIP等其余39项通过 |
| 扩展GREEN | 136项全通过 | stdlib trace行覆盖86.56%，没有安装覆盖率包 |
| API结构RED | 3项，1失败、2错误 | run.head_sha=null仍回落HEAD，仓库/附件对象畸形产生裸异常；补上游结构校验，不放宽判定 |
| 最终正式入口 | **139项，0失败/错误/跳过** | 调用与CI相同的`experiments.slm.tests.run_suite`，含CLI parse→dispatch→真实本地归档，以及原导出/学生规则 |

边界测试真实使用临时Git仓库/ZIP/JSON，只有外部API/传输和付费调用被mock，不mock附件、指纹或资格算法；临时Git提交不在项目主分支，不修改用户Git配置。覆盖率以CPython3.11标准库trace的可执行行统计：**总计86.66%**，九个统计文件各自均高于80%（git_source86.17、collector86.96、archive_index90.10、gate_artifacts85.71、gate_archive91.67、data83.60、dataset_io80.91、corpus90.00、正式测试入口91.30）。这不是分支覆盖率，不声称真实网络或Linux环境已运行。

原始日志/每轮summary在`target/archive-gate-implementation/{red,green-first,green-second,edges-first,green-final,metadata-red}/`；最终CI入口日志、summary、覆盖率和受测生产源哈希在`final-contracts/`。实际执行：`python target/archive-gate-implementation/verify_final.py`，内部调用正式`python -m experiments.slm.tests.run_suite --evidence ...`入口并加标准库trace。CI已接入同一正式入口，上传`slm-python-contracts`，提交后再看同SHA结果。

### 9.3 真实旧材料对照与保留边界

只读复用#65/#66已登记的原ZIP、GitHub元数据和受测Git提交，以原生命令dry-run验证：#65为STATIC/BUILD/START PASSED、BUSINESS FAILED，**401/25**；#66四门PASSED，**426/0**。不是重跑业务IT，不给它们补验收资格，也不访问GitHub。3963个Git跟踪数据文件及Git暂存区前后完全不变；验证脚本只调用原生命令，不另写归档/资格算法，结果在`target/archive-gate-implementation/existing-archives.json`。

本机是Windows/CPython3.11，未启动Maven/应用/数据库/模型权重，未调用教师、未训练、未提交推送；学生仍49条/v4。两次AFT scoped inspect均在150秒超时，无fresh诊断；查询未发现pyright/basedpyright或coverage包，未运行独立类型/格式检查器，不把索引错误计数或超时算作类型检查通过。实际Python导入/执行由139项测试验证；独立静态类型检查与新源码CI均 **NOT_RUN**。旧Java/POM工具链、oracle、所有冻结manifest和历史原件未改。临时交接清理4.5尚未执行。
