# SIR 实验数据

## 首批采样与后续运行

[`pilots/dual-token-v1/`](pilots/dual-token-v1/)：2026-10-25的公开训练种子题试跑，包含1份既有参考、3个真实教师初始候选、同一分支2次反馈修复。初始候选0/3静态通过；最终修复候选通过完整静态生成。该原始批次BUILD/START/BUSINESS保持 **NOT_RUN**，不回写旧资格；`train-qualified.jsonl`是当时的空导出。后续Linux真实验收见下节，当前仍0条新业务合格教师答案。

- `task.json`：公开题目、固定接口与业务家族，split=train，不是盲测。
- `manifest.json`：冻结输入、工具链/oracle的源码指纹和Git基线。未提交实验适配器由文件指纹单独绑定，不能只凭Git HEAD或旧CI结果推断已验收。
- `candidates.jsonl`、`samples/`：答案、origin、模型/请求/响应/用量与源码哈希；feedback-repair记录parent_id。
- `checked.jsonl`、`checked-r1.jsonl`、`checked-r2.jsonl`、`checks/`：每轮真实CLI静态结果，不覆盖首轮失败。
- `collectors/`：首次反馈修复前及后续收集器快照；新增repair调用记录collector_sha256。首批初始采样没有该新增字段，不能声称已有逐调用的固定代码revision绑定。
- `pilot-summary.json`：按实际调用、初始候选与修复轨迹分开的统计。
- `verification/`：Java最新XML摘要、Python31项按模块最新结果与Python依赖版本；不是新候选的真库验收证据。
- [`research/2026-10-25/`](research/2026-10-25/)：轻量官方模型卡/配置、模型目录元信息和真实分词器长度结果，不含模型权重与大分词器缓存。

## Linux 参考环境实际验收

[`runs/dual-token-lab-v1/`](runs/dual-token-lab-v1/)：在负责人指定的`ssh lab`上实际执行原DualTokenBusinessConformanceIT，未改oracle。参考四门全PASSED、35/0；教师`ef9ddc30eb79`静态/构建/启动PASSED、业务FAILED（19/14）。根因是先把尚未生成的令牌UUID拷入pair，后persist才赋UUID，正确登录500；不是平台不可达。新API调用0次。

- `runtime/`保存两次原始XML、业务/HTTP报告、构建/应用日志及绑定的`slm-runtime.json`；临时数据库账号/密码不在归档中。
- `train-teacher.jsonl`为0条；`reference-seed-qualified.jsonl`为1条既有参考，不能冒充新教师样本或评测成绩。
- `verification/`保存完整源码清单、运行脚本、两处准备失败、最终清理证明、实际生成Java、oracle源码快照与摘要/文件哈希索引。
- 本次历史入口是SSH原生systemd scope，不是Forgejo Actions或ML API镜像；该临时目录后按负责人要求删除。后续Forgejo仅局域网直连，任务通过公网ML API派发；工具指南仅以独立Prompt文本提供，不依赖SSH。

## 编译器补漏后的复查

