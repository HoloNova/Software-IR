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

完整说明：[数据报告](../../docs/qualification/SLM_DATA_PILOT_REPORT.md)、[操作命令](../../experiments/slm/README.md)、[模型选择](../../docs/qualification/SLM_MODEL_SELECTION.md)。API密钥不属于数据或metadata。数据Git属性禁止换行转换，保持证据字节哈希。

跨Windows/Linux或工具链字节变化时，先用实验CLI的`import --from-bundle ... --bundle ...`在目标环境重新冻结输入、导入原响应并重置所有资格，再重做检查；不要修改本批manifest或借用原通过结果。

这不是可直接开训的数据集，也不证明小模型生成能力。完成真实业务门后才由工具生成训练输出；失败轨迹继续保留用于研究，不把失败SIR当正确答案。扩题时先冻结需求/oracle并按业务家族划分训练/开发/盲测，不能随机打散这六条记录当三套集合。
