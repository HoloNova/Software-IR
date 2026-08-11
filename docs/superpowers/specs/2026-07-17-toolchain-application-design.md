# KCG-Code 工具链工程应用层设计

**状态：** Accepted for minimal implementation
**日期：** 2026-07-17
**范围：** 统一编排现有 v0.1 编译管线，并把内存生成结果安全应用到显式工程目录

## 1. 背景与目标

当前管线已经完成：

```text
SIR -> Parser -> Semantic -> Spring Boot Lowering -> Deterministic Generator
```

Generator 的输出是不可变 `GeneratedFile` 内存集合。它必须继续保持纯函数；读取 SIR、处理工作区路径、冲突策略和磁盘事务属于新的工程应用层。

本阶段交付一个最小端到端垂直切片：调用方显式给出 SIR 文件、稳定 SourceId、输出根和冲突策略，应用层依次调用全部公开阶段，只有所有编译阶段成功且文件系统预检通过后才应用完整文件集。

## 2. 方案比较与决定

### 方案 A：单一应用层模块（采用）

新增 `sir-toolchain-application`。公开 API、统一编排和文件事务位于同一 Maven 模块；文件系统实现保留在 internal 包。

- 优点：只有一个 Target 时依赖和失败语义最直接；没有提前创建无第二实现者的 SPI。
- 缺点：未来出现其他宿主或存储后端时，可能需要抽取 application API。
- 采用原因：满足当前垂直切片，复杂度最低，未来抽取是机械迁移。

### 方案 B：application-api + filesystem-adapter

- 优点：端口/适配器边界明显，便于替换存储。
- 缺点：当前只有本地文件系统一个实现，会产生未被真实需求证明的接口和模块。

### 方案 C：直接实现 CLI

- 优点：用户可立即通过命令行调用。
- 缺点：参数、退出码和终端展示会扩大范围，并可能掩盖尚未冻结的应用结果和事务协议。

CLI 不属于本阶段；未来只能消费本设计的应用 API。

## 3. 模块边界与依赖方向

```text
sir-toolchain-application
  -> sir-parser                   (SirParser, SirSource, diagnostics)
  -> sir-semantic                 (SirSemanticAnalyzer)
  -> sir-lowering-api             (LoweringAnalysis, diagnostics)
  -> sir-lowering-spring-boot     (SpringBootTargetLowering)
  -> sir-generator-spring-boot    (SpringBootGenerator, GeneratedFile)
```

约束：

1. 现有模块不反向依赖应用层。
2. 应用层只导入各模块公开包；不读取 Generator internal。
3. `SirParser` 增加公开静态工厂，隐藏 `internal.DefaultSirParser`。不修改 Parser POM、ANTLR 配置或生成源码。
4. Generator 不增加文件系统、进程、覆盖策略或工作区状态。
5. 文件系统类型不进入 Core Semantic IR 或 `sir-lowering-api`。

## 4. 公开 API 草案

包：`io.kcg.sir.application.api`

```java
public final class ToolchainApplication {
    public ToolchainResult execute(ToolchainRequest request);
}

public record ToolchainRequest(
        Path sourceFile,
        SourceId sourceId,
        Path outputRoot,
        ConflictPolicy conflictPolicy) {}

public enum ConflictPolicy {
    FAIL_IF_EXISTS,
    REPLACE_EXISTING
}

public sealed interface ToolchainResult {
    record Success(ExecutionManifest manifest,
                   List<ExecutionDiagnostic> diagnostics)
            implements ToolchainResult {}

    record Failure(ExecutionStage failedStage,
                   FailureDisposition disposition,
                   List<ExecutionDiagnostic> diagnostics)
            implements ToolchainResult {}
}
```

辅助类型：

- `ExecutionStage`：`READ`、`PARSE`、`SEMANTIC`、`LOWERING`、`GENERATION`、`PREFLIGHT`、`WRITE`、`ROLLBACK`。
- `FailureDisposition`：`NO_CHANGES`、`ROLLED_BACK`、`RECOVERY_REQUIRED`。
- `ExecutionDiagnostic`：稳定 code、stage、severity、message，以及可选 SourceSpan、LoweredNodeId、relativePath。
- `ExecutionManifest`：规范化绝对输出根、显式 conflict policy、按 Generator 顺序保存的不可变 `AppliedFile`。
- `AppliedFile`：相对路径、`CREATED/REPLACED`、UTF-8 byte count、SHA-256、artifact id、可选 symbol id。

