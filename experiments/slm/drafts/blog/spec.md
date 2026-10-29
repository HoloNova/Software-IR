# 博客题出题规格（v1 已冻结）

## 0. 定位、基线与交付边界

本规格的“未调用／待采样批准”描述两题冻结时状态，不维护逐轮采样计数；后续采样、修复与费用以[数据报告](../../../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-blog-v7-initial)为准，规范业务范围不随候选结果更改。

基线为 `main` 的 `c1ea338`。本题 family 为 `content.blog`，split 为 `train`，两种题型的正式 ID 为 `content-blog-change-v1`、`content-blog-scratch-v1`；同源题面、改说法和后续变体留在同一集合。

**选择“作者建草稿 → 挂标签 → 发布 → 匿名阅读 → 读者点赞”的 7 能力闭环，不把整个博客的 22 个展开能力搬进题目。**注册登录、分类/标签管理、评论、作者完整 CRUD 不考；用户、分类、标签和有效访问令牌由判卷夹具预置。认证仍走数据库中的真实 Bearer 凭据，不以任意用户 ID 请求头或固定身份替代。

这份文件保留已批准的出题设计。对应的裁剪参考已在 `66c276915d825f4d1914da721b1ddf4e0ceda2de` 的 [verify #200](https://github.com/HoloNova/Software-IR/actions/runs/40839225636) **静态／构建／启动／业务四门通过，业务 425/0**；并非继承原完整主题的证据。原 `sir-toolchain-application/src/test/resources/topics/blog/project.sir` 不改。现已确认 base 的静态负例并冻结两份正式 task／仅参考批次，模型 API 尚未调用；后续教师须独立过四门。实现与逐项追溯见 [traceability.md](traceability.md)。

本题独立落地路径为 `sir-toolchain-application/src/test/resources/valid/blog.sir`、`conformance/mysql/blog-ddl.sql`、`blog-seed.sql` 和 `BlogBusinessConformanceIT.java`，再关联两份 task；这些路径已落实为实现并通过本题参考 CI 验收。**不能仍把完整原主题设为新题 reference_source**：它多出接口、表和评论响应，与本规格不同。

### 0.1 参考了什么

下文 `A/` 指 `sir-toolchain-application/src/test/java/io/kcg/sir/application/`，`G/` 指 `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/`，`L/` 指 `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/`，`R` 指 `docs/SIR_LANGUAGE_REFERENCE.md`；行号对应上述基线。

| 材料 | 本规格采用的做法 |
|---|---|
| `experiments/slm/drafts/token-desk/` 全部文件，包括 base、两份原题面、contract、notes、traceability 及改说法/对照表 | 改写题明确保留行为；从零题不依赖 base；接口/实体名和原句可追溯；夹具前置不冒充业务要求 |
| `experiments/slm/tasks/token-desk-change-v1.json` | 合同与 DDL、参考、oracle 绑定；同一业务 oracle 运行参考或教师候选 |
| `A/conformance/TokenDeskBusinessConformanceIT.java`:35–44、96–128、131–247 | 夹具 → 编译生成 → 构建 → 启动 → HTTP 与独立 JDBC 检查；该 IT **没有另外调用 `*Checks` 类**，业务断言就在四个私有 assert 方法中，不虚构可直接复用的 TokenDeskChecks |
| `A/conformance/BusinessSliceHarness.java`:31–39、429–446、635–675 | schema/DDL/seed 属于夹具；失败要记录；拒绝后比较完整行，不能只查行数 |
| `sir-toolchain-application/src/test/resources/conformance/mysql/token-desk-ddl.sql`:1–22 | SQL 不限定 schema，由 harness 在本轮自有 schema 中执行；但本题必须真的建立两组联合唯一约束，不能照搬 token-desk 故意容纳脏数据的做法 |
| `experiments/slm/drafts/new-problem-survey.md` §4 | 保留发布可见性、标签关联、重复点赞/计数、并发及拒绝不写库；为控制规模明确放弃本版评论链路 |

## 1. 范围与裁剪清单

保持 `software CampusBlog`、namespace `example.blog`、原 Java 21 / Spring Boot / MyBatis-Plus / MySQL / Maven / REST 外壳。这里的“保留”指新裁剪参考应保留的行为，不表示已经改动文件。

### 1.1 七个能力

| 能力 | 来源/裁剪方式 | 保留理由 |
|---|---|---|
| CreatePost | 从原 `use Crud { entity: Post; owner: author; path: myposts; }` 展开结果中只保留 CreatePost、所需 input/view | 有真实创建入口，不靠判卷直接插入文章代替正常流程；作者从当前身份取得 |
| Publish | 保留原工作流、错误和路径 | 作者授权、状态流转、重复发布与并发唯一赢家 |
| Feed | 保留 | 公开性、字面搜索、稳定排序与分页 |
| ViewPost | 工作流不变；output 从 PostDetail 改为已有 PublicPost | 仍验证发布前不可见/发布后可见；删除评论后不保留虚假的 comments 输出 |
| AttachTag | 保留 | 作者授权、两端不存在、重复关系冲突 |
| PostsByTag | 保留 | 请求选择的标签与文章关系、发布状态共同筛选，防漏条件/重复根行 |
| LikePost | 保留 | 每用户一次、计数与关系同时提交、不同用户并发不丢数 |

原主题的 CreateTag、CreateCategory、Categories、PostsByCategory、AddComment、Comments、DeleteComment，以及 Crud 展开的 GetPost、ListPost、UpdatePost、DeletePost 不纳入；移除两个登录/注册 use，且不保留其 Login/Refresh/Logout/Register。不能保留 `use Crud` 再声称只有一个创建能力，它实际会展开五个。

### 1.2 裁剪后全部声明

表中字段顺序也是建议固定的存储/响应顺序；测试比较 JSON key 集合，不依赖 JSON 对象成员顺序。

| 种类 | 名称与完整成员 |
|---|---|
| enum | PostState：DRAFT、PUBLISHED |
| entity | User：`id: Id generated auto`；`name: Name unique` |
| entity | AccessToken：`id: Token generated uuid`；`owner: Ref<User>`；`expiresAt: Moment`；`credential subject owner expires expiresAt` |
| entity | Category：`id: Id generated auto`；`name: Code unique` |
| entity | Post：`id: Id generated auto`；`author: Ref<User>`；`category: Ref<Category>`；`title: Name`；`content: Text`；`state: PostState default PostState.DRAFT`；`likes: Count default 0`；`createdAt: Moment default now()` |
| entity | Tag：`id: Id generated auto`；`name: Code unique` |
| entity | PostTag：`id: Id generated auto`；`post: Ref<Post>`；`tag: Ref<Tag>`；`unique (post, tag)` |
| entity | Like：`id: Id generated auto`；`post: Ref<Post>`；`user: Ref<User>`；`unique (post, user)` |
| view | UserView from User：id、name |
| view | CategoryView from Category：id、name |
| view | PostView from Post：id、author（Ref<User>，返回数值主键）、category（Ref<Category>，返回数值主键）、title、content、state、likes、createdAt，类型逐项同 Post |
| view | PublicPost from Post：id、author（UserView）、category（CategoryView）、title、content、likes、createdAt，其他类型逐项同 Post |
| input | CreatePostInput：`category: Ref<Category>`、`title: Name`、`content: Text`；**对应 HTTP 字段为 categoryId、title、content** |
| input | PostRef：`id: Id` |
| input | FeedQuery：`keyword: Text`、`page: PageNo`、`size: PageSize` |
| input | AttachTagInput：`postId: Id`、`tagId: Id` |
| input | TagQuery：`tagId: Id`、`page: PageNo`、`size: PageSize` |
| error | BadPage：400；Forbidden：403；PostNotFound、TagNotFound：404；NotPublishable、TagAttached、AlreadyLiked：409 |
| capability | 仅 §1.1 七项，所有写能力均 authenticated + atomic，三个读能力均公开 readonly |

User.name、AccessToken 来自原主题所用标准功能的展开；UserView 来自 AccountRegistration 的展开。显式提取这些声明，删除不再使用的 passwordHash、RefreshToken、TokenPair、登录/注册 input/view/error；既不保留密码存储，也不新增“发令牌”接口。来源：`sir-semantic/src/main/resources/io/kcg/sir/semantic/feature/dual-token-account.sir`:1–4、`dual-token-login.sir`:1–6、`account-registration.sir`:5–11。

Comment、CommentView、PostDetail 及其专用输入/错误一并删除；Category/Tag 虽无管理接口，仍由外键和公开嵌套响应使用，不能删掉。不得添加 Version、scope、角色管理员或额外业务限制：本版均未要求。

### 1.3 改写 base 的机械差异

以**本规格裁剪参考**为起点，仅删除四个完整 capability：Publish、AttachTag、PostsByTag、LikePost。保留 CreatePost、Feed、ViewPost 及其现有行为；所有实体、enum、view、input、error 和外壳均不删改，即使有暂时未引用的声明。

因此 base 仍能创建草稿、匿名查询和读取夹具中的已发布文章；它不是空壳。仅有 base 时四个路由不存在，无法满足下述成功/并发检查。同 SHA CI 的契约检查及该 CI 原编译器的内存生成已确认 base 完整生成 47 文件且缺上述四个 Controller；这是静态负例，不是已经运行过 base 的 HTTP 失败报告。

## 2. 完整 MySQL DDL（夹具）

格式与 token-desk 一样，不写 CREATE DATABASE/USE，由 harness 选择自有 schema。所有表用 InnoDB；固定 `utf8mb4_bin`，避免本题文本检查依赖服务器默认排序规则。`Like` 映射为 MySQL 保留字表名 **`like`**，必须加反引号，不改成另一个实体名。

Name 最长 100、Code 最长 32，不能误套 token-desk 中 Text 的 VARCHAR(64)。Post.content 无长度上限，采用 LONGTEXT；Moment 为 DATETIME(6)，无服务器 NOW 默认值，创建时刻由应用写入。数据库类型/长度依据 R:86–109、311、2720 及 `L/api/SpringBootDeploymentPlanner.java`:37–76、217–230；命名依据 `L/internal/SpringBootModelLowerer.java`:559–561、657、1940–1948。

```sql
-- TEST_FIXTURE_DDL: isolated owned schema; not product initialization/migration.
CREATE TABLE `user` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY blog_user_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE access_token (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT blog_access_owner FOREIGN KEY (owner_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY blog_category_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE post (
    id BIGINT NOT NULL AUTO_INCREMENT,
    author_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    content LONGTEXT NOT NULL,
    state VARCHAR(9) NOT NULL DEFAULT 'DRAFT',
    likes INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT blog_post_author FOREIGN KEY (author_id) REFERENCES `user` (id),
    CONSTRAINT blog_post_category FOREIGN KEY (category_id) REFERENCES category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE tag (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY blog_tag_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE post_tag (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY blog_post_tag (post_id, tag_id),
    CONSTRAINT blog_post_tag_post FOREIGN KEY (post_id) REFERENCES post (id),
    CONSTRAINT blog_post_tag_tag FOREIGN KEY (tag_id) REFERENCES tag (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `like` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY blog_post_like (post_id, user_id),
    CONSTRAINT blog_like_post FOREIGN KEY (post_id) REFERENCES post (id),
    CONSTRAINT blog_like_user FOREIGN KEY (user_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

## 3. 种子数据与请求记号

时间均按 UTC；harness 的应用 JVM、JDBC 和观察连接保持 UTC。有效凭据截至 2099 年，过期凭据固定在 2000 年，不用等待真实超时。用户不是通过被测接口注册的；不能把这些 INSERT 算作候选通过了创建行为。

```sql
INSERT INTO `user` (id, name) VALUES
    (1, 'alice'), (2, 'bob'), (3, 'carol'), (4, 'dave'), (5, 'erin'),
    (6, 'frank'), (7, 'grace'), (8, 'heidi'), (9, 'ivan'), (10, 'judy');

INSERT INTO access_token (id, owner_id, expires_at) VALUES
    ('10000000-0000-4000-8000-000000000001', 1, '2099-01-01 00:00:00.000000'),
    ('10000000-0000-4000-8000-000000000002', 2, '2099-01-01 00:00:00.000000'),
    ('10000000-0000-4000-8000-000000000003', 3, '2099-01-01 00:00:00.000000'),
    ('10000000-0000-4000-8000-000000000004', 4, '2099-01-01 00:00:00.000000'),
    ('10000000-0000-4000-8000-000000000005', 5, '2099-01-01 00:00:00.000000'),
    ('10000000-0000-4000-8000-000000000006', 6, '2099-01-01 00:00:00.000000'),
    ('10000000-0000-4000-8000-000000000007', 7, '2099-01-01 00:00:00.000000'),
    ('10000000-0000-4000-8000-000000000008', 8, '2099-01-01 00:00:00.000000'),
    ('10000000-0000-4000-8000-000000000009', 9, '2099-01-01 00:00:00.000000'),
    ('10000000-0000-4000-8000-000000000010', 10, '2099-01-01 00:00:00.000000'),
    ('10000000-0000-4000-8000-000000000099', 1, '2000-01-01 00:00:00.000000');

INSERT INTO category (id, name) VALUES (10, 'tech'), (11, 'life');
INSERT INTO tag (id, name) VALUES (20, 'sir'), (21, 'java'), (22, 'empty');

INSERT INTO post (id, author_id, category_id, title, content, state, likes, created_at) VALUES
    (101, 1, 10, 'draft alpha', 'hidden', 'DRAFT', 0, '2026-01-05 00:00:00.000000'),
    (102, 1, 10, 'alpha%beta', 'plain', 'PUBLISHED', 0, '2026-01-02 00:00:00.000000'),
    (103, 2, 11, 'alpha_beta', 'plain', 'PUBLISHED', 0, '2026-01-02 00:00:00.000000'),
    (104, 1, 11, 'plain', 'alpha body', 'PUBLISHED', 0, '2026-01-01 00:00:00.000000'),
    (105, 1, 10, 'never public', 'alpha hidden', 'DRAFT', 0, '2026-01-04 00:00:00.000000'),
    (106, 1, 10, 'other', 'unrelated', 'PUBLISHED', 0, '2026-01-03 00:00:00.000000'),
    (107, 2, 11, CONCAT('alpha', CHAR(92), 'beta'), 'plain', 'PUBLISHED', 0, '2026-01-03 00:00:00.000000'),
    (108, 1, 10, 'publish race', 'race', 'DRAFT', 0, '2026-01-06 00:00:00.000000'),
    (109, 1, 10, 'same liker race', 'race', 'PUBLISHED', 0, '2026-01-07 00:00:00.000000'),
    (110, 1, 10, 'different likers race', 'race', 'PUBLISHED', 0, '2026-01-08 00:00:00.000000'),
    (111, 1, 10, 'tag race', 'race', 'DRAFT', 0, '2026-01-09 00:00:00.000000');

INSERT INTO post_tag (id, post_id, tag_id) VALUES
    (1, 101, 20), (2, 102, 20), (3, 103, 20), (4, 106, 20),
    (5, 102, 21), (6, 104, 21);
-- like 初始为空；文章的 likes 全为 0。
```

`A(n)` 表示头 `Authorization: Bearer 10000000-0000-4000-8000-` 加上 n 的十二位十进制补零文本，n=1…10；例如 A(2) 对应 bob。`anon` 表示不带 Authorization；`E` 表示尾号 99 的过期令牌；未知合法 UUID 固定为 `99999999-9999-4999-8999-999999999999`。不存在的文章/标签 ID 统一为 999999。

种子自检：7 张表行数依次为 user=10、access_token=11、category=2、post=11、tag=3、post_tag=6、like=0；所有外键存在、每个 post.likes 等于点赞行数。自检失败记夹具错误，不判教师业务失败。

## 4. 接口合同（与 contract.json 一致）

使用现有 contract 的 namespace/capabilities/entities/interfaces/enums/declared_errors/storage 结构；success_status、body_kind 和分页数值是本规格补充的显式约定。后续导出 JSON 时不得丢弃这些约定。

```json
{
  "namespace": "example.blog",
  "family": "content.blog",
  "split": "train",
  "capabilities": ["CreatePost", "Publish", "Feed", "ViewPost", "AttachTag", "PostsByTag", "LikePost"],
  "entities": ["User", "AccessToken", "Category", "Post", "Tag", "PostTag", "Like"],
  "interfaces": {
    "CreatePost": {
      "route": "POST /api/myposts", "authentication": "Bearer",
      "request_fields": ["categoryId", "title", "content"],
      "response_fields": ["id", "author", "category", "title", "content", "state", "likes", "createdAt"],
      "body_kind": "PostView", "success_status": 201,
      "errors": {"unauthenticated": 401, "INVALID_REQUEST": 400}
    },
    "Publish": {
      "route": "POST /api/my/posts/{id}/publish", "authentication": "Bearer",
      "path_fields": ["id"], "request_fields": [],
      "response_fields": ["id", "author", "category", "title", "content", "state", "likes", "createdAt"],
      "body_kind": "PostView", "success_status": 200,
      "errors": {"unauthenticated": 401, "Forbidden": 403, "PostNotFound": 404, "NotPublishable": 409}
    },
    "Feed": {
      "route": "GET /api/posts", "authentication": "none",
      "request_fields": ["keyword", "page", "size"],
      "response_fields": ["total", "page", "size", "records"],
      "body_kind": "Page<PublicPost>", "success_status": 200,
      "errors": {"BadPage": 400}
    },
    "ViewPost": {
      "route": "GET /api/posts/{id}", "authentication": "none",
      "path_fields": ["id"], "request_fields": [],
      "response_fields": ["id", "author", "category", "title", "content", "likes", "createdAt"],
      "body_kind": "PublicPost", "success_status": 200,
      "errors": {"PostNotFound": 404}
    },
    "AttachTag": {
      "route": "POST /api/posts/{postId}/tags", "authentication": "Bearer",
      "path_fields": ["postId"], "request_fields": ["tagId"],
      "response_fields": [], "body_kind": "empty", "success_status": 201,
      "errors": {"unauthenticated": 401, "Forbidden": 403, "PostNotFound": 404, "TagNotFound": 404, "TagAttached": 409}
    },
    "PostsByTag": {
      "route": "GET /api/tags/{tagId}/posts", "authentication": "none",
      "path_fields": ["tagId"], "request_fields": ["page", "size"],
      "response_fields": ["total", "page", "size", "records"],
      "body_kind": "Page<PublicPost>", "success_status": 200,
      "errors": {"TagNotFound": 404, "BadPage": 400}
    },
    "LikePost": {
      "route": "POST /api/posts/{id}/like", "authentication": "Bearer",
      "path_fields": ["id"], "request_fields": [],
      "response_fields": ["id", "author", "category", "title", "content", "likes", "createdAt"],
      "body_kind": "PublicPost", "success_status": 201,
      "errors": {"unauthenticated": 401, "PostNotFound": 404, "AlreadyLiked": 409}
    }
  },
  "public_post_nested_fields": {"author": ["id", "name"], "category": ["id", "name"]},
  "pagination": {"default_page": 1, "default_size": 20, "max_page": 10000, "max_size": 100},
  "enums": {"PostState": ["DRAFT", "PUBLISHED"]},
  "declared_errors": {"BadPage": 400, "Forbidden": 403, "PostNotFound": 404, "TagNotFound": 404, "NotPublishable": 409, "TagAttached": 409, "AlreadyLiked": 409},
  "storage": {
    "user": ["id", "name"],
    "access_token": ["id", "owner_id", "expires_at"],
    "category": ["id", "name"],
    "post": ["id", "author_id", "category_id", "title", "content", "state", "likes", "created_at"],
    "tag": ["id", "name"],
    "post_tag": ["id", "post_id", "tag_id"],
    "like": ["id", "post_id", "user_id"]
  },
  "readiness": "GET /api/posts?keyword=&page=1&size=10",
  "fixtures": {"provided_by_oracle": true}
}
```

说明：

- 路径字段只从 URL 取；无其余输入的 POST 发送 `{}`。request_fields 不重复列 path_fields，合起来才是完整输入；发布路径 `/my/posts/…` 与创建路径 `/myposts` 刻意沿用原主题，不能擅自统一改名。
- PostView 的 author/category 是数值 ID；PublicPost 的 author/category 是嵌套对象。不能把公开响应误写成 authorId/categoryId，也不能把 CreatePost 的 HTTP categoryId 写成嵌套对象。完整字段类型见 §1.2。
- 正常创建测试只用存在的 categoryId 和非 null content。原 Crud 创建没有加载 Category 并映射 CategoryNotFound，**本版不凭空要求“创建时分类不存在 → 404”**，也不因工具链现已统一非 Optional 必填而临时追加 category/content 缺失评分；本题只对标题测试已写明的校验。
- 所有公开响应和每个分页元素的 key 集合必须精确匹配；不能漏字段、加 state/comments/口令/凭据。声明错误为 `{"code":"错误名","message":"错误名","fields":[]}`；401 是认证拦截器空体响应，不强行解析 JSON。title 校验错误为 400 INVALID_REQUEST，fields 中检查对应 title 的 NotBlank/Size，不固定本地化 message 文本。

## 5. 两种需求原文

各代码块是可直接取出的题面；编号不混入正文。scratch 按行依次为 S01–S14，change 为 C01–C09；最后一行是输出格式要求，单列作静态/格式检查，不伪造 HTTP 断言。

### 5.1 改写题

```text
在给定软件上补回 Publish、AttachTag、PostsByTag、LikePost，保留 CreatePost、Feed、ViewPost 的现有行为。
写操作使用 Authorization: Bearer 访问令牌识别当前用户，缺少、格式错误、未知或过期的令牌均返回空体 401，读接口允许匿名访问。
Publish 按 id 发布当前作者自己的草稿，成功返回 200 和接口合同中的 PostView，文章不存在返回 404 PostNotFound，非作者返回 403 Forbidden，已经发布返回 409 NotPublishable。
AttachTag 接收路径 postId 和请求中的 tagId，只允许文章作者绑定已存在的标签，成功返回空体 201，文章不存在返回 404 PostNotFound，非作者返回 403 Forbidden，标签不存在返回 404 TagNotFound，同一文章与标签重复绑定返回 409 TagAttached。
PostsByTag 按 tagId 分页返回带该标签的已发布文章，不得混入草稿、其他标签的文章或重复文章，按 createdAt、id 都降序排列，标签不存在返回 404 TagNotFound，存在但无匹配文章返回空页。
分页参数 page、size 可分别省略，默认分别为 1、20，有效范围分别为 1 到 10000、1 到 100，超出范围返回 400 BadPage，响应为 total、page、size、records，total 是全部匹配文章数，越界页仍返回正确 total。
LikePost 按 id 为已发布文章点赞，成功返回 201 和接口合同中的 PublicPost，保存当前用户的唯一点赞记录并把该文章 likes 加一，同一用户再次点赞返回 409 AlreadyLiked，草稿或不存在文章返回 404 PostNotFound，作者也可以点赞自己的已发布文章。
同一草稿被作者并发发布时恰好一次成功，其余为 409 NotPublishable，同一文章与标签被作者并发绑定时恰好一次成功，其余为 409 TagAttached，同一用户并发点赞同一文章时恰好一次成功，其余为 409 AlreadyLiked，不同用户并发点赞都成功且总计数与点赞记录数相等，所有拒绝都不能改变已有行或新增业务记录，成功只改变该操作应改变的行，声明错误返回接口合同中的 code、message、fields，公开响应的字段和嵌套结构与合同一致，所有路由和请求字段采用接口合同，路径字段以 URL 为准，不能被同名请求体字段替换。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
```

### 5.2 从零写题

```text
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
```

题面与合同、§1.2 存储声明清单共同提供给出题流程；DDL/seed 由 oracle 提供，不要求候选自己建表/造数。没有“使用某种 SQL/守卫语法/先写哪张表”等实现提示。

## 6. 判卷检查清单与逐句追溯

### 6.1 执行与断言约定

一个 `BlogBusinessConformanceIT` 复用 BusinessSliceHarness，用同一套 HTTP/JDBC 检查运行裁剪参考或候选，不能用金标准源替换候选。实现已拆为 BlogPublicationChecks、BlogTagChecks、BlogLikeChecks，并由 BlogConcurrencyChecks、BlogConcurrentRequests、BlogLockWaits 承接真实竞争；公共比较与七表观察分别在 BlogAssertions、BlogFixture，复用同一个应用进程和原报告协议。

§6.2–§6.6 每小节开始前重放同一套 seed；同小节按表中顺序运行。重置只由夹具控制连接在无在途请求时执行，先删 like/post_tag，再 post/access_token/category/tag/user，随后按 §3 插入；保留一个生成应用进程，不为每行重建工程。§6.7 每一轮单独重置。自增后续 ID 只从 HTTP 响应取得，不假定无间隙或下一值等于 112。

下文 `PV(p)` / `PP(p)` 分别指 §4 的 PostView / PublicPost：**串行检查逐字段和 JDBC 同一文章比对**，PP 的嵌套 user/category 还须匹配这两表的 id/name，不能只检查字段存在；并发轮次按 §6.7 比较整轮最终差异，响应中的动态 likes 按该轮规则检查，不拿较早响应的 likes 与整轮结束后的最终值硬比。`PAGE(ids,total,page,size)` 同时断言 200、精确外层四个键、每项完整 PP、总数、页参数和 id 的完整顺序；GET 请求体均为无。`ERR(n,E)` 断言状态 n 和精确声明错误信封；AUTH401 单独断言 401 空体。Moment 解析为 UTC 时刻后比较，不把 JDBC 日期文本与 JSON 直接作字符串比较；新建行响应与 DATETIME(6) 之间允许至多 1 ms 的绑定/存储精度差，随后读取的时间仍须与同一存储值一致。数值检查 JSON 数字类型与数值，不把带引号的字符串当整数。

`UNCHANGED` 表示通过独立观察连接读取七张表的**全部列、全部行、按主键排序**，请求前后内容完全相等；不是只检查行数或 likes。自增分配器出现空号不属于业务行变化，不比较 information_schema 的 AUTO_INCREMENT。串行的每个成功写请求也比较全库差异，只允许清单列出的变化；串行及并发结束后的 GET 也要求 UNCHANGED。使用解析后的 JSON 比较，不沿用 harness 中专为课程 code/name/capacity 写的 records 文本提取器。

C01 的“保留”承接 S02、S04、S05、S08、S09、S13 在 base 中的既有行为；下表同时给出直接要求，避免改写题只测新接口。S14/C09 是输出格式，由静态门检查，非业务 HTTP 要求。

### 6.2 身份与创建

| 检查 | HTTP 请求（前置/头/体） | 期望状态与响应 | 独立数据库验证 | 原句 |
|---|---|---|---|---|
| B01 公开入口/readiness | anon，`GET /api/posts?keyword=&page=1&size=10` | PAGE([110,109,107,106,103,102,104],7,1,10) | UNCHANGED；草稿 101/105/108/111 仍存在但不入列 | S01/S04/S08/S09/S13；C01/C02 |
| B02 每个写入口拒绝非法身份 | 对 `POST /api/myposts` 体 `{"categoryId":10,"title":"auth","content":"body"}`、`POST /api/my/posts/101/publish` 体 `{}`、`POST /api/posts/101/tags` 体 `{"tagId":21}`、`POST /api/posts/102/like` 体 `{}`，分别用无头、`Bearer not-a-uuid`、`Bearer <§3未知UUID>`、E 四种身份，共 16 请求；均使用有效业务参数 | 每次 AUTH401；不要求业务错误 JSON | **逐次** UNCHANGED，不能只比较整个循环最终状态 | S01/S12；C02/C08 |
| B03 创建作者 A | A(1)，`POST /api/myposts`，`{"categoryId":10,"title":"flow article","content":"flow payload"}` | 201，PV(pA)，author=1、category=10、state=DRAFT、likes=0 | 只新增该 post；其全部列与输入/PV 一致；created_at 在请求前后 UTC 时刻间（数据库微秒截断容差 1 ms）；其他表不变 | S02/S09/S12/S13；C01 |
| B04 创建作者 B | A(2)，同路由，`{"categoryId":11,"title":"bob article","content":"bob payload"}` | 201，PV(pB)，author=2、category=11、不同的新 id | 只新增 bob 的 post，不能把作者写死为 alice；检查默认值与时间同 B03 | S01/S02/S09/S12；C01/C02 |
| B05 标题校验 | A(1)，同创建路由，categoryId=10、content="body"；title 分别省略、null、空串、三个空格、101 个 ASCII a | 每次 400 INVALID_REQUEST；fields 含 path=title，缺省/null/空白检查 NotBlank，过长检查 Size；不固定错误条目顺序/message | 每次 UNCHANGED | S02/S12；C01/C08 |
| B06 合法长度上界/普通正文 | A(1)，`POST /api/myposts`，categoryId=10、title 为 100 个 ASCII t、content 为 1001 个 ASCII b 的 JSON | 201 PV(pLong)，title/content 逐字返回 | 只新增一个草稿，正文不可截断；默认值和创建时间同 B03，防把 Name 偷限为 32/64 或把 Text 偷限为 Remark | S02/S09/S12；C01 |

### 6.3 发布与阅读闭环

本小节首先再次执行 B03 取得 pA，然后依次执行下列检查。

| 检查 | HTTP 请求 | 期望状态与响应 | 独立数据库验证 | 原句 |
|---|---|---|---|---|
| P01 草稿不可见 | anon，`GET /api/posts/{pA}`；`GET /api/posts?keyword=flow&page=1&size=10` | 前者 ERR(404,PostNotFound)，后者 PAGE([],0,1,10) | 两次均 UNCHANGED，pA 的 state 仍为 DRAFT | S04/S05/S12；C01/C08 |
| P02 作者外拒绝 | A(2)，`POST /api/my/posts/{pA}/publish`，`{}` | ERR(403,Forbidden) | UNCHANGED，不得先改为已发布再拒绝 | S03/S12；C03/C08 |
| P03 不存在文章 | A(1)，`POST /api/my/posts/999999/publish`，`{}` | ERR(404,PostNotFound) | UNCHANGED | S03/S12；C03/C08 |
| P04 作者发布、路径优先 | A(1)，`POST /api/my/posts/{pA}/publish`，`{"id":105}` | 200，PV(pA)，state=PUBLISHED；返回的不是 105 | 仅 pA.state 从 DRAFT 改为 PUBLISHED；105 仍 DRAFT，其他列/表不变 | S03/S09/S12/S13；C03/C08 |
| P05 发布后读到同一行 | anon，`GET /api/posts/{pA}`；`GET /api/posts?keyword=flow&page=1&size=10` | 200 PP(pA)；PAGE([pA],1,1,10) | UNCHANGED；响应 title/content/author/category/likes/createdAt 与已创建行一致 | S04/S05/S09/S12；C01/C08 |
| P06 重放发布 | A(1)，`POST /api/my/posts/{pA}/publish`，`{}` | ERR(409,NotPublishable) | UNCHANGED，created_at 不重写 | S03/S12；C03/C08 |
| P07 公开不存在/草稿 | anon，分别 `GET /api/posts/999999`、`GET /api/posts/105` | 每次 ERR(404,PostNotFound) | UNCHANGED，不能删草稿伪装不可见 | S05/S12；C01/C08 |

### 6.4 搜索、排序、分页与嵌套投影

以下均为 anon。每次响应均逐项 PP/JDBC 比对并验证 UNCHANGED。

| 检查 | HTTP 请求 | 期望状态与响应 | 数据库/反例重点 | 原句 |
|---|---|---|---|---|
| Q01 正常两页与同刻排序 | `GET /api/posts?keyword=alpha&page=1&size=2`；page=2；page=3 | 分别 PAGE([107,103],4,1,2)、PAGE([102,104],4,2,2)、PAGE([],4,3,2) | 104 只在 content 命中；101/105 草稿排除；102/103 同刻按 id 降序；不以本页长度充当 total | S04/S08/S09/S12；C01 |
| Q02 字面特殊字符 | `GET /api/posts?keyword=%25&page=1&size=10`；keyword=%5F；keyword=%5C | 分别 PAGE([102],1,1,10)、PAGE([103],1,1,10)、PAGE([107],1,1,10) | URL 编码后分别是百分号、下划线、反斜线，不能成为 SQL 通配符/逃逸符 | S04/S08/S09/S12；C01 |
| Q03 空搜索与无命中 | `GET /api/posts?keyword=&page=1&size=10`；keyword=zzz-no-match | PAGE([110,109,107,106,103,102,104],7,1,10)；PAGE([],0,1,10) | 空 keyword 不是只匹配空标题；零命中不返回 404 | S04/S08/S09/S12；C01 |
| Q04 各自省略 | `GET /api/posts?keyword=alpha`；`GET /api/posts?keyword=alpha&size=2`；`GET /api/posts?keyword=alpha&page=2` | PAGE([107,103,102,104],4,1,20)；PAGE([107,103],4,1,2)；PAGE([],4,2,20) | 两个参数能独立默认，不要求同时传/同时省略 | S08/S09/S12；C01/C06 |
| Q05 边界与坏分页 | anon，对 `GET /api/posts?keyword=alpha` 和 `GET /api/tags/20/posts` 分别追加 page=0/-1/10001（size=2）及 size=0/-1/101（page=1）的查询参数，共 12 请求 | 每次 ERR(400,BadPage) | 逐次 UNCHANGED；不接受静默截断 | S08/S12；C01/C06/C08 |
| Q06 有效上界 | `GET /api/posts?keyword=alpha&page=10000&size=100`；`GET /api/posts?keyword=alpha&page=1&size=100` | PAGE([],4,10000,100)；PAGE([107,103,102,104],4,1,100) | 上界本身合法；对空页仍准确 total | S08/S09/S12；C01/C06 |

### 6.5 标签绑定与关系筛选

| 检查 | HTTP 请求 | 期望状态与响应 | 独立数据库验证 | 原句 |
|---|---|---|---|---|
| T01 选择指定标签 | anon，`GET /api/tags/20/posts?page=1&size=2`；page=2 | PAGE([106,103],3,1,2)；PAGE([102],3,2,2) | UNCHANGED；101 有标签但为草稿；104 只有另一标签，不能混入；102 有两个标签也只出现一次 | S07/S08/S09/S12；C05/C06/C08 |
| T02 另一标签与空标签 | anon，`GET /api/tags/21/posts?page=1&size=10`；`GET /api/tags/22/posts?page=1&size=10` | PAGE([102,104],2,1,10)；PAGE([],0,1,10) | UNCHANGED；不能写死 tagId=20 | S07/S08/S09/S12；C05/C06/C08 |
| T03 未知标签 | anon，`GET /api/tags/999999/posts?page=1&size=10` | ERR(404,TagNotFound) | UNCHANGED；和空标签不同 | S07/S12；C05/C08 |
| T04 草稿绑定成功 | A(1)，`POST /api/posts/101/tags`，`{"tagId":21}` | 201 空体 | 仅新增 (101,21) 一行；文章/标签/其他关系均不变，不能擅自发布文章 | S06/S12；C04/C08 |
| T05 绑定后仍不泄露草稿 | anon，`GET /api/tags/21/posts?page=1&size=10` | PAGE([102,104],2,1,10) | UNCHANGED；不能用“有标签”替代“已发布” | S07/S09/S12；C05/C08 |
| T06 绑定两类不存在 | A(1)，`POST /api/posts/999999/tags`，`{"tagId":21}`；`POST /api/posts/101/tags`，`{"tagId":999999}` | 分别 ERR(404,PostNotFound)、ERR(404,TagNotFound) | 每次 UNCHANGED；不能只依赖 FK 失败返回 500 | S06/S12；C04/C08 |
| T07 非作者绑定 | A(2)，`POST /api/posts/101/tags`，`{"tagId":22}` | ERR(403,Forbidden) | UNCHANGED | S06/S12；C04/C08 |
| T08 重复绑定 | A(1)，`POST /api/posts/101/tags`，`{"tagId":21}` | ERR(409,TagAttached) | UNCHANGED，仍仅一条 (101,21) | S06/S12；C04/C08 |
| T09 绑定发布闭环 | A(1)，`POST /api/my/posts/101/publish`，`{}`；anon，`GET /api/tags/21/posts?page=1&size=10` | 前者 200 PV(101)，后者 PAGE([101,102,104],3,1,10) | 发布只改 state；随后读 UNCHANGED；原分类不被标签替换 | S03/S07/S08/S09/S12；C03/C05/C06/C08 |
| T10 标签页各自默认与越界 | anon，`GET /api/tags/20/posts`；`…?size=2`；`…?page=2`；`…?page=3&size=2`；`…?page=10000&size=100` | PAGE([101,106,103,102],4,1,20)；PAGE([101,106],4,1,2)；PAGE([],4,2,20)；PAGE([],4,3,2)；PAGE([],4,10000,100) | UNCHANGED；此时 101 已发布，createdAt 最大，必须排在第一 | S07/S08/S09/S12；C05/C06/C08 |
| T11 已发布文章也可绑定 | A(1)，`POST /api/posts/106/tags`，`{"tagId":21}`；随后 anon，`GET /api/tags/21/posts?page=1&size=10` | 前者 201 空体，后者 PAGE([101,106,102,104],4,1,10) | 写请求只增加 (106,21)，106 仍 PUBLISHED；随后读 UNCHANGED，防擅自限制只能给草稿绑定 | S06/S07/S09/S12；C04/C05/C08 |

### 6.6 点赞、回滚与读写一致性

| 检查 | HTTP 请求 | 期望状态与响应 | 独立数据库验证 | 原句 |
|---|---|---|---|---|
| L01 正常点赞 | A(2)，`POST /api/posts/102/like`，`{}` | 201 PP(102)，likes=1 | 仅新增 Like(post=102,user=2)，102.likes 从 0 到 1，其他列/表不变 | S10/S09/S12；C07/C08 |
| L02 重复点赞回滚 | A(2)，再次同请求 | ERR(409,AlreadyLiked) | UNCHANGED，尤其 likes 仍 1、Like 仍一行，不能先计数后冲突留下多加的一次 | S10/S12；C07/C08 |
| L03 不同用户与作者本人 | A(3)，同请求；再用 A(1)，同请求 | 依次 201 PP(102)，likes=2、3 | 每次只新增对应用户一条 Like 并加 1；(102,1/2/3) 各一行，likes=COUNT(*)=3 | S01/S10/S12；C02/C07/C08 |
| L04 草稿/不存在拒绝 | A(2)，`POST /api/posts/105/like`，`{}`；`POST /api/posts/999999/like`，`{}` | 每次 ERR(404,PostNotFound) | 每次 UNCHANGED，草稿 likes 仍 0 | S10/S12；C07/C08 |
| L05 三条公开读路径看到提交值 | anon，`GET /api/posts/102`；`GET /api/posts?keyword=%25&page=1&size=10`；`GET /api/tags/20/posts?page=1&size=10` | 200 PP(102) likes=3；PAGE([102],1,1,10) likes=3；PAGE([106,103,102],3,1,10) 中 102.likes=3 | UNCHANGED，逐项与 JDBC 比较；不能只有点赞响应计数正确 | S04/S05/S07/S09/S10/S12；C01/C05/C07/C08 |

### 6.7 并发：四轮独立竞争

每轮先重置 §3 数据，6 个工作线程各自创建独立 HTTP 客户端，先到达 ready latch，再由 start latch 同时放行。不得在循环里同步发六次后称“并发”。并发客户端单请求超时 30 秒，整轮收集限时 35 秒；不要直接套用 `HttpAssertionClient.java`:39、58 的固定 10 秒超时去等待数据库屏障。

为使竞争可复现，夹具另开控制连接、关闭 autocommit，先 `SELECT id FROM post WHERE id=<目标> FOR UPDATE` 持锁，再同时发请求；控制端只观察本 schema/目标行在 MySQL `performance_schema.data_lock_waits` 中的真实等待，确认六个请求均已到达该目标引起的数据库锁等待链（六个不同运行时连接/事务，允许父行锁或其关联唯一键上的等待）后释放控制事务，不能只见一个等待就提前放行。每轮记录请求 ready/start/结束和锁等待证据；观察等待限时 5 秒，任何请求提前异常或未形成竞争均记录失败并在 finally 释放锁、收齐响应，不能静默当通过。此屏障不注入应用钩子；控制账号缺少锁观测权限记环境 NOT_RUN，不删掉并发断言。夹具不主动改变业务行。

| 检查 | 六个实际 HTTP 请求 | HTTP 结果 | JDBC 最终状态与不变量 | 原句 |
|---|---|---|---|---|
| X01 发布竞争 | 全为 A(1)，`POST /api/my/posts/108/publish`，`{}` | 恰好 1 个 200 PV(108)，5 个 ERR(409,NotPublishable) | 仅 108.state 改为 PUBLISHED；整库其他列/行不变；200 不能多于一份 | S03/S11/S12；C03/C08 |
| X02 同一标签绑定 | 全为 A(1)，`POST /api/posts/111/tags`，`{"tagId":22}` | 恰好 1 个 201 空体，5 个 ERR(409,TagAttached) | 只新增一条 (111,22)，111 仍 DRAFT；其余全库行不变 | S06/S11/S12；C04/C08 |
| X03 同一用户重复点赞 | 全为 A(2)，`POST /api/posts/109/like`，`{}` | 恰好 1 个 201 PP(109) likes=1，5 个 ERR(409,AlreadyLiked) | Like(109,2) 恰好一行，109.likes=1；全库其余行不变 | S10/S11/S12；C07/C08 |
| X04 不同用户并发点赞 | 分别 A(3)…A(8)，`POST /api/posts/110/like`，`{}` | 6 个 201 PP(110)，每个返回 likes 在 1…6；不要求返回顺序或响应计数互异 | 恰好六条不同 user 的 Like，110.likes=6=COUNT(*)；其余行不变 | S10/S11/S12；C07/C08 |

各轮完成后再 anon `GET /api/posts/108`（X01）/ `GET /api/tags/22/posts?page=1&size=10`（X02，仍为空页，草稿不公开）/ `GET /api/posts/109`（X03）/ `GET /api/posts/110`（X04），分别检查 200 PP、PAGE([],0,1,10)、200 PP likes=1、200 PP likes=6；读操作 UNCHANGED。竞争失败者的“无副作用”通过整轮只允许赢家应有差异证明，不能在其他线程正成功写入时错误要求全库瞬时不变。

500、死锁、锁超时、请求超时均不能当成声明的 409 或成功；不增加服务端重试假设。全局计数不变量必须在所有请求结束后用独立 JDBC 重查，不从成功响应条数推导。

### 6.8 需求句子覆盖索引

| scratch 原句 | change 承接 | 必须存在的检查 |
|---|---|---|
| S01 身份/匿名 | C02 | B01–B04、B02 的四写接口非法身份矩阵 |
| S02 创建/默认值/标题 | C01 保留 CreatePost | B03–B06，P01；数据库创建时间与默认值 |
| S03 发布 | C03 | P02–P06、T09、X01 |
| S04 搜索 | C01 保留 Feed | B01、P01/P05、Q01–Q03、L05 |
| S05 详情可见性 | C01 保留 ViewPost | P01/P05/P07、L05 |
| S06 标签绑定 | C04 | T04、T06–T08、T11、X02 |
| S07 按标签查询 | C05 | T01–T03、T05、T09–T11、L05 |
| S08 分页 | C01 保留 Feed + C06 | Q01、Q03–Q06、T01/T02/T10；Q05 两种分页路由均展开 |
| S09 精确投影 | C01 保留 + C08 | 所有 PV/PP/PAGE 都逐字段、精确 key 和嵌套/JDBC 值比较 |
| S10 点赞 | C07 | L01–L05、X03–X04 |
| S11 四类并发 | C08 | X01–X04，锁等待和完整 HTTP/JDBC 证据 |
| S12 拒绝/成功写范围/错误信封 | C08 | 全部 UNCHANGED、成功全库差异、ERR；不是额外一条无实参检查 |
| S13 接口/路径优先 | C01 保留 + C08 | 全部请求方法/路径/字段；P04 显式 body/path 冲突 |
| S14 输出格式 | C09 | 静态检查输入为完整 sir 0.3，不将 Java/SQL/解释当答案；不算业务 HTTP 检查 |

夹具初始行数、CI 开关、schema 清理、构建、readiness 和最终汇总是执行器前置/汇总，不能额外要求候选“实现判卷器”。它们失败照样阻断有效证据，但与业务原句分开报告。

## 7. 依赖生成器行为的风险与依据

| 风险/本规格裁决 | 语言参考依据（R 的行号） | 本轮直接核对的源码与落地要求 |
|---|---|---|
| 新建行即 201，不能把点赞当普通更新 200；AttachTag 是 201 空体，不是 204 | R:895–915（HTTP 映射、创建 201）、R:105（Unit 无内容） | `G/ControllerRenderer.java`:127–131、181–189、209–220：含 CreateStep 判为创建，VOID 不输出 body；正式裁剪参考必须先实际验证三个创建入口 |
| 自定路径统一前缀 /api；路径字段覆盖 body | R:902–915 | `G/ControllerRenderer.java`:158–179；P04 直接验证，避免只看接口名猜路由 |
| page/size 可各自默认，合法越界页不是 404 | R:1954 说明分页仍有默认值，R:1685 说明外层结构与越界 total | **参考未列具体默认值/上限数字**，不能伪造语言文档依据；1/20/10000/100 来自 `L/profile/SpringBootQueryPolicy.java`:14–22 和 `G/WorkflowRenderer.java`:477–493，本题显式冻结为接口合同。它是当前目标行为，不宣称跨 Target 的语言保证 |
| Bearer 真正认证为 User，401 发生在工作流前；401 可能没有 JSON 信封 | R:374–389、805，声明错误信封见 R:589–593 | `G/CredentialIdentityAdapterRenderer.java`:77–125；`G/ApplicationRenderer.java`:334–346 空体 401；需启动 `external` 模式，禁止 local-fixed。`BusinessSliceHarness.useActorMode` 与已有 Bearer IT 的调用提供接法，但此题不调用登录 |
| Name/Code 的约束与 SQL 长度不等于 token-desk 的 Text 列宽；仅测试本题显式要求的标题校验，不随工具链新增隐性评分 | R:86–113、253、1954 | `L/api/SpringBootDeploymentPlanner.java`:48–76；只测 title 明确的 NotBlank/Size，不增加 content/category 缺失一定 400 的隐性评分项；字段错误对象实际为 path/code/message，见 `G/ApiErrorRenderer.java`:25–32，不能把 path 写成 field |
| Ref 输入 category 的 HTTP 名是 categoryId；PostView 的同名 Ref 输出却是数值 author/category | R:109 规定引用存主键，R:538 规定嵌套视图；**未完整写明输入自动 Id 后缀** | `L/internal/SpringBootModelLowerer.java`:580–582、455–478、1940–1942；`G/TypeRenderer.java`:17–25。按实际 HTTP 合同检查，不能把持久化 author_id 当响应字段 |
| 表/列 snake_case、Ref 加 _id、Token 用 CHAR(36)；like 是保留字，不能生成/查询裸 like | R:2720；R:259–261 的唯一约束 | `L/internal/SpringBootModelLowerer.java`:559–561、657；`L/profile/MySqlIdentifiers.java`:6–11、36、57–65，`G/EntityRenderer.java`:51：已统一转义 like，无需在题目中改名；本题 DDL/JDBC 同样加反引号 |
| 默认 state/likes 来自 create，now 是本次创建时刻而非 seed/服务器启动时刻 | R:311 | `L/api/SpringBootDeploymentPlanner.java`:68–69 不给 DATETIME 加数据库默认；B03/B04 对 HTTP/JDBC 与调用时间区间交叉核对，不能只查非空 |
| 字面搜索转义 %/_/反斜线，分页总数不能被标签重复关联放大 | R:1160；R:1289 的 any 连接条件 | `L/profile/SpringBootQueryPolicy.java`:21；原 blog 的 Feed/PostsByTag 工作流。Q02/T01/T02 的反例分别命中，大小写由本题 DDL 固定，不额外声称语言自动大小写无关 |
| 重复关系必须依靠实际唯一约束触发；事务要把先更新的 likes 一起回滚 | R:259–261、766、1317–1347 | 两个联合唯一索引不可省；L02/X02/X03 既查错误又查全库差异。不得用“都是 atomic”代替唯一性与并发证据 |
| publish 只有 DRAFT→PUBLISHED 才唯一赢家，不是任意守卫更新都唯一 | R:1355–1399 的状态流转例；R:1441–1445 对行内累加的精确说明 | 本题发布后原条件不再成立，X01 检查 1 胜；不同用户点赞则必须全胜。R:1399 的泛化句应按本题具体不变量理解，不再照抄为所有守卫的结论 |
| LikePost 原子加一、返回值与并发最终状态；FK 锁顺序可能造成死锁 | R:1441–1445、1496；R:1007 说明死锁不自动重试 | 不在需求原文提示实现顺序；参考沿用既有 LikePost 的计数后关系持久化语义。X04 只要求每响应值在有效范围，最终 JDBC 必须 6，不能要求 HTTP 完成顺序等于提交顺序 |
| 去评论后 ViewPost 不能残留 comments，不把原完整主题当裁剪参考的证据 | R:538 对嵌套集合读全量的说明 | 本题明确改用已有 PublicPost，删 Comment/PostDetail；先跑新裁剪参考再冻结任务和判卷。此处是有意的业务范围裁剪，不是原主题 bug 修复 |

**收口条件与实际进展：**验收次序保持“裁剪参考同 SHA 四门 → base 静态可用且缺新增接口 → 冻结 task/DDL/seed/oracle → 采样”。前三步现已由 verify #200、base 契约／原编译器检查、两份正式 task 和两批 prepare 完成。真库只在 CI 执行；本轮本机没有启动应用／数据库或调用模型。reference_evidence 固定指向本题裁剪参考的 `66c27691`／verify #200，不引用旧完整主题；教师资格不继承参考。模型采样预算仍待确认。
