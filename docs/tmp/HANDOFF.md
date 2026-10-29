# 交接文档（临时，任务完成后删除）

> **这是临时文件。** 下面第 4 节的待办全部完成、或负责人说不做了以后，用 `git rm -r docs/tmp` 删除整个目录（含 `archive_gate_runs.py`），单独提交一次（`docs: 移除交接文档`）。除本目录外没有文档链接到这里。
>
> 本文只写**还要做什么**和**怎么做**。已经做完的事在[数据报告](../qualification/SLM_DATA_PILOT_REPORT.md)（第一至十二阶段）、[datasets/sir/README.md](../../datasets/sir/README.md) 和 [experiments/slm/README.md](../../experiments/slm/README.md)，这里不重复。

交接时的基线：`main` 的 `391eb87`，verify [#198](https://github.com/HoloNova/Software-IR/actions/runs/40679190786)（`5cff5c3` 必填改动）与 [#199](https://github.com/HoloNova/Software-IR/actions/runs/40680535123)（`391eb87` 生成器变量名修复）均已通过。

## 1. 先读什么

1. `AGENTS.md`（项目规范，**优先级最高**）：用中文回答；改代码前先讲清“为什么存在”并等负责人确认；不许假设；本机只跑定向测试，全量交给 CI；提交干净、一个提交一件事。
2. `docs/qualification/SLM_DATA_PILOT_REPORT.md` 第七至十二阶段：小模型数据管线的来龙去脉。
3. `experiments/slm/README.md`：`prepare / generate / repair / check / export / student / import` 各命令和 prompt 版本说明。
4. `experiments/slm/drafts/` 下的分析与设计：`prompt-audit-general-v5.md`、`teacher-error-census.md`、`course-selection/failure-analysis.md`、`design/required-input-design.md`、`blog/spec.md`、`new-problem-survey.md`。

## 2. 工作方式

- **审批**：负责人用“确认”批准。代码改动流程固定：先讲设计（为什么、替代方案、不做什么）→ 确认 → 写代码。负责人已授权：在 `main` 上直接工作；证据/数据归档类提交可以直接提交推送，不用先看 diff。
- **提交**：`git commit --author="HoloNova <holonova25@gmail.com>"`，信息末尾写自己的 `Co-Authored-By`；提交前 `git diff --cached --check` 并读一遍暂存区。多步脚本用 `set -e`，避免前一步失败后半截提交。
- **调用教师模型（花钱）**：`DEEPSEEK_API_KEY` 只检查存在，不打印。**每次调用前报预计 token，调用后报实际用量**（输入、其中缓存命中、输出、合计），不自动循环修到通过。每个失败候选只修一轮。
- **静态通过 ≠ 合格**：只有 GitHub 上 `slm-business-gate` 四门全过、证据归档并 `export` 后才算合格教师答案。
- **CI**：`datasets/**`、`docs/**`、`**/*.md` 的推送不触发 verify；`experiments/` 下的 `.py/.json/.sir` 会触发。负责人说过“有结果我会通知你”，不要定时轮询 CI。
- **定向测试模板**（`-am` 不能省；容器自带的 `JAVA_TOOL_OPTIONS` 会让 `GeneratorDeterminismMatrixTest` 误报，所以去掉它）：

  ```bash
  env -u JAVA_TOOL_OPTIONS MAVEN_OPTS=-Xmx2g mvn -B -o -pl <模块> -am test \
    -Dtest='<测试类或包，如 io.kcg.sir.generator.springboot.**>' -Dsurefire.failIfNoSpecifiedTests=false
  ```

  云端容器里 `systemd-run` 存在但不能用（没有 systemd 作为 PID 1），改用单线程 + JVM 内存上限，并在报告里写明 `nproc`/`free`。`GeneratedProjectOfflineCompilationTest` 的 10 个用例在本机必然失败（本地仓库没有 Spring Boot 依赖），以 CI 为准。改了 `docs/SIR_LANGUAGE_REFERENCE.md` 要单独跑 `LanguageReferenceExamplesTest`。
- **业务门**：GitHub Actions 手动触发 `slm-business-gate.yml`，输入 `bundle`、`sample_id`、`it`（12 个业务 IT 之一）。每个样本一个并发组，可以同时触发多个。批次必须先提交推送。
- **归档业务门**：`python3 docs/tmp/archive_gate_runs.py [--dry-run] <run_id>...`。它下载两个 artifact、核对 zip 哈希与 GitHub 记录、检查不含 CI 口令、核对证据与候选的绑定哈希，复制到 `<批次>/runtime/<样本>/` 并写 `gate-run.json`，通过的自动 `export`。run id 可用 `curl -sS "https://api.github.com/repos/HoloNova/Software-IR/actions/workflows/slm-business-gate.yml/runs?per_page=40"` 列出（仓库公开，列表不需要令牌；下载 artifact 需要 `GITHUB_TOKEN` 或 `gh auth`）。
- **改了 `datasets/sir/README.md`** 必须同步 `datasets/sir/MANIFEST.sha256` 里 README 那一行的哈希，再 `(cd datasets/sir && sha256sum -c MANIFEST.sha256 --quiet)`。
- **学生训练数据**：所有合格批次跑一次 `python3 -m experiments.slm.data student --bundle <所有有非空 train-teacher.jsonl 的批次> --output datasets/sir/student/train-student-vN.jsonl`，再用 `experiments/slm/profile_tokens.py` 的 `measure` 量长度（tokenizer 按 `datasets/sir/research/2026-10-25/*--model-api.json` 的修订版从 Hugging Face 下载到临时目录）。

## 3. 交接时的状态（只列数字）

- 合格教师答案 **44 条**（鉴权 23、课程 21），学生格式 `datasets/sir/student/train-student-v2.jsonl`（44 条，最长 3,874 / 3,042 tokens）。
- train 16 份原题中只有 **course-selection 的改写、从零写**没有任何合格答案；crud-access 从零写（原题有、3 种新说法都没有）次之。
- dev 集（shop.multiplication）两题在 v6 + 一轮修复后仍静态失败；test 集（finance、library）还没采样。
- 题目：`experiments/slm/tasks/` 共 71 份（23 份原题 + 48 份需求变体 `*-p[1-3]-v1.json`）。

## 4. 待办（按顺序）

### 4.1 course-selection 改写 p1 在新工具链上重新判卷

候选 `course-course-selection-change-p1-v1-teacher-e61b238d030f`（批次 `datasets/sir/pilots/course-selection-change-p1-g6`）静态通过，业务门构建失败的原因是生成器变量撞名（已由 `391eb87` 修复）。旧证据绑定旧工具链，不能直接重跑，要导入新批次：

```bash
python3 -m experiments.slm.data import --from-bundle datasets/sir/pilots/course-selection-change-p1-g6 \
  --bundle datasets/sir/pilots/course-selection-change-p1-g6t
python3 -m experiments.slm.data check --bundle datasets/sir/pilots/course-selection-change-p1-g6t
```

（本机先 `mvn -B -q -pl kcg-cli -am compile`，检查器用各模块的 `target/classes`。）`import` 不调 API、不花 token。若 `e61b238d030f` 静态通过：提交推送 → 触发业务门（IT 为 `CourseSelectionBusinessConformanceIT`）→ 负责人通知结果后用归档脚本归档。注意新规则下非可选字段一律必填，这份候选可能连带改善或暴露别的问题，以判卷报告为准。

### 4.2 报告补第十三阶段（纯文档，不触发 CI）

`docs/qualification/SLM_DATA_PILOT_REPORT.md` 记录：

- `5cff5c3` 非可选输入一律必填（设计 `experiments/slm/drafts/design/required-input-design.md`），verify #198；
- `391eb87` 生成器内部局部变量统一 `$` 前缀，verify #199（新增 `GeneratedLocalNamesTest`，旧生成器报 6 处重复定义）；
- 两次改动改变了工具链摘要：之后的候选都要在新工具链上检查；已归档的 44 条不受影响；
- **各题 task 里的 `reference_evidence` 不改**：task 文件一改哈希就变，已冻结批次和 `import` 的“同一道题”检查都会失效。参考答案在新工具链上通过的证据是 verify #199，写进报告即可；
- 4.1 的结果。

同时更新 `datasets/sir/README.md` 对应段落与 MANIFEST。

### 4.3 规则手册 v7（先给负责人看改动）

在 `experiments/slm/prompts/` 新建 `sir-0.3-general-v7.md`（已用过的 prompt 文件不改）。以 v6 为底稿，只改与新行为相关的部分：

- v6 第 38 行“数值缺值”整段改成新规则：非 Optional 输入一律必填（缺值 400 `NotNull`，`notBlank` 文本报 `NotBlank`）；只作分页参数的 page/size 有默认值；不再需要、也不要写 `input.x - 1 >= 0` 这种绕弯写法；
- 第 39 行分页说明同步；
- 依据：`docs/SIR_LANGUAGE_REFERENCE.md` §3.3“普通输入的必填规则”与 §6“算术用到的输入同样必填”。

把 v6→v7 的差异讲给负责人，确认后提交，并在 `experiments/slm/README.md` 的“Prompt 版本”一节补一段。

### 4.4 用 v7 补采（花钱，先报预计 token 等确认）

建议范围：course-selection 两题及其 6 份变体、crud-access 从零写及 3 份变体、dev 集两题（每份 1 次）。参考用量：v6 一轮平均每次约 5.5K 输入、1.1K 输出，course-selection/multiplication 这类大题约 8～13K 输入、3～6K 输出。流程同第十二阶段：`prepare --system-prompt experiments/slm/prompts/sir-0.3-general-v7.md` → `generate --count 1 --max-tokens 8192` → `check` → 静态失败用 `repair --feedback experiments/slm/prompts/repair-general-v1.md` 修一轮 → 提交推送 → 业务门 → 归档 → `student` 出新一版。

### 4.5 博客题（新 family `content.blog`，train）

规格 `experiments/slm/drafts/blog/spec.md` 已审核通过，可直接照着做。**写代码前先向负责人讲清业务 IT 的结构并等确认。** 要产出：

- 裁剪后的参考答案 `sir-toolchain-application/src/test/resources/valid/blog.sir`（规格 §1，不能直接用整个 `topics/blog/project.sir`）；
- `conformance/mysql/blog-ddl.sql`、`blog-seed.sql`（规格 §2、§3）；
- `BlogBusinessConformanceIT.java`，复用 `BusinessSliceHarness`，检查清单见规格 §6；在 `slm-business-gate.yml` 的 `it` 选项和属性映射里加上它，并按现有 IT 的方式接入 `verify.yml`；
- 改写题 base（规格 §1.3：删 Publish、AttachTag、PostsByTag、LikePost 四个能力）放 `experiments/slm/drafts/blog/base.sir`，需求原文放 `requirement-change.md`／`requirement-scratch.md`，`contract.json`、`traceability.md` 仿照 `drafts/token-desk/`；
- `experiments/slm/tasks/blog-change-v1.json`、`blog-scratch-v1.json`（`oracle.files` 用 IT 的传递闭包；`reference_evidence` 填参考答案通过的那次 verify）。

收口顺序（规格 §7 末尾）：参考答案先过四门 → 确认 base 静态可用且过不了新增接口检查 → 冻结 task → 采样。

### 4.6 任务板题

另一台机器的 agent 负责写 `experiments/slm/drafts/task-board/spec.md`（prompt 与博客规格相同，只换软件路径 `topics/tasks/project.sir` 和目录名）。推上来后先审核，再按 4.5 的方式实现。

## 5. 待负责人决定（不要自行开始）

- **训练学生模型**：数据格式已就绪（`student` 命令、v2 数据），但模型、样本量、评分方式都还没定。
- **编译器语法报错改进**：教师对“遇到不符合 SIR 语法的内容：as”这类报错修不动（第十二阶段：分页 `find` 漏 `else 错误`、约束之间漏逗号）。可以让解析器直接说缺了什么，需要先出设计。
- **局部更新里显式传 null 而目标非可选**：目前只有金额有约束兜底，其他类型可能 500。设计文档 §7 列为下一条规则。
- **`Name`/`Code` 缺值报 `NotBlank` 还是统一 `NotNull`**：本次保持 `NotBlank`。

## 6. 需要留意的坑

- **工具链绑定**：候选的静态结果绑定 `toolchain_core_sha256`。工具链一变，未判卷的候选必须 `import` 到新批次重查，否则业务门会因绑定不一致失败。
- **task 文件冻结后不要改**：改动会让批次的 `task_sha256` 失配；`import --task` 只允许 `oracle.files` 不同。
- `write_jsonl`／`write_new_json` 以独占方式新建文件，目标已存在会报错，不会覆盖。
- 变量名 `B=$(for …; do [ -s "$f" ] && …; done)` 这类写法最后一项为假时整体返回 1，配合 `&&` 会让后续命令静默不执行；在循环后加 `; true`。
- 教师有时把修复后的代码原样交回（逐字节相同），这种不送业务门，报告里注明。
- 生成器在工作流方法里新增的局部变量一律用 `$` 前缀（SIR 标识符不含 `$`），否则会和作者变量撞名。
- 业务门里“应用启动失败（`KCG-ACTOR-STARTUP-001`）”通常是候选给合同里不要求登录的接口加了认证，不是环境问题。
