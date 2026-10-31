# 健身房打卡题出题规格（草稿，待负责人审核）

## 0. 定位、基线与交付边界

基线为 `main` 的 `95def2c`。本题 family 为 `fitness.check-in`，split 为 `train`，两种题型的计划 ID 为 `fitness-check-in-change-v1`、`fitness-check-in-scratch-v1`；同源题面、改说法和后续变体留在同一集合。

**状态：规格草稿。**本轮只写了规格、`reference.sir`、`base.sir`、两份需求原文、`contract.json`、`traceability.md`；没有写 Java/IT、DDL/种子文件，没有改工具链，没有冻结 task，没有调用模型，没有启动数据库或应用，没有跑 Maven 测试。§2 的 DDL 与 §3 的种子是写在本文里的设计，实现阶段才落成文件。

**选择“会员打卡 → 本人按自然月看次数/总时长/平均时长 → 教练按可选条件看全部打卡”的 7 能力闭环。**会员管理、教练指派、项目管理、修改或删除打卡、会员自己的分页列表都不考；用户、教练、项目和有效访问令牌由判卷夹具预置。认证沿用博客的做法：数据库里真实的 Bearer 凭据，不以任意用户 ID 请求头或固定身份替代。

实现阶段的落地路径（待负责人确认后才做）：`sir-toolchain-application/src/test/resources/valid/fitness-check-in.sir`、`conformance/mysql/fitness-check-in-ddl.sql`、`fitness-check-in-seed.sql`、`FitnessCheckInBusinessConformanceIT.java`（复用 `BusinessSliceHarness`），再关联两份 task。

### 0.1 参考了什么

下文 `A/` 指 `sir-toolchain-application/src/test/java/io/kcg/sir/application/`，`G/` 指 `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/`，`L/` 指 `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/`，`R` 指 `docs/SIR_LANGUAGE_REFERENCE.md`；行号对应上述基线。

| 材料 | 本规格采用的做法 |
|---|---|
| `experiments/slm/drafts/blog/` 全部文件 | 章节结构、DDL/种子/合同/需求原文的格式；认证凭据、`UNCHANGED` 全库比较、`PAGE/ERR/AUTH401` 记号、逐句追溯 |
| `experiments/slm/drafts/new-problem-plan.md` §1–§3、§6 | 选题理由：训练数据里 `count`、`sum`、`avg`、`month` 一次都没出现；B1 的结构清单 |
| `experiments/slm/drafts/new-problem-survey.md` §0.1、§4 | 现有 family 与 split 的占用，同源边界 |
| `experiments/slm/prompts/sir-0.3-general-v9.md` | 教师能看到的规则：需求原文不要求手册和语言参考之外的能力 |
| `valid/money.sir`、`topics/ledger/project.sir`（只读） | 确认 test 侧账本的实体和能力，写 §0.3 边界核对；不借用其业务形状 |
| `A/conformance/DecimalSumChecks.java`:42–55、`CalendarLedgerChecks.java`:27、30–31 | 标量响应按 `BigDecimal` 数值比较；`avg` 半进位的已有证据；非法/缺失 `month` 都是 400 BadPeriod、GET 多余查询参数被忽略的已有证据 |
| `A/conformance/BusinessSliceHarness.java`:547 | 就绪探针是匿名 GET 并要求 200，决定本题要有一个公开接口（`ListPrograms`） |

### 0.2 本地检查结果（本轮实际运行）

环境说明：本轮在云端会话执行（`nproc`=4、内存 15 GB），不是开发机；单进程短任务，没有使用 `systemd-run`。云端没有 `~/.m2`，AGENTS.md 里的 `-o` 离线编译不可用，所以改为在线编译 `MAVEN_OPTS=-Xmx2g mvn -B -q -pl kcg-cli -am compile`（只编译，不跑测试，退出码 0）；这一步保留了 `JAVA_TOOL_OPTIONS`（在线取依赖需要代理），检查器运行时按要求去掉。检查器命令来自 `experiments.slm.dataset_io.checker_command()`，输入 `{"id","sir","stopAfter":"GENERATION"}`。

| 文件 | ok | stage | 诊断码 | 生成文件数 | digest（前 16 位） |
|---|---|---|---|---:|---|
| `reference.sir`（7 能力） | true | GENERATION | 无 | 45 | `af9b4363f1c0f195` |
| `base.sir`（3 能力） | true | GENERATION | 无 | 36 | `8fc06a3ac7a97813` |

base 比参考少的 9 个生成文件正好是：被删 4 个能力的 Controller 和 Service 各一个（`CoachCheckIns`、`CoachMonthVisits`、`MyMonthAverage`、`MyMonthHours`），加上不再需要的 `ExactAggregate.java`。对应 `PageResponse.java` 仍在，因为 base 保留了 `ListPrograms`（分页）。

负例（改 `reference.sir` 的副本，仅用于确认语言依据，副本没有保留在仓库）：

| 改动 | 结果 | 说明 |
|---|---|---|
| 把 `Hours` 改成 `Count`，对它做 `sum`/`avg` | SEMANTIC，`SIR-TYPE-001`“sum requires a Decimal field”（sum、avg 各一条） | 见 §7 发现 F1 |
| 参考中把 `Optional<Ref<…>>` 换成 `Optional<Id>` 并与 Ref 字段比较 | SEMANTIC，`SIR-TYPE-001`“比较操作数类型不一致” | `Optional<Ref<…>>` 是写法上唯一能做可选归属筛选的类型，见 §7 发现 F2 |
| `output Serial;` / `output Money;` 取代别名类型 | GENERATION 通过，生成文件 digest 不变 | 标量输出可以直接写类型，不需要 `type` 别名 |

另用 `kcg generate` 把参考生成到会话临时目录（不在仓库），只读取了生成的 DTO、Controller、Service，用于 §7 的核对；用 `kcg package` 导出了生成器自己的建表脚本，与 §2 的夹具 DDL 逐表逐列对照：列名、类型、长度、默认值、唯一键、外键一致，仅排序规则（`utf8mb4_0900_bin` 对 `utf8mb4_bin`）、索引/外键名不同。

### 0.3 与 dev/test 的边界核对

结论：**业务、实体和数据形状与 test 的 finance.money/library/market、dev 的 shop/booking 都不同；与 ledger 共享的只有 `month`、`sum`、`avg` 的机制，这是任务允许的相同机制。**逐条核对：

