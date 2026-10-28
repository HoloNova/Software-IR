# 可做新题的参考软件盘点

## 0. 结论与统计口径

基线：`main` 的 `4c39441`。只读文件、声明、测试入口和任务配置；未运行 Maven、生成工程、数据库或模型 API。表中的“有测试”表示仓库存在对应测试代码，不是本轮已执行通过。

**优先推荐 7 份：博客、任务板、完整账本、完整预约、部署 V3 收藏、课程管理与关联组合、完整市场。真正较独立的新业务家族主要是博客和任务板；另外五份分别受现有 train/dev/test 同源边界约束，不能换个 family 名就当全新独立数据。**推荐明细见 §4；所有路径见 §1、§2 和附录。

先纠正两个容易导致重复计数的口径：

| 项目 | 本次实际盘点 |
|---|---|
| 任务配置 | `experiments/slm/tasks/*.json` 有 **23 份 JSON、12 个 family、12 个不同 reference_source**。其中 22 份是背景所说 11 道题的 change/scratch，另 1 份是保留的 `dual-token-v1.json`；不把题型数算成软件数 |
| `.sir` 文件 | Git 跟踪的 **365 份**：Application 测试资源 63；其他模块资源 129（含 4 份标准功能模板）；改写题 drafts/base 11；datasets 冻结底稿、参考/教师输出副本 162。忽略 target 等未跟踪生成产物，不把它们当仓库参考软件 |
| 声明计数 | 特性列表示源码中存在声明/用法，不代表所有保留声明都被当前接口或业务门使用。`E/C` 是实体/能力数量；有功能展开时记“手写 → 展开后”。按当前 DualTokenLogin 加 3E/3C、AccountRegistration 加 1C、每个 Crud 加 5C 计算；Tagging 示例加 1E/1C。没有运行编译器。多文件项目另列合计，模块片段不是独立软件 |
| “已使用” | **参**＝任务直接 reference_source；**基**＝任务 base_source；**间接**＝整份或切片已被当前参考软件组合、或同内容副本。不能仅看 reference_source 路径不同就判为新业务 |
| DDL | “全”指已有可覆盖该软件持久化结构的 fixture DDL/可复用子集；不是已证明所有列约束与新题完全一致。“部分”明确缺表/列或需更名；部署过程中可生成 schema 不等于仓库已有独立 `.sql` |
| 成本 | **低**：有可复用 DDL 和当前测试基础，主要补业务 IT；**中**：缺完整 DDL或需要较多新检查/拆装；**高**：纯机制样例、业务过小、故意错误、模板或重复底稿，不值得单独出题。成本不是实现授权 |

标准模板计数依据：`sir-semantic/src/main/resources/io/kcg/sir/semantic/feature/dual-token-login.sir` 的三实体与 Login/Refresh/Logout；`account-registration.sir`:13 起的 Register；`crud.sir`:40,80,106,135,184 的五能力。`FeatureUseTest.java`:48–58、`CrudFeatureTest.java` 有展开与手写等价性检查。表内“聚合”包括 sum/avg；视图的 count(关联) 单独点名，不与数值聚合混为一谈。Crud 在目标实体有 Version 时展开为 PATCH（如 crud-feature 的 Note），不能因源码只写 use 就漏算该能力。

### 0.1 已有题目与占用的分组

| family | split | JSON数 | 任务文件 | 参考 .sir | DDL | 业务 IT |
|---|---|---|---|---|---|---|
| auth.bearer-credential | train | 2 | `bearer-credential-change-v1.json`<br>`bearer-credential-scratch-v1.json` | `sir-toolchain-application/src/test/resources/valid/bearer-credential.sir` | bearer-credential-ddl.sql | `A/conformance/BearerCredentialBusinessConformanceIT.java` |
| auth.crud-access | train | 2 | `crud-access-change-v1.json`<br>`crud-access-scratch-v1.json` | `sir-toolchain-application/src/test/resources/valid/crud-access.sir` | crud-access-ddl.sql | `A/conformance/CrudAccessBusinessConformanceIT.java` |
| auth.dual-token.rotation | train | 1 | `dual-token-v1.json` | `sir-toolchain-application/src/test/resources/valid/dual-token-written.sir` | dual-token-ddl.sql（oracle/IT 引用） | `A/conformance/DualTokenBusinessConformanceIT.java` |
| auth.guarded-transition | train | 2 | `guarded-transition-change-v1.json`<br>`guarded-transition-scratch-v1.json` | `sir-toolchain-application/src/test/resources/valid/guarded-transition.sir` | credential-rotation-ddl.sql | `A/conformance/GuardedTransitionBusinessConformanceIT.java` |
| auth.token-desk | train | 2 | `token-desk-change-v1.json`<br>`token-desk-scratch-v1.json` | `sir-toolchain-application/src/test/resources/valid/token-desk.sir` | token-desk-ddl.sql | `A/conformance/TokenDeskBusinessConformanceIT.java` |
| course.course-selection | train | 2 | `course-selection-change-v1.json`<br>`course-selection-scratch-v1.json` | `sir-toolchain-application/src/test/resources/topics/course/project.sir` | course-ddl.sql | `A/conformance/CourseSelectionBusinessConformanceIT.java` |
| course.query-slice | train | 2 | `query-slice-change-v1.json`<br>`query-slice-scratch-v1.json` | `sir-toolchain-application/src/test/resources/valid/course-catalog.sir` | course-catalog-ddl.sql | `A/conformance/QuerySliceBusinessConformanceIT.java` |
| course.relation-slice | train | 2 | `relation-slice-change-v1.json`<br>`relation-slice-scratch-v1.json` | `sir-toolchain-application/src/test/resources/valid/course-enrollment.sir` | course-enrollment-ddl.sql | `A/conformance/RelationSliceBusinessConformanceIT.java` |
| course.write-slice | train | 2 | `write-slice-change-v1.json`<br>`write-slice-scratch-v1.json` | `sir-toolchain-application/src/test/resources/valid/course-admin.sir` | course-admin-ddl.sql | `A/conformance/WriteSliceBusinessConformanceIT.java` |
| finance.money | test | 2 | `money-change-v1.json`<br>`money-scratch-v1.json` | `sir-toolchain-application/src/test/resources/valid/money.sir` | money-ddl.sql | `A/conformance/MoneyBusinessConformanceIT.java` |
| library.library-borrowing | test | 2 | `library-borrowing-change-v1.json`<br>`library-borrowing-scratch-v1.json` | `sir-toolchain-application/src/test/resources/valid/g9-topic-conformance.sir` | library-ddl.sql | `A/conformance/LibraryBorrowingBusinessConformanceIT.java` |
| shop.multiplication | development | 2 | `multiplication-change-v1.json`<br>`multiplication-scratch-v1.json` | `sir-toolchain-application/src/test/resources/valid/shop-conformance.sir` | multiplication-ddl.sql | `A/conformance/MultiplicationBusinessConformanceIT.java` |

上表任务路径均在 `experiments/slm/tasks/`。同 family 的改写/从零以及改说法必须放在同一集合；split 的正式值为 train/development/test。依据：`experiments/slm/README.md`:47–51。旧双令牌题没有顶层 ddl_source，但其 oracle 与 `DualTokenBusinessConformanceIT.java`:35–38 仍绑定 `dual-token-ddl.sql`，不能漏掉。

### 0.2 测试与 DDL 路径约定

