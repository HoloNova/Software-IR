# ADR-003：工具链应用层拥有安全工程应用语义

## 状态

Accepted for minimal implementation（2026-07-17）

## 背景

`SpringBootGenerator` 已是只消费 `SpringBootLoweredModel` 并返回不可变 `GeneratedFile` 集合的纯函数。下一阶段需要统一编排 SIR 读取、Parse、Semantic、Lowering、Generate，并把完整结果安全应用到显式目录。

若把磁盘、覆盖或进程行为加入 Generator，会破坏 ADR-002；若让调用方各自写盘，则路径逃逸、链接、冲突和部分失败语义无法形成统一保证。

## 选项

1. Generator 直接写盘并管理覆盖。
2. 单独建立应用层，消费全部公开编译 API 和内存生成结果。
3. 直接在 CLI 中实现编排与文件操作。

## 决定

采用选项 2，新增 `sir-toolchain-application`：

- Generator 继续是纯函数；
- 应用层拥有 SIR 读取、统一编排、输出根验证、冲突策略、staging、原子移动、备份、清理、回滚和执行 manifest；
- 默认 `FAIL_IF_EXISTS`，覆盖必须显式选择 `REPLACE_EXISTING`；
- 所有普通失败返回结构化 `ToolchainResult.Failure`；
- 失败状态明确区分 `NO_CHANGES`、`ROLLED_BACK` 和 `RECOVERY_REQUIRED`；
- 应用层只调用各阶段公开 API，不读取 internal 或绕过 Validator。

## 理由

这使副作用和纯编译阶段形成清晰事务边界，同时让路径与冲突规则只有一个可测试实现。当前只有本地文件系统和 Spring Boot Target，因此单模块比提前拆分 SPI 更简单。

## 权衡

- 多文件写入在一般文件系统上不存在崩溃级全局原子事务；根已存在时采用逐文件原子移动和补偿回滚，并诚实报告恢复失败。
- 未来出现第二存储后端或远程工作区时，可能需要从模块中抽取 application API/port。
- 本阶段不提供 CLI，调用方先直接使用 Java API。

## 后果

- 正面：统一编排、默认不覆盖、可审计 manifest、普通失败不留下半写入结果。
- 负面：应用层直接依赖当前五个编译模块；事务实现需要平台路径测试。
- 缓解：依赖只指向公开 API；路径策略采用跨平台最严格公共子集；真实 Windows 与离线 Maven 验收单独报告。

## 重新评估触发条件

- 出现第二个 Generator Target；
- 出现非本地文件系统宿主；
- 引入 Project Symbol Graph 与 owner-aware 增量更新；
- 需要崩溃恢复日志或跨进程并发锁。