| 对照 | 其实体与能力（以仓库当前文件为准） | 本题是否相同 | 为什么不同 |
|---|---|---|---|
| test：finance.money / ledger（个人记账、分类预算、月报） | `topics/ledger/project.sir`：User、Category、Entry、Budget；EntriesBetween、TotalBetween、TotalsByCategory、AverageBetween、ReadTimeZone/SetTimeZone、TotalsForMonth、BudgetExceededForMonth 等 | **机制相同，业务不同** | 本题没有分类、预算、记账 CRUD、分组合计（`sum … by`）和“用户保存时区偏好”；被统计量是训练时长（0.25～12 小时，不是金额），时区是软件里固定的 `Asia/Shanghai`，不是每个用户一个时区；三项统计各是一个标量接口，不是 `Page<SumGroup>`；`count`、教练角色、可选会员/项目筛选分页是 ledger 没有的；数据形状是“少量会员的打卡行 + 贴着月界的时刻”，不是“分类下的账目” |
| test：library（借书、预约） | Book、Copy、Loan、Reservation；Borrow、GiveBack、Renew、ReserveBook、OverdueLoans 等 | 不同 | 本题没有库存、副本、借阅上限、预约队列、罚金，也没有 `for update`、守卫更新或关联 `any`；唯一写入是新增一行打卡 |
| test：market（二手买卖） | Item、TradeOrder；BrowseItems、BuyItem、CancelOrder、SoldOrders | 不同 | 没有商品、价格、买卖双方和订单状态；本题的只读筛选只是“按会员/项目可选条件列出打卡” |
| development：shop（购物车、下单扣库存） | ShopProduct、PurchaseOrder、OrderLine、CartItem、Coupon、Redemption；AddToCart、Checkout、Pay、ApplyCoupon 等 | 不同 | 没有购物车、订单、库存扣减、优惠券；没有金额乘法；没有 `each`、`repeat` |
| development：booking（预约房间、按周重复预订） | Room、Booking；Book、BookWeekly、CancelBooking、RoomSchedule | 不同 | 没有房间、时段重叠判断、按周重复；本题的时间只用于“归属哪个自然月”，不做区间排他 |

另外，本题的 `Role`（MEMBER/COACH）只用来区分“只看自己”和“教练看全部”，没有任何角色管理能力。

## 1. 范围与裁剪清单

保持 `software GymCheckIn`、namespace `example.gym`、原 Java 21 / Spring Boot / MyBatis-Plus / MySQL / Maven / REST 外壳。

### 1.1 七个能力

| 能力 | 做什么 | 保留理由（对应的结构） |
|---|---|---|
| ListPrograms | 公开，按 name、id 升序分页列项目 | 提供匿名就绪探针；`Page`、`order by` |
| LogWorkout | 会员为自己记录一次打卡（项目、时长） | 真实创建入口；`load … else`、`create`、Ref 输入、带精度的十进制输入校验；会员由令牌取得 |
| MyMonthVisits | 本人在某自然月的打卡次数 | `month … in … as since, until`、`count … where`；输出 `Serial` |
| MyMonthHours | 本人在某自然月的总时长 | `month`、`sum`；输出 `Money`（十进制） |
| MyMonthAverage | 本人在某自然月的平均时长 | `month`、`avg`（半进位到两位小数） |
| CoachCheckIns | 教练按可选会员、可选项目筛选打卡，分页 | `input.x is none or item.x == input.x` 可选筛选、`Page`、角色检查 |
| CoachMonthVisits | 教练统计全体会员某月打卡次数，可选项目 | `month`、`count`、可选筛选、角色检查 |

不考：注册登录、会员/教练/项目的管理、修改或删除打卡、会员自己的分页列表、教练指派会员、统计导出。

### 1.2 对 new-problem-plan §1 结构的覆盖

| 结构 | 本题用到的能力 |
|---|---|
| `count … where` | MyMonthVisits、CoachMonthVisits |
| `sum` | MyMonthHours |
| `avg` | MyMonthAverage |
| `month … in … as since, until`（只能用在 readonly query） | MyMonthVisits、MyMonthHours、MyMonthAverage、CoachMonthVisits |
| 可选筛选 `input.x is none or …` | CoachCheckIns（member、program）、CoachMonthVisits（program） |
| `Page` 分页 | ListPrograms、CoachCheckIns |
| `Money` | 时长类型 `Hours = Money where precision(4, 2), min(0.25), max(12)`，输出 `Money`。**注意：这里的 Money 不是金额，只是 `sum`/`avg` 唯一支持的十进制，见 §7 发现 F1** |
| 本题不覆盖（属于其他 family） | `any`、`containsLiteral`、`exists`、`unique (a, b)`、`each`、`repeat`、`scope`、`versioned`、`patch of`、`persist … else`、`update … when`、`for update` |

### 1.3 全部声明

| 种类 | 名称与完整成员 |
|---|---|
| enum | Role：MEMBER、COACH |
| type | Hours = Money where precision(4, 2), min(0.25), max(12) |
| entity | User：`id: Id generated auto`；`name: Name unique`；`role: Role default Role.MEMBER` |
| entity | AccessToken：`id: Token generated uuid`；`owner: Ref<User>`；`expiresAt: Moment`；`credential subject owner expires expiresAt` |
| entity | Program：`id: Id generated auto`；`name: Code unique` |
| entity | CheckIn：`id: Id generated auto`；`member: Ref<User>`；`program: Ref<Program>`；`hours: Hours`；`checkedInAt: Moment default now()` |
| view | CheckInView from CheckIn：id、member（Ref<User>，返回数值主键）、program（Ref<Program>，返回数值主键）、hours、checkedInAt |
| view | ProgramView from Program：id、name |
| input | PageQuery：`page: PageNo`、`size: PageSize` |
| input | LogInput：`programId: Id`、`hours: Hours` |
| input | MonthQuery：`month: Optional<Text>` |
| input | CoachMonthQuery：`month: Optional<Text>`、`program: Optional<Ref<Program>>` |
| input | CoachQuery：`member: Optional<Ref<User>>`、`program: Optional<Ref<Program>>`、`page: PageNo`、`size: PageSize` |
| error | BadPage、BadPeriod：400；Forbidden：403；ProgramNotFound：404 |
| capability | 仅 §1.1 七项；LogWorkout 是 authenticated + atomic 的 command，其余六个是 readonly query，除 ListPrograms 外都 authenticated |

`month` 与 `zone`：`month` 步骤的区域写成字面量 `"Asia/Shanghai"`（`reference.sir` 里共四处 `month input.month in "Asia/Shanghai" …`），请求里没有 zone 字段。`reference.sir` 即以上全部声明的完整源码。

### 1.4 改写 base 的机械差异

以 `reference.sir` 为起点，仅删除四个完整 capability：MyMonthHours、MyMonthAverage、CoachCheckIns、CoachMonthVisits（`base.sir` 由脚本按能力块切掉，其余字节不动）。保留 ListPrograms、LogWorkout、MyMonthVisits，所有实体、enum、type、view、input、error 都不删（CoachQuery、CoachMonthQuery 暂时没人引用，仍保留）。