- `A/` 表示 `sir-toolchain-application/src/test/java/io/kcg/sir/application/`；`A/conformance/` 是同目录下的业务测试。
- DDL 文件名相对 `sir-toolchain-application/src/test/resources/conformance/mysql/`。其他路径均完整给出。
- `TopicSamplesTest.java`:27–45 编译/生成全部八个 topics 并规划交付；`TopicSamplesOfflineCompilationTest.java`:33–48 有条件地编译其生成 Java（无冻结仓库就跳过）。它们**不等于八套业务判卷**。
- `SampleCorpusTest.java`:14–36 把 20 个早期 valid fixture 编译到生成；`:50–56 专门验证两个 invalid fixture 被拒绝。多文件入口见 `ProjectTestSupport.java`:16–24 与 `A/conformance/ProjectVerificationChainIT.java`:59。
- 其他模块的 RuntimeTest 可能使用 javac、代理 mapper 或生成代码片段执行，不等于真实 HTTP/MySQL。`BusinessSliceHarness` 负责独立 schema、生成/构建/启动、HTTP/JDBC 证据与清理；新增题需要自己的业务断言，不能把“能编译”当成判卷。

## 1. Application 的全部 63 份 `.sir`

每行是一份实际文件；即使是故意错误、变更版本或模块片段也列出。已有题的行仅标占用，不重复推荐。实体/能力依据本行软件的声明与上述功能展开规则；测试列给出真实载入点或共同入口。

| 编号 / 完整路径 | 现有题占用 | 业务领域 | E/C | 源码特性 | DDL | 已有测试载入点 | 新题成本 |
|---|---|---|---|---|---|---|---|
| A1 `sir-toolchain-application/src/test/resources/conformance/slm/course-enrollment-missing-status.sir` | 未作参考；负例 | 故意漏状态过滤的错误候选 | 3/1 | 分页、关联any | 全：course-enrollment-ddl.sql；错误在业务过滤，不在表结构 | `A/conformance/IndependentVerificationIT.java`:41<br>`A/conformance/SlmGeneratedProjectEvaluationIT.java`:22 | 高：负例，不能作参考答案 |
| A2 `sir-toolchain-application/src/test/resources/deployment/dual-token-v2/project.sir` | 未直接用作参/基 | 账号/笔记/标签部署 V2 | 3/5 → 6/18 | 凭据、Optional、CRUD展开(含分页) | 部分：无独立 V2 SQL；label 与 note 新字段缺固定 DDL | `.github/workflows/verify.yml`:269 | 中：有部署烟测，静态 DDL 需固定 |
| A3 `sir-toolchain-application/src/test/resources/deployment/dual-token-v3/project.sir` | 未直接用作参/基 | 笔记收藏/评分/可见性 | 3/14 → 6/22 | 分页、守卫更新、凭据、关联any、scope、金额、Optional、CRUD展开(含分页) | 部分：无独立 V3 SQL；需 favorite、user.favorites、note 的新字段 | `A/OptionalDeploymentFixtureTest.java`:20<br>`.github/workflows/verify.yml`:286 | 中：迁移烟测转业务 IT、补静态 DDL |
| A4 `sir-toolchain-application/src/test/resources/deployment/dual-token/project.sir` | 未直接用作参/基 | 账号/笔记部署 V1 | 2/3 → 5/6 | 凭据 | 部分：dual-token-ddl.sql 的 user/认证表；note 已有字段不同，需核对 | `.github/workflows/verify.yml`:252 | 高：旧部署底稿，同已有登录笔记业务 |
| A5 `sir-toolchain-application/src/test/resources/invalid/lowering-unsupported-any.sir` | 未作参考；负例 | 故意不可降级的关联查询 | 2/1 | 分页、关联any | 不适用：预期静态拒绝 | `A/SampleCorpusTest.java`:51<br>`A/SirValidationApplicationTest.java`:55 | 高：负例，不是新题答案 |
| A6 `sir-toolchain-application/src/test/resources/invalid/semantic-unresolved-name.sir` | 未作参考；负例 | 故意未声明符号 | 1/1 | 基本载入/声明 | 不适用：预期语义拒绝 | `A/CandidateVerificationTest.java`:209<br>`A/SampleCorpusTest.java`:51 | 高：负例，不是新题答案 |
| A7 `sir-toolchain-application/src/test/resources/topics/blog/project.sir` | 未直接用作参/基 | 博客/标签/评论/点赞 | 7/13 → 10/22 | 分页、守卫更新、凭据、关联any、删除、CRUD展开(含分页) | 部分：crud-access-ddl.sql 只有不同结构的 post；缺 category/comment/tag/post_tag/like | `A/TopicSamplesTest.java`:27<br>`A/TopicSamplesOfflineCompilationTest.java`:33 | 中：缺完整 DDL、需跨路径检查 |
| A8 `sir-toolchain-application/src/test/resources/topics/booking/project.sir` | 未直接用作参/基 | 完整房间预约/每周系列 | 3/7 → 6/11 | 分页、repeat、凭据、行锁、乘法、删除 | 部分：multiplication-ddl.sql:8–9 的 room/booking；需角色、位置/容量、booker/purpose | `A/ExplicitLockToolchainContractTest.java`:12<br>`A/TopicSamplesTest.java`:27 | 中：简化表不足、需完整流程 |
| A9 `sir-toolchain-application/src/test/resources/topics/course/project.sir` | 参:course.course-selection | 选课/先修/容量并发 | 5/10 → 8/14 | 分页、守卫更新、凭据、关联any、行锁、计算视图、删除 | 全：course-ddl.sql | `A/ComputedViewToolchainContractTest.java`:23<br>`A/conformance/CourseSelectionBusinessConformanceIT.java`:12 | 已用 |
| A10 `sir-toolchain-application/src/test/resources/topics/ledger/project.sir` | 间接：finance.money / test（§1.1） | 个人记账/月报/预算 | 4/11 → 7/30 | 分页、聚合、month、凭据、关联any、金额、Optional、CRUD展开(含分页) | 全：money-ddl.sql 的 user/category/entry/budget 与认证表子集 | `A/DecimalSumToolchainContractTest.java`:22<br>`A/RelatedCountProjectGraphContractTest.java`:73 | 中：DDL/统计检查可复用，完整 CRUD 闭环仍需较多检查 |
| A11 `sir-toolchain-application/src/test/resources/topics/library/project.sir` | 间接：library.library-borrowing / test（§1.1） | 图书借还/排队预约 | 5/11 → 8/20 | 分页、守卫更新、凭据、关联any、行锁、金额、乘法、计算视图、Optional、删除、CRUD展开(含分页)、关联count视图 | 全：library-ddl.sql 子集 | `A/LibraryBorrowingToolchainContractTest.java`:20<br>`A/RelatedCountProjectGraphContractTest.java`:22 | 低（但已整体进入 test，不另出同源题） |
| A12 `sir-toolchain-application/src/test/resources/topics/market/project.sir` | 间接：library.library-borrowing / test（§1.1） | 二手交易/商品订单 | 3/6 → 6/15 | 分页、守卫更新、凭据、金额、Optional、CRUD展开(含分页) | 部分：library-ddl.sql 的 item/认证表；缺 trade_order | `A/RelatedCountProjectGraphContractTest.java`:73<br>`A/conformance/G9TopicComposition.java`:16 | 中：补 trade_order 与交易检查 |
| A13 `sir-toolchain-application/src/test/resources/topics/shop/project.sir` | 间接：shop.multiplication / development（§1.1） | 商店/购物车/优惠券 | 7/16 → 10/20 | 分页、守卫更新、each、凭据、行锁、金额、乘法、Optional、删除 | 近全：multiplication-ddl.sql；原 Product 与 ShopProduct 更名/列精度需适配 | `A/MultiplicationToolchainContractTest.java`:24<br>`A/conformance/ShopSourceComposition.java`:16 | 中（已整体进入 development，不另出同源题） |
| A14 `sir-toolchain-application/src/test/resources/topics/tasks/project.sir` | 未直接用作参/基 | 项目协作/任务看板 | 4/8 → 7/17 | 分页、凭据、Optional、CRUD展开(含分页) | 部分：可复用认证表；缺 project/project_member/task | `A/TopicSamplesTest.java`:27<br>`A/TopicSamplesOfflineCompilationTest.java`:33 | 中：缺项目/成员/任务 DDL |
| A15 `sir-toolchain-application/src/test/resources/valid/account-registration-feature.sir` | 未直接用作参/基 | 账号注册（feature 写法） | 1/0 → 4/4 | 凭据 | 部分：现有认证 DDL 可复用，但需 User.role 及种子适配 | `A/FeatureUseTest.java`:56<br>`A/FeatureUseTest.java`:62 | 高：与手写等价、已有登录题同族 |
| A16 `sir-toolchain-application/src/test/resources/valid/account-registration-written.sir` | 未直接用作参/基 | 账号注册（手写写法） | 1/1 → 4/4 | 凭据 | 部分：同 feature 版本 | `A/FeatureUseTest.java`:55 | 高：与 feature 等价，不另算题 |
| A17 `sir-toolchain-application/src/test/resources/valid/bearer-credential.sir` | 参:auth.bearer-credential | Bearer 凭据/笔记 | 3/5 | 凭据 | 全：bearer-credential-ddl.sql | `A/conformance/BearerCredentialBusinessConformanceIT.java`:29<br>`A/conformance/SlmCandidateSupportTest.java`:106 | 已用 |
| A18 `sir-toolchain-application/src/test/resources/valid/calendar-month.sir` | 未直接用作参/基 | 月份边界纯计算 | 0/2 | month、Optional | 不需要数据库表（无持久化实体） | 未见直接资源载入；不等于已执行通过 | 高：0 实体，机制样例 |
| A19 `sir-toolchain-application/src/test/resources/valid/campus-market-actorless-readonly-base.sir` | 未直接用作参/基 | 校园 Goods 发布/列表变更版本 | 1/1 | 基本载入/声明 | campus-market-ddl.sql 仅 goods；发布只写外部 seller_id，无用户读取。加用户流程须补 user 表 | `A/SampleCorpusTest.java`:15 | 高：1–3 能力，多个版本同一小业务 |
| A20 `sir-toolchain-application/src/test/resources/valid/campus-market-candidate-modify-input-field-constraints.sir` | 未直接用作参/基 | 校园 Goods 发布/列表变更版本 | 2/1 | 金额 | campus-market-ddl.sql 仅 goods；发布只写外部 seller_id，无用户读取。加用户流程须补 user 表 | `A/SampleCorpusTest.java`:16 | 高：1–3 能力，多个版本同一小业务 |
| A21 `sir-toolchain-application/src/test/resources/valid/campus-market-candidate.sir` | 未直接用作参/基 | 校园 Goods 发布/列表变更版本 | 2/1 | 金额 | campus-market-ddl.sql 仅 goods；发布只写外部 seller_id，无用户读取。加用户流程须补 user 表 | `A/SampleCorpusTest.java`:15 | 高：1–3 能力，多个版本同一小业务 |
| A22 `sir-toolchain-application/src/test/resources/valid/campus-market-minimal-add-search-goods.sir` | 未直接用作参/基 | 校园 Goods 发布/列表变更版本 | 2/3 | 金额 | campus-market-ddl.sql 仅 goods；发布只写外部 seller_id，无用户读取。加用户流程须补 user 表 | `A/SampleCorpusTest.java`:17 | 高：1–3 能力，多个版本同一小业务 |
| A23 `sir-toolchain-application/src/test/resources/valid/campus-market-minimal.sir` | 未直接用作参/基 | 校园 Goods 发布/列表变更版本 | 2/2 | 金额 | campus-market-ddl.sql 仅 goods；发布只写外部 seller_id，无用户读取。加用户流程须补 user 表 | `A/SampleCorpusTest.java`:16 | 高：1–3 能力，多个版本同一小业务 |
| A24 `sir-toolchain-application/src/test/resources/valid/campus-market-two-capabilities-remove-publish-goods.sir` | 未直接用作参/基 | 校园 Goods 发布/列表变更版本 | 1/1 | 基本载入/声明 | campus-market-ddl.sql 仅 goods；发布只写外部 seller_id，无用户读取。加用户流程须补 user 表 | `A/SampleCorpusTest.java`:18 | 高：1–3 能力，多个版本同一小业务 |
| A25 `sir-toolchain-application/src/test/resources/valid/campus-market-two-capabilities.sir` | 未直接用作参/基 | 校园 Goods 发布/列表变更版本 | 1/2 | 基本载入/声明 | campus-market-ddl.sql 仅 goods；发布只写外部 seller_id，无用户读取。加用户流程须补 user 表 | `A/SampleCorpusTest.java`:17<br>`A/SirValidationApplicationTest.java`:72 | 高：1–3 能力，多个版本同一小业务 |
| A26 `sir-toolchain-application/src/test/resources/valid/campus-market.sir` | 未直接用作参/基 | 校园 Goods 发布/列表变更版本 | 2/1 | 金额 | campus-market-ddl.sql 仅 goods；发布只写外部 seller_id，无用户读取。加用户流程须补 user 表 | `A/ApplicationTestSupport.java`:24<br>`A/CandidateVerificationTest.java`:197 | 高：1–3 能力，多个版本同一小业务 |
| A27 `sir-toolchain-application/src/test/resources/valid/course-admin-enrollment-add-list-refs.sir` | 未直接用作参/基 | 课程管理组合的增删/只读/收紧版本 | 3/3 | 分页、PATCH、关联any、Optional | 全：change-loop-ddl.sql | `A/SampleCorpusTest.java`:21 | 高：与主组合是变更版本，不另算领域 |
| A28 `sir-toolchain-application/src/test/resources/valid/course-admin-enrollment-filter-any.sir` | 未直接用作参/基 | 课程管理组合的增删/只读/收紧版本 | 3/4 | 分页、PATCH、关联any、Optional | 全：change-loop-ddl.sql | `A/SampleCorpusTest.java`:19 | 高：与主组合是变更版本，不另算领域 |
| A29 `sir-toolchain-application/src/test/resources/valid/course-admin-enrollment-readonly.sir` | 未直接用作参/基 | 课程管理组合的增删/只读/收紧版本 | 3/2 | 分页、PATCH、关联any、Optional | 全：change-loop-ddl.sql | `A/SampleCorpusTest.java`:21 | 高：与主组合是变更版本，不另算领域 |
| A30 `sir-toolchain-application/src/test/resources/valid/course-admin-enrollment-remove-update.sir` | 未直接用作参/基 | 课程管理组合的增删/只读/收紧版本 | 3/3 | 分页、PATCH、关联any、Optional | 全：change-loop-ddl.sql | `A/SampleCorpusTest.java`:20 | 高：与主组合是变更版本，不另算领域 |
| A31 `sir-toolchain-application/src/test/resources/valid/course-admin-enrollment-tighten.sir` | 未直接用作参/基 | 课程管理组合的增删/只读/收紧版本 | 3/4 | 分页、PATCH、关联any、Optional | 全：change-loop-ddl.sql | `A/SampleCorpusTest.java`:20 | 高：与主组合是变更版本，不另算领域 |
| A32 `sir-toolchain-application/src/test/resources/valid/course-admin-enrollment.sir` | 未直接用作参/基 | 课程版本 PATCH+关联列表组合 | 3/4 | 分页、PATCH、关联any、Optional | 全：change-loop-ddl.sql；当前编译生成测试仍在，旧 ChangeLoop IT 不在 | `A/ProjectGenerationTest.java`:24<br>`A/SampleCorpusTest.java`:19 | 低：DDL/写关系测试基础均有，补组合 IT |
| A33 `sir-toolchain-application/src/test/resources/valid/course-admin.sir` | 参:course.write-slice | 课程局部更新/版本 | 1/3 | PATCH、Optional | 全：course-admin-ddl.sql | `A/SampleCorpusTest.java`:18<br>`A/conformance/WriteSliceBusinessConformanceIT.java`:57 | 已用 |
| A34 `sir-toolchain-application/src/test/resources/valid/course-catalog.sir` | 参:course.query-slice | 课程分页查询 | 1/1 | 分页、Optional | 全：course-catalog-ddl.sql | `A/SampleCorpusTest.java`:18<br>`A/conformance/QuerySliceBusinessConformanceIT.java`:58 | 已用 |
| A35 `sir-toolchain-application/src/test/resources/valid/course-enrollment.sir` | 参:course.relation-slice | 课程关联投影/筛选 | 3/1 | 分页、关联any、乘法、计算视图 | 全：course-enrollment-ddl.sql | `A/ComputedViewToolchainContractTest.java`:33<br>`A/SampleCorpusTest.java`:19 | 已用 |
| A36 `sir-toolchain-application/src/test/resources/valid/crud-access.sir` | 参:auth.crud-access | 角色 CRUD/实体可见范围 | 4/0 → 4/10 | 凭据、scope、CRUD展开(含分页) | 全：crud-access-ddl.sql | `A/CrudAccessContractTest.java`:160<br>`A/CrudAccessContractTest.java`:181 | 已用 |
| A37 `sir-toolchain-application/src/test/resources/valid/crud-feature.sir` | 未直接用作参/基 | 多实体 CRUD 模板等价展示 | 5/0 → 8/23 | 凭据、scope、Optional、CRUD展开(含分页) | 部分：现成小切片 DDL 不覆盖全部 Owner/Note/Item/Post 等字段 | `A/CrudAccessContractTest.java`:202<br>`A/CrudFeatureTest.java`:46 | 高：多个领域拼接，主要验证 feature，不是独立业务 |
| A38 `sir-toolchain-application/src/test/resources/valid/crud-written.sir` | 未直接用作参/基 | 多实体 CRUD 手写等价展示 | 5/20 → 8/23 | 分页、PATCH、凭据、scope、Optional、删除 | 部分：同 feature 版本 | `A/CrudFeatureTest.java`:39<br>`A/CrudFeatureTest.java`:47 | 高：与 feature 版本等价，不另出题 |
| A39 `sir-toolchain-application/src/test/resources/valid/decimal-group.sir` | 未直接用作参/基 | 类别分组金额统计 | 3/1 | 分页、聚合、关联any、scope、金额、Optional | 部分：money-ddl.sql 的类别/条目不是完全同结构；不能因同名直接用 | `A/conformance/GroupedSumContractTest.java`:56 | 中：需 Account/Category/Entry 的匹配 DDL |
| A40 `sir-toolchain-application/src/test/resources/valid/default-value.sir` | 未直接用作参/基 | 默认值机制 | 1/1 | 默认值 | 无专用 DDL | `A/SemanticTypeDeclarationTest.java`:47 | 高：一实体一能力示例 |
| A41 `sir-toolchain-application/src/test/resources/valid/delete-step.sir` | 未直接用作参/基 | 单表删除机制 | 1/2 | 删除 | 无适配 DDL；现有 note 表多了写入约束，不能仅按表名判断 | `A/SemanticTypeDeclarationTest.java`:40 | 高：只有载入/删除流程 |
| A42 `sir-toolchain-application/src/test/resources/valid/dual-token-base.sir` | 基:auth.dual-token.rotation | 登录题之前的用户/笔记底稿 | 2/3 | 基本载入/声明 | 全：dual-token-ddl.sql 子集 | 未见直接资源载入；不等于已执行通过 | 高：已用作 base_source，不是新题 |
| A43 `sir-toolchain-application/src/test/resources/valid/dual-token-feature.sir` | 未直接用作参/基 | 双令牌 feature 等价版本 | 2/3 → 5/6 | 凭据 | 全：dual-token-ddl.sql | `A/FeatureUseTest.java`:49<br>`A/FeatureUseTest.java`:68 | 高：现有登录题的另一写法 |
| A44 `sir-toolchain-application/src/test/resources/valid/dual-token-written.sir` | 参:auth.dual-token.rotation | 双令牌登录/轮换 | 5/6 | 守卫更新、凭据 | 全：dual-token-ddl.sql | `A/DeploymentPackageApplicationTest.java`:452<br>`A/FeatureUseTest.java`:48 | 已用 |
| A45 `sir-toolchain-application/src/test/resources/valid/entity-extension.sir` | 未直接用作参/基 | 实体 extend/default 机制 | 1/1 | 局部扩展、Optional | 无专用完整 DDL | `A/SemanticTypeDeclarationTest.java`:63 | 高：声明扩展示例 |
| A46 `sir-toolchain-application/src/test/resources/valid/entity-scope.sir` | 未直接用作参/基 | 单实体可见范围机制 | 1/3 | scope | 部分：普通 note DDL 无对应 scope 字段 | `A/SemanticTypeDeclarationTest.java`:55 | 高：已被完整 CRUD/部署软件覆盖 |
| A47 `sir-toolchain-application/src/test/resources/valid/fault-matrix-dirs-base.sir` | 未直接用作参/基 | 单 Goods 故障矩阵底稿/候选 | 1/0 | 基本载入/声明 | 全：campus-market-ddl.sql 的 goods 可用；无完整业务 | `A/SampleCorpusTest.java`:22 | 高：0–1 能力，基础设施 fixture |
| A48 `sir-toolchain-application/src/test/resources/valid/fault-matrix-dirs-candidate.sir` | 未直接用作参/基 | 单 Goods 故障矩阵底稿/候选 | 1/1 | 基本载入/声明 | 全：campus-market-ddl.sql 的 goods 可用；无完整业务 | `A/SampleCorpusTest.java`:22 | 高：0–1 能力，基础设施 fixture |
| A49 `sir-toolchain-application/src/test/resources/valid/g9-topic-conformance.sir` | 参:library.library-borrowing | 完整图书馆+市场目录组合 | 6/12 → 9/26 | 分页、守卫更新、凭据、关联any、行锁、金额、乘法、计算视图、Optional、删除、CRUD展开(含分页)、关联count视图 | 全：library-ddl.sql | `A/RelatedCountProjectGraphContractTest.java`:22<br>`A/conformance/G9TopicContractTest.java`:11 | 已用 |
| A50 `sir-toolchain-application/src/test/resources/valid/guarded-transition.sir` | 参:auth.guarded-transition | 一次性凭据轮换 | 1/2 | 守卫更新 | 全：credential-rotation-ddl.sql | `A/conformance/GuardedTransitionBusinessConformanceIT.java`:45 | 已用 |
| A51 `sir-toolchain-application/src/test/resources/valid/money.sir` | 参:finance.money | 金额/聚合/月报/预算组合 | 10/19 → 13/32 | 分页、PATCH、守卫更新、聚合、month、凭据、关联any、scope、金额、Optional、CRUD展开(含分页) | 全：money-ddl.sql | `A/DecimalSumToolchainContractTest.java`:34<br>`A/MoneyToolchainContractTest.java`:19 | 已用 |
| A52 `sir-toolchain-application/src/test/resources/valid/multi-course/modules/course.sir` | 未直接用作参/基 | 多文件课程管理项目（模块片段） | 1/0 | Optional | 全：项目合计可用 change-loop-ddl.sql | `A/CompilationServiceTest.java`:114<br>`A/CompilationServiceTest.java`:124 | 低（项目整体）/高（单个片段不能单独出题） |
| A53 `sir-toolchain-application/src/test/resources/valid/multi-course/modules/enrollment.sir` | 未直接用作参/基 | 多文件课程管理项目（模块片段） | 1/0 | 基本载入/声明 | 全：项目合计可用 change-loop-ddl.sql | `A/CompilationServiceTest.java`:43<br>`A/ComputedViewToolchainContractTest.java`:33 | 低（项目整体）/高（单个片段不能单独出题） |
| A54 `sir-toolchain-application/src/test/resources/valid/multi-course/modules/student.sir` | 未直接用作参/基 | 多文件课程管理项目（模块片段） | 1/0 | 基本载入/声明 | 全：项目合计可用 change-loop-ddl.sql | `A/ProjectGenerationTest.java`:60<br>`A/ProjectGenerationTest.java`:61 | 低（项目整体）/高（单个片段不能单独出题） |
| A55 `sir-toolchain-application/src/test/resources/valid/multi-course/project.sir` | 未直接用作参/基 | 多文件课程管理项目 | 0/4（项目合计 3/4） | 分页、PATCH、关联any、Optional | 全：项目合计可用 change-loop-ddl.sql | `A/ProjectTestSupport.java`:16<br>`A/conformance/ProjectVerificationChainIT.java`:59 | 低（项目整体）/高（单个片段不能单独出题） |
| A56 `sir-toolchain-application/src/test/resources/valid/multiplication.sir` | 间接：shop.multiplication / development（§1.1） | 乘法/容量/简化预约组合 | 7/10 | 守卫更新、repeat、行锁、金额、乘法、计算视图 | 全：multiplication-ddl.sql 的 calculation/product/receipt/receipt_line/course/room/booking 子集 | `A/ExplicitLockToolchainContractTest.java`:23<br>`A/MultiplicationToolchainContractTest.java`:18 | 低（但已被 shop-conformance 组合占用） |
| A57 `sir-toolchain-application/src/test/resources/valid/related-request-values.sir` | 未直接用作参/基 | 关联谓词绑定请求文本 | 2/1 | 分页、关联any | 部分：course-enrollment-ddl.sql 无 note，须补；已有编译生成入口 | `A/SampleCorpusTest.java`:41 | 中：需 enrollment.note 等字段 |
| A58 `sir-toolchain-application/src/test/resources/valid/rename-course-search.sir` | 未直接用作参/基 | 课程能力/视图更名素材 | 1/2 | 基本载入/声明 | 无匹配完整 DDL；同名 course 列不一致 | `A/SampleCorpusTest.java`:22 | 高：变更回归，不新增领域 |
| A59 `sir-toolchain-application/src/test/resources/valid/semantic-types.sir` | 未直接用作参/基 | 语义类型声明覆盖 | 1/0 | 基本载入/声明 | 无适配 DDL | `A/SemanticTypeDeclarationTest.java`:31<br>`A/SemanticTypeDeclarationTest.java`:70 | 高：0 能力、非业务后端 |
| A60 `sir-toolchain-application/src/test/resources/valid/shop-conformance.sir` | 参:shop.multiplication | 完整商店+算术/预约组合 | 14/26 → 17/30 | 分页、守卫更新、each、repeat、凭据、行锁、金额、乘法、计算视图、Optional、删除 | 全：multiplication-ddl.sql | `A/conformance/MultiplicationBusinessConformanceIT.java`:18<br>`A/conformance/ShopCancellationContractTest.java`:14 | 已用 |
| A61 `sir-toolchain-application/src/test/resources/valid/time-zone.sir` | 未直接用作参/基 | 时区偏好纯机制 | 1/3 | Optional | 无独立完整 DDL；账本的 user.time_zone 可作实现素材 | 未见直接资源载入；不等于已执行通过 | 高：少量读写/转换，已在账本场景出现 |
| A62 `sir-toolchain-application/src/test/resources/valid/token-desk.sir` | 参:auth.token-desk | 凭据管理/登录退出 | 2/6 | 守卫更新 | 全：token-desk-ddl.sql | `A/conformance/TokenDeskBusinessConformanceIT.java`:40 | 已用 |
| A63 `sir-toolchain-application/src/test/resources/valid/user-feature.sir` | 未直接用作参/基 | 自定义 Tagging 功能 | 1/0 → 2/1 | 自定义feature | 部分：note 不是 tag，缺展开后 tag 表 | `A/CrudAccessContractTest.java`:185<br>`A/UserFeatureTest.java`:33 | 高：模板展开示范、缺完整业务 |

### 1.1 不能只按路径判断“未用”

| 原始软件 | 已有同源使用与证据 | 对新题的限制 |
|---|---|---|
| `sir-toolchain-application/src/test/resources/topics/ledger/project.sir` | `A/conformance/CalendarLedgerContractTest.java`:20–29 逐声明确认月报/预算相关代码来自实际 ledger；`MoneyBusinessConformanceIT.java`:44–57 执行 DecimalSumChecks、GroupedSumChecks、CalendarLedgerChecks | 聚合、月报、时区不是未实现、未测试；完整账本可补端到端组合，但必须留在 finance.money 的 test，不能供 train |
| `sir-toolchain-application/src/test/resources/topics/shop/project.sir` | `A/conformance/ShopSourceComposition.java`:16–34 正式重命名 Product→ShopProduct 后把整个 shop 合入 `sir-toolchain-application/src/test/resources/valid/shop-conformance.sir`；`MultiplicationBusinessConformanceIT.java`:56–61 调用取消、结账、优惠券检查 | 原始 shop 虽未直接作为 reference_source，实际上整份业务已进入 development；不另推荐成独立训练家族 |
| `sir-toolchain-application/src/test/resources/topics/library/project.sir` | `A/conformance/G9TopicComposition.java`:14–22 保留完整 library 并加入市场切片；`LibraryBorrowingBusinessConformanceIT.java`:60–65 调用借还、预约、计数检查 | 完整 library 已用于 test，不把原文件再算新领域 |
| `sir-toolchain-application/src/test/resources/topics/market/project.sir` | 同一个 G9 composition :16–30 复制 ItemStatus、Item、Crud、BrowseInput、BrowseItems 五项；library-ddl.sql:11–22 已有 item | 市场的买卖流程仍可补，但 Item 目录部分已在 test；新题同组隔离 |
| `sir-toolchain-application/src/test/resources/valid/multiplication.sir` | `ShopSourceComposition.java`:23–34 作为组合底稿；Multiplication IT 另有计数/预约检查 | 即使它不是 tasks 的直接 reference_source，也不是未见过的独立算术题 |

### 1.2 DDL 可用性的重要差异

| 软件 | 现成资产 | 仍缺什么 |
|---|---|---|
| 完整 ledger | money-ddl.sql 中 user/category/entry/budget 与三种认证表；日历和分组检查 | 需抽出/固定最小子集、补分类 CRUD、区间列表、总和/平均与月份之间的一致性断言；不是从零设计金额 SQL |
| 完整 booking | multiplication-ddl.sql:8–9 只有简化 room/booking | 原主题的 room.location/capacity、booking.booker/purpose、认证/角色及对应 seed，不能直接套简化表 |
| 完整 market | library-ddl.sql:11–22 有 item，认证表也有 | 缺 trade_order；完整交易的唯一冲突、状态推进及回滚检查 |
| 完整 shop | multiplication-ddl.sql:12 起大部分已存在 | 原主题叫 product，组合后叫 shop_product；同名 product 还被精确乘法样例占用。不能把现有 DDL 原样套给原 topic |
| 课程管理+关联 | change-loop-ddl.sql:30–59 有 course 的 description/version 和 student/enrollment | 旧 ChangeLoop IT 已退役；当前只保留 DDL及独立写/关系判卷基础，新组合仍需自己的 IT |
| 校园发布 Goods | campus-market-ddl.sql:28–37 只有 goods | 当前发布流程只把外部 actor 主键写成 seller_id，不读 user 表；若新题加入用户资料读取/建用户，需要补 user DDL。简单发布/列表本身偏小 |
| 部署 V3 | `.github/scripts/deployment-smoke.py`:296–356 已有收藏/并发/Optional HTTP 检查；verify.yml:286–295 使用 V3 导出 | 没有专用静态 favorite DDL；需要把部署烟测中的业务断言整理为 BusinessSliceHarness IT，不能称“已经有独立判卷题” |

## 2. 其他模块中的参考与机制样例

下面按**源码字节一致的组**合并展示，组内每个路径都列出；相同内容不重复评估业务领域/E/C/特性/DDL/成本。若与 §1 同内容，直接继承其完整评估，并分别给出其他模块的测试入口。相同文件名但内容不同（例如小金额样例和 Application 的大组合）不合并。

| 组号 / 组内全部完整路径 | 既有题关系 | 业务领域 | E/C | 源码特性 | DDL | 已有测试 | 新题成本 |
|---|---|---|---|---|---|---|---|
| S1 `kcg-cli/src/test/resources/io/kcg/cli/campus-market.sir`<br>`sir-generator-spring-boot/src/test/resources/valid/campus-market.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/campus-market.sir`<br>`sir-parser/src/test/resources/valid/campus-market.sir`<br>`sir-semantic/src/test/resources/valid/campus-market.sir` | 同 A26（未直接用作参/基） | 校园 Goods 发布/列表变更版本 | 2/1 | 金额 | campus-market-ddl.sql 仅 goods；发布只写外部 seller_id，无用户读取。加用户流程须补 user 表 | `kcg-cli/src/test/java/io/kcg/cli/KcgCheckTest.java`:20<br>`kcg-cli/src/test/java/io/kcg/cli/KcgWorkflowCommandsTest.java`:155 | 高：1–3 能力，多个版本同一小业务 |
| S2 `kcg-cli/src/test/resources/io/kcg/cli/crud-feature.sir`<br>`sir-generator-spring-boot/src/test/resources/valid/crud-feature.sir` | 同 A37（未直接用作参/基） | 多实体 CRUD 模板等价展示 | 5/0 → 8/23 | 凭据、scope、Optional、CRUD展开(含分页) | 部分：现成小切片 DDL 不覆盖全部 Owner/Note/Item/Post 等字段 | `kcg-cli/src/test/java/io/kcg/cli/KcgWorkflowCommandsTest.java`:139<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratedProjectOfflineCompilationTest.java`:207 | 高：多个领域拼接，主要验证 feature，不是独立业务 |
| S3 `kcg-cli/src/test/resources/io/kcg/cli/dual-token-base.sir` | 未直接作为任务参考 | 用户注册/笔记旧底稿 | 2/3 | 基本载入/声明 | dual-token-ddl.sql 子集；CLI 版本有细节差异 | `kcg-cli/src/test/java/io/kcg/cli/KcgPackageCommandTest.java`:17<br>`kcg-cli/src/test/java/io/kcg/cli/KcgPackageCommandTest.java`:36 | 高：已有鉴权题同源底稿 |
| S4 `kcg-cli/src/test/resources/io/kcg/cli/multi-course/modules/course.sir` | 同 A52（未直接用作参/基） | 多文件课程管理项目（模块片段） | 1/0 | Optional | 全：项目合计可用 change-loop-ddl.sql | `kcg-cli/src/test/java/io/kcg/cli/KcgVersionCommandsTest.java`:29<br>`kcg-cli/src/test/java/io/kcg/cli/KcgWorkflowCommandsTest.java`:21 | 低（项目整体）/高（单个片段不能单独出题） |
| S5 `kcg-cli/src/test/resources/io/kcg/cli/multi-course/modules/enrollment.sir` | 同 A53（未直接用作参/基） | 多文件课程管理项目（模块片段） | 1/0 | 基本载入/声明 | 全：项目合计可用 change-loop-ddl.sql | `kcg-cli/src/test/java/io/kcg/cli/KcgVersionCommandsTest.java`:29<br>`kcg-cli/src/test/java/io/kcg/cli/KcgWorkflowCommandsTest.java`:21 | 低（项目整体）/高（单个片段不能单独出题） |
| S6 `kcg-cli/src/test/resources/io/kcg/cli/multi-course/modules/student.sir` | 同 A54（未直接用作参/基） | 多文件课程管理项目（模块片段） | 1/0 | 基本载入/声明 | 全：项目合计可用 change-loop-ddl.sql | `kcg-cli/src/test/java/io/kcg/cli/KcgVersionCommandsTest.java`:29<br>`kcg-cli/src/test/java/io/kcg/cli/KcgWorkflowCommandsTest.java`:21 | 低（项目整体）/高（单个片段不能单独出题） |
| S7 `kcg-cli/src/test/resources/io/kcg/cli/multi-course/project.sir` | 同 A55（未直接用作参/基） | 多文件课程管理项目 | 0/4（项目合计 3/4） | 分页、PATCH、关联any、Optional | 全：项目合计可用 change-loop-ddl.sql | `kcg-cli/src/test/java/io/kcg/cli/KcgVersionCommandsTest.java`:45–56（遍历四文件并执行 ToolchainApplication） | 低（项目整体）/高（单个片段不能单独出题） |
| S8 `sir-change/src/test/resources/edit/campus-market.sir` | 同 A23（未直接用作参/基） | 校园 Goods 发布/列表变更版本 | 2/2 | 金额 | campus-market-ddl.sql 仅 goods；发布只写外部 seller_id，无用户读取。加用户流程须补 user 表 | `sir-change/src/test/java/io/kcg/sir/change/edit/SourceEditorTest.java`:106 | 高：1–3 能力，多个版本同一小业务 |
| S9 `sir-change/src/test/resources/edit/course-catalog.sir` | 未直接作为任务参考 | 课程编辑 fixture | 3/2 | 分页、PATCH、关联any | 部分：change-loop-ddl.sql 的 student 缺 version | `sir-change/src/test/java/io/kcg/sir/change/edit/EditFixtures.java`:14 | 高：编辑回归、课程领域已用 |
| S10 `sir-change/src/test/resources/valid/bounded-each.sir`<br>`sir-generator-spring-boot/src/test/resources/valid/bounded-each.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/bounded-each.sir`<br>`sir-semantic/src/test/resources/valid/bounded-each.sir` | 未直接作为任务参考 | 批次逐行返库/有界事务 | 3/1 | 守卫更新、each、行锁、scope | 部分：product.stock 可参考，缺完整 batch/line DDL | `sir-change/src/test/java/io/kcg/sir/change/edit/BoundedEachEditingTest.java`:38<br>`sir-change/src/test/java/io/kcg/sir/change/edit/BoundedEachEditingTest.java`:51 | 中：缺 batch/line，需边界/回滚 IT |
| S11 `sir-change/src/test/resources/valid/calendar-month.sir`<br>`sir-generator-spring-boot/src/test/resources/valid/calendar-month.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/calendar-month.sir`<br>`sir-semantic/src/test/resources/valid/calendar-month.sir` | 同 A18（未直接用作参/基） | 月份边界纯计算 | 0/2 | month、Optional | 不需要数据库表（无持久化实体） | `sir-change/src/test/java/io/kcg/sir/change/edit/CalendarMonthEditingTest.java`:37<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/CalendarMonthRuntimeTest.java`:17 | 高：0 实体，机制样例 |
| S12 `sir-change/src/test/resources/valid/decimal-group.sir`<br>`sir-generator-spring-boot/src/test/resources/valid/decimal-group.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/decimal-group.sir`<br>`sir-semantic/src/test/resources/valid/decimal-group.sir` | 同 A39（未直接用作参/基） | 类别分组金额统计 | 3/1 | 分页、聚合、关联any、scope、金额、Optional | 部分：money-ddl.sql 的类别/条目不是完全同结构；不能因同名直接用 | `sir-change/src/test/java/io/kcg/sir/change/edit/GroupedSumEditingTest.java`:44<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GroupedSumRuntimeTest.java`:25 | 中：需 Account/Category/Entry 的匹配 DDL |
| S13 `sir-change/src/test/resources/valid/decimal-sum.sir`<br>`sir-generator-spring-boot/src/test/resources/valid/decimal-sum.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/decimal-sum.sir`<br>`sir-semantic/src/test/resources/valid/decimal-sum.sir` | 非直接参；资金/统计机制与 test 同源需隔离 | 金额汇总/标签过滤机制 | 3/2 | 聚合、关联any、scope、金额、Optional | 无完整匹配 DDL；money 的 measurement/measurement_tag 是另一映射 | `sir-change/src/test/java/io/kcg/sir/change/edit/DecimalSumEditingTest.java`:45<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/DecimalSumRuntimeTest.java`:16 | 中：缺 Owner/Entry/Tag 完整匹配表 |
| S14 `sir-change/src/test/resources/valid/explicit-lock.sir`<br>`sir-generator-spring-boot/src/test/resources/valid/explicit-lock.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/explicit-lock.sir`<br>`sir-semantic/src/test/resources/valid/explicit-lock.sir` | 非直接参；商店/预约 development 同源 | 简化房间区间互斥 | 2/1 | 行锁 | 全：multiplication-ddl.sql 的 room/booking 子集；补对应种子即可 | `sir-change/src/test/java/io/kcg/sir/change/edit/ExplicitLockEditingTest.java`:15<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/ExplicitLockRuntimeTest.java`:92 | 低（但同预约机制已进 development） |
| S15 `sir-change/src/test/resources/valid/scalar-queries.sir`<br>`sir-generator-spring-boot/src/test/resources/valid/scalar-queries.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/scalar-queries.sir`<br>`sir-semantic/src/test/resources/valid/scalar-queries.sir` | 未直接作为任务参考 | 标签评分的 exists/count 机制 | 4/2 | exists/count、分页、关联any、scope、Optional | 无 owner/entry/tag/entry_tag 的完整配套 DDL | `sir-change/src/test/java/io/kcg/sir/change/edit/ScalarQueryEditingTest.java`:15<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorEntityArtifactInvarianceTest.java`:69 | 高：机制小样例，非完整软件流程 |
| S16 `sir-change/src/test/resources/valid/time-zone.sir`<br>`sir-generator-spring-boot/src/test/resources/valid/time-zone.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/time-zone.sir`<br>`sir-semantic/src/test/resources/valid/time-zone.sir` | 同 A61（未直接用作参/基） | 时区偏好纯机制 | 1/3 | Optional | 无独立完整 DDL；账本的 user.time_zone 可作实现素材 | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/TimeZonePatchRuntimeTest.java`:13<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/TimeZoneRuntimeTest.java`:15 | 高：少量读写/转换，已在账本场景出现 |
| S17 `sir-generator-spring-boot/src/test/resources/valid/actor-role.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/actor-role.sir` | 未直接作为任务参考 | 角色控制发布/改名/删除 Goods | 2/3 | actor 角色、归属检查、普通更新/硬删除 | 部分：campus-market goods 还要求 price/status，不能直接用于此 create | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratedProjectOfflineCompilationTest.java`:225<br>`sir-lowering-spring-boot/src/test/java/io/kcg/sir/lowering/springboot/ActorRowLoweringTest.java`:17 | 中：需匹配 User/Goods 的表 |
| S18 `sir-generator-spring-boot/src/test/resources/valid/atomic-counter.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/atomic-counter.sir` | 未直接作为任务参考 | 课程容量计数机制 | 1/2 | 守卫更新 | 无同结构专用 DDL；需核对 name/capacity/enrolled，semantic 版另有 fee | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorAtomicCounterContractTest.java`:15<br>`sir-lowering-spring-boot/src/test/java/io/kcg/sir/lowering/springboot/AtomicCounterLoweringTest.java`:19 | 高：已被课程/商店计数覆盖 |
| S19 `sir-generator-spring-boot/src/test/resources/valid/bearer-credential.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/bearer-credential.sir`<br>`sir-semantic/src/test/resources/valid/bearer-credential.sir` | 同 A17（参:auth.bearer-credential） | Bearer 凭据/笔记 | 3/5 | 凭据 | 全：bearer-credential-ddl.sql | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratedProjectOfflineCompilationTest.java`:186<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorCredentialContractTest.java`:19 | 已用 |
| S20 `sir-generator-spring-boot/src/test/resources/valid/built-in-functions.sir`<br>`sir-semantic/src/test/resources/valid/built-in-functions.sir` | 非直接参；library/test 同源 | 借阅罚款/日期函数机制 | 1/2 | 守卫更新、行锁、金额、乘法、计算视图、Optional | 部分：library loan 有更多写入必需列，示例另有 start_day/end_day，不能直接套 | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/BuiltInFunctionRuntimeTest.java`:41<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/BuiltInFunctionSemanticsTest.java`:15 | 高：小机制且与 library 同域 |
| S21 `sir-generator-spring-boot/src/test/resources/valid/cart-checkout.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/cart-checkout.sir`<br>`sir-semantic/src/test/resources/valid/cart-checkout.sir` | 非直接参；商店/预约 development 同源 | 小购物车结账/清空 | 5/2 | 守卫更新、each、行锁、金额、乘法、删除 | 部分：multiplication-ddl.sql 的表可参考，但 product 精度/其他实体列不同 | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/LoopBodyRuntimeTest.java`:65<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/LoopBodySemanticsTest.java`:16 | 中（同商店 development，非独立家族） |
| S22 `sir-generator-spring-boot/src/test/resources/valid/compound-find.sir` | 未直接作为任务参考 | 账号状态布尔查询 | 1/1 | 复合 and/or/not、枚举/Flag 筛选 | 无匹配 user.state/enabled DDL | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorArchitectureRegressionTest.java`:34<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorControllerTransportContractTest.java`:14 | 高：一条纯筛选 |
| S23 `sir-generator-spring-boot/src/test/resources/valid/computed-views.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/computed-views.sir`<br>`sir-semantic/src/test/resources/valid/computed-views.sir` | 未直接作为任务参考 | 课程/课时计算与嵌套视图 | 2/7 | 分页、金额、乘法、计算视图、Optional | 部分：已有 course 非同结构，缺 lesson | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/ComputedViewRuntimeTest.java`:25<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/ComputedViewRuntimeTest.java`:103 | 中：需 lesson 与匹配 course 表 |
| S24 `sir-generator-spring-boot/src/test/resources/valid/course-admin.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/course-admin.sir`<br>`sir-parser/src/test/resources/valid/course-admin.sir`<br>`sir-semantic/src/test/resources/valid/course-admin.sir` | 同 A33（参:course.write-slice） | 课程局部更新/版本 | 1/3 | PATCH、Optional | 全：course-admin-ddl.sql | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratedProjectOfflineCompilationTest.java`:70<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorDeterminismMatrixTest.java`:21 | 已用 |
| S25 `sir-generator-spring-boot/src/test/resources/valid/course-catalog.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/course-catalog.sir`<br>`sir-parser/src/test/resources/valid/course-catalog.sir` | 同 A34（参:course.query-slice） | 课程分页查询 | 1/1 | 分页、Optional | 全：course-catalog-ddl.sql | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratedProjectOfflineCompilationTest.java`:49<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorDeterminismMatrixTest.java`:20 | 已用 |
| S26 `sir-generator-spring-boot/src/test/resources/valid/course-enrollment.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/course-enrollment.sir`<br>`sir-parser/src/test/resources/valid/course-enrollment.sir`<br>`sir-semantic/src/test/resources/valid/course-enrollment.sir` | 未直接作为任务参考 | 早期课程关联查询 | 3/1 | 分页、关联any | 全：course-enrollment-ddl.sql；相对 Application 缺新增计算字段，不影响表 | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorDeterminismMatrixTest.java`:22<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorRelationSliceContractTest.java`:18 | 低（但已是现有 relation-slice 同源形态） |
| S27 `sir-generator-spring-boot/src/test/resources/valid/decimal-average.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/decimal-average.sir`<br>`sir-semantic/src/test/resources/valid/decimal-average.sir` | 非直接参；资金/统计机制与 test 同源需隔离 | 按账户/类别计算金额均值 | 3/2 | 分页、聚合、关联any、金额、Optional | 无完整匹配 DDL；同名 category/entry 字段不同，另需 quantity/occurred_at | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/AverageRuntimeTest.java`:14<br>`sir-lowering-spring-boot/src/test/java/io/kcg/sir/lowering/springboot/AverageLoweringTest.java`:13 | 中：需 Account/Category/Entry 的完整表 |
| S28 `sir-generator-spring-boot/src/test/resources/valid/entity-dto-contract.sir` | 未直接作为任务参考 | 实体/DTO 类型矩阵 | 2/0 | 金额、Optional | 无适配 DDL | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorEntityDtoContractTest.java`:26 | 高：0 能力、非业务流程 |
| S29 `sir-generator-spring-boot/src/test/resources/valid/guarded-update.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/guarded-update.sir`<br>`sir-semantic/src/test/resources/valid/guarded-update.sir` | 未直接作为任务参考 | UUID 凭据守卫轮换 | 1/1 | 守卫更新 | 现有 credential-rotation-ddl.sql:10 是 BIGINT 主键，此例为 Token，不能直接套 | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratedProjectOfflineCompilationTest.java`:150<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorGuardedUpdateContractTest.java`:16 | 高：小机制、现有凭据题已覆盖 |
| S30 `sir-generator-spring-boot/src/test/resources/valid/money.sir` | 非直接参；资金/统计机制与 test 同源需隔离 | Wallet/VersionedWallet 小金额切片 | 2/8 | PATCH、守卫更新、金额、Optional | 全：money-ddl.sql 的 wallet/versioned_wallet 子集 | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/DecimalSchemaVerifierRuntimeTest.java`:35<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/DeploymentDependencyPrimingTest.java`:11 | 低（但属于 finance.money/test 的已有切片） |
| S31 `sir-generator-spring-boot/src/test/resources/valid/multiplication.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/multiplication.sir` | 非直接参；商店/预约 development 同源 | 早期精确乘法/购买切片 | 4/5 | 守卫更新、金额、乘法 | 全：multiplication-ddl.sql 的 calculation/product/receipt/receipt_line 子集 | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/MultiplicationRuntimeTest.java`:19<br>`sir-lowering-spring-boot/src/test/java/io/kcg/sir/lowering/springboot/MultiplicationLoweringTest.java`:23 | 低（但已被 Application 大组合吸收） |
| S32 `sir-generator-spring-boot/src/test/resources/valid/optional-payload.sir` | 未直接作为任务参考 | Optional 参数载荷/查询机制 | 2/2 | 关联any、Optional | 无 Entry.key/score 与 Link.marker 的配套 DDL | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/OptionalPayloadRuntimeTest.java`:19<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/OptionalPayloadRuntimeTest.java`:27 | 高：纯 null/类型绑定回归 |
| S33 `sir-generator-spring-boot/src/test/resources/valid/optional-values.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/optional-values.sir`<br>`sir-semantic/src/test/resources/valid/optional-values.sir` | 未直接作为任务参考 | Optional 比较/守卫机制 | 1/3 | 分页、守卫更新、Optional | 无 Entry.score/due_at 专用 DDL | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorOptionalValueContractTest.java`:17<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorOptionalValueContractTest.java`:62 | 高：纯机制，完整收藏场景更合适 |
| S34 `sir-generator-spring-boot/src/test/resources/valid/related-count.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/related-count.sir`<br>`sir-semantic/src/test/resources/valid/related-count.sir` | 非直接参；library/test 同源 | 图书目录/副本数量 | 3/1 → 3/6 | 分页、scope、计算视图、CRUD展开(含分页)、关联count视图 | 部分：小示例的 Book 只写 title，但 library DDL 还要求 isbn/author；copy 表另缺 removed，不能直接套 CRUD | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/RelatedCountRuntimeTest.java`:16<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/RelatedCountRuntimeTest.java`:108 | 中（同 library/test，不独立 train） |
| S35 `sir-generator-spring-boot/src/test/resources/valid/row-with-nested-views.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/row-with-nested-views.sir` | 未直接作为任务参考 | 作者/图书/书评嵌套输出 | 3/3 | Row 返回、嵌套外键/列表视图 | 无匹配 DDL；现有 library.book 为另一结构。可作后备查询题，流程较小 | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/RowWithNestedViewsGenerationTest.java`:16<br>`sir-lowering-spring-boot/src/test/java/io/kcg/sir/lowering/springboot/RowWithNestedViewsLoweringTest.java`:19 | 中：缺 author/book/review 的完整表 |
| S36 `sir-generator-spring-boot/src/test/resources/valid/secret-field.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/secret-field.sir`<br>`sir-semantic/src/test/resources/valid/secret-field.sir` | 未直接作为任务参考 | 密码注册/匹配机制 | 1/2 | Secret 哈希、matches、unique 字段载入 | 无匹配 account 完整 DDL（token-desk 的 account 字段不同） | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratedProjectOfflineCompilationTest.java`:168<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorSecretFieldContractTest.java`:29 | 高：已有多个鉴权题，独立增量低 |
| S37 `sir-generator-spring-boot/src/test/resources/valid/service-workflow-contract.sir` | 未直接作为任务参考 | 服务/传输步骤矩阵 | 1/3 | Optional | 无匹配 UUID User.state DDL | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorControllerTransportContractTest.java`:15<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorServiceWorkflowContractTest.java`:15 | 高：生成器合同，不是独立业务 |
| S38 `sir-generator-spring-boot/src/test/resources/valid/time-arithmetic.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/time-arithmetic.sir`<br>`sir-semantic/src/test/resources/valid/time-arithmetic.sir` | 未直接作为任务参考 | 工单时效/日期算术展示 | 1/2 | 日期/时间/数值算术、分页、金额 | 无 ticket 的配套 DDL | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratedProjectOfflineCompilationTest.java`:128<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorArithmeticContractTest.java`:15 | 高：偏表达式演示，没有完整工单业务 |
| S39 `sir-generator-spring-boot/src/test/resources/valid/unique-lookup.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/unique-lookup.sir`<br>`sir-semantic/src/test/resources/valid/unique-lookup.sir` | 未直接作为任务参考 | 账户多种唯一键查找 | 1/4 | 多类型 unique、按唯一字段载入、普通更新 | 无匹配 account 的 email/badge/token/nickname DDL | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratedProjectOfflineCompilationTest.java`:103<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorKeyedLoadContractTest.java`:18 | 高：键查找机制，业务很窄 |
| S40 `sir-generator-spring-boot/src/test/resources/valid/unit-output.sir` | 未直接作为任务参考 | Ping 无返回数据 | 0/1 | 基本载入/声明 | 不需要表 | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorArchitectureRegressionTest.java`:20<br>`sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorControllerTransportContractTest.java`:16 | 高：0 实体、无业务 |
| S41 `sir-generator-spring-boot/src/test/resources/valid/weekly-booking.sir`<br>`sir-lowering-spring-boot/src/test/resources/valid/weekly-booking.sir`<br>`sir-semantic/src/test/resources/valid/weekly-booking.sir` | 非直接参；商店/预约 development 同源 | 简化每周预约 | 2/1 | repeat、行锁、乘法 | 部分：现有 room 表缺 booked，需补 DDL | `sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/RepeatRuntimeTest.java`:54<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/RepeatSemanticsTest.java`:16 | 中（同 development 预约；完整 booking 更值得做） |
| S42 `sir-lowering-spring-boot/src/test/resources/valid/all-workflow-steps.sir`<br>`sir-parser/src/test/resources/valid/all-workflow-steps.sir`<br>`sir-semantic/src/test/resources/valid/all-workflow-steps.sir` | 未直接作为任务参考 | 全步骤 IR 覆盖 | 1/1 | 基本载入/声明 | 无匹配 UUID User.state DDL | `sir-lowering-spring-boot/src/test/java/io/kcg/sir/lowering/springboot/LoweredIrHardeningTest.java`:100<br>`sir-lowering-spring-boot/src/test/java/io/kcg/sir/lowering/springboot/LoweredIrHardeningTest.java`:124 | 高：IR 矩阵，不是业务 |
| S43 `sir-lowering-spring-boot/src/test/resources/valid/input-ref-test.sir` | 未直接作为任务参考 | Ref 输入类型机制 | 1/1 | Ref 输入声明、Unit 返回 | 无专用 DDL | `sir-lowering-spring-boot/src/test/java/io/kcg/sir/lowering/springboot/LoweredIrHardeningTest.java`:209 | 高：返回 Unit，无真实业务 |
| S44 `sir-lowering-spring-boot/src/test/resources/valid/persist-provenance.sir` | 未直接作为任务参考 | persist 来源/时序机制 | 1/3 | 基本载入/声明 | 无专用 User.name DDL | `sir-lowering-spring-boot/src/test/java/io/kcg/sir/lowering/springboot/LoweredIrHardeningTest.java`:172<br>`sir-lowering-spring-boot/src/test/java/io/kcg/sir/lowering/springboot/LoweredIrHardeningTest.java`:182 | 高：IR 测试素材 |
| S45 `sir-parser/src/test/resources/invalid/arbitrary-call.sir` | 负例 | 故意非法的解析输入 | 0/1 | 基本载入/声明 | 不适用：预期解析拒绝 | `sir-parser/src/test/java/io/kcg/sir/parser/SirParserTest.java`:76<br>`sir-parser/src/test/java/io/kcg/sir/parser/SirParserTest.java`:162 | 高：负例不能作参考 |
| S46 `sir-parser/src/test/resources/invalid/chained-comparison.sir` | 负例 | 故意非法的解析输入 | 0/1 | 基本载入/声明 | 不适用：预期解析拒绝 | `sir-parser/src/test/java/io/kcg/sir/parser/SirParserTest.java`:77 | 高：负例不能作参考 |
| S47 `sir-parser/src/test/resources/invalid/unsupported-target.sir` | 负例 | 故意非法的解析输入 | 0/0 | 基本载入/声明 | 不适用：预期解析拒绝 | `sir-parser/src/test/java/io/kcg/sir/parser/SirParserTest.java`:70 | 高：负例不能作参考 |
| S48 `sir-parser/src/test/resources/invalid/unsupported-version.sir` | 负例 | 故意非法的解析输入 | 0/0 | 基本载入/声明 | 不适用：预期解析拒绝 | `sir-parser/src/test/java/io/kcg/sir/parser/SirParserTest.java`:64 | 高：负例不能作参考 |
| S49 `sir-parser/src/test/resources/valid/unit-capability.sir`<br>`sir-semantic/src/test/resources/valid/unit-capability.sir` | 未直接作为任务参考 | Ping 只读机制 | 0/1 | 基本载入/声明 | 不需要表 | `sir-parser/src/test/java/io/kcg/sir/parser/SirParserTest.java`:94<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/TestSources.java`:106 | 高：无业务 |
| S50 `sir-semantic/src/main/resources/io/kcg/sir/semantic/feature/account-registration.sir` | 未直接作为任务参考 | 标准功能模板片段 | 模板增 0E/1C | 注册/唯一冲突 | 由被展开的实体决定；不是独立软件 | `sir-toolchain-application/src/test/java/io/kcg/sir/application/FeatureUseTest.java`:48–58 / `sir-toolchain-application/src/test/java/io/kcg/sir/application/CrudFeatureTest.java`:46–53（通过 use 间接载入） | 高：不可独立出题 |
| S51 `sir-semantic/src/main/resources/io/kcg/sir/semantic/feature/crud.sir` | 未直接作为任务参考 | 标准功能模板片段 | 模板增 0E/5C | CRUD/分页/PATCH/角色/归属策略 | 由被展开的实体决定；不是独立软件 | `sir-toolchain-application/src/test/java/io/kcg/sir/application/FeatureUseTest.java`:48–58 / `sir-toolchain-application/src/test/java/io/kcg/sir/application/CrudFeatureTest.java`:46–53（通过 use 间接载入） | 高：不可独立出题 |
| S52 `sir-semantic/src/main/resources/io/kcg/sir/semantic/feature/dual-token-account.sir` | 未直接作为任务参考 | 标准功能模板片段 | 模板只扩展账户字段 | 账户姓名/密码字段扩展 | 由被展开的实体决定；不是独立软件 | `sir-toolchain-application/src/test/java/io/kcg/sir/application/FeatureUseTest.java`:48–58 / `sir-toolchain-application/src/test/java/io/kcg/sir/application/CrudFeatureTest.java`:46–53（通过 use 间接载入） | 高：不可独立出题 |
| S53 `sir-semantic/src/main/resources/io/kcg/sir/semantic/feature/dual-token-login.sir` | 未直接作为任务参考 | 标准功能模板片段 | 模板增 3E/3C | 凭据/轮换/守卫/退出 | 由被展开的实体决定；不是独立软件 | `sir-toolchain-application/src/test/java/io/kcg/sir/application/FeatureUseTest.java`:48–58 / `sir-toolchain-application/src/test/java/io/kcg/sir/application/CrudFeatureTest.java`:46–53（通过 use 间接载入） | 高：不可独立出题 |
| S54 `sir-semantic/src/test/resources/valid/atomic-counter.sir` | 未直接作为任务参考 | 课程容量计数机制 | 1/2 | 守卫更新、金额 | 无同结构专用 DDL；需核对 name/capacity/enrolled，semantic 版另有 fee | `sir-semantic/src/test/java/io/kcg/sir/semantic/AtomicCounterSemanticsTest.java`:15 | 高：已被课程/商店计数覆盖 |
| S55 `sir-semantic/src/test/resources/valid/check-then-insert.sir` | 非直接参；商店/预约 development 同源 | 先查后插区间互斥警告素材 | 2/1 | 行锁 | 全：multiplication-ddl.sql 的 room/booking 子集；room 种子需提供 name | `sir-semantic/src/test/java/io/kcg/sir/semantic/CheckThenInsertWarningTest.java`:16 | 低（但现有预约同源，不另推荐） |
| S56 `sir-semantic/src/test/resources/valid/default-value.sir` | 同 A40（未直接用作参/基） | 默认值机制 | 1/1 | 默认值 | 无专用 DDL | `sir-semantic/src/test/java/io/kcg/sir/semantic/schema/StructureViewCoverageTest.java`:22<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/source/SirDeclarationWriterRoundTripTest.java`:28 | 高：一实体一能力示例 |
| S57 `sir-semantic/src/test/resources/valid/delete-step.sir` | 同 A41（未直接用作参/基） | 单表删除机制 | 1/2 | 删除 | 无适配 DDL；现有 note 表多了写入约束，不能仅按表名判断 | `sir-semantic/src/test/java/io/kcg/sir/semantic/schema/StructureViewCoverageTest.java`:22<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/source/SirDeclarationWriterRoundTripTest.java`:28 | 高：只有载入/删除流程 |
| S58 `sir-semantic/src/test/resources/valid/dual-token-feature.sir` | 同 A43（未直接用作参/基） | 双令牌 feature 等价版本 | 2/3 → 5/6 | 凭据 | 全：dual-token-ddl.sql | `sir-semantic/src/test/java/io/kcg/sir/semantic/schema/StructureViewCoverageTest.java`:22<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/source/SirDeclarationWriterRoundTripTest.java`:28 | 高：现有登录题的另一写法 |
| S59 `sir-semantic/src/test/resources/valid/entity-extension.sir` | 同 A45（未直接用作参/基） | 实体 extend/default 机制 | 1/1 | 局部扩展、Optional | 无专用完整 DDL | `sir-semantic/src/test/java/io/kcg/sir/semantic/schema/StructureViewCoverageTest.java`:22<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/source/SirDeclarationWriterRoundTripTest.java`:28 | 高：声明扩展示例 |
| S60 `sir-semantic/src/test/resources/valid/entity-scope.sir` | 同 A46（未直接用作参/基） | 单实体可见范围机制 | 1/3 | scope | 部分：普通 note DDL 无对应 scope 字段 | `sir-semantic/src/test/java/io/kcg/sir/semantic/schema/StructureViewCoverageTest.java`:22<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/source/SirDeclarationWriterRoundTripTest.java`:28 | 高：已被完整 CRUD/部署软件覆盖 |
| S61 `sir-semantic/src/test/resources/valid/identity-after-persist.sir` | 未直接作为任务参考 | 篮筐/票据主键时序 | 4/3 | repeat | 无 basket/line/ticket/receipt 的匹配 DDL | `sir-semantic/src/test/java/io/kcg/sir/semantic/IdentityAfterPersistSemanticsTest.java`:16 | 高：语义时序展示，未见完整交付/HTTP测试 |
| S62 `sir-semantic/src/test/resources/valid/persist-order.sir` | 未直接作为任务参考 | 选课计数与关联写入顺序 | 2/1 | 守卫更新 | 部分：现有完整 enrollment/简化 course 形状各不相同，不能仅按名复用 | `sir-semantic/src/test/java/io/kcg/sir/semantic/FailsOrderMessageTest.java`:12<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/PersistOrderSemanticsTest.java`:16 | 高：课程写序机制，现有题覆盖 |
| S63 `sir-semantic/src/test/resources/valid/semantic-types.sir` | 同 A59（未直接用作参/基） | 语义类型声明覆盖 | 1/0 | 基本载入/声明 | 无适配 DDL | `sir-semantic/src/test/java/io/kcg/sir/semantic/schema/StructureViewCoverageTest.java`:22<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/source/SirDeclarationWriterRoundTripTest.java`:28 | 高：0 能力、非业务后端 |
| S64 `sir-semantic/src/test/resources/valid/unique-key.sir` | 未直接作为任务参考 | 课程/选课联合唯一约束 | 3/1 | 联合 unique、引用载入、persist 唯一冲突 | 部分：需 course.term/联合键，现有课程 DDL 不完全相同 | `sir-semantic/src/test/java/io/kcg/sir/semantic/schema/StructureViewCoverageTest.java`:22<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/source/SirDeclarationWriterRoundTripTest.java`:28 | 高：机制且课程领域已用 |
| S65 `sir-semantic/src/test/resources/valid/user-feature.sir` | 同 A63（未直接用作参/基） | 自定义 Tagging 功能 | 1/0 → 2/1 | 自定义feature | 部分：note 不是 tag，缺展开后 tag 表 | `sir-semantic/src/test/java/io/kcg/sir/semantic/schema/StructureViewCoverageTest.java`:22<br>`sir-semantic/src/test/java/io/kcg/sir/semantic/source/SirDeclarationWriterRoundTripTest.java`:28 | 高：模板展开示范、缺完整业务 |

