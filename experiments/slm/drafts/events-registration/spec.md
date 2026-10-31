# 活动报名缴费题出题规格（草稿，待负责人审核）

## 0. 定位、基线与交付边界

基线为 `main` 的 `95def2c`。本题 family 为 `events.registration`，split 为 `train`，计划 ID 为 `events-registration-change-v1`、`events-registration-scratch-v1`；同源题面、改说法和后续变体留在同一集合。

**状态：规格草稿。**本轮只写了规格、`reference.sir`、`base.sir`、两份需求原文、`contract.json`、`traceability.md`；没有写 Java/IT、DDL/种子文件，没有改工具链，没有冻结 task，没有调用模型，没有启动数据库或应用，没有跑 Maven 测试。§2 的 DDL 与 §3 的种子是写在本文里的设计。

**选择“组织者建活动 → 公开列表 → 会员报名（名额、一人一次、记录当时报名费）→ 本人报名列表 → 组织者看已收报名费与报名人数”的 6 能力闭环。**注册登录、活动修改/取消、取消报名/退款、支付网关、报名名单都不考；用户和有效访问令牌由夹具预置。认证沿用博客：数据库里真实的 Bearer 凭据。

“缴费”的含义：不接支付网关。一次成功的报名就视为按当时的活动报名费收取，报名记录保存该金额（`fee` 快照）；“已收报名费”是该活动所有报名记录 `fee` 之和。**本规格有意不做“待缴费 → 已缴费”状态流转**：`shop` 的 `Pay`（`CREATED→PAID` 守卫更新）与之同形，属于 dev 题的换名（见 §0.3）。

实现阶段的落地路径（待确认）：`valid/events-registration.sir`、`conformance/mysql/events-registration-ddl.sql`、`-seed.sql`、`EventsRegistrationBusinessConformanceIT.java`（复用 `BusinessSliceHarness` 与博客 §6.7 的并发写法）。

### 0.1 参考了什么

缩写同 fitness 规格：`A/`=`sir-toolchain-application/src/test/java/io/kcg/sir/application/`，`G/`=`sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/`，`L/`=`sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/`，`R`=`docs/SIR_LANGUAGE_REFERENCE.md`。

| 材料 | 本规格采用的做法 |
|---|---|
| `experiments/slm/drafts/blog/` 全部文件 | 章节结构、记号、`UNCHANGED`、并发屏障（§6.7）、逐句追溯 |
| `new-problem-plan.md` §1–§3、§6；`new-problem-survey.md` §0.1、§4 | 选题理由、占用与同源边界 |
| `prompts/sir-0.3-general-v9.md` | 教师规则；需求原文不超出手册和语言参考 |
| `topics/course/project.sir` 的 `Enroll`（只读） | 确认与本题 `Register` 核心同构，见 §0.3、发现 E1 |
| `valid/shop-conformance.sir`、`topics/market`、`topics/booking`、`g9-topic-conformance.sir`、`topics/ledger`（只读） | 写 §0.3 边界核对 |
| `A/conformance/DecimalSumChecks.java`:42–55 | 标量响应按 `BigDecimal` 数值比较 |

### 0.2 本地检查结果（本轮实际运行）

环境同 fitness 规格 §0.2：云端会话（`nproc`=4、15 GB），无 `~/.m2`，改用在线 `mvn -B -q -pl kcg-cli -am compile`（只编译，退出码 0）；检查器命令来自 `experiments.slm.dataset_io.checker_command()`，运行时去掉 `JAVA_TOOL_OPTIONS`。

| 文件 | ok | stage | 诊断码 | 生成文件数 | digest（前 16 位） |
|---|---|---|---|---:|---|
| `reference.sir`（6 能力） | true | GENERATION | 无 | 42 | `c9b0bad6ed2bb99c` |
| `base.sir`（3 能力） | true | GENERATION | 无 | 35 | `5c8fc5ebed3619bb` |

base 比参考少的 7 个文件：被删三个能力（Register、EventRevenue、EventRegistrationCount）的 Controller 与 Service 各一个，加 `ExactAggregate.java`。

负例（改参考副本，未保留）：

| 改动 | 结果 |
|---|---|
| 去掉 `persist made else AlreadyRegistered;` 的 `else` | SEMANTIC，`SIR-VALID-004`：先 `exists` 再 create，新行 persist 必须写 else |
| 把 `persist made …` 排在 `persist event;` 之前 | SEMANTIC，`SIR-VALID-015`：先 persist 被计数的行 |
| 把守卫 `update event when … else EventFull { … }` 换成无守卫的 `update event { registered: event.registered + 1; }` | **GENERATION 通过、无诊断** |
| 去掉 `Fee` 的 `precision(10, 2), min(0)`，改成裸 `Money` | GENERATION 通过 |

后两条说明：**“名额不超卖”和“fee 精度/下限”编译器不强制，只有业务 IT 能发现**，这正是 §6 要有并发和边界检查的原因。

另用 `kcg generate` 在会话临时目录读了生成的 `RegisterService`：`UPDATE event … WHERE registered < capacity` 先执行（受影响行数不是 1 则抛 `EventFull`），随后 `insert` 报名记录，`DuplicateKeyException` 转成 `AlreadyRegistered`，整个方法在 `@Transactional` 内。用 `kcg package` 导出的 `V1__initialize.sql` 与 §2 逐表逐列对照一致（`event.fee`/`registration.fee` 为 `DECIMAL(10,2)`，`registered INT NOT NULL DEFAULT 0`，唯一键 `(event_id, attendee_id)`），仅排序规则与索引/外键名不同。

### 0.3 与 dev/test 的边界核对

结论：**业务、实体和数据形状与 test 的 finance.money/library/market、dev 的 shop/booking 都不同；机制上与它们及 train 的 course.course-selection 有重叠，下面逐条说明，其中 course 的重叠需要负责人知悉（发现 E1）。**

