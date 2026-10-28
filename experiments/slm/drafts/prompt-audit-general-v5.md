# SIR 0.3 通用规则手册 v5 核对

## 范围与结论

对象为 `experiments/slm/prompts/sir-0.3-general-v5.md`（下称 P，行号按原文件）；语言依据为 `docs/SIR_LANGUAGE_REFERENCE.md`（L）。代码和样例基线为 `b38fa2fa0dafe667d15eccf3ab86a1af56cdafda`，随后文档提交未改变这些来源。本次只读源码和现存样例，不运行 Maven、编译器或模型 API，不把“看起来合法”冒充新执行的通过结果。

按一句中的独立写法、限制、顺序要求拆为 **105 项**：正确 65 项、错误 1 项、不完整 35 项、找不到依据 4 项。“不完整”表示关键适用边界未讲，不等于例子必错；“找不到依据”中若为任务/提示策略，会明确区分，不拿语言源码否定任务合同。

最明确的错误是 P:72 把一般守卫说成唯一赢家；主要缺口是算术输入必填、Optional 空比较、关联查询作用域、认证主体、循环与嵌套视图的边界。没有发现手册发明一种编译器完全没有的语句构造；但多处组合例/参数示意在 valid 中没有原样版本，不能标成已有完整通过样例。

### 文件缩写（后文“V:1759”等均指这里的完整路径和行号）

| 缩写 | 文件 |
| --- | --- |
| P | experiments/slm/prompts/sir-0.3-general-v5.md |
| L | docs/SIR_LANGUAGE_REFERENCE.md |
| G | sir-parser/src/main/antlr4/io/kcg/sir/internal/Sir.g4 |
| V | sir-semantic/src/main/java/io/kcg/sir/semantic/internal/ValidatePass.java |
| T | sir-semantic/src/main/java/io/kcg/sir/semantic/internal/TypePass.java |
| R | sir-semantic/src/main/java/io/kcg/sir/semantic/internal/ResolvePass.java |
| Y | sir-semantic/src/main/java/io/kcg/sir/semantic/feature/SemanticTypes.java |
| Z | sir-semantic/src/main/java/io/kcg/sir/semantic/internal/SemanticTypeDeclarations.java |
| M | sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/SpringBootModelLowerer.java |
| I | sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/SpringBootInputValidator.java |
| Q | sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/profile/SpringBootQueryPolicy.java |
| W | sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/WorkflowRenderer.java |
| C | sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/ControllerRenderer.java |
| D | sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/DtoRenderer.java |
| A | sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/ArithmeticInputs.java |
| E | sir-semantic/src/main/java/io/kcg/sir/semantic/internal/ComputedViewRules.java |
| K | sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/RelatedCountValidation.java |
| F | sir-semantic/src/main/java/io/kcg/sir/semantic/feature/Features.java |
| B | sir-semantic/src/main/resources/io/kcg/sir/semantic/feature/crud.sir |
| H | sir-semantic/src/main/resources/io/kcg/sir/semantic/feature/dual-token-account.sir |
| N | sir-semantic/src/main/java/io/kcg/sir/semantic/internal/NormalizePass.java |
| J | sir-semantic/src/main/java/io/kcg/sir/semantic/model/NormalizedError.java |
| U | sir-semantic/src/main/java/io/kcg/sir/semantic/api/EntityScopes.java |
| X | sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/ExpressionRenderer.java |
| O | sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/SecretDigestRenderer.java |
| DP | sir-semantic/src/main/java/io/kcg/sir/semantic/feature/DecimalPrecision.java |
| SP | sir-parser/src/main/java/io/kcg/sir/internal/DefaultSirParser.java |
| S | sir-toolchain-application/src/test/resources/valid/（样例文件名均相对此目录） |

## 一、逐条规则核对

### 骨架、名字与类型

| 编号 | 手册行 | 拆分规则 | 判断 | 语言参考 / 编译器依据 | 正确说法或边界 |
| --- | --- | --- | --- | --- | --- |
| R001 | P:1 | 只返回完整 SIR，不夹带 Markdown/Java/SQL | 正确 | L:63；G:5–8 | 这是源文件输入形式；说明性提示不能当 SIR 声明。 |
| R002 | P:1 | 只能使用本手册出现过的构造 | 找不到依据 | L:599–694、2205；G:55–68 | 属提示策略，不是语言限制；语言还支持 extend、自定义 feature、多文件等手册未教的构造。 |
| R003 | P:3–11 | sir/software 内 metadata→target→sources→imports→declarations 固定 | 正确 | L:63；G:5–8、34–51；SP:81–92 | 中文“名字/声明”是占位符，不是可原样编译的标识符。 |
| R004 | P:7 | Java 21/Spring Boot/MyBatis-Plus/MySQL/Maven/REST 固定目标 | 正确 | L:63–69；I:158–175 | 是当前 Spring Boot lowering 的支持组合，不代表 Java 代码可嵌入。 |
| R005 | P:12 | item/order/error/input/target/query/command/build/from/field/present 等不能作名字 | 正确 | L:71–82；G:538–610、659–661 | 这些都是保留 token；course/row/made/found 是普通 IDENT。 |
| R006 | P:12 | 注释用 // | 正确 | L:65–69；G:663–669 | // 合法；编译器还支持块注释，手册未说“只允许 //”。 |
| R007 | P:15 | Id、Serial、Version 是 64 位；Token 是 UUID | 正确 | L:88–112；Y:59–67 | Version 还自动带 versioned 标记，不只是普通整数。 |
| R008 | P:15 | Count 为 32 位；Quantity 至少 1；PageNo/PageSize 为分页整数 | 正确 | L:88–112；Y:62–66 | 分页默认/上限由使用它的分页步骤决定，不是这些类型自带 min/max。 |
| R009 | P:15 | Money 默认 precision(19,2) | 正确 | L:178；Y:78–80；DP:12–13 | 不隐含非负；需要非负要另写 min。 |
| R010 | P:15 | Name/Code 非空白且有长度；Remark/Email/Secret/Moment/Day/Flag 等 | 正确 | L:88–112；Y:68–77 | Name/Code 的 notBlank、length 与 Secret 的摘要标记来自语义类型。 |
| R011 | P:15 | Unit、Optional、List、Page、Ref 可用 | 不完整 | L:115、728、1956；G:407–415；R:729–757；T:878–896 | 包装类型不是任意位置任意嵌套：实体用 Ref，Page 的输出元素限 view 或 SumGroup；Optional 有独立空值语义。 |
| R012 | P:16 | type UnitPrice = Money where precision(19,4), min(0) | 正确 | L:181、194–215；G:95–97；Z:27–31、99–114 | 自定义类型继承底层类型/规则，同名规则替换而非叠加；具体例子合法但 valid 中参数不同。 |
| R013 | P:17 | enum 声明、Status.OPEN 取值 | 正确 | L:219–229；G:120–124；R:1205–1213 | 枚举名、成员名都须存在，不能随意互换不同枚举。 |

