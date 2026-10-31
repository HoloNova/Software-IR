# 通用规则手册 v11：缺值报指定错误、变量天数平移

## 1. 状态与边界

v11 以 [v10](../../prompts/sir-0.3-general-v10.md) 为底稿，只补两条教师反复做错、而 v10 没写清楚的规则；不改其他规则，不改已用过的 v10、反馈前缀、冻结题目或批次。模型 API 调用 0 次。例子只用手册已有的 Course 等名称和 month/addDays 的通用写法，不带入任何题目的实体名或参考答案。按 LF 解码后的字符数，v10 为 10,767，v11 为 11,135。

## 2. 逐项差异与依据

| v10 位置 | v11 改什么 | 为什么存在 | 依据 |
| --- | --- | --- | --- |
| 83：month | 括号里补：年月或时区缺值、null、格式不对都报 else 的错误；值来自请求字段时，字段要声明为 `Optional<Text>`。紧接着新增一条通用规则：需求要求某字段缺少时报指定错误，就把它声明为 Optional，再由 month 步骤或 validate 报错；非 Optional 字段缺值在工作流之前就以 400 INVALID_REQUEST（NotNull）拒绝 | fitness.check-in 从零题连续两轮、4 个业务门失败：需求要求 month 缺少返回 400 BadPeriod，教师把 month 声明为必填 Text，缺值时得到 400 INVALID_REQUEST。v10 只给了 month 步骤写法，没说必填规则会先于它拒绝请求 | 语言参考 §3.3“普通输入的必填规则”与 §5.12 `month`（:1778–1797，示例即 `Optional<Text>`）；参考答案 `valid/fitness-check-in.sir`:53–54 |
| 95：表达式 | 补：`+ 数量 单位` 的数量只能是整数字面量（`now() + 15 minutes`、`input.at + 7 days`）；按变量天数平移写 `addDays(input.at, input.days)` | maintenance.work-order 从零题写了 `input.firstDueAt + index * input.intervalDays days`，在 `days` 处语法失败；参考答案用 `addDays` | `Sir.g4`:465–486（durationOperand 只接 INT）；参考答案 `valid/maintenance-work-order.sir`:217 |

### 没有顺手改变的规则

- 不改必填规则本身，也不让 month 在必填字段缺值时改报自定义错误；只说明需求要这种行为时怎么写。
- 不新增按变量小时/分钟平移的写法；目前只有 addDays 一个函数。
- v10 新增的“可选 Ref 的 HTTP 字段名不加 Id 后缀”已按 `SpringBootModelLowerer.targetPropertyName`（只给 Ref 类型加 Id，`Optional<Ref<…>>` 不加）核实，原样保留。
- 不改 `repair-general-v1.md`，不增加修复轮数。

## 3. 本机例证核对

用 `828d3a7` 源码本机编译的 kcg-cli（`experiments.slm.dataset_io.checker_command()`）一次检查 4 份通用 SIR。“缺值报 BadPeriod”是运行期行为，静态检查只能证明 Optional 写法合法，运行期证据要等用 v11 采样的候选过业务门。

| 例证 | 实际静态结果 |
| --- | --- |
| `field month: Optional<Text>` + `month input.month in "Asia/Shanghai" … else BadPeriod` + 分页查询 | GENERATION，无诊断，18 文件 |
| `startsAt: addDays(course.startsAt, input.days)` | GENERATION，无诊断，17 文件 |
| `startsAt: course.startsAt + 7 days` | GENERATION，无诊断，17 文件 |
| `startsAt: course.startsAt + input.days days` | PARSE，`SIR-PARSE-001`（`2f3d52c` 之后消息为“时间平移的数量只能是整数字面量……请用 addDays”） |

结论：3 个正例完整静态生成，1 个反例按预期拒绝。BUILD/START/BUSINESS 未运行。
