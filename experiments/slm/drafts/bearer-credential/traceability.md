# 判卷追溯

源码基点：`7b305c40379083b18e936f1118079578c9074cf1`。路径前缀为 `sir-toolchain-application/src/test/java/io/kcg/sir/application/conformance/`。

改写题中未删除业务均由开头的“保留”句承接，并以 base 的原有行为为准；从零题须独立描述。表中动态说明保留测试表达式，循环中的每个实参均属于该项检查；error 包装行同时检查状态码和错误码，page/checkPage 包装行包含HTTP状态、组数、记录数量/顺序及每项精确key/total断言，browsed包装行包含状态和是否入列。包装器内部的同类断言以调用行定位，不把包装器当额外业务接口。

| 检查位置与说明 | 改写题对应原句 | 从零题对应原句 |
| --- | --- | --- |
| `BearerCredentialBusinessConformanceIT.java:112` check: "the fixture seed is present in the owned schema" | 未覆盖：夹具/执行器自检，不是软件行为 | 未覆盖：夹具/执行器自检，不是软件行为 |
| `BearerCredentialBusinessConformanceIT.java:131` expectStatus: "a request without a credential is refused with 401" | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” |
| `BearerCredentialBusinessConformanceIT.java:132` check: "the refusal wrote no note" | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” |
| `BearerCredentialBusinessConformanceIT.java:137` check: "a valid token authenticates" | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” |
| `BearerCredentialBusinessConformanceIT.java:140` check: "the scheme name is not case sensitive" | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” |
| `BearerCredentialBusinessConformanceIT.java:142` check: "each note was written as the owner of the token it carried" | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” |
| `BearerCredentialBusinessConformanceIT.java:152` expectStatus: "'" + header + "' is refused with 401" | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” |
| `BearerCredentialBusinessConformanceIT.java:155` check: "the refusals wrote no note" | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” | “PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写；有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。” |
| `BearerCredentialBusinessConformanceIT.java:160` expectStatus: "logging out answers 200" | “Logout 接收 token，返回 200 并使该令牌立即失效，不影响其他用户令牌；不存在的令牌返回 401 BadToken。” | “Logout 接收 token，返回 200 并使该令牌立即失效，不影响其他用户令牌；不存在的令牌返回 401 BadToken。” |
| `BearerCredentialBusinessConformanceIT.java:163` expectStatus: "a token that was logged out is refused with 401" | “Logout 接收 token，返回 200 并使该令牌立即失效，不影响其他用户令牌；不存在的令牌返回 401 BadToken。” | “Logout 接收 token，返回 200 并使该令牌立即失效，不影响其他用户令牌；不存在的令牌返回 401 BadToken。” |
| `BearerCredentialBusinessConformanceIT.java:166` check: "another account's token is unaffected" | “Logout 接收 token，返回 200 并使该令牌立即失效，不影响其他用户令牌；不存在的令牌返回 401 BadToken。” | “Logout 接收 token，返回 200 并使该令牌立即失效，不影响其他用户令牌；不存在的令牌返回 401 BadToken。” |
| `BearerCredentialBusinessConformanceIT.java:169` expectStatus: "logging out a token that does not exist is the declared 401" | “Logout 接收 token，返回 200 并使该令牌立即失效，不影响其他用户令牌；不存在的令牌返回 401 BadToken。” | “Logout 接收 token，返回 200 并使该令牌立即失效，不影响其他用户令牌；不存在的令牌返回 401 BadToken。” |
| `BearerCredentialBusinessConformanceIT.java:170` check: "only the two accepted notes and the one after remain" | “Logout 接收 token，返回 200 并使该令牌立即失效，不影响其他用户令牌；不存在的令牌返回 401 BadToken。” | “Logout 接收 token，返回 200 并使该令牌立即失效，不影响其他用户令牌；不存在的令牌返回 401 BadToken。” |
| `BearerCredentialBusinessConformanceIT.java:178` check: "registering " + name + " stores an account" | “保留 Register、GetUser、PostNote 的现有行为” | “Register 接收 name、password，创建并保存账号，成功返回账号 id、name，不返回口令；GetUser 按 id 查询并返回 id、name，不存在返回 404 UserNotFound。” |
| `BearerCredentialBusinessConformanceIT.java:187` expectStatus: "the right password logs " + name + " in (a login creates the token row, so it answers 201)" | “Login 接收 name、password，正确口令返回 201 及 id、expiresAt；id 是 UUID，令牌关联该账号并保存，15 分钟后到期，错误口令返回 401 BadLogin。” | “Login 接收 name、password，正确口令返回 201 及 id、expiresAt；id 是 UUID，令牌关联该账号并保存，15 分钟后到期，错误口令返回 401 BadLogin。” |
| `BearerCredentialBusinessConformanceIT.java:189` check: "the login answers a well-formed token" | “Login 接收 name、password，正确口令返回 201 及 id、expiresAt；id 是 UUID，令牌关联该账号并保存，15 分钟后到期，错误口令返回 401 BadLogin。” | “Login 接收 name、password，正确口令返回 201 及 id、expiresAt；id 是 UUID，令牌关联该账号并保存，15 分钟后到期，错误口令返回 401 BadLogin。” |
| `BearerCredentialBusinessConformanceIT.java:191` check: "the token is stored for " + name + " and expires in about fifteen minutes" | “Login 接收 name、password，正确口令返回 201 及 id、expiresAt；id 是 UUID，令牌关联该账号并保存，15 分钟后到期，错误口令返回 401 BadLogin。” | “Login 接收 name、password，正确口令返回 201 及 id、expiresAt；id 是 UUID，令牌关联该账号并保存，15 分钟后到期，错误口令返回 401 BadLogin。” |
| `BearerCredentialBusinessConformanceIT.java:196` expectStatus: "a wrong password is the declared 401" | “Login 接收 name、password，正确口令返回 201 及 id、expiresAt；id 是 UUID，令牌关联该账号并保存，15 分钟后到期，错误口令返回 401 BadLogin。” | “Login 接收 name、password，正确口令返回 201 及 id、expiresAt；id 是 UUID，令牌关联该账号并保存，15 分钟后到期，错误口令返回 401 BadLogin。” |
| `BearerCredentialBusinessConformanceIT.java:83` assertTrue: 判卷总报告 passed | 未覆盖：仅汇总上表检查，不增加软件行为 | 未覆盖：仅汇总上表检查，不增加软件行为 |

## 不作为业务需求的检查

IT 的启用开关、参考环境完整性、夹具初始行数、编译/构建/启动和总报告 assertTrue 属于执行器前置或汇总，不要求软件自行造种子或生成测试报告。初始行数在上表逐项列为未覆盖；总报告断言是上表各项的汇总，不增加业务约束。
