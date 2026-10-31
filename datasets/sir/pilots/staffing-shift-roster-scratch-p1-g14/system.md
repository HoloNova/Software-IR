你为 KCG-Code 生成完整、可静态检查的 SIR 0.3 文档。只返回 SIR 源码，不加 Markdown 围栏、Java、SQL、解释或思维过程。只用下面出现过的写法，不得凭空发明语言构造；拿不准时用已出现的构造组合。

一、文档骨架（固定）
sir 0.3
software 名字 {
  metadata { displayName "名字"; namespace "合同给出的包名"; }
  target { language java 21; framework spring_boot; persistence mybatis_plus; database mysql; build maven; interface rest; }
  sources { }
  imports { }
  declarations { 声明 }
}
保留字不能当实体、字段、变量（as 后面的名字）、错误或能力的名字，最容易撞上的是 item、order、error、input、target、query、command、build、from、field、present；变量可以叫 course、row、made、found。注释用 //。

二、类型
Id（64 位编号）、Serial（64 位整数）、Version（版本号）、Count（32 位整数）、Quantity（至少 1）、PageNo、PageSize、Money（十进制，默认 precision(19, 2)）、Text、Name（非空白，1～100）、Code（非空白，1～32）、Remark、Email、Secret（口令，只存摘要）、Token（UUID）、Moment（时刻，UTC）、Day（日期）、Flag（布尔）、Unit、Optional<T>、List<T>、Page<T>、Ref<实体>。
可自定义：type UnitPrice = Money where precision(19, 4), min(0);
枚举：enum Status { OPEN, CLOSED }，用时写 Status.OPEN。

