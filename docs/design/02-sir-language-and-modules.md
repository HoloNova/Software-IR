# 第二步：SIR 语言、业务契约与模块组合

日期：2026-09-08。状态：推荐设计草案。依赖[共享契约](00-shared-contracts.md)和[产品范围](01-product-and-delivery.md)，由[编译生成设计](03-compiler-and-generated-backend.md)实现这些语义。

本文件规定主设计目标语义。当前 Grammar 是单个 software 下的 enum/entity/input/error/capability 及有限 workflow；下面的 valueType/query/operation/policy/module/application/lifecycle、@id 和 nodeKey 均为目标形式，不是当前可执行示例。正式实现必须覆盖业务语义与反例，不能以能解析作为完成门。[源码与测试依据](implementation-baseline.md)

G1 先在单文件链中增加课程业务切片；G2 再完成本章多文件模块与持久身份。保留现有 capability/workflow 到目标 operation/query 的语义映射，正式语法可经版本化演进确定，不要求仅为换术语重写编译器。

## 1. 应用源的组织

```text
sir/
  project.sir              # 项目、模块实例、版本与 Profile 引用
  modules/identity.sir     # 账号、角色、身份映射
  modules/course.sir       # 课程与查询
  modules/enrollment.sir   # 报名关联、操作和约束
  lifecycle.sir            # 数据库新建/更新意图与受控回填
  modules.lock.json        # 确切模块版本和内容摘要
```

文件路径是源定位，不是业务身份。模块之间通过公开声明和显式 import/binding 连接；跨文件移动不自动改变 SymbolId。源快照有明确文件清单，不动态扫描依赖目录寻找同名声明。

声明类别为 entity、enum、valueType、query、operation、policy、module、application 和 lifecycle。框架扩展使用已登记的扩展名；未知扩展为编译错误，不忽略后继续生成。

## 2. 稳定身份与名称

当前 AstNodeId 由 SourceId、结构路径、名称或序号构成；SymbolId 由软件名、声明种类及名称等编码。它们支持现有编译链中的确定性定位，但不保证重命名、跨文件移动或插入步骤后身份不变。以下为 G2 的目标契约，不能直接用来解释旧 Bundle 中的 ID。

每个可跨阶段引用的声明拥有持久化 declarationId，每个模块实例拥有 moduleInstanceId。编辑器或 Agent 创建新声明时取得新 ID 并写入 SIR；重命名保留 ID；删除后再创建新的同名声明也不得隐式取得旧身份。

SymbolId 的构成以共享契约为准。名称用于阅读和一次 Resolve，名称变化影响的 API/Java/SQL 映射分别评估。重复 ID、模块绑定歧义、同作用域重复名称立即报错。

身份格式升级需显式版本和旧声明到新声明的一对一映射，并验证引用闭包；旧 Graph、Bundle、ChangePlan 不静默重解释。G2 增补同名删除后重建、旧格式拒绝/转换、重复 ID 和重命名后保持数据库物理映射的验收；G3 迁移依赖此门通过。

引用点由稳定的拥有者声明/节点、ReferenceRole 和字段槽位标识。operation 的步骤、重复查询条件等可重复节点需要持久化 nodeKey；生成 AST 时据此形成 AstNodeId，不能用系统时间、随机数或当前行号。固定槽位中的引用改变目标后，保留引用点身份并改变 binding。

完整语法必须能保存这些 nodeKey。简短示例可以省略非重点节点键，但不可据此让 Parser 静默生成不稳定 ID。纯排版变化只改变 SourceSpan，不改变声明和引用点身份；修改节点结构可以合理改变相应引用点身份。

首轮标识符使用受约束 ASCII，中文用于 label、描述与字符串值。关键字、标识符区分大小写，所有大小写处理显式使用 Locale.ROOT。业务字符串值不继承标识符比较规则。

## 3. 类型系统

| 类型 | 默认语义 | 重要限制 |
| --- | --- | --- |
| String(max) | Unicode 文本，按码点计长度 | max 必须有界；不隐式 trim 或折叠大小写 |
| Int / Long | 有符号整数 | 不把小数静默截断成整数 |
| Decimal(precision, scale) | 精确十进制 | 超出范围报错；默认不允许隐式舍入 |
| Bool | true/false | 不接受任意字符串真假值 |
| Date | 无时区的日历日期 | ISO 日期输入，不当作午夜时间点 |
| Instant | 时间线上的时刻 | 必须有明确偏移，规范输出 UTC |
| Enum | 显式稳定值集合 | 值身份与展示名分开；未知值报错 |
| Id<Entity> | 某实体不可变技术身份 | 不能把 Course ID 绑定到 Student 引用 |
| Ref<Entity> | 对指定实体的关系引用 | 需要基数、可空与关系行为 |
| Optional<T> | 字段或参数可无值 | null、缺席和空集合各有独立语义 |

首轮不开放任意泛型、继承层次、反射和用户定义函数执行。必要的值对象可组合基础类型与纯约束，不含任意副作用。