普通文件、输入、路径和冲突错误由 `execute` 返回 Failure。构造器只对 null 等编程错误执行不变量检查。

## 5. 编排流程

```text
Validate request paths
  -> strict UTF-8 read
  -> SirParser.parse
  -> SirSemanticAnalyzer.analyze
  -> SpringBootTargetLowering.lower (包含输入与 Lowered IR Validator)
  -> SpringBootGenerator.generate (再次验证 Lowered IR，纯内存)
  -> portable path + conflict + link preflight
  -> stage every file
  -> atomic publish / transactional commit
  -> immutable manifest
```

每个阶段只在前一阶段 Success 时运行。Parse、Semantic、Lowering 和 Generation 的原始稳定错误码被保留，并映射到统一诊断；成功阶段的 warning/info 也按阶段顺序保留。

## 6. 路径与威胁模型

### 6.1 信任边界

- 用户显式提供的 source path、SourceId、output root 和 conflict policy 是不可信输入。
- `GeneratedFile` 来自受信 Generator，但应用层仍独立校验，避免未来其他调用方或契约回归绕过安全边界。
- 同时修改输出树的其他进程不受应用层控制；提交前必须重新检查，仍存在操作系统级 TOCTOU 残余风险。

### 6.2 必须拒绝

1. 非绝对 output root，避免依赖当前工作目录。
2. output root 或其已存在父链中的符号链接。
3. 绝对生成路径、反斜杠、空段、`.`、`..` 或归一化后越过 root 的路径。
4. 规范化或 `Locale.ROOT` 大小写折叠后重复的目标路径，保证 Windows 与大小写敏感平台结果一致。
5. Windows 非法字符、控制字符、尾随空格/点、设备保留名（如 CON、NUL、COM1、LPT1）。
6. 目标或其已存在父目录是符号链接。
7. `REPLACE_EXISTING` 下的目录、链接或非普通文件。

### 6.3 链接检查

预检用 `NOFOLLOW_LINKS` 检查根、每个已有父组件和每个目标。创建目录时逐层检查；每次提交前再次检查目标父链和目标。应用层不通过 `toRealPath()` 跟随未知链接来“证明”安全。

## 7. 冲突策略

### FAIL_IF_EXISTS（默认）

任何目标已存在都令整个执行在 PREFLIGHT 阶段失败，`FailureDisposition.NO_CHANGES`。必须先扫描全部目标，再创建 staging 或修改输出文件。

### REPLACE_EXISTING（显式）

只允许替换现有普通文件。manifest 对每个文件记录 `CREATED` 或 `REPLACED`。所有覆盖在提交前已知，且旧文件先原子移动到事务备份区；覆盖行为因此可审计、可回滚。

本阶段不实现按 glob、按 owner 或交互确认的混合策略。

## 8. 原子性、清理与回滚

### 输出根不存在

1. 在输出根父目录同一文件系统内创建唯一事务目录。
2. 在 staging 子树完整写入全部内容并计算 manifest。
3. 用 `ATOMIC_MOVE` 把完整 staging 子树发布为 output root。
4. 原子移动不受支持时失败并清理，不降级成非原子目录复制。

这样不会暴露半棵生成树。为到达父目录而新建的空目录在失败时逆序清理。

### 输出根已存在

1. 在同卷事务目录写入全部 staging 文件。
2. 为覆盖目标先原子移动旧文件到 backup。
3. 逐文件用 `ATOMIC_MOVE` 发布 staged 文件。
4. 任一步失败时，按逆序删除新文件并恢复 backup；移除本事务创建的空目录。

常规、可恢复 I/O 故障返回 `ROLLED_BACK`，并保证目标文件集恢复到执行前。文件系统可能拒绝回滚或进程可能在提交中崩溃；这种无法由 Java NIO 提供全局事务保证的情况返回 `RECOVERY_REQUIRED`，保留恢复材料并给出明确诊断，绝不返回 Success。

