- 在给定软件上增加登录签发访问令牌、退出两项功能。

- 保留 Register、GetUser、PostNote 的现有行为。

- Login：接收 name、password，口令正确时返回 201 及 id、expiresAt。

- Login 的令牌：id 为 UUID，关联登录账号并保存，15 分钟后到期。

- 错误口令返回 401 BadLogin。

- PostNote 的作者身份：取自 Authorization 的 Bearer 令牌，方案名不区分大小写。

- PostNote 的写入规则：有效令牌对应的笔记保存到令牌所属用户。

- 匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401，且不写笔记。

- Logout：接收 token，返回 200，使该令牌立即失效，但不影响其他用户令牌。

- Logout 的不存在令牌错误：返回 401 BadToken。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
