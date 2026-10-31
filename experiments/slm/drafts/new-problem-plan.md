# 补齐训练覆盖的新题清单

基线 `92ff22d`。状态：**清单，待负责人挑选；每个新 family 动手前另出规格（仿 `drafts/blog/spec.md`）**。本文只读统计，未调用模型，未改题目或数据。

## 1. 目标与现状

目标（理由见与负责人的讨论）：第一轮正式训练约 300～500 条合格答案；**25～30 个业务 family**（finance、library 永远留作 test，shop 留作 development）；**每种语言结构至少出现在 15～20 条答案里**；每道题 2～3 份不同答案即可。数据到 150 条左右时，先按 25%/50%/100% 训练、在 dev 上测通过率，按学习曲线决定还要补多少。

现状（学生 v5，57 条，10 个 train family）。下表是“在多少条答案里出现过”（按源码文本匹配统计）：

| 结构 | 条数 | | 结构 | 条数 |
| --- | ---: | --- | --- | ---: |
| `count … where` | **0** | | `exists … where` | 5 |
| `sum` / `avg` | **0** | | `unique (a, b)` | 5 |
| `each … limit` | **0** | | `scope` | 2 |
| `repeat … times` | **0** | | `versioned` | 3 |
| `month … in` | **0** | | `is none` | 1 |
| `Money` | **0** | | `for update` | 12 |
| `any(...)` | 13 | | `patch of` | 11 |
| `Page` 分页 | 27 | | `persist … else` | 19 |
| `containsLiteral` | 13 | | `update … when` | 19 |

## 2. 关键限制：缺的结构只在 dev/test 同源素材里

`topics/` 下各主题的结构分布（出现次数）：

| 素材 | 已占用的 family / split | count | sum/avg | month | each | repeat | Money | is none | scope |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `topics/ledger` | finance.money / **test** | 0 | 5/2 | 2 | 0 | 0 | 4 | 0 | 0 |
| `topics/shop` | shop.multiplication / **development** | 0 | 0 | 0 | 4 | 0 | 15 | 0 | 0 |
| `topics/booking` | 与 shop 同源 / **development** | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 |
| `topics/market` | 与 library 同源 / **test** | 0 | 0 | 0 | 0 | 0 | 5 | 3 | 0 |
| `topics/tasks` | 未用，可做 train | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 0 |
| `deployment/dual-token-v3` | 归 auth.dual-token.rotation / train | 2 | 0 | 0 | 0 | 0 | 3 | 7 | 1 |
| `valid/course-admin-enrollment.sir` | 归 course.write-slice / train | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

依据：`new-problem-survey.md` §4（ledger、market 必须随 test 组，booking 随 development 组，不能换个 family 名放进 train）。所以 **`sum`/`avg`/`month`/`each`/`repeat` 在现有素材里没有能进 train 的来源**，只能新写业务。新写的业务也要避开 test/dev 的业务形状：不做个人记账、预约房间、购物车、借书和二手交易。

## 3. 清单

### A. 现成素材（先做，成本中）

| # | 素材 | family / split | 补什么 | 要做的 |
| --- | --- | --- | --- | --- |
| A1 | `topics/tasks` | 新 `collaboration.task-board`，train | `is none`（可选指派人、到期日）、`exists`、`unique (a, b)`、成员权限 | 规格一直没到位（原交接 4.6）；按博客规格的做法重写 |
| A2 | `deployment/dual-token-v3` | 沿用 `auth.dual-token.rotation`，train | `count`、`Money`、`scope`、`is none`、并发计数 | 已有部署烟测，缺独立 DDL 和业务 IT |
| A3 | `valid/course-admin-enrollment.sir` | 沿用 `course.write-slice`，train | `versioned` + `patch of` 与关联的组合 | 成本低，DDL 和测试思路可复用；结构上补得少 |

### B. 新写业务（补 0 条的结构）

都是建议，没有规格。每个 family 规模控制在 3～6 个实体，比博客（10 实体）小，业务 IT 也相应小，降低单题成本。

| # | 建议 family（train） | 业务 | 主要补的结构 |
| --- | --- | --- | --- |
| B1 | `fitness.check-in` | 健身房会员打卡：按会员、按自然月统计次数和时长 | `count`、`sum`、`avg`、`month`、`is none` 筛选 |
| B2 | `events.registration` | 活动报名缴费：名额计数、同一人只报一次、按活动汇总已收报名费 | `Money`、`sum`、`count`、`unique (a, b)`、`update … when` 名额 |
| B3 | `maintenance.work-order` | 设施报修工单：可选指派人、版本化处理记录、按周期生成例行巡检工单、批量关闭超期工单 | `versioned`、`patch of`、`is none`、`repeat … times`、`each … limit`、`scope` 软删除 |
| B4 | `staffing.shift-roster` | 门店排班：按周重复生成班次、同一员工同一时段唯一、超限整批不写 | `repeat … times`、`unique (a, b)`、`for update`、`count` |
| B5 | `loyalty.points` | 会员积分：消费入账、按月汇总积分、批量过期 | `sum`、`month`、`each … limit`、`update … when` |
| B6 | `survey.poll` | 问卷投票：一人一票、可选备注、按选项计票分页、评分题的平均分 | `count`、`avg`、`unique (a, b)`、`is none`、`exists`、`any(...)` |
| B7 | `catering.meal-order` | 食堂订餐：餐品单价 × 份数、按日汇总金额、截止后不能改 | `Money`、乘法、`sum`、`update … when` 守卫 |
| B8 | `docs.knowledge-base` | 知识库文章：版本化编辑、关键字搜索、可选分类、软删除 | `versioned`、`patch of`、`containsLiteral` 可选关键字、`scope` |

