# 报修与排班参考：同 SHA CI 证据及任务冻结

参考提交为 `5fe4c8339a927fb526c004c276beb4c1c8892996`，[verify 41194982453](https://github.com/HoloNova/Software-IR/actions/runs/41194982453) 的两个 job 均 success。冻结全量门、真实 MySQL/HTTP 业务、独立验证、CLI 和冷部署通过；不是仅按网页绿色推定两题合格。原始报告、对应模块 JUnit、六份输入的 Git blob 摘要、每轮不同连接/事务屏障均已核对。

| 参考 | 生成文件 | 业务检查通过/失败 | X01/X02/X03 实际屏障人数 |
|---|---:|---:|---|
| maintenance.work-order | 55 | 599/0 | 6/6/2 |
| staffing.shift-roster | 56 | 870/0 | 6/3/6 |

## 原始附件

| artifact | ID | 原 ZIP SHA256 |
|---|---|---|
| conformance-evidence | 13040472036 | `161d42ba60bf940b9467000e9c6cd48a82a9e12d37bb062e305702faced9c01d` |
| surefire-reports | 13040317316 | `e9afdd2a066b6db7510a084e5bc9d783f87fe4aa4a5889555ed53c6e2c7fad36` |
| standalone-cli | 13040212811 | `5a3021ca327d57cd7ed510b62217765506396a535a5741bbbd70160a92d7dd7f` |

`original-zips/` 保留原 ZIP 字节；`evidence/` 只展开本轮两题的报告与原日志，`junit/` 只取本次模块的两个业务 IT。Surefire ZIP 含 datasets 下的历史报告，不混算全局计数。`run.json`、`artifacts.json` 和摘要均保留；解包的日志不清洗。冻结核对时原件尚未入Git；负责人现已授权提交/普通推送。须按明确路径显式纳入11份被忽略的原日志/ZIP，再按暂存Git blob检查 `MANIFEST.sha256`；路径清单见 `pending-add.json`。

提取的该 CI 原 `kcg.jar` SHA256 为 `ab6d2c96ab9ec86e233df8c40849f1331a24d53d3568554d93a25fc3da0b3e33`，用于新的静态绑定；不重编工具链，不重新运行业务门。报告的 `combinedSha256` 和 CLI 的版本化 `digest` 是不同协议，绝不直接互比或替换。

## 判卷依赖与公开合同

报修闭包为 36 份 Java 源加 SIR/DDL/seed，共39文件；排班为37份 Java 加三份资源，共40文件。分别以 `-proc:none -implicit:none`、空 sourcepath、无 test-classes 的独立输出目录编译通过，实际命令和依赖哈希见各 `*-oracle-closure.json`。这是依赖完整性检查，不是再运行业务测试。

正式 task 的公开合同不包含状态、运行证据、评分排除项或真实种子；仅保留业务接口/字段/常量/错误与 `fixtures.provided_by_oracle=true`，DDL 按原 prepare 附入题目。`EditWorkOrder.check_order` 的旧注记曾把合并字段校验放在版本预检查前，现按已验收实现与六项运行回归校正为：权限后版本预检查、再合并字段校验、最后条件持久化冲突。没有更改原需求、参考、DDL、种子、oracle 或工具链。

## 任务定义与参考批次

`task-definitions.json` 登记两 family 各8题：change/scratch原题各1，加各3种等义表述，共16题。原题 requirement 取批准文件，不修改原文件；变体只用经过逐句审阅的整词组替换与独立条款重排。`requirements-equivalence.json` 记录每条来源，所有原条款恰出现一次，标识符及数字的多重集保持不变；这些机械核对不冒充自然语言等义的自动证明。

批次使用 `datasets/sir/pilots/<题号去掉-v1>-g13/`、已提交的 v10 手册及原 prepare。g13 只是批次名，不代表已采样。`checkout-verification.json` 记录工具链和 oracle 与受测 Git blob 原字节一致；新 task 定义作为明确的未提交输入覆盖到隔离 LF 快照，不冒称该 CI 已测试新题定义。`batch-summary.json`、各批 `checked.jsonl` 和 `checks/` 是静态冻结证据。

每批仅一份 `existing_reference`；只有 static 可获本次检查结果，build/start/business 保持 NOT_RUN。scratch 的 base.sir 为空；change 的 base 原字节保留。base 的 HTTP 负例未运行，不借参考资格，也不把参考种子计作新教师或训练数据。没有调用模型、生成教师答案、导出训练数据或变更学生 v5。

负责人已确认先提交/普通推送，再对四道原题各初采一次deepseek-flash；预算18万tokens、每次最大8192输出、零自动重试，不含失败修复，12份变体不采。当前计划见 `sampling-plan.json`；后续失败一轮修复、业务门、原archive-gate及export不借此自动授权。旧候选及冻结输入不动，不能以本次参考 CI 覆盖旧候选或未来答案资格。
