- GetCredential：按 id 返回 id、owner、used、expiresAt。

- 不存在时返回 404 CredentialNotFound。

- Refresh 的输入：id。

- Refresh 的成功规则：凭据未过期且未使用时可轮换一次，创建一条 owner 相同、used=false 的新凭据。

- 返回 id、owner、used、expiresAt，新期限为当前时间起 7 天，使用 ISO UTC 时刻。

- 轮换的原子性：把旧凭据标记为已使用与保存新凭据必须原子完成。

- 并发轮换：8 个请求同时操作同一条凭据时，恰好一个成功，其余均返回 409 Invalid，合计只新增一行。

- 失败与重放：均不写数据库，也不改变其他凭据。

- Refresh 的错误：已使用为 409 Invalid，未使用但过期为 400 Expired，不存在为 404 CredentialNotFound。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
