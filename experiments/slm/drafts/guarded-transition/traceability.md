# 判卷追溯

源码基点：`7b305c40379083b18e936f1118079578c9074cf1`。路径前缀为 `sir-toolchain-application/src/test/java/io/kcg/sir/application/conformance/`。

改写题中未删除业务均由开头的“保留”句承接，并以 base 的原有行为为准；从零题须独立描述。表中动态说明保留测试表达式，循环中的每个实参均属于该项检查；error 包装行同时检查状态码和错误码，page/checkPage 包装行包含HTTP状态、组数、记录数量/顺序及每项精确key/total断言，browsed包装行包含状态和是否入列。包装器内部的同类断言以调用行定位，不把包装器当额外业务接口。

| 检查位置与说明 | 改写题对应原句 | 从零题对应原句 |
| --- | --- | --- |
| `GuardedTransitionBusinessConformanceIT.java:131` check: "the fixture seed is present in the owned schema" | 未覆盖：夹具/执行器自检，不是软件行为 | 未覆盖：夹具/执行器自检，不是软件行为 |
| `GuardedTransitionBusinessConformanceIT.java:149` expectStatus: "an already used credential is refused with the declared 409" | “已使用凭据返回 409 Invalid，未使用但过期返回 400 Expired，不存在返回 404 CredentialNotFound。” | “已使用凭据返回 409 Invalid，未使用但过期返回 400 Expired，不存在返回 404 CredentialNotFound。” |
| `GuardedTransitionBusinessConformanceIT.java:150` check: "the refusal wrote nothing: still three rows and row 2 unchanged" | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” |
| `GuardedTransitionBusinessConformanceIT.java:157` expectStatus: "an expired credential is refused with the declared 400" | “已使用凭据返回 409 Invalid，未使用但过期返回 400 Expired，不存在返回 404 CredentialNotFound。” | “已使用凭据返回 409 Invalid，未使用但过期返回 400 Expired，不存在返回 404 CredentialNotFound。” |
| `GuardedTransitionBusinessConformanceIT.java:158` check: "the refusal wrote nothing: still three rows and row 3 still unused" | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” |
| `GuardedTransitionBusinessConformanceIT.java:165` expectStatus: "an unknown credential is refused with the declared 404" | “已使用凭据返回 409 Invalid，未使用但过期返回 400 Expired，不存在返回 404 CredentialNotFound。” | “已使用凭据返回 409 Invalid，未使用但过期返回 400 Expired，不存在返回 404 CredentialNotFound。” |
| `GuardedTransitionBusinessConformanceIT.java:178` check: "exactly one of " + RACERS + " simultaneous requests rotates the credential" | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” |
| `GuardedTransitionBusinessConformanceIT.java:180` check: "every other simultaneous request is refused with the declared 409" | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” |
| `GuardedTransitionBusinessConformanceIT.java:184` check: "the rotation created exactly one new credential, so the refused requests left none" | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” |
| `GuardedTransitionBusinessConformanceIT.java:186` check: "the rotated credential is marked used exactly once" | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” |
| `GuardedTransitionBusinessConformanceIT.java:195` expectFields: "the winner answers with the new credential, owned by the same owner and unused" | “Refresh 接收 id；未过期且未使用的凭据可轮换一次，成功创建一条同 owner、used=false 的新凭据并返回 id、owner、used、expiresAt，新期限为当前时间起 7 天且使用 ISO UTC 时刻。” | “Refresh 接收 id；未过期且未使用的凭据可轮换一次，成功创建一条同 owner、used=false 的新凭据并返回 id、owner、used、expiresAt，新期限为当前时间起 7 天且使用 ISO UTC 时刻。” |
| `GuardedTransitionBusinessConformanceIT.java:197` check: "the new credential is stored unused" | “Refresh 接收 id；未过期且未使用的凭据可轮换一次，成功创建一条同 owner、used=false 的新凭据并返回 id、owner、used、expiresAt，新期限为当前时间起 7 天且使用 ISO UTC 时刻。” | “Refresh 接收 id；未过期且未使用的凭据可轮换一次，成功创建一条同 owner、used=false 的新凭据并返回 id、owner、used、expiresAt，新期限为当前时间起 7 天且使用 ISO UTC 时刻。” |
| `GuardedTransitionBusinessConformanceIT.java:204` check: "the new credential expires about seven days from now (time shift evaluated once)" | “Refresh 接收 id；未过期且未使用的凭据可轮换一次，成功创建一条同 owner、used=false 的新凭据并返回 id、owner、used、expiresAt，新期限为当前时间起 7 天且使用 ISO UTC 时刻。” | “Refresh 接收 id；未过期且未使用的凭据可轮换一次，成功创建一条同 owner、used=false 的新凭据并返回 id、owner、used、expiresAt，新期限为当前时间起 7 天且使用 ISO UTC 时刻。” |
| `GuardedTransitionBusinessConformanceIT.java:208` check: "the new credential's expiry is an ISO instant" | “Refresh 接收 id；未过期且未使用的凭据可轮换一次，成功创建一条同 owner、used=false 的新凭据并返回 id、owner、used、expiresAt，新期限为当前时间起 7 天且使用 ISO UTC 时刻。” | “Refresh 接收 id；未过期且未使用的凭据可轮换一次，成功创建一条同 owner、used=false 的新凭据并返回 id、owner、used、expiresAt，新期限为当前时间起 7 天且使用 ISO UTC 时刻。” |
| `GuardedTransitionBusinessConformanceIT.java:215` expectStatus: "replaying the rotated credential is refused with the declared 409" | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” |
| `GuardedTransitionBusinessConformanceIT.java:216` check: "the replay wrote nothing: still four rows" | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” |
| `GuardedTransitionBusinessConformanceIT.java:220` check: "the other seeded credentials are unchanged" | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” | “轮换须原子地把旧凭据标记为已使用并保存新凭据；8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行；失败和重放均不写库，也不改变其他凭据。” |
| `GuardedTransitionBusinessConformanceIT.java:103` assertTrue: 判卷总报告 passed | 未覆盖：仅汇总上表检查，不增加软件行为 | 未覆盖：仅汇总上表检查，不增加软件行为 |

## 不作为业务需求的检查

IT 的启用开关、参考环境完整性、夹具初始行数、编译/构建/启动和总报告 assertTrue 属于执行器前置或汇总，不要求软件自行造种子或生成测试报告。初始行数在上表逐项列为未覆盖；总报告断言是上表各项的汇总，不增加业务约束。