业务编号与技术主键分开。课程号可变规则需要显式操作，主键不可改；主键的 API 表示采用十进制字符串，Decimal 也采用精确十进制字符串。值比较策略写入 Profile，课程版文本默认区分大小写；编号可额外声明禁止首尾空白，以避免隐式清洗。

## 4. 实体与字段权限

实体字段包含类型、可空性、默认值、值约束及访问属性。访问属性至少区分 serverManaged、createWritable、updateWritable、responseVisible。密码散列仅服务端维护且永不进入业务响应。

默认值区分新建记录默认值与新增字段的旧数据回填；前者不能自动用于后者。时间戳由运行时服务端提供，不能在 Generator 内写入生成时的时间。

字段声明由 Constraint 定义业务性质，Target 决定 Java 注解和数据库约束。唯一性是数据库约束与运行时错误映射共同保证，不能仅靠生成一条“查重查询”。

## 5. 课程示例的完整业务对象

| 实体 | 关键字段 | 约束 |
| --- | --- | --- |
| Account | id、username、passwordHash、role、enabled | username 唯一；role 为 ADMIN/STUDENT；凭据不返回 |
| Student | id、account、studentNo、name、email?、version | account 一对一且唯一；studentNo 唯一 |
| Course | id、code、name、description?、capacity、enrolledCount、version | code 唯一；capacity > 0；0 <= enrolledCount <= capacity |
| Enrollment | id、student、course、status、createdAt、version | student + course 唯一；status 为 ACTIVE/CANCELLED |

身份模块提供当前 Principal 到 Account/Student 的正式映射。客户端提交 studentId 不具有授权意义。课程容量在报名能力启用时纳入完整业务验收；基础 CRUD 阶段可以先不开放报名操作。

上表描述目标能力；固定实验的版本切分见 07，r0001 不包含 email，r0002 才新增它。含 email 的 PATCH 示例用于 r0002 及以后，不能据此允许 r0001 接受未声明字段。

如下示例展示字段和 query 的表面形式；正式节点键及完整 Account/Student 定义按上表补齐：

```text
entity Course @id("course") {
  id: Id<Course> generated
  code: String(max=32) required unique @id("course-code")
  name: String(max=100) required @id("course-name")
  description: String(max=500) optional @id("course-description")
}

query SearchCourses @id("search-courses") {
  inputs { keyword: Optional<String>; page: Int=1; size: Int=20 }
  from Course
  when keyword.present: name containsLiteral keyword
  output { id; code; name }
  order { code ascending; id ascending }
  pagination { page; size; maxSize=100 }
}
```

## 6. CRUD 的精确语义

实体本身不开放接口；operation 显式选择 create、get、patch、delete 或 domainAction。Create、Patch、Detail 和 ListItem 拥有独立 DTO 契约，不把 Entity 直接作为请求体。

PATCH 请求采用明确的更新载荷：expectedVersion 与 changes。changes 中字段缺席保持旧值，显式 null 清空且必须可空，出现值则替换。空 changes 返回 400；未知字段、不可写字段、试图写 id/role/status 等受控字段返回 400 或明确业务拒绝，不静默忽略。

```json
{
  "expectedVersion": "3",
  "changes": {
    "name": "张同学",
    "email": null
  }
}
```

上例只允许作用于声明的 Student PATCH 操作；version 由服务端匹配并递增。权限范围检查通过后，先以数据库当前记录组成候选完整对象，再验证跨字段约束，最终以 id + expectedVersion 条件写入。版本冲突不能返回成功并丢弃变化。

删除必须显式声明。默认外键 RESTRICT，不隐式级联删除。归档是可观察的状态变化，其查询过滤与唯一性是否释放需明确；课程版归档不释放业务编号。Enrollment 的取消保留记录，再次报名使用重新激活动作。

## 7. 查询、分页与投影

Query 由参数、根实体、类型化 Predicate 树、投影、排序和分页组成。可用 Predicate：eq、ne、lt/le/gt/ge、in、containsLiteral、isNull、and/or/not。运算符必须与类型兼容，null 比较使用独立节点。

可选参数的条件省略只发生在声明的 when-present 节点，不对所有 null 参数统一移除。in 的空集合固定解释为 false；集合长度最多 100。字符串包含按字面量处理 `%`、`_` 与转义字符；所有值参数绑定。

分页默认 page=1、size=20，size 范围 1..100，首轮 page 最大 10000。非法值返回 400，不自动夹紧。total 是授权范围和全部过滤条件下的根实体数量；超出最后一页返回空 items 和实际 total。

排序只允许 query 声明字段；用户可选择的排序方向也受白名单约束。固定追加唯一主键；可空排序字段首轮不开放可变 null 顺序，Profile 统一 null-last 并显式降低为目标 SQL。没有允许排序输入时，调用者不能额外指定 sort。

关联查询只返回声明的投影，最大默认深度 2；不得递归返回双方完整 Entity。一对多过滤采用存在性语义，不把根记录乘成多条；具体 SQL 计划由 03 决定。

