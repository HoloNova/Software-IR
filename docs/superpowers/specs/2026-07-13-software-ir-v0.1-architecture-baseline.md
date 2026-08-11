# Software IR v0.1 架构基线

状态：已确认  
日期：2026-07-13

## 1. 本阶段要证明什么

Software IR v0.1 不尝试确定性生成任意真实软件，也不以“大模型直接生成 Java”为主线。

本阶段只验证：

> 对一个边界明确的 Java Web 后端子集，一份经过验证的 SIR 可以确定性地产生结构稳定、符合指定架构、能够编译和测试的 Spring Boot 项目。

v0.1 固定目标栈：

- Java 21；
- Spring Boot；
- MyBatis-Plus；
- MySQL；
- Maven；
- REST；
- 单体后端。

暂不进入主闭环：

- SLM 训练与推理；
- 已有项目增量修改；
- 多语言和多后端同时生成；
- 微服务、分布式事务和复杂工作流；
- 多 Agent 编排；
- 自动修复闭环。

SLM 的未来职责是把自然语言整理为 SIR；SIR 是否正确仍由确定性系统验证。

## 2. 一份完整 SIR 的内部边界

对用户而言，可以只有一份 `.sir` 文件；系统内部必须把它拆成三类信息。

### 2.1 Metadata

描述项目身份：

- SIR 版本；
- 软件名称；
- 命名空间；
- 使用的 Extension 及版本。

Metadata 不描述业务行为，也不决定如何生成 Java 类。

### 2.2 Core Semantic IR

描述软件语义：

- Entity、Enum、Input 和 Error；
- Capability 的输入、输出和执行者；
- 有限 Workflow；
- 持久化意图；
- 权限、事务和只读要求；
- Command/Query 暴露意图。

Core SIR 可以写 `persist goods`，但不能写 `goodsMapper.insert(goods)`；可以写 `requires atomic`，但不能写 `@Transactional`。

Value 属于长期 Core 类型体系，但不进入 v0.1 最小语言；等值相等、嵌套和持久化映射规则明确后再通过后续语言版本加入。

### 2.3 Target Profile

描述技术实现选择：

- Java 和 Spring Boot 版本；
- MyBatis-Plus；
- MySQL；
- 可选 Redis 能力；
- Maven 依赖目录；
- 包结构、命名和生成策略。

Core SIR 与 Target Profile 可以出现在同一文件，但不能互相替代。例如 `persist goods` 是业务语义，`persistence mybatis-plus` 是实现选择。

## 3. 系统处理链

```text
人工或 Agent 编写 SIR
        ↓
Parser：读取文本结构
        ↓
AST：保存用户原始写法和位置
        ↓
Symbol/Type Analysis：解析类型、变量和引用
        ↓
Validated Semantic Model：形成可信软件语义
        ↓
Target Router：选择唯一实现适配器
        ↓
Lowering：转换成 Spring Boot 项目设计
        ↓
Lowered IR：类、方法、注解、依赖和配置计划
        ↓
Generator：生成 Java、pom.xml、YAML、SQL 和测试
        ↓
Maven：解析依赖、编译和测试
        ↓
生成报告
```

Generator 只能读取已经验证的 Lowered IR，不能直接读取 DSL 文本，也不能自行猜测业务语义或依赖版本。

## 4. 六个核心模块

### 4.1 SIR Parser

输入 `.sir` 文本，输出 AST 和语法诊断。它只判断文本结构，不生成代码。

### 4.2 Semantic Analyzer

建立符号表和类型信息，检查：

- 重复定义；
- 未定义类型、变量和枚举值；
- 输入输出不匹配；
- Workflow 漏返回或使用不可用变量；
- 只读能力修改数据；
- 持久化不存在或不允许持久化的对象。

### 4.3 Compilation Context

这是“公共黑板”的正式形态，记录已经确定的事实：

- 目标栈及版本；
- 依赖清单；
- 类型、符号和变量；
- 约束；
- 已规划的生成制品；
- 来源位置与诊断。

它不是 Agent 可以随意书写的共享笔记。每个阶段产生新的不可变快照：

```text
Parsed Context
→ Resolved Context
→ Validated Context
→ Lowered Context
```

后续阶段不能悄悄改变前面已经确定的事实。

### 4.4 Target Router 与 Profile Registry

根据 Target Profile 选择唯一适配器，例如：

```text
framework    → SpringBootAdapter
persistence  → MyBatisPlusAdapter
database     → MySqlAdapter
cache        → RedisCacheAdapter（仅在声明具体缓存语义时）
```

适配器必须声明它支持什么、依赖什么、与什么冲突。一个能力槽位只能选择一个实现，例如 MyBatis-Plus 与 JPA 不能同时成为主持久化适配器。

### 4.5 Lowering

把 Core 语义与 Target Profile 结合，产生具体工程设计。例如：

```text
Entity Goods + persistent + MyBatis-Plus
→ Goods.java + GoodsMapper.java + 表映射

Capability PublishGoods + REST + atomic
→ Controller 方法 + Service 方法 + 事务边界
```

