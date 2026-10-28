在给定软件上加课程目录搜索。课程数据、搜索请求字段和课程摘要的现有定义都保留。

SearchCourses 用 GET /api/search-courses，按 keyword 查课程 name 中包含的原文字。百分号、下划线就是普通字符，不当通配符。

结果按 code 升序，同 code 再按 id 升序。每条只给 code、name、capacity，不给 id、description。整个查询不改数据库。

分页返回 total、page、size、records，total 算全部匹配数。

page 不传用 1，size 不传用 20。page 只能是 1～10000，size 只能是 1～100。

分页参数越界返回 400 InvalidPageParam，别带 records。只是翻过末页的话，返回 200、空 records，总数照旧。

keyword 没传或者全是空白，返回 400。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