成功后清理事务目录。若仅清理临时材料失败，输出文件仍是完整已提交状态，Success 携带 cleanup warning；不把有效工程伪装成未写入。

## 9. 确定性

- SIR 内容严格按 UTF-8 解码，非法字节返回 READ Failure。
- 不从默认 Locale 构造路径键；大小写折叠使用 `Locale.ROOT`。
- manifest 顺序与 Generator 文件顺序一致，集合全部不可变。
- 内容摘要固定 SHA-256、小写十六进制；byte count 以 UTF-8 字节为准。
- 临时目录名不进入公开结果或生成内容。
- Windows 路径规则在所有平台统一预检，避免同一生成集只在部署到 Windows 后才冲突。

## 10. 错误码域

应用层新增 `SIR-APP-*`：

- `SIR-APP-REQUEST-*`：请求与绝对路径。
- `SIR-APP-READ-*`：文件不存在、非普通文件、I/O、非法 UTF-8。
- `SIR-APP-PATH-*`：越界、非法 Windows 名、重复、链接。
- `SIR-APP-CONFLICT-*`：默认拒绝覆盖或不可替换类型。
- `SIR-APP-WRITE-*`：staging/atomic move/commit 失败。
- `SIR-APP-ROLLBACK-*`：回滚不完整，需要恢复。
- `SIR-APP-CLEANUP-*`：提交后临时材料清理 warning。

下游 `SIR-*` 码不重写，仅增加所属 `ExecutionStage`。

## 11. 测试矩阵

| 层次 | 最小验收 |
|---|---|
| 编排失败 | 非法 SIR 返回 PARSE Failure，且不创建 output root |
| 编排成功 | campus-market 从真实 SIR 经过全部公开阶段写出完整工程 |
| Semantic/Lowering | 对应失败码保留且不触碰文件系统 |
| 冲突 | 默认策略在任一目标存在时全量失败且零修改 |
| 显式覆盖 | 普通文件被替换，manifest 标记 REPLACED；无关文件不变 |
| 路径 | dot、越界、Windows 非法名、保留名、大小写重复全部失败 |
| 链接 | root/父目录/目标链接失败；平台不支持真实链接时用可注入探针验证决策逻辑并单独报告集成限制 |
| 原子性 | staging 失败零修改；第 N 次 commit 故障后逆序回滚；回滚故障返回 RECOVERY_REQUIRED |
| 公共契约 | Success/Failure/manifest/diagnostic 集合不可变、顺序稳定 |
| 确定性 | 重复执行到两个空根，manifest（除根路径）与内容摘要一致；土耳其 Locale 一致 |
| 父工程 | 离线 `clean verify`，统计各模块测试数 |
| 原始源码 | JavaCompiler 直接编译原始 GeneratedFile 内容，延续现有验证 |
| 真实工程 | 对应用层写出的原始工程执行精确生成 POM 的离线 Maven compile/test |
| Spring 启动 | 只有真实 Spring Context/进程启动成功才能报告成功；缺少数据库运行配置时报告未验证，不以 stub 或 compile 代替 |

## 12. 明确非目标

- CLI、GUI、HTTP 服务或 Maven/Gradle 进程执行 API；
- Redis Extension、算法能力、前端契约、Change SIR、Project Symbol Graph、Java 反向解析；
- 用户代码保护区、增量 owner-aware 更新或任意 Java 源码 escape hatch；
- 默认数据库账号、密码或隐式运行时配置；
- 崩溃恢复日志和跨文件系统事务。

## 13. 分阶段实施计划

1. 添加 Parser 公开工厂测试和 API，避免应用层依赖 internal。
2. 新增模块与最小失败测试：非法 SIR 不创建输出根。
3. 实现统一阶段编排和结构化诊断映射。
4. 以路径/冲突失败测试驱动 portable preflight。
5. 实现 staging、原子发布、覆盖备份和回滚；用故障注入测试根因。
6. 补齐 manifest、不可变性、确定性、链接和 Windows 路径测试。
7. 执行父工程、生成工程真实 Maven 编译和可行的 Spring 分层验收。