base 仍能列项目、记录打卡、查本人当月次数，不是空壳；`MyMonthVisits` 里已经有 `month` 与 `count` 的写法，补回 `sum`/`avg` 可以直接照抄区间条件。静态检查结果见 §0.2：GENERATION 通过，36 个文件，缺四个新增 Controller。这是静态负例，没有运行 base 得到 HTTP 失败。

## 2. 完整 MySQL DDL（夹具）

格式同博客：不写 CREATE DATABASE/USE，由 harness 选择自有 schema；InnoDB；固定 `utf8mb4_bin`。时间列 `DATETIME(6)`，没有服务器 NOW 默认值，打卡时刻由应用写入。类型依据 R:86–109、178–192、311 与 `L/api/SpringBootDeploymentPlanner.java`:54–76、219–230；命名依据 `L/internal/SpringBootModelLowerer.java`:559–561、657。下面的表已与 `kcg package` 导出的 `V1__initialize.sql` 对照过（见 §0.2）。

```sql
-- TEST_FIXTURE_DDL: isolated owned schema; not product initialization/migration.
CREATE TABLE `user` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(6) NOT NULL DEFAULT 'MEMBER',
    PRIMARY KEY (id),
    UNIQUE KEY gym_user_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE access_token (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT gym_access_owner FOREIGN KEY (owner_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE program (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY gym_program_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE check_in (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    program_id BIGINT NOT NULL,
    hours DECIMAL(4,2) NOT NULL,
    checked_in_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT gym_check_in_member FOREIGN KEY (member_id) REFERENCES `user` (id),
    CONSTRAINT gym_check_in_program FOREIGN KEY (program_id) REFERENCES program (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

表名 `user`、`check_in`、列名 `role`、`hours` 都不在 `L/profile/MySqlIdentifiers.java` 的 MySQL 8.4 保留字表里，生成的 SQL 不需要转义；`user` 在 DDL 里加反引号仅沿用博客写法。DDL 不加业务 CHECK（例如不限制 hours 范围），范围由应用校验、由 IT 验证。

## 3. 种子数据与请求记号

时间均按 UTC；harness 的应用 JVM、JDBC 和观察连接保持 UTC。有效凭据截至 2099 年，过期凭据固定在 2000 年。用户不是通过被测接口注册的；这些 INSERT 不算候选通过了创建行为。

```sql
INSERT INTO `user` (id, name, role) VALUES
    (1, 'alice', 'MEMBER'), (2, 'bob', 'MEMBER'), (3, 'carol', 'MEMBER'), (4, 'dave', 'COACH');

INSERT INTO access_token (id, owner_id, expires_at) VALUES
    ('20000000-0000-4000-8000-000000000001', 1, '2099-01-01 00:00:00.000000'),
    ('20000000-0000-4000-8000-000000000002', 2, '2099-01-01 00:00:00.000000'),
    ('20000000-0000-4000-8000-000000000003', 3, '2099-01-01 00:00:00.000000'),
    ('20000000-0000-4000-8000-000000000004', 4, '2099-01-01 00:00:00.000000'),
    ('20000000-0000-4000-8000-000000000099', 1, '2000-01-01 00:00:00.000000');

INSERT INTO program (id, name) VALUES (10, 'spin'), (11, 'yoga'), (12, 'swim');

INSERT INTO check_in (id, member_id, program_id, hours, checked_in_at) VALUES
    (201, 1, 10, 1.25, '2026-01-05 02:00:00.000000'),
    (202, 1, 11, 1.00, '2026-01-20 12:00:00.000000'),
    (203, 1, 10, 1.50, '2026-01-31 16:30:00.000000'),
    (204, 1, 10, 2.00, '2026-02-14 03:00:00.000000'),
    (205, 1, 11, 0.75, '2026-02-28 15:59:59.999999'),
    (206, 1, 10, 1.25, '2026-02-28 16:00:00.000000'),
    (207, 1, 11, 1.00, '2025-12-31 16:00:00.000000'),
    (208, 1, 10, 3.00, '2025-12-31 15:59:59.999999'),
    (301, 2, 10, 1.00, '2026-02-10 02:00:00.000000'),
    (302, 2, 11, 1.01, '2026-02-14 03:00:00.000000');
