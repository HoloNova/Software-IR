# query-slice 需求说法逐句对照

原需求按句号、分号拆行；C 对应 requirement-change.md，S 对应 requirement-scratch.md。表内 p1、p2、p3 均为对应新增文件中的原句；p3 开头的业务背景仅交代使用场景，不增加规则。

| 来源 | 原需求句段 | p1 对应句子 | p2 对应句子 | p3 对应句子 |
| --- | --- | --- | --- | --- | --- |
| C01 | 在给定软件上增加课程目录搜索，保留课程数据、搜索请求字段和课程摘要的现有定义。 | 在给定软件上加课程目录搜索。课程数据、搜索请求字段和课程摘要的现有定义都保留。 | 在给定软件上增加课程目录搜索。<br>保留课程数据、搜索请求字段和课程摘要的现有定义。 | 在给定软件上增加课程目录搜索，原有课程数据、搜索请求字段和课程摘要的定义保持不变。 |
| C02 | SearchCourses 通过 GET /api/search-courses 按 keyword 对课程 name 作字面包含搜索，百分号和下划线都是普通字符，不是通配符； | SearchCourses 用 GET /api/search-courses，按 keyword 查课程 name 中包含的原文字。百分号、下划线就是普通字符，不当通配符。 | SearchCourses：通过 GET /api/search-courses 按 keyword 对课程 name 做字面包含搜索，百分号与下划线按普通字符处理，不作通配符。 | 用户通过 GET /api/search-courses 调用 SearchCourses，用 keyword 查找 name 字面包含该文字的课程；输入的百分号和下划线只代表这些字符本身，不是通配符。 |
| C03 | 按 code 升序、相同时按 id 升序返回，记录仅含 code、name、capacity，不泄露 id、description，整个查询不改数据库。 | 结果按 code 升序，同 code 再按 id 升序。每条只给 code、name、capacity，不给 id、description。整个查询不改数据库。 | 搜索结果：按 code 升序、同 code 时按 id 升序。<br>记录仅含 code、name、capacity，不泄露 id、description。<br>整个查询不改数据库。 | 用户看到的结果先按 code 升序排列，同 code 的再按 id 升序排列；每条仅展示 code、name、capacity，不泄露 id、description，这次查询也不能改变数据库。 |
| C04 | 分页响应为 total、page、size、records，total 是全部匹配数； | 分页返回 total、page、size、records，total 算全部匹配数。 | 分页响应：total、page、size、records，其中 total 为全部匹配数。 | 翻页响应包含 total、page、size、records，total 始终表示所有匹配课程的数量。 |
| C05 | page、size 缺省为 1、20，合法范围分别为 1～10000、1～100； | page 不传用 1，size 不传用 20。page 只能是 1～10000，size 只能是 1～100。 | 分页参数：page 默认 1、范围 1～10000。<br>size 默认 20、范围 1～100。 | 用户不传 page 时使用 1，不传 size 时使用 20；传入时 page 必须在 1～10000，size 必须在 1～100。 |
| C06 | 越界返回 400 InvalidPageParam 且不含 records，超过末页则返回 200、空 records 和原总数。 | 分页参数越界返回 400 InvalidPageParam，别带 records。只是翻过末页的话，返回 200、空 records，总数照旧。 | 分页边界：参数越界返回 400 InvalidPageParam 且不含 records。<br>超过末页返回 200、空 records 和原总数。 | 用户传的分页参数越界时，得到不含 records 的 400 InvalidPageParam；若参数合法但已翻过末页，则得到 200、空 records 以及原来的总数。 |
| C07 | keyword 缺失或纯空白返回 400。 | keyword 没传或者全是空白，返回 400。 | keyword 校验：缺失或纯空白均返回 400。 | 用户漏填 keyword 或只填空白时，搜索返回 400。 |
| C08 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 |
| S01 | SearchCourses 通过 GET /api/search-courses 按 keyword 对课程 name 作字面包含搜索，百分号和下划线都是普通字符，不是通配符； | SearchCourses 用 GET /api/search-courses，按 keyword 查课程 name 中包含的原文字。百分号、下划线就是普通字符，不当通配符。 | SearchCourses：通过 GET /api/search-courses 按 keyword 对课程 name 做字面包含搜索，百分号与下划线按普通字符处理，不作通配符。 | 用户通过 GET /api/search-courses 调用 SearchCourses，用 keyword 查找 name 字面包含该文字的课程；输入的百分号和下划线只代表这些字符本身，不是通配符。 |
| S02 | 按 code 升序、相同时按 id 升序返回，记录仅含 code、name、capacity，不泄露 id、description，整个查询不改数据库。 | 结果按 code 升序，同 code 再按 id 升序。每条只给 code、name、capacity，不给 id、description。整个查询不改数据库。 | 搜索结果：按 code 升序、同 code 时按 id 升序。<br>记录仅含 code、name、capacity，不泄露 id、description。<br>整个查询不改数据库。 | 用户看到的结果先按 code 升序排列，同 code 的再按 id 升序排列；每条仅展示 code、name、capacity，不泄露 id、description，这次查询也不能改变数据库。 |
| S03 | 分页响应为 total、page、size、records，total 是全部匹配数； | 分页返回 total、page、size、records，total 算全部匹配数。 | 分页响应：total、page、size、records，其中 total 为全部匹配数。 | 翻页响应包含 total、page、size、records，total 始终表示所有匹配课程的数量。 |
| S04 | page、size 缺省为 1、20，合法范围分别为 1～10000、1～100； | page 不传用 1，size 不传用 20。page 只能是 1～10000，size 只能是 1～100。 | 分页参数：page 默认 1、范围 1～10000。<br>size 默认 20、范围 1～100。 | 用户不传 page 时使用 1，不传 size 时使用 20；传入时 page 必须在 1～10000，size 必须在 1～100。 |
| S05 | 越界返回 400 InvalidPageParam 且不含 records，超过末页则返回 200、空 records 和原总数。 | 分页参数越界返回 400 InvalidPageParam，别带 records。只是翻过末页的话，返回 200、空 records，总数照旧。 | 分页边界：参数越界返回 400 InvalidPageParam 且不含 records。<br>超过末页返回 200、空 records 和原总数。 | 用户传的分页参数越界时，得到不含 records 的 400 InvalidPageParam；若参数合法但已翻过末页，则得到 200、空 records 以及原来的总数。 |
| S06 | keyword 缺失或纯空白返回 400。 | keyword 没传或者全是空白，返回 400。 | keyword 校验：缺失或纯空白均返回 400。 | 用户漏填 keyword 或只填空白时，搜索返回 400。 |
| S07 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 |

自查结论：已逐条对照原需求及 traceability.md；没有检查点缺失或改动，未新增要求；名称、数字、保留行为及末句均保持原意或原样。
