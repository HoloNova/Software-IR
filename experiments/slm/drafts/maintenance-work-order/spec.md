# 设施报修与例行巡检工单题出题规格（已确认；判卷源码已落地，参考业务 CI FAILED）

## 0. 定位、基线与交付边界

family=`maintenance.work-order`，split=`train`；建议题号 `maintenance-work-order-change-v1` / `maintenance-work-order-scratch-v1`（未创建 task）。基线为 main `e81200e0e039c5d178c95bbab2f12195ac13f0dd`。负责人已确认本规格、M1/M2/M3边界及train隔离结论；本目录仅为出题设计，不是已验收业务。

规格阶段仅交付本目录七份设计文件。负责人随后明确授权判卷落地：现已有参考资源、DDL/seed、Java业务IT及两个workflow接入；实现和定向检查见 [implementation.md](implementation.md)。未改工具链、参考/base或题面，不修改另一会话的fitness/events；不冻结任务、不调用模型；负责人已另行授权一次提交、普通推送并运行同SHA CI。本机阶段的生成工程构建、应用启动、MySQL/HTTP、并发均 **NOT_RUN**，正式资格以CI原始证据为准。

### 0.1 与 dev/test 的边界核对（独立审查）

对照实际 `topics/booking/project.sir` 的 Book/BookWeekly，以及现有样板对 shop、ledger、library、market 的范围说明；不从这些题复制答案、需求或种子。

| 对照 | 业务、实体与数据形状差异 | 结论 |
|---|---|---|
| development：booking | booking 是 Room → 带 booker、startsAt/endsAt 的 Booking，按半开区间排他，重复预订每周检查占用。这里是设施损坏的 WorkOrder，以及独立的 InspectionOrder 到期任务；巡检只有 dueAt，没有起止区间、booker、容量或占用；同一设施同一时刻可有任意多张巡检单，重复计划也允许。周期由 intervalDays 决定，不固定按周；任务生成后再执行批量关闭，而非取消预约释放时段。 | **共享有界循环与时间平移机制，不是预约换名。** |
| development：shop | 没有商品、购物车、订单明细、价格、库存、支付、退库；批量操作把已有巡检任务的 closed 置真，不逐项扣资源、汇总金额或新建订单。设施父锁只串行化两个批处理入口。 | `each` 机制重用，业务/关系形状不同。 |
| test：finance.money / ledger | 无账目、分类、预算、金额、自然月或月报。title、可空指派关系、处理备注、removed/version 是运维工作记录；dueAt 是单一任务期限。 | 不同。 |
| test：library | 无书目、副本、Loan、预约队列、借还、罚金和个人借阅上限；关闭任务不释放可借资源。 | 不同。 |
| test：market | 无卖家、买家、商品、成交、订单状态与金额快照。工单 manager 权限不代表交易双方。 | 不同。 |

与 booking 的机制重合已明确暴露，不把“有不同名字”当隔离证据。若负责人仍将所有周期任务视为 booking 同源，替换建议为 **quality.inspection-batch：按样本数量生成质检记录**（无日期平移），保留 repeat/each 覆盖；这是备选，不擅自替换当前授权题目。

### 0.2 已读材料与设计选择

样板为 `drafts/events-registration/`、`fitness-check-in/`、`blog/` 的规格、合同、题面、base、追溯；blog 目录没有 reference.sir，其正式参考入口由样板规格指向 `valid/blog.sir`，不能虚构本目录存在该文件。覆盖缺口见 `drafts/new-problem-plan.md` §1–§3、§6；教师规则为 `prompts/sir-0.3-general-v9.md`（下文 V）。下文 R 指 `docs/SIR_LANGUAGE_REFERENCE.md`，行号固定对应上述基线。

**必须拆分两种工单。**报修记录 WorkOrder 用版本号保护人工编辑；例行巡检 InspectionOrder 是非版本化的批处理任务。R:1374 要求版本化实体的每次更新都使用该实体的局部更新输入；R:404–448 的输入携带一个主键/expectedVersion，不是“每行各有一个版本”的列表。因此不能让一份普通批关闭请求直接修改多张版本化报修单。这里没有把版本号移除或绕过冲突检查；两个业务流程本来就有不同的输入和生命周期。负责人已确认M1的范围拆分；不宣称“报修单也能批量关闭”。

### 0.3 规格阶段实际静态检查（保留原始证据）

环境：Windows 11、Temurin Java 21.0.11、Maven 3.9.14，18 个逻辑核、23.53 GiB 内存，执行前可用约 2.92 GiB。未启动应用/数据库。按负责人要求仅编译 CLI 依赖链，去掉 JAVA_TOOL_OPTIONS，MAVEN_OPTS=-Xmx2g，无 -T、无测试，42 秒、退出 0。Windows 没有 env/systemd-run，实际等价命令：

```powershell
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
$env:MAVEN_OPTS='-Xmx2g'
mvn -B -o -q -pl kcg-cli -am compile
```

对应要求的 POSIX 命令为 `env -u JAVA_TOOL_OPTIONS MAVEN_OPTS=-Xmx2g mvn -B -o -q -pl kcg-cli -am compile`。Maven settings 的 localRepository 是 `D:/maven-repo`，不是默认 ~/.m2；检查器调用 `checker_command(Path('D:/maven-repo'))`，得到 java、-Xmx256m、-XX:MaxMetaspaceSize=128m、-XX:ActiveProcessorCount=2、九模块 target/classes 加 ANTLR 4.13.2 的类路径、`io.kcg.cli.KcgCli check`。经 stdin 逐行传 JSONL `{id,sir,stopAfter:"GENERATION"}`，子进程环境再次移除 JAVA_TOOL_OPTIONS。退出 0，stderr 为空。以下为真实 stdout 原行：

