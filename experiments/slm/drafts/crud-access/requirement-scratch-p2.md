- 图书目录访问：匿名可读详情和分页列表。

- 只有 ADMIN 能创建、更新、删除。

- 匿名写入返回 401，READER 写入返回 403。

- 角色判断必须先于图书存在性判断。

- 创建图书：只接收 title，成功返回 201 及 id、title、stock。

- stock 固定由服务端初始化为 5。

- 更新图书只接收 title，成功返回 200 且保留 stock。

- 删除图书成功返回 200，并实际删除记录。

- 图书请求拒绝规则：夹带 stock 返回 400，所有拒绝均不改数据库。

- 文章公开读取：匿名、READER、其他 ADMIN 均可读详情和分页列表。

- state=hidden 的文章不入列表，详情返回 404 PostNotFound。

- 文章写权限：创建、修改、删除同时要求 ADMIN 角色及本人作者归属。

- 匿名返回 401，错误角色返回 403。

- 其他管理员越权访问本人写入路径按 404 PostNotFound 处理。

- 创建文章：接收 body，author 由当前用户确定，state 默认为 open，version 初始为 1。

- 修改文章：使用 PATCH，接收 expectedVersion、changes.body。

- 成功只改传入内容并使 version 加一。

- 版本过旧返回 409 StalePost，不得覆盖新值。

- 文章拒绝规则：夹带 state 返回 400。

- 拒绝或版本冲突不得改库，state 保持 open。

- 删除文章：成功返回 200，并删除本人文章。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
