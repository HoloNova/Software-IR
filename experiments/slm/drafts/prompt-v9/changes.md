# 通用规则手册 v9：同步三条已实施的语言规则

## 1. 状态与边界

基线 `e6f3b1d`。v9 以 [v8](../../prompts/sir-0.3-general-v8.md) 为底稿，只同步 v8 写成之后已经实施的三条规则：`any` 内同一字段自比较被拒（`47bf67c`）、查重后新建唯一行的 `persist` 必须写 `else`（`47bf67c`）、`containsLiteral` 两侧接受可选文本（`e6f3b1d`）。不改其他规则，不改已用过的 v8、反馈前缀、冻结题目或批次。模型 API 调用 0 次。

例子沿用 v7/v8 已有的 Course/Enrollment/User 名称，不带入任何题目的实体名或参考答案。按 LF 解码后的字符数，v8 为 10,182，v9 为 10,530，增加 348（3.4%）。字符数不是 token 数。

## 2. 逐项差异与依据

| v8 位置 | v9 改什么 | 为什么存在 | 实现依据 |
| --- | --- | --- | --- |
| 71：Optional 筛选 | 加一句可选关键字写法 `input.keyword is none or item.title containsLiteral input.keyword` | 规则放开后，给出和比较相同形状的正面写法 | `TypePass` 的 `CONTAINS_LITERAL` 分支；语言参考 §5.3 新示例 |
| 72：any 作用域 | 加一句：any 内两边都是关联实体同一字段（`student == student`）恒为真、编译器拒绝；要比外层同名变量就先给变量换名 | v8 只解释了作用域，没说这种写法现在会被拒、该怎么改；博客与选课从零写的候选反复写出它 | `ValidatePass.rejectRelatedSelfComparisons`（`SIR-VALID-001`） |
| 75：persist | 加一句：先 exists 查过带 unique 的实体再 create，新行 persist 必须写 else，沿用查重时的错误 | v8 只说 else“可以”用于唯一冲突；现在这种情况是必须，且说明原因（并发会同时通过查重） | `ValidatePass.validatePrecheckedUniqueInserts`（`SIR-VALID-004`） |
| 88：自查 | “persist else 仅上述两类”后加“查重后的唯一新行必须写” | 自查清单与第 75 行一致 | 同上 |
| 95：Optional | 删掉“containsLiteral 右侧必须是非 Optional 文本……”，改为“containsLiteral 和比较同一张表：两侧都可以是 Optional 文本，任一边为空为 false” | 规则已改；保留旧句会让模型继续绕开正常写法 | `e6f3b1d`；语言参考 §6 |

### 没有顺手改变的规则

- 不做判空后的类型收窄；Optional 仍不能算术、调用函数或取可选 Ref 的成员。
- 不要求所有唯一新行都写 else，只要求“查重之后”的那一种。
- 不改 `repair-general-v1.md`，不增加修复轮数。

## 3. 本机例证核对

用 `e6f3b1d` 源码本机编译的 `kcg-cli`（`experiments.slm.dataset_io.checker_command()`，JVM heap 256 MiB）一次检查 5 份通用 SIR。不是 CI 的 standalone JAR；`e6f3b1d` 的 verify 结果待负责人通知。

| 例证 | 实际静态结果 |
| --- | --- |
| 可选关键字 `input.keyword is none or item.title containsLiteral input.keyword` | GENERATION，无诊断 |
| any 内外层变量换名为 `learner` 后写 `student == learner` | GENERATION，无诊断 |
| 查重后 `persist made else AlreadyEnrolled;` | GENERATION，无诊断 |
| any 内 `student == student` | SEMANTIC，`SIR-VALID-001` |
| 查重后 `persist made;` 不写 else | SEMANTIC，`SIR-VALID-004` |

结论：3 个正例完整静态生成，2 个反例按预期拒绝。BUILD/START/BUSINESS 未运行。