Lowering 可以出现 Spring、MyBatis-Plus 和 Redis 概念，但 Core Semantic IR 不出现这些实现类名。

### 4.6 Generator 与 Build Verifier

Generator 根据 Lowered IR 写出代码和配置。Build Verifier 使用 Maven Wrapper：

- 解析经过 Profile 选定的依赖；
- 编译；
- 执行测试；
- 收集并分类错误。

系统不自行实现 Maven 下载器，也不接受 SIR 中的任意下载 URL。默认由 Maven 自动解析依赖，同时生成离线环境和失败处理指南。

## 5. 确定性边界

v0.1 中以下内容必须确定：

- 项目结构和包名；
- 类型、字段和公开接口；
- Maven 依赖及经过测试的版本组合；
- Mapper、Service、Controller 的存在和签名；
- 权限和事务边界；
- 数据库映射；
- 生成文件排序和内容；
- 编译及验收结果。

同一 SIR、Profile 和 Generator 版本应产生字节级一致的文件。该性质必须通过测试验证，不能只作为口头承诺。

长期可以允许模型补充复杂的局部方法体，但模型不得自行修改依赖、公开接口、事务边界和其他受保护结构。该混合生成模式不进入 v0.1 主闭环。

## 6. Redis 的正式定位

“加入 Redis”有两种不同含义。

### 6.1 只把 Redis 当成已选组件

如果只是生成依赖和连接配置，工作量确实较小：

```text
cache redis
```

可以确定性产生：

- Redis starter 依赖；
- host、port 等配置占位；
- RedisTemplate 或 CacheManager 基础配置。

但这只能证明系统会安装 Redis，不能证明软件为什么需要 Redis，也没有可验收的业务行为。

### 6.2 把 Redis 当成可生成的软件能力

必须先声明具体语义：

- 查询结果缓存；
- Session 存储；
- 分布式锁；
- 原子计数器；
- 排行榜；
- 消息发布订阅。

不同语义的键设计、序列化、过期、失效、一致性、失败降级和测试完全不同。因此不能用一个模糊的 `use redis` 覆盖全部行为。

### 6.3 v0.1 决策

Redis 不作为核心主链的必选组件，但允许作为第一个可选 Extension。第一版只支持一个明确能力：查询缓存。

示意语义：

```text
capability QueryGoods {
  requires cacheable ttl 5m
}
```

Target Profile 再决定使用 Redis 实现。验收至少包括：

- 生成所需依赖和配置；
- 相同查询能够命中缓存；
- 商品写入后相关缓存失效；
- Redis 不可用时的行为由 Profile 明确为失败或回源，不能保持未定义。

如果主编译链尚未稳定，该 Extension 延后实现，但接口和测试标准保留。这不是认为 Redis 不重要，而是避免“增加了依赖”被误认为“完成了缓存语义”。

## 7. 第一条 Gold Example

校园二手交易系统至少包含：

- User、Goods、Order；
- 发布商品；
- 查询在售商品；
- 下单；
- 下单时检查商品状态和买卖双方；
- 创建订单并把商品改为已售；
- 下单过程具有事务性。

它必须覆盖跨实体状态变化，不能只有 Entity CRUD。

Redis 查询缓存作为可选扩展示例，不是主示例通过的前置条件。

## 8. 第一阶段验收标准

主链完成必须同时满足：

1. 合法 SIR 可以解析并形成 Semantic Model；
2. 未定义类型、变量和非法 Workflow 能在生成前报告；
3. Lowered IR 能明确展示将生成的类、方法、依赖和配置；
4. 相同输入重复生成相同文件；
5. 生成项目通过 Maven 编译和规定测试；
6. 实现中不存在针对 CampusMarket 名称的硬编码；
7. Generator 不读取原始 DSL；
8. Core Model 不依赖 Spring 或 MyBatis-Plus 类。

## 9. 项目推进方式

每次只推进一个可验证阶段：

```text
冻结一小段规范
→ 项目负责人能够复述
→ 拆成 Agent 任务和负面测试
→ 实现
→ 项目负责人亲自运行示例
→ 复盘后进入下一段
```

第一轮顺序：

1. 冻结最小 SIR 元素；
2. Parser 与 AST；
3. Symbol Table 和类型检查；
4. Validation；
5. Lowered IR；
6. Spring Boot/MyBatis-Plus/MySQL 生成；
7. Maven 编译和业务测试；
8. 主链稳定后实现 Redis 查询缓存 Extension；
9. SIR 和评测集稳定后再引入 SLM。

## 10. 关键架构决策

- 对用户是一份完整 SIR，对系统内部是 Metadata、Core Semantic IR 和 Target Profile 分层；
- Agent/SLM 可以产生 SIR，但不能判定 SIR 正确；
- Compilation Context 是阶段化、不可变的可信事实库；
- 技术分支通过 Profile Registry 和适配器选择，不使用巨型条件分支；
- 依赖由经过测试的 Profile 决定，由 Maven Wrapper 解析；
- v0.1 做受限语义范围内的确定性闭环；
- Redis 作为具体语义 Extension，而不是无语义的组件标签；
- 复杂业务的模型局部填充属于后续能力，不改变确定性控制平面的职责。
