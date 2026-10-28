用户发笔记时需要表明自己是谁，也需要在退出后让当前令牌失效。

在给定软件上补上登录签发访问令牌和退出功能，同时保留 Register、GetUser、PostNote 的现有行为。

用户用 name、password 调用 Login，口令正确时拿到 201 和 id、expiresAt。

这次登录的令牌以 UUID 作为 id，关联该账号并保存，15 分钟后到期；若口令错误，则返回 401 BadLogin。

用户发笔记时，PostNote 用 Authorization 中的 Bearer 令牌确认作者，方案名无论大小写都接受。

携带有效令牌发表的笔记归该令牌所属用户并保存；匿名请求、错误方案、缺令牌、非 UUID、未知令牌或已失效令牌，都只能得到 401，不能留下笔记。

用户用 token 调用 Logout 后收到 200，该令牌立即失效，其他用户的令牌照常使用。

如果退出时提交的令牌不存在，Logout 返回 401 BadToken。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