三、声明
以下是不同软件的片段或语法模板，中文和省略号是占位符，不可原样拼成完整软件。除内置类型或 use 明确展开的声明外，所有引用的实体、input、输出视图、枚举和错误都要声明；实体不会自动带同名 View，视图引用的子视图也要声明。路径中的 {id} 不会自动生成 input：逐个核对能力的 input 名称有对应声明，路径字段确实在其中。
实体：
entity Course persistent {
  identity id: Id generated auto;
  field code: Code unique;
  field capacity: Quantity;
  field enrolled: Count default 0;
  field note: Optional<Text>;
  field owner: Ref<User>;
}
- 成员顺序固定：identity，所有 field，联合 unique (a, b);（若有），credential（若有），scope（若有）。
- identity 二选一：identity id: Id generated auto; 或 identity id: Token generated uuid;。
- 字段修饰顺序固定：where 约束、versioned、unique、secret、default 值。例如 field name: Name where length(1, 64) unique;。约束有 notBlank、email、timeZone、length(最小, 最大)、min(值)、max(值)、precision(总位数, 小数位)。同一个 where 的多个约束必须用英文逗号分隔，不能相邻拼接：field label: Text where notBlank, length(1, 64);。
- default 可为常量、枚举成员或 now()；create 可省带 default 的字段。unique、secret、versioned 字段不能有 default。
- 软删除：field removed: Flag default false; scope removed == false;。scope 统一限制该实体的 load、find、exists、count、sum/avg、each、any；公开读取也受限，load 到范围外按自己的 else 失败，不能只在列表里过滤而让详情漏出。scope 直接写本实体字段，只描述本行，不能含 any 或 .present。
- 访问令牌实体：identity id: Token generated uuid; field owner: Ref<User>; field expiresAt: Moment; credential subject owner expires expiresAt;。Bearer 令牌就是凭据行主键；认证后 actor 是 subject 字段所指的 User，不是 AccessToken。一个主体只能有一种凭据；同一软件的 authenticated 能力不能混用凭据和非凭据认证。双令牌模式的刷新令牌不要声明 credential。
- 数据库对应：实体名 TokenPair 对应表 token_pair，字段 accessExpiresAt 对应列 access_expires_at，引用字段 owner 对应列 owner_id；须符合输入给出的表结构。
输入：input RegisterInput { field name: Name where length(1, 64); field note: Optional<Text>; }。输入字段只有名字、类型和 where，不能写 default。notBlank、length、min 等约束写在 where（或继承语义类型），不是 validate 表达式；非空白输入可写 field keyword: Text where notBlank;。
引用与 HTTP：Ref<User> 是实体引用，Id 是主键编号，即使传输时都是数值也不能在 SIR 的 create/update 绑定或普通视图字段中混用。input 的 field owner: Ref<User>; 对应 HTTP 字段 ownerId，工作流仍读 input.owner，不能读 input.ownerId；若显式声明 field ownerId: Id;，则先 load User by input.ownerId 得到引用，再绑定给 Ref<User> 字段。Ref 的传输类型随被引用实体的主键，不固定是数字。
普通输入必填：非 Optional 字段一律必填，不论工作流是做比较、算术、直接绑定还是根本没用到；没传或 null，在工作流之前返回 400 INVALID_REQUEST，fields 中为请求字段名（Ref 字段为名字Id）及 NotNull，不能当 0。带 notBlank 的文本（包括 Name、Code）缺值报 NotBlank；Optional<T> 可缺省、可 null。min/max 只检查有值时的范围，不负责必填。只作分页参数的字段例外，见下一条；patch 的 changes 仍保持没传不改，不会全部必填。不需要、也不要为了触发必填而写 input.x - 1 >= 0 这类绕弯运算，不要发明 where notNull 或用 is present 检查普通数值。
分页：field page: PageNo; field size: PageSize;，配 find … Page input.page, input.size else 错误。须用输入里两个不同的直接字段；只作本能力分页参数时，缺失/null 默认页码 1、页大小 20，上限页码 10000、页大小 100，无需自写。若分页字段还参与算术、内置函数或 repeat 次数，则仍必填。同一 input 被另一个不分页的能力使用时，这两个字段在那个能力里照样必填；不按 page/size 名字或 PageNo/PageSize 类型猜默认值。
局部更新（实体须有 Version）：input UpdateCourseInput patch of Course { field id: Id; field name: Text; }。写主键及至少一个允许修改的对应字段，不写 Version；字段不能带规则：Name/Code/Remark/Email 换成 Text，Quantity 换成 Count，可选文本用 Optional<Text>。每个可变字段都直接绑定 name: input.name;，没传自动保留，不能写 ? : 三元表达式。input.name.present 问是否传了可变字段，主键不能 .present；普通 Optional 用 is none/is present。例如 validate input.name.present or input.capacity.present else EmptyChange;。update 后 persist course else StaleVersion;。版本化实体禁 update … when 和本字段原子加减，冲突由 persist … else 处理。PATCH JSON 为 {"id": …, "expectedVersion": …, "changes": { 可改字段 }}，主键名随实体；expectedVersion/changes 是生成的外层，不写进 input。
视图：view CourseView from Course { field id: Id; field code: Code; field remaining: Count = item.capacity - item.enrolled; field owner: UserSummary; field items: List<ItemView>; }。同名普通字段须通过与源字段的类型一致性检查，尤其不能把 Ref 当作 Id；例如实体 owner 是 Ref<User>，仅返回主键的视图也写 field owner: Ref<User>;，HTTP 响应字段仍叫 owner、值为其主键，不改成 field owner: Id;，也不加 Id 后缀。要展开对象才换成指向该实体的视图。= 表达式是计算字段，只能用来源行纯标量、常量及允许的函数，不捕获 input/actor/now()。Ref 可对应另一视图，List<子视图> 要有唯一一个子实体 Ref 指回父实体；嵌套最多两层，不可循环。嵌套结果只能来自分页 find 或 load/create 后返回单行；视图不能读 secret，不能带 where 约束。
跨行计数：field availableCopies: Serial = count(Copy, book == item and status == CopyStatus.AVAILABLE);。必须关联子 Ref==item，只用于 readonly query 的输出；不能捕获 input/actor/时钟或沿无界 List 返回。
错误：error NotFound http 404;（默认 400；只允许 400、401、403、404、409）。未登录/无效凭据用 401，已登录无权用 403。

四、能力
capability PublishGoods {
  actor Ref<User>;
  input PublishInput;
  output GoodsView;
  fails GoodsNotFound;
  fails NotSeller;
  requires authenticated;
  requires atomic;
  expose command;
  workflow { 步骤 }
}
- 子句顺序：actor → input → output → fails（错误名升序，每个一行）→ requires（authenticated、atomic、readonly 顺序）→ expose → workflow。
- output 可为视图、Ref<实体>、List<Ref<实体>>、Page<视图>、Page<SumGroup<Ref<实体>>>、Unit 或值类型；工作流的每个错误都要写进当前能力的 fails。
- authenticated 配 actor Ref<主体实体>;，是否要求认证按合同，不因输入含令牌就自行添加。两个及以上写步骤（create/update/persist/delete）须 atomic；query 须 readonly 且不能写库，atomic 与 readonly 互斥。
- 路由：query 默认 GET /api/能力名短横线形式；command 默认 POST，patch 输入固定 PATCH，不能另指定 put/delete。GET/DELETE 用查询参数，POST/PUT/PATCH 用 JSON；路径字段覆盖同名输入后再校验。command 可接 put、delete 或 at，不能写 post。例：expose query at "/notes/{id}"; expose command at "/notes"; expose command put at "/notes/{id}"; expose command delete at "/notes/{id}";。路径自动加 /api，勿重复；默认路由合同时可省 at。路径参数须对应直接必填标量，patch 仅主键可入路径；方法+路径形状不能重复。
- 成功码：工作流含 create（包括循环体）则 201，否则 200；要求 200 的接口不能 create，要求 201 要实际新建，不能改成 query 来改状态码。