| 对照 | 其实体与能力 | 本题是否相同 | 为什么不同 |
|---|---|---|---|
| test：finance.money / ledger | User、Category、Entry、Budget；TotalBetween、TotalsByCategory、AverageBetween、TotalsForMonth、BudgetExceededForMonth… | 机制相同（`Money`、`sum`） | 本题没有分类、预算、月份、平均、时区；合计对象是“某活动的报名记录”，按组织者授权，金额来自报名时快照的 fee；业务是报名，不是记账 |
| test：library | Book、Copy、Loan、Reservation；Borrow、GiveBack、ReserveBook… | 机制相似（用守卫的计数占名额） | 没有副本、借阅上限、归还、预约队列、罚金；本题计数只增不减，没有释放名额的路径 |
| test：market | Item、TradeOrder；BuyItem（守卫状态 ON_SALE→SOLD + 建订单并快照 price） | 机制相似（建单时快照价格） | 单件商品一次性售出对比“N 个名额 + 每人每场只能一次”；没有买卖双方、没有订单状态；本题的快照用于汇总 |
| dev：shop | PurchaseOrder、OrderLine、CartItem、Coupon；AddToCart、Checkout、Pay、BuyNow… | 机制相似（守卫计数、`Pay` 的守卫状态流转） | 没有购物车、订单行、金额乘法、优惠券；**有意不做 `Pay` 状态机**（同形） |
| dev：booking | Room、Booking；Book、BookWeekly、CancelBooking | 不同 | 没有房间、时段重叠、`for update` 父行锁、按周重复；本题时间只用于“是否已开始” |
| **train：course.course-selection** | `topics/course/project.sir` 的 `Enroll`：`exists` 查重 → 守卫 `update selected when enrolled < capacity` → `create Enrollment` → 先 `persist selected` 再 `persist made else AlreadyEnrolled` | **核心相同** | 本题 `Register` 是它去掉先修课、时间冲突、`for update`、发布状态后的简化版。新增的是：`Money` 与精度、`sum`、`count`、报名时快照 fee、开始时间窗、组织者授权、可选的“自己的活动”。**对 `update … when`、`persist … else` 这两个结构，本题几乎不增加训练多样性**（它们在现有数据里已各有 19 条），真正补缺的是 `Money`、`sum`、`count` |

## 1. 范围与裁剪清单

保持 `software EventSignup`、namespace `example.events`、Java 21 / Spring Boot / MyBatis-Plus / MySQL / Maven / REST 外壳。

### 1.1 六个能力

| 能力 | 做什么 | 保留理由（对应的结构） |
|---|---|---|
| CreateEvent | 任一登录用户创建活动，成为组织者 | `create`；带明确精度的 `Money` 输入校验；计数器初值 |
| ListEvents | 公开，分页列出尚未开始的活动 | `Page`；查询里的 `now()`；视图计算字段 `remaining`；匿名就绪探针 |
| Register | 报名：存在性/开始时间/重复/名额四步判断 | `exists`、`update … when` 守卫计数、`unique (event, attendee)`、`persist … else`、快照 fee |
| MyRegistrations | 本人报名列表，分页 | 归属筛选 `item.attendee == actor`、`Page` |
| EventRevenue | 组织者看某活动已收报名费 | `sum`（`Money`）、组织者授权 |
| EventRegistrationCount | 组织者看某活动报名人数 | `count … where`、组织者授权 |

不考：注册登录、改/删/取消活动、取消报名与退款、支付网关与支付状态、报名名单、候补。

### 1.2 对 new-problem-plan §1 结构的覆盖

| 结构 | 本题用到的能力 |
|---|---|
| `Money`（明确精度） | `Fee = Money where precision(10, 2), min(0)`：Event.fee、Registration.fee、CreateEvent 输入、EventView/RegistrationView；输出 `Money`（EventRevenue） |
| `sum` | EventRevenue |
| `count … where` | EventRegistrationCount |
| `unique (a, b)` | Registration：`unique (event, attendee)` |
| `update … when` 守卫的名额计数 | Register：`update event when event.registered < event.capacity else EventFull { registered: event.registered + 1; }` |
| `exists` 查重 + `persist … else` | Register：`exists Registration …` → `validate not already else AlreadyRegistered` → `persist made else AlreadyRegistered` |
| `Page` | ListEvents、MyRegistrations |
| 不覆盖（属于其他 family） | `avg`、`month`、`is none` 筛选、`any`、`containsLiteral`、`each`、`repeat`、`scope`、`versioned`、`patch of`、`for update` |

### 1.3 全部声明

| 种类 | 名称与完整成员 |
|---|---|
| type | `Fee = Money where precision(10, 2), min(0)` |
| entity | User：`id: Id generated auto`；`name: Name unique` |
| entity | AccessToken：同博客，`credential subject owner expires expiresAt` |
| entity | Event：`id`；`organizer: Ref<User>`；`title: Name`；`startsAt: Moment`；`capacity: Quantity`；`registered: Count default 0`；`fee: Fee` |
| entity | Registration：`id`；`event: Ref<Event>`；`attendee: Ref<User>`；`fee: Fee`；`registeredAt: Moment default now()`；`unique (event, attendee)` |
| view | EventView from Event：id、organizer（Ref<User>，数值）、title、startsAt、capacity、registered、`remaining: Count = item.capacity - item.registered`、fee |
| view | RegistrationView from Registration：id、event（Ref<Event>，数值）、attendee（Ref<User>，数值）、fee、registeredAt |
| input | NewEvent：`title: Name`、`startsAt: Moment`、`capacity: Quantity`、`fee: Fee`；EventRef：`id: Id`；PageQuery：`page: PageNo`、`size: PageSize` |
| error | BadPage 400；Forbidden 403；EventNotFound 404；AlreadyRegistered、EventFull、RegistrationClosed 409 |
| capability | 仅 §1.1 六项；CreateEvent、Register 是 authenticated + atomic command；ListEvents 公开 readonly；其余 authenticated + readonly |

