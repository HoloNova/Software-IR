凭据持有者要能查看凭据，并把仍可用的凭据换成一条新的凭据。

查看凭据时，GetCredential 按 id 给出 id、owner、used、expiresAt；凭据不存在则返回 404 CredentialNotFound。

持有者把要轮换的凭据 id 交给 Refresh。

凭据还没过期也没被使用时，可以轮换一次；成功后创建一条同 owner 且 used=false 的新凭据，持有者得到 id、owner、used、expiresAt，新期限从当前时间起算 7 天，以 ISO UTC 时刻表示。

一次轮换中，旧凭据变成已使用和新凭据被保存必须是一个原子操作。

如果 8 个请求同时拿同一条凭据来轮换，恰好一个请求成功，其余都返回 409 Invalid，数据库总共只能多一行。

轮换失败或再次提交旧凭据时，数据库不发生写入，其他凭据也保持不变。

持有者提交已使用的凭据会得到 409 Invalid，提交未使用但已过期的凭据会得到 400 Expired，提交不存在的凭据则得到 404 CredentialNotFound。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
