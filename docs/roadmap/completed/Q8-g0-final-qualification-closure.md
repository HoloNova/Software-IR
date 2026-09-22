# 已完成工作单：Q8 G0 最终资格关闭动作

- 状态：`DONE`
- 阶段：路线图阶段 7；关闭 G0
- 验收：项目负责人 2026-09-18 确认 Q8，通过后由负责人手动提交实现快照
- 归档：2026-09-18，G0 文档闭合时归档

## 完成门

负责人确认的 G0 完成门全部满足：

1. 冻结形式与完成形式的离线 Reactor 均 `BUILD SUCCESS`。
2. 当次 Surefire 合计为 **554 run / 0 fail / 0 error / 5 skip**。
3. MySQL 8.4.11 参考环境上的五场景 conformance 矩阵为 `QUALIFIED`，并已复跑确认。
4. Windows、thin JAR/发行包、完整 CLI 写生命周期、第二卷/挂载点等未覆盖范围均已登记。
5. `CURRENT_QUALIFICATION.md` 第 0 节作为 G0 当前资格结论的唯一权威入口。

## 当次证据

- 冻结命令：`mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify`
- 完成命令：`mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true`
- 两条命令均为 `BUILD SUCCESS`；父工程与九个子模块全部完成。
- 外部矩阵：MySQL 8.4.11，`IG-ACTOR`、`IG-READONLY`、`APPLY-UPDATE`、`APPLY-CREATE`、`APPLY-DELETE` 全部通过。
- 提交后的复核仍得到同样结果：实现快照为 `24eec6d`，`main` 与 `origin/main` 一致；工作树在文档闭合前干净。

## 范围与残余边界

Q8 只完成资格状态与文档闭合，不新增生产行为。G0 之后的下一步是 G1；完整 CLI 本地写生命周期、thin JAR/发行包、Windows 侧和其他登记的未覆盖项不因 G0 关闭而自动完成。
