# 通用规则手册 v12：单独写出 avg

## 1. 状态与边界

状态：**草稿，待负责人确认，确认前不用于采样**。v12 以 [v11](../../prompts/sir-0.3-general-v11.md) 为底稿，只改第 81 行一处；不改其他规则，不改已用过的 v11、反馈前缀、冻结题目或批次。模型 API 调用 0 次。

## 2. 改动与依据

| v11 位置 | v12 改什么 | 为什么存在 | 依据 |
| --- | --- | --- | --- |
| 81：sum | 把“avg 同理”拆成单独一行 `avg 实体.金额字段 where 条件 as 平均;`，写明只算非空值、按字段小数位四舍五入（0.5 进位）、空集合为 0，并说明需求要平均值就写 avg，SIR 没有除法，不能用 sum 加 count 自己算 | fitness.check-in 的 MyMonthAverage：g14 从零 1 份与 g15 改写 6 份全部写成 `sum`，业务门全失败（平均时长返回了总时长）；按失败证据修一轮后，6 份只是多写了一个 `count` 仍返回总和，只有 1 份改成 avg。历史上 g11/g12 也是一半 avg 一半 sum。教师不知道有 avg | 语言参考 §5.10“平均值”（:1718–1725）；参考答案 `valid/fitness-check-in.sir`:136 |

### 没有顺手改变的规则

- 不改 sum/avg 的语义、可用位置（仅 readonly query）或分组写法。
- 不改 `repair-runtime-v1.md`。

## 3. 本机例证核对

用当前源码本机编译的 kcg-cli 检查一个 `avg Enrollment.fee where item.student == input.studentId as mean;` 的最小软件：GENERATION，无诊断，15 文件。
