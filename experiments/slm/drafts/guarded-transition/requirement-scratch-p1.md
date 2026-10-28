GetCredential 按 id 查，返回 id、owner、used、expiresAt。没有就返回 404 CredentialNotFound。

Refresh 传 id。

没过期、没用过的凭据只能轮换一次。成功创建一条 owner 相同、used=false 的新凭据，返回 id、owner、used、expiresAt。新期限从现在起算 7 天，用 ISO UTC 时刻。

旧凭据标成已使用、新凭据保存，这两件事必须原子完成。

8 个请求同时换同一条凭据，正好一个成功，其他全是 409 Invalid，总共只新增一行。

失败或重放都不能写库，也不能动其他凭据。

用过的返回 409 Invalid。没用过但过期的返回 400 Expired。不存在的返回 404 CredentialNotFound。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