### 实体、输入、分页与局部更新

| 编号 | 手册行 | 拆分规则 | 判断 | 语言参考 / 编译器依据 | 正确说法或边界 |
| --- | --- | --- | --- | --- | --- |
| R014 | P:21–28 | entity persistent，先 identity 再字段 | 正确 | L:233–240；G:126–143 | 完整实体须恰有一个主键；示例中的 User 需另有声明。 |
| R015 | P:29 | identity→field→联合 unique→credential→scope 成员顺序 | 正确 | L:240、261；G:126–133 | 可以有多条联合 unique；顺序不是格式偏好。 |
| R016 | P:30 | Id generated auto / Token generated uuid 两种主键例子 | 正确 | L:233–251；R:382–397 | 规范例子合法；实际按底层 Int64/UUID 检查，主键名字也不必叫 id。 |
| R017 | P:31 | where→versioned→unique→secret→default 的修饰顺序 | 正确 | L:240；G:165–167 | 还有可选 @id 在最后；手册未要求必须用 @id，省略不影响普通业务。 |
| R018 | P:31 | notBlank/email/timeZone/length/min/max/precision 约束可用 | 不完整 | L:253、178；V:2372–2461 | 文本/数字/十进制适用类型与参数个数不同；不允许重复，min≤max，precision 需 p≥1 且 0≤s≤p。 |
| R019 | P:32 | default 可为常量、枚举、now()，create 可省该字段 | 正确 | L:311；G:207–212；V:2166–2205 | 显式绑定覆盖 default；Optional 也可省略。 |
| R020 | P:32 | default 的适用范围 | 不完整 | L:311；V:998–1027；T:429–442 | unique、secret、versioned 字段不能有 default；默认值须匹配类型及精度，不是每个字段都能套 default。 |
| R021 | P:33 | removed 默认 false 加 scope 实现软删除可见性 | 不完整 | L:340–350；V:974–995；U:62–68、86–144、164–172 | scope 定义“存在”，作用于 load/find/exists/count/sum/any；只给 List 写筛选不等效，scope 本身不能再用 any/count。 |
| R022 | P:34 | Token 主键、owner Ref、expiresAt Moment、credential 子句 | 正确 | L:372–389；G:183–188；T:392–402 | 令牌就是凭据行主键；超时不认证，以 owner 指向的实体为主体。 |
| R023 | P:34 | 凭据认证的 actor、全软件一致性 | 不完整 | L:389、769；V:913–961 | 一个主体只能一种 credential；authenticated 的 actor 应是 subject 指向的实体，凭据方式不能和无凭据方式混用。 |
| R024 | P:34 | 双令牌模式刷新令牌不声明 credential | 正确 | L:2114–2129；F:103–115 | 这是双令牌职责分离，不是按实体名字含 Refresh 就禁语法。 |
| R025 | P:35 | TokenPair/accessExpiresAt/owner 的 SQL 名称映射 | 正确 | L未明说命名算法；M:109、559–561、657、1944–1962 | 表名和普通列为 snake_case；Ref 列加 _id。固定 DDL 是否满足还须对题目合同核对。 |
| R026 | P:36 | 普通 input 字段写类型/where，不能 default | 正确 | L:391–400；G:145–155；V:1256–1265 | 普通输入不能借 versioned 取得版本机制；规则可来自语义类型。 |
| R027 | P:36 | notBlank 等约束不是 validate 表达式 | 正确 | L:253、979–985；G:222–225、304–306；V:2451–2461 | 约束写 where（或继承语义类型）；validate 接布尔表达式，没有“input.x notBlank”运算符。 |
| R028 | P:37 | page/size 类型与 Page 输入字段 | 不完整 | L:1160、1685；T:918–929；V:683–726；I:704–736 | 必须是 input 的两个不同直接字段；不能用同一个字段、任意运算或变量替代，错误须进 fails。 |
| R029 | P:37 | 缺省 page=1、size=20，上限 page=10000、size=100 | 正确 | L:1685提及默认与上限但未列数值；Q:21；W:479–487 | 这里缺省也包含 null；不要加 validate 拦掉本应接受的缺省。 |
| R030 | P:38 | patch of 实体须有版本号，只写主键和可改字段，不写版本号 | 不完整 | L:402；V:1256–1325、1633–1659 | 还须至少一个可变字段，字段必须对应实体成员；仅用于该实体的 command 写能力。Version 等价于版本标记，不限定字段名。 |
| R031 | P:38 | patch 字段用去掉规则的 Text/Count/Optional<Text> | 正确 | L:402、436–446；V:1282–1284；B:20–25 | 包括语义类型继承的规则；Optional<Remark> 仍带 length，不能原样保留。 |
| R032 | P:38 | 每个 patch 字段直接绑定 input.x，没传自动保留，不写三元表达式 | 正确 | L:436–446；V:1664–1693；G:423–535 | 每个可变字段都要绑定；? : 不是 SIR 运算符，生成器按 presence 决定应用变化。 |
| R033 | P:38 | .present 只用于 patch 字段，普通可选值用 is none/present | 不完整 | L:436–446、1956；V:1695–1705；T:951–964 | 主键不在 changes 内，连 patch 的主键也不能 .present；普通 Optional 的“空”不同于 patch 的“没传”。 |
| R034 | P:38 | 版本化实体禁 when/原子本字段加减，persist else 报冲突 | 正确 | L:1317、1397；V:1590–1616、1756–1759、1826–1829 | 不手写版本增量，不把 expectedVersion 变成普通业务字段。 |
| R035 | P:38 | PATCH JSON 外层 id/expectedVersion/changes 自动生成 | 正确 | L:436–446；D:85–103；M:621–650 | 示例主键叫 id；如果实体主键另有名字，外层随主键名，不能一律强行改成 id。 |

### 视图、错误与能力声明

