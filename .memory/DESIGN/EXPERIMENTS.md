---
created: 2026-07-16
updated: 2026-08-11
importance: medium
confidence: confirmed
source: command_evidence
status: reference
---

# 构建与资格实验记录

## 2026-08-11 默认离线 Reactor

命令：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
```

结果：345 run、0 failure、0 error、10 skip，父工程和九个子模块 BUILD SUCCESS。

边界：conformance 包被 POM 排除；Change fixtures 造成额外类级 skip；不构成外部 MySQL、完整 CLI 生命周期或生产资格。

## 早期日期化实验

- 2026-07-14：Parser + Semantic 119 项通过。
- 2026-07-17：七模块 283 项通过。
- 2026-07-18：加入 Project Graph 后记录 392 项通过。

这些数据用于理解阶段演进，不是当前测试下限或当前资格结论。