`reference.sir` 即以上全部声明的完整源码。

### 1.4 改写 base 的机械差异

以 `reference.sir` 为起点，仅删三个完整 capability：Register、EventRevenue、EventRegistrationCount；其余声明（含暂未引用的 `EventRef` 外的全部实体、type、view、error）不删。base 仍能建活动、列活动、看本人报名（种子里有报名行），不是空壳；它的 `MyRegistrations`、`ListEvents` 没有计数器写入路径，因此补回 `Register` 需要教师自己组合守卫计数、查重和 `persist … else`。静态检查：GENERATION 通过，35 个文件，缺三个 Controller。这是静态负例，没有运行 base 的 HTTP。

## 2. 完整 MySQL DDL（夹具）

格式同博客；InnoDB；`utf8mb4_bin`；`DATETIME(6)`；依据 R:86–109、178–192 与 `L/api/SpringBootDeploymentPlanner.java`:54–76、219–230，已与 `kcg package` 导出的 DDL 对照（§0.2）。**不加 `CHECK (registered <= capacity)`**，避免数据库掩盖超卖，超卖由 IT 的 JDBC 不变量发现。`event`、`user`、`registration`、`fee` 都不在 `L/profile/MySqlIdentifiers.java` 的保留字表里。

```sql
-- TEST_FIXTURE_DDL: isolated owned schema; not product initialization/migration.
CREATE TABLE `user` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY events_user_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE access_token (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT events_access_owner FOREIGN KEY (owner_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    organizer_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    capacity INT NOT NULL,
    registered INT NOT NULL DEFAULT 0,
    fee DECIMAL(10,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT events_event_organizer FOREIGN KEY (organizer_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE registration (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    attendee_id BIGINT NOT NULL,
    fee DECIMAL(10,2) NOT NULL,
    registered_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY events_registration_once (event_id, attendee_id),
    CONSTRAINT events_registration_event FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT events_registration_attendee FOREIGN KEY (attendee_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

联合唯一键必须真实存在：它是“同一人并发重复报名只有一次成功”的最后防线（R:261）。

## 3. 种子数据与请求记号

时间均按 UTC。有效凭据截至 2099 年，过期凭据固定在 2000 年；“未开始”的活动开始时间在 2099 年，“已开始”的在 2000 年，不用等待真实时间。

```sql
INSERT INTO `user` (id, name) VALUES
    (1,'alice'),(2,'bob'),(3,'carol'),(4,'dave'),(5,'erin'),
    (6,'frank'),(7,'grace'),(8,'heidi'),(9,'ivan'),(10,'judy');

INSERT INTO access_token (id, owner_id, expires_at) VALUES
    ('30000000-0000-4000-8000-000000000001', 1, '2099-01-01 00:00:00.000000'),
    ('30000000-0000-4000-8000-000000000002', 2, '2099-01-01 00:00:00.000000'),
    ('30000000-0000-4000-8000-000000000003', 3, '2099-01-01 00:00:00.000000'),
    ('30000000-0000-4000-8000-000000000004', 4, '2099-01-01 00:00:00.000000'),
    ('30000000-0000-4000-8000-000000000005', 5, '2099-01-01 00:00:00.000000'),
    ('30000000-0000-4000-8000-000000000006', 6, '2099-01-01 00:00:00.000000'),
    ('30000000-0000-4000-8000-000000000007', 7, '2099-01-01 00:00:00.000000'),
    ('30000000-0000-4000-8000-000000000008', 8, '2099-01-01 00:00:00.000000'),
    ('30000000-0000-4000-8000-000000000009', 9, '2099-01-01 00:00:00.000000'),
    ('30000000-0000-4000-8000-000000000010', 10, '2099-01-01 00:00:00.000000'),
    ('30000000-0000-4000-8000-000000000099', 1, '2000-01-01 00:00:00.000000');

INSERT INTO event (id, organizer_id, title, starts_at, capacity, registered, fee) VALUES
    (10, 1, 'Spring Workshop',   '2099-03-01 09:00:00.000000',  3, 0, 20.00),
    (11, 1, 'Free Talk',         '2099-03-02 09:00:00.000000',  5, 1,  0.00),
    (12, 2, 'Hack Night',        '2099-03-03 18:00:00.000000',  2, 1, 15.50),
    (13, 1, 'Past Meetup',       '2000-01-01 09:00:00.000000', 10, 1,  5.00),
    (14, 1, 'Capacity Race',     '2099-04-01 09:00:00.000000',  4, 0, 10.00),
    (15, 1, 'Same Person Race',  '2099-04-01 09:00:00.000000', 10, 0, 12.34),
    (16, 1, 'Revenue Review',    '2099-05-01 09:00:00.000000', 10, 3, 20.00),
    (17, 2, 'Empty Event',       '2099-06-01 09:00:00.000000', 10, 0, 99.99),
    (18, 1, 'Dime Event',        '2099-07-01 09:00:00.000000', 10, 3,  0.10);

INSERT INTO registration (id, event_id, attendee_id, fee, registered_at) VALUES
    (1, 12, 4, 15.50, '2026-01-01 00:00:00.000000'),
    (2, 13, 6,  5.00, '2026-01-01 00:00:00.000000'),
    (3, 16, 3, 17.50, '2026-01-01 01:00:00.000000'),
    (4, 16, 4, 20.00, '2026-01-02 00:00:00.000000'),
    (5, 16, 5, 20.00, '2026-01-03 00:00:00.000000'),
    (6, 11, 3,  0.00, '2026-01-01 01:00:00.000000'),
    (7, 18, 7,  0.10, '2026-02-01 00:00:00.000000'),
    (8, 18, 8,  0.10, '2026-02-01 00:00:00.000000'),
    (9, 18, 9,  0.10, '2026-02-01 00:00:00.000000');
