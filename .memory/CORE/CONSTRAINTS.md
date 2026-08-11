---
created: 2026-07-16
updated: 2026-08-11
importance: critical
confidence: confirmed
source: accepted_architecture
status: active
---

# 约束条件

## 模块边界

- Parser 不包含 Target 或业务框架语义。
- Semantic 只依赖 Parser。
- Lowering 输入为 Normalized model，并产生独立可验证 Lowered IR。
- Generator 只渲染 Lowered IR，不读 AST/SIR/SymbolTable，不写磁盘。
- Project Graph 不访问文件系统，不重新解析名称。
- Application 独占 Bundle、CURRENT、LOCK、Journal 和工程写入。
- CLI 只适配 typed Application API，不成为第二状态权威。

## 语义与确定性

- 名称只在 Resolve 解析一次。
- 每个真实引用拥有稳定 AstNodeId、ReferenceRole 和唯一 SymbolId binding。
- 公开集合不可变且顺序确定；大小写转换使用 `Locale.ROOT`。
- 普通非法输入返回结构化 Failure，不逃逸为内部异常。

## 文件事务

- `CURRENT=B0` 只向后补偿；`CURRENT=B1` 只向前验证/清理。
- 方向、路径、链接、文件类型、物理身份或补偿证据不确定时 fail closed。
- DELETE backup 使用同卷 hard link，无 copy/move/replace fallback。
- 路径检查覆盖 Windows 保留名、大小写重复、symlink、junction/reparse point 和提交时重检。

## 验收

- 测试数量来自当次运行，不在长期约束中冻结。
- exclude、skip、BLOCKED、NOT_RUN 与 PASS 分开报告。
- 完成前运行标准离线 `clean verify`、`git diff --check` 和 `git status --short`。
- 不读取或提交用户本地受保护目录和交付文件。