| 编号 | 手册行 | 拆分规则 | 判断 | 语言参考 / 编译器依据 | 正确说法或边界 |
| --- | --- | --- | --- | --- | --- |
| R036 | P:39 | 普通视图字段同名读取，= 表达式为计算字段 | 正确 | L:448–470；G:149–160；T:220–270 | 普通字段须有来源字段；计算字段可另取输出名，但类型须匹配。 |
| R037 | P:39 | 计算字段允许哪些表达式 | 不完整 | L:450、1933–1965；E:15–49；V:1183–1200 | 只能来源行的纯标量及允许的函数/常量，不能捕获 input/actor、now 或任意查询/跨行读取。 |
| R038 | P:39 | Ref 对应另一视图；List<子视图> 唯一反向 Ref | 不完整 | L:538、571–584；R:597–627；V:74、2088–2115 | 还有限两层关联深度、禁止循环；“有唯一一个指回父实体的 Ref”不是允许无约束多级展开。 |
| R039 | P:39 | 嵌套视图限分页 find 或 load/create 后返回单行 | 正确 | L:538；I:359–376 | 未分页 find 不能带嵌套响应；片段仍须补全所有实体/视图声明。 |
| R040 | P:39 | 视图不能读 secret、不能带约束 | 正确 | L:450；V:1183–1220 | 字段写 Name 等语义名不意味着可给 view 增加 where 校验。 |
| R041 | P:40 | 跨行 count 返回 Serial，只用于 readonly query 输出 | 不完整 | L:472–513；K:18–36、100–118 | 关联必须子 Ref==item，不能捕获请求/actor/clock 或嵌套查询；不能沿无界 List 返回。示例本身有 valid 逐字证据。 |
| R042 | P:41 | error 默认 400；只允许 400/401/403/404/409 | 正确 | L:586–593；G:227–229；T:527–537；N:429–430；J:19 | 错误名字是响应 code；不能靠声明任意 http 值改成功码。 |
| R043 | P:41 | 未认证/凭据无效 401，已登录无权 403 | 正确 | L:593、807–893；B:33–67 | 是 HTTP/业务约定；编译器检查码范围，不替作者推导谁该有权限。 |
| R044 | P:44–54、55 | capability actor→input→output→fails→requires→expose→workflow | 正确 | L:724；G:231–241 | actor/input 可省，output/expose/workflow 不可省。 |
| R045 | P:55 | fails 按错误名字升序，每个一行 | 正确 | L:730；G:256–258；V:2286–2312 | 排序和不可重复被检查；“每个一行”是排版，语法并不要求物理换行。 |
| R046 | P:55 | requires 按 authenticated→atomic→readonly 排列 | 正确 | L:764；V:2325–2343 | 顺序不表示三者都要写；atomic 与 readonly 互斥。 |
| R047 | P:56 | output 只能是列出的视图/Ref/List<Ref>/Page<视图>/Unit/值类型 | 不完整 | L:728、1639–1685；R:729–757；T:878–896 | 漏了合法的 Page<SumGroup<Ref<实体>>>，与手册77行自己给出的输出不一致；不应把这张清单当穷举。 |
| R048 | P:56 | 工作流每个错误要在当前能力 fails 声明 | 正确 | L:730；V:410–475、534–569 | 全局有 error 不代表每个 capability 自动可以使用它。 |
| R049 | P:57 | authenticated 配 actor Ref<实体>，工作流读 actor | 不完整 | L:769、389；R:697–706；V:913–961 | 写法正确但须补凭据主体一致性；actor 不是 Token 行本身。 |
| R050 | P:57 | 两个及以上 create/update/persist 写步骤必须 atomic | 不完整 | L:766；V:365–375、2161–2164 | 代码还把 delete 算写步骤；循环体写步骤也计入，不能漏掉两次删除等组合。 |
| R051 | P:57 | query 要 readonly；readonly 禁 create/update/persist/delete | 正确 | L:767；V:358–364、429–475 | 还须记住 atomic 与 readonly 不能并用，下面缺失表补列。 |

### HTTP、读取与查询

| 编号 | 手册行 | 拆分规则 | 判断 | 语言参考 / 编译器依据 | 正确说法或边界 |
| --- | --- | --- | --- | --- | --- |
| R052 | P:58 | query 默认 GET；普通 command POST，patch command PATCH | 正确 | L:897–899；M:734–746 | 这是无显式方法时的默认规则，不能用 query 伪装修状态码。 |
| R053 | P:58 | 显式 put/delete 只跟 command，POST 不写 post | 不完整 | L:914–916；G:271–278；V:258–262 | patch 输入固定 PATCH，连 put/delete 也不能显式指定。 |
| R054 | P:58 | 输入是查询参数还是 JSON 体 | 不完整 | L:911；M:748–753；C:155–179 | 默认 POST/PATCH 的 JSON 说明正确，但 DELETE 跟 GET 一样用查询参数；路径值覆盖同名请求体值，再校验。 |
| R055 | P:58 | 默认能力名短横线路由，自定路径自动加 /api | 正确 | L:897–911；V:337–349；M:709 | 不要在 at 字符串里重复 /api；手册说的 /api/api/notes 与实现一致。 |
| R056 | P:58 | 路由正好默认时必须不写 at | 找不到依据 | L:902–916；V:264–269 | 是简洁写作策略，不是编译限制；显式写同一合法路径也能表达同一合同。 |
| R057 | P:58 | 合同路由照写、四种 at 示例 | 不完整 | L:911–916；V:278–334 | 须补路径段格式、占位字段必须直接必填标量、patch 仅主键入路径、方法+路径形状不得冲突。 |
| R058 | P:59 | 含任意 create（含循环）则 201，否则 200 | 正确 | L:900；C:123–126、218–220 | 依据是工作流中有没有 CreateStep，不看 return 的行，也不看是否命名为 Create。 |
| R059 | P:61 | 按顺序执行，且只以一个 return 结束 | 正确 | L:977；V:398–406、511–517、2150–2158 | 循环体不能 return；没有条件分支 return 语法。 |
| R060 | P:62 | validate 条件 else 错误 | 正确 | L:979–985；G:304–306；T:571–575；V:410–415 | 条件须布尔，错误要进当前 fails；失败立即终止。 |
| R061 | P:63 | load 主键不写字段名 | 正确 | L:987–1001、1041；G:310–312；V:1148–1163 | by id: 并非主键加载的另一种合法简写。 |
| R062 | P:64 | load 可以按声明 unique 的非主键字段 | 不完整 | L:1009–1076；V:1148–1180 | 版本化实体按字段 load 后再 update 会被拒绝，需按主键加载；不是所有 Ref/普通字段都能当查询键。 |
| R063 | P:65 | for update 只用于 atomic command 按主键加载 | 正确 | L:1001；V:419–425；G:315–317 | 锁持有到事务结束，scope 仍生效；不要求必须更新这行。 |
| R064 | P:66 | find where/order/Page/as 写法 | 正确 | L:1113–1160；G:334–336、362–372 | 排序和 Page 子句各有语义约束，不是随意 SQL。 |
| R065 | P:67 | 普通查询比较左 item.字段，右不读 item | 不完整 | L:1121、1544；V:745–791、472–495 | 不只是 find；exists/count/sum/each 共用查询形状。any 内另有裸字段作用域。 |
| R066 | P:67 | 不对查询列做运算 | 正确 | L:1121、1954；V:635–668 | 请求侧计算可作参数，any 内不能做算术；别把“列不可算”理解成所有参数都不能算。 |
| R067 | P:67 | 不分页得 List<Ref>，分页得 Page<视图> 且 query；where true | 正确 | L:1160；R:846–854；V:613–618、683–701 | Page 根实体须是该视图的来源实体，where true 仍受 scope 限制。 |
| R068 | P:68 | Optional 筛选用 input.kind is none or item.kind==input.kind | 不完整 | L:1220、1956；T:951–964、1024–1032 | 示例合法；须补任何空参与 ==/!=/大小比较都 false，not(==) 与 != 不等价。 |
| R069 | P:68 | any(Enrollment, course==item and active==true) | 不完整 | L:1289；T:291–366；V:2008–2085 | 需要正确 Ref 连接且在最外层 and，可反向 id==item.ref，不可嵌套；裸 active 必须是被查实体的已声明字段。 |
| R070 | P:68 | item.owner==actor 只看当前主体 | 正确 | L:1227–1247、1625；T:1057–1076 | owner 须是指向 actor 实体的 Ref；没有自动管理员越权。 |

