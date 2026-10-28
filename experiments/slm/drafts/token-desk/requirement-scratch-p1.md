GetCredential 按 UUID id 查，返回 id、owner、expiresAt。不存在就返回 404 CredentialNotFound。

凭据主键要存成标准 UUID 文本。

Refresh 传 token，只有没用过也没过期的凭据能换。旧记录标成已使用，同时保存恰好一条新记录，必须原子完成。新记录 owner 相同、used=false，从现在起 7 天到期，主键是不同的 UUID。返回 id、owner、expiresAt。

Refresh 重放或者 token 未知，返回 409 Invalid。过期返回 400 Expired。失败别新增记录。

Touch 传 token，只能在 owner 是 alice 时把 used 设成 true，返回 200 和凭据字段。

Touch 符合 owner 条件时，used 原来就是 true 也得成功。owner 不对或者 token 不存在，返回 409 Invalid，别改那一行。

FindAccount 按 name 查账号。只找到一条就返回 200 和 id、name；没找到返回 404 AccountNotFound。

库里脏数据让同名账号有多行时，必须返回 500，不能随便挑一条。

Register 传 name、password，创建并保存账号。成功返回 id、name，别带口令或摘要。

数据库别存明文口令，要存带独立盐的 pbkdf2-sha256$ 格式摘要。

Login 传 name、password。口令对了返回 200 和账号 id、name。

口令错了和账号不存在，必须返回完全相同的 401 BadLogin。

库里那些不是摘要格式的旧文本，就算和输入一样也不能登录。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