四个 `sir-semantic/src/main/resources/.../feature/*.sir` 是标准模板片段，不是可独立交付的后端；业务计数按模板“增加多少声明”描述。parser 的 invalid 文件是故意错误，测试应拒绝，不能拿它们当参考答案。纯 IR、DTO、主键时序等样例适合编译器回归，不因已有测试就自动值得出题。

## 3. 当前覆盖的缺口：不是“仓库完全没有”

`experiments/slm/drafts/teacher-error-census.md` §1、§2、§5 证明的是已有响应的错误分布：普通查询/any 作用域、凭据主体、统一可见性、必填、版本与持久化时点等。它**没有证明模型擅长聚合或循环**；附录 A 的 47 份教师响应来自 train/旧双令牌题，没有资金、商店、图书家族的教师评估。下表同时看任务合同和实际 oracle，不仅搜参考源码关键字。

| 特性 | 现有题/判卷占用 | 真正欠缺 | 优先软件 |
|---|---|---|---|
| sum/avg、标签存在筛选、分组分页 | finance.money / test：money-change-v1.json:255–276；Money IT:44–57 | 不是无题，而是 train 缺业务分布，现有 test 还是大混合；不能直接拿账本/DecimalSum 同源实现进 train | 完整 ledger 可补 test 场景；如果要补 train，需要另写独立非同源业务，而不是重命名 Entry/Measurement（本次不创作新软件） |
| month、时区、月预算 | finance.money / test；CalendarLedgerChecks:26–43 | 月份与 CRUD/区间/类别的一致性组合较薄；train 没有这类独立业务 | ledger（保持 test） |
| each 有界逐行写、整事务回滚 | shop.multiplication / development；ShopCheckoutChecks、ShopCancellationChecks，Multiplication IT:59–60 | 不是完全没有循环；train 没有独立的多行事务业务。bounded-each/cart-checkout 是同机制切片，不是凭换名字就独立 | 原 shop 不重复出题；未来需新的独立场景。机制样例列作素材，不冒充新的训练家族 |
| repeat + addDays、区间排他 | shop.multiplication / development；IntervalBookingChecks 与 `sir-toolchain-application/src/test/resources/valid/multiplication.sir` 的 BookWeekly | 当前混在大商店题里，完整预约的 booker/角色/取消/日程未独立判卷 | booking（保持 development） |
| exists/count 与 Optional 空比较、scope 全读取路径 | train 有选课 exists、关联过滤和 CRUD scope；部署 V3 有烟测但未成题 | census §5 暴露作用域/可见性；训练中缺评分空值、!= 与 not(==)、count/exists 一致性的完整用户流程 | 部署 V3 收藏（train，同旧双令牌家族） |
| 多对多标签、评论/发布/点赞跨行关系 | 关联题 train 主要是课程；census §2 有漏关联状态条件 | 不缺 any 语法例，缺非课程领域的多路径业务检查 | blog（新 family） |
| 可选负责人/到期日、成员权限、可选筛选组合 | 既有题有 Optional 文本/金额，但无项目协作题 | null/present 与业务归属、筛选的组合分布窄 | tasks（新 family） |
| PATCH 版本 + 关联分页同时出现 | train 的 write-slice/relation-slice 分开验 | census 有 patch/版本错和查询形状错；缺同一软件跨写入与关联读取的回归闭环 | course-admin-enrollment（train，同课程组） |