### 写入、守卫与聚合/循环

| 编号 | 手册行 | 拆分规则 | 判断 | 语言参考 / 编译器依据 | 正确说法或边界 |
| --- | --- | --- | --- | --- | --- |
| R071 | P:69 | create 给所有必填，不能绑主键/Version | 正确 | L:1297；G:374–376；V:2166–2205 | 可省的是 Optional/default/框架版本字段；绑定不得重复，先创建对象再 persist 入库。 |
| R072 | P:70 | update 变量 {字段:值} | 正确 | L:1308–1317；G:378–380；V:2209–2245 | 目标须为实体行，不可绑定主键/版本或同字段两次。 |
| R073 | P:71 | create/update 之后 persist 才写库 | 正确 | L:1297、1308–1317；G:389–391；W:767–832 | update 不是自动写库；delete 是直接删除步骤，不再 persist。 |
| R074 | P:71 | persist else 仅版本条件更新/新行唯一冲突 | 正确 | L:1317–1319；V:1598–1630 | 普通守卫更新的失败在 update when else，不给后面的普通 persist 另加 else。 |
| R075 | P:72 | update when 在数据库写入时检查条件，后面 persist | 不完整 | L:1397；V:1735–1775、1784–1805；W:881–936 | 必须针对加载行（也含 each 行），非版本化；一旦使用守卫/本字段相对更新，该行整个流程只能有一个 update。 |
| R076 | P:72 | 一般守卫“并发下只让一个成功” | 错误 | L:1352、1397–1403；W:897–936；样例 multiplication.sir:115–121 | 守卫仅保证条件与写入同一条 SQL。capacity=3、enrolled=0 时两次 +1 都成功；只有写后令守卫不再成立才有唯一赢家。 |
| R077 | P:72 | 守卫绑定不能读这一行其他字段 | 不完整 | L:1397；V:1762–1774；样例 g9-topic-conformance.sir:278–284 | 漏了先 load for update 锁定目标的例外：锁住后可读目标其他字段；本字段原子加减也有单独例外。 |
| R078 | P:73 | enrolled: course.enrolled+1 是原子加减 | 不完整 | L:1401–1445；V:1809–1858；W:913–922 | 同字段±不读目标的值；整数/十进制、非版本字段；目标须已存，不能把未 persist 新对象当数据库计数。 |
| R079 | P:74 | delete 只删 load/each 的行 | 正确 | L:1498–1527；G:395–397；V:2248–2271 | 不能删 create 得到的变量；同一行删后不能再 update/persist。 |
| R080 | P:75 | exists/count 工作流步骤 | 不完整 | L:1537–1544；G:357–360；R:897–909 | exists 得 Flag，count 得 Serial；item 只在该条件内有效，共用普通查询形状，不是通用表达式函数。 |
| R081 | P:76 | sum 金额字段，只在 readonly query；avg 同理 | 不完整 | L:1594–1637；T:648–665；V:472–479；I:613–625 | 可读 Optional<Money>，空不计、空集合为0；avg 按字段小数位四舍五入，二者不计 secret，仍须手写归属条件。 |
| R082 | P:77 | sum by 引用字段，输出 Page<SumGroup<Ref<实体>>> | 不完整 | L:1639–1689；T:667–692、878–896；V:714–726 | 是直接非可选 Ref；total 是组数、按键排序、无行不补零；avg 也能分组，不是普通 Page<view>。 |
| R083 | P:78 | month input.month in input.zone 得本地自然月边界，限只读 query | 不完整 | L:1691–1712；G:341–345；T:631–640；V:466–470 | 月份需严格 YYYY-MM、有效区域时区，得 UTC Moment 的半开边界；不是固定30天，错误须列 fails。 |
| R084 | P:79 | each atomic command、字面 limit 1..100、体内禁 find/return/嵌套循环 | 不完整 | L:1759–1761；V:529–552、576–610 | 还要求排序不重复且主键收尾；体内不能聚合/month，外部行仅允许无守卫本字段累加；超限循环一次不执行。 |
| R085 | P:80 | repeat input.weeks times limit 12 else TooMany as week | 不完整 | L:1854、1903；G:327–332；T:606–614；V:558–570 | 只在 atomic command；次数 Int32 且非 Optional，limit 为1..100；序号从0起，只在体内可见，循环限制与each相同。 |
| R086 | P:81 | return 变量或 return unit | 正确 | L:1529–1535；G:399–401；T:733–751、777–787 | 返回类型须匹配；Unit 输出只能 return unit，视图可返回它来源实体的行。 |

### 易错点、内置功能与任务协议

