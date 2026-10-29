写操作使用 Authorization: Bearer 访问令牌识别当前用户，缺少、格式错误、未知或过期的令牌均返回空体 401，读接口允许匿名访问。
CreatePost 接收 categoryId、title、content 并创建当前用户的文章，成功返回 201 和接口合同中的 PostView，初始 state 为 DRAFT、likes 为 0、createdAt 为创建时刻，title 必须是 1 到 100 个字符的非空白文本，缺少、null、空白或过长均返回 400 INVALID_REQUEST 和 title 字段错误。
Publish 按 id 发布当前作者自己的草稿，成功返回 200 和接口合同中的 PostView，文章不存在返回 404 PostNotFound，非作者返回 403 Forbidden，已经发布返回 409 NotPublishable。
Feed 按 keyword 在已发布文章的 title 或 content 中做字面包含搜索，百分号、下划线和反斜线不是通配符，空 keyword 匹配全部已发布文章，不得混入草稿，按 createdAt、id 都降序分页返回。
ViewPost 按 id 返回已发布文章的 PublicPost，成功为 200，草稿或不存在的文章均返回 404 PostNotFound。
AttachTag 接收路径 postId 和请求中的 tagId，只允许文章作者绑定已存在的标签，成功返回空体 201，文章不存在返回 404 PostNotFound，非作者返回 403 Forbidden，标签不存在返回 404 TagNotFound，同一文章与标签重复绑定返回 409 TagAttached。
PostsByTag 按 tagId 分页返回带该标签的已发布文章，不得混入草稿、其他标签的文章或重复文章，按 createdAt、id 都降序排列，标签不存在返回 404 TagNotFound，存在但无匹配文章返回空页。
分页参数 page、size 可分别省略，默认分别为 1、20，有效范围分别为 1 到 10000、1 到 100，超出范围返回 400 BadPage，响应为 total、page、size、records，total 是全部匹配文章数，越界页仍返回正确 total。
PublicPost 及其分页元素仅包含 id、author、category、title、content、likes、createdAt，author 为 id、name，category 为 id、name，PostView 仅包含 id、author、category、title、content、state、likes、createdAt，其中 author、category 是数值主键。
LikePost 按 id 为已发布文章点赞，成功返回 201 和接口合同中的 PublicPost，保存当前用户的唯一点赞记录并把该文章 likes 加一，同一用户再次点赞返回 409 AlreadyLiked，草稿或不存在文章返回 404 PostNotFound，作者也可以点赞自己的已发布文章。
同一草稿被作者并发发布时恰好一次成功，其余为 409 NotPublishable，同一文章与标签被作者并发绑定时恰好一次成功，其余为 409 TagAttached，同一用户并发点赞同一文章时恰好一次成功，其余为 409 AlreadyLiked，不同用户并发点赞都成功且总计数与点赞记录数相等。
所有拒绝都不能改变已有行或新增业务记录，成功只改变该操作应改变的行，声明错误返回接口合同中的 code、message、fields。
所有路由、请求字段和响应字段采用接口合同，路径中的字段值以 URL 为准，不能被同名请求体字段替换。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
