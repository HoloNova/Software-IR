---
created: 2026-07-17
updated: 2026-07-17
importance: high
confidence: confirmed
source: implementation_and_tests
status: active
---

# 工具链工程应用层完成记录

## 结果

`sir-toolchain-application` 已实现最小端到端垂直切片：严格读取 SIR，经 Parser、Semantic、Spring Boot Lowering 和纯函数 Generator 后，把不可变 `GeneratedFile` 集合安全应用到用户显式指定的绝对工程目录。

## 审查收尾

- 修复“当前文件已备份、新文件尚未提交时失败会丢弃备份”的数据丢失风险；事务显式记录 backup/commit 状态并逆序恢复。
- 冲突策略进入事务层，`FAIL_IF_EXISTS` 在提交时重检；目标链与根链在变更前后多次重检符号链接。
- 故障注入钩子从公开 API 收回 internal 包私有缝。
- Windows 通过链接探针覆盖根父链、根本身、目标父链和目标四类拒绝场景，0 skipped。
- 补齐 Semantic、Lowering、Generation 失败阶段、清单归一化和审计字段不变量测试。

## 验收

精确命令 `mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify` 在沙箱外 BUILD SUCCESS：parser 43、semantic 78、lowering-api 4、spring-lowering 32、spring-generator 47、application 79，总计 283，0 failure、0 error、0 skipped。`git diff --check` 通过。

生成工程真实 Maven 编译和 Spring Context/启动仍未验证，因为本地离线仓库缺少冻结 Profile 依赖且没有显式 MySQL 环境；未用 stub 或其他版本冒充。
