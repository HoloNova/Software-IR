# 第 0 步实验手册：不训练，先量一量强 LLM 写 SIR 的底子

> 本文只含文档，不改仓库。语法摘要来自 `sir-parser/.../Sir.g4` 和 `sir-semantic` 的类型/约束代码，示例来自 `sir-toolchain-application/src/test/resources/valid/`。
> 我没有跑过任何一条下面的需求，所以 20 条需求里**有些可能触到语言边界**；这正是实验要发现的东西，不是文档错误。

## 0. 这个实验回答什么

三个问题，按重要程度排：

1. 强 LLM 只看语法说明和两个示例，写出的 SIR 有多少能通过编译器？（**编译通过率**）
2. 通过的那些，有多少真的满足需求？（**意思正确率**，编译通过不等于做对，见 `MAIN.md` §1.1）
3. 失败的集中在哪一类？（决定 K、C、反馈回路各自该投多少）

不回答：微调能提升多少、哪个模型最好、成本多少。那是第 2 步以后的事。

**依赖说明：** 第 1 个问题的自动判定使用 Q23 交付的批量校验入口 `kcg check`，调用方式与输入输出格式见 [`docs/SIR_BATCH_CHECK.md`](SIR_BATCH_CHECK.md)。本手册第 4、6、7 节用到的字段是它输出里的 `ok`、`stage`、`diagnostics[].code`，四个阶段取值为 `PARSE / SEMANTIC / LOWERING / GENERATION`。

**范围提醒：** `ok=true` 只表示请求的静态阶段通过，不表示生成工程能构建、能启动、或满足需求。Q23 的限定试跑（见 [`docs/qualification/Q23_REAL_EVALUATION_PILOT.md`](qualification/Q23_REAL_EVALUATION_PILOT.md)，我只读了工作单对它的描述）里，一个漏掉状态过滤的样本就是静态、构建、启动全部通过而业务断言失败。所以第 5 节的意思检查不可省。

## 1. 事前约定（跑之前写下来，跑完不改）

| 项 | 你要填 |
|---|---|
| 被测模型与版本 | |
| 温度、最大输出长度 | 建议温度 0.7（需要看波动），其余默认 |
| 每条需求重复次数 | 建议 3 次 |
| 是否允许模型自我修复 | 第 0 步建议：**不允许**（只看一次成型能力）。后面再单独测「带编译器报错的第二次机会」 |
| 判读门槛 | 先写下你自己的预期，例如「编译通过 ≥ 某值才算有微调价值」。我不替你定数字，但必须在看结果前定 |

## 2. 固定给模型的材料

### 2.1 系统提示（原样使用，不要边跑边改）

````text
你是 SIR 0.1 的编写者。只输出一份完整的 .sir 文本，不要解释，不要 Markdown 代码围栏。

== 文件骨架（固定，不可省） ==
sir 0.1

software <软件名> {
  metadata { displayName "<显示名>"; namespace "<java 包名>"; }
  target {
    language java 21;
    framework spring_boot;
    persistence mybatis_plus;
    database mysql;
    build maven;
    interface rest;
  }
  declarations { ...声明... }
}

== 声明种类（顺序不限，名字在整份文件内唯一） ==
enum E { A, B }
entity X persistent {
  identity id: Int64 generated auto;        // 必须有且仅有一个 identity，放在最前
  field f: <类型> [where <约束>,...] [versioned];
}
view V from X { field f: <类型>; ... }       // 只读响应形状，字段可嵌套其他 view 或 List<view>
input I { field f: <类型> [where ...]; }     // 请求体
input I patch of X { field f: <类型>; ... }  // 局部更新：缺席=保持，null=清空，有值=替换
error Err [http 404];                       // http 状态码可省
capability C {
  [actor Ref<User>;]                         // 需要当前登录者时
  [input I;]
  output <类型>;
  fails Err;                                 // 可写多条
  requires atomic | readonly | authenticated;// 可写多条
  expose command | query;
  workflow { ...步骤... }
}

