---
created: 2026-07-16
updated: 2026-08-11
importance: high
confidence: confirmed
source: current_validation
status: active
---

# 已知缺口与环境注意事项

## P0：conformance 整包被排除

`sir-toolchain-application/pom.xml` 同时从 testCompile 和 Surefire 排除 `io/kcg/sir/application/conformance/**`。目录中有 46 个文件，但 suite、fixture 和 evidence 辅助类包含不完整实现。默认构建通过不证明 conformance 可用。

## P0：Generator / Project Graph 直接测试不足

Generator 已建立第一层 canonical 输出契约，但 Renderer 语义、环境确定性和生成工程离线编译仍不完整；Project Graph 当前仍无直接模块测试。Application 的间接执行不能替代模块契约测试。

## P1：Change fixtures 缺失

缺少多个 base/candidate SIR，导致 `ChangePlanningApplicationTest` 6 项 skip，`KcgCliWorkflowTest` 整类 assumption skip。

## P1：CLI 边界

当前正式命令只有 `context` 和 `plan`。`generate/register/apply/recover` 不应被文档称为当前可用命令。

## 环境：Git 全局 ignore 警告

受限环境可能显示 `unable to access C:\Users\zdw00/.config/git/ignore: Permission denied`。以仓库 `.gitignore`、明确的 tracked file 列表和 `git status` 为准；不要因此扩大用户目录访问范围。

## 环境：沙箱内 Maven 文件访问

受限沙箱可能阻止 javac/esbuild 读取工作区上级或模块输出。关键构建应使用获批的沙箱外标准命令，并区分环境权限失败与产品失败。