`count(关联)` 计算视图已有 library test 的 `LibraryCountChecks/LibraryCountReadBudget`，不能将 `related-count.sir` 宣称成完全未考的新特性。来源见 `LibraryBorrowingBusinessConformanceIT.java`:61–63。

## 4. 推荐前 7 份：family、集合与业务判卷方向

排序兼顾新增业务多样性、补机制组合缺口和同源隔离，不意味着七份都能进入 train。family/split 是建议，**本次不新增 task.json、不改现有分组**。

| 优先级与软件路径 | 理由与成本 | 建议 family / split | 业务 IT 应重点检查 |
|---|---|---|---|
| 1. `sir-toolchain-application/src/test/resources/topics/blog/project.sir` | **中**。完整内容业务，10E/22C；已有编译/生成 Java 的测试入口，缺独立完整 DDL；能增加非课程关联任务 | 新 `content.blog`，建议 train；改写/从零/后续变体整组。认证模板是公共基础能力，不把它的存在当整份博客已出过题 | 注册作者/分类/标签→建草稿→作者发布→匿名列表/详情一致；标签链接不能重复、重复关联不重复计根行；草稿不可见；评论归属与删除；重复点赞及计数、并发一致性；拒绝请求后 JDBC 核对未写入 |
| 2. `sir-toolchain-application/src/test/resources/topics/tasks/project.sir` | **中**。7E/17C；项目/成员/任务形成真实协作流，补 Optional 引用/时间和筛选组合；缺项目相关 DDL | 新 `collaboration.task-board`，建议 train；如需独立 dev 领域可整组改分，不能拆题型 | 项目创建/成员参与/创建任务；非成员拒绝；分配时校验同项目权限；未分配/无到期日/不同状态与优先级筛选；本人和项目任务边界；到期与未到期、翻页；更新状态与数据库一致。按实际工作流明确授权规则，不假定任意成员都能管理项目 |
| 3. `sir-toolchain-application/src/test/resources/topics/ledger/project.sir` | **中**（DDL/统计检查可复用，完整 CRUD 闭环仍需较多新检查）。7E/30C；可把现有资金大组合中的统计拆成清楚的个人记账闭环，补总额/平均/月份交叉核对 | 沿用 `finance.money`，**test**。已有月报/预算声明被 money 逐字嵌入，禁止另取新 family 放 train | 分类/记账/预算 CRUD 与归属；精确金额、半开区间边界；全空/零记录与分组缺桶；总额/分组/平均一致；时区夏令时月界、偏好缺省/非法值；月预算等号不超额、规则更改后的历史重查；跨人分类不可泄露记录 |
| 4. `sir-toolchain-application/src/test/resources/topics/booking/project.sir` | **中**。6E/11C；把 repeat 和行锁从混合题中抽成完整预约，需补非简化 DDL和 seed | 沿用 `shop.multiplication` 的 **development**；或未来重划统一预约家族时整组迁移，本次不拆 | 管理员建房/普通用户拒绝；预约半开区间，相邻可行、重叠拒绝；每周系列成功、第二周冲突整批回滚、次数超限不写；本人列表、他人取消拒绝；取消后时段可重用；同房并发不能重叠，不同房不互相误判 |
| 5. `sir-toolchain-application/src/test/resources/deployment/dual-token-v3/project.sir` | **中**。6E/22C；Optional 评分比较、scope、count/exists、关联分页、唯一收藏/并发计数都有真实烟测基础，但没有独立静态 DDL/Business IT | 沿用 `auth.dual-token.rotation`，train；不要以新“收藏”名拆去 dev/test，源码保留同一登录和笔记业务 | 登录→创建可见笔记→收藏一次/重复冲突；多并发收藏计数不丢；缺评分/null 与有值比较、!= 和 not(==) 的区别；按本人收藏过滤、scope 隐藏行的列表/详情/count/exists 一致；拒绝请求无副作用；DDL从当前 schema 固定，不把部署迁移过程本身塞进业务题 |
| 6. `sir-toolchain-application/src/test/resources/valid/course-admin-enrollment.sir` | **低**。3E/4C；change-loop DDL和当前编译测试可复用，写/关系各自 IT 可复用断言思路；少写新 schema，多补组合检查 | 沿用 `course.write-slice`，train；与 course.relation-slice 同属当前课程 train 组；多文件版本只是同题另一入口，不算另一独立 family | 新建→部分更新/清空描述→版本递增与陈旧请求拒写→同一条记录进入带 ACTIVE 关联过滤的分页；关联投影不被根筛选截掉；空/越界页总数与排序；并发版本只一方提交；输入约束与数据库快照 |
| 7. `sir-toolchain-application/src/test/resources/topics/market/project.sir` | **中**。6E/15C；Item 目录已有 test 基础，新增完整买卖/状态流程需 trade_order DDL及较多断言 | 沿用 `library.library-borrowing` 的 **test** 隔离组（现有该题已混入市场目录）；若将来独立 family，必须保留同一集合，不能直接转 train | 卖家建商品与默认状态；价格/condition 可选筛选；买家不能买自己商品；同一商品竞争只有有效交易；购买/取消的实际状态顺序与参与方权限（当前只有 PLACED/CANCELLED，没有确认接口）；他人订单不可见；失败回滚与商品/订单一致。未在源码表达的行为须先定需求，不能靠 IT 假设已实现 |

