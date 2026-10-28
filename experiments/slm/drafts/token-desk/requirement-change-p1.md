在给定软件上加账号注册和口令登录。保留 GetCredential、Refresh、Touch、FindAccount 的现有行为。

Register 传 name、password，创建并保存账号。成功返回 id、name，别带口令或摘要。

数据库别存明文口令，要存带独立盐的 pbkdf2-sha256$ 格式摘要。

Login 传 name、password。口令对了返回 200 和账号 id、name。

口令错了和账号不存在，必须返回完全相同的 401 BadLogin。

库里那些不是摘要格式的旧文本，就算和输入一样也不能登录。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