| 编号 | 手册行 | 拆分规则 | 判断 | 语言参考 / 编译器依据 | 正确说法或边界 |
| --- | --- | --- | --- | --- | --- |
| R087 | P:84 | 新行读主键/当引用/返回含主键视图前必须 persist，报 FLOW-007 | 正确 | L:1299；V:1381–1507；W:804–809 | UUID 也在插入时赋值；不能只因为“是UUID”就提早读。 |
| R088 | P:84 | 新行的其他普通字段 create 后可读 | 正确 | L:1299；V:1436–1507 | 视图不含主键时，也可能返回未持久化对象；不等于有数据库写入。 |
| R089 | P:85 | 新建引用行+更新父行，先 persist 父或 for update，报 VALID-015 | 不完整 | L:1445；V:1340–1372 | 不限“计数”，规则针对新行引用的父行也被修改；for update 后两种 persist 顺序均安全。 |
| R090 | P:86 | exists/count 再插入防不住并发；名额用守卫，时段锁父行 | 正确 | L:1007、1592；V:1515–1541；W:897–936 | atomic 只包事务，不自动锁查询范围；所有冲突写路径要锁同一父行。 |
| R091 | P:87 | Secret 绑定 input.password 自动加盐摘要 | 正确 | L:2099；M:1299–1308；X:67–68；O:36–55 | 语义依据是目标 secret 字段，不是输入字段恰好叫 password；具体摘要由生成器处理。 |
| R092 | P:87 | secret 只能以 stored matches candidate 读取，不能比较/返回/视图 | 正确 | L:2004、2099；V:1092–1145、1205–1210 | matches 的左边须为 secret，右边为文本；不能放查询谓词。 |
| R093 | P:87 | 不存在账号与口令错用同一个错误 | 正确 | L:2099；F:103–115；样例 dual-token-written.sir:96–97 | 是登录模式的业务要求，不是编译器自动把两个 error 合并；仍需作者在load/validate用同一个else。 |
| R094 | P:88 | 显式比较归属、显式检查角色 | 正确 | L:807–893；T:1021–1153；B:63–67 | Ref==actor/枚举角色比较合法；检查顺序由业务要求决定，管理员不自动绕过归属。 |
| R095 | P:89 | 加减同种整数/金额，乘法还允许金额×整数 | 不完整 | L:154、1933；T:1029–1036、1092–1101、1130–1140 | “同类型”应解释为底层 Int32/Int64/Decimal；字面量可跟64位变宽、字段不能跨宽度；Decimal×Decimal也合法。 |
| R096 | P:89 | 没有除法/取模 | 正确 | L:1933；G:475–477、611–640 | 不要通过 max/min 或 Java片段假装有除法。 |
| R097 | P:89 | now/addDays/daysBetween/max/min 函数可用 | 不完整 | L:1965；G:500–520；T:1102–1129；A:57–77 | 列函数名不等于任意参数均可：daysBetween 同Moment/Day且返回Serial，max/min不可文本，addDays为时间+整数天，均不可Optional。 |
| R098 | P:89 | now()+15 minutes 等时间位移 | 正确 | L:2002；G:462–489；T:985–1017 | Moment可各种时长，Day只按天，位移字面量有32位上限。 |
| R099 | P:89 | Optional 不能算术，is none/present 与 and/or/not 可用 | 不完整 | L:1954–1965；T:951–982、1021–1032 | 还漏“判空不缩窄类型”、空比较表、算术/函数依赖输入会在工作流前按 NotNull 拒绝缺值。 |
| R100 | P:90 | use DualTokenLogin 产生两令牌/三能力并扩展账号字段 | 正确 | L:2114–2127；F:103–115；H:1–4 | 默认字段 loginName/password；可以通过参数改名，不应为use又重复手写相同字段。 |
| R101 | P:90 | use Crud 产生 Create/Get/List/Update/Delete | 不完整 | L:2166–2199；F:119–166；B:1–25、40–100 | 默认合法；漏 owner/path/writer/writeRole/writeExclude/read/readView，可组合现有参数满足大量合同，不必全手写。 |
| R102 | P:90 | use 展开后不能再写同名声明 | 正确 | L:620–650；R:205–215；H:1–4 | 包括被 extend 加入的字段也不能重复；可用 prefix 避免同名能力冲突。 |
| R103 | P:90 | 标准行为不合时手写展开 | 正确 | L:2129、2201；F:14–20 | 是可行的组合方式，不是语言禁止自定义 feature 或合法参数定制。 |
| R104 | P:93 | 改写返回完整文档，保留原能力/路由/字段；从零写整个软件 | 找不到依据 | 语言层无此题型判据；G:5–8 只检查源文档；任务JSON的 requirement/contract 是依据 | 这是采样任务协议，不能说编译器证明了旧行为仍在；要由任务合同和业务门验收。 |
| R105 | P:93 | namespace/路由/字段/错误/常量不迁就实现，不加Java/SQL逃生口 | 找不到依据 | G:55–68 仅定义合法声明；L:2718–2722；任务合同为冻结值依据 | Java/SQL逃生口确实不在语法；具体名字与常量的冻结来自任务，不是这些拼写被语言保留。 |

## 二、手册每个代码例子的 valid 对照

检索了 45 份根目录 `valid/*.sir`，另确认目录下还有4份多文件工程片段（不冒充根目录样例）。这里“原样”仅忽略排版空白；“同形”明确允许更换实体/字段/变量/错误名或数字参数，但**不是原样例已执行的证明**。中文“步骤/条件/变量”、省略号、函数形参和JSON展示分别标明，不当作可以直接复制的完整SIR。

复合例按整块核对，同时将其中容易误读的独立片段另列。所有 prose 中的声明、查询、绑定、调用、路由和 use 示例都在以下表内；类型与运算符清单合并为一行，但按登记表与语法规则逐一查过。


### 2.1 已有样例支持的片段与非SIR展示