## 8. 关系与模块边界

每条关系指定拥有者字段、目标实体、ONE/MANY、可空性和删除行为。一对一要求唯一外键；多对多由显式关联实体表达。首轮不做嵌套多实体自动保存；每个业务命令明确其写入集合。

模块只导出公开实体身份、操作、query 或能力接口。其他模块不得依赖其私有字段。共享实体有唯一归属；多个模块使用 Account 必须绑定到同一个导出，不能自动合并重复声明。

首轮模块组合为明确导入、实例参数和能力绑定，尚不要求完整通用泛型语言。参数化报名模块可要求 Participant 和 Enrollable 两种契约，实例化时验证字段/操作与约束匹配。模块定义版本固定，应用实例绑定参与语义与缓存摘要。

引用边形成可验证的依赖图，首轮拒绝模块依赖环。单模块内实体互相关联可以存在，但建表和外键操作顺序由数据库设计处理。未来组装界面输出同一份模块绑定 SIR，不拥有直接生成 Java 的旁路。

## 9. 业务动作、约束与事务

Operation 描述输入、输出、授权、前置条件、受限步骤和事务语义。首轮步骤集合包括读取实体、构造候选值、验证条件、创建记录、条件更新、状态转换、返回投影；不允许任意循环、网络调用、脚本或源代码字符串。

流程是有限状态机：每个动作声明来源状态、目标状态和授权。不能从普通 PATCH 修改流程状态。有限表达式用于比较、逻辑组合和边界算术；Type 验证类型，Validate 验证流程与副作用组合。

报名用例的领域契约为：账号可用、当前学生存在、课程存在且可报名、没有 ACTIVE 重复记录、有剩余名额；一次原子业务操作增加有效报名并占用一个名额。取消操作把 ACTIVE 改为 CANCELLED 并释放一个名额。重复取消返回 409，不重复释放；重新激活需重新验证名额。

SIR 描述原子性和冲突条件，Lowering 选择锁/条件更新实现。实体 version 保护一般 PATCH，不能单独替代跨实体事务。业务条件失败、数据库冲突和非预期异常均不得提交半成品。

## 10. 认证、授权与 HTTP 契约

默认认证为单实例会话。平台账号与生成后端 Account 独立；用户无需平台登录态才能登录本地业务后端。登录/退出、当前身份、CSRF 获取与错误响应由标准身份能力提供，安全配置由 Profile 负责。

授权由显式角色与数据范围组合。未声明公开的 operation 默认不开放匿名访问。数据范围进入数据库查询或事务中的检查，不在读取所有用户数据后仅靠前端隐藏。

| 情况 | HTTP | 默认语义 |
| --- | --- | --- |
| 参数、类型、分页、未知字段错误 | 400 | 返回字段路径和稳定错误码 |
| 未登录或会话失效 | 401 | 不重定向为 HTML 登录页 |
| 角色不允许 | 403 | 不执行查询或写入 |
| 授权数据范围内没有记录 | 404 | 数据归属过滤后未找到；不泄露他人记录存在性 |
| 唯一性、版本、状态或名额冲突 | 409 | 返回可理解的冲突类型 |
| 非预期错误 | 500 | traceId，隐藏 SQL 与内部凭据 |

此处细化基础框架的归属策略：角色拒绝为 403，按 owner 过滤后没有资源为 404。API 文档和自动验收使用相同约定。

## 11. 诊断阶段归属

| 错误类型 | 唯一阶段 | 例子 |
| --- | --- | --- |
| 非法语法、UTF-8 或词法 | Parser | 未闭合字符串 |
| 名称/导出不存在、重复身份 | Resolve | Course 引用找不到实体 |
| 参数与表达式不兼容 | Type | Date 与 Long 比较 |
| 流程、关系、权限或操作矛盾 | Validate | 一对一缺唯一约束、无允许来源状态 |
| Profile 不支持某种有效 Core 能力 | Lowering | 受支持关系上的未提供目标映射 |
| 数据库变更潜在丢数据 | 迁移规划 | 删除持久化字段 |
| 实际重复数据、schema 漂移 | 本地部署检查 | 新唯一约束与旧记录冲突 |

阶段失败后不靠空对象继续生成。后续阶段不能为了方便再次解析名称或把用户错误抛成 NPE。相同错误只由唯一阶段输出，相关引用通过 relatedLocations 说明。

## 12. 本步验收

语言验证需要正反例：跨文件重命名保留身份、重复 ID 拒绝、引用绑定唯一、PATCH 三态、组合查询与空集合、关系约束、只读字段、归属过滤、重复报名与非法状态。关键错误码、数量和 SourceSpan 都需验证。

模块验证至少组合出选课与活动报名两个模型；它们共享模块定义但不共享项目/数据库身份。一个模块升级不能悄悄删除另一模块拥有的字段。

设计接受后，再写正式语法和规范化数据结构。若某能力暂不实现，应从发布 Profile 的支持列表移除并给出诊断，而不是保留语法却生成无行为的代码。