```jsonl
{"id":"maintenance-work-order-reference","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":55,"digest":"3944fc366883738fcadc6ce5fec995a9747fd36b4479f3fc6a1a4b8ab2cbf612","sourceSha256":"bfe04bc9b91f970f4de151d54e686a38b68872b71a7da535dd4df362dd39be32"}
{"id":"maintenance-work-order-base","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":47,"digest":"859082d49123a8a9cb652b513612ce0eb259568a2f7078e0f32f6f02d7757308","sourceSha256":"3ffa5ff966f5159e792230e53637acfae18a268608207d311cd0a99c863d19f7"}
```

| 文件 | stage | 诊断码 | 生成文件数 |
|---|---|---|---:|
| reference.sir | GENERATION | 无（含无 WARNING） | 55 |
| base.sir | GENERATION | 无（含无 WARNING） | 47 |

这是内存生成，不是生成 Java 可编译、HTTP 或真库业务通过的证据。

## 1. 范围、全部声明与结构覆盖

### 1.1 八个能力

| 能力 | 职责 | 结构覆盖与语言依据 |
|---|---|---|
| ListFacilities | 匿名设施目录，分页 | view、find/order/Page：R:450–472、1115–1162 |
| OpenWorkOrder | 登录用户提交未指派报修单 | create/persist、默认值、初始版本：R:311、1348–1374；凭据 R:372–389 |
| EditWorkOrder | 设施 manager 修改标题/指派人/处理备注/软删除 | versioned、patch of、.present、persist else：R:257、404–448、1372–1374；scope R:340–370 |
| GetWorkOrder | manager 读未删除报修单 | load、权限、scope：R:989–995、809–854、340–370 |
| ListWorkOrders | manager 按可选指派人筛选未删除单 | Optional<Ref<User>>；`input.assignee is none or …`：R:106–113、1222–1250、2043–2050；Page |
| GenerateInspections | 按请求周期生成巡检单 | repeat：R:1935–1990；addDays、整数乘法：R:2006、2009、2020、2052；父行锁 R:997–1009 |
| CloseOverdueInspections | 一批关闭 0～50 张超期巡检单 | each、确定排序、超限先拒绝：R:1801–1848；父行锁 R:1933 |
| ListInspections | manager 分页读巡检单含已关闭行 | Page；观察生成和关闭后的真实数据 |

repeat/each 都只在 atomic command 使用，上限分别为字面量 24/50，均在 1～100 内；序号从 0 开始。体内只有 create/persist 或 update/persist，不含 find、return、嵌套循环、sum/avg/month；return unit 在体外。`count` 是循环允许的独立步骤（R:1848），但本题没用，不把“聚合限制”误读成禁止 count。

### 1.2 全部声明

完整逐字段定义以同目录 reference.sir 为准，外壳为 FacilityMaintenance / example.maintenance / Java 21 + Spring Boot + MyBatis-Plus + MySQL + Maven + REST。

| 种类 | 完整清单 |
|---|---|
| User | id:Id 自增；name:Name unique |
| AccessToken | id:Token UUID；owner:Ref<User>；expiresAt:Moment；credential subject owner expires expiresAt |
| Facility | id:Id 自增；name:Name；manager:Ref<User> |
| WorkOrder | id:Id 自增；facility:Ref<Facility>；reporter:Ref<User>；title:Name；assignee:Optional<Ref<User>>；note:Optional<Remark>；removed:Flag default false；version:Serial versioned；scope removed==false |
| InspectionOrder | id:Id 自增；facility:Ref<Facility>；title:Name；dueAt:Moment；closed:Flag default false |
| 视图 | FacilityView=id/name；WorkOrderView=id/facility/reporter/title/assignee/note/removed/version；InspectionView=id/facility/title/dueAt/closed；同名字段类型与源相同 |
| 普通输入 | PageQuery=page:PageNo/size:PageSize；NewWorkOrder=facilityId:Id/title:Name；WorkOrderRef=id:Id；WorkOrderQuery=facilityId:Id/assignee:Optional<Ref<User>>/page/size；InspectionPlan=facilityId:Id/title:Name/firstDueAt:Moment/intervalDays:Count(min1,max30)/occurrences:Quantity；CloseInput=facilityId:Id/cutoff:Moment；FacilityPage=facilityId:Id/page/size |
| 局部更新输入 | WorkOrderPatch patch of WorkOrder=id:Id/title:Text/assignee:Optional<Ref<User>>/note:Optional<Text>/removed:Flag；不声明 version；规则继承目标字段 |
| 错误 | BadPage、EmptyChange=400；Forbidden=403；FacilityNotFound、WorkOrderNotFound=404；StaleVersion、TooManyInspections、TooManyOccurrences=409 |

非可选输入缺失/null 由入口返回 INVALID_REQUEST，不在工作流里绕弯检查。只有 page/size 在仅作分页参数时可独立默认（R:402、2041；V:39–41）。报修单字段允许清空的仅 assignee/note；removed=null 的未决规则见 M3。

### 1.3 base 的机械差异

从 reference.sir 仅删 EditWorkOrder、ListWorkOrders、GenerateInspections、CloseOverdueInspections 四个完整能力块，其他字节不动；所有 input/error/view/entity 保留。base 有设施目录、新建与详情、巡检列表四个可用入口；缺四个对应 Controller/Service（55→47 文件）。这是静态负例，未启动 base 冒称 HTTP 失败。

