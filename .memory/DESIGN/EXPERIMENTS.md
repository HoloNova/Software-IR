---
created: 2026-07-16
updated: 2026-07-17
importance: high
confidence: confirmed
source: experiment
status: active
---

# 实验记录

## 实验：SIR v0.1 第三轮完整离线构建

**日期：** 2026-07-14

**假设：** Parser/AST 与四阶段 Semantic Pipeline 在 clean 后能够离线重新生成 ANTLR 源码、编译并通过全部回归测试。

**方法：** 执行：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
```

并覆盖未知 Find、完整引用绑定、负数 min/max、length 整数限制、未知字段诊断唯一性、identity 诊断归属、重复 SymbolId、链式 MemberExpression 与确定性。

**结果：**

- `sir-parser`：41 tests，0 failures，0 errors；
- `sir-semantic`：78 tests，0 failures，0 errors；
- 合计：119 tests，0 failures，0 errors；
- BUILD SUCCESS，总耗时 41.431 秒。

**分析：** 该结果确认第三轮语义模型可作为 Lowering 输入基线。它不证明大型工程性能、SLM 生成准确率、Lowering 或代码生成能力。

**后续：** 下一阶段新增模块后继续使用 clean verify，并为 Lowered IR 验证、Target Lowering 和 Generator 增加独立测试计数与快照验收。

## 实验：工具链工程应用层离线完整构建

**日期：** 2026-07-17

**假设：** Parser 到安全工程应用的七模块管线能从 clean 状态离线重建，并在 Windows 上验证路径、冲突、事务回滚和确定性。

**方法：** 在沙箱外执行：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short
```

**结果：**

- parser 43、semantic 78、lowering-api 4、spring-lowering 32、spring-generator 47、toolchain-application 79；
- 合计 283 tests，0 failures，0 errors，0 skipped；
- Reactor 7 个项目全部 SUCCESS；
- Windows 符号链接决策测试通过可注入探针执行，无 OS 跳过；
- `git diff --check` 通过，仅有 LF/CRLF 提示。

**分析：** 结果证明当前公开管线可被统一编排并安全应用到显式工程目录；不证明生成工程已在真实 Spring/MySQL 依赖下编译或启动。

**后续：** 以现有 SymbolId、LoweredNodeId、Artifact ownership 和 manifest provenance 为输入，设计 Project Symbol Graph v0.1 的只读确定性快照。
