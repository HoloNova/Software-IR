加个图书目录，匿名也能看详情和分页列表。创建、更新、删除只给 ADMIN，匿名写入返回 401，READER 写入返回 403。先判角色，再看图书存不存在。

创建图书只传 title，成功返回 201 和 id、title、stock。stock 固定由服务端设成 5。

更新图书只传 title，成功返回 200，stock 别动。删除成功也返回 200，要真的删掉记录。

请求里夹带 stock 就返回 400。所有被拒的请求都不能改数据库。

文章详情和分页列表公开，匿名、READER、其他 ADMIN 都能读。但 state=hidden 的文章别放进列表，读详情返回 404 PostNotFound。

文章创建、修改、删除都得是 ADMIN，作者还得是本人。匿名返回 401，角色不对返回 403。其他管理员不能越权走本人的写入路径，返回 404 PostNotFound。

创建文章传 body，author 用当前用户。state 默认 open，version 一开始是 1。

文章修改用 PATCH，传 expectedVersion 和 changes.body。成功只改传入内容，version 加一。版本旧了返回 409 StalePost，不能盖掉新值。

夹带 state 返回 400。被拒或者版本冲突都不能改库，state 保持 open。

删除文章成功返回 200，删掉本人的文章。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
