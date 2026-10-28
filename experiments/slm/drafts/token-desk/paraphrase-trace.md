# token-desk 需求说法逐句对照

原需求按句号、分号拆行；C 对应 requirement-change.md，S 对应 requirement-scratch.md。表内 p1、p2、p3 均为对应新增文件中的原句；p3 开头的业务背景仅交代使用场景，不增加规则。

| 来源 | 原需求句段 | p1 对应句子 | p2 对应句子 | p3 对应句子 |
| --- | --- | --- | --- | --- | --- |
| C01 | 在给定软件上增加账号注册与口令登录，保留 GetCredential、Refresh、Touch、FindAccount 的现有行为。 | 在给定软件上加账号注册和口令登录。保留 GetCredential、Refresh、Touch、FindAccount 的现有行为。 | 在给定软件上增加账号注册、口令登录。<br>保留 GetCredential、Refresh、Touch、FindAccount 的现有行为。 | 在给定软件上补上账号注册和口令登录，同时保留 GetCredential、Refresh、Touch、FindAccount 的现有行为。 |
| C02 | Register 接收 name、password，创建并保存账号，成功返回 id、name，不包含口令或摘要； | Register 传 name、password，创建并保存账号。成功返回 id、name，别带口令或摘要。 | Register：接收 name、password，创建并保存账号。<br>成功返回 id、name，不包含口令或摘要。 | 用户提交 name、password 给 Register 后，账号被创建并保存，成功响应包含 id、name，但不能带口令或摘要。 |
| C03 | 数据库口令必须为带独立盐的 pbkdf2-sha256$ 格式摘要，不能存明文。 | 数据库别存明文口令，要存带独立盐的 pbkdf2-sha256$ 格式摘要。 | 口令存储：必须使用带独立盐的 pbkdf2-sha256$ 格式摘要，禁止保存明文。 | 用户的口令进入数据库时必须是带独立盐的 pbkdf2-sha256$ 格式摘要，不能把明文存进去。 |
| C04 | Login 接收 name、password，正确口令返回 200 和账号 id、name； | Login 传 name、password。口令对了返回 200 和账号 id、name。 | Login：接收 name、password，正确口令返回 200 及账号 id、name。 | 用户用 name、password 调用 Login，口令正确时得到 200 和该账号的 id、name。 |
| C05 | 错误口令和不存在账号都返回完全相同的 401 BadLogin； | 口令错了和账号不存在，必须返回完全相同的 401 BadLogin。 | Login 失败一致性：错误口令与账号不存在，均返回完全相同的 401 BadLogin。 | 用户输错口令或输入不存在的账号时，看到的必须是完全相同的 401 BadLogin。 |
| C06 | 数据库中不是摘要格式的旧文本，即使与输入相同也不得登录。 | 库里那些不是摘要格式的旧文本，就算和输入一样也不能登录。 | 旧口令文本：数据库中的非摘要格式旧文本，即使等于输入，也不得用于登录成功。 | 若账号保存的是非摘要格式的旧文本，即使用户输入与它完全一致，也不能让这次登录成功。 |
| C07 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 |
| S01 | GetCredential 按 UUID id 返回 id、owner、expiresAt，不存在返回 404 CredentialNotFound； | GetCredential 按 UUID id 查，返回 id、owner、expiresAt。不存在就返回 404 CredentialNotFound。 | GetCredential：按 UUID id 返回 id、owner、expiresAt。<br>不存在时返回 404 CredentialNotFound。 | 查看凭据时，用户将 UUID id 交给 GetCredential，得到 id、owner、expiresAt；找不到则返回 404 CredentialNotFound。 |
| S02 | 凭据主键按标准 UUID 文本保存。 | 凭据主键要存成标准 UUID 文本。 | 凭据主键：按标准 UUID 文本保存。 | 保存凭据时，它的主键必须采用标准 UUID 文本。 |
| S03 | Refresh 接收 token，仅允许未使用且未过期凭据轮换，原子地标记旧记录已使用并保存恰好一条 owner 相同、used=false、期限为当前起 7 天且主键为不同 UUID 的新记录，返回 id、owner、expiresAt； | Refresh 传 token，只有没用过也没过期的凭据能换。旧记录标成已使用，同时保存恰好一条新记录，必须原子完成。新记录 owner 相同、used=false，从现在起 7 天到期，主键是不同的 UUID。返回 id、owner、expiresAt。 | Refresh：接收 token，仅轮换未使用且未过期的凭据。<br>原子地标记旧记录已使用并保存恰好一条新记录，新记录 owner 相同、used=false、期限为当前起 7 天、主键为不同 UUID。<br>返回 id、owner、expiresAt。 | 用户将 token 交给 Refresh 时，只有未使用且未过期的凭据能轮换；旧记录标记为已使用与保存恰好一条新记录必须原子完成，新记录保持相同 owner、used=false，期限从当前起算 7 天，主键换成不同的 UUID，响应为 id、owner、expiresAt。 |
| S04 | 重放和未知 token 返回 409 Invalid，过期返回 400 Expired，失败不新增记录。 | Refresh 重放或者 token 未知，返回 409 Invalid。过期返回 400 Expired。失败别新增记录。 | Refresh 的失败：重放、未知 token 返回 409 Invalid，过期返回 400 Expired。<br>失败不新增记录。 | 再次提交已轮换的 token 或提交未知 token，会得到 409 Invalid，凭据过期则得到 400 Expired；轮换失败不能新增记录。 |
| S05 | Touch 接收 token，仅 owner 为 alice 时将 used 设为 true 并返回 200 与凭据字段； | Touch 传 token，只能在 owner 是 alice 时把 used 设成 true，返回 200 和凭据字段。 | Touch：接收 token，仅当 owner 为 alice 时将 used 设为 true，返回 200 及凭据字段。 | 用户用 token 调用 Touch 时，只有 owner 为 alice 才能把 used 设成 true，并得到 200 和凭据字段。 |
| S06 | 即使原值已为 true 仍须成功，owner 不符或 token 不存在为 409 Invalid 且不改行。 | Touch 符合 owner 条件时，used 原来就是 true 也得成功。owner 不对或者 token 不存在，返回 409 Invalid，别改那一行。 | Touch 的匹配规则：used 原值已为 true 仍须成功。<br>owner 不符或 token 不存在时返回 409 Invalid，且不改行。 | 即使凭据的 used 本来已是 true，符合条件的 Touch 仍要成功；若 owner 不符或 token 不存在，则返回 409 Invalid，不改动记录。 |
| S07 | FindAccount 按 name 查账号，唯一命中返回 200 和 id、name，未命中为 404 AccountNotFound； | FindAccount 按 name 查账号。只找到一条就返回 200 和 id、name；没找到返回 404 AccountNotFound。 | FindAccount：按 name 查询账号，唯一命中返回 200 及 id、name，未命中返回 404 AccountNotFound。 | 用户按 name 调用 FindAccount，唯一命中时得到 200 与 id、name，没有命中时得到 404 AccountNotFound。 |
| S08 | 数据库因脏数据有同名多行时必须返回 500，不得任选一行。 | 库里脏数据让同名账号有多行时，必须返回 500，不能随便挑一条。 | FindAccount 的多行错误：数据库脏数据造成同名多行时必须返回 500，不得任选一行。 | 如果脏数据让数据库中出现同名多行，FindAccount 必须返回 500，不能随意选出其中一个账号。 |
| S09 | Register 接收 name、password，创建并保存账号，成功返回 id、name，不包含口令或摘要； | Register 传 name、password，创建并保存账号。成功返回 id、name，别带口令或摘要。 | Register：接收 name、password，创建并保存账号。<br>成功返回 id、name，不包含口令或摘要。 | 用户提交 name、password 给 Register 后，账号被创建并保存，成功响应包含 id、name，但不能带口令或摘要。 |
| S10 | 数据库口令必须为带独立盐的 pbkdf2-sha256$ 格式摘要，不能存明文。 | 数据库别存明文口令，要存带独立盐的 pbkdf2-sha256$ 格式摘要。 | 口令存储：必须使用带独立盐的 pbkdf2-sha256$ 格式摘要，禁止保存明文。 | 用户的口令进入数据库时必须是带独立盐的 pbkdf2-sha256$ 格式摘要，不能把明文存进去。 |
| S11 | Login 接收 name、password，正确口令返回 200 和账号 id、name； | Login 传 name、password。口令对了返回 200 和账号 id、name。 | Login：接收 name、password，正确口令返回 200 及账号 id、name。 | 用户用 name、password 调用 Login，口令正确时得到 200 和该账号的 id、name。 |
| S12 | 错误口令和不存在账号都返回完全相同的 401 BadLogin； | 口令错了和账号不存在，必须返回完全相同的 401 BadLogin。 | Login 失败一致性：错误口令与账号不存在，均返回完全相同的 401 BadLogin。 | 用户输错口令或输入不存在的账号时，看到的必须是完全相同的 401 BadLogin。 |
| S13 | 数据库中不是摘要格式的旧文本，即使与输入相同也不得登录。 | 库里那些不是摘要格式的旧文本，就算和输入一样也不能登录。 | 旧口令文本：数据库中的非摘要格式旧文本，即使等于输入，也不得用于登录成功。 | 若账号保存的是非摘要格式的旧文本，即使用户输入与它完全一致，也不能让这次登录成功。 |
| S14 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 |

自查结论：已逐条对照原需求及 traceability.md；没有检查点缺失或改动，未新增要求；名称、数字、保留行为及末句均保持原意或原样。