这里刻意不把原 library/shop、feature 与手写等价版本、六个课程变更版本分别塞进推荐凑数。它们的价值是组合/回归素材，而非新的独立业务分布。单独的 calendar-month（0E/2C）、atomic-counter（1E/2C）、unit-output（0E/1C）更适合机制练习，不建议为满足数量而各建一个业务判卷工程。

## 附录 A. 已用于改写题的 11 份 drafts 底稿

这些路径属于已有任务的输入，**不是未使用参考软件**；仍逐一登记，避免重新当作新题。DDL与目标家族的 oracle 继承 §0.1；原功能被删减过，不能以目标参考的能力数替代底稿本身。

| 完整路径 | 任务关系 | E/C | 保留特性 | 判断 |
|---|---|---|---|---|
| `experiments/slm/drafts/bearer-credential/base.sir` | 基:auth.bearer-credential | 2/3 | 基本载入/声明 | 已有题底稿，不推荐为新题；DDL/业务门沿用对应 family |
| `experiments/slm/drafts/course-selection/base.sir` | 基:course.course-selection | 5/5 → 8/9 | 分页、守卫更新、凭据、关联any、行锁、计算视图、删除 | 已有题底稿，不推荐为新题；DDL/业务门沿用对应 family |
| `experiments/slm/drafts/crud-access/base.sir` | 基:auth.crud-access | 3/0 → 3/5 | 凭据、scope、CRUD展开(含分页) | 已有题底稿，不推荐为新题；DDL/业务门沿用对应 family |
| `experiments/slm/drafts/guarded-transition/base.sir` | 基:auth.guarded-transition | 1/1 | 基本载入/声明 | 已有题底稿，不推荐为新题；DDL/业务门沿用对应 family |
| `experiments/slm/drafts/library-borrowing/base.sir` | 基:library.library-borrowing | 5/11 → 8/20 | 分页、守卫更新、凭据、关联any、行锁、金额、乘法、计算视图、Optional、删除、CRUD展开(含分页)、关联count视图 | 已有题底稿，不推荐为新题；DDL/业务门沿用对应 family |
| `experiments/slm/drafts/money/base.sir` | 基:finance.money | 8/15 → 11/28 | 分页、PATCH、守卫更新、聚合、month、凭据、关联any、scope、金额、Optional、CRUD展开(含分页) | 已有题底稿，不推荐为新题；DDL/业务门沿用对应 family |
| `experiments/slm/drafts/multiplication/base.sir` | 基:shop.multiplication | 13/20 → 16/24 | 分页、守卫更新、each、repeat、凭据、行锁、金额、乘法、计算视图、Optional、删除 | 已有题底稿，不推荐为新题；DDL/业务门沿用对应 family |
| `experiments/slm/drafts/query-slice/base.sir` | 基:course.query-slice | 1/0 | Optional | 已有题底稿，不推荐为新题；DDL/业务门沿用对应 family |
| `experiments/slm/drafts/relation-slice/base.sir` | 基:course.relation-slice | 3/0 | 乘法、计算视图 | 已有题底稿，不推荐为新题；DDL/业务门沿用对应 family |
| `experiments/slm/drafts/token-desk/base.sir` | 基:auth.token-desk | 2/4 | 守卫更新 | 已有题底稿，不推荐为新题；DDL/业务门沿用对应 family |
| `experiments/slm/drafts/write-slice/base.sir` | 基:course.write-slice | 1/2 | Optional | 已有题底稿，不推荐为新题；DDL/业务门沿用对应 family |