```

`A(n)` 表示头 `Authorization: Bearer 30000000-0000-4000-8000-` 加 n 的十二位补零文本，n=1…10（alice…judy）；`E` 为尾号 99 的过期令牌（alice）；未知合法 UUID 固定 `99999999-9999-4999-8999-999999999999`；不存在的活动/报名 id 为 999999。

种子自检：行数 user=10、access_token=11、event=9、registration=9；外键成立；每个活动的 `registered` 等于其报名行数（11→1、12→1、13→1、16→3、18→3，其余 0）且不超过 capacity；`(event_id, attendee_id)` 无重复。

数据设计要点：①14 与 15 同一开始时刻，验证 id 升序的次序；②报名 3（carol 在 16 的 17.50）故意不等于活动当前 fee 20.00，用来区分“按报名记录的 fee 求和”和“人数×活动 fee”；③活动 11 是免费活动，已有 1 条 fee=0 的报名，收入为 0 而人数为 1；④活动 18 三条 0.10，合计必须是精确的 0.30（浮点会得 0.30000000000000004）；⑤活动 13 已开始；⑥活动 17 没有任何报名，收入与人数都是 0；⑦报名 3 与 6（carol）同刻，id 降序为 6、3。

各活动的收入/人数（种子状态）：10→0/0，11→0/1，12→15.50/1，13→5.00/1，14→0/0，15→0/0，16→57.50/3（17.50+20.00+20.00），17→0/0，18→0.30/3。

## 4. 接口合同（与 contract.json 一致）

完整 JSON 见 `contract.json`；`scalar_bodies`、`fee`、`capacity`、`view_fields`、`check_order`、`pagination` 是本规格补充的显式约定。

| 能力 | 路由 | 认证 | 请求字段 | 成功 | 响应 | 声明错误 |
|---|---|---|---|---|---|---|
| CreateEvent | `POST /api/events` | Bearer | title、startsAt、capacity、fee | 201 | EventView | INVALID_REQUEST 400 |
| ListEvents | `GET /api/events` | 无 | page、size | 200 | `Page<EventView>` | BadPage 400 |
| Register | `POST /api/events/{id}/registrations` | Bearer | 路径 id；体 `{}` | 201 | RegistrationView | EventNotFound 404；RegistrationClosed、AlreadyRegistered、EventFull 409 |
| MyRegistrations | `GET /api/my/registrations` | Bearer | page、size | 200 | `Page<RegistrationView>` | BadPage 400 |
| EventRevenue | `GET /api/events/{id}/revenue` | Bearer | 路径 id | 200 | JSON 数字 | Forbidden 403、EventNotFound 404 |
| EventRegistrationCount | `GET /api/events/{id}/registration-count` | Bearer | 路径 id | 200 | JSON 整数 | Forbidden 403、EventNotFound 404 |

- 401 为拦截器空体；声明错误信封 `{"code","message","fields":[]}`；校验错误为 400 `INVALID_REQUEST`，`fields` 元素 `path/code/message`。
- EventView 键集合精确为 `id、organizer、title、startsAt、capacity、registered、remaining、fee`；RegistrationView 精确为 `id、event、attendee、fee、registeredAt`；organizer/event/attendee 是数值主键。
- 判断顺序（Register）：EventNotFound → RegistrationClosed → AlreadyRegistered → EventFull，写入合同的 `check_order`，题面也写明。
- `Register` 路径字段以 URL 为准，体里同名 `id` 被覆盖；生成的应用拒绝 JSON 里的未知字段（`G/ApplicationConfigRenderer.java`:25），所以 CreateEvent 的体里写 `organizer` 或 `registered` 是 400。
- 标量响应：整数是不带引号的 JSON 整数，金额是 JSON 数字，按数值比较。

## 5. 两种需求原文

与 `requirement-scratch.md`（S01–S13）、`requirement-change.md`（C01–C08）逐字一致；无 SIR 语法，无实现提示。

### 5.1 改写题

```text
在给定软件上补回 Register、EventRevenue、EventRegistrationCount，保留 CreateEvent、ListEvents、MyRegistrations 的现有行为。
除 ListEvents 外，所有接口使用 Authorization: Bearer 访问令牌识别当前用户，缺少、格式错误、未知或过期的令牌均返回空体 401，ListEvents 允许匿名访问。
Register 按路径中的 id 为当前用户报名，成功返回 201 和接口合同中的 RegistrationView，保存当前用户对该活动的唯一报名记录，记录的 fee 取报名时刻该活动的 fee，registeredAt 为服务器处理请求的时刻，同时该活动的 registered 加一；活动不存在返回 404 EventNotFound，活动已开始（startsAt 不晚于当前时刻）返回 409 RegistrationClosed，同一用户已报名该活动返回 409 AlreadyRegistered，名额已满（registered 不小于 capacity）返回 409 EventFull，同时满足多个条件时按这个顺序返回，组织者也可以报名自己的活动。
多个用户并发报名同一活动时，成功次数不超过名额，活动的 registered 始终等于该活动的报名记录数，同一用户并发重复报名同一活动时恰好一次成功，其余返回 409 AlreadyRegistered，且 registered 只增加一次。
EventRevenue 只允许该活动的组织者使用，非组织者返回 403 Forbidden，活动不存在返回 404 EventNotFound（先判断活动是否存在），返回该活动全部报名记录的 fee 之和，按报名记录上保存的 fee 计算，不用活动当前的 fee，没有报名记录时为 0，结果是 JSON 数字且不能有浮点误差。
EventRegistrationCount 的使用权限和错误与 EventRevenue 相同，返回该活动的报名记录条数，是 JSON 整数，没有报名记录时为 0。
所有拒绝都不能改变已有行或新增业务记录，成功只改变该操作应改变的行，声明错误返回接口合同中的 code、message、fields，所有路由、请求字段和响应字段采用接口合同，路径中的字段值以 URL 为准，不能被同名请求体字段替换。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
```

### 5.2 从零写题

```text
除 ListEvents 外，所有接口使用 Authorization: Bearer 访问令牌识别当前用户，缺少、格式错误、未知或过期的令牌均返回空体 401，ListEvents 允许匿名访问。
CreateEvent 接收 title、startsAt、capacity、fee，创建一个以当前用户为组织者的活动，成功返回 201 和接口合同中的 EventView，初始 registered 为 0，title 是 1 到 100 个字符的非空白文本，capacity 是不小于 1 的整数，fee 是不小于 0 的金额，最多 8 位整数和 2 位小数（0 到 99999999.99），缺少、null、越界或超过两位小数均返回 400 INVALID_REQUEST 和对应字段错误，不要求 startsAt 晚于当前时刻。
ListEvents 分页返回尚未开始（startsAt 晚于当前时刻）的活动，按 startsAt、id 都升序，名额已满的活动仍然列出，EventView 的 remaining 等于 capacity 减 registered。
分页参数 page、size 可分别省略，默认分别为 1、20，有效范围分别为 1 到 10000、1 到 100，超出范围返回 400 BadPage，响应为 total、page、size、records，total 是全部匹配记录数，越界页仍返回正确 total。
Register 按路径中的 id 为当前用户报名，成功返回 201 和接口合同中的 RegistrationView，保存当前用户对该活动的唯一报名记录，记录的 fee 取报名时刻该活动的 fee，registeredAt 为服务器处理请求的时刻，同时该活动的 registered 加一；活动不存在返回 404 EventNotFound，活动已开始（startsAt 不晚于当前时刻）返回 409 RegistrationClosed，同一用户已报名该活动返回 409 AlreadyRegistered，名额已满（registered 不小于 capacity）返回 409 EventFull，同时满足多个条件时按这个顺序返回，组织者也可以报名自己的活动。
多个用户并发报名同一活动时，成功次数不超过名额，活动的 registered 始终等于该活动的报名记录数，同一用户并发重复报名同一活动时恰好一次成功，其余返回 409 AlreadyRegistered，且 registered 只增加一次。
MyRegistrations 按 registeredAt、id 都降序分页返回当前用户的报名记录。
EventRevenue 只允许该活动的组织者使用，非组织者返回 403 Forbidden，活动不存在返回 404 EventNotFound（先判断活动是否存在），返回该活动全部报名记录的 fee 之和，按报名记录上保存的 fee 计算，不用活动当前的 fee，没有报名记录时为 0，结果是 JSON 数字且不能有浮点误差。
EventRegistrationCount 的使用权限和错误与 EventRevenue 相同，返回该活动的报名记录条数，是 JSON 整数，没有报名记录时为 0。
EventView 仅包含 id、organizer、title、startsAt、capacity、registered、remaining、fee，RegistrationView 仅包含 id、event、attendee、fee、registeredAt，其中 organizer、event、attendee 是数值主键。
所有拒绝都不能改变已有行或新增业务记录，成功只改变该操作应改变的行（CreateEvent 新增一个活动，Register 新增一条报名记录并使该活动的 registered 加一），声明错误返回接口合同中的 code、message、fields。
所有路由、请求字段和响应字段采用接口合同，路径中的字段值以 URL 为准，不能被同名请求体字段替换，请求体不能指定组织者或 registered。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
```

题面与合同、§1.3 声明清单共同提供给出题流程；DDL/seed 由 oracle 提供。

## 6. 判卷检查清单与逐句追溯

### 6.1 执行与断言约定

一个 `EventsRegistrationBusinessConformanceIT`（待实现）复用 `BusinessSliceHarness`，同一套 HTTP/JDBC 检查运行参考或候选。§6.2–§6.6 每小节开始前重放 §3 的 seed（先删 registration、access_token，再 event、user），同小节按表序运行；§6.7 每轮单独重置。自增 id 只从响应取得。

记号同 fitness 规格 §6.1：`EV(r)`/`RV(r)` 是 EventView/RegistrationView 的精确键集合、数值类型与 JDBC 逐字段一致（金额用 `compareTo`，Moment 解析为 UTC 时刻，新建行允许 1 ms 精度差）；`PAGE_EV/PAGE_RV(ids,total,page,size)`；`INT(n)`；`DEC(x)`；`ERR(n,E)`；`AUTH401`；`UNCHANGED` = 独立连接读取 user/access_token/event/registration 四表全部列全部行按主键排序，前后完全相等；每个成功写请求也比较全库差异，只允许清单列出的变化。

**不变量 I01**（每个小节结束、每轮并发结束后，用独立 JDBC 检查）：每个活动 `registered == COUNT(registration WHERE event_id)`，`registered <= capacity`，`(event_id, attendee_id)` 无重复，且 `SUM(fee)` 等于 HTTP 的 EventRevenue（Java 中 `BigDecimal` 重算）。

### 6.2 公开入口、身份与创建

| 检查 | HTTP 请求 | 期望 | 数据库验证 | 原句 |
|---|---|---|---|---|
| B01 就绪与列表 | anon，`GET /api/events?page=1&size=10` | PAGE_EV([10,11,12,14,15,16,17,18],8,1,10)；13 已开始，不在其中；每项 remaining=capacity−registered（3,4,1,4,10,7,10,7） | UNCHANGED | S01/S03/S10；C02 |
| B02 非法身份矩阵 | 五个需认证接口：`POST /api/events` 体 `{"title":"auth","startsAt":"2099-08-01T09:00:00Z","capacity":5,"fee":1}`、`POST /api/events/10/registrations` 体 `{}`、`GET /api/my/registrations?page=1&size=10`、`GET /api/events/16/revenue`、`GET /api/events/16/registration-count`，各用无头、`Bearer not-a-uuid`、未知 UUID、E，共 20 请求 | 每次 AUTH401 | 逐次 UNCHANGED | S01/S11；C02/C07 |
| C01 创建 | A(1)，`POST /api/events`，`{"title":"New Fest","startsAt":"2099-08-01T09:00:00Z","capacity":50,"fee":12.50}` | 201 EV：organizer=1、registered=0、remaining=50、fee=12.50、startsAt 同一时刻 | 只新增该行（organizer_id=1，registered=0，fee=12.50）；其他不变 | S02/S10/S11 |
| C02 边界值与身份 | A(2)，`{"title":"Free Fest","startsAt":"2000-01-01T00:00:00Z","capacity":1,"fee":0}`；A(1)，fee=99999999.99；A(1)，fee=12.3400；A(1)，title 为 100 个 ASCII 字符 | 都是 201；organizer 分别 2、1、1、1；`12.3400` 存为 12.34；过去的 startsAt 被接受 | 各只新增一行；organizer 来自令牌 | S02；C07 |
| C03 非法输入 | A(1)：fee 取 100000000.00、12.345、-0.01、省略、null；capacity 取 0、-1、省略、null；title 取省略、null、空串、三个空格、101 个字符；startsAt 取省略、null、`"tomorrow"` | 每次 400 INVALID_REQUEST；`fields` 含对应 `path`（fee/capacity/title/startsAt）；省略/null 的 code 为 NotNull（title 为 NotBlank）；其余 code 与 `"tomorrow"` 的 path 不固定，只检查状态与 code=INVALID_REQUEST，实际值记入报告（§7 第 7 行） | 每次 UNCHANGED | S02/S11 |
| C04 不能自设组织者或计数 | A(1)，合法体外加 `"organizer":2`；另一次加 `"registered":5` | 400（未知字段被拒；只检查状态） | UNCHANGED | S12 |

### 6.3 列表与本人报名

| 检查 | HTTP 请求 | 期望 | 重点 | 原句 |
|---|---|---|---|---|
| L01 分页 | anon，`GET /api/events?size=3`；`&page=2`；`&page=3`；`&page=4` | PAGE_EV([10,11,12],8,1,3)；([14,15,16],8,2,3)；([17,18],8,3,3)；([],8,4,3) | 14、15 同刻按 id 升序；total 不用本页长度 | S03/S04 |
| L02 默认与上界 | anon，`GET /api/events`；`?page=2`；`?size=2`；`?page=10000&size=100` | PAGE_EV(全部 8,8,1,20)；([],8,2,20)；([10,11],8,1,2)；([],8,10000,100) | page/size 各自可省 | S04 |
| L03 坏分页 | anon，`GET /api/events` 追加 page=0/-1/10001（size=2）、size=0/-1/101（page=1），共 6 次；同样对 `GET /api/my/registrations`（A(3)），共 12 次 | 每次 ERR(400,BadPage) | 逐次 UNCHANGED | S04 |
| M01 本人报名 | A(3)；A(4)；A(5)；A(6)；A(7)；A(1)、A(2)、A(10) 各 `GET /api/my/registrations` | PAGE_RV([6,3],2,1,20)；([4,1],2,1,20)；([5],1,1,20)；([2],1,1,20)；([7],1,1,20)；([],0,1,20)×3 | 3、6 同刻按 id 降序；只看自己；无报名是空页不是 404 | S07/S10 |
| M02 分页 | A(3)，`?size=1`；`?size=1&page=2`；`?size=1&page=3` | ([6],2,1,1)；([3],2,2,1)；([],2,3,1) | | S07/S04 |

### 6.4 报名

按序在同一份重放的 seed 上执行；每个成功写请求比较全库差异。

| 检查 | HTTP 请求 | 期望 | 数据库验证 | 原句 |
|---|---|---|---|---|
| R01 首次报名 | A(1)，`POST /api/events/10/registrations`，`{}` | 201 RV：event=10、attendee=1、fee=20.00、registeredAt 在请求前后之间 | 只新增一行；事件 10 的 registered 0→1；其他不变；I01 | S05/S10/S11 |
| R02 重复 | A(1)，同请求 | ERR(409,AlreadyRegistered) | UNCHANGED（registered 仍 1，无新行） | S05 |
| R03 两人继续 | A(2) 再 A(3) 报名活动 10 | 都 201，fee=20.00 | registered 依次 2、3（= capacity） | S05 |
| R04 名额已满 | A(4) 报名活动 10 | ERR(409,EventFull) | UNCHANGED；无 (10,4) 行；registered 仍 3 | S05 |
| R05 顺序：已报名先于已满 | A(1) 再报名活动 10（已报名且已满） | ERR(409,AlreadyRegistered)，不是 EventFull | UNCHANGED | S05（判断顺序） |
| R06 已开始 | A(10) 报名活动 13；A(6) 报名活动 13（已有报名且已开始） | 两次都是 ERR(409,RegistrationClosed)（已开始先于已报名） | 逐次 UNCHANGED | S05 |
| R07 不存在 | A(1) 报名 999999 | ERR(404,EventNotFound) | UNCHANGED | S05 |
| R08 免费活动 | A(1) 报名活动 11 | 201，fee=0.00 | 新增一行 fee=0；registered 1→2 | S05 |
| R09 路径优先 | A(2)，`POST /api/events/11/registrations`，体 `{"id":10}` | 201，event=11 | 新增的是 (11,2)；活动 10 不变 | S12 |
| R10 组织者报名自己的活动 | A(1) 报名活动 14 | 201 | 新增 (14,1)；registered 0→1 | S05 |
| R11 fee 是报名时快照 | 夹具连接 `UPDATE event SET fee=25.00 WHERE id=12`（finally 里恢复 15.50）；A(7) 报名活动 12 | 201，fee=25.00 | 新行 fee=25.00，种子里 dave 的 15.50 不变；registered 1→2；A(2) 请求活动 12 收入 → DEC(40.50)，人数 INT(2) | S05/S08 |
| R12 列表反映名额 | anon `GET /api/events?size=20`（R01–R10 之后） | 活动 10 的 registered=3、remaining=0 且仍在列表中 | UNCHANGED | S03 |
| R13 本人列表 | A(1) `GET /api/my/registrations` | 包含 R01、R08、R10 产生的三行，registeredAt 降序 | UNCHANGED | S07 |

### 6.5 收入与人数

每次只读，UNCHANGED。种子状态下：

| 检查 | 请求 | 期望 | 重点 | 原句 |
|---|---|---|---|---|
| V01 | A(1) 对活动 16 的 revenue 与 registration-count | DEC(57.50)；INT(3) | 17.50 的快照 fee 参与求和（人数×20.00 会得 60.00） | S08/S09 |
| V02 | A(1) 对活动 11、13、18、10、14、15；A(2) 对活动 12、17 | 11→DEC(0)/INT(1)；13→DEC(5.00)/INT(1)；18→DEC(0.30)/INT(3)；10、14、15→DEC(0)/INT(0)；12→DEC(15.50)/INT(1)；17→DEC(0)/INT(0) | 免费活动收入 0 人数 1；18 的 0.30 必须精确；已开始的 13 也能查；空集合是 0 | S08/S09 |
| V03 非组织者 | A(2) 对活动 16；A(1) 对活动 12；A(3) 对活动 11（两个接口各一次） | 每次 ERR(403,Forbidden) | 逐次 UNCHANGED | S08/S09 |
| V04 不存在 | A(1) 对 999999（两接口） | ERR(404,EventNotFound)（先判存在） | UNCHANGED | S08/S09 |
| V05 报名后读取 | 在 R03 之后，A(1) 对活动 10 | DEC(60.00)；INT(3) | 新报名立刻被统计读到；registered 与人数一致 | S08/S09 |

### 6.6 响应形状与错误信封

I02：上述所有 EV/RV/PAGE 响应键集合精确，标量不是对象或带引号文本，分页外层恰好 `total/page/size/records`。I03：所有声明错误信封键集合精确，INVALID_REQUEST 的 `fields` 元素键为 path/code/message。

### 6.7 并发：两轮独立竞争

每轮先重置 §3 数据，6 个工作线程各自建独立 HTTP 客户端，先到 ready latch，再由 start latch 同时放行；单请求超时 30 秒，整轮收集限时 35 秒。夹具另开控制连接、关闭 autocommit，先 `SELECT id FROM event WHERE id=<目标> FOR UPDATE` 持锁，再同时发请求，只观察本 schema 在 `performance_schema.data_lock_waits` 中的真实等待，确认六个请求来自六个不同运行时连接/事务均已排在该目标的锁等待链上（5 秒内）后释放；做法同博客 §6.7，不注入应用钩子；没有锁观测权限记 NOT_RUN，不删断言。生成的 `Register` 在 `UPDATE event` 处等待，所以锁住活动行能让六个请求同时卡在守卫更新处（生成物已确认 `UPDATE` 先于 `INSERT`）。

| 检查 | 六个实际 HTTP 请求 | HTTP 结果 | JDBC 最终状态与不变量 | 原句 |
|---|---|---|---|---|
| X01 名额竞争 | A(3)…A(8) 各一次 `POST /api/events/14/registrations` `{}`（容量 4） | 恰好 4 个 201 RV（fee=10.00）、2 个 409 EventFull；不要求哪几个用户赢 | 活动 14 的 registered=4=报名行数=4，四个不同的 attendee，败者没有行；其余行/列不变；随后 A(1) 的 revenue=DEC(40.00)、count=INT(4)；I01 | S06/S05 |
| X02 同一人重复报名 | A(2) 六次 `POST /api/events/15/registrations` `{}`（容量 10） | 恰好 1 个 201、5 个 409 AlreadyRegistered | (15,2) 恰好一行；registered=1（不是 2～6：失败的事务必须连同计数一起回滚）；其余不变；I01 | S06/S05 |

500、死锁、锁超时、请求超时都不能当成 409 或成功；不增加服务端重试假设；全局不变量在所有请求结束后用独立 JDBC 重查，不从成功响应条数推导。X01 的判据是“4 胜 2 败”而不是“唯一赢家”：R:1528 说明容量 N 的守卫计数多次成功是正常结果。

### 6.8 需求句子覆盖索引

| scratch 原句 | change 承接 | 必须存在的检查 |
|---|---|---|
| S01 身份 | C02 | B01、B02 |
| S02 创建与校验 | C01（保留 CreateEvent） | C01–C04 |
| S03 列表 | C01（保留 ListEvents） | B01、L01、L02、R12 |
| S04 分页 | C01（保留） | L01–L03、M02 |
| S05 报名 | C03 | R01–R13、V05 |
| S06 并发 | C04 | X01、X02、I01 |
| S07 本人报名 | C01（保留 MyRegistrations） | M01、M02、R13 |
| S08 收入 | C05 | V01–V05、R11、I01 |
| S09 人数 | C06 | V01–V05 |
| S10 精确投影 | C01、C07 | I02、全部 EV/RV/PAGE 比较 |
| S11 拒绝/写范围/信封 | C07 | 全部 UNCHANGED、成功全库差异、I03 |
| S12 合同/路径优先/不能自设计数 | C07 | R09、C04 |
| S13 输出格式 | C08 | 静态检查，不算业务 HTTP 检查 |

夹具行数、CI 开关、构建、readiness、汇总是执行器前置/汇总，与业务原句分开报告。

## 7. 依赖生成器行为的风险与依据，以及“发现”

### 7.1 风险与依据

| # | 风险/本规格裁决 | 语言参考依据 | 本轮核对的源码与落地要求 |
|---|---|---|---|
| 1 | `Money` 的明确精度：`Fee` 为 `DECIMAL(10,2)`、下限 0；等值写法（`12.3400`）被接受，`12.345`、负数被拒 | R:94、R:178–192、R:253 | `G/DtoRenderer.java`:234–235（`@DecimalMin`）；`G/DecimalValidationRenderer.java`:29–34（`acceptsDecimal`，末尾为零的等值写法可接受）；生成的 `NewEvent` 带 `@ExactDecimal(precision=10, scale=2)` 与 `@DecimalMin("0")`。§0.2 负例表明去掉精度编译器不报错，所以 C02/C03 要测 |
| 2 | `sum` 只读、十进制、空集合为 0；权限要自己写 | R:1681–1716（R:1712 权限） | `EventRevenue` 里先 `validate event.organizer == actor`；`A/conformance/DecimalSumChecks.java`:42–55 以 `BigDecimal` 比较；V02 的 0.30 检验精确性 |
| 3 | `count` 是 64 位整数，不能防并发 | R:1624–1631、R:1679 | 本题 `count` 只读，用于 EventRegistrationCount；与 `registered` 的一致性由 I01 与 V05 检验 |
| 4 | 联合唯一键是“一人一场只一次”的最后防线 | R:261、R:1376–1378 | DDL 必须真实建立 `(event_id, attendee_id)` 唯一键；`G/WorkflowRenderer.java`:821 把 `DuplicateKeyException` 转成声明错误 |
| 5 | 先 `exists` 再 create 时 `persist … else` 由编译器强制；先 persist 被计数的行 | R:1376–1378、R:1532 | §0.2 负例：`SIR-VALID-004`、`SIR-VALID-015`；参考里 `persist event;` 在 `persist made else AlreadyRegistered;` 之前 |
| 6 | 守卫计数不保证“唯一赢家”，容量 N 就是 N 个赢家；无守卫版本也能编译 | R:1488–1530 | §0.2 第三条负例；X01 判据为 4 胜 2 败，且 registered 必须等于行数 |
| 7 | 校验错误 code 字符串（`NotNull`、`NotBlank` 之外，如 DecimalMin、ExactDecimal）未实测；`startsAt="tomorrow"` 属于“不可读请求体”路径，`fields` 可能为空；`capacity=1.5` 不作为非法样例（Jackson 默认可能把 1.5 截断为整数，未验证） | R:402 | `G/ApiExceptionAdviceRenderer.java`:46–57（code 取 Spring 约束名）；未运行，故 C03 对这些情形只固定状态与 `INVALID_REQUEST`，实际值首次 CI 写入报告 |
| 8 | 视图计算字段 `remaining = capacity − registered` | R:454–472 | `Quantity − Count` 同为 32 位整数，生成通过；B01/L01 逐项检查 |
| 9 | 查询里的 `item.startsAt > now()`；`Register` 里的 `validate event.startsAt > now()` | R:1153（查询里的 `now()`）、R:1997 | 种子用 2099/2000 的固定时刻，不依赖真实等待 |
| 10 | 事务与隔离：普通 command 为 `@Transactional`（MySQL 默认可重复读）；锁住活动行后六个请求在守卫 UPDATE 处等待 | R:1488–1530 | `G/ServiceRenderer.java`:87–91；生成的 `RegisterService`；X02 的失败者先通过 `exists`，后在 INSERT 撞唯一键，`AlreadyRegistered` 抛出后整个事务回滚、计数随之回滚（最终 registered=1 验证） |
| 11 | 创建 201；其余 200；401 空体 | R:902 | `G/ControllerRenderer.java`:126、220；`G/ApplicationRenderer.java`:336、344 |
| 12 | 请求体未知字段 400，GET 多余参数忽略 | — | `G/ApplicationConfigRenderer.java`:25 |
| 13 | 分页默认值与上限 | R:402、R:1162 | `L/profile/SpringBootQueryPolicy.java`:21（20/100/10000），本题冻结为合同 |
| 14 | 就绪探针：匿名 GET 200 | — | `A/conformance/BusinessSliceHarness.java`:547；`ListEvents` 公开 |

### 7.2 发现（需要负责人决定，本轮没有改工具链）

**E1：`Register` 与 train 的 `course.course-selection.Enroll` 核心同构**（见 §0.3 最后一行）。语言上没有缺口；问题是训练价值：`update … when` 与 `persist … else` 已经各有 19 条，本题主要补 `Money`、`sum`、`count`。请决定：①接受（成本低、`Money` 精度与金额汇总是实质新增）；②要求再区分（例如加入“取消报名并释放名额”，但那会引入与 `Register` 反向的加锁顺序，需要先设计）；③把 `Register` 弱化成不带守卫计数、改补别的结构。我倾向 ①。

**E2：有意不做“缴费状态”。**原因见 §0 与 §0.3：`shop.Pay` 同形。代价是“缴费”只是报名即收取的快照，没有未缴/已缴之分。若负责人想要缴费状态，需要先确认它不算 dev 同源。

**E3：所有写能力的守卫与精度只能靠 IT 发现**（§0.2 后两条负例），已在 §6 里用 X01、C02/C03 覆盖，无需工具链改动。

**E4：本题不涉及整数 `sum`、可选 Ref 命名两个问题**（那是 fitness 规格的 F1、F2）；`Fee` 本来就是金额，用 `Money` 合适。

**E5（待首次 CI 实测，非工具链问题）：**§7.1 第 7 行的校验 code 字符串与不可读请求体的 `fields` 形态。

**收口条件：**负责人审核 → 回答 E1/E2 → 才进入下一阶段（落库 `.sir`、DDL/种子文件、业务 IT 与并发屏障、verify.yml 接入）。本规格不触发 CI，不冻结 task，不调用模型。