不考：注册登录、设施/用户管理、报修结案状态机、通知/图片、巡检执行记录、恢复软删除、跨设施指派策略、未知用户指派的业务错误、分页一致性压测。

## 2. 完整 MySQL DDL（仅规格内夹具设计）

使用独立 schema，不写 CREATE DATABASE/USE；InnoDB、utf8mb4_bin、DATETIME(6)。无业务 CHECK，避免数据库掩盖循环上限/权限缺失。SQL 类型依据 R:86–113、253；实体/列命名与可选引用的特殊行为见 §7。外键实际建立，assignee 的列名就是 `assignee`，不是 assignee_id。

```sql
-- TEST_FIXTURE_DDL: isolated owned schema, not a migration.
CREATE TABLE `user` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY maintenance_user_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE access_token (
  id CHAR(36) NOT NULL, owner_id BIGINT NOT NULL, expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (owner_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE facility (
  id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(100) NOT NULL, manager_id BIGINT NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (manager_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE work_order (
  id BIGINT NOT NULL AUTO_INCREMENT, facility_id BIGINT NOT NULL, reporter_id BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL, assignee BIGINT NULL, note VARCHAR(500) NULL,
  removed TINYINT(1) NOT NULL DEFAULT 0, version BIGINT NOT NULL,
  PRIMARY KEY (id), FOREIGN KEY (facility_id) REFERENCES facility (id),
  FOREIGN KEY (reporter_id) REFERENCES `user` (id), FOREIGN KEY (assignee) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE inspection_order (
  id BIGINT NOT NULL AUTO_INCREMENT, facility_id BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL, due_at DATETIME(6) NOT NULL, closed TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id), FOREIGN KEY (facility_id) REFERENCES facility (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

version 不给数据库 DEFAULT：参考在 create 时初始化为 0；不让 seed/DDL 代替候选的初始值行为。DDL 尚未执行，也未调用 package 导出；下一阶段须与生成部署 schema 对照后再落文件，不能声称已真库核对。

## 3. 种子数据与请求记号

以下全是合成夹具凭据，不是机器上的真实秘密。JVM/JDBC/观察连接用 UTC。有效期固定 2099，过期固定 2000，不等待时钟超时。

```sql
INSERT INTO `user` (id,name) VALUES (1,'alice'),(2,'bob'),(3,'carol'),(4,'dave');
INSERT INTO access_token (id,owner_id,expires_at) VALUES
 ('40000000-0000-4000-8000-000000000001',1,'2099-01-01 00:00:00.000000'),
 ('40000000-0000-4000-8000-000000000002',2,'2099-01-01 00:00:00.000000'),
 ('40000000-0000-4000-8000-000000000003',3,'2099-01-01 00:00:00.000000'),
 ('40000000-0000-4000-8000-000000000004',4,'2099-01-01 00:00:00.000000'),
 ('40000000-0000-4000-8000-000000000099',1,'2000-01-01 00:00:00.000000');
INSERT INTO facility (id,name,manager_id) VALUES (10,'Boiler',1),(11,'Lift',2),(12,'Empty',1);
INSERT INTO work_order (id,facility_id,reporter_id,title,assignee,note,removed,version) VALUES
 (101,10,4,'Leak',NULL,NULL,0,0),
 (102,10,4,'Valve',3,'checked',0,2),
 (103,10,2,'Archived',3,'old',1,5),
 (104,11,1,'Button',2,NULL,0,0),
 (105,10,4,'Race',3,'race',0,0),
 (106,10,4,'Gauge',2,NULL,0,0);
INSERT INTO inspection_order (id,facility_id,title,due_at,closed) VALUES
 (201,10,'Inspect A','2000-01-01 00:00:00.000000',0),
 (202,10,'Inspect B','2000-01-01 00:00:00.000000',0),
 (203,10,'At cutoff','2000-01-02 00:00:00.000000',0),
 (204,10,'Already done','2000-01-01 00:00:00.000000',1),
 (205,11,'Other site','2000-01-01 00:00:00.000000',0),
 (206,10,'Future','2099-01-01 00:00:00.000000',0);