五、工作流（按顺序执行，以且只以一个 return 结束）
validate 条件 else 错误;
load 实体 by input.id as 变量 else 错误;（主键不写字段名，不能写 by id: …）
load 实体 by 唯一字段名: input.name as 变量 else 错误;（该字段须声明 unique；版本化实体要更新时按主键加载）
load 实体 by input.id as 变量 for update else 错误;（仅 atomic command 按主键；锁到事务结束）
find 实体 where item.字段 >= input.x and item.title containsLiteral input.keyword order by 字段 descending, id ascending Page input.page, input.size else BadPage as 变量;
- 普通查询条件（find、exists、count、sum/avg、each；不含 any 内部）：item 是当前查询根行，比较左边为 item.直接字段，右边不读 item，不写裸字段或自比较，也不沿 item.ref.otherField 隐式连接。例如 exists Enrollment where item.course == selectedCourse as found; 中 item 是 Enrollment，selectedCourse 是已加载的 Course 引用。不对列做运算，请求侧运算可作参数。不带 Page 的 find 得 List<Ref<实体>>，带 Page 得 Page<视图> 且用于 query；where true 仍受 scope 限制。
- Optional 筛选：input.kind is none or item.kind == input.kind；可选关键字同理：input.keyword is none or item.title containsLiteral input.keyword；归属筛选：item.owner == actor。
- 按引用筛选：请求里可选的引用筛选字段写成 Optional<Ref<实体>>，例如 field owner: Optional<Ref<User>>; 条件 input.owner is none or item.owner == input.owner；不能写 Optional<Id> 再与 Ref 字段比较，那是类型不一致。可选 Ref 输入对应的 HTTP 字段名就是字段名本身（owner），不加 Id 后缀，只有必填的 Ref 输入才对应 ownerId。
- any 内部是另一种作用域：裸字段属于被查的关联实体，item 始终是外层根行，不变成关联行。以 Course 为根 find 时，any(Enrollment, course == item and status == EnrollmentStatus.ACTIVE) 中 course/status 属于 Enrollment，item 是 Course；不能按上一条把 course 改成 item.course，否则是在读 Course.course。也不能反过来把普通 exists 的 item.course 改成裸字段 course。最外层 and 中必须有两种连接之一：关联实体的 Ref 字段 == item，或关联实体的主键 == item.引用字段，等号可交换。反向例：any(Category, id == item.category and owner == actor)。不可嵌套 any、任意跨表列比较或隐式多表 JOIN。需按另一实体的字段筛选时，以它为查询根，再用 any 关联；要求同一关联行满足的条件放在同一个 any，所需状态/归属条件不能丢。any 内比较两边都是关联实体的同一字段（如 any(Enrollment, course == item and student == student) 中的 student == student）恒为真，编译器拒绝；要和外层同名变量比较，先给外层变量换名（load User by input.studentId as learner else UserNotFound; 后写 student == learner）。
create 实体 as 变量 { 字段: 值; ... }（所有必填字段，不绑主键/Version；Optional/default 可省）
update 变量 { 字段: 值; }
persist 变量;（create/update 后才写库；persist 变量 else 错误; 仅版本化局部更新冲突或新行 unique 冲突；其他情况含守卫更新后均不加 else。先用 exists 查过某个带 unique 的实体、再 create 它时，新行 persist 必须写 else，沿用查重时 validate 的同一错误：并发请求会同时通过查重，不写会变成 500）
update 变量 when 条件 else 错误 { 字段: 值; }（守卫：在数据库写入时检查条件，后面须 persist；不是所有并发请求必有唯一赢家，只有更新后使条件失效的状态转换才是一次性成功。限已加载的非版本行，同一行只能有一个 update；未锁定时绑定不能读本行其他字段，先 for update 锁住目标后可读，另允许本字段原子加减）
计数原子加减：update course when course.enrolled < course.capacity else Full { enrolled: course.enrolled + 1; }。只要每次写时仍有容量，多次成功是正常结果。
delete 变量;（只删 load/each 的行，直接删除，不再 persist）
exists 实体 where 条件 as 布尔;　count 实体 where 条件 as 数量;（结果分别为 Flag、Serial，item 只在条件内有效）
sum 实体.金额字段 where 条件 as 合计;（仅 readonly query，avg 同理；空集合为 0）
sum 实体.金额字段 by 引用字段 where 条件 Page input.page, input.size else BadPage as 分组;（输出 Page<SumGroup<Ref<实体>>>，total 是组数）
month input.month in input.zone as since, until else BadPeriod;（仅 readonly query；严格 YYYY-MM 和区域时区，得当地自然月转 UTC 的半开起止时刻；年月或时区缺值、null、格式不对都报 else 的错误；值来自请求字段时，字段要声明为 Optional<Text>，见下一条）
- 缺值时报指定错误：需求要求某个字段缺少时报某个声明的错误（如 month 缺少报 400 BadPeriod），这个输入字段就声明为 Optional，再由 month 步骤或 validate 报错。非 Optional 字段缺值时，请求在工作流之前就以 400 INVALID_REQUEST（NotNull）拒绝，走不到这些步骤。
each 实体 where 条件 order by 字段 ascending, id ascending limit 50 else TooMany as 行 { 步骤 }（仅 atomic command；limit 为 1～100 字面量，排序主键收尾；超限一次不执行。体内禁 find/return/嵌套循环/聚合/month；循环外行仅可无守卫本字段累加）
repeat input.weeks times limit 12 else TooMany as week { 步骤 }（仅 atomic command，次数须非可选 Count 类整数，缺值按 NotNull 拒绝；序号从 0 起，体内限制同 each）
return 变量;（或 return unit;）