== 类型 ==
String Int32 Int64 Boolean Decimal Date DateTime Uuid
Optional<T>  List<T>  Page<T>  Ref<实体名>
约束名只可用：notBlank  length(最小,最大)  min(n)  max(n)  email

== 工作流步骤（只有这 7 种） ==
validate <布尔表达式> else <Err>;
load <实体> by <表达式> as <变量> else <Err>;
find <实体> where <表达式> [order by 字段 [ascending|descending], ...] [Page <页>, <每页数> else <Err>] as <变量>;
create <实体> as <变量> { 字段: 表达式; ... }
update <变量> { 字段: 表达式; ... }
persist <变量> [else <Err>];       // 实体有 versioned 字段时，else 的 Err 用于版本冲突
return <表达式>;

== 表达式 ==
字面量: 数字 "字符串" true false
引用: input.字段  actor  item  枚举名.取值  now()
运算: and or not == != < <= > >=  字段 containsLiteral "文本"
存在性: any(实体, 条件)           // 条件里用 item 指当前外层行；如 any(Enrollment, course == item and status == EnrollmentStatus.ACTIVE)
可选字段: input.f.present
````

> 如果你发现模型反复在某处出错，**不要**回头往这份提示里加一条规则就重跑——那会让第 0 步变成「调提示」而不是「量底子」。把它记进失败归类，等第 2 步再处理。

### 2.2 少样本示例（放在系统提示之后）

给两份**完整**文件，路径如下（直接粘贴文件全文）：

- 示例一：`sir-toolchain-application/src/test/resources/valid/course-admin-enrollment.sir`（课程域：创建、读取、局部更新、`any` 过滤分页）
- 示例二：`sir-toolchain-application/src/test/resources/valid/campus-market.sir`（商品域：`actor`、`requires authenticated`、`Ref`）

**注意：** 下面 A 组需求里，和示例同域的几条（A1、A8 等）会因为模型能直接照抄而偏容易。结果汇报时分「与示例同域」和「新域」两类统计，避免高估。

## 3. 20 条需求

需求用产品经理口吻写，不出现 SIR 关键字。「预期构件」列只给你（评分者）看，**不要**发给模型。

### A 组：首次生成（10 条，每条给模型一句话需求，要求输出完整文件）

| ID | 领域 | 需求（发给模型） | 预期构件（评分用） |
|---|---|---|---|
| A1 | 课程 | 做一个课程管理：可以创建课程（课程编号、名称、容量，名称不能为空且不超过 100 字，容量至少为 1），也可以按 id 查看课程详情，查不到时返回「课程不存在」（404）。 | entity + create/get 两个 capability + view + error http 404 |
| A2 | 学生 | 做一个学生管理：创建学生（学号、姓名、邮箱，邮箱必须合法），按 id 查询。 | email 约束；与 A1 同构，作新域对照 |
| A3 | 课程 | 课程列表：按课程编号升序，分页返回，页码或页大小不合法时报「页参数非法」。 | `find ... order by ... Page ... else` |
| A4 | 课程 | 修改课程：可以只改名称、描述、容量中的任意几项；一项都没传时报「空修改」；有并发修改冲突时报「版本过期」（409）。 | `patch of` + `versioned` + `.present` + `persist else` |
| A5 | 选课 | 学生可以报名某门课：记录哪个学生报了哪门课，状态为「有效」或「已取消」，新报名默认有效。 | 两个 `Ref` + enum + create |
| A6 | 选课 | 查看某门课的详情时，同时带回这门课的所有报名（报名里带学生学号和姓名）。 | view 嵌套 `List<view>` |
| A7 | 选课 | 列出「至少有一个有效报名」的课程，按课程编号升序分页。 | `any(Enrollment, course == item and status == ...)` |
| A8 | 商品 | 登录用户可以发布商品：标题不能为空，价格至少 0.01，发布后状态为「在售」，卖家是当前登录用户。 | `actor` + `requires authenticated` + `Decimal` + `min(0.01)` |
| A9 | 商品 | 按标题包含某关键字搜索「在售」商品，分页。 | `containsLiteral` + `and` + enum 比较 |
| A10 | 选课 | 学生可以取消自己的报名：报名不存在时报「报名不存在」；取消后状态变为「已取消」。 | `load` + `update` + `persist`；**可能触到边界**：枚举常量赋值、「自己的」归属判断 |

