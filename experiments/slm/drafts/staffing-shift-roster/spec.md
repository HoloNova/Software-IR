# 门店排班题出题规格（已确认；参考 CI PASSED）

## 0. 定位、基线与交付边界

family=`staffing.shift-roster`，split=`train`；正式原题 `staffing-shift-roster-change-v1` / `staffing-shift-roster-scratch-v1`，另有各3份p1/p2/p3等义表述。基线 main `e81200e0e039c5d178c95bbab2f12195ac13f0dd`。负责人已确认本规格、train隔离、关系id排序及不设容量/工资/跨店调动的边界；原需求边界保持；参考已由同SHA CI真实验收，任务与批次进展见implementation §7及共享冻结记录。

规格、DDL/seed、业务IT与两个workflow已完成，OGNL根因修复获批后已由5fe4c833及verify 41194982453验收，见 [implementation.md §7](implementation.md#7-修复版同-sha-ci-验收passed当前参考状态)。当前按负责人“继续下一步”进入task定义/冻结，不改原需求、reference/base、DDL/seed、判卷或工具链，不修改fitness/events，也不调用模型。本机不运行业务IT；新批次和未来候选不能继承参考运行门资格。

### 0.1 与 dev/test 的边界核对（独立审查）

直接核对 `topics/booking/project.sir`：Room(name/location/capacity)；Booking(room/booker/startsAt/endsAt/purpose)，Book/BookWeekly先锁Room、检查半开区间重叠，然后创建占用记录；同源属于development。

| 对照 | 业务、实体与数据形状差异 | 结论 |
|---|---|---|
| development：booking | 本题Store→Shift与Employee→Assignment←Shift形成员工与离散班次的多对多编排。Shift的键是门店/日期/开店或闭店班，没有开始/结束时刻、booker、purpose和区间排他。**周循环仅创建空班次，不给任何人占用时段**；后续独立AssignEmployee才新增关系。一班可以有多名员工，同一员工可以参加同日两个不同班次。booking每轮直接创建某用户的房间占用，没有独立“空班次+多人编排”的阶段。 | repeat/addDays和父锁机制相同；业务不变量、关系基数、循环产物明显不同，**不是BookWeekly改名**。 |
| development：shop | 没有商品/购物车/订单/明细/库存/金额/支付；员工编入班次不扣库存，不存在取消返库或“金额×数量”。 | 不同。 |
| test：finance.money / ledger | 无账目、分类、预算、月份汇总、金额或时区；日期用于门店日常开闭店工作，不做月报。 | 不同。 |
| test：library | 无书目/副本/Loan/借还/预约队列/罚金；班次不是可独占借出的物件，员工编排不减少一个可用副本数。 | 不同。 |
| test：market | 无买家/卖家/商品/交易/成交状态，编排关系不代表订单。 | 不同。 |

**边界裁决：负责人已确认可作为train新题。**如果把Employee直接当Room、把Assignment改成起止时间区间，再在repeat内为同一员工逐周创建Assignment，就与booking太像；本规格明确禁止走那条路线。若负责人认为“离散周班次”仍过近，替换建议是 **production.checklist：按批次数生成作业清单，再由员工认领清单项**（不做时间平移）；不擅自用备选取代本题。

### 0.2 材料与设计理由

已读取AGENTS.md、new-problem-plan.md §1–§3/§6、fitness/events/blog样板（blog无目录内reference.sir，其样板指向正式valid/blog.sir）、语言参考和v9手册。下文R=`docs/SIR_LANGUAGE_REFERENCE.md`，V=`experiments/slm/prompts/sir-0.3-general-v9.md`，行号固定在上述基线。

选“生成空班次→分页看班次/员工→把员工编入班次→按班次计数/按员工看编排”的七能力闭环。没有工资、打卡、跨店借调、自动优化排班、精确时刻冲突或人数上限；**count只是实际关系条数，不伪造一个名额计数器**。这样既补count，又不把排班做成booking或报名名额题的换名。

### 0.3 规格阶段实际检查结果（保留原始证据）

与maintenance规格共享同一次CLI编译：Windows 11、Java21.0.11、Maven3.9.14，18逻辑核/23.53GiB内存，编译前可用约2.92GiB；单次离线CLI依赖链编译、42秒、退出0，没有测试/全量构建。实际PowerShell命令：

```powershell
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
$env:MAVEN_OPTS='-Xmx2g'
mvn -B -o -q -pl kcg-cli -am compile
```

对应负责人给出的 `env -u JAVA_TOOL_OPTIONS MAVEN_OPTS=-Xmx2g mvn -B -o -q -pl kcg-cli -am compile`。本机没有env/systemd-run；未因此改为在线下载或启动业务IT。

检查器来自 `experiments.slm.dataset_io.checker_command(Path('D:/maven-repo'))`（settings指定的实际本地仓库）；单JVM，Xmx256m/Metaspace128m/ActiveProcessorCount2，九模块target/classes及ANTLR4.13.2类路径，`io.kcg.cli.KcgCli check`。stdin每行 `{id,sir,stopAfter:"GENERATION"}`；运行环境去掉JAVA_TOOL_OPTIONS。

首轮reference/base都在SEMANTIC报 `SIR-TYPE-001`，位置分别是旧reference第225行、旧base第154行：普通find不能 `order by shift`（Ref<Shift>）。只把EmployeeRoster排序改成id升序后，两份重新检查，退出0、stderr为空，真实stdout：

```jsonl
{"id":"staffing-shift-roster-reference","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":56,"digest":"70df0cece29c46b261a212076754f205405ae07927f6ff7b35c301067edef2ba","sourceSha256":"5d1cf02d377906ab75004fab1f5ae8f92a7f027da16b5c7617c87e877974c2d0"}
{"id":"staffing-shift-roster-base","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":50,"digest":"592f7d443900f1e5d0b45787c90b330cfcdcf42bf124393a23b4926bca736fca","sourceSha256":"ea9225ba705e3982732ec62c9f00e87b1eea67cd55f31f167f163c9d617473d8"}
```

| 文件 | stage | 诊断码 | 生成文件数 |
|---|---|---|---:|
| reference.sir | GENERATION | 无（含无WARNING） | 56 |
| base.sir | GENERATION | 无（含无WARNING） | 50 |

按员工查看的是“编排记录列表”，不是按日期排序的日历；合同与题面已经如实约定。不得从静态生成推导MySQL、HTTP、事务或并发已通过。

## 1. 范围、全部声明与结构覆盖

### 1.1 七个能力

| 能力 | 职责 | 覆盖结构与R依据 |
|---|---|---|
| ListStores | 匿名门店目录 | Page、view、find/order：450–472、1115–1162 |
| GenerateWeeklyShifts | 同一band按周生成空班次 | repeat/addDays：1935–1990、2009、2052；exists：1624–1631；unique：261；persist else：1376–1378；Store父锁：997–1009 |
| StoreShifts | manager读本店班次分页 | Page、存在性和manager权限：809–854 |
| StoreEmployees | manager读本店员工分页 | Page、同名员工的id排序兜底 |
| AssignEmployee | manager将本店员工编入班次 | unique(employee,shift)、exists查重后persist else；Shift行锁：261、997–1009、1376–1378 |
| ShiftHeadcount | manager看指定班次实际人数 | count输出Serial，空集0：1624–1631，不当并发锁：1679 |
| EmployeeRoster | manager按员工读编排关系 | Page，按关系id升序，不隐式JOIN日期 |

repeat仅在atomic command，limit为字面量8，符合1～100；序号0起，日期每周+7天；体内只有exists/validate/create/persist，没有find/return/嵌套循环/sum/avg/month（R:1846–1848、1990；V:83–84）。循环外return unit。两处先exists再建唯一行都显式persist else，父锁也不豁免（R:1378；V:75）。

### 1.2 全部声明

完整定义见reference.sir；软件StoreRoster / namespace example.staffing / Java21+SpringBoot+MyBatisPlus+MySQL+Maven+REST。

| 种类 | 完整成员清单 |
|---|---|
| enum | ShiftBand={OPENING,CLOSING} |
| User | id:Id自增；name:Name unique |
| AccessToken | id:Token UUID；owner:Ref<User>；expiresAt:Moment；credential subject owner expires expiresAt |
| Store | id:Id自增；name:Name；manager:Ref<User> |
| Employee | id:Id自增；store:Ref<Store>；name:Name（非unique，允许重名） |
| Shift | id:Id自增；store:Ref<Store>；day:Day；band:ShiftBand；unique(store,day,band) |
| Assignment | id:Id自增；employee:Ref<Employee>；shift:Ref<Shift>；unique(employee,shift) |
| views | StoreView=id/name；EmployeeView=id/store/name；ShiftView=id/store/day/band；AssignmentView=id/employee/shift；类型与实体字段相同 |
| inputs | PageQuery=page:PageNo/size:PageSize；StorePage=storeId:Id/page/size；WeeklyShifts=storeId:Id/firstDay:Day/band:ShiftBand/weeks:Quantity；AssignInput=shiftId:Id/employeeId:Id；ShiftRef=shiftId:Id；EmployeePage=employeeId:Id/page/size |
| errors | BadPage=400；Forbidden=403；EmployeeNotFound、ShiftNotFound、StoreNotFound=404；AlreadyAssigned、ShiftExists、TooManyWeeks、WrongStore=409 |

User是登录管理者；Employee是本店人事条目，不要求每位员工有账号，不把actor当employee。所有员工归属由种子固定，不考CRUD/调店，避免额外并发写路径破坏锁约定。

### 1.3 base的机械差异

仅删除GenerateWeeklyShifts、AssignEmployee、ShiftHeadcount三个完整能力，其余字节保持reference原样。base保留四个目录/列表能力、所有输入/错误/实体/视图/枚举，种子让现有列表有可观察行为。参考56→base50文件，少三个Controller/Service；这是静态缺接口负例，未运行base HTTP。

## 2. 完整MySQL DDL（仅规格内设计）

独立schema、InnoDB、utf8mb4_bin，Moment用DATETIME(6)、Day用DATE；无业务CHECK/触发器防止数据库替代候选的权限和批次上限。两组UNIQUE必须真实存在。R:86–113、261；SpringBootDeploymentPlanner.java:37–75、83–93规定列与联合唯一形状。以下未实际执行，也未package导出比对，下一阶段再对照落库。

```sql
-- TEST_FIXTURE_DDL: isolated owned schema, not a migration.
CREATE TABLE `user` (
  id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(100) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY roster_user_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE access_token (
  id CHAR(36) NOT NULL, owner_id BIGINT NOT NULL, expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (owner_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE store (
  id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(100) NOT NULL, manager_id BIGINT NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (manager_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE employee (
  id BIGINT NOT NULL AUTO_INCREMENT, store_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (store_id) REFERENCES store (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE shift (
  id BIGINT NOT NULL AUTO_INCREMENT, store_id BIGINT NOT NULL, day DATE NOT NULL,
  band VARCHAR(7) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY roster_shift_slot (store_id,day,band),
  FOREIGN KEY (store_id) REFERENCES store (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE assignment (
  id BIGINT NOT NULL AUTO_INCREMENT, employee_id BIGINT NOT NULL, shift_id BIGINT NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY roster_employee_slot (employee_id,shift_id),
  FOREIGN KEY (employee_id) REFERENCES employee (id), FOREIGN KEY (shift_id) REFERENCES shift (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

band两枚举名均7字符。表/列保留实体的snake_case物理名，不擅自重命名；索引名是夹具名，不要求候选代码依赖它。班次没有capacity、startsAt、endsAt，不能在判卷里暗加上限和区间排他。

## 3. 种子数据与请求记号

以下是合成夹具，不是真实凭据；JVM/JDBC及观察连接按UTC，Day只按日历日期处理。

```sql
INSERT INTO `user` (id,name) VALUES (1,'alice'),(2,'bob'),(3,'carol');
INSERT INTO access_token (id,owner_id,expires_at) VALUES
 ('50000000-0000-4000-8000-000000000001',1,'2099-01-01 00:00:00.000000'),
 ('50000000-0000-4000-8000-000000000002',2,'2099-01-01 00:00:00.000000'),
 ('50000000-0000-4000-8000-000000000003',3,'2099-01-01 00:00:00.000000'),
 ('50000000-0000-4000-8000-000000000099',1,'2000-01-01 00:00:00.000000');
INSERT INTO store (id,name,manager_id) VALUES (10,'North',1),(11,'South',2),(12,'Empty',1);
INSERT INTO employee (id,store_id,name) VALUES (101,10,'Amy'),(102,10,'Ben'),(103,10,'Ben'),(104,11,'Dana');
INSERT INTO shift (id,store_id,day,band) VALUES
 (201,10,'2026-01-05','OPENING'),
 (202,10,'2026-01-05','CLOSING'),
 (203,10,'2026-01-12','OPENING'),
 (204,11,'2026-01-05','OPENING'),
 (205,10,'2026-01-19','OPENING'),
 (206,10,'2026-01-21','CLOSING');
INSERT INTO assignment (id,employee_id,shift_id) VALUES
 (301,101,201),(302,102,201),(303,101,202),(304,104,204);
```

A(n)=`Bearer 50000000-0000-4000-8000-`加n的十二位十进制补零文本，n=1/2/3；E尾号99；anon无头；未知UUID=`99999999-9999-4999-8999-999999999999`，业务不存在id=999999。alice管10/12，bob管11，carol不管任何门店；员工不是Bearer主体。

种子自检：user=3、access_token=4、store=3、employee=4、shift=6、assignment=4；所有外键有效，两组联合唯一无重复，assignment两端的store相同；201人数2、202人数1、203/205/206人数0、204人数1。员工101编排[301,303]，102为[302]，103为空，104为[304]。设施相同的日期不代表冲突：201与202同日不同band，201与204不同店同日同band。

## 4. 接口合同

完整机器合同是 [contract.json](contract.json)，下表与其一致：

| 能力 | 方法与/api后路径 | 输入 | 成功响应 |
|---|---|---|---|
| ListStores | GET /stores | page、size | 200 Page<StoreView> |
| GenerateWeeklyShifts | POST /stores/{storeId}/weekly-shifts | firstDay、band、weeks | 201 空体 |
| StoreShifts | GET /stores/{storeId}/shifts | page、size | 200 Page<ShiftView> |
| StoreEmployees | GET /stores/{storeId}/employees | page、size | 200 Page<EmployeeView> |
| AssignEmployee | POST /shifts/{shiftId}/assignments | employeeId | 201 AssignmentView |
| ShiftHeadcount | GET /shifts/{shiftId}/headcount | 无 | 200 JSON整数 |
| EmployeeRoster | GET /employees/{employeeId}/roster | page、size | 200 Page<AssignmentView> |

只有ListStores匿名；路径字段以URL为准。所有引用响应是数字ID，名称为store/employee/shift，不带Id后缀、不嵌套。员工表的store_id与HTTP的store不同，不混用。

分页默认1/20、范围1…10000/1…100；超界BadPage，合法空页保留total。头部认证失败空体401；声明错误信封code/message/fields=[]；入口校验400 INVALID_REQUEST、fields项path/code/message。普通非Optional输入全部必填（R:402），weeks缺值不等于0；分页字段仅作分页时例外。缺失/null的firstDay/band/weeks/employeeId是NotNull，weeks<=0只固定code/path，不猜范围约束字符串。不可读日期/未知枚举的fields可能为空，§6只锁定400 INVALID_REQUEST；不虚构其path。

AssignEmployee判断顺序：ShiftNotFound→StoreNotFound（夹具外键完整时不会触发）→Forbidden→EmployeeNotFound→WrongStore→AlreadyAssigned。Generate先门店存在/授权，再超8周，再逐周查重；缺值校验发生在工作流前，业务优先级只在有效输入下比较。

## 5. 两种需求原文

自然语言全文在 [requirement-scratch.md](requirement-scratch.md)（S01–S13）和 [requirement-change.md](requirement-change.md)（C01–C08），以非空行顺序编号。出题时给对应完整原文、合同、§1.2/§2的数据结构；改写另给base。题面不提示循环、查重步骤或SQL锁写法，而要求可观察的周生成、整批回滚、唯一编排和人数查询。

### 5.1 改写题全文

```text
在给定软件上补回 GenerateWeeklyShifts、AssignEmployee、ShiftHeadcount，保留 ListStores、StoreShifts、StoreEmployees、EmployeeRoster 的现有行为、全部声明和接口合同。
ListStores 允许匿名访问，其余接口使用数据库中的真实 Authorization: Bearer 凭据识别用户，缺少、格式错误、未知或过期返回空体 401，只允许对应门店 manager 操作，不实现注册或登录。
GenerateWeeklyShifts 接收路径 storeId 及必填 firstDay、band、weeks，firstDay 是日期，band 为 OPENING 或 CLOSING，weeks 是至少 1 的整数，缺失、null 或非法值返回 400 INVALID_REQUEST；门店不存在返回 404 StoreNotFound，非 manager 返回 403 Forbidden，之后检查最多 8 周，超过返回 409 TooManyWeeks 且整批不写；成功空体 201，第零周为 firstDay，以后每周加 7 个日历日，band 不变，同一门店、日期、band 已存在则返回 409 ShiftExists 并撤销本批先前所有新建班次，两个不同 band 可在同一天存在，生成时不编排员工，不做资源预约或区间重叠检查。
AssignEmployee 按路径 shiftId 和必填 employeeId 新增员工编排关系，成功 201 AssignmentView；按班次不存在（404 ShiftNotFound）、非门店 manager（403 Forbidden）、员工不存在（404 EmployeeNotFound）、员工不在该店（409 WrongStore）、员工已在该班次（409 AlreadyAssigned）的顺序拒绝；同一员工同一班次并发编排恰好一次成功，其余 AlreadyAssigned，最终只有一行，不同本店员工并发编入同一班次全部成功，同一员工能参加同日两个不同班次，不设容量、工资和预约冲突规则。
ShiftHeadcount 返回班次全部编排关系条数，是 JSON 整数，无人是 0，班次不存在返回 404 ShiftNotFound，非该店 manager 返回 403 Forbidden，先判断存在再判断权限；新编排提交后统计立即可见。
同一门店相同三周班次计划并发提交恰好一批成功，其余 409 ShiftExists，只增加三张班次且不增加编排关系；所有拒绝不改变业务行，成功只产生本操作规定的变化，路径优先于同名请求体字段，路由、字段名、字段类型、错误信封及必填校验均按合同。
保留所有分页约定：page、size 可各自省略，默认 1、20，范围 1 到 10000、1 到 100，非法为 400 BadPage，响应 total、page、size、records，越界空页仍有正确 total；EmployeeRoster 按编排关系 id 升序而不是按班次日期排序。
只返回完整的软件描述文档，不要 Java、SQL 或解释。
```

### 5.2 从零写题全文

```text
ListStores 允许匿名访问，其余接口用数据库中真实的 Authorization: Bearer 访问令牌识别当前用户，缺少、格式错误、未知或过期的令牌均返回空体 401；用户、令牌、门店、门店 manager 和员工由夹具预置，不实现注册、登录或员工管理。
ListStores 按 name、id 都升序分页返回门店的 id、name；其他操作只能由对应门店的 manager 使用，非 manager 返回 403 Forbidden，不能靠请求体自称 manager。
GenerateWeeklyShifts 接收路径 storeId 及 firstDay、band、weeks，为该门店按周生成离散班次；firstDay 为日期，band 只能为 OPENING（开店班）或 CLOSING（闭店班），weeks 为至少 1 的整数，均必填，缺失、null 或非法值返回 400 INVALID_REQUEST；门店不存在返回 404 StoreNotFound；检查门店存在和权限后，一次最多生成 8 周，超过返回 409 TooManyWeeks 且整批不写，成功返回空体 201。
第零周的班次日期为 firstDay，之后逐周增加 7 个日历日，全部用同一 band，同一门店同一天同一 band 只能有一个班次，已有任意一周的班次返回 409 ShiftExists 且之前几周新建的也全部撤销；OPENING 与 CLOSING 是不同班次，允许同一天各一个；生成班次不指派员工，不预订资源，也不检查时间区间重叠。
StoreShifts 分页返回路径 storeId 对应门店的全部班次，按 day、id 都升序；StoreEmployees 分页返回该门店全部员工，按 name、id 都升序；两者均先检查门店存在（404 StoreNotFound），再检查 manager 权限。
AssignEmployee 接收路径 shiftId 和 employeeId，将该员工编入该班次，成功返回 201 和 AssignmentView；班次不存在返回 404 ShiftNotFound，非该班次门店 manager 返回 403 Forbidden，员工不存在返回 404 EmployeeNotFound，员工不属于该门店返回 409 WrongStore，同一员工已经在该班次返回 409 AlreadyAssigned，按这个顺序判断，成功只新建一条编排关系。
同一员工同一班次被并发编排时恰好一次成功，其余返回 409 AlreadyAssigned，最终只有一行；同一班次的不同本店员工并发编排全部成功；同一员工可以参加同一天的两个不同班次，班次不设容量、工资或预约冲突规则。
ShiftHeadcount 返回路径 shiftId 对应班次的编排关系条数，响应为不带引号的 JSON 整数，无人是 0，先检查班次存在（404 ShiftNotFound）再检查该门店 manager 权限；不能数成该员工的班次数，也不能把别店班次算进来。
EmployeeRoster 分页返回路径 employeeId 对应员工的编排关系，按编排关系 id 升序，不按班次日期排序；员工不存在返回 404 EmployeeNotFound，非该员工所属门店 manager 返回 403 Forbidden，没有编排时返回空页。
所有分页参数 page、size 可分别省略，默认 1、20，范围分别为 1 到 10000、1 到 100，非法返回 400 BadPage，响应恰好为 total、page、size、records，total 是全部匹配行数，越界页仍保留正确 total。
同一门店相同三周班次计划被并发提交时恰好一批成功，其他返回 ShiftExists，数据库只增加三张班次且不增加编排关系；任何一周的失败都不能留下半批班次。
全部接口的路由、请求字段、响应字段及其类型严格采用合同，路径字段覆盖请求体中的同名字段；所有拒绝不改变业务行，成功只产生该操作应有的行差异，声明错误返回合同的 code、message、fields，普通输入缺失或 null 的字段错误采用合同，不能返回凭据或 manager。
只返回完整的软件描述文档，不要 Java、SQL 或解释。
```

## 6. 判卷检查清单与逐句追溯（参考已由同 SHA CI 验收）

### 6.1 执行与完整比较

本节现由 `StaffingShiftRosterBusinessConformanceIT` 及 `Staffing*Checks` 实现，复用 BusinessSliceHarness；文件和验证状态见 [implementation.md](implementation.md)。每小节独立重放§3，同表按行顺序；并发每轮独立重置。无在途请求时按assignment→shift→employee→access_token→store→user删除，再按种子插入。使用external数据库凭据模式，不用local-fixed。

SV/EV/AV/TV=合同的ShiftView/EmployeeView/AssignmentView/StoreView：精确键集合、JSON类型及同一JDBC行全字段相等；Day按LocalDate比较，不能因时区移一天。PAGE(ids,total,page,size)同时检查200、精确四外层键、全部id顺序及逐项视图。INT(n)要求裸JSON整数，不能是字符串、对象或3.0。ERR校验声明信封、AUTH401检查空体。

UNCHANGED=独立观察连接按主键读六表全部列全部行后前比较，不能只读行数；串行每个拒绝和每个GET均逐次检查，每个成功写只允许对应新增行。不比较自增分配器空号，新id从响应取得或用JDBC差集识别。

### 6.2 身份、分页目录、权限与人数

| 检查 | 具体请求 | 期望与反例重点 | 原句 |
|---|---|---|---|
| B01 | anon GET /api/stores；size=2&page=1/2 | PAGE([12,10,11],3,1,20)；([12,10],3,1,2)；([11],3,2,2)，按名称不是id排序 | S01–02；C01–02 |
| B02 | 六认证入口各用无头/not-a-uuid/未知UUID/E，参数合法：Generate店12三周、StoreShifts/Employees店10、Assign班203员工103、Headcount201、Roster101 | 共24请求AUTH401，逐次六表UNCHANGED | S01/S12；C02/C06 |
| Q01 | A(1) GET /api/stores/10/shifts?page=1&size=2，page2/3/4；A(2)店11；A(1)店12 | [201,202]/[203,205]/[206]/[]，total5；[204]total1；[]total0 | S05/S10；C01/C07 |
| Q02 | A(1) GET /api/stores/10/employees?size=2&page=1/2 | [101,102]/[103]，total3；两个Ben按id兜底，不丢行；店12空页 | S05/S10；C01/C07 |
| Q03 | A(1) GET /api/employees/101/roster?size=1&page=1/2/3；员工102、103；A(2)员工104 | [301]/[303]/[]，total2；[302]total1、[]total0；[304]total1 | S09–10；C01/C07 |
| Q04 | 四个分页入口ListStores/StoreShifts/StoreEmployees/EmployeeRoster，各用有权限目标：page=0/-1/10001、size=0/-1/101；再分别省略两参数、只省page、只省size及合法上界 | 24个BadPage；默认1/20独立生效；page10000&size100空页保留total；GET均UNCHANGED | S10；C01/C07 |
| Q05 | A(1)Headcount班201/202/203/205/206；A(2)班204 | INT(2)/INT(1)/INT(0)/INT(0)/INT(0)；INT(1)，不能按employee统计 | S08；C05 |
| Q06 | A(2)读店10的shifts/employees、班201headcount、员工101roster；A(1)对相应999999根对象各一次 | 四次403 Forbidden；四次404（StoreNotFound×2、ShiftNotFound、EmployeeNotFound），存在性先于权限；UNCHANGED | S02/S05/S08–09；C01–02/C05 |

Q04有四种分页能力，不为“空店同一路径”虚增能力数。所有PAGE在每行都校验每个投影全部字段，而非只比较id。

### 6.3 按周生成与整批回滚

P={firstDay:"2026-02-02",band:"OPENING",weeks:3}。日期只按日历天+7，不能写成固定30天或使用自然月。

| 检查 | 具体操作 | 期望与六表差异 | 原句 |
|---|---|---|---|
| G01 | A(1) POST /api/stores/12/weekly-shifts P | 空体201，只增加3张store12/OPENING，day恰为2026-02-02、02-09、02-16；Assignment仍4行；StoreShifts读到同样三行 | S03–04；C03 |
| G02 | 相同请求再执行；然后同日期weeks3但band=CLOSING | 前者409 ShiftExists UNCHANGED；后者201再加3班、无编排；两个band可同日并存 | S04；C03 |
| G03 | 重置；store12 firstDay=2026-02-02,band=OPENING,weeks=8；另独立重置weeks=9及2147483647 | 8周201，完整日期列由java.time重算，末日2026-03-23；后两409 TooManyWeeks且UNCHANGED，不能只创建前8周 | S03；C03 |
| G04 晚失败 | 重置；店10 firstDay=2026-01-07,band=CLOSING,weeks=3，种子206恰在第三周2026-01-21 | 409 ShiftExists；前两周01-07/01-14不得留下行，六表完全UNCHANGED；证明非“先查第一周就拒绝”的假回滚 | S04/S11；C03/C06 |
| G05 | firstDay省略/null；band省略/null；weeks省略/null/0/-1；firstDay="not-a-date"；band="NIGHT"，均对店12 | 前八400 INVALID_REQUEST，缺值NotNull对应字段；后两只要求400 INVALID_REQUEST，不固定不可读输入fields；逐次UNCHANGED | S03/S12；C03/C06 |
| G06 | A(2)生成店12；A(1)生成999999；A(1)URL店12而body.storeId=10，P | 403 Forbidden、404 StoreNotFound；最后201只在店12生成，不改店10；所有操作不隐式新增Assignment | S02–04/S12；C03/C06 |
| G07 | 重置；店12firstDay=2028-02-23,band=OPENING,weeks=3 | 201，日期恰为2028-02-23、03-01、03-08，验证闰年跨月，不用28/30天估算；仍无Assignment | S04；C03 |

### 6.4 员工编排、查重与查询闭环

| 检查 | 具体操作 | 期望与六表差异 | 原句 |
|---|---|---|---|
| A01 | 重置；A(1) POST /api/shifts/203/assignments {employeeId:103} | 201 AV，新行employee103/shift203；只增加该一行；Headcount203=1，Roster103包含新行；不能把actor1当employee103 | S06/S08–09；C04–05 |
| A02 | 相同请求重放 | 409 AlreadyAssigned，UNCHANGED；人数仍1 | S06；C04 |
| A03 | 顺次编入employee101、102到203 | 都201，只加各自关系；203人数3；可同一班多人，无容量规则 | S07–08；C04–05 |
| A04 | 班999999/员工103；班203/员工999999；A(2)班203/员工999999；班203/员工104 | 依次ShiftNotFound404、EmployeeNotFound404、Forbidden403（权限先于员工存在）、WrongStore409；均UNCHANGED | S06；C04 |
| A05 | employeeId省略/null；合法体外加employee或manager字段 | 前两400 INVALID_REQUEST，employeeId/NotNull；额外字段400，不能伪造主体/归属 | S06/S12；C04/C06 |
| A06 | 重置；A(1)班202编employee102；随后Headcount201/202、Roster102 | 201，班202人数2，201仍2；员工102的关系按id为[302,新增id]；同日两band允许 | S07–09；C04–05/C01 |
| A07 | URL班203，body={shiftId:201,employeeId:103} | 201AV.shift=203，只加(103,203)，201不变，人数203=1 | S12；C04/C06 |
| A08 | 重置；夹具连接插入两条合法关系(id=900,employee101,shift206)、(id=901,employee101,shift203)；随后A(1) GET /api/employees/101/roster | 顺序完整为[301,303,900,901]，不是按shift或day排序；新增两行是夹具前置，HTTP读取UNCHANGED；人数206和203各1 | S09；C01/C07 |

A08先插晚日期班206、后插早日期班203，让“编排id顺序”和“班次日期顺序”真正不同；两条JDBC新增是夹具前置，不算候选实现了创建。

### 6.5 并发检查（真实HTTP/数据库屏障）

每轮独立重置。每个工作线程独立HTTP客户端，用ready/start两个latch同时放行；单请求30秒、整轮35秒。控制连接事务先锁本轮目标行；从本schema的performance_schema.data_lock_waits/data_locks/threads确认**全部N个不同运行时连接/事务**到达该目标的锁等待链（含间接链）后再释放；观察限时5秒，不能见一个等待就开始。权限不足NOT_RUN，提前失败/未形成竞争记失败并finally释放、收齐结果；不注入应用钩子。锁与READ_COMMITTED依据R:1003–1009。

| 检查 | 请求与目标锁 | HTTP判据 | 独立JDBC最终判据 | 原句 |
|---|---|---|---|---|
| X01 同员工同班 | 控制锁shift203；六个A(1)同时编入employee103 | 恰1个201 AV，5个409 AlreadyAssigned | 恰1条(103,203)，班203人数1，其他六表行不变；Roster103一致 | S07；C04 |
| X02 不同员工同班 | 控制锁shift203；三个A(1)同时各编入employee101/102/103 | 三个201 AV，不要求响应先后 | 三条不同employee关系，203人数3；全库仅这三新增行，证明不是把班次误设成唯一占用 | S07–08；C04–05 |
| X03 相同周计划 | 控制锁store12；六个A(1)同时提交P（三周OPENING） | 恰1个空体201，5个409 ShiftExists | 仅三班(12,02-02/09/16,OPENING)，无新Assignment；StoreShifts逐字段一致，无半批 | S11；C06 |

任何500、死锁、锁超时或请求超时均不等价于409或成功，不假设自动重试。重复编排的胜负以真实关系UNIQUE和父锁协议共同兜底；不能因有父锁就删除persist的else。

### 6.6 不变量、追溯与证据边界

I01：对每班次用JDBC全部assignment独立计数，与Headcount比较；不从201条数推导。I02：每关系employee.store=shift.store，unique(employee,shift)无重复。I03：unique(store,day,band)且周生成的完整日期/枚举集合精确匹配，不只是行数。I04：所有成功/拒绝的六表差异与投影精确符合合同。I05：生成不增编排；Assign不改已有班次/员工/身份。

逐句索引见 [traceability.md](traceability.md)。S13/C08为输出静态格式，不算HTTP业务项；readiness、schema/种子自检、进程、清理、报告是执行器前置/收尾，不附加教师业务需求。所有表项已有对应 IT 源码，现已在修复版同SHA CI执行：本题870项检查通过、0失败，含全部三轮并发；当前验收见 [implementation.md §7](implementation.md#7-修复版同-sha-ci-验收passed当前参考状态)。原band的OGNL失败保留于§5，不以轻量单测替代业务验收。

## 7. 风险、语言依据与发现

| 项 | 依据 | 本题处理/待验 |
|---|---|---|
| repeat范围/体内限制 | R:1846–1848、1935–1990；V:83–84 | limit8字面量，体外return；G03/G04分别测超限先拒与晚失败全回滚。 |
| 日期加法 | R:2009、2052；V:94 | 用Day按日历+7，不引入时区；G07验证闰年跨月。 |
| 显式行锁/隔离 | R:997–1009；V:68、91 | 周计划先锁Store；编排先锁Shift；两入口不交叉修改父行，不出现一条路径先锁Store再锁Shift、另一条反向。X01–03须观测实际等待。 |
| exists后的唯一冲突 | R:261、1376–1378；V:75 | Shift与Assignment都有真实联合唯一；两处persist else分别ShiftExists/AlreadyAssigned，atomic和锁不豁免。 |
| count是64位 | R:1631、1679；V:79 | Headcount输出Serial，不与32位字段做混宽运算，不把count当锁/自动维护的列。 |
| 普通输入必填与分页例外 | R:402、2041；V:39–40 | firstDay/band/weeks/employeeId缺失或null都是400，page/size仅作分页时默认；不造notNull语法或算术触发必填。 |
| Ref响应与snake_case | R:109、452；SpringBootModelLowerer.java:653–665、1942–1944 | store/employee/shift是数字主键；物理列*_id；不用嵌套视图冒充标量。 |
| HTTP/信封/状态 | R:595、897–918；V:61–62 | 含create（包括repeat体）为201，count查询200裸整数；401空体；首次IT验证不可读日期/枚举的错误形状。 |
| 排序发现 | R:1162只允许可比较字段，并未承诺普通Ref可排序；首轮检查SIR-TYPE-001 | 见S1；EmployeeRoster按id，不按引用/关联日期。 |
| DDL/种子尚未执行 | DeploymentPlanner.java:37–75、83–93；类型/枚举长度按本源码设计 | 下一阶段须对实际生成schema、DDL、seed、HTTP逐一验证；静态通过不是落库证据。 |

**发现S1（真实检查所得）：**普通find不能按Ref<Shift>排序；最初reference/base都在该处SEMANTIC失败。`each`对引用主键排序的规定（R:1846）不能直接套用普通find。本题自然选择“编排记录按id排序”，不以读取关联日期/伪造日期列补洞。如果负责人要按班次日期排序的员工日历，这是新需求/查询能力问题，不本轮硬写JOIN。

**发现S2（负责人边界裁决）：**周生成与booking机制确实相同。当前用“生成空班次+员工与班次多对多关系、无区间排他、同班多员工”的三项硬边界区分，负责人已确认接受为train。若要员工区间排他/每周直接派人，这一形状就太接近booking，应换题或留在development，不顺带扩展。

**已确认：**接受S2隔离结论、S1的关系id排序及本题不设容量/工资/跨店规则。指定结构全部可静态生成，无需工具链修改。规格文档此前已单独提交并推送。负责人现已另行授权 DDL/种子/IT 和两个 workflow 的接入，见 [implementation.md](implementation.md)；d31af6af的失败与修复过程保留；随后5fe4c833同SHA CI已通过，当前进入task定义/冻结。参考验收不代表已有教师合格答案，采样、候选判卷、导出与训练均未开始。