| 编号 | 手册行 | 例子 | 检索结果 | valid依据 | 说明 |
| --- | --- | --- | --- | --- | --- |
| E01 | P:4–11 | 文档/metadata/target 骨架 | 模板同形 | S/account-registration-feature.sir:1、S/account-registration-written.sir:1 | 语法模板；中文名字与“声明”须换成真实内容。 |
| E02 | P:12 | course/row/made/found 变量名 | 模板同形 | S/course-admin-enrollment-add-list-refs.sir:118、S/course-admin-enrollment-filter-any.sir:116 | 普通标识符例，不是完整程序。 |
| E03 | P:15 | 全部列举的类型及泛型 | 模板同形 | S/account-registration-written.sir:30、S/bearer-credential.sir:21 | 类型登记Y:59–80、包装语法G:407–415；Unit可见return例。 |
| E08 | P:30 | `identity id: Id generated auto;` | 原样有 | S/account-registration-feature.sir:21、S/account-registration-written.sir:21 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E09 | P:30 | `identity id: Token generated uuid;` | 原样有 | S/bearer-credential.sir:25、S/crud-access.sir:14 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E11 | P:32 | 默认值与now() | 模板同形 | S/crud-feature.sir:40、S/crud-written.sir:40 | 完整同形字段见default-value.sir:22–27。 |
| E12 | P:33 | `field removed: Flag default false; scope removed == false;` | 原样有 | S/crud-feature.sir:40、S/crud-written.sir:40 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E13 | P:34 | `identity id: Token generated uuid; field owner: Ref<User>; field expiresAt: Moment; credential subject owner expires expiresAt;` | 原样有 | S/bearer-credential.sir:25、S/crud-access.sir:14 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E14 | P:35 | SQL名字映射TokenPair/accessExpiresAt/owner | 模板同形 | S/bearer-credential.sir:26、S/crud-access.sir:15 | 这不是SIR语句；映射实现M:109、559–561、657。 |
| E17 | P:37 | `field page: PageNo; field size: PageSize;` | 原样有 | S/course-admin-enrollment-add-list-refs.sir:102、S/course-admin-enrollment-filter-any.sir:100 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E18 | P:37 | Page find模板 | 模板同形 | S/course-admin-enrollment-add-list-refs.sir:138、S/course-admin-enrollment-filter-any.sir:169 | 省略号/“错误”是占位符，不是可复制源码。 |
| E20 | P:38 | `name: input.name;` | 原样有 | S/account-registration-written.sir:47、S/bearer-credential.sir:81 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E21 | P:38 | `input.name.present` | 原样有 | S/course-admin-enrollment-filter-any.sir:147、S/course-admin-enrollment-tighten.sir:149 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E23 | P:38 | `persist course else StaleVersion;` | 原样有 | S/course-admin-enrollment-filter-any.sir:154、S/course-admin-enrollment-tighten.sir:156 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E24 | P:38 | JSON外层id/expectedVersion/changes | 模板/非SIR | 不适用（HTTP示意，见DTO源码） | 这是HTTP示意，不是SIR；D:85–103为实际生成依据，不能拿它要求valid里出现JSON。 |
| E26 | P:39 | `field remaining: Count = item.capacity - item.enrolled;` | 原样有 | S/multiplication.sir:109、S/shop-conformance.sir:667 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E27 | P:40 | `field availableCopies: Serial = count(Copy, book == item and status == CopyStatus.AVAILABLE);` | 原样有 | S/g9-topic-conformance.sir:133 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E29 | P:44–54 | PublishGoods能力骨架 | 模板同形 | S/campus-market-candidate-modify-input-field-constraints.sir:47、S/campus-market-candidate.sir:47 | “步骤”是占位符；输入/视图/错误需声明，未见同一fails列表整块。 |
| E30 | P:58 | `expose query at "/notes/{id}";` | 原样有 | S/crud-written.sir:108 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E31 | P:58 | `expose command at "/notes";` | 原样有 | S/crud-written.sir:90 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E33 | P:58 | `expose command delete at "/notes/{id}";` | 原样有 | S/crud-written.sir:150 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E34 | P:62 | validate条件模板 | 模板同形 | S/bearer-credential.sir:96、S/campus-market-candidate-modify-input-field-constraints.sir:57 | 条件/错误是占位符；具体matches/角色例另列。 |
| E35 | P:63 | load主键模板 | 模板同形 | S/bearer-credential.sir:70、S/course-admin-enrollment-add-list-refs.sir:118 | G:310–312；这里只改实体/变量名。 |
| E36 | P:64 | load唯一字段模板 | 模板同形 | S/bearer-credential.sir:95、S/dual-token-written.sir:96 | 对应字段必须unique。 |
| E37 | P:65 | load for update模板 | 模板同形 | S/g9-topic-conformance.sir:155、S/multiplication.sir:147 | 对应能力必须atomic command。 |
| E38 | P:66 | 组合find/字面包含/排序/分页模板 | 模板同形 | S/course-catalog.sir:47、S/g9-topic-conformance.sir:52 | 字段/title/x/错误是占位符；所有子构造合法，不声称有同一整条查询。 |
| E39 | P:67 | `where true` | 原样有 | S/crud-written.sir:121 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E42 | P:68 | `item.owner == actor` | 原样有 | S/decimal-group.sir:45、S/money.sir:331 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E43 | P:69 | create绑定模板 | 模板同形 | S/account-registration-written.sir:46、S/bearer-credential.sir:80 | 字段值/变量是占位符；G:374–376。 |
| E44 | P:70 | 普通update模板 | 模板同形 | S/bearer-credential.sir:113、S/course-admin-enrollment-filter-any.sir:149 | G:378–380。 |
| E45 | P:71 | 普通persist模板 | 模板同形 | S/bearer-credential.sir:84、S/campus-market-candidate-modify-input-field-constraints.sir:64 | G:389–391。 |
| E46 | P:71 | 带else的persist模板 | 模板同形 | S/account-registration-written.sir:50、S/course-admin-enrollment-filter-any.sir:154 | 必须区分版本冲突/唯一冲突两类。 |
| E47 | P:72 | 带when的update模板 | 模板同形 | S/dual-token-written.sir:143、S/g9-topic-conformance.sir:226 | V:1735–1775；不能把“一个成功”当通用语义。 |
| E49 | P:74 | delete变量模板 | 模板同形 | S/crud-written.sir:153、S/delete-step.sir:39 | load/each来源限制见V:2248–2271。 |
| E50 | P:75 | exists独立步骤模板 | 模板同形 | S/g9-topic-conformance.sir:224、S/multiplication.sir:148 | G:357–360。 |
| E52 | P:76 | sum金额字段模板 | 模板同形 | S/money.sir:136 | G:347–351。 |
| E53 | P:76 | avg同形模板 | 模板同形 | S/money.sir:161 | G:347–351；金额与readonly限制同sum。 |
| E54 | P:77 | sum by分页模板及SumGroup输出 | 模板同形 | S/decimal-group.sir:36、S/money.sir:189 | decimal-group有完整对应；也适用于avg。 |
| E55 | P:78 | `month input.month in input.zone as since, until else BadPeriod;` | 原样有 | S/calendar-month.sir:13 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E56 | P:79 | each模板及limit 50 | 模板同形 | S/shop-conformance.sir:261 | shop-conformance用limit100；50也在1..100范围内，不代表找到逐字50整块。 |
| E57 | P:80 | repeat模板及limit12 | 模板同形 | S/multiplication.sir:164、S/shop-conformance.sir:722 | valid错误名TooManyWeeks，不是TooMany。 |
| E58 | P:81 | return变量模板 | 模板同形 | S/account-registration-written.sir:51、S/bearer-credential.sir:71 | 返回须匹配output；G:399–401、T:733–751。 |
| E59 | P:81 | `return unit;` | 原样有 | S/crud-written.sir:154、S/default-value.sir:41 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E60 | P:84 | `persist access;` | 原样有 | S/dual-token-written.sir:106 | 单条原样存在；完整顺序可看dual-token-written。 |
| E61 | P:87 | `validate account.passwordHash matches input.password else BadLogin;` | 原样有 | S/token-desk.sir:124 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E63 | P:88 | `validate actor.role == Role.ADMIN else Forbidden;` | 原样有 | S/shop-conformance.sir:89 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E64 | P:89 | `now()` | 原样有 | S/bearer-credential.sir:99、S/default-value.sir:27 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E65 | P:89 | `now() + 15 minutes` | 原样有 | S/bearer-credential.sir:99、S/dual-token-written.sir:100 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |
| E70 | P:89 | is none / is present / and / or / not | 模板同形 | S/decimal-group.sir:43、S/g9-topic-conformance.sir:53 | 运算符示例，不是独立SIR声明；G:433–456。 |
| E71 | P:90 | `use DualTokenLogin { account: User; }` | 原样有 | S/crud-feature.sir:56、S/crud-written.sir:428 | 片段原样有，不代表把其它未声明类型省掉后仍是完整文档。 |