## 附录 B. datasets 中的全部 162 份冻结 `.sir`

这是实验底稿、已有参考副本或教师候选，不是额外 162 份经过认可的新参考软件。其静态/业务资格须查对应 checked/运行记录，不能仅因文件扩展名是 `.sir` 就当正确答案；依据见 teacher-error-census §口径、§1、§2、附录 A。下列路径完整列出，均不作为新题推荐；对应业务来自已有题目，不能借目录变化规避 family 隔离。

| 完整路径 | 性质/不作为新题 |
|---|---|
| `datasets/sir/pilots/bearer-credential-change-g1/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/bearer-credential-change-g1/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/bearer-credential-change-g1/samples/auth-bearer-credential-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/bearer-credential-change-g1/samples/auth-bearer-credential-change-v1-teacher-07b9ea18ef67/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/bearer-credential-scratch-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/bearer-credential-scratch-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/bearer-credential-scratch-g4/samples/auth-bearer-credential-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/bearer-credential-scratch-g4/samples/auth-bearer-credential-scratch-v1-teacher-0214246b5d17/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/bearer-credential-scratch-g5/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/bearer-credential-scratch-g5/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/bearer-credential-scratch-g5/samples/auth-bearer-credential-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/bearer-credential-scratch-g5/samples/auth-bearer-credential-scratch-v1-teacher-1115da26ef0d/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/bearer-credential-scratch-g5/samples/auth-bearer-credential-scratch-v1-teacher-c9ab97cd12d2/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/bearer-credential-scratch-g5/samples/auth-bearer-credential-scratch-v1-teacher-efbdfe58ef70/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/course-selection-change-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/course-selection-change-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/course-selection-change-g4/samples/course-course-selection-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/course-selection-change-g4/samples/course-course-selection-change-v1-teacher-6ec3685a54cd/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/course-selection-change-g5/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/course-selection-change-g5/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/course-selection-change-g5/samples/course-course-selection-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/course-selection-change-g5/samples/course-course-selection-change-v1-teacher-1cc5c4027a2d/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/course-selection-change-g5/samples/course-course-selection-change-v1-teacher-dbbbac2d45bc/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/course-selection-change-g5/samples/course-course-selection-change-v1-teacher-ef4ab1db87b9/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/course-selection-scratch-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/course-selection-scratch-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/course-selection-scratch-g4/samples/course-course-selection-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/course-selection-scratch-g4/samples/course-course-selection-scratch-v1-teacher-eb0ce1da4119/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/course-selection-scratch-g5/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/course-selection-scratch-g5/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/course-selection-scratch-g5/samples/course-course-selection-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/course-selection-scratch-g5/samples/course-course-selection-scratch-v1-teacher-524cb80d7495/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/course-selection-scratch-g5/samples/course-course-selection-scratch-v1-teacher-a9030f28af53/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/crud-access-change-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/crud-access-change-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/crud-access-change-g4/samples/auth-crud-access-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/crud-access-change-g4/samples/auth-crud-access-change-v1-teacher-d6b3dce267c8/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/crud-access-scratch-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/crud-access-scratch-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/crud-access-scratch-g4/samples/auth-crud-access-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/crud-access-scratch-g4/samples/auth-crud-access-scratch-v1-teacher-1df6bb81718e/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/crud-access-scratch-g5/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/crud-access-scratch-g5/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/crud-access-scratch-g5/samples/auth-crud-access-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/crud-access-scratch-g5/samples/auth-crud-access-scratch-v1-teacher-3062e8f8e07b/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/crud-access-scratch-g5/samples/auth-crud-access-scratch-v1-teacher-9b916df2eb27/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/crud-access-scratch-g5/samples/auth-crud-access-scratch-v1-teacher-b6e7a5435998/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/dual-token-v1/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/dual-token-v1/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/dual-token-v1/samples/auth-dual-token-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/dual-token-v1/samples/auth-dual-token-v1-teacher-2f2f819780a2/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/dual-token-v1/samples/auth-dual-token-v1-teacher-4fe6870d899c/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/dual-token-v1/samples/auth-dual-token-v1-teacher-6702e9b70653/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/dual-token-v1/samples/auth-dual-token-v1-teacher-ef9ddc30eb79/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/dual-token-v1/samples/auth-dual-token-v1-teacher-f4b4a6071888/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/dual-token-v2/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/dual-token-v2/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/dual-token-v2/samples/auth-dual-token-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/dual-token-v2/samples/auth-dual-token-v1-teacher-25e7a4ca946e/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/dual-token-v2/samples/auth-dual-token-v1-teacher-75d6da4889cd/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/dual-token-v2/samples/auth-dual-token-v1-teacher-e473808f70e8/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/guarded-transition-change-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/guarded-transition-change-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/guarded-transition-change-g4/samples/auth-guarded-transition-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/guarded-transition-change-g4/samples/auth-guarded-transition-change-v1-teacher-bd642066c9d7/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/guarded-transition-change-g5/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/guarded-transition-change-g5/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/guarded-transition-change-g5/samples/auth-guarded-transition-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/guarded-transition-change-g5/samples/auth-guarded-transition-change-v1-teacher-857fcfe57b22/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/guarded-transition-change-g5/samples/auth-guarded-transition-change-v1-teacher-c10a33a33557/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/guarded-transition-scratch-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/guarded-transition-scratch-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/guarded-transition-scratch-g4/samples/auth-guarded-transition-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/guarded-transition-scratch-g4/samples/auth-guarded-transition-scratch-v1-teacher-1dff8d4a8a1c/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/query-slice-change-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/query-slice-change-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/query-slice-change-g4/samples/course-query-slice-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/query-slice-change-g4/samples/course-query-slice-change-v1-teacher-905d204a57e4/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/query-slice-scratch-g1/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/query-slice-scratch-g1/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/query-slice-scratch-g1/samples/course-query-slice-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/query-slice-scratch-g1/samples/course-query-slice-scratch-v1-teacher-82872fe06240/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/query-slice-scratch-g2/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/query-slice-scratch-g2/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/query-slice-scratch-g2/samples/course-query-slice-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/query-slice-scratch-g2/samples/course-query-slice-scratch-v1-teacher-182e9f50beb7/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/query-slice-scratch-g3/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/query-slice-scratch-g3/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/query-slice-scratch-g3/samples/course-query-slice-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/query-slice-scratch-g3/samples/course-query-slice-scratch-v1-teacher-cadd5a148bb8/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/relation-slice-change-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/relation-slice-change-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/relation-slice-change-g4/samples/course-relation-slice-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/relation-slice-change-g4/samples/course-relation-slice-change-v1-teacher-d3c1df26ed80/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/relation-slice-scratch-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/relation-slice-scratch-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/relation-slice-scratch-g4/samples/course-relation-slice-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/relation-slice-scratch-g4/samples/course-relation-slice-scratch-v1-teacher-c6167c316f44/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/relation-slice-scratch-g5/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/relation-slice-scratch-g5/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/relation-slice-scratch-g5/samples/course-relation-slice-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/relation-slice-scratch-g5/samples/course-relation-slice-scratch-v1-teacher-561bdfe9ead2/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/token-desk-change-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/token-desk-change-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/token-desk-change-g4/samples/auth-token-desk-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/token-desk-change-g4/samples/auth-token-desk-change-v1-teacher-b38e01722cc0/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/token-desk-scratch-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/token-desk-scratch-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/token-desk-scratch-g4/samples/auth-token-desk-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/token-desk-scratch-g4/samples/auth-token-desk-scratch-v1-teacher-bd4698114bcb/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-change-g1/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/write-slice-change-g1/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/write-slice-change-g1/samples/course-write-slice-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-change-g1/samples/course-write-slice-change-v1-teacher-ab5caa38a15d/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-change-g2/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/write-slice-change-g2/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/write-slice-change-g2/samples/course-write-slice-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-change-g2/samples/course-write-slice-change-v1-teacher-e52b33f4295b/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-change-g3/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/write-slice-change-g3/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/write-slice-change-g3/samples/course-write-slice-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-change-g3/samples/course-write-slice-change-v1-teacher-75ff6c7c1924/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-change-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/write-slice-change-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/write-slice-change-g4/samples/course-write-slice-change-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-change-g4/samples/course-write-slice-change-v1-teacher-51ab2996a20e/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-scratch-g4/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/write-slice-scratch-g4/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/write-slice-scratch-g4/samples/course-write-slice-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-scratch-g4/samples/course-write-slice-scratch-v1-teacher-036fa8e447e7/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-scratch-g5/base.sir` | 冻结底稿 |
| `datasets/sir/pilots/write-slice-scratch-g5/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/pilots/write-slice-scratch-g5/samples/course-write-slice-scratch-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-scratch-g5/samples/course-write-slice-scratch-v1-teacher-3f240deb1301/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/pilots/write-slice-scratch-g5/samples/course-write-slice-scratch-v1-teacher-be9817697402/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-gate-v1/base.sir` | 冻结底稿 |
| `datasets/sir/runs/dual-token-gate-v1/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/runs/dual-token-gate-v1/samples/auth-dual-token-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-gate-v1/samples/auth-dual-token-v1-teacher-2f2f819780a2/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-gate-v1/samples/auth-dual-token-v1-teacher-4fe6870d899c/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-gate-v1/samples/auth-dual-token-v1-teacher-6702e9b70653/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-gate-v1/samples/auth-dual-token-v1-teacher-bd870a834f77/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-gate-v1/samples/auth-dual-token-v1-teacher-e2c157051e10/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-gate-v1/samples/auth-dual-token-v1-teacher-ef9ddc30eb79/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-gate-v1/samples/auth-dual-token-v1-teacher-f4b4a6071888/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-lab-v1/base.sir` | 冻结底稿 |
| `datasets/sir/runs/dual-token-lab-v1/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/runs/dual-token-lab-v1/samples/auth-dual-token-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-lab-v1/samples/auth-dual-token-v1-teacher-2f2f819780a2/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-lab-v1/samples/auth-dual-token-v1-teacher-4fe6870d899c/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-lab-v1/samples/auth-dual-token-v1-teacher-6702e9b70653/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-lab-v1/samples/auth-dual-token-v1-teacher-ef9ddc30eb79/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-lab-v1/samples/auth-dual-token-v1-teacher-f4b4a6071888/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-recheck-v2/base.sir` | 冻结底稿 |
| `datasets/sir/runs/dual-token-recheck-v2/reference.sir` | 历史验证输入/快照；非新参考 |
| `datasets/sir/runs/dual-token-recheck-v2/samples/auth-dual-token-v1-reference/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-recheck-v2/samples/auth-dual-token-v1-teacher-2f2f819780a2/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-recheck-v2/samples/auth-dual-token-v1-teacher-4fe6870d899c/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-recheck-v2/samples/auth-dual-token-v1-teacher-6702e9b70653/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-recheck-v2/samples/auth-dual-token-v1-teacher-bd870a834f77/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-recheck-v2/samples/auth-dual-token-v1-teacher-ef9ddc30eb79/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |
| `datasets/sir/runs/dual-token-recheck-v2/samples/auth-dual-token-v1-teacher-f4b4a6071888/candidate.sir` | 冻结参考/教师候选（须查 checked.origin） |

## 附录 C. 盘点完整性与本轮边界

| 范围 | `.sir` 数 | 登记位置 |
|---|---|---|
| datasets | 162 | 附录 B |
| experiments | 11 | 附录 A |
| kcg-cli | 7 | §2（按字节相同组列全路径） |
| sir-change | 9 | §2（按字节相同组列全路径） |
| sir-generator-spring-boot | 34 | §2（按字节相同组列全路径） |
| sir-lowering-spring-boot | 29 | §2（按字节相同组列全路径） |
| sir-parser | 10 | §2（按字节相同组列全路径） |
| sir-semantic | 40 | §2（按字节相同组列全路径） |
| sir-toolchain-application | 63 | §1 |
| 总计 | 365 | 365 个唯一 Git 跟踪路径，无遗漏 |

所有 E/C、特性和复用结论来自当前文件/模板/测试代码；“低/中/高”和推荐方向是静态评估，没有执行或承诺新的业务通过证据。只新增本报告，不创建测试、DDL、题目或训练数据，不调用模型。纯文档只做差异与路径/清单检查；重验证仍受 `AGENTS.md` 的 CI 资源规则约束。
