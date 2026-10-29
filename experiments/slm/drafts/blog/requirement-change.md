在给定软件上补回 Publish、AttachTag、PostsByTag、LikePost，保留 CreatePost、Feed、ViewPost 的现有行为。
写操作使用 Authorization: Bearer 访问令牌识别当前用户，缺少、格式错误、未知或过期的令牌均返回空体 401，读接口允许匿名访问。
Publish 按 id 发布当前作者自己的草稿，成功返回 200 和接口合同中的 PostView，文章不存在返回 404 PostNotFound，非作者返回 403 Forbidden，已经发布返回 409 NotPublishable。
AttachTag 接收路径 postId 和请求中的 tagId，只允许文章作者绑定已存在的标签，成功返回空体 201，文章不存在返回 404 PostNotFound，非作者返回 403 Forbidden，标签不存在返回 404 TagNotFound，同一文章与标签重复绑定返回 409 TagAttached。
PostsByTag 按 tagId 分页返回带该标签的已发布文章，不得混入草稿、其他标签的文章或重复文章，按 createdAt、id 都降序排列，标签不存在返回 404 TagNotFound，存在但无匹配文章返回空页。
分页参数 page、size 可分别省略，默认分别为 1、20，有效范围分别为 1 到 10000、1 到 100，超出范围返回 400 BadPage，响应为 total、page、size、records，total 是全部匹配文章数，越界页仍返回正确 total。
LikePost 按 id 为已发布文章点赞，成功返回 201 和接口合同中的 PublicPost，保存当前用户的唯一点赞记录并把该文章 likes 加一，同一用户再次点赞返回 409 AlreadyLiked，草稿或不存在文章返回 404 PostNotFound，作者也可以点赞自己的已发布文章。
同一草稿被作者并发发布时恰好一次成功，其余为 409 NotPublishable，同一文章与标签被作者并发绑定时恰好一次成功，其余为 409 TagAttached，同一用户并发点赞同一文章时恰好一次成功，其余为 409 AlreadyLiked，不同用户并发点赞都成功且总计数与点赞记录数相等，所有拒绝都不能改变已有行或新增业务记录，成功只改变该操作应改变的行，声明错误返回接口合同中的 code、message、fields，公开响应的字段和嵌套结构与合同一致，所有路由和请求字段采用接口合同，路径字段以 URL 为准，不能被同名请求体字段替换。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
