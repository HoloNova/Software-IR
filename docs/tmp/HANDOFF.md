# 交接文档（临时，任务完成后删除）

> **这是临时文件。** 第 4 节的待办全部完成、或负责人说不做了以后，用 `git rm -r docs/tmp` 删除整个目录（含两个脚本），单独提交一次（`docs: 移除交接文档`）。除本目录外没有文档链接到这里。
>
> 本文只写**还要做什么**和**怎么做**。已经做完的事在[数据报告](../qualification/SLM_DATA_PILOT_REPORT.md)（第一至二十四阶段）、[datasets/sir/README.md](../../datasets/sir/README.md) 和 [experiments/slm/README.md](../../experiments/slm/README.md)，这里不重复。

交接时的基线：`main` 的 `698b113`。最近一次工具链改动是 `2e4fa74`（只改诊断文字），[verify #202](https://github.com/HoloNova/Software-IR/actions/runs/40877385610) 和 [verify #203](https://github.com/HoloNova/Software-IR/actions/runs/40981119034) 均通过。上一版交接文档的 4.1～4.5 已全部完成；4.6 任务板规格始终没有推上来，转为第 5 节的待决定事项。

## 1. 先读什么

1. `AGENTS.md`（项目规范，**优先级最高**）：用中文回答；改代码前先讲清“为什么存在”并等负责人确认；不许假设；本机只跑定向测试，全量交给 CI；提交干净、一个提交一件事。
2. 数据报告第二十至二十四阶段：博客题的采样、修复和业务门 #65 的失败分析，是本文第 4 节的直接依据。
3. `experiments/slm/README.md`：`prepare / generate / repair / check / export / student / import` 各命令和 prompt 版本（当前默认 v8）。
4. `sir-semantic/src/main/java/io/kcg/sir/semantic/internal/ResolvePass.java` 的 `resolveNameExpression`（`:1143` 起，`any` 里裸名字怎么绑定），以及 `ValidatePass.java:1580–1630`（`persist ... else` 的现有规则和 `insertsIntoUniqueEntity`）。

## 2. 工作方式

- **审批**：负责人用“确认”批准。代码改动流程固定：先讲设计（为什么、替代方案、不做什么）→ 确认 → 写代码。负责人已授权：在 `main` 上直接工作；证据/数据归档类提交可以直接提交推送，不用先看 diff。
- **代理**：AGENTS.md 规定 openai-codex 只做设计和难题分析，**不直接改代码**。`2e4fa74` 的 Java 改动署名是 ChatGPT；如果不是负责人有意安排，以后代码改动由主会话完成。
- **提交**：`git commit --author="HoloNova <holonova25@gmail.com>"`，信息末尾写自己的 `Co-Authored-By`；提交前 `git diff --cached --check` 并读一遍暂存区。原始 CI 日志和原始 stdout 的行尾空白按证据原样保留，是已知的 `--check` 例外。
- **调用教师模型（花钱）**：`DEEPSEEK_API_KEY` 只检查存在，不打印。**每次调用前报预计 token，调用后报实际用量**（输入、其中缓存命中、输出、合计）。每个失败候选只修一轮，不自动循环。
- **静态通过 ≠ 合格**：只有 GitHub 上 `slm-business-gate` 四门全过、证据归档并 `export` 后才算合格教师答案。verify 里参考答案的通过不能借给候选。
- **CI**：`datasets/**`、`docs/**`、`**/*.md` 的推送不触发 verify。负责人说过“有结果我会通知你”，不要定时轮询 CI。
- **定向测试模板**（`-am` 不能省；容器自带的 `JAVA_TOOL_OPTIONS` 会让 `GeneratorDeterminismMatrixTest` 误报，所以去掉它）：

  ```bash
  env -u JAVA_TOOL_OPTIONS MAVEN_OPTS=-Xmx2g mvn -B -o -pl <模块> -am test \
    -Dtest='<测试类>' -Dsurefire.failIfNoSpecifiedTests=false
  ```

  改了 `docs/SIR_LANGUAGE_REFERENCE.md` 要单独跑 `LanguageReferenceExamplesTest`。在 Windows 机器上跑 `kcg check` 时用 LF 换行的检出（第二十三阶段的做法），否则源码哈希对不上。
- **业务门**：GitHub Actions 手动触发 `slm-business-gate.yml`，输入 `bundle`、`sample_id`、`it`。批次必须先提交推送。
- **归档业务门**：`python3 docs/tmp/archive_gate_runs.py [--dry-run] <run_id>...`（说明见脚本开头）。第二十二至二十四阶段没有用它，每次另写了一次性脚本放在 `datasets/sir/research/...`；以后用这个脚本，或按 4.4 把它收进 `data.py`，不要再新写。
- **改了 `datasets/sir/README.md`** 必须同步 `datasets/sir/MANIFEST.sha256` 里 README 那一行的哈希，再 `(cd datasets/sir && sha256sum -c MANIFEST.sha256 --quiet)`。

## 3. 交接时的状态（只列数字）

- 合格教师答案 **48 条**，学生格式 `datasets/sir/student/train-student-v3.jsonl`（48 条），未训练。
- 73 道题（train 67、development 2、test 4）中 **26 道没有合格答案**：选课改写/从零写及其变体 7 道、博客 2 道、dev 2 道、test 4 道（从未采样），其余 11 道是鉴权（8 道）和课程切片（3 道）的个别变体。
- 博客两题：v7、v8 各采一次并各修一轮，全部失败；v8 共 4 次调用 53,777 tokens。两条分支都已停止，**没有待判卷的候选**。

## 4. 待办（按顺序）

### 4.1 两条新的静态检查（先出设计给负责人确认，再写代码）

**为什么要做。** 业务门 #65 的 25 条失败断言只有两个原因，都能通过现在的静态检查，要到业务门才暴露（报告第二十四阶段）。修一轮运行期错误花了 24,257 tokens，教师补上一处，又在另一处引出语法错误。这两类错误在选课从零写系列里也反复出现。让编译器直接指出，比靠模型从业务失败里猜原因便宜得多，所有用到关联筛选和唯一约束的题都受益（AGENTS.md 设计规则第 3 条）。

**规则 A：`any` 里比较两边是同一个字段。** 例：`any(PostTag, post == item and tag == tag)`。在 `any(E, ...)` 里，裸名字先绑定到 E 的字段（`ResolvePass.resolveNameExpression`），所以两个 `tag` 都是 `PostTag.tag`，条件恒为真，外层加载的变量 `tag` 被悄悄忽略。选课候选里同样出现过 `not any(CompletedCourse, student == student and ...)`，先修课检查因此没有比对学生。

- 建议判定：比较（`==`、`!=`）两侧绑定到**同一个符号**时报错。提示要说清两边都是 E 的字段 `tag`；想用外层同名变量，就给外层变量改名（参考答案用的是 `wanted`）。可以顺带覆盖普通 find 条件里 `item.x == item.x` 的写法。
- **不要**做成“裸名字和外层变量同名就报错”：图书馆参考答案的 `ReserveBook` 写了 `any(Loan, copy == item and reader == actor ...)`，外层同时有变量 `reader`，这个写法是对的，会被误伤。
- 实现位置：`TypePass` 处理 `any` 的地方（`:301` 附近），或 `ValidatePass` 的比较检查。诊断码优先复用已有的，不新增；先查语言参考末尾的诊断码表。

**规则 B：查过重复，却没处理并发撞重。** 例：AttachTag 先 `exists PostTag where ... as attached; validate not attached else TagAttached;`，再 `create PostTag as link {...}; persist link;`。两个请求同时通过 exists 检查时，后插入的撞上 `unique (post, tag)`，返回 500 而不是 409。

- 建议判定：工作流里有 `exists E where ...`，又 `create` 了 E，且 E 有唯一约束（用现成的 `insertsIntoUniqueEntity`），那么这一行的 `persist` 必须写 `else`。用 `SIR-VALID-004`，和 `versioned` 实体“条件更新必须写 else”的规则放在一起（`ValidatePass:1610` 附近）。提示里建议 `else` 用前面 `validate not ... else` 的同一个错误。
- **不要**做成“凡是新建唯一实体的行都必须写 else”：现有 19 处参考答案和测试资源（注册、建课、建分类、加房间等）没写 else，改成硬规则要连带改参考答案和冻结的题目。负责人如果想要这条更宽的规则，单独设计。

**影响范围（已扫描，可复现）。** `python3 docs/tmp/rule_impact_scan.py` 扫仓库里全部 `.sir`、Java 文本块和 Markdown 示例共 304 份，**两条规则都是 0 命中**，参考答案和测试资源不用改。加 `--history` 扫历史教师候选 171 份：**28 份命中**（规则 A 15 处，规则 B 83 处），全在博客、选课从零写、乘法（dev）三组里，**48 条合格答案无一命中**。这是正则近似，实现后以语义检查为准。

**要改的文件。** 两个检查的实现；`sir-semantic` 里每条规则各一对正反例单测；`docs/SIR_LANGUAGE_REFERENCE.md` §5.3 的“关联存在性：`any`”（:1252 附近）和 §5.5 的“新建的行撞上已有的行”（:1321 附近）各加一句说明，改完跑 `LanguageReferenceExamplesTest`。代码、测试、文档放在同一个提交。定向测试：`-pl sir-semantic -am -Dtest='<新测试类>,QueryDiagnosticMessageTest'`，全量交给 CI。

**时间约束。** 这次改动会改变工具链摘要。现在没有待判卷的候选，可以直接推送。

### 4.2 用新工具链重查失败候选，确认检查生效（不花钱）

用 `import` 把 `blog-change-g8`、`blog-scratch-g8` 的失败候选导入新批次（如 `blog-change-g9`），在新工具链上 `check`，确认 `906a864f71bf` 报出规则 A 和规则 B，`718c0d88dafa`、`215ebacdb9b3` 报出规则 A。

```bash
python3 -m experiments.slm.data import --from-bundle datasets/sir/pilots/blog-change-g8 --bundle datasets/sir/pilots/blog-change-g9
python3 -m experiments.slm.data check --bundle datasets/sir/pilots/blog-change-g9
```

结果写进报告第二十五阶段。然后向负责人报一轮**静态**反馈修复的预计 token（参考第二十三阶段，单份约 11.6K），确认后每份修一轮；静态通过的再送业务门。静态修复的花费不到运行期修复的一半，也不用等业务门。

### 4.3 语法报错改进（建议负责人提高优先级，先出设计）

教师修不动的错误里，语法报错占了大头：分页 `find` 漏 `else 错误`、约束之间漏逗号（第十二阶段）、在 find 条件里嵌 `exists`（第二十四阶段）。现在的报错只有“遇到不符合 SIR 语法的内容：xx”，没说缺了什么、该怎么写。设计时先从 `datasets/sir` 里所有 `SIR-PARSE-001` 的检查记录统计出现最多的几种，按频次挑前三种给出具体提示。**这一项等负责人确认后再开始。**

### 4.4 整理归档脚本（代码改动，先讲原因）

- 把 `docs/tmp/archive_gate_runs.py` 收进 `experiments/slm/data.py`，作为 `archive-gate` 命令（带单测），以后各阶段不再写一次性脚本。
- `blog-change-g8`、`blog-scratch-g8` 各存了一份 `data.py`/`corpus.py`/`dataset_io.py` 副本（约 800 行）。提交号和 `collector_sha256` 已经能锁定来源，以后的批次不再复制。已有副本属于冻结数据，不删。

### 4.5 收尾

4.1～4.4 完成或负责人说不做后，删除 `docs/tmp`，单独提交。

## 5. 待负责人决定（不要自行开始）

- **训练学生模型**：48 条、v3 数据已就绪，模型、样本量、评分方式未定。
- **test 集采样**：finance、library 的 4 道题从未采样；它们用来做最后评估，什么时候采由负责人定。
- **任务板题**：规格一直没推上来，继续做还是放弃。
- **更宽的唯一约束规则**：凡是新建唯一实体的行都必须写 `else`（见 4.1 规则 B 的“不要”）。
- **局部更新里显式传 null 而目标非可选**：目前只有金额有约束兜底，其他类型可能 500。
- **`Name`/`Code` 缺值报 `NotBlank` 还是统一 `NotNull`**：目前保持 `NotBlank`。

## 6. 需要留意的坑

- **工具链绑定**：候选的静态结果绑定 `toolchain_core_sha256`。工具链一变，未判卷的候选必须 `import` 到新批次重查，否则业务门会因绑定不一致失败。
- **task 文件冻结后不要改**：改动会让批次的 `task_sha256` 失配；`import --task` 只允许 `oracle.files` 不同。各题的 `reference_evidence` 不随新 verify 更新，新证据写进报告。
- `write_jsonl`／`write_new_json` 以独占方式新建文件，目标已存在会报错，不会覆盖。
- 教师有时把修复后的代码原样交回，或只改一行（第二十三阶段），这种不送业务门，报告里注明。
- 运行期修复可能引入新的语法错误（第二十四阶段把 `any` 改成了嵌入的 `exists`）。能用静态诊断说清的错误，优先补检查，不靠运行期修复。
- 生成器在工作流方法里新增的局部变量一律用 `$` 前缀（SIR 标识符不含 `$`），否则会和作者变量撞名。
- 业务门里“应用启动失败（`KCG-ACTOR-STARTUP-001`）”通常是候选给合同里不要求登录的接口加了认证，不是环境问题。
- Actions 原始聚合日志 `failed.log` 含 CI 环境口令行，只留本机，不进归档（归档脚本会检查 `kcg-ci-`）。