### 2.2 未找到原样例子的具体片段（单列）

| 编号 | 手册行 | 例子 | 检索结果 | 最近的同形/局部证据 | 为什么不直接判成“编造” |
| --- | --- | --- | --- | --- | --- |
| E04 | P:16 | `type UnitPrice = Money where precision(19, 4), min(0);` | 无原样 | S/money.sir:11、S/multiplication.sir:8 | valid 使用 precision(21,4) 而非这组19,4+min参数；Z:99–114支持该组合。 |
| E05 | P:17 | `enum Status { OPEN, CLOSED }` | 无原样 | S/default-value.sir:29 | 仅换枚举名同形；不是语言没有该写法。 |
| E06 | P:17 | `Status.OPEN` | 无原样 | S/default-value.sir:23 | 枚举名来自上下文。 |
| E07 | P:21–28 | `entity Course persistent { identity id: Id generated auto; field code: Code unique; field capacity: Quantity; field enrolled: Count default 0; field note: Optional<Text>; field owner: Ref<User>; }` | 无原样 | S/course-admin-enrollment-add-list-refs.sir:21、S/course-admin-enrollment-filter-any.sir:21 | 未见整块；CourseCounter见multiplication，owner见bearer；需要另外声明User。 |
| E10 | P:31 | `field name: Name where length(1, 64) unique;` | 无原样 | S/dual-token-base.sir:21、S/dual-token-written.sir:21 | 未见这组字段名+长度+unique原样组合；G:165–167明确支持。 |
| E15 | P:36 | `input RegisterInput { field name: Name where length(1, 64); field note: Optional<Text>; }` | 无原样 | S/account-registration-written.sir:29、S/dual-token-base.sir:44 | 未见整块；字段类型/约束分别有同形例，G:145–155支持。 |
| E16 | P:36 | `field keyword: Text where notBlank;` | 无原样 | S/course-catalog.sir:34（Name继承notBlank，非同一声明） | 具体Text where notBlank可见L:932；输入where见G:153–155。 |
| E19 | P:38 | `input UpdateCourseInput patch of Course { field id: Id; field name: Text; }` | 无原样 | S/course-admin-enrollment-add-list-refs.sir:94、S/course-admin-enrollment-filter-any.sir:92 | course-admin.sir:46–51还有description/capacity；未见这个完整两字段版本。 |
| E22 | P:38 | `validate input.name.present or input.capacity.present else EmptyChange;` | 无原样 | S/course-admin-enrollment-filter-any.sir:147、S/course-admin-enrollment-tighten.sir:149 | course-admin增加description.present；G/V支持这两个字段的or，不要求固定三字段。 |
| E25 | P:39 | `view CourseView from Course { field id: Id; field code: Code; field remaining: Count = item.capacity - item.enrolled; field owner: UserSummary; field items: List<ItemView>; }` | 无原样 | S/course-admin-enrollment-add-list-refs.sir:78、S/course-admin-enrollment-filter-any.sir:78 | 未见整块；UserSummary/ItemView及关联实体都未给出。各机制有valid例，不能把这块当完整可编译程序。 |
| E28 | P:41 | `error NotFound http 404;` | 无原样 | S/bearer-credential.sir:62、S/course-admin-enrollment-add-list-refs.sir:106 | 错误名不同不改变语法；T:527–537。 |
| E32 | P:58 | `expose command put at "/notes/{id}";` | 无原样 | S/crud-written.sir:219 | valid根目录未见这个逐字PUT例；L:907、G:271–278和V:258–262有直接依据。 |
| E40 | P:68 | `input.kind is none or item.kind == input.kind` | 无原样 | S/g9-topic-conformance.sir:53、S/money.sir:136 | 未见kind这个原样字段；同一Optional-or模式存在。 |
| E41 | P:68 | `any(Enrollment, course == item and active == true)` | 无原样 | S/course-admin-enrollment.sir:167、S/course-enrollment.sir:84 | 未见active布尔存储列这个例；valid用枚举status，不能混淆EnrollmentSummary的计算active与实体字段。 |
| E48 | P:73 | `update course when course.enrolled < course.capacity else CourseFull { enrolled: course.enrolled + 1; }` | 无原样 | S/multiplication.sir:119、S/shop-conformance.sir:677 | valid的错误名为Full；仅错误名不同，执行机制相同。 |
| E51 | P:75 | count独立步骤模板 | 未见valid同形 | 未找到对应根目录样例 | L:1537–1544、G:357–360、R:897–909直接支持；不能拿视图count()函数充当工作流count步骤。 |
| E62 | P:88 | `validate goods.seller == actor else NotSeller;` | 无原样 | S/crud-written.sir:378、S/g9-topic-conformance.sir:267 | 未见这组实体字段名；同形Ref归属比较存在，须由真实字段声明决定。 |
| E66 | P:89 | `addDays(t, n)` | 无原样 | S/multiplication.sir:165、S/shop-conformance.sir:723 | 参数名字t/n为示意，实参需要时间与整数。 |
| E67 | P:89 | `daysBetween(a, b)` | 无原样 | S/g9-topic-conformance.sir:282 | 参数a/b为示意；返回64位天数。 |
| E68 | P:89 | `max(a, b)` | 无原样 | S/g9-topic-conformance.sir:282 | 不是字段where max约束。 |
| E69 | P:89 | `min(a, b)` | 无原样 | 未找到对应根目录样例 | valid根目录未见min函数调用，T:1111–1119直接支持；不能拿where min约束充当函数证据。 |
| E72 | P:90 | `use Crud { entity: Note; }` | 无原样 | S/crud-access.sir:24、S/crud-feature.sir:52 | 若例中还写path/owner，属于同一use形状；F:145–166支持默认参数。 |

