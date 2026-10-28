在给定软件上加上登录发访问令牌和退出功能。保留 Register、GetUser、PostNote 的现有行为。

Login 传 name、password。口令对了就返回 201，带上 id、expiresAt。

id 要是 UUID。令牌要关联这个账号并保存，15 分钟后到期。口令错了返回 401 BadLogin。

PostNote 从 Authorization 的 Bearer 令牌认作者，方案名大小写都行。

令牌有效就把笔记存到它所属的用户。匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌，都返回 401，不写笔记。

Logout 传 token，返回 200，让这个令牌马上失效。其他用户的令牌不受影响。

Logout 遇到不存在的令牌，返回 401 BadToken。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
