# 通用规则手册 v6 草案改动说明

## 范围、来源与篇幅

以 `experiments/slm/prompts/sir-0.3-general-v5.md` 全文为底稿，保留开头、七节结构及章节顺序。只新增本目录的两份草稿，不替换 `prompts/` 下的版本，不修改语言参考、源码或样例。依据为仓库 `727e90d9f6e6e6e5ec27be7392ad575c75dabd08` 的现存材料；未运行 Maven、编译器或模型 API，样例引用不代表本次重新执行过。

按 UTF-8 解码后的 Unicode 字符计数，**包括英文、空白和换行**：v5 为 **7,508**，v6 为 **8,977**，不超过 9,000。七节内容未调序；第七节改称“输入合同”，去掉题型称呼，仍保留完整输出及合同冻结要求。手册不含样本编号、失败断言或测试反馈。

下面的“压缩”不引入新语义；“补全”是现有语言/目标已经具有的行为，并非采纳“非 Optional 输入一律必填”的新设计。

### 文件缩写

所有冒号后的数字均为所列文件的行号。报告引用另标章节或审计规则编号。

| 缩写 | 文件 |
| --- | --- |
| P | `experiments/slm/prompts/sir-0.3-general-v5.md` |
| N | `experiments/slm/drafts/prompt-v6/sir-0.3-general-v6.md` |
| A | `experiments/slm/drafts/prompt-audit-general-v5.md` |
| B | `experiments/slm/drafts/teacher-error-census.md` |
| C | `experiments/slm/drafts/course-selection/failure-analysis.md` |
| L | `docs/SIR_LANGUAGE_REFERENCE.md` |
| G | `sir-parser/src/main/antlr4/io/kcg/sir/internal/Sir.g4` |
| VA | `sir-semantic/src/main/java/io/kcg/sir/semantic/internal/ValidatePass.java` |
| TY | `sir-semantic/src/main/java/io/kcg/sir/semantic/internal/TypePass.java` |
| RE | `sir-semantic/src/main/java/io/kcg/sir/semantic/internal/ResolvePass.java` |
| AR | `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/ArithmeticInputs.java` |
| ML | `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/SpringBootModelLowerer.java` |
| WR | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/WorkflowRenderer.java` |
| ES | `sir-semantic/src/main/java/io/kcg/sir/semantic/api/EntityScopes.java` |
| CV | `sir-semantic/src/main/java/io/kcg/sir/semantic/internal/ComputedViewRules.java` |
| RC | `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/RelatedCountValidation.java` |
| QP | `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/profile/SpringBootQueryPolicy.java` |
| DT | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/DtoRenderer.java` |
| CO | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/ControllerRenderer.java` |
| FE | `sir-semantic/src/main/java/io/kcg/sir/semantic/feature/Features.java` |
| S | `sir-toolchain-application/src/test/resources/`，后文样例路径相对此目录 |

## 一、逐项改动

| 编号 / v5 → v6 位置 | 改了什么 | 为什么改：报告出处 | 语言参考或源码依据 |
| --- | --- | --- | --- |
| 01 · P:19–20 → N:19–20 | 说明各段是独立片段/模板；补全实体、input、输出视图、子视图、枚举和错误声明，内置类型及 use 已展开声明除外；不能凭实体名推导同名 View。 | A 一 R014、R036–R040、三“复用扩展与声明闭合”；B 五“所有引用声明须闭合”；C 三“声明闭合与错误集合自查”。 | L:115、448–450、620–650；RE:1284–1292 检查未定义类型，RE:205–215 检查声明名。 |
| 02 · P:32 → N:33 | 保留 default 省绑定，补 unique/secret/versioned 字段不能有 default。 | A 一 R020、三“default/version/secret的互斥边界”。 | L:311；VA:998–1027；TY:429–442。 |
| 03 · P:33 → N:34 | scope 从软删除短例扩为统一读取范围，列清 load/find/exists/count/sum/avg/each/any、公开读取和 load 的失败方式；不能只过滤列表。 | A 一 R021、三“scope覆盖所有读取入口”；B 五 scope；C 三的通用合同完整性建议。 | L:342–350；ES:62–68、86–144、164–172：avg 与 sum 共用 SumStep/GroupedSumStep；VA:974–995 限制 scope 形状。 |
| 04 · P:34、57 → N:35、59 | 凭据认证 actor 必须是 subject 所指实体，而非令牌实体；每主体一种凭据，同软件不能混用认证方式。 | A 一 R023、R049；B 五“凭据 actor”；C 三“保持业务合同”。 | L:372–389、769；VA:913–961。 |
| 05 · P:36 后新增 → N:38 | 区分数值范围与必填；算术依赖在工作流前按 400 INVALID_REQUEST/原字段路径/NotNull 拒绝缺失和 null。普通范围约束、直接比较和直接绑定不触发该检查，不发明 where notNull。 | A 一 R099、三“数值/时间函数依赖输入的NotNull”；B 五数值缺值；C 三“解释数值必填与运算依赖”。 | L:1954；AR:15–38、57–78；ML:703–709；WR:49–77；G:222–225 和 VA:2451–2461 没有 notNull 约束写法。 |
| 06 · P:89 → N:38、83、93 | 精确列触发范围：不写“只有加减乘”，还含取负、daysBetween/max/min/addDays 的数值/时间输入及 repeat 次数；保留 patch changes 例外。 | A 一 R085、R097、R099；B 五数值缺值；C 三第一项要求按真实机制解释。 | L:1954、1965；AR:27、57–78；TY:606–614。patch 按 presence 降级而非统一要求所有 changes：L:436–446，VA:1664–1705。 |
| 07 · P:37 → N:39 | 分页须两个不同的直接输入字段；明确缺失和 null 都默认，只有拿去算术的参数才转为算术必填；不把分页类型误说成自带范围约束。 | A 一 R008、R028–R029；A 三“数值/时间函数依赖输入的NotNull”。 | L:1685、1954；TY:918–929；VA:683–726；QP:21；WR:479–487。 |
| 08 · P:38 → N:40 | 压缩 patch 段，补至少一个可变字段、全部可变字段直接绑定；主键不能 .present；外层主键名字随实体。未传保持、去规则类型、版本冲突机制均保留。 | A 一 R030、R032–R035；B 一 patch/三元表达式类及五历史规则说明。 | L:402–446；VA:1256–1325、1633–1705；DT:85–103；ML:621–650。 |
| 09 · P:39 → N:41 | 计算视图只用来源行纯标量/常量/允许函数，不能捕获 input/actor/now；嵌套最多两层且不可循环，保留来源限制。 | A 一 R037–R039；A 三“嵌套和计数响应的界限”。 | L:450、538、571–584；CV:15–49；VA:1183–1200、2088–2115。 |
| 10 · P:40 → N:42 | 跨行 count 必须连接子 Ref==item；不能捕获请求、actor、时钟或沿无界 List 返回。 | A 一 R041；A 三“嵌套和计数响应的界限”。 | L:472–513；RC:18–36、100–118。 |
| 11 · P:56 → N:58 | 输出类型补 Page<SumGroup<Ref<实体>>>，与分组语法一致；fails 只压缩并指明当前能力，不扩写旧规则。 | A 一 R047；B 五 fails；C 三“声明闭合与错误集合自查”。 | L:728–730、1639–1685；TY:878–896；VA:410–475。 |
| 12 · P:57 → N:59 | 补 delete 也算写步骤、atomic/readonly 互斥；认证是否需要按合同，令牌参数不自动等于当前身份。 | A 一 R050–R051、三最后一项；B 五“鉴权要求从需求来”。 | L:764–769；VA:358–375、2161–2164；身份来自 credential subject 而非任意输入参数：L:389。是否加认证属于输入合同要求，未声称编译器会替作者推导权限。 |
| 13 · P:58 → N:60 | 压缩路由段，补 DELETE 用查询参数、路径覆盖同名输入；patch 固定 PATCH；路径参数/方法形状限制。默认路由“必须省 at”改为“可省”，不把写作偏好当禁令。 | A 一 R053–R057；A 三“GET/DELETE输入与路径合并”。 | L:897–916；VA:246–349；ML:734–753；CO:155–179。 |
| 14 · P:59 → N:61 | 保留 create 决定 201/200；补不能把写能力改成 query 来改变成功码。 | A 一 R051、R058；B 四“运行反馈修复”及五结论。 | L:767、900；VA:358–364、429–475；CO:123–126、218–220。 |
| 15 · P:63–65 → N:65–67 | 主键/唯一字段加载语法压缩；补版本化行要更新须按主键加载、for update 锁至事务结束。 | A 一 R061–R063；A 三“读取和更新并发不由atomic自动解决”。 | L:987–1076；VA:419–425、1148–1180。 |
| 16 · P:67 → N:69 | 把 find 的普通谓词作用域统一到 exists/count/sum/avg/each：item 是本次查询根行，只读直接字段；右边不能读取 item，不能裸字段自比较或沿引用链隐式连接。请求侧可计算。 | A 一 R065–R067；B 五查询作用域；C 三“统一查询作用域说明”。 | L:1121、1544、1954；VA:635–668、745–791、472–495。 |
| 17 · P:68 → N:70、94 | 保留 Optional-or 筛选，并补完整空比较语义：==、!=、大小比较遇空均 false，not(==) 不等于 !=；判空不缩窄，普通空值与 patch presence 分开。 | A 一 R068、R099；A 三“Optional完整真值表与不缩窄”。 | L:1220、1956–1965；TY:951–982、1024–1032。 |
| 18 · P:68 → N:71 | 解释 any 内裸字段属于关联实体，item 仍是外层；补子 Ref==item、子主键==item.ref 两种连接、顶层 and 及可交换等号。禁止嵌套 any/任意跨表列比较/隐式多表 JOIN；同一行条件放同一个 any，不能漏合同需要的状态和归属条件。 | A 一 R069；B 五关联作用域及二“关联存在谓词漏状态条件”；C 三“讲完整 any 连接与查询根选择”。 | L:1289；TY:291–366；VA:2008–2085。状态和归属是否必要由输入合同决定，并非语言强制每个 any 都有固定 status/owner 字段。 |
| 19 · P:69 → N:72 | create 句保留不绑定主键/Version，补 Optional/default 可省；不加长反复违反的 Version 禁令。 | A 一 R071；B 五 Version；C 三“声明闭合与错误集合自查”。 | L:1297；VA:2166–2205。 |
| 20 · P:71 → N:74 | persist/else 两种用途原意不变，仅压缩并指向六节短清单，不再追加长反例。 | A 一 R073–R074；B 五 persist else。 | L:1317–1319；VA:1598–1630。 |
| 21 · P:72 → N:75 | **改正唯一错误**：一般守卫只保证写时条件，不保证所有并发请求只有一个成功；一次性来自写后条件失效。补非版本已加载行、唯一 update，以及先 for update 后可读目标其他字段的例外。 | A 一 R075–R077、四；B 五末段的守卫例外说明。 | 可执行依据 WR:897–936；限制 VA:1735–1805。L:1399 的“唯一成功”是状态转换场景简写，须结合 L:1401–1445，不照抄成一般保证。 |
| 22 · P:73 → N:76 | 计数例改用现成样例的错误名 Full，并说明容量未满时多次成功正常，不是唯一赢家。 | A 二 E48、一 R076–R078。 | L:1401–1445；VA:1809–1858；S/valid/multiplication.sir:115–121；WR:913–922。 |
| 23 · P:74–75 → N:77–78 | delete 直接执行、后面不 persist；exists/count 结果为 Flag/Serial，item 只在条件内。 | A 一 R079–R080；B 五查询作用域。 | L:1498–1544；VA:2248–2271；RE:897–909。 |
| 24 · P:76–77 → N:79–80 | 保留金额聚合模板，补空集合 0、分页分组 total 是组数。 | A 一 R081–R082；A 三“sum/avg/分组的返回与空集”。 | L:1625–1689；TY:648–692。 |
| 25 · P:78 → N:81 | month 补严格年月、区域时区、当地自然月转 UTC 半开区间。 | A 一 R083；A 三“自然月和时区”。 | L:1691–1712。 |
| 26 · P:79–80 → N:82–83 | each 补主键收尾、超限零执行、禁聚合/month、外部行累加界限；repeat 补 atomic、32 位非可选次数、0 起序号及同样体内限制。 | A 一 R084–R085；A 三“循环的边界和事务回滚”。 | L:1759–1761、1854、1903；VA:529–610；TY:606–614；AR:27。 |
| 27 · P:83 后新增 → N:87 | 只加一行四项自查：保留字、create 不绑 Version、persist else 适用范围、当前 fails 列全；各自原规则不重复加长。 | B 五“已有规则仍违反”的前四类；C 三“已有规则再堆措辞收益有限”。 | L:71–82、730、1297、1317–1319；VA:1598–1630、2166–2205；G:538–610。 |
| 28 · P:85 → N:89 | 将“引用行+父计数”的提示说成新行引用且修改父行的一般写序，保留 for update 替代。 | A 一 R089；A 三并发与持久化边界。 | L:1445；VA:1340–1372。 |
| 29 · P:86 → N:90 | 原先并发提示改成通用规则：atomic 不锁查询范围，多行冲突锁共同父行且所有竞争路径一致。 | A 一 R090；C 三“保持业务合同”，A 三并发边界。 | L:1001–1007、1592；VA:1515–1541；WR:897–936。 |
| 30 · P:89 → N:93 | 算术补底层宽度与字面量变宽；保留原函数，补参数/结果类型、Optional 禁用和函数依赖前置 NotNull。 | A 一 R095–R099；A 三“算术宽度与金额精确性”；C 三第一项。 | L:154、1933–1965；TY:1029–1036、1092–1140；AR:57–78。 |
| 31 · P:90 → N:95 | 保留两种 use 及禁止重复展开声明；“偏离就全部手写”改为先看功能参数能否组合满足合同，再决定是否手写。未新增语法。 | A 一 R101–R103、三“内置功能可组合参数而非全手写”。 | L:2101–2129、2166–2201；FE:103–183。 |
| 32 · P:12、31、35–36、41、55、84、87–88 → N:12、32、36–37、43、57、88、91–92 | 仅压缩已有正确说明，为新增缺口腾出篇幅；保留关键字、修饰顺序、SQL 名称、约束写法、错误码、声明顺序、主键时点、口令与授权例子。 | A 一 R005、R017、R025–R027、R042–R045、R087–R094 判正确；B 五不建议仅扩写已有规则。 | 对应原文 P；语义仍以 L:71–82、240、253、593、724、1299、2099 为准；SQL 命名 ML:109、559–561、657、1944–1962。 |
| 33 · P:92–93 → N:97–98 | 第七节仅改中性标题与称呼，保留完整文档和合同冻结；属于输入/输出协议，不把具体 namespace/路由常量说成语言保留值。 | A 一 R104–R105、四对任务要求与语言约束的区分；遵守本次“不提题目”的写作要求。 | 完整源文档 G:5–8、合法声明 G:55–68；合同冻结继承 P:93，是写作要求而非新增编译语义。 |

## 二、新增、替换例子的真实出处

保留的老片段不声称拼在一起就是可编译软件；中文实体/字段/步骤模板仍须按真实声明替换。新增或替换的具体例子优先使用已有源码，手册正文不塞文件路径，以免占用教师规则篇幅。

| v6 例子 | 真实出处 | 使用方式 |
| --- | --- | --- |
| `amount: input.price * input.quantity;` | S/valid/multiplication.sir:18–29 | 原样绑定，两个输入分别为 UnitPrice/Count；用来解释真实乘法依赖，不制造无意义运算。 |
| `any(Enrollment, course == item and status == EnrollmentStatus.ACTIVE)` | S/valid/course-enrollment.sir:84 | 原样正向连接；替换 v5 未给实体上下文的 active 布尔列。 |
| `any(Category, id == item.category and owner == actor)` | S/valid/decimal-group.sir:46 | 原样反向连接；item 为外层 Entry，id/owner 为 Category 字段。 |
| `update course when course.enrolled < course.capacity else Full { enrolled: course.enrolled + 1; }` | S/valid/multiplication.sir:115–121 | 原样语句，说明守卫容量计数可多次成功。 |
| 一次性状态转换的文字解释 | S/valid/guarded-transition.sir:62 | `used == false` 更新为 `used: true`，与普通计数区分；没有把这份软件作为专门提示塞入手册。 |
| Optional-or 筛选 | L:1220–1247；S/valid/g9-topic-conformance.sir:53 | 手册保留语言参考里的 kind 示例；valid 具有同形 Optional-or，字段名并非原样。 |
| scope/凭据/计数视图 | S/valid/crud-feature.sir:40–41；S/valid/bearer-credential.sir:24–29；S/valid/g9-topic-conformance.sir:133 | 保留 v5 现成片段，补其适用范围，不另编新实体级特例。 |

## 三、没有做的事

没有把 35 个“不完整”项目全部扩成百科，也没有只对四类反复违反的旧规则堆长提示。优先完成三份报告交集的数值缺值、查询关联、凭据身份、统一可见性、声明闭合，加上 Optional 真值表和唯一错误修正；其他内容尽量在原句内补短边界。

没有引入“非 Optional 一律必填”、新约束关键字、隐式 JOIN 或新的修复编排；没有调用模型验证 v6 效果。v6 仍为待审核草稿，不因引用现成样例就宣称它能提升生成通过率。
