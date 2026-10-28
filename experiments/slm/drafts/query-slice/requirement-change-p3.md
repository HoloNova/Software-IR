查课的人需要按课程名称找课程，再翻页查看课程摘要。

在给定软件上增加课程目录搜索，原有课程数据、搜索请求字段和课程摘要的定义保持不变。

用户通过 GET /api/search-courses 调用 SearchCourses，用 keyword 查找 name 字面包含该文字的课程；输入的百分号和下划线只代表这些字符本身，不是通配符。

用户看到的结果先按 code 升序排列，同 code 的再按 id 升序排列；每条仅展示 code、name、capacity，不泄露 id、description，这次查询也不能改变数据库。

翻页响应包含 total、page、size、records，total 始终表示所有匹配课程的数量。

用户不传 page 时使用 1，不传 size 时使用 20；传入时 page 必须在 1～10000，size 必须在 1～100。

用户传的分页参数越界时，得到不含 records 的 400 InvalidPageParam；若参数合法但已翻过末页，则得到 200、空 records 以及原来的总数。

用户漏填 keyword 或只填空白时，搜索返回 400。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
