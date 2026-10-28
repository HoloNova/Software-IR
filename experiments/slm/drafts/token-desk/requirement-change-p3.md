账号使用者需要注册账号并用口令登录，已有凭据和账号查询功能还要照常工作。

在给定软件上补上账号注册和口令登录，同时保留 GetCredential、Refresh、Touch、FindAccount 的现有行为。

用户提交 name、password 给 Register 后，账号被创建并保存，成功响应包含 id、name，但不能带口令或摘要。

用户的口令进入数据库时必须是带独立盐的 pbkdf2-sha256$ 格式摘要，不能把明文存进去。

用户用 name、password 调用 Login，口令正确时得到 200 和该账号的 id、name。

用户输错口令或输入不存在的账号时，看到的必须是完全相同的 401 BadLogin。

若账号保存的是非摘要格式的旧文本，即使用户输入与它完全一致，也不能让这次登录成功。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
