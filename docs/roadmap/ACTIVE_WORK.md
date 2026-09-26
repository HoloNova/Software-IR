# 当前工作单：IDLE（无获授权工作单）

- 状态：**`IDLE`**（2026-09-26；Q16 已完成验收并归档于 [`completed/Q16-persistent-identity-and-read-only-rename-plan.md`](completed/Q16-persistent-identity-and-read-only-rename-plan.md)）。
- 当前没有新的 Q 系列实施授权。候选项只是供负责人审定方向，**不构成开工授权**；未经新工作单批准，不修改生产代码、测试、POM 或生成物。
- 最新已验收源码：`b2f5436a0c98cba69467d211b19053237ba5edf6`；GitHub CI [run `36234304027`](https://github.com/HoloNova/Software-IR/actions/runs/36234304027)：两条全量闸门均 837 / 0 / 0 / 5，四个业务 IT 全 `PASSED`（40/61/36/63），证据 artifact 为 `surefire-reports` 与 `conformance-evidence`。归档记录本身为纯文档，不改变该源码。

## 下一张工作单候选（须单独审定）

1. **Q17：单声明改名的原子文件事务**。从 Q16 已验证的只读 `RenamePlan` 出发，先实测并冻结混合路径更新/撤销/建立的 Journal 与故障恢复方案，再验证受管旧文件清理、未受管文件保护、CURRENT 方向和“应用盘面 == 候选从零生成”；V4 日志只是候选，不预设采用。Q16 没有改名 apply。
2. **能力与 Input 同时改名**。原课程例子全局替换会同时改 Input 声明，Q16 以 `SIR-RENAME-PATH-004` 拒绝漏报的 Input DTO；后续需独立决定 Input 持久身份及多声明计划，不能以 Q17 的事务自动补足。
3. **G2 其余门禁**。多文件源清单、import/binding、模块实例、nodeKey、跨文件移动和旧身份兼容的完整证据；G2 尚未完成。
4. **Q12 路由模板**与 **BIZ-07 起的报名业务**：均未立项，不占用 G2 当前授权。

## 已登记的独立项

- 错误信封码名与主设计逐字对齐（Q10 裁决保留现状）。
- `SpringBootModelLowerer.java` 的 CFR 反编译文本是否重写（独立清理）。
- 非分页 find 的关联读取、关联深度 3 层及以上、预算由“按投影使用点”改为关系计划去重的替代口径。
- `in` / `isNull`、when-present 可选过滤、按关联字段排序、关联集合自身分页。
- 变更影响模型是否允许操作改动幸存文件（例如 `Application.java` 的 actor 传输片段）；与 Q16 单声明改名事务分开裁决。
- 实体字段改名后的物理列名继承与 G3 数据库迁移，不因 `@id` 相同而自动执行 DDL。

执行时先按 [`README.md`](README.md) 的阶段门与 [`REMAINING_WORK.md`](REMAINING_WORK.md) 的候选核对事实，再建立 `SPEC_REVIEW` 工作单；候选不替代授权。
