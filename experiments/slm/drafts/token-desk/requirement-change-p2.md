- 在给定软件上增加账号注册、口令登录。

- 保留 GetCredential、Refresh、Touch、FindAccount 的现有行为。

- Register：接收 name、password，创建并保存账号。

- 成功返回 id、name，不包含口令或摘要。

- 口令存储：必须使用带独立盐的 pbkdf2-sha256$ 格式摘要，禁止保存明文。

- Login：接收 name、password，正确口令返回 200 及账号 id、name。

- Login 失败一致性：错误口令与账号不存在，均返回完全相同的 401 BadLogin。

- 旧口令文本：数据库中的非摘要格式旧文本，即使等于输入，也不得用于登录成功。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
