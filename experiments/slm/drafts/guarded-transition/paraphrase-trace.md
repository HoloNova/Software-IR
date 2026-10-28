# guarded-transition 需求说法逐句对照

原需求按句号、分号拆行；C 对应 requirement-change.md，S 对应 requirement-scratch.md。表内 p1、p2、p3 均为对应新增文件中的原句；p3 开头的业务背景仅交代使用场景，不增加规则。

| 来源 | 原需求句段 | p1 对应句子 | p2 对应句子 | p3 对应句子 |
| --- | --- | --- | --- | --- | --- |
| C01 | 在给定软件上增加凭据一次性轮换功能，保留 GetCredential 的现有行为。 | 在给定软件上加一次性凭据轮换。保留 GetCredential 的现有行为。 | 在给定软件上增加凭据一次性轮换功能。<br>保留 GetCredential 的现有行为。 | 在给定软件上补上凭据一次性轮换功能，GetCredential 的现有行为仍须保留。 |
| C02 | Refresh 接收 id； | Refresh 传 id。 | Refresh 的输入：id。 | 持有者把要轮换的凭据 id 交给 Refresh。 |
| C03 | 未过期且未使用的凭据可轮换一次，成功创建一条同 owner、used=false 的新凭据并返回 id、owner、used、expiresAt，新期限为当前时间起 7 天且使用 ISO UTC 时刻。 | 没过期、没用过的凭据只能轮换一次。成功创建一条 owner 相同、used=false 的新凭据，返回 id、owner、used、expiresAt。新期限从现在起算 7 天，用 ISO UTC 时刻。 | Refresh 的成功规则：凭据未过期且未使用时可轮换一次，创建一条 owner 相同、used=false 的新凭据。<br>返回 id、owner、used、expiresAt，新期限为当前时间起 7 天，使用 ISO UTC 时刻。 | 凭据还没过期也没被使用时，可以轮换一次；成功后创建一条同 owner 且 used=false 的新凭据，持有者得到 id、owner、used、expiresAt，新期限从当前时间起算 7 天，以 ISO UTC 时刻表示。 |
| C04 | 轮换须原子地把旧凭据标记为已使用并保存新凭据； | 旧凭据标成已使用、新凭据保存，这两件事必须原子完成。 | 轮换的原子性：把旧凭据标记为已使用与保存新凭据必须原子完成。 | 一次轮换中，旧凭据变成已使用和新凭据被保存必须是一个原子操作。 |
| C05 | 8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行； | 8 个请求同时换同一条凭据，正好一个成功，其他全是 409 Invalid，总共只新增一行。 | 并发轮换：8 个请求同时操作同一条凭据时，恰好一个成功，其余均返回 409 Invalid，合计只新增一行。 | 如果 8 个请求同时拿同一条凭据来轮换，恰好一个请求成功，其余都返回 409 Invalid，数据库总共只能多一行。 |
| C06 | 失败和重放均不写库，也不改变其他凭据。 | 失败或重放都不能写库，也不能动其他凭据。 | 失败与重放：均不写数据库，也不改变其他凭据。 | 轮换失败或再次提交旧凭据时，数据库不发生写入，其他凭据也保持不变。 |
| C07 | 已使用凭据返回 409 Invalid，未使用但过期返回 400 Expired，不存在返回 404 CredentialNotFound。 | 用过的返回 409 Invalid。没用过但过期的返回 400 Expired。不存在的返回 404 CredentialNotFound。 | Refresh 的错误：已使用为 409 Invalid，未使用但过期为 400 Expired，不存在为 404 CredentialNotFound。 | 持有者提交已使用的凭据会得到 409 Invalid，提交未使用但已过期的凭据会得到 400 Expired，提交不存在的凭据则得到 404 CredentialNotFound。 |
| C08 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 |
| S01 | GetCredential 按 id 返回 id、owner、used、expiresAt，不存在返回 404 CredentialNotFound。 | GetCredential 按 id 查，返回 id、owner、used、expiresAt。没有就返回 404 CredentialNotFound。 | GetCredential：按 id 返回 id、owner、used、expiresAt。<br>不存在时返回 404 CredentialNotFound。 | 查看凭据时，GetCredential 按 id 给出 id、owner、used、expiresAt；凭据不存在则返回 404 CredentialNotFound。 |
| S02 | Refresh 接收 id； | Refresh 传 id。 | Refresh 的输入：id。 | 持有者把要轮换的凭据 id 交给 Refresh。 |
| S03 | 未过期且未使用的凭据可轮换一次，成功创建一条同 owner、used=false 的新凭据并返回 id、owner、used、expiresAt，新期限为当前时间起 7 天且使用 ISO UTC 时刻。 | 没过期、没用过的凭据只能轮换一次。成功创建一条 owner 相同、used=false 的新凭据，返回 id、owner、used、expiresAt。新期限从现在起算 7 天，用 ISO UTC 时刻。 | Refresh 的成功规则：凭据未过期且未使用时可轮换一次，创建一条 owner 相同、used=false 的新凭据。<br>返回 id、owner、used、expiresAt，新期限为当前时间起 7 天，使用 ISO UTC 时刻。 | 凭据还没过期也没被使用时，可以轮换一次；成功后创建一条同 owner 且 used=false 的新凭据，持有者得到 id、owner、used、expiresAt，新期限从当前时间起算 7 天，以 ISO UTC 时刻表示。 |
| S04 | 轮换须原子地把旧凭据标记为已使用并保存新凭据； | 旧凭据标成已使用、新凭据保存，这两件事必须原子完成。 | 轮换的原子性：把旧凭据标记为已使用与保存新凭据必须原子完成。 | 一次轮换中，旧凭据变成已使用和新凭据被保存必须是一个原子操作。 |
| S05 | 8 个请求同时轮换同一条凭据时恰有一个成功，其余均为 409 Invalid，总共只新增一行； | 8 个请求同时换同一条凭据，正好一个成功，其他全是 409 Invalid，总共只新增一行。 | 并发轮换：8 个请求同时操作同一条凭据时，恰好一个成功，其余均返回 409 Invalid，合计只新增一行。 | 如果 8 个请求同时拿同一条凭据来轮换，恰好一个请求成功，其余都返回 409 Invalid，数据库总共只能多一行。 |
| S06 | 失败和重放均不写库，也不改变其他凭据。 | 失败或重放都不能写库，也不能动其他凭据。 | 失败与重放：均不写数据库，也不改变其他凭据。 | 轮换失败或再次提交旧凭据时，数据库不发生写入，其他凭据也保持不变。 |
| S07 | 已使用凭据返回 409 Invalid，未使用但过期返回 400 Expired，不存在返回 404 CredentialNotFound。 | 用过的返回 409 Invalid。没用过但过期的返回 400 Expired。不存在的返回 404 CredentialNotFound。 | Refresh 的错误：已使用为 409 Invalid，未使用但过期为 400 Expired，不存在为 404 CredentialNotFound。 | 持有者提交已使用的凭据会得到 409 Invalid，提交未使用但已过期的凭据会得到 400 Expired，提交不存在的凭据则得到 404 CredentialNotFound。 |
| S08 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 |

自查结论：已逐条对照原需求及 traceability.md；没有检查点缺失或改动，未新增要求；名称、数字、保留行为及末句均保持原意或原样。
