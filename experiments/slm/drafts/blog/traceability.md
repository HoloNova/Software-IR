# 博客题判卷追溯

本目录落实已批准的 [spec.md](spec.md)，保留 `content.blog` / `train` 及七能力范围。`requirement-change.md`、`requirement-scratch.md` 和 `contract.json` 分别逐字／逐对象取自规格 §5、§4；DDL/seed 的 SQL 与规格 §2、§3 相同，不提供实现提示或额外评分项。

**裁剪参考已由同 SHA 的 CI 验收，正式两题已冻结。**`66c276915d825f4d1914da721b1ddf4e0ceda2de` 的 [verify #200](https://github.com/HoloNova/Software-IR/actions/runs/40839225636) 四门通过、业务 425/0；该次 CI 的 base 契约检查及原编译器再次生成均确认四个新增 Controller 缺失。两份正式 task 为 `experiments/slm/tasks/blog-change-v1.json` / `blog-scratch-v1.json`，各有一个仅参考的 g7 批次，运行门不继承参考资格。模型调用仍为 0，采样预算待确认；原始证据见 `datasets/sir/research/2026-10-29/blog-reference-ci200/`。

下文 `C/` 为 `sir-toolchain-application/src/test/java/io/kcg/sir/application/conformance/`；S01–S14、C01–C09 是两份题面按非空行顺序编号。每个表项包含方法内循环的全部实参；公共 PP/PV/PAGE 比较展开为精确 key、数值类型、逐字段与独立 JDBC 值相等，不把只有字段存在算通过。完整逐请求参数也保留在规格 §6。

## 1. 题目与参考

- 参考：`sir-toolchain-application/src/test/resources/valid/blog.sir`，显式七能力，不使用完整 `topics/blog/project.sir` 充当裁剪答案。只提取原 Crud 的创建语义；不保留另外四个 Crud 能力、登录、注册或评论。
- base：[base.sir](base.sir)，从该参考仅删 Publish、AttachTag、PostsByTag、LikePost，其他声明不动。`BlogReferenceContractTest.baseCompilesAndRemovesOnlyTheFourNewCapabilities` 验证两边完整静态生成、其余声明相同，且 base 不生成这四个 Controller。**这证明静态缺少新增接口，不冒称已经启动 base 得到 HTTP 失败证据。**
- 夹具：`conformance/mysql/blog-ddl.sql`、`blog-seed.sql`，七张表、两个真实联合 UNIQUE，`like` 仍是带反引号的原物理名字；没有为了测试改成别名表。
- 合同中的 categoryId 是 HTTP 输入名；PostView 的 author/category 是数值主键，PublicPost 中是两层嵌套对象。只对创建标题测试规格列出的缺失、null、空白和超长，不因新工具链已统一输入必填而追加 category/content 缺失评分。

## 2. 身份与创建

| 检查 | 代码位置／完整范围 | scratch 原句 | change 承接 |
| --- | --- | --- | --- |
| B01 | `BlogBusinessConformanceIT.execute` 将启动 readiness GET 前后七表比较；`BlogPublicationChecks.identityAndCreation` 再验证匿名列表的全部 ID/total/order/PP | S01、S04、S08、S09、S13 | C01、C02 |
| B02 | `BlogPublicationChecks.authenticationMatrix`：创建、发布、挂标签、点赞四写入口 × 无头／格式错误／未知 UUID／过期，共 16 请求；每次空体 401 与七表 UNCHANGED | S01、S12 | C02、C08 |
| B03、B04 | `BlogPublicationChecks.create`：alice、bob 各正常创建；身份来自 Bearer；仅新增响应 ID 对应 post，完整输入/defaults/创建时间及 PV 与 JDBC 一致 | S01、S02、S09、S12、S13 | C01、C02 |
| B05 | `BlogPublicationChecks.invalidTitles`：省略、null、空串、三空格、101 ASCII 字符；400 INVALID_REQUEST，title/NotBlank 或 Size；不固定本地化消息或字段顺序 | S02、S12 | C01、C08 |
| B06 | `BlogPublicationChecks.create`：100 字符标题、1001 字符正文原样创建；不把 Name 限成 Code，不把 Text 限成 Remark | S02、S09、S12 | C01 |

`create` 只从 HTTP 数值 id 追踪新行，不假定自增连续。创建响应与 DATETIME(6) 允许最多 1 ms 精度差；创建时间须在请求开始／结束 UTC 区间内（同一精度容差）。后续读必须匹配实际存储值，不继续使用创建容差。

## 3. 发布与阅读

| 检查 | `BlogPublicationChecks.publicationAndReading`／`publish` | scratch 原句 | change 承接 |
| --- | --- | --- | --- |
| P00 | 本小节先以正常创建取得新 pA，复用 B03 检查，不直接插文章替代创建 | S02 | C01 |
| P01 | pA 草稿详情 404、flow 搜索空页，两次七表不变 | S04、S05、S12 | C01、C08 |
| P02、P03 | 非作者 403 Forbidden、不存在 404 PostNotFound；逐次七表不变 | S03、S12 | C03、C08 |
| P04 | URL 的 pA 与 body.id=105 冲突；200 PV(pA)，仅 pA.state 改为 PUBLISHED，其余行／列不变 | S03、S09、S12、S13 | C03、C08 |
| P05 | 详情与 flow 搜索读到同一新文章的完整 PP/嵌套值；读不写库 | S04、S05、S09、S12 | C01、C08 |
| P06 | 重放发布 409 NotPublishable，七表不变（包括 created_at） | S03、S12 | C03、C08 |
| P07 | 未知文章和另一草稿均 404 PostNotFound，不删草稿假装不可见 | S05、S12 | C01、C08 |

## 4. 搜索、排序与分页

| 检查 | `BlogPublicationChecks.searchAndPagination`／`badPagination` | scratch 原句 | change 承接 |
| --- | --- | --- | --- |
| Q01 | alpha 前两页和越界第三页；完整顺序为 [107,103]／[102,104]／[]，total 均 4；覆盖仅正文命中、同刻 id 降序、草稿排除 | S04、S08、S09、S12 | C01 |
| Q02 | URL 编码的百分号／下划线／反斜线，分别只匹配 102／103／107 | S04、S08、S09、S12 | C01 |
| Q03 | 空关键词全部已发布、零命中空页，不能把草稿混入或返回 404 | S04、S08、S09、S12 | C01 |
| Q04 | 同时缺省／分别只缺 page 或 size；默认 1/20 独立生效 | S08、S09、S12 | C01、C06 |
| Q05 | Feed、PostsByTag 两路 × page=0/-1/10001、size=0/-1/101，共 12 次 400 BadPage，逐次不写库 | S08、S12 | C01、C06、C08 |
| Q06 | 有效上界 page=10000、size=100；合法空页仍 total=4 | S08、S09、S12 | C01、C06 |

每个 PAGE 都检查外层恰好 total/page/size/records、JSON 整数而非字符串、完整记录数量／顺序，以及每项 PP 全字段和嵌套值。每次 GET 比较七表全部列、全部行。

## 5. 标签关系

| 检查 | `BlogTagChecks.run`／`defaultsAndBounds`／`attach` | scratch 原句 | change 承接 |
| --- | --- | --- | --- |
| T01、T02 | 指定标签两页、另一标签、空标签；过滤草稿与其他标签，根行不重复 | S07、S08、S09、S12 | C05、C06、C08 |
| T03 | 未知标签 404 TagNotFound，与存在但空的标签不同 | S07、S12 | C05、C08 |
| T04、T05 | 给草稿 101 挂 21，空体 201，仅新增该 post_tag；草稿依旧不出公开页 | S06、S07、S09、S12 | C04、C05、C08 |
| T06、T07、T08 | 缺文章／缺标签 404、非作者 403、重复绑定 409，精确错误信封且逐次七表不变 | S06、S12 | C04、C08 |
| T09 | 发布已挂标签的 101 后读到它；发布只改 state，不能用标签替换分类 | S03、S07、S08、S09、S12 | C03、C05、C06、C08 |
| T10 | 发布后的指定标签页分别缺省、越界页、合法上界；独立 total/order/PP 与不写库 | S07、S08、S09、S12 | C05、C06、C08 |
| T11 | 已发布 106 也可挂 21；只加关系、不改文章或其他行，随后公开页完整更新 | S06、S07、S09、S12 | C04、C05、C08 |

## 6. 点赞、原子回滚与提交后读取

| 检查 | `BlogLikeChecks.run`／`like` | scratch 原句 | change 承接 |
| --- | --- | --- | --- |
| L01 | bob 点赞 102：201 PP，仅新增该用户 Like、目标 likes +1；计数等于实际关系行数 | S09、S10、S12 | C07、C08 |
| L02 | 同用户重复点赞 409 AlreadyLiked；七表全相等，不能留下已增的计数 | S10、S12 | C07、C08 |
| L03 | carol 与作者 alice 依次成功；各只加自己的关系和一次计数 | S01、S10、S12 | C02、C07、C08 |
| L04 | 草稿／未知文章均 404 PostNotFound，逐次七表不变 | S10、S12 | C07、C08 |
| L05 | 详情、百分号字面搜索、标签页三路读到实际提交计数及完整 PP，读不写库 | S04、S05、S07、S09、S10、S12 | C01、C05、C07、C08 |

## 7. 四轮真实竞争

`BlogConcurrentRequests` 仅负责博客并发传输，不修改公共 `HttpAssertionClient`。每轮先独立重置种子，六个新客户端在 ready/start 两个 latch 同步；请求 30 秒、整轮结果收集 35 秒。控制连接先持有目标 post 行锁；`BlogLockWaits` 从本 schema 的真实 data_lock_waits/data_locks/threads 构造等待图，允许间接唯一键等待，必须同时有六个不同运行时连接和事务连到该控制连接，不能用一条等待或六条重复边充数。最多观察五秒，有客户端提前结束或不能形成竞争都记失败，再释放控制事务、收齐响应。

权限或 instrumentation 不足明确 NOT_RUN。连接中断、超时、死锁、500 都不是 409／成功；客户端异常会中止 IT，在 teardown 停应用后才清理 schema，不继续重置有潜在在途请求的数据。真实 ready/start/end 时间、持锁目标、连接／事务及等待图写入同一业务报告；候选模式沿用 `SlmCandidateSupport` 将其列入 `slm-runtime.json` 的原始附件哈希。

| 检查 | `BlogConcurrencyChecks` | HTTP 与独立 JDBC | scratch／change |
| --- | --- | --- | --- |
| X01 | `publish`，草稿 108，六个 alice | 1×200 PV、5×409 NotPublishable；只改 state；随后匿名 PP 与全库不变 | S03、S11、S12／C03、C08 |
| X02 | `attach`，草稿 111／标签 22，六个 alice | 1×空体201、5×409 TagAttached；只加一条关系，仍草稿；随后标签页空且不写库 | S06、S11、S12／C04、C08 |
| X03 | `sameLiker`，109，六个 bob | 1×201 PP likes=1、5×409 AlreadyLiked；仅一行 Like 和一次计数；随后匿名 PP likes=1 | S10、S11、S12／C07、C08 |
| X04 | `differentLikers`，110，carol…heidi | 6×201 PP，每个 likes 为数值 1…6；六个不同 user 关系、最终 likes=6，其他七表行／列不动；随后匿名 PP likes=6 | S10、S11、S12／C07、C08 |

每轮最终对所有 post 独立检查 likes=COUNT(like)，不从 HTTP 成功条数推导。X04 不要求响应完成顺序或响应计数互异；整轮只准赢家应有的数据库差异，不能在其他线程成功写入时错误要求失败者的瞬时全库不变。

## 8. 公共比较、静态门与非业务前置

- `BlogAssertions`：全部 PV/PP/PAGE、声明错误、401 空体、精确 JSON（重复 key／尾随文档拒绝）、数值类型和 Moment。S09、S12、S13／C08 由上述每个调用展开，不另造接口。
- `BlogFixture`：独立只读 JDBC 连接观察七表、完整不可变快照、仅夹具控制连接重放种子、UTC 读取和持锁。自增分配器空号不算业务行变化；不读取 information_schema 的 AUTO_INCREMENT。
- S14／C09 的完整 SIR 输出由原静态编译器检查，不能以 HTTP 断言冒充；不另写 SIR 解析／语义。
- `BlogOracleTest` 的 JSON／快照／锁图反例，以及 `BlogReferenceContractTest` 的裁剪、路由、状态、external 环境、DDL 声明，都是测试基础设施的定向回归，不算新的用户需求。
- 启用开关、环境／种子自检、schema 所有权、编译／构建／启动、进程退出、schema 删除和报告汇总是前置与收尾；其失败照样阻断有效证据，但不要求候选实现判卷器。
- `verify.yml` 运行参考并单独要求报告正式第二行 `verdict=PASSED`，不会将 NOT_RUN 当通过；`slm-business-gate.yml` 已加入 BlogBusinessConformanceIT 与 `kcg.blog-conformance.enabled` 映射，仍沿用原候选四门附件校验。

## 9. 正式冻结的边界

已按顺序取得参考同 SHA 的 verify #200 四门证据，确认 base 完整生成 47 个文件但缺四个新增 Controller，再填写两份 task 的真实 `reference_evidence` 并以原 prepare 冻结两批。base 的运行 HTTP 仍 NOT_RUN，未把静态负例冒称业务失败；模型采样尚待预算确认。不得给任何教师候选继承参考的通过证据。

`oracle.files` 须覆盖执行闭包：以既有 token-desk 题的公共 BusinessSliceHarness/SlmCandidateSupport、安全目录／进程／JDBC／脱敏依赖闭包为基础，移除 TokenDeskBusinessConformanceIT，加入本题的 BlogBusinessConformanceIT、BlogAssertions、BlogFixture、BlogPublicationChecks、BlogTagChecks、BlogLikeChecks、BlogConcurrentRequests、BlogConcurrencyChecks、BlogLockWaits，以及本题 DDL、seed、裁剪参考；不把仅做回归的两个 `*Test` 当业务运行入口，也不漏掉新的辅助类。两题实际冻结 38 项路径（35 个 Java、DDL、seed、参考），逐项与受测 Git blob 核对；另用 CI 核心 JAR 和外部依赖单独编译这 35 个 Java，不把既有 test-classes 放进 classpath，也不隐式补编其他源，验证未漏依赖。记录见归档中的 task-freeze.json、checkout-verification.json 与 oracle-closure.json。