### B 组：修改（10 条，每条给模型「当前 SIR 全文 + 一句话修改要求」，要求输出修改后的完整文件）

「参考答案」用仓库里已有的 fixture 作标准。**这些 fixture 是一条累积的修改链，不全是单步差异**，我只核对了下面标 ✔ 的几对；标 ? 的要先 diff 再用。

| ID | 基线 | 修改要求（发给模型） | 参考答案 |
|---|---|---|---|
| B1 | `course-admin-enrollment.sir` | 报名查询不要再区分报名状态，凡是有报名的课程都列出来。 | ✔ `course-admin-enrollment-filter-any.sir`（只改过滤条件一行） |
| B2 | `course-admin-enrollment.sir` | 创建课程时，课程名称最长改为 20 个字（课程本身的存储长度不变）。 | ✔ `course-admin-enrollment-tighten.sir`（只改创建输入的长度约束） |
| B3 | `course-admin-enrollment-tighten.sir` | 去掉「修改课程」这个功能。 | ✔ `course-admin-enrollment-remove-update.sir`（相对基线只多删了 `UpdateCourse`；文件内注释写明它的输入声明和错误声明被**有意保留**） |
| B4 | ? | 新增「按课程列出报名」的查询。 | ? `course-admin-enrollment-add-list-refs.sir`（它相对 `course-admin-enrollment.sir` 的 diff 还包含 CreateCourse、UpdateCourse 被删，说明处在更靠后的累积链上；**基线要自己确定**，先 diff 前后文件再用） |
| B5 | `campus-market-minimal.sir` | 新增「搜索商品」的查询。 | ? `campus-market-minimal-add-search-goods.sir` |
| B6 | `course-admin-enrollment.sir` | 课程分页改为按名称倒序。 | 无现成答案，人工写 |
| B7 | `course-admin-enrollment.sir` | 创建课程时容量上限为 500。 | 无现成答案（`max(500)`），人工写 |
| B8 | `campus-market.sir` | 商品状态增加「已预订」。 | 无现成答案，人工写（enum 加一项） |
| B9 | `campus-market.sir` | 发布商品时标题长度限制在 1 到 50 字。 | 无现成答案，人工写 |
| B10 | `course-admin-enrollment.sir` | 课程详情里增加一个可选的「简介」字段，创建和查看都带上。 | 无现成答案，涉及 entity/view/input 三处联动，**用来测多处一致性** |

> B3 的陷阱很典型：模型拿到「去掉修改课程」的要求，可能只删 `UpdateCourse`，也可能把 `UpdateCourseInput` 和 `StaleVersion` 等孤儿声明一起删。哪种算对，要在跑之前**由你来定**（参考答案保留了孤儿声明；这也是 Q13 当时对变更层的约束，不代表产品上唯一正确）。

## 4. 执行步骤

1. 每条需求按第 1 节约定跑 3 次，原样保存模型输出到 `out/<ID>-<n>.sir`，不要手改。
2. 保存时同时记录：模型名、温度、输出 token 数。
3. 把所有输出组装成一个 JSONL（一行一个 `{"id":"A1-1","sir":"..."}`，`stopAfter` 省略即默认 `GENERATION`），按 `docs/SIR_BATCH_CHECK.md` 的命令用 `kcg check < samples.jsonl` 一次性校验，拿到每条的 `ok / stage / diagnostics`。单批最多 10,000 行，本实验 60 条远小于此。
4. 对**通过编译**的输出，做第 5 节的意思检查。
5. 对**没通过**的输出，按第 6 节归类。