以上“无原样”不等于不支持：例如枚举换名、错误名CourseFull/Full、自定义类型精度19/21都不会改变语法。反过来，P:39 的 UserSummary/ItemView 没有给出声明，P:68 的 active 也没有实体字段定义；**缺上下文的片段不能保证原样可编译**。应把它们标成“模板，需补声明”，而不是指称存在这份完整参考实现。

P:40 的跨行 count 有 S/g9-topic-conformance.sir:133 的完整原样片段；each/repeat/month/分组金额也都有根目录样例，不能因为只熟悉选课样例就误判为不存在。

## 三、对写业务有用、语言参考有而手册漏讲的规则

| 规则 | 业务意义/通用写法 | 权威位置 |
| --- | --- | --- |
| 数值/时间函数依赖输入的NotNull | 非Optional输入仅有min/max不代表必填；参与算术或内置函数的输入在工作流前按400 INVALID_REQUEST/NotNull拒绝缺失和null。不能发明where notNull语法。 | L:1954、1965；A:57–77；W:49–77 |
| Optional完整真值表与不缩窄 | 任一空参与==/!=/大小比较都false；not(x==y)不等于x!=y。判过空也不能把Optional当普通值做算术或取可选Ref成员。 | L:1956–1965；T:951–964、1024–1032 |
| 普通查询与any的两种作用域 | find/exists/count/sum/each用item.字段；any里裸名是子实体字段；连接是子Ref==item或子主键==item.ref，最外层and，不可任意跨表列比较/嵌套any。 | L:1121、1289、1544；T:291–366；V:2008–2085 |
| 算术宽度与金额精确性 | Count/Serial字段不自动互转，字面量可跟随64位变宽；Money无隐含非负；精度不够不能悄悄舍入，avg才按其定义舍入。 | L:154、178、1637、1933；T:1029–1036、1092–1101、1130–1140 |
| 凭据主体与整个软件的一致性 | actor是credential subject所指实体；一个主体一种凭据；全软件不能一部分由凭据认证、一部分不是。 | L:389；V:913–961 |
| scope覆盖所有读取入口 | 软删除/隐藏范围自动作用load/find/exists/count/sum/any；详情、公开读与嵌套/计数不应绕过它。 | L:342、513、2166、2196；V:974–995；U:62–68、86–144、164–172 |
| 唯一约束真正规则 | 联合unique至少两字段；禁止Optional/secret/version/主键，不限长文本要限长，总长度≤768；唯一冲突用新行persist else，先exists不是并发唯一保证。 | L:259–261；V:823–875；T:466–487 |
| default/version/secret的互斥边界 | default不能给unique/secret/version；每实体至多一个64位版本字段，版本字段不能再带规则；Secret不能与unique/约束混用。 | L:257、309、311；V:998–1054、1224–1250 |
| patch完整合同 | 主键恰一次、至少一可变字段、每个字段都要绑定；主键不能present；版本号在生成的外层，不在SIR输入；仅command可用。 | L:402–446；V:1256–1325、1633–1705 |
| 嵌套和计数响应的界限 | 嵌套最多两层；不能无分页find嵌套，关联List唯一反向Ref；跨行count只读取声明连接范围，不捕获外部变量，也不能沿无界List返回。 | L:472–538、571–584；V:74、2088–2115；K:18–36、100–118 |
| GET/DELETE输入与路径合并 | GET/DELETE其余输入来自查询参数；POST/PUT/PATCH来自JSON，路径同名字段覆盖体中值；patch路径只能主键，方法+路径形状不得重复。 | L:911–916；V:246–334；M:748–753；C:164–179 |
| 读取和更新并发不由atomic自动解决 | for update持有到事务结束；所有竞争路径锁同一父行；守卫只能保证条件写入，唯一赢家需写后条件失效；锁目标可读其他字段。 | L:1001–1007、1397–1445；V:1762–1774；W:897–936 |
| sum/avg/分组的返回与空集 | exists得Flag，count得Serial；sum/avg金额空集0、平均按字段精度舍入；分组total是组数、无匹配键不补零、越页仍保留total；金额汇总也必须显式授权。 | L:1544、1625–1689；T:648–692 |
| 循环的边界和事务回滚 | each主键收尾、超限先报错且一次不执行；repeat次数Int32、序号0起；禁嵌套/return/find/聚合/month，循环外行仅无守卫原子累加，任一步失败整笔回滚。 | L:1759–1761、1903；V:529–610 |
| 自然月和时区 | 严格YYYY-MM+区域时区，按当地月初/月末转UTC半开区间，不能假定30天或固定秒数。 | L:1691–1712；I:579–597 |
| 内置功能可组合参数而非全手写 | Crud的owner、path、writer、writeRole、writeExclude、read/public、readView；DualTokenLogin的字段名/期限/prefix；AccountRegistration注册；都展开普通声明。 | L:2101–2129、2166–2201；F:103–183；B:1–100 |
| 复用扩展与声明闭合 | 允许extend给实体补字段、自定义feature按实体展开；跨文件需要import且名字全工程唯一。手写实体不会凭名字自动带视图，每个被引用的类型都要声明或由use明确展开。 | L:115、599–694、2205；G:55–113；R:205–215、1284–1291 |
| atomic/readonly互斥，delete也算写步骤 | 两次delete等组合仍需要atomic；只读禁止所有写步骤，不能为了200把含写流程改成query。 | L:766–769；V:358–375、2161–2164 |

## 四、建议的手册修订边界（本次未改手册）

先改掉“守卫总能保证一个成功”的绝对表述，再补算术必填、空比较、查询/any作用域、认证主体、HTTP输入位置和循环/响应边界。保留已有正确规则，不为某道题写专用条件；把示例标成“完整软件 / 声明片段 / 语法模板”，并为具体片段给出对应valid样例。

L:1399 对守卫也有“只有一个成功”的简写，W:881–888 的注释同样偏向一次性状态转换。**可执行实现 W:897–936 以及 L:1401–1445、S/multiplication.sir:115–121 的计数用途，才界定了一般语义**：写时条件满足即可继续成功。这是文档表述范围问题，不是本次发现编译器丢了原子性；本次没有修改语言参考或源码。

“找不到依据”的提示策略/冻结合同不是语言bug。它们可以保留为任务要求，但应与语言能否表达、编译器是否强制检查分开。已有规则仍被教师违反的情况和修复成效见 `teacher-error-census.md`，选课的逐失败根因见 `course-selection/failure-analysis.md`；这些统计不能替代新答案的业务判卷。