B4 与 booking（dev）都有“按周重复”，B7 与 shop（dev）都有“单价 × 数量”。机制相同可以，业务和数据形状要明显不同；写规格时专门核对，避免把 dev 的题换了个名字放进 train。

## 4. 覆盖估算

按现在的做法，每个 family 约 8 道题（改写、从零写各 1 道原题，各 3 种需求改写）。目前 57 条答案分布在 56 道题上，**每道题实际约 1 份**；采一轮、修一轮后，大约六到七成的题能拿到合格答案。

A1 和 B1–B8 是 9 个新 family，A2、A3 并入已有 family 但各带一组新题，合计约 11 组、88 道新题。按六到七成估算，新增约 55～65 条，总计约 **110～120 条，19 个 family**。对已经有合格答案的题再采一轮（这类题通过率高、成本低），把每道题补到 2 份，可以到约 **170～180 条**，正好是第一次做学习曲线实验的规模。每种 0 条的结构在 B 组里至少出现在 2 个 family（`count`、`sum`、`Money` 有 3～4 个），按每个 family 约 8～14 条答案计，可以到 15～20 条以上。要到 25～30 个 family，还要再加 6～10 个，等学习曲线的结果再定。

## 5. 每个 family 的工作量（以博客为参照）

1. 规格（需求、合同、参考答案裁剪、IT 检查清单），负责人审核；
2. 参考 SIR、DDL、种子数据、业务 IT（复用 `BusinessSliceHarness`），接入 `verify.yml` 与 `slm-business-gate.yml`；
3. 参考答案过四门（CI）后冻结 task（原题 2 道 + 改写 6 道）；
4. 采样、修一轮、业务门、归档、导出。

1–3 是主要成本，花的是工程时间，不是 API 费用；第 4 步按这一轮的经验，每个 family 约 10～15 万 tokens。

## 6. 建议顺序与待负责人决定

建议先做 **B1（统计/自然月）和 B2（金额/计数）**：两者合起来补上 `count`、`sum`、`avg`、`month`、`Money` 五个 0 条的结构，业务也最不像现有 dev/test 题。再做 A1（规格搁置已久、素材现成），然后 B3、B4（补 `each`、`repeat`、`versioned`）。

需要负责人决定：

1. 是否按 §6 的顺序，或另选；
2. B 组 family 名称和业务是否合适，有没有想换的领域；
3. 规格和实现由主会话做，还是规格交给 openai-codex 设计、实现由主会话完成（AGENTS.md 白名单）。

## 7. B3/B4 实际进展（2026-10-31，原清单之后）

本节更新B3报修和B4排班，不改其他工作线的安排。两题规格边界已由负责人确认，DDL/seed、业务IT与两个workflow已落地；CI揭示的两处通用实现问题获批修复后，在 `5fe4c8339a927fb526c004c276beb4c1c8892996` 的 [verify 41194982453](https://github.com/HoloNova/Software-IR/actions/runs/41194982453) 参考验收通过：报修599/0、排班870/0，六轮真实并发、冻结门和独立部署成功。

已完成§5第3步：每family的change/scratch原题各1，以及各3份p1/p2/p3等义表述，共16份train task、16个仅参考g13批次；全部Schema/静态生成通过，原题、SIR/base、DDL/seed与业务oracle不改。变体共用同一合同/判卷，逐句保留业务条款，不从booking/shop或test搬运。定义与批次在冻结核对时尚未提交；负责人现已批准提交/普通推送，原件与输入哈希见[参考/冻结记录](../../../datasets/sir/research/2026-10-31/bounded-reference-ci41194982453/README.md)。

第4步尚未开始：本轮模型调用0，新增教师/四门合格答案0，学生v5原字节不变，base HTTP负例NOT_RUN。下一步建议先对四道原题各初采1次deepseek-flash、每次最大8192输出tokens，四次初采保守预算18万tokens（按实际冻结内容字节估算，非实际用量，不含修复），负责人现已确认上述初采与提交/普通推送；不包含失败修复，不自动采12份变体或触发重复参考CI。
