# ADR-020: CURRENT 基线与事务恢复方向

- Status: Accepted
- Date: 2026-08-11
- Scope: UPDATE、CREATE、DELETE 文件事务及显式恢复
- Decision owner: user

## Context

Application 通过不可变 Baseline Bundle、事务 Journal 和 `CURRENT` 维护工程状态。Journal 同时记录事务开始时的 B0 和候选提交后的 B1，而 `CURRENT` 是基线发布的唯一线性化点。

如果不同文档对 `CURRENT=B0` 和 `CURRENT=B1` 使用相反方向，Agent 可能在故障处理中恢复已经提交的旧字节，或在尚未提交时继续向前发布。方向必须由一个跨 UPDATE、CREATE、DELETE 的契约统一决定。

## Options considered

| Option | Meaning | Result |
|---|---|---|
| A | B0 向后补偿，B1 向前验证/清理 | 与 CURRENT 的发布语义、当前实现和可执行测试一致 |
| B | 反转 CURRENT 的发布语义 | 会把未提交状态当作已提交，并允许撤销已发布基线 |
| C | 观察磁盘内容后猜方向 | 在外部漂移、部分写入和身份不确定时不安全 |

## Decision

选择 Option A，并冻结以下不变量：

1. `CURRENT=B0` 表示 B1 尚未发布。恢复只能执行向后补偿，使输出、Bundle 和事务材料回到 B0 一致状态。
2. `CURRENT=B1` 表示 B1 已经发布。恢复只能验证 B1 并清理 staging、backup 和 Journal，不得恢复 B0 字节。
3. CURRENT 既不等于 Journal 的 B0，也不等于 B1 时，返回 `RECOVERY_REQUIRED` 或结构化 Failure，保留证据且不得猜测。
4. B0/B1 Bundle、Journal、输出字节或物理文件身份无法证明时 fail closed。
5. 恢复只能显式触发；普通 `context`、`plan`、`generate` 或新的 Apply 不得隐式恢复未完成事务。
6. UPDATE、CREATE、DELETE 必须共享上述方向语义；单个实现只能细化阶段动作，不能重新定义方向。

## Evidence

- `ChangeRecoveryEngine` 根据 `currentIsB0` 选择 `backwardRecovery`，否则对 B1 选择 `forwardRecovery`。
- `ChangeDeleteRecoveryEngine` 使用相同分支。
- `RecoveryStateMachineTest` 直接覆盖 B0 向后补偿和 B1 向前验证/清理。
- `docs/superpowers/specs/2026-08-04-local-software-ir-mvp-delivery-design.md` 将 `ROLLED_BACK` 映射为 current B0，将 `RECOVERED` 映射为 current B1 的向前完成。

## Consequences

- 文档、测试和实现拥有单一方向术语。
- 已发布 B1 不会被恢复流程静默撤销。
- 未发布 B1 不会因磁盘上出现部分新字节而被猜测提交。
- 某些可人工修复的模糊状态会被保守拒绝，这是 fail-closed 选择的预期代价。

## Revisit trigger

任何改变 `CURRENT` 线性化点、Bundle/Journal 格式、事务提交顺序或引入多基线并发发布的设计，都必须通过新的 ADR 明确取代本决策。
