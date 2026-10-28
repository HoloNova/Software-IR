- 在给定软件上增加课程局部修改及并发版本保护。

- 保留 CreateCourse、GetCourse 的现有行为。

- UpdateCourse：使用 PATCH /api/update-course，接收 id、expectedVersion、changes。

- changes 仅允许 name、description、capacity。

- 局部修改规则：未传字段保持原值。

- description 仅在显式 null 时清空。

- code 不可修改。

- 更新成功：返回合并后的完整课程字段，version 从数据库当前值加一。

- 版本比较：expectedVersion 必须与记录实际版本比较。

- 过旧返回 409 StaleVersion。

- 并发更新：两个持相同版本的请求竞争，恰好一个返回 200、另一个返回 409。

- 数据库只更新一次，其他行不变。

- 拒绝结果：所有被拒请求均不写库、不推进版本。

- 空修改：changes 为空时返回 400 EmptyChange。

- 目标不存在：id 不存在时返回 404 CourseNotFound。

- 请求字段校验：缺 id 或 expectedVersion、未知外层字段、未知 changes 字段均返回 400。

- 错误须指出缺失或未知字段路径。

- name 校验：纯空白返回 400 INVALID_REQUEST，fields 包含 path=changes.name、code=notBlank。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