[`runs/dual-token-recheck-v2/`](runs/dual-token-recheck-v2/)：`bca868b` 新增 `SIR-FLOW-007` 并改进实体成员顺序报错后，从首批导入5份教师原始响应、在当前工具链上重做静态检查，API 调用0次。只有既有参考通过；`ef9ddc30eb79` 现在在静态门因先读未 persist 行的主键而失败，正是 lab 批次在真库里发现的错误。所有运行门仍为 NOT_RUN，见[数据报告第三阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#第三阶段编译器补漏后的复查与-prompt-v22026-10-25)。

## prompt v2：反馈修复与新初始批次

2026-10-26，4次真实 API 调用（合计 input 14820、output 4425、total 19245 tokens）。

- [`runs/dual-token-recheck-v2/`](runs/dual-token-recheck-v2/) 新增1次 v2 反馈修复：`ef9ddc30eb79 → bd870a834f77`，只把 persist 挪到读主键之前，`checked-r1.jsonl` 里完整静态生成通过。
- [`pilots/dual-token-v2/`](pilots/dual-token-v2/)：用 `prompts/sir-0.3-auth-v2.md` 新建的初始批次，1份既有参考 + 3份教师初始候选；参考通过，3份候选静态失败（成员顺序、Logout 返回类型、自创内联构造各1份）。

两处的 BUILD/START/BUSINESS 都是 **NOT_RUN**：可复用的业务门执行环境尚未建立，静态通过不等于业务正确，新教师合格训练答案仍为0条。详见[数据报告第四阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#第四阶段v2-反馈修复与-v2-初始采样2026-10-26)。

## GitHub 业务门

[`runs/dual-token-gate-v1/`](runs/dual-token-gate-v1/)：判卷代码改到共用底座后，用 `import --task` 从复查批次重新导入（API 调用0次），静态结果与复查批次一致。`bd870a834f77` 经 [slm-business-gate #1](https://github.com/HoloNova/Software-IR/actions/runs/40330717774) 实际判卷：静态/构建/启动 PASSED，业务 FAILED（34/1），唯一失败是登出返回 201 而非 200，因为候选在登出时新建了一行 TokenPair。证据在 `runtime/`，`gate-run.json` 记 run URL、提交 SHA 与 artifact 哈希；`train-teacher.jsonl` 为0条。详见[数据报告第五阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#第五阶段github-上的可复用业务门2026-10-26)。

同一批次随后对 `bd870a834f77` 做了1次业务门反馈修复（`repair --runtime-evidence`，input 12480、output 1199 tokens）：新候选 `e2c157051e10` 只把 Logout 改成 query，静态失败（query 必须只读），见 `checked-r1.jsonl` 与[数据报告第六阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#第六阶段业务门反馈修复与-22-份新题2026-10-28)。新教师合格训练答案仍为0条。

## 通用 prompt 试采样与首批合格答案

`pilots/` 下的 `*-g1`～`*-g4` 是用通用 system prompt（`sir-0.3-general-v1`～`v4`）对 train 集三道题的试采样批次，每个批次 1 份教师候选。`bearer-credential-change-g1` 的 `07b9ea18ef67`、`query-slice-scratch-g3` 的 `cadd5a148bb8` 和 `write-slice-change-g4` 的 `51ab2996a20e` 经 GitHub 业务门四门全部通过，证据在各自的 `runtime/`，`train-teacher.jsonl` 各 1 条，**这是首批 3 条新教师合格训练答案**。详见[数据报告第七阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#第七阶段通用-prompt-试采样与首批合格教师答案2026-10-28)。

## v4 跑完 train 集一轮

固定用 `sir-0.3-general-v4` 对 train 集其余 13 份题目各采 1 次（`pilots/*-g4`）：静态通过 7 份，四门通过 6 份（crud-access 改写、guarded-transition 从零写、token-desk 两种题型、query-slice 改写、relation-slice 改写），证据在各批次 `runtime/`，`train-teacher.jsonl` 各 1 条。relation-slice 从零写业务失败（漏了 ACTIVE 条件），证据同样保留。**新教师合格训练答案累计 9 条。**详见[数据报告第八阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#第八阶段通用-prompt-v4-跑完-train-集一轮2026-10-28)。

## v5 补采与静态修复

`pilots/*-g5` 是用 `sir-0.3-general-v5` 对 v4 一轮未合格的 7 份题目的补采，静态失败的 6 份又用 `repair-general-v1` 各修一轮（结果在各批次 `checked-r1.jsonl`）。四门通过 3 份：relation-slice 从零写、guarded-transition 改写、write-slice 从零写，`train-teacher.jsonl` 各 1 条；业务失败 3 份，证据同样保留在 `runtime/`。详见[数据报告第九阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#第九阶段v5-补采与通用静态修复2026-10-28)。

随后对业务失败的 3 份用 `repair --runtime-evidence` 各修一轮（结果在 `checked-r2.jsonl`）：bearer-credential 从零写 `efbdfe58ef70` 与 crud-access 从零写 `3062e8f8e07b` 四门通过，证据在 `runtime/`，`train-teacher.jsonl` 各 1 条；course-selection 改写的修复结果与原候选逐字节相同，未送业务门。**新教师合格训练答案累计 14 条。**详见[数据报告第十阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#第十阶段业务失败候选的运行反馈修复2026-10-28)。

## v6 与需求变体

`pilots/*-g6` 是用 `sir-0.3-general-v6` 对 48 份需求变体题（train 16 份题目各 3 种说法）与 dev 集 2 题的采样；静态失败的又各修一轮（`checked-r1.jsonl`）。36 份送业务门，30 份四门通过，证据在各批次 `runtime/`，`train-teacher.jsonl` 各 1 条。**新教师合格训练答案累计 44 条。**详见[数据报告第十二阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#第十二阶段规则手册-v6-与需求变体2026-10-28)。

## 新工具链上的选课候选复查

[`pilots/course-selection-change-p1-g6t/`](pilots/course-selection-change-p1-g6t/) 从 v6 的选课改写 p1 导入原始响应，API 调用 0 次。普通非 Optional 输入必填（`5cff5c3`，verify #198）及生成器 `$` 局部变量（`391eb87`，verify #199）已修复并通过 CI；新批次在与 Git blob 字节一致的 LF 源码快照中重新冻结工具链、编译检查器并静态检查。参考与教师修复候选 `e61b238d030f` 均完整生成 95 个文件，原始教师 `e7bb44f7a3e1` 仍在解析阶段失败。同一候选在受测提交 `bfd7d768` 的 [业务门 #61](https://github.com/HoloNova/Software-IR/actions/runs/40795909868) 四门全通过、152/0，已核对 ZIP/绑定/附件哈希并归档 `runtime/`，`train-teacher.jsonl` 导出 1 条。**新教师合格答案累计 45 条**（鉴权 23、课程 22），首次有合格的选课改写答案；原始请求、响应、源码及 task 的 `reference_evidence` 不变。学生 v2 仍为当时的 44 条，待下一批判卷结束统一更新。字节核对见批次 `verification/checkout-verification.json`，说明见[数据报告第十三阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-toolchain-recheck-v1)。

## v7 定向首轮（尚无新增合格答案）

负责人确认预算后，14 个 `pilots/*-g7/` 新批次完成 14 次初始请求：13 份答卷全部静态失败（10 PARSE、3 SEMANTIC），乘法改写 1 次 APIConnectionError 无响应、用量未知，未自动重试。14 份参考静态通过，原始请求／响应、候选、诊断及冻结来源均保留；汇总见 [`research/2026-10-29/v7-initial-sampling.json`](research/2026-10-29/v7-initial-sampling.json)，原因与逐次用量见[数据报告第十四阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-prompt-v7-initial)。已知输入 91,358（缓存命中 43,776）、输出 36,182，合计 127,540 tokens；失败网络请求的未知用量不算 0。未调用反馈修复或派发业务门，新增合格答案 0，累计仍为 45；dev 两题不进入学生训练。本阶段尚未执行修复；后续获批的一轮修复见下节，不手改答案或放宽判卷。

## v7 一轮修复与补采（新增 3 条合格答案）

13 份失败答案各修一轮，并补采网络失败的乘法改写题一次，14 次均收到响应。结果保存在 `checked-r1.jsonl`／`checked-resample.jsonl`，首轮结果与所有原始附件不覆盖。3 份 CRUD 静态通过：原题 `4a6b10922574`（58 文件）、p2 `976fd70bd19f`（58）、p3 `3a97ea60acea`（55）；其余 11 份失败，其中 4 份修复源码与父候选完全相同。只将 3 份通过者提交后派发业务门：受测 SHA `e1a51bb775aaeab52d30b9dd3d1758f74e4c0a7d`，[#62](https://github.com/HoloNova/Software-IR/actions/runs/40801380578)、[#63](https://github.com/HoloNova/Software-IR/actions/runs/40801384180)、[#64](https://github.com/HoloNova/Software-IR/actions/runs/40801387816) 各四门全 PASSED、48/0，原始证据已归档，`train-teacher.jsonl` 各导出 1 条。**合格教师答案累计 48 条（鉴权 26、课程 22）**；汇总见 [`v7-business-gate-summary.json`](research/2026-10-29/v7-business-gate-summary.json) 和[第十六阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-prompt-v7-qualified)。dev 两题均失败且不进入 train，未追加第二轮修复。

本轮输入 156,701（缓存命中 139,392）、输出 41,707，合计 **198,408 tokens**。汇总见 [`research/2026-10-29/v7-repair-sampling.json`](research/2026-10-29/v7-repair-sampling.json)，逐次用量、父子关系验证及失败分析见[数据报告第十五阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-prompt-v7-repair)。两阶段已知合计 325,948 tokens，首轮连接错误的用量仍未知。

## 博客新题：参考验收与正式冻结

[`research/2026-10-29/blog-reference-ci200/`](research/2026-10-29/blog-reference-ci200/) 保存 `66c276915d825f4d1914da721b1ddf4e0ceda2de` 的 [verify #200](https://github.com/HoloNova/Software-IR/actions/runs/40839225636) 原始 ZIP／博客报告／日志／JUnit及哈希。裁剪参考四门通过、55 文件、业务 **425/0**，四轮均形成六连接／六事务真实竞争；同次 Linux 的旧候选适配器 9 项全部通过。base 静态生成 47 文件并缺四个新增 Controller，运行 HTTP 未执行，不虚构 base 的业务失败结果。

`experiments/slm/tasks/blog-change-v1.json`、`blog-scratch-v1.json` 为 `content.blog` / `train`，共用真实参考证据与 38 项 oracle 闭包。[`pilots/blog-change-g7/`](pilots/blog-change-g7/)、[`blog-scratch-g7/`](pilots/blog-scratch-g7/) 已在逐文件匹配 Git blob 的 LF 快照中通过原 prepare 冻结，每批只有 1 份既有参考、静态通过，BUILD/START/BUSINESS 保持 NOT_RUN。**没有新教师或训练资格**，累计合格仍为 48 条，学生 v3 不变。采样预算待确认，本轮模型 API 调用 0 次；不能将业务报告旧 combinedSha256 当作候选的版本化生成摘要。说明见[第十八阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-blog-reference-frozen)。

## 学生训练格式

[`student/train-student-v3.jsonl`](student/) 包含目前全部 **48 条**合格答案：固定短 system（`experiments/slm/prompts/student-v1.md`）+ 与教师相同的题目 + 最终合格 SIR，不带教师规则手册和修复经过。跨工具链时保留已封存 v2 的 44 条原字节，只用原 `student` 命令包装新合格的 4 条再合成 v3；48 条均核对导出索引、候选与运行绑定，`.index.json` 的 `assembly` 记录来源与哈希，不改历史 manifest。v1（14 条）和 v2（44 条）保留原样。

`.tokens.json` 是固定 revision 分词器的 CPU 实测，v3 最长完整序列为 **9,311**（deepseek-coder-1.3b）／**7,102**（Qwen2.5-Coder-3B、Qwen3-4B），均未截断且低于 16K；不能沿用 v2 的 3,874／3,042 上限。只测长度，未执行模型权重或训练。详见[数据报告第十六阶段](../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-prompt-v7-qualified)。

完整说明：[数据报告](../../docs/qualification/SLM_DATA_PILOT_REPORT.md)、[操作命令](../../experiments/slm/README.md)、[模型选择](../../docs/qualification/SLM_MODEL_SELECTION.md)。API密钥不属于数据或metadata。数据Git属性禁止换行转换，保持证据字节哈希。

跨Windows/Linux或工具链字节变化时，先用实验CLI的`import --from-bundle ... --bundle ...`在目标环境重新冻结输入、导入原响应并重置所有资格，再重做检查；不要修改本批manifest或借用原通过结果。

这不是可直接开训的数据集，也不证明小模型生成能力。完成真实业务门后才由工具生成训练输出；失败轨迹继续保留用于研究，不把失败SIR当正确答案。扩题时先冻结需求/oracle并按业务家族划分训练/开发/盲测，不能随机打散这六条记录当三套集合。
