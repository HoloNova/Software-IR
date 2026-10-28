账号使用者需要查询、轮换和更新凭据，也需要注册账号、查询账号并用口令登录。

查看凭据时，用户将 UUID id 交给 GetCredential，得到 id、owner、expiresAt；找不到则返回 404 CredentialNotFound。

保存凭据时，它的主键必须采用标准 UUID 文本。

用户将 token 交给 Refresh 时，只有未使用且未过期的凭据能轮换；旧记录标记为已使用与保存恰好一条新记录必须原子完成，新记录保持相同 owner、used=false，期限从当前起算 7 天，主键换成不同的 UUID，响应为 id、owner、expiresAt。

再次提交已轮换的 token 或提交未知 token，会得到 409 Invalid，凭据过期则得到 400 Expired；轮换失败不能新增记录。

用户用 token 调用 Touch 时，只有 owner 为 alice 才能把 used 设成 true，并得到 200 和凭据字段。

即使凭据的 used 本来已是 true，符合条件的 Touch 仍要成功；若 owner 不符或 token 不存在，则返回 409 Invalid，不改动记录。

用户按 name 调用 FindAccount，唯一命中时得到 200 与 id、name，没有命中时得到 404 AccountNotFound。

如果脏数据让数据库中出现同名多行，FindAccount 必须返回 500，不能随意选出其中一个账号。

用户提交 name、password 给 Register 后，账号被创建并保存，成功响应包含 id、name，但不能带口令或摘要。

用户的口令进入数据库时必须是带独立盐的 pbkdf2-sha256$ 格式摘要，不能把明文存进去。

用户用 name、password 调用 Login，口令正确时得到 200 和该账号的 id、name。

用户输错口令或输入不存在的账号时，看到的必须是完全相同的 401 BadLogin。

若账号保存的是非摘要格式的旧文本，即使用户输入与它完全一致，也不能让这次登录成功。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
