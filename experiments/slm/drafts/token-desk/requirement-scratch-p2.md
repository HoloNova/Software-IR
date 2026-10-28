- GetCredential：按 UUID id 返回 id、owner、expiresAt。

- 不存在时返回 404 CredentialNotFound。

- 凭据主键：按标准 UUID 文本保存。

- Refresh：接收 token，仅轮换未使用且未过期的凭据。

- 原子地标记旧记录已使用并保存恰好一条新记录，新记录 owner 相同、used=false、期限为当前起 7 天、主键为不同 UUID。

- 返回 id、owner、expiresAt。

- Refresh 的失败：重放、未知 token 返回 409 Invalid，过期返回 400 Expired。

- 失败不新增记录。

- Touch：接收 token，仅当 owner 为 alice 时将 used 设为 true，返回 200 及凭据字段。

- Touch 的匹配规则：used 原值已为 true 仍须成功。

- owner 不符或 token 不存在时返回 409 Invalid，且不改行。

- FindAccount：按 name 查询账号，唯一命中返回 200 及 id、name，未命中返回 404 AccountNotFound。

- FindAccount 的多行错误：数据库脏数据造成同名多行时必须返回 500，不得任选一行。

- Register：接收 name、password，创建并保存账号。

- 成功返回 id、name，不包含口令或摘要。

- 口令存储：必须使用带独立盐的 pbkdf2-sha256$ 格式摘要，禁止保存明文。

- Login：接收 name、password，正确口令返回 200 及账号 id、name。

- Login 失败一致性：错误口令与账号不存在，均返回完全相同的 401 BadLogin。

- 旧口令文本：数据库中的非摘要格式旧文本，即使等于输入，也不得用于登录成功。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