## 5. 意思检查（只对编译通过的做，每条回答 4 个问题）

人工判，是/否：

1. 需求里提到的每个字段、约束、错误码，是否都出现了？
2. 工作流顺序是否合理（先校验再加载再更新再持久化，不是反过来）？
3. 有没有多出来的、需求没要求的行为？
4. B 组：除了被要求修改的地方，其余内容是否与基线逐字一致？（可用 diff 工具判断）

任何一条为「否」，该样本记为「通过编译但意思错」。**B 组第 4 问最容易暴露问题**：小改动常伴随模型顺手「重写」别的部分。

## 6. 失败归类（每个失败样本只选一类，选最早出错的那一层）

| 类别 | 判据 | 说明哪一层该投入 |
|---|---|---|
| 语法错 | 阶段为 PARSE，且诊断码**不是** `KCG-CHECK-INPUT*` / `KCG-CHECK-LIMIT*`（这两类是输入 JSONL 本身的问题，也标成 PARSE，属于「环境错」，不是模型的语法错） | C（语法约束）能直接消除 |
| 引用或类型错 | 阶段为 SEMANTIC，如引用了不存在的实体、类型不匹配 | 单靠语法约束消除不了；要 K（学会名字一致性）和编译器反馈 |
| 降级或生成错 | 阶段为 LOWERING/GENERATION | 多半是语言边界，转给 G 侧 Agent 判断是 bug 还是不支持 |
| 意思错 | 编译通过但第 5 节有「否」 | K 与评测集质量；编译通过率会高估能力 |
| 表达缺口 | 需求合理，但 SIR 写不出来（记下是哪条需求、缺什么） | 语言本身要扩，**优先级最高**，别用微调去掩盖 |
| 环境错 | 超时、截断、API 失败 | 剔除重跑，不计入模型表现 |

## 7. 记录表（CSV，一行一次生成）

```csv
id,run,model,temp,out_tokens,stage,ok,fail_class,meaning_ok,notes
A1,1,<model>,0.7,812,NONE,true,,true,
A10,2,<model>,0.7,640,SEMANTIC,false,引用或类型错,,枚举常量赋值被拒
```

汇总时分别给出：A/B 分组、「与示例同域」/「新域」分组的 **编译通过率**、**意思正确率**，以及失败类别计数。三次重复的波动也要报（至少给最小值和最大值），不要只报平均。

## 8. 怎么读结果（建议的判读，请在看数据前确认你同意）

| 观察到 | 说明 | 下一步 |
|---|---|---|
| 编译通过率与意思正确率都高 | 强模型加示例已够；微调的价值主要是**降成本**，不是提成功率 | 第 2 步改成「小模型能否追上强模型」，成本是主指标 |
| 失败集中在**语法错** | 模型没内化语法 | 先上 C，往往不必先微调 |
| 失败集中在**引用或类型错** | 局部一致性问题 | 做编译器反馈的二次机会实验；K 的数据里多放多实体样本 |
| 通过编译但**意思错**占比高 | 校验器太宽松，会污染数据闭环 | **先补评测（业务断言）再谈闭环**，别让它进训练集 |
| 出现多条**表达缺口** | 语言能力不够，不是模型问题 | 先和 G 侧 Agent 对齐，决定扩语言还是收窄实验范围 |
| B 组第 4 问频繁为「否」 | 模型倾向整体重写 | 修改任务要考虑改成输出「补丁」而不是整文件 |

## 9. 这一步结束时应该有的产物

1. 60 份原始输出（20 条 × 3 次）与记录表。
2. 一页汇总：两个通过率、失败类别分布、表达缺口清单。
3. 你对「下一步先做 C、先做 K、还是先扩语言」的一句话决定，附上依据是第 8 节的哪一行。

没有这三样，就还不该租 GPU。
