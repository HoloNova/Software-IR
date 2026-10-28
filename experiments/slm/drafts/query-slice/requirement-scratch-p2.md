- SearchCourses：通过 GET /api/search-courses 按 keyword 对课程 name 做字面包含搜索，百分号与下划线按普通字符处理，不作通配符。

- 搜索结果：按 code 升序、同 code 时按 id 升序。

- 记录仅含 code、name、capacity，不泄露 id、description。

- 整个查询不改数据库。

- 分页响应：total、page、size、records，其中 total 为全部匹配数。

- 分页参数：page 默认 1、范围 1～10000。

- size 默认 20、范围 1～100。

- 分页边界：参数越界返回 400 InvalidPageParam 且不含 records。

- 超过末页返回 200、空 records 和原总数。

- keyword 校验：缺失或纯空白均返回 400。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
