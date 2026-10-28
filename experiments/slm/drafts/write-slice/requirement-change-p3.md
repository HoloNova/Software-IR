课程维护者需要只改动部分课程信息，并避免并发修改覆盖彼此的结果。

在给定软件上补上课程局部修改与并发版本保护，同时保留 CreateCourse、GetCourse 的现有行为。

维护者通过 PATCH /api/update-course 调用 UpdateCourse，提交 id、expectedVersion、changes，其中 changes 只能包含 name、description、capacity。

维护者没有提交的字段保持原值，只有明确将 description 传为 null 才清空它，code 则不能修改。

修改成功后，维护者拿到合并后的完整课程字段，version 必须从数据库当前值加一。

请求中的 expectedVersion 要和该记录的实际版本比较，维护者持有的版本若已过旧，则返回 409 StaleVersion。

当两个请求带着相同版本竞争修改时，恰好一个得到 200，另一个得到 409，数据库只能更新一次，其他行不能改变。

维护者的请求只要被拒绝，就不能写数据库，也不能使版本前进。

维护者提交空 changes 时，UpdateCourse 返回 400 EmptyChange。

如果要修改的 id 不存在，返回 404 CourseNotFound。

请求漏掉 id 或 expectedVersion，或者在外层或 changes 中带入未知字段时，维护者会得到 400，错误中必须指出缺失或未知字段的路径。

维护者把 name 填成纯空白时，返回 400 INVALID_REQUEST，fields 中必须包含 path=changes.name、code=notBlank。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
