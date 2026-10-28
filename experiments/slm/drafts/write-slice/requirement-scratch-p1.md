CreateCourse 传 code、name、可空的 description、capacity。创建后返回 201 和 id、code、name、description、capacity、version，保存时 version 从 0 开始。

创建时 name 全是空白，返回 400，错误指出 path=name，别新增行。

GetCourse 按 id 查，返回 200 和全部课程字段。id 未知就返回 404，code=CourseNotFound。

UpdateCourse 用 PATCH /api/update-course，传 id、expectedVersion、changes。changes 里只能放 name、description、capacity。

没传的字段别动。description 只有明确传 null 才清空，code 不让改。

改成功后返回合并后的完整课程字段，version 按数据库当前值加一。

expectedVersion 要跟记录的实际版本比。版本旧了返回 409 StaleVersion。

两个拿着相同版本的请求同时竞争，只能一个 200、另一个 409。数据库只更新一次，别的行不动。

所有被拒的请求都别写库，也别推进版本。

changes 是空的就返回 400 EmptyChange。

id 找不到就返回 404 CourseNotFound。

缺 id 或 expectedVersion，或者外层、changes 里混了未知字段，都返回 400。错误里要说清缺失或未知字段的路径。

name 全是空白就返回 400 INVALID_REQUEST，fields 里要有 path=changes.name、code=notBlank。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
