# bearer-credential 需求说法逐句对照

原需求按句号、分号拆行；C 对应 requirement-change.md，S 对应 requirement-scratch.md。表内 p1、p2、p3 均为对应新增文件中的原句；p3 开头的业务背景仅交代使用场景，不增加规则。

| 来源 | 原需求句段 | p1 对应句子 | p2 对应句子 | p3 对应句子 |
| --- | --- | --- | --- | --- | --- |
| C01 | 在给定软件上增加登录签发访问令牌和退出功能，保留 Register、GetUser、PostNote 的现有行为。 | 在给定软件上加上登录发访问令牌和退出功能。保留 Register、GetUser、PostNote 的现有行为。 | 在给定软件上增加登录签发访问令牌、退出两项功能。<br>保留 Register、GetUser、PostNote 的现有行为。 | 在给定软件上补上登录签发访问令牌和退出功能，同时保留 Register、GetUser、PostNote 的现有行为。 |
| C02 | Login 接收 name、password，正确口令返回 201 及 id、expiresAt； | Login 传 name、password。口令对了就返回 201，带上 id、expiresAt。 | Login：接收 name、password，口令正确时返回 201 及 id、expiresAt。 | 用户用 name、password 调用 Login，口令正确时拿到 201 和 id、expiresAt。 |
| C03 | id 是 UUID，令牌关联该账号并保存，15 分钟后到期，错误口令返回 401 BadLogin。 | id 要是 UUID。令牌要关联这个账号并保存，15 分钟后到期。口令错了返回 401 BadLogin。 | Login 的令牌：id 为 UUID，关联登录账号并保存，15 分钟后到期。<br>错误口令返回 401 BadLogin。 | 这次登录的令牌以 UUID 作为 id，关联该账号并保存，15 分钟后到期；若口令错误，则返回 401 BadLogin。 |
| C04 | PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写； | PostNote 从 Authorization 的 Bearer 令牌认作者，方案名大小写都行。 | PostNote 的作者身份：取自 Authorization 的 Bearer 令牌，方案名不区分大小写。 | 用户发笔记时，PostNote 用 Authorization 中的 Bearer 令牌确认作者，方案名无论大小写都接受。 |
| C05 | 有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。 | 令牌有效就把笔记存到它所属的用户。匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌，都返回 401，不写笔记。 | PostNote 的写入规则：有效令牌对应的笔记保存到令牌所属用户。<br>匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401，且不写笔记。 | 携带有效令牌发表的笔记归该令牌所属用户并保存；匿名请求、错误方案、缺令牌、非 UUID、未知令牌或已失效令牌，都只能得到 401，不能留下笔记。 |
| C06 | Logout 接收 token，返回 200 并使该令牌立即失效，不影响其他用户令牌； | Logout 传 token，返回 200，让这个令牌马上失效。其他用户的令牌不受影响。 | Logout：接收 token，返回 200，使该令牌立即失效，但不影响其他用户令牌。 | 用户用 token 调用 Logout 后收到 200，该令牌立即失效，其他用户的令牌照常使用。 |
| C07 | 不存在的令牌返回 401 BadToken。 | Logout 遇到不存在的令牌，返回 401 BadToken。 | Logout 的不存在令牌错误：返回 401 BadToken。 | 如果退出时提交的令牌不存在，Logout 返回 401 BadToken。 |
| C08 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 |
| S01 | Register 接收 name、password，创建并保存账号，成功返回账号 id、name，不返回口令； | Register 传 name、password，创建并保存账号。成功返回账号 id、name，不要返回口令。 | Register：接收 name、password，创建并保存账号。<br>成功返回账号 id、name，不返回口令。 | 用户提交 name、password 给 Register 后，账号被创建并保存，成功响应带账号 id、name，不带口令。 |
| S02 | GetUser 按 id 查询并返回 id、name，不存在返回 404 UserNotFound。 | GetUser 按 id 查，返回 id、name。找不到就返回 404 UserNotFound。 | GetUser：按 id 查询，返回 id、name。<br>账号不存在时返回 404 UserNotFound。 | 查看账号时，GetUser 根据 id 返回 id、name；查不到该账号则返回 404 UserNotFound。 |
| S03 | PostNote 接收非空白 text，成功创建笔记并返回 id、text。 | PostNote 传非空白 text，成功创建笔记并返回 id、text。 | PostNote：接收非空白 text，创建笔记成功后返回 id、text。 | 发表笔记时，用户交给 PostNote 的 text 不能是空白，成功创建后得到 id、text。 |
| S04 | Login 接收 name、password，正确口令返回 201 及 id、expiresAt； | Login 传 name、password。口令对了就返回 201，带上 id、expiresAt。 | Login：接收 name、password，口令正确时返回 201 及 id、expiresAt。 | 用户用 name、password 调用 Login，口令正确时拿到 201 和 id、expiresAt。 |
| S05 | id 是 UUID，令牌关联该账号并保存，15 分钟后到期，错误口令返回 401 BadLogin。 | id 要是 UUID。令牌要关联这个账号并保存，15 分钟后到期。口令错了返回 401 BadLogin。 | Login 的令牌：id 为 UUID，关联登录账号并保存，15 分钟后到期。<br>错误口令返回 401 BadLogin。 | 这次登录的令牌以 UUID 作为 id，关联该账号并保存，15 分钟后到期；若口令错误，则返回 401 BadLogin。 |
| S06 | PostNote 以 Authorization 的 Bearer 令牌识别作者，方案名不区分大小写； | PostNote 从 Authorization 的 Bearer 令牌认作者，方案名大小写都行。 | PostNote 的作者身份：取自 Authorization 的 Bearer 令牌，方案名不区分大小写。 | 用户发笔记时，PostNote 用 Authorization 中的 Bearer 令牌确认作者，方案名无论大小写都接受。 |
| S07 | 有效令牌的笔记保存到所属用户，匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401 且不写笔记。 | 令牌有效就把笔记存到它所属的用户。匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌，都返回 401，不写笔记。 | PostNote 的写入规则：有效令牌对应的笔记保存到令牌所属用户。<br>匿名、错误方案、缺令牌、非 UUID、未知或已失效令牌均返回 401，且不写笔记。 | 携带有效令牌发表的笔记归该令牌所属用户并保存；匿名请求、错误方案、缺令牌、非 UUID、未知令牌或已失效令牌，都只能得到 401，不能留下笔记。 |
| S08 | Logout 接收 token，返回 200 并使该令牌立即失效，不影响其他用户令牌； | Logout 传 token，返回 200，让这个令牌马上失效。其他用户的令牌不受影响。 | Logout：接收 token，返回 200，使该令牌立即失效，但不影响其他用户令牌。 | 用户用 token 调用 Logout 后收到 200，该令牌立即失效，其他用户的令牌照常使用。 |
| S09 | 不存在的令牌返回 401 BadToken。 | Logout 遇到不存在的令牌，返回 401 BadToken。 | Logout 的不存在令牌错误：返回 401 BadToken。 | 如果退出时提交的令牌不存在，Logout 返回 401 BadToken。 |
| S10 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 |

自查结论：已逐条对照原需求及 traceability.md；没有检查点缺失或改动，未新增要求；名称、数字、保留行为及末句均保持原意或原样。