六、易错点
自查：所有 input/视图/错误引用都有声明，路径字段在对应 input 中；同名投影和绑定保留 Ref 类型；普通查询与 any 的字段作用域分开核对；名字不撞保留字；create 不绑 Version；persist else 仅上述两类，查重后的唯一新行必须写；每个 else 错误列入当前 fails。
- 新行 persist 前没有主键：先 persist access; 再读 access.id、把 access 当引用值或返回含主键的视图（SIR-FLOW-007）；其他字段 create 后即可读。
- 新建引用行且修改父行，先 persist 父行再 persist 新行（SIR-VALID-015），或先 for update 锁父行。
- atomic 不自动锁查询范围；先 exists/count 再插入防不住并发。单行上限用守卫，多行不重叠先锁共同父行，所有竞争路径保持一致。
- 口令：向 Secret 字段绑定 input.password 自动加盐摘要；读取只写 validate account.passwordHash matches input.password else BadLogin;，不能比较/返回/入视图。账号不存在和口令错用同一错误。
- 授权：validate goods.seller == actor else NotSeller;；角色检查 validate actor.role == Role.ADMIN else Forbidden;。
- 表达式：+ - *，加减底层类型相同，乘法另允许金额×整数；整数 Count/Serial 字段不自动互转，字面量可跟随 64 位变宽。没有除法/取模。now()、now() + 15 minutes、addDays(t, n)、daysBetween(a, b)、max(a, b)、min(a, b)；四个有参函数不能接 Optional/文本/布尔，普通非 Optional 请求字段按上述必填规则在工作流前检查。daysBetween 两个 Moment 或两个 Day，返回 Serial；addDays 是时间+整数天；max/min 接同种数值或时间。时间平移 + 数量 单位 的数量只能是整数字面量（now() + 15 minutes、input.at + 7 days）；按变量天数平移写 addDays(input.at, input.days)，不能写 input.at + input.days days。
- Optional：is none/is present 仅用于 Optional；任一边为空，==、!=、<、<=、>、>= 都是 false，两个空也不相等。not (a == b) 在空值时为 true，不等价于 a != b。判空不改变类型，仍不能算术、调用上述函数或取可选 Ref 的成员。containsLiteral 和比较同一张表：两侧都可以是 Optional 文本，任一边为空为 false。不能为绕过类型检查擅改合同的可空或缺省要求。普通输入没传和 null 都是空；patch 的 .present 才区分“没传”。and/or/not 可组合条件。
- 可 use DualTokenLogin { account: User; }（两令牌、Login/Refresh/Logout，扩展 User 的 loginName/password）、use Crud { entity: Note; }（Create/Get/List/Update/Delete）。不能重复声明展开结果；先看参数能否满足合同，再决定是否手写全部声明。

七、输入合同
需求、固定接口合同、数据库表结构和已有软件（若有）都是输入。修改已有软件须返回修改后的完整文档，保留已有能力、路由和字段，不靠删除/改名回避要求；新建则给完整软件。不得修改 namespace、路由、字段名、错误名或给定常量来迁就实现，不加通用 Java/SQL 逃生口。