```

`A(n)` 表示头 `Authorization: Bearer 20000000-0000-4000-8000-` 加 n 的十二位十进制补零文本：A(1) alice（会员）、A(2) bob（会员）、A(3) carol（会员，没有任何打卡）、A(4) dave（教练）。`anon` 表示不带 Authorization；`E` 表示尾号 99 的过期令牌（alice）；未知合法 UUID 固定为 `99999999-9999-4999-8999-999999999999`。不存在的项目/会员 id 统一为 999999。

种子自检：4 张表行数依次为 user=4、access_token=5、program=3、check_in=10；所有外键存在；check_in 所有 hours 在 [0.25, 12] 且最多两位小数。自检失败记夹具错误，不判教师业务失败。

**月界设计（Asia/Shanghai，UTC+8，无夏令时）。**下表是每行打卡的当地时刻与所属月份，也是 §6.4 期望值的来源。其中 203、205、206、207、208 贴在月界上，分别能分辨“按 UTC 月份”“区间含/不含端点写反”“忘了时区”这几类错误：

| id | 会员 | UTC 时刻 | 当地（Asia/Shanghai） | 所属月 | hours |
|---|---|---|---|---|---:|
| 208 | alice | 2025-12-31 15:59:59.999999 | 2025-12-31 23:59:59.999999 | 2025-12 | 3.00 |
| 207 | alice | 2025-12-31 16:00:00 | 2026-01-01 00:00:00 | 2026-01 | 1.00 |
| 201 | alice | 2026-01-05 02:00:00 | 2026-01-05 10:00:00 | 2026-01 | 1.25 |
| 202 | alice | 2026-01-20 12:00:00 | 2026-01-20 20:00:00 | 2026-01 | 1.00 |
| 203 | alice | 2026-01-31 16:30:00 | 2026-02-01 00:30:00 | 2026-02（UTC 月是 1 月） | 1.50 |
| 204 | alice | 2026-02-14 03:00:00 | 2026-02-14 11:00:00 | 2026-02 | 2.00 |
| 205 | alice | 2026-02-28 15:59:59.999999 | 2026-02-28 23:59:59.999999 | 2026-02（最后一刻） | 0.75 |
| 206 | alice | 2026-02-28 16:00:00 | 2026-03-01 00:00:00 | 2026-03（第一刻） | 1.25 |
| 301 | bob | 2026-02-10 02:00:00 | 2026-02-10 10:00:00 | 2026-02 | 1.00 |
| 302 | bob | 2026-02-14 03:00:00 | 2026-02-14 11:00:00 | 2026-02（与 204 同刻） | 1.01 |

按月汇总（次数 / 总时长 / 平均时长，平均按两位小数半进位）：

| 会员 | 月份 | 次数 | 总时长 | 平均 | 备注 |
|---|---|---:|---:|---:|---|
| alice | 2025-12 | 1 | 3.00 | 3.00 | 只含 208 |
| alice | 2026-01 | 3 | 3.25 | 1.08 | 207、201、202；3.25/3=1.0833… |
| alice | 2026-02 | 3 | 4.25 | 1.42 | 203、204、205；4.25/3=1.4166…→1.42 |
| alice | 2026-03 | 1 | 1.25 | 1.25 | 只含 206 |
| alice | 2026-04 | 0 | 0 | 0 | 空集合为 0 |
| bob | 2026-02 | 2 | 2.01 | 1.01 | 1.005 半进位得 1.01（舍入到偶数或截断会得 1.00） |
| bob | 2026-01 | 0 | 0 | 0 | |
| carol、dave | 任意 | 0 | 0 | 0 | |

教练视角（所有会员）：2026-02 共 5 次（203、204、205、301、302），其中项目 10 有 3 次（203、204、301），项目 11 有 2 次（205、302），项目 12 有 0 次；2026-01 共 3 次；2026-03 共 1 次；2025-12 共 1 次；2026-04 共 0 次。

## 4. 接口合同（与 contract.json 一致）

使用现有 contract 的 namespace/capabilities/entities/interfaces/enums/declared_errors/storage 结构；`scalar_bodies`、`hours`、`calendar`、`average`、`view_fields`、`pagination` 是本规格补充的显式约定，导出 JSON 时不得丢弃。完整 JSON 见 `contract.json`，要点：

| 能力 | 路由 | 认证 | 请求字段（路径字段另列） | 成功 | 响应 | 声明错误 |
|---|---|---|---|---|---|---|
| ListPrograms | `GET /api/programs` | 无 | page、size | 200 | `Page<ProgramView>` | BadPage 400 |
| LogWorkout | `POST /api/check-ins` | Bearer | programId、hours | 201 | CheckInView | INVALID_REQUEST 400、ProgramNotFound 404 |
| MyMonthVisits | `GET /api/my/month/visits` | Bearer | month | 200 | JSON 整数 | BadPeriod 400 |
| MyMonthHours | `GET /api/my/month/hours` | Bearer | month | 200 | JSON 数字 | BadPeriod 400 |
| MyMonthAverage | `GET /api/my/month/average` | Bearer | month | 200 | JSON 数字 | BadPeriod 400 |
| CoachCheckIns | `GET /api/coach/check-ins` | Bearer | member、program、page、size | 200 | `Page<CheckInView>` | Forbidden 403、BadPage 400 |
| CoachMonthVisits | `GET /api/coach/month/visits` | Bearer | month、program | 200 | JSON 整数 | Forbidden 403、BadPeriod 400 |

说明：

- 所有 401 都是认证拦截器的空体响应，不解析 JSON；声明错误为 `{"code":"错误名","message":"错误名","fields":[]}`；请求校验错误为 400 `INVALID_REQUEST`，`fields` 元素是 `path/code/message`。
- **请求字段名以合同为准：`LogWorkout` 的项目是 `programId`；教练筛选的是 `member`、`program`（不带 `Id`）**。两者命名不同是生成器当前的行为，见 §7 发现 F2。
- CheckInView 的 member、program 是数值主键，不是嵌套对象；键集合必须精确为 `id、member、program、hours、checkedInAt`。
- 三个统计接口响应体是单个标量：次数是不带引号的 JSON 整数，总时长和平均时长是 JSON 数字，按数值比较（不要求固定小数位数）。
- 分页：page/size 可各自省略，默认 1/20，上限 10000/100，来自 `L/profile/SpringBootQueryPolicy.java`:21，本题显式冻结。
- `month` 严格写成 `YYYY-MM`；缺失、空串、格式不对、或自然月超出 `1000-01-01`…`9999-12-31` 的可表示范围，都是 400 BadPeriod（依据 §7 第 4 行）。

## 5. 两种需求原文

各代码块是可直接取出的题面（也是 `requirement-scratch.md`、`requirement-change.md` 的逐字内容）；scratch 按行依次为 S01–S11，change 为 C01–C08；最后一行是输出格式要求，单列作静态/格式检查，不伪造 HTTP 断言。题面里没有 SIR 语法，没有“使用某种聚合/区间步骤”等实现提示。

### 5.1 改写题

```text
在给定软件上补回 MyMonthHours、MyMonthAverage、CoachCheckIns、CoachMonthVisits，保留 ListPrograms、LogWorkout、MyMonthVisits 的现有行为。
除 ListPrograms 外，所有接口使用 Authorization: Bearer 访问令牌识别当前用户，缺少、格式错误、未知或过期的令牌均返回空体 401，ListPrograms 允许匿名访问；用户分为会员和教练，角色保存在 user 表的 role 列，值为 MEMBER 或 COACH。
MyMonthHours 和 MyMonthAverage 分别返回当前用户在 month 指定的自然月内的总时长和平均时长，month 写成 YYYY-MM，自然月按 Asia/Shanghai 时区计算，从当月 1 日 00:00 起（含）到下月 1 日 00:00 止（不含），month 缺少、格式不对或超出可表示范围均返回 400 BadPeriod，总时长和平均时长是 JSON 数字且不能有浮点误差，平均时长按两位小数四舍五入（0.5 进位），该月没有记录时都是 0。
CoachCheckIns 只允许教练使用，非教练返回 403 Forbidden，可按 member（会员 id）和 program（项目 id）筛选，两者都可省略，省略就不限制，给出不存在的 id 返回空页而不是 404，按 checkedInAt、id 都降序分页返回接口合同中的 CheckInView。
CoachMonthVisits 只允许教练使用，非教练返回 403 Forbidden，返回所有会员在 month 指定的自然月内的打卡次数，可按 program 筛选，省略就不限制，month 的规则和错误与 MyMonthVisits 相同。
分页参数 page、size 可分别省略，默认分别为 1、20，有效范围分别为 1 到 10000、1 到 100，超出范围返回 400 BadPage，响应为 total、page、size、records，total 是全部匹配记录数，越界页仍返回正确 total。
所有拒绝都不能改变已有行或新增业务记录，成功只新增 LogWorkout 的一行，声明错误返回接口合同中的 code、message、fields，所有路由、请求字段和响应字段采用接口合同，当前用户只由令牌决定，不能通过请求字段冒充其他用户。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
```

### 5.2 从零写题

```text
除 ListPrograms 外，所有接口使用 Authorization: Bearer 访问令牌识别当前用户，缺少、格式错误、未知或过期的令牌均返回空体 401，ListPrograms 允许匿名访问；用户分为会员和教练，角色保存在 user 表的 role 列，值为 MEMBER 或 COACH。
ListPrograms 按 name、id 都升序分页返回项目的 id 和 name。
分页参数 page、size 可分别省略，默认分别为 1、20，有效范围分别为 1 到 10000、1 到 100，超出范围返回 400 BadPage，响应为 total、page、size、records，total 是全部匹配记录数，越界页仍返回正确 total。
LogWorkout 接收 programId 和 hours，为当前用户记录一次打卡，成功返回 201 和接口合同中的 CheckInView，打卡时间为服务器处理请求的时刻，hours 是 0.25 到 12 小时之间的数，最多两位小数，缺少、null、超出范围或超过两位小数均返回 400 INVALID_REQUEST 和 hours 字段错误，programId 缺少或 null 返回 400 INVALID_REQUEST 和 programId 字段错误，项目不存在返回 404 ProgramNotFound。
MyMonthVisits、MyMonthHours、MyMonthAverage 分别返回当前用户在 month 指定的自然月内的打卡次数、总时长和平均时长，month 写成 YYYY-MM，自然月按 Asia/Shanghai 时区计算，从当月 1 日 00:00 起（含）到下月 1 日 00:00 止（不含），month 缺少、格式不对或超出可表示范围均返回 400 BadPeriod，次数是 JSON 整数，总时长和平均时长是 JSON 数字且不能有浮点误差，平均时长按两位小数四舍五入（0.5 进位），该月没有记录时三者都是 0。
CoachCheckIns 只允许教练使用，非教练返回 403 Forbidden，可按 member（会员 id）和 program（项目 id）筛选，两者都可省略，省略就不限制，给出不存在的 id 返回空页而不是 404，按 checkedInAt、id 都降序分页返回 CheckInView。
CoachMonthVisits 只允许教练使用，非教练返回 403 Forbidden，返回所有会员在 month 指定的自然月内的打卡次数，可按 program 筛选，省略就不限制，month 的规则和错误与 MyMonthVisits 相同。
CheckInView 仅包含 id、member、program、hours、checkedInAt，其中 member、program 是数值主键，ProgramView 仅包含 id、name。
所有拒绝都不能改变已有行或新增业务记录，成功只新增 LogWorkout 的一行，声明错误返回接口合同中的 code、message、fields。
所有路由、请求字段和响应字段采用接口合同，当前用户只由令牌决定，不能通过请求字段冒充其他用户。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
```

题面与合同、§1.3 声明清单共同提供给出题流程；DDL/seed 由 oracle 提供，不要求候选自己建表/造数。

## 6. 判卷检查清单与逐句追溯

### 6.1 执行与断言约定

一个 `FitnessCheckInBusinessConformanceIT`（待实现）复用 `BusinessSliceHarness`，用同一套 HTTP/JDBC 检查运行裁剪参考或候选，不能用参考源替换候选。

§6.2–§6.6 每小节开始前重放同一套 seed，同小节按表中顺序运行；重置只由夹具控制连接在无在途请求时执行，先删 check_in、access_token，再 program、user，随后按 §3 插入；保留一个生成应用进程。自增后续 ID 只从 HTTP 响应取得。

记号：`CV(r)` 指 CheckInView：精确四个以外无多余键，键集合为 §4 所列，数值检查 JSON 数字类型与数值，`member`、`program`、`hours`、`checkedInAt` 与 JDBC 同一行逐字段一致（`hours` 用 `BigDecimal.compareTo`，Moment 解析为 UTC 时刻后比较）；`PAGE_CV(ids,total,page,size)`/`PAGE_PV(ids,total,page,size)` 同时断言 200、外层恰好 `total/page/size/records` 四个键、每项完整 CV/ProgramView、总数、页参数和 id 的完整顺序；`INT(n)` 断言 200 且响应体是不带引号的 JSON 整数 n（`3.0`、`"3"` 都不行）；`DEC(x)` 断言 200 且响应体是 JSON 数字、`new BigDecimal(body).compareTo(new BigDecimal(x)) == 0`；`ERR(n,E)` 断言状态 n 和精确声明错误信封；`AUTH401` 单独断言 401 空体。`UNCHANGED` 指通过独立观察连接读取四张表全部列、全部行、按主键排序，请求前后内容完全相等；每个成功写请求也比较全库差异，只允许清单列出的变化；所有 GET 之后也要求 UNCHANGED。

**“统计期望”不能只靠本文常量。**§6.6 的 I01 在 Java 里用 `java.time`（Asia/Shanghai）和 `BigDecimal`（HALF_UP，两位）从 JDBC 读出的行独立重算，再与接口比较，防止本文表格自己出错。

### 6.2 公开入口与身份

| 检查 | HTTP 请求 | 期望 | 数据库验证 | 原句 |
|---|---|---|---|---|
| B01 就绪与项目列表 | anon，`GET /api/programs?page=1&size=10` | PAGE_PV([10,12,11],3,1,10)，名称依次 spin、swim、yoga | UNCHANGED；顺序按 name 升序而非 id | S01/S02/S03/S08；C02/C06 |
| B02 分页默认与边界 | anon，`GET /api/programs`；`?size=2`；`?page=2`；`?page=1&size=2`；`?page=2&size=2`；`?page=3&size=2`；`?page=10000&size=100` | PAGE_PV([10,12,11],3,1,20)；([10,12],3,1,2)；([],3,2,20)；([10,12],3,1,2)；([11],3,2,2)；([],3,3,2)；([],3,10000,100) | UNCHANGED；page/size 可各自省略 | S03；C06 |
| B03 坏分页 | anon，`GET /api/programs` 分别追加 page=0/-1/10001（size=2）、size=0/-1/101（page=1），共 6 请求 | 每次 ERR(400,BadPage) | 逐次 UNCHANGED | S03；C06 |
| B04 非法身份矩阵 | 六个需要认证的接口：`POST /api/check-ins` 体 `{"programId":10,"hours":1.5}`、`GET /api/my/month/visits?month=2026-01`、`/api/my/month/hours?month=2026-01`、`/api/my/month/average?month=2026-01`、`GET /api/coach/check-ins?page=1&size=10`、`GET /api/coach/month/visits?month=2026-01`，分别用无头、`Bearer not-a-uuid`、`Bearer <未知 UUID>`、E 四种身份，共 24 请求，均使用有效业务参数 | 每次 AUTH401 | **逐次** UNCHANGED | S01/S09；C02/C07 |

### 6.3 记录打卡

| 检查 | HTTP 请求 | 期望 | 数据库验证 | 原句 |
|---|---|---|---|---|
| W01 会员记录 | A(1)，`POST /api/check-ins`，`{"programId":10,"hours":1.50}` | 201，CV：member=1、program=10、hours=1.50 | 只新增该行；全部列与输入一致；checked_in_at 在请求前后 UTC 时刻之间（数据库微秒截断，容差 1 ms）；其他表/行不变 | S04/S08/S09；C07 |
| W02 身份来自令牌，最小值 | A(2)，`{"programId":11,"hours":0.25}` | 201，CV：member=2、hours=0.25 | 只新增 bob 的行，不能把会员写死为 alice | S04/S09/S10；C07 |
| W03 上界、等值写法、教练也能记 | A(4)，`{"programId":12,"hours":12}`；A(3)，`{"programId":10,"hours":1.2500}` | 都是 201；hours 分别为 12、1.25；member 分别为 4、3 | 各只新增一行；`1.2500` 存为 1.25（数值相等的写法被接受） | S04；C07 |
| W04 hours 非法 | A(1)，programId=10，hours 分别为：省略、null、0.24、12.01、0、-1、1.234、100 | 每次 400 INVALID_REQUEST，`fields` 含 `path=hours`；省略/null 的 code 为 NotNull，其余只要求 path，实际 code 记入报告（见 §7 第 8 行） | 每次 UNCHANGED | S04/S09；C07 |
| W05 programId 非法 | A(1)，hours=1：programId 省略、null；programId=999999 | 前两者 400 INVALID_REQUEST，`path=programId`，code NotNull；后者 ERR(404,ProgramNotFound) | 每次 UNCHANGED | S04/S09 |
| W06 不能冒充他人 | A(1)，`{"programId":10,"hours":1,"member":2}` | 400（生成的应用拒绝未知请求字段；只检查状态码） | UNCHANGED，没有新增给 bob 的行 | S10；C07 |

### 6.4 本人按自然月统计

以下均为只读，每次 UNCHANGED；A(1)=alice，A(2)=bob，A(3)=carol，A(4)=dave。次数、总时长、平均三个接口各请求一次，期望值取自 §3 汇总表。

| 检查 | 请求（`month=` 取值，用户） | 次数 / 总时长 / 平均 | 重点 | 原句 |
|---|---|---|---|---|
| Q01 | 2026-01，alice | INT(3) / DEC(3.25) / DEC(1.08) | 207 刚好落在 1 月第一刻；203（UTC 1 月 31 日）不能算 1 月；平均 3.25/3 = 1.0833… 取两位 | S05；C03 |
| Q02 | 2026-02，alice | INT(3) / DEC(4.25) / DEC(1.42) | 203 属于 2 月；205 是 2 月最后一刻；206 不属于 2 月 | S05；C03 |
| Q03 | 2026-03，alice；2025-12，alice | INT(1)/DEC(1.25)/DEC(1.25)；INT(1)/DEC(3.00)/DEC(3.00) | 206 是 3 月第一刻；208 属于 12 月（区间含起不含止） | S05；C03 |
| Q04 | 2026-02，bob | INT(2) / DEC(2.01) / DEC(1.01) | 平均 1.005 半进位得 1.01 | S05；C03 |
| Q05 | 空集合：2026-04，alice；2026-01，bob；2026-02，carol；2026-02，dave | 每个都是 INT(0) / DEC(0) / DEC(0) | 无记录是 0，不是 404、不是 null、不是错误 | S05；C03 |
| Q06 | 只能看自己：bob 请求 `?month=2026-01&member=1&memberId=1`（额外查询参数） | 三个接口都是 INT(0)/DEC(0)/DEC(0)，状态 200 | alice 在 1 月有 3 次；额外参数不能改变统计对象（生成的 GET 绑定忽略多余查询参数，已有证据 `CalendarLedgerChecks.java`:27） | S05/S10；C07 |
| Q07 | 非法 month：`MyMonthVisits` 对缺失、空串、`2026-1`、`2026-13`、`2026-00`、`2026-02-01`、`abcd-ef`、`9999-12`、`0000-01`、`0999-12` 各一次；`MyMonthHours`、`MyMonthAverage`、`CoachMonthVisits`（A(4)）各对缺失、`2026-13` 一次 | 每次 ERR(400,BadPeriod) | 逐次 UNCHANGED；缺失不是 400 INVALID_REQUEST | S05/S07；C03/C05 |
| Q08 | 新写入后统计：重置后 A(1) 先后记录 `{"programId":10,"hours":1.50}`、`{"programId":11,"hours":2.25}`，取响应中两行 checkedInAt 所在的 Asia/Shanghai 月份 M，再对 M 请求三个接口 | INT(2) / DEC(3.75) / DEC(1.88) | 3.75/2 = 1.875 半进位得 1.88；新写入立刻被统计读到；对 2025-12～2026-04 的结果与 Q01–Q05 相同（不被新行污染）。若两行的月份不同（恰好跨月），或 M 落在 2025-12～2026-04 内，记夹具错误 NOT_RUN，不判教师业务失败 | S04/S05；C03 |

### 6.5 教练查询

| 检查 | HTTP 请求 | 期望 | 数据库/反例重点 | 原句 |
|---|---|---|---|---|
| K01 非教练被拒 | A(1)、A(2) 各请求 `GET /api/coach/check-ins?page=1&size=10` 与 `GET /api/coach/month/visits?month=2026-02` | 每次 ERR(403,Forbidden) | 逐次 UNCHANGED；不能因为会员能看自己就放行 | S06/S07；C04/C05 |
| K02 全部与排序 | A(4)，`GET /api/coach/check-ins`（不带任何参数）；`?page=1&size=3`；page=2、3、4、5（size=3） | PAGE_CV([206,205,302,204,301,203,202,201,207,208],10,1,20)；([206,205,302],10,1,3)；([204,301,203],10,2,3)；([202,201,207],10,3,3)；([208],10,4,3)；([],10,5,3) | 204、302 同刻，按 id 降序 302 在前；总数不能用本页长度代替；205（15:59:59.999999）排在 206 之后 | S06/S03/S08；C04/C06 |
| K03 按会员筛选 | A(4)，`?member=1`；`?member=2`；`?member=3`；`?member=999999`；`?member=1&size=2&page=2` | PAGE_CV([206,205,204,203,202,201,207,208],8,1,20)；([302,301],2,1,20)；([],0,1,20)；([],0,1,20)；([204,203],8,2,2) | 没有打卡的会员、不存在的会员都是空页，不是 404 | S06；C04 |
| K04 按项目筛选 | A(4)，`?program=10`；`?program=11`；`?program=12`；`?program=999999` | PAGE_CV([206,204,301,203,201,208],6,1,20)；([205,302,202,207],4,1,20)；([],0,1,20)；([],0,1,20) | 项目筛选独立生效；不存在的项目是空页 | S06；C04 |
| K05 两个条件同时 | A(4)，`?member=1&program=11`；`?member=2&program=10`；`?member=2&program=12` | PAGE_CV([205,202,207],3,1,20)；([301],1,1,20)；([],0,1,20) | 两个可选条件用“与”，各自省略互不影响 | S06；C04 |
| K06 坏分页 | A(4)，对 `GET /api/coach/check-ins` 分别追加 page=0/-1/10001（size=2）及 size=0/-1/101（page=1），共 6 请求 | 每次 ERR(400,BadPage) | 逐次 UNCHANGED | S03；C06 |
| K07 教练月统计 | A(4)，`GET /api/coach/month/visits?month=2026-02`；`&program=10`；`&program=11`；`&program=12`；`&program=999999`；`month=2026-01`；`2026-03`；`2025-12`；`2026-04` | 依次 INT(5)、INT(3)、INT(2)、INT(0)、INT(0)、INT(3)、INT(1)、INT(1)、INT(0) | 统计的是全体会员而不是教练自己；月界规则同 Q01–Q03；不存在的项目是 0 不是 404 | S07；C05 |
| K08 新数据可见 | 重置后 A(1) 记录一次 `{"programId":12,"hours":1}`；A(4) 请求 `GET /api/coach/check-ins?page=1&size=3` 与 `GET /api/coach/month/visits?month=M&program=12`（M 为新行 checkedInAt 所在的 Asia/Shanghai 月） | 分页 total=11，首条是新行，其后依次 206、205，顺序按 checkedInAt 降序；月统计为 INT(1) | 全库只新增一行；若 M 落在 2025-12～2026-04 内记夹具错误 NOT_RUN | S04/S06/S07；C04/C05 |

### 6.6 独立重算与响应形状

| 检查 | 做法 | 期望 | 原句 |
|---|---|---|---|
| I01 统计独立重算 | 对 user∈{1,2,3,4}、月份∈{2025-12,2026-01,2026-02,2026-03,2026-04} 各取 JDBC 全部 check_in 行，在 Java 中用 `ZoneId.of("Asia/Shanghai")`、`BigDecimal` 重算次数、总和、`setScale(2, HALF_UP)` 平均，并重算教练全体/按项目的次数；再与接口逐项比较 | 全部相等 | S05/S07；C03/C05 |
| I02 响应形状 | 对上面所有成功响应：CV 与 ProgramView 的键集合精确；PAGE 外层恰好四个键；分页元素没有多余键；标量响应不是对象、不是带引号文本 | 全部匹配 | S08；C07 |
| I03 错误信封 | 所有声明错误为 `{"code":…,"message":…,"fields":[]}`，键集合精确；INVALID_REQUEST 的 `fields` 元素键为 path/code/message | 全部匹配 | S09；C07 |

### 6.7 并发

**本题不设并发轮次。**唯一写入是新增一行打卡：没有计数器、没有联合唯一约束、没有状态流转，并发请求之间不存在需要仲裁的共享行；加入一轮“六个并发创建”只能证明数据库能同时插入六行，信息量低，且不是任务对本题提出的要求（并发检查由 events.registration 承担）。如果负责人要求，可以追加“六个并发 LogWorkout 全部 201、行数加六、随后统计与 JDBC 重算一致”一轮，不需要锁等待屏障。

### 6.8 需求句子覆盖索引

| scratch 原句 | change 承接 | 必须存在的检查 |
|---|---|---|
| S01 身份/匿名/角色 | C02 | B01–B04、W02、K01 |
| S02 项目列表 | C02（保留） | B01、B02 |
| S03 分页 | C06 | B02、B03、K02、K06 |
| S04 记录打卡与校验 | C07（保留 LogWorkout） | W01–W06、Q08、K08 |
| S05 本人月统计 | C03（补回时长/平均）、C01（保留次数） | Q01–Q08、I01 |
| S06 教练查询 | C04 | K01–K06、K08 |
| S07 教练月统计 | C05 | Q07、K07、K08、I01 |
| S08 精确投影 | C07 | I02、全部 CV/PAGE 比较 |
| S09 拒绝/成功写范围/错误信封 | C07 | 全部 UNCHANGED、I03 |
| S10 合同/当前用户只由令牌决定 | C07 | W02、W06、Q06 |
| S11 输出格式 | C08 | 静态检查输入为完整 sir 0.3，不算业务 HTTP 检查 |

夹具初始行数、CI 开关、schema 清理、构建、readiness 和最终汇总是执行器前置/汇总，不能额外要求候选“实现判卷器”；失败照样阻断有效证据，但与业务原句分开报告。

## 7. 依赖生成器行为的风险与依据，以及“发现”

### 7.1 风险与依据

| # | 风险/本规格裁决 | 语言参考依据（R 的行号） | 本轮直接核对的源码与落地要求 |
|---|---|---|---|
| 1 | 统计接口响应体是单个标量：`count` 为 64 位整数，`sum`/`avg` 为十进制；直接写 `output Serial;`、`output Money;` 即可，不必起别名 | R:1631（count 结果为 64 位整数）、R:1708–1710（sum）、R:1718–1724（avg） | 生成的 Controller 返回 `Long`/`BigDecimal`，因此响应体是裸数字；`A/conformance/DecimalSumChecks.java`:42–55 以 `BigDecimal` 数值比较，本题照做，不检查小数位数（R:1710 说明 0 的显示位数不保证） |
| 2 | `sum`/`avg` 只能用在十进制字段上 | R:1708（“不支持整数字段”） | `sir-semantic/.../TypePass.java`:658、678：`SIR-TYPE-001 sum requires a Decimal field`；§0.2 负例已确认。**见发现 F1** |
| 3 | `avg` 按字段小数位四舍五入，0.5 进位；空集合为 0 | R:1724 | `L/internal/SpringBootModelLowerer.java`:1139–1146 取字段的 `precision` 小数位；`DecimalSumChecks.java`:52 已用 1.785→1.79 验证半进位；本题种子用 1.005→1.01、1.875→1.88 再验证，并让 3.25/3、4.25/3 取两位 |
| 4 | `month` 是只读 query 的步骤；严格 `YYYY-MM`；区间半开；缺失/非法都走声明的错误；可表示范围有限 | R:1778–1795 | `G/CalendarStepRenderer.java`:22–34：月份为空、不符合“四位数字-两位月份（01 到 12）”、区域不被接受或结果超出 `1000-01-01T00:00:00Z`…`9999-12-31T23:59:59.999999Z` 都抛声明错误；`A/conformance/CalendarLedgerChecks.java`:30–31 已有 `2026-1`、`2026-13`、`9999-12`、`0000-01`、缺失都是 400 BadPeriod 的证据。`0999-12` 的起点早于 1000-01-01 因而也是 BadPeriod（由同一段代码推出，首次 CI 实测确认） |
| 5 | 时区写成字面量 `"Asia/Shanghai"` | R:1793（两个表达式是文本或可选文本）、R:1997（表达式表里的文本字面量） | 检查器已让 `month input.month in "Asia/Shanghai" …` 通过 GENERATION；生成代码里区域变量是该字面量。手册（v9）只展示 `input.zone` 形式，没有字面量示例；R:1793 说明“两个表达式是文本”，因此需求原文没有要求手册之外的能力 |
| 6 | 可选筛选：参数缺省或 null 即不限制，不存在的 id 不是错误 | R:1222–1250、R:2043–2050（真值表） | 参考用 `input.member is none or item.member == input.member`；K03/K04 的“不存在的 id 返回空页”是该写法的直接结果，需求原文已写明 |
| 7 | **可选 Ref 输入的 HTTP 字段名不带 `Id`** | R:109（引用存主键）；v9 手册写“Ref 对应 HTTP 字段 xxxId”，没有可选情形 | `L/internal/SpringBootModelLowerer.java`:1942–1944：只有 `RefType` 加 `Id`，`Optional<Ref<…>>` 不是 `RefType`；本轮生成的 `CoachQuery` 字段是 `member`、`program`（`Optional<Long>`），而 `LogInput` 里必填 Ref 才是 `programId`。**见发现 F2** |
| 8 | `hours` 的范围与精度校验：`DecimalMin("0.25")`、`DecimalMax("12")`、精度 `(4,2)`；等值写法（`1.2500`）被接受 | R:178–192（金额精度，同规则适用于带 `precision` 的 Money 别名）、R:253（约束 `min`/`max`/`precision`） | `G/DtoRenderer.java`:234–235 生成 `@DecimalMin/@DecimalMax`；`G/DecimalValidationRenderer.java`:29–34 的 `acceptsDecimal` 接受末尾为零的等值写法；校验错误经 `G/ApiExceptionAdviceRenderer.java`:46–57 变成 400 INVALID_REQUEST，`fields` 的 code 取 Spring 约束名。**我没有运行，不能断言各 code 的字符串**，所以 W04 对 NotNull 之外只检查 `path`，实际 code 由首次 CI 写入报告 |
| 9 | 教练检查读取 `actor` 的非主键字段 | R:895 | 生成的 Service 先按主键读 User，不存在则 401，角色不符抛 `ForbiddenException`（本轮生成物 `CoachMonthVisitsService`）；因此“角色不是 COACH → 403”发生在业务之前 |
| 10 | 分页默认值与上限 | R:402、R:1162 | `L/profile/SpringBootQueryPolicy.java`:21：20/100/10000；语言参考没有列这些数字，所以本题显式冻结为合同 |
| 11 | 创建返回 201，查询/统计返回 200 | R:902 | `G/ControllerRenderer.java`:126、220：含 `CreateStep` 的能力加 `@ResponseStatus(CREATED)` |
| 12 | Bearer 凭据在业务前认证，401 空体；需要 external 认证模式 | R:372–389、R:807 | `G/ApplicationRenderer.java`:336、344 空体 401（与博客同一机制） |
| 13 | JSON 请求体里的未知字段被拒绝（400）；GET 的多余查询参数被忽略 | — | `G/ApplicationConfigRenderer.java`:25 `fail-on-unknown-properties: true`（W06 用到）；`CalendarLedgerChecks.java`:27 的 `userId=2` 例子证明 GET 多余参数被忽略（Q06 用到） |
| 14 | 就绪探针只能是匿名 GET 且期望 200 | — | `A/conformance/BusinessSliceHarness.java`:547：`new BusinessEndpointReadiness(200).waitUntilReadyGet(…)`；其余六个接口都要认证，所以增加公开的 `ListPrograms` 作为探针，不改工具链 |
| 15 | 表/列命名、DDL 类型 | R:86–109、R:253 | 见 §2；已与 `kcg package` 导出的 DDL 对照 |

### 7.2 发现（需要负责人决定，本轮没有改工具链）

**F1：`sum`/`avg` 只支持十进制，整数“分钟”不能聚合。**
- 需要什么：健身时长最自然的存法是整数分钟，`sum`/`avg` 直接作用在 `Count` 字段上。
- 现状：R:1708 明确不支持整数字段，TypePass 报 `SIR-TYPE-001`（§0.2 负例）。
- 本规格怎么做：把时长定义成“小时，最多两位小数”的十进制别名 `Hours = Money where precision(4, 2), min(0.25), max(12)`，并让需求原文如实写“0.25 到 12 小时、最多两位小数”。我认为这是正常的业务表示（工时系统常用小数小时），不是为了凑结构而绕口；但 `Money` 这个名字在这里不表示金额，教师和读者需要接受这一点。
- 按 AGENTS.md 三问：①新业务是否靠组合已有能力？是，当前规格完全靠已有能力实现；②普通需求在 SIR 中是否绕口？“累计分钟/件数/积分”这类整数求和要改成小数才能写，有一点绕；③修复一种实现问题能否让一类业务共同受益？整数 `sum`/`avg` 若支持，new-problem-plan 的 B5（积分按月汇总）、B7 之外的“工时、件数、积分”都受益。
- 建议：先接受当前规格，不改工具链；把“整数 sum/avg”记为待评审项，由负责人决定是否立项。

**F2：必填 Ref 输入的 HTTP 名带 `Id`，可选 Ref 输入的不带。**
- 需要什么：同一个概念在合同里应只有一种命名，教师按手册写 `Optional<Ref<User>>` 会预期 `memberId`。
- 现状：见 §7.1 第 7 行；`LogInput.programId` 与 `CoachQuery.member`/`program` 并存于同一软件。
- 本规格怎么做：合同照实际行为写 `member`、`program`，并在 §4 与需求原文中明示字段名；`LogWorkout` 的项目字段故意用显式 `programId: Id` + `load … else ProgramNotFound`，避免把必填 Ref 的 `programId` 与可选 Ref 的 `program` 命名差异再叠加到同一输入里。
- 三问：①是修复而不是新能力；②普通需求“可选的归属筛选”很常见，现状要求作者记住两套命名；③修复一次，所有可选 Ref 筛选类业务受益（例如 A1 任务板的可选指派人）。
- 建议：规格先按实际行为冻结；是否统一命名（改工具链）或在手册补一句，由负责人决定，不在本轮处理。

**F3（已用业务方式解决，无需决定）：**所有接口都要认证时没有匿名可读的就绪探针；通过增加公开的 `ListPrograms` 解决。

**F4（待首次 CI 实测，非工具链问题）：**§7.1 第 8 行的校验错误 code 字符串，以及第 4 行 `0999-12` 的结果，需要首次业务 IT 实测后写入 IT 断言；在此之前规格只固定 `path` 与状态码。

**收口条件：**负责人审核本规格 → 确认是否接受 F1/F2 → 才进入下一阶段（`.sir` 参考落库、DDL/种子文件、业务 IT、verify.yml 接入）。本规格不触发 CI，不冻结 task，不调用模型。
