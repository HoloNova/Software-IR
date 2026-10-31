# 通用规则手册 v10：补一条可选 Ref 筛选的写法

## 1. 状态与边界

基线 `22a4c65`。v10 以 [v9](../../prompts/sir-0.3-general-v9.md) 为底稿，只在第五节“Optional 筛选”那条之后加一条，其余逐字不变；v9 与已冻结批次不改。例子用通用的 `owner`/`User`，不带入任何题目的实体名。

## 2. 差异与依据

| v9 位置 | v10 改什么 | 为什么存在 | 依据 |
| --- | --- | --- | --- |
| 第 71 行“Optional 筛选…归属筛选”之后 | 新增“按引用筛选”：可选的引用筛选字段写成 `Optional<Ref<实体>>`；不能写 `Optional<Id>` 与 Ref 字段比较；可选 Ref 输入的 HTTP 字段名是字段名本身，必填 Ref 才是 `xxxId` | fitness.check-in 从零题 g11 的两份教师答案都把筛选字段写成 `Optional<Id>`，得到 `SIR-TYPE-001`，一轮静态反馈也修不好；手册没有说明该写法 | 检查器：`Optional<Id>` 与 Ref 比较报 `SIR-TYPE-001`（规格 fitness §0.2）；`SpringBootModelLowerer.java`:1942–1944 只给 `RefType` 加 `Id`，生成的 `CoachQuery` 字段为 `member`、`program` |

没有改变任何语言规则，也没有调用模型验证通过率提升。