```

A(n) 是 `Bearer 40000000-0000-4000-8000-` 加 n 的十二位十进制补零文本（n=1…4）；E 是尾号 99；anon 无头；未知 UUID=`99999999-9999-4999-8999-999999999999`；不存在的业务 id=999999。alice 管 10/12，bob 管 11，dave 只是报修人，不能因为是 reporter 就获得管理读取权限。

种子自检：user=4、access_token=5、facility=3、work_order=6、inspection_order=6；外键有效；scope 内报修 5 张，设施10活跃单 [101,102,105,106]；assignee=3 得 [102,105]，2 得 [106]。设施10巡检排序 [201,202,204,203,206]；cutoff=`2000-01-02T00:00:00Z` 只匹配 [201,202]。先核对种子，不把夹具失败判为候选失败。

## 4. 接口合同

权威机器合同是 [contract.json](contract.json)，响应键、数值类型、请求字段、排序、错误、limit、排除项均显式列入。摘要：

| 能力 | 方法与 /api 后路径 | 成功体/状态 | 输入 |
|---|---|---|---|
| ListFacilities | GET /facilities | Page<FacilityView> / 200 | page、size |
| OpenWorkOrder | POST /work-orders | WorkOrderView / 201 | facilityId、title |
| EditWorkOrder | PATCH /work-orders/{id} | WorkOrderView / 200 | expectedVersion、changes；外层 id 可带但 URL 优先 |
| GetWorkOrder | GET /work-orders/{id} | WorkOrderView / 200 | 路径 id |
| ListWorkOrders | GET /facilities/{facilityId}/work-orders | Page<WorkOrderView> / 200 | assignee、page、size |
| GenerateInspections | POST /facilities/{facilityId}/inspection-series | 空体 / 201 | title、firstDueAt、intervalDays、occurrences |
| CloseOverdueInspections | POST /facilities/{facilityId}/close-overdue-inspections | 空体 / 200 | cutoff |
| ListInspections | GET /facilities/{facilityId}/inspections | Page<InspectionView> / 200 | page、size |

只有 ListFacilities 匿名；其余 Bearer。设施级接口按存在→权限→循环/分页判断。编辑按普通信封校验→EmptyChange→WorkOrderNotFound→FacilityNotFound→Forbidden→合并字段校验→StaleVersion 判断；只在合法 changes/已有设施下断言业务错误优先级，不制造坏输入与鉴权错误的未定义组合。

编辑示例：`PATCH /api/work-orders/102`，体 `{"expectedVersion":2,"changes":{"assignee":null,"note":"repaired"}}`。assignee 的 HTTP 名不带 Id；响应的 facility/reporter/assignee 是数字 ID（后者可 null），不是嵌套对象。声明错误信封精确为 code/message/fields=[]；入口校验是 INVALID_REQUEST，fields 项有 path/code/message；401 空体。不固定本地化校验消息。普通新建输入的 Name 缺失/null/空白为 NotBlank，普通必填数值/时刻缺失/null 为 NotNull。PATCH 合并候选的字段路径是 changes.title / changes.note，模板错误码分别使用小写 notBlank / length；外层 expectedVersion 缺值仍是 NotNull。依据 WorkflowRenderer.java:1075–1082、ValidationSupportRenderer.java:63–89，不能将普通 DTO 的 NotBlank/Size 原样套到 PATCH。范围错误只固定状态/code/path，不猜测首次 IT 尚未验证的其他约束字符串。

分页固定 1/20、上限10000/100；path 覆盖同名 body；不把数据库列名当 JSON 名。未知 body 字段拒绝的目标行为另见 §7，首次 IT 再给运行证据。

## 5. 两种需求原文

自然语言题面为 [requirement-scratch.md](requirement-scratch.md)（S01–S14）和 [requirement-change.md](requirement-change.md)（C01–C09），编号按非空行顺序，不写入题面；不含语言实现语法。出题时提供相应全文、contract.json、§1.2 声明/§2 数据结构；改写题另给 base.sir。不会只给摘要让教师猜业务规则。

scratch 覆盖身份、目录、新建、详情、局部编辑/版本竞争、软删除、可选筛选、周期巡检、批量关闭、巡检列表/串行化、分页、信封、输出。change 补四个能力，保留其余四项；失败后不靠改合同、改种子或删除保留能力迁就答案。

### 5.1 改写题全文

```text
在给定软件上补回 EditWorkOrder、ListWorkOrders、GenerateInspections、CloseOverdueInspections，保留 ListFacilities、OpenWorkOrder、GetWorkOrder、ListInspections 的行为、全部声明和接口合同。
ListFacilities 允许匿名访问，其余接口用数据库中真实的 Authorization: Bearer 凭据识别用户，缺少、格式错误、未知或过期的令牌均返回空体 401，不实现注册或登录。
EditWorkOrder 只允许该设施的 manager 编辑未删除的报修单，成功返回 200 和更新后的 WorkOrderView；请求外层为 id、必填 expectedVersion、changes，路径 id 优先，changes 只允许 title、assignee、note、removed，至少给出一项，否则返回 400 EmptyChange；没给的字段保留，assignee、note 显式 null 清空，assignee 有值是已有用户编号，title 仍是 1 到 100 字符非空白文本，note 最长 500，removed 给布尔值，不允许改 facility、reporter、version。
报修单不存在或已删除返回 404 WorkOrderNotFound，非 manager 返回 403 Forbidden，版本不一致返回 409 StaleVersion，按此顺序判断（空 changes 先返回 EmptyChange）；成功 version 加一，包括同值编辑；同一版本被并发编辑恰好一次成功，其余 StaleVersion，不覆盖赢家。
提交 removed=true 软删除报修单，保留整行，该次响应含 removed=true，后续详情、列表和再次编辑都不可见，也不能恢复，不连带删除设施或巡检单；ListWorkOrders 仅设施 manager 可用，设施不存在返回 FacilityNotFound，非 manager 返回 Forbidden，按 id 升序分页列未删除行，可按 assignee 筛选，省略时不限制且包括未指派行，不存在的用户编号返回空页。
GenerateInspections 仅设施 manager 可用，设施不存在返回 404 FacilityNotFound，非 manager 返回 403 Forbidden；title、firstDueAt、intervalDays、occurrences 都必填，title 规则同报修标题，intervalDays 为 1 到 30 的整数，occurrences 至少 1，缺失、null 或范围非法返回 400 INVALID_REQUEST；一次最多 24 张，超出返回 409 TooManyOccurrences 且整批不写；第零张 dueAt=firstDueAt，随后每张增加 intervalDays 个 24 小时，同设施、同标题、closed=false，成功空体 201，重复请求允许另建同日期、标题的巡检单。
CloseOverdueInspections 仅设施 manager 可用，设施不存在返回 404 FacilityNotFound，非 manager 返回 403 Forbidden；必填 cutoff 缺失或 null 返回 400 INVALID_REQUEST，只将该设施未关闭且 dueAt 严格早于 cutoff 的巡检单按 id 升序关闭，最多 50 张，51 张或更多返回 409 TooManyInspections 且一张也不改，空集合成功空体 200；其他行和报修单不变；同一设施的生成与关闭并发时等价于两个完整批次串行执行，不允许半批交错。
分页 page、size 可分别省略，默认 1、20，范围 1 到 10000、1 到 100，非法为 400 BadPage，返回 total、page、size、records，越界空页保留正确 total；所有路由、请求名、字段类型及响应采用合同，路径字段优先，拒绝不改变业务行，成功只改变本操作应改变的行，错误信封和普通输入必填错误采用合同。
只返回完整的软件描述文档，不要 Java、SQL 或解释。
```

### 5.2 从零写题全文

```text
ListFacilities 允许匿名访问；其余接口用 Authorization: Bearer 访问令牌识别当前用户，缺少、格式错误、未知或过期的令牌均返回空体 401，用户和令牌由夹具预置，不实现注册或登录。
ListFacilities 按 name、id 都升序分页返回设施的 id、name；设施和各自的 manager 由夹具预置。
OpenWorkOrder 接收 facilityId 和 title，为当前用户新建一张报修单，成功返回 201 和 WorkOrderView；facility 不存在返回 404 FacilityNotFound；title 必须是 1 到 100 字符的非空白文本，缺失、null、空串、纯空白、过长均返回 400 INVALID_REQUEST；facilityId 缺失或 null 也返回 400 INVALID_REQUEST；新行 reporter 为当前用户，assignee、note 为空，removed 为 false，version 为 0。
GetWorkOrder 按 id 返回未删除报修单的 WorkOrderView，报修单不存在或已删除返回 404 WorkOrderNotFound，只有该设施的 manager 能读取，否则返回 403 Forbidden；先判断报修单存在，再判断权限。
EditWorkOrder 只允许该设施的 manager 编辑未删除的报修单，成功返回 200 和更新后的 WorkOrderView；请求的外层字段为 id、expectedVersion、changes，id 以路径为准，expectedVersion 是必填版本号，changes 只允许 title、assignee、note、removed，至少提供一项，否则返回 400 EmptyChange；没有提交的字段保持原值，assignee、note 显式提交 null 则清空，assignee 有值时为夹具中已有用户的编号，title 有值仍需满足原规则，note 最长 500 字符，removed 提交布尔值；不能修改 facility、reporter、version。
EditWorkOrder 的 id 不存在或已删除返回 404 WorkOrderNotFound，非 manager 返回 403 Forbidden，已通过权限检查但 expectedVersion 与当前版本不等返回 409 StaleVersion；成功时 version 只加一，即使提交的值与旧值相等也加一；同一版本被并发编辑恰好一次成功，其余返回 StaleVersion 且不能覆盖赢家。
EditWorkOrder 提交 removed=true 就是软删除，保留数据库中的整行；该次成功响应含 removed=true，之后详情、列表、再次编辑都看不到该行，不能通过再次编辑恢复，也不连带删除该设施或巡检单。
ListWorkOrders 只允许路径 facilityId 对应设施的 manager 使用，设施不存在返回 404 FacilityNotFound，非 manager 返回 403 Forbidden；按 id 升序分页列未删除的报修单，可用 assignee 筛选，省略则不限制（包括未指派行），提供不存在的用户编号则返回空页，不是 404。
GenerateInspections 只允许路径 facilityId 对应设施的 manager 使用，设施不存在返回 404 FacilityNotFound，非 manager 返回 403 Forbidden；接收 title、firstDueAt、intervalDays、occurrences，title 规则同报修标题，firstDueAt 是 UTC 时刻，intervalDays 是 1 到 30 的整数，occurrences 是至少 1 的整数，字段均必填，缺失、null 或违反范围返回 400 INVALID_REQUEST；最多一次生成 24 张例行巡检单，超过返回 409 TooManyOccurrences 且整批不写；第零张的 dueAt 为 firstDueAt，之后每张增加 intervalDays 个完整的 24 小时，保存同一设施和标题且 closed=false，成功返回空体 201；重复提交是新的独立巡检计划，允许生成相同日期、标题的行，不做预约占用或去重。
CloseOverdueInspections 只允许路径 facilityId 对应设施的 manager 使用，设施不存在返回 404 FacilityNotFound，非 manager 返回 403 Forbidden；必填 cutoff 是 UTC 时刻，缺失或 null 返回 400 INVALID_REQUEST；只把该设施 closed=false 且 dueAt 严格早于 cutoff 的巡检单改为 closed=true，按 id 升序处理，一次最多 50 张，匹配 51 张或更多返回 409 TooManyInspections 且一张也不改；没有匹配行时也成功，成功返回空体 200；已关闭、恰好等于 cutoff、晚于 cutoff、其他设施的行和所有报修单都不改变。
ListInspections 只允许路径 facilityId 对应设施的 manager 使用，设施不存在返回 404 FacilityNotFound，非 manager 返回 403 Forbidden，按 dueAt、id 都升序分页返回该设施的全部巡检单（包括已关闭的）；同一设施的巡检生成与批量关闭并发时必须等价于先完整执行其中一个，再完整执行另一个，不允许半批交错。
所有分页的 page、size 可分别省略，默认 1、20，有效范围分别为 1 到 10000、1 到 100，超出范围返回 400 BadPage；返回 total、page、size、records，total 是全部匹配行数，越界空页仍有正确 total。
路由、请求名、响应字段和字段类型严格按接口合同；引用响应是数字编号，未指派的 assignee 和空 note 返回 null，不能泄露 manager 或凭据；路径字段覆盖请求体的同名字段，所有拒绝均不改变业务行，成功只产生该操作规定的行差异，声明错误为合同中的 code、message、fields，普通输入缺值的字段错误按合同给出。
只返回完整的软件描述文档，不要 Java、SQL 或解释。
```

## 6. 判卷检查清单与逐句追溯（源码已落地，参考业务 CI FAILED）

### 6.1 执行与比较规则

本节现由 `MaintenanceWorkOrderBusinessConformanceIT` 及 `Maintenance*Checks` 实现，复用 BusinessSliceHarness；落地文件和验证状态见 [implementation.md](implementation.md)。各小节重放 §3；同表按顺序执行；并发轮次独立重置。先删 inspection_order、work_order、access_token，再 facility、user，仅在无在途请求时重放。应用用 external 凭据模式，不用 local-fixed。新 ID 从响应取得，不猜自增下一个数。

WV/IV/FV：分别按合同精确比较 WorkOrderView/InspectionView/FacilityView 的键集合、JSON 类型和 JDBC 同行全部值；空 assignee/note 必须有键且为 null。Moment 转 UTC 比较，非字符串比对。PAGE(ids,total,page,size) 同时断言200、外层恰好total/page/size/records、完整id顺序及每项投影。ERR(status,code) 校验精确声明错误；AUTH401 校验空体401。UNCHANGED=独立连接按主键读取五表全部行、全部列，逐值相等，不是仅比较行数。所有 GET/拒绝逐次 UNCHANGED；所有成功写也做全库差异，仅允许本项列出的变化。AUTO_INCREMENT 空号不作为业务行变化。

### 6.2 身份、目录、新建与详情

| 检查 | 具体操作 | 期望与数据库差异 | 原句 |
|---|---|---|---|
| B01 | anon GET /api/facilities；size=2；page=2&size=2 | PAGE([10,12,11],3,1,20)；([10,12],3,1,2)；([11],3,2,2)，name 升序 | S01–02；C01–02 |
| B02 | 七个认证入口各用无头、not-a-uuid、未知UUID、E；参数用本表/下表的合法值（Get/Edit=102，设施=10，Open=合法标题，Edit用版本2和note，Generate用1次，Close用标准cutoff） | 共28请求，每次AUTH401及UNCHANGED | S01/S13；C02/C08 |
| W01 | A(4) POST /api/work-orders {facilityId:10,title:"Pipe"}，再A(2)同请求title="Pipe B" | 各201 WV，只新增一行，reporter分别4/2，assignee/note=null、removed=false、version=0；manager不是reporter | S03/S13；C01 |
| W02 | A(1)新建，title依次省略/null/空串/三空格/101字符；另facilityId省略/null；合法title设施999999 | 前五400 INVALID_REQUEST title；前四含NotBlank；facilityId缺值含NotNull；未知设施404 FacilityNotFound；逐次UNCHANGED | S03；C01 |
| W03 | title为100个ASCII字符新建；合法体额外加reporter=2、version=8各一次 | 前者201原样存；后两者400，不能伪造系统字段，UNCHANGED | S03/S13；C01/C08 |
| D01 | A(1) GET102；A(4) GET102；A(1) GET103和999999 | 200完整WV（version2）；403 Forbidden；两次404 WorkOrderNotFound；不因reporter身份放行 | S04/S07；C01/C05 |

### 6.3 PATCH 与软删除

每次有效更新以后从响应取得新版本；除表内顺序外不复用其他小节状态。

| 检查 | 具体操作 | 期望与数据库差异 | 原句 |
|---|---|---|---|
| P01 | A(1) PATCH102 {expectedVersion:2,changes:{note:"fixed"}} | 200 WV，version3；仅note/version变，title/assignee等保留；GET102一致 | S05–06；C03–04 |
| P02 | PATCH102 version3，changes={assignee:null,note:null} | 200，version4，两列NULL；其余保留；随后GET逐字段一致，不能只响应清空而数据库没清空 | S05；C03 |
| P03 | PATCH102 version4，changes={assignee:2,title:"Renamed"}；随后同值title再PATCH用新版本 | 依次version5/6；assignee=2，不是字符串；第二次即使同值仍增版 | S05–06；C03–04 |
| P04 | 当前行用旧版本2改note；A(2)用旧版本2改note；有效版本但changes={}；999999带非空changes | 依次409 StaleVersion、403 Forbidden、400 EmptyChange、404 WorkOrderNotFound；逐次UNCHANGED | S05–06；C03–04 |
| P05 | 有效版本，title=null/空白/101字符，note=501字符；expectedVersion省略/null | 各400 INVALID_REQUEST；title空/null检查changes.title/notBlank，过长title或note检查changes.title或changes.note/length，外层版本缺值检查expectedVersion/NotNull；不增version、不写库 | S05/S13；C03/C08 |
| P06 | PATCH102 URL，body.id=101，有效版本，changes={note:"path wins"} | 只改102的note/version，101不变；200WV.id=102 | S13；C03/C08 |
| P07 | 对101合法PATCH changes={removed:true} | 200 WV removed=true/version1；数据库整行保留，只removed/version变；GET101、再编辑101均404；ListWorkOrders不再含101，巡检及设施不变 | S07；C05 |
| P08 | 有效版本changes.note=""与500字符各一次；请求changes加入facility或reporter或version各一次 | 空串/500合法，原样保留；额外字段各400且UNCHANGED | S05/S13；C03/C08 |

按负责人已确认的 §7 边界，不对 assignee=999999 和 removed=null 发起评分；如需增加相应错误处理须另提需求，不把潜在500冒充已处理的404/400。

### 6.4 可选筛选与分页

| 检查 | 具体操作 | 期望 | 原句 |
|---|---|---|---|
| L01 | 重置；A(1) GET /api/facilities/10/work-orders；assignee=3；assignee=2；assignee=999999 | PAGE([101,102,105,106],4,1,20)；([102,105],2,1,20)；([106],1,1,20)；([],0,1,20)；未给条件时包含101 NULL，103始终排除 | S08；C05 |
| L02 | 上一路size=2,page=1/2/3；仅page=2；仅size=1；page=10000&size=100 | [101,102]/[105,106]/[]，total4；([],4,2,20)；([101],4,1,1)；([],4,10000,100) | S12；C08 |
| L03 | A(2)读设施10列表；A(1)读999999；A(1)读设施12 | 403 Forbidden；404 FacilityNotFound；PAGE([],0,1,20) | S08；C05 |
| L04 | 四个分页场景（三条路由：ListFacilities、ListWorkOrders、ListInspections，以及设施12的空报修页）：合法权限参数下page=0/-1/10001，size=0/-1/101各一次 | 24次400 BadPage，逐次UNCHANGED；所有非空入口另测page/size分别省略与合法上界，total从JDBC独立重算 | S12；C01/C08 |
| L05 | A(1)设施10巡检分页size=2的page1/2/3/4；A(2)设施11；A(1)设施12 | [201,202]/[204,203]/[206]/[]，total5；[205]total1；空页total0 | S11–12；C01/C08 |

### 6.5 周期生成、批量关闭与边界

| 检查 | 具体操作 | 期望与数据库差异 | 原句 |
|---|---|---|---|
| R01 | A(1)设施12生成 title="Routine",firstDueAt="2000-01-01T06:00:00Z",intervalDays=3,occurrences=3 | 空体201；只新增3巡检，dueAt为1/1、1/4、1/7的06:00Z，closed=false；列表/JDBC完整一致，无报修行变化 | S09/S11；C06 |
| R02 | 同体重复一次；另设施12生成occurrences=24,intervalDays=30,title="Boundary",firstDueAt同上 | 都201；分别再加3和24，允许同日重复；24张的序号为0…23，最后一张为first+690天；按JDBC和java.time重算，不只数行数 | S09；C06 |
| R03 | 重置；occurrences=25、2147483647（intervalDays=30）；其余合法 | 两次409 TooManyOccurrences且UNCHANGED；极大次数在体前拒绝，不先计算庞大日期乘积 | S09；C06 |
| R04 | occurrences省略/null/0/-1；intervalDays省略/null/0/31；firstDueAt省略/null；title省略/null/空白/101字符 | 每次400 INVALID_REQUEST，对缺数值/时刻断言NotNull、标题缺值NotBlank；无写入 | S09/S13；C06/C08 |
| R05 | A(2)生成设施10；A(1)生成999999；A(2)关闭设施10；A(1)关闭999999 | 403/404/403/404，对应Forbidden/FacilityNotFound；UNCHANGED | S09–10；C06–07 |
| E01 | 重置；A(1)设施10，cutoff="2000-01-02T00:00:00Z"关闭；相同请求再执行 | 两次空体200；首次仅201/202.closed由0→1；第二次UNCHANGED；203恰等于cutoff不改，204已闭不改，205其他设施不改，206未来不改 | S10；C07 |
| E02 | 设施12为空关闭；cutoff缺失/null | 空集合200 UNCHANGED；缺值各400 INVALID_REQUEST cutoff/NotNull | S10；C07 |
| E03 | 独立重置，夹具在设施12插入n张巡检，id=1000+i、title="limit-"+i、dueAt="2000-01-01T00:00:00Z"、closed=0，i=1…n；n依次1/50/51，每次独立重置后调用标准cutoff关闭 | n=1/50：空体200且恰好n行closed=true；n=51：409 TooManyInspections、五表完全UNCHANGED；不能只关前50张 | S10；C07 |
| E04 | E01后设施10巡检列表、Get102、报修列表 | 巡检仍5行，closed只有应变行改变；报修完整值、版本及scope行为不受批关闭影响 | S10–11/S13；C01/C07–08 |

### 6.6 并发与可观察屏障

独立 schema、真实 MySQL/HTTP，不以线程内mock或顺序循环充数。每轮独立重置；每个客户端独立连接，用 ready/start latch 同时放行；请求超时30秒、整轮35秒。控制连接关闭autocommit，先持目标行的 FOR UPDATE 锁，通过本schema的 performance_schema.data_lock_waits/data_locks/threads 验证本轮所有请求的不同连接/事务均到达该锁等待链，再释放。观察5秒，不具权限记 NOT_RUN，有请求提前失败/未到屏障不能算通过；finally释放控制锁并收齐响应。记录ready/start/end与等待图，不注入应用钩子。

| 检查 | 操作与控制锁 | HTTP及最终数据库判据 | 原句 |
|---|---|---|---|
| X01 | 控制锁work_order105；六个A(1)同时PATCH105，expectedVersion=0，note分别race-1…race-6 | 恰1个200 WV.version=1，5个409 StaleVersion；只105.note/version改变，最终note为赢家提交值；GET105一致。不允许6次依次覆盖。 | S06；C04 |
| X02 | 控制锁facility10；六个A(1)同时标准cutoff批关闭 | 六个空体200（空集合不是冲突）；最终仅201/202.closed=true，其他五表值不变；不得误要求“唯一赢家”。 | S10–11；C07 |
| X03 | 控制锁facility10；同时发两请求：生成firstDueAt=2000-01-01T00:00Z、intervalDays=1、occurrences=3；关闭cutoff=2000-01-04T00:00Z | 生成201、关闭200；原201/202/203全关闭。三张新行要么全关闭（生成先），要么全未关闭（关闭先），不允许混合；仅这三行与原应变closed列变化，报修/其他设施不变。 | S11；C07 |

500、死锁、锁超时、请求超时都不是409或成功，不假设服务端重试。失败者的无副作用以整轮最终差异证明，不在其他线程成功写入时强求瞬时全库不变。

### 6.7 逐句追溯与不变量

完整对应见 [traceability.md](traceability.md)。I01：每次响应投影和完整行相符；I02：scope外报修仍在数据库但详情/列表/编辑全不可见；I03：每个成功编辑版本恰加1、拒绝不增；I04：生成序列的设施/标题/逐日间隔完全一致；I05：关闭集合严格等于独立JDBC读行后计算的谓词集合，不能仅用成功条数推导。所有GET后再做UNCHANGED。

S14/C09输出格式由静态门检查，不算业务HTTP断言。构建、readiness、种子自检、清理、报告是夹具前置/收尾，不是教师要实现的业务能力。以上已由同SHA CI真实执行，本题597项检查通过、2失败；P04错误优先级冲突见 [implementation.md §5](implementation.md)。20项轻量单测通过不抵消业务失败。

## 7. 依赖生成器的风险、依据与发现

| 项 | 已知依据（本基线） | 裁决/待验证 |
|---|---|---|
| versioned与PATCH | R:257、404–448、1372–1374；V:41；SpringBootWritePolicy.java:23–25 初始0/递增1；WorkflowRenderer.java:835–853 条件更新及返回新版本 | X01必须真实运行，静态通过不能证明无丢更新。 |
| 可选引用命名 | SpringBootModelLowerer.java:653–665、1942–1944 只对直接Ref加Id/_id，Optional<Ref>不加；DeploymentPlanner.java:43–52解包Optional后建立外键 | HTTP与物理列均为assignee；不是assigneeId/assignee_id。DDL按实际目标设计；未执行package，不冒称导出比对。 |
| 清空是否真写NULL | R:111、404–448、2043；版本更新专用Mapper（非普通updateById） | P02既查响应又查JDBC，禁止“返回null但库没清空”。 |
| scope | R:340–370；V:34 | P07同时覆盖load、分页和编辑；没有额外“includeRemoved”逃生入口。 |
| 循环、原子性 | R:1803、1846–1848、1933、1941、1990；V:83–84 | 超限先拒；体外return；父锁在生成/关闭两个入口都取得；E03/X03真库待验。 |
| 行锁隔离 | R:1003、1007–1009 | 显式锁入口READ_COMMITTED；父锁不必persist，不推断readonly查询也加锁。 |
| 普通输入必填 | R:402、2041；V:39–40 | 不能把occurrences缺省当0；范围校验不替代NotNull；分页独立默认。 |
| HTTP/错误/未知字段 | R:595、897–918；V:61–62；ApplicationConfigRenderer.java:25配置拒未知JSON字段；DtoRenderer.java:45–141维护PATCH存在性 | 创建含循环201，其余200；401空体；首次IT实测错误fields，不凭静态结果声称HTTP通过。 |
| 分页具体数字 | SpringBootQueryPolicy.java:21；R:402只规定例外而未列所有数字 | 合同显式冻结1/20/10000/100；三条分页路由及空集合场景均验证。 |

**发现 M1（范围已确认）：**版本化单行编辑与多行批关闭不能在同一报修实体上直接组合（§0.2）。当前按真实业务分为“人工报修记录”和“例行巡检任务”，5实体/8能力，结构自然且全部静态通过。负责人已接受此拆分；若以后要求所有工单统一版本化且可批关闭，需另行评审语言缺口，本轮不造旁路。

**发现 M2（可选指派对象存在性）：**Optional<Ref<User>> 可以直接赋值/比较，但R:2043不允许判空后取其成员，没有分支/类型收窄来表达“有值时按主键load用户并映射404”。本版指派域明确限已有用户或null；未知用户指派未承诺业务错误，真实外键仍存在，可能成为数据库失败。负责人已确认按此题域出题；不通过换成Optional<Id>、弱化外键或改工具链凑答案。

**发现 M3（既有未决规则）：**项目状态§7列出“PATCH显式null写非可选目标”待设计；因此removed只提交布尔值，本版不评分removed=null。title=null已有非空白目标规则，P05仍检验（PATCH模板的字段错误码为小写notBlank）。若要统一null错误，另立项，不在规格里虚称已解决。

负责人已确认结构覆盖、M1/M2/M3及dev/test隔离；其中未知用户指派和removed=null的评分排除项保留，不等于相应语言缺口已修复。规格文档此前已单独提交并推送。负责人现已另行授权 DDL/种子/业务IT 和两个 workflow 的接入，落地状态见 [implementation.md](implementation.md)；判卷实现提交d31af6af未改工具链，其同SHA CI业务失败；两处根因修复随后另获本地修复及提交授权，未推送或运行新CI，尚无四门资格，task冻结仍未办理。
