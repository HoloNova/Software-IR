# write-slice 需求说法逐句对照

原需求按句号、分号拆行；C 对应 requirement-change.md，S 对应 requirement-scratch.md。表内 p1、p2、p3 均为对应新增文件中的原句；p3 开头的业务背景仅交代使用场景，不增加规则。

| 来源 | 原需求句段 | p1 对应句子 | p2 对应句子 | p3 对应句子 |
| --- | --- | --- | --- | --- | --- |
| C01 | 在给定软件上增加课程局部修改与并发版本保护，保留 CreateCourse、GetCourse 的现有行为。 | 在给定软件上加课程局部修改和并发版本保护。保留 CreateCourse、GetCourse 的现有行为。 | 在给定软件上增加课程局部修改及并发版本保护。<br>保留 CreateCourse、GetCourse 的现有行为。 | 在给定软件上补上课程局部修改与并发版本保护，同时保留 CreateCourse、GetCourse 的现有行为。 |
| C02 | UpdateCourse 使用 PATCH /api/update-course，接收 id、expectedVersion、changes，changes 只可包含 name、description、capacity； | UpdateCourse 用 PATCH /api/update-course，传 id、expectedVersion、changes。changes 里只能放 name、description、capacity。 | UpdateCourse：使用 PATCH /api/update-course，接收 id、expectedVersion、changes。<br>changes 仅允许 name、description、capacity。 | 维护者通过 PATCH /api/update-course 调用 UpdateCourse，提交 id、expectedVersion、changes，其中 changes 只能包含 name、description、capacity。 |
| C03 | 没传的字段保持原值，description 显式 null 才清空，code 不可修改； | 没传的字段别动。description 只有明确传 null 才清空，code 不让改。 | 局部修改规则：未传字段保持原值。<br>description 仅在显式 null 时清空。<br>code 不可修改。 | 维护者没有提交的字段保持原值，只有明确将 description 传为 null 才清空它，code 则不能修改。 |
| C04 | 成功返回合并后的完整课程字段，version 从数据库当前值加一。 | 改成功后返回合并后的完整课程字段，version 按数据库当前值加一。 | 更新成功：返回合并后的完整课程字段，version 从数据库当前值加一。 | 修改成功后，维护者拿到合并后的完整课程字段，version 必须从数据库当前值加一。 |
| C05 | 必须按记录实际版本比较 expectedVersion，过旧返回 409 StaleVersion； | expectedVersion 要跟记录的实际版本比。版本旧了返回 409 StaleVersion。 | 版本比较：expectedVersion 必须与记录实际版本比较。<br>过旧返回 409 StaleVersion。 | 请求中的 expectedVersion 要和该记录的实际版本比较，维护者持有的版本若已过旧，则返回 409 StaleVersion。 |
| C06 | 两个持相同版本的请求竞争时恰有一个 200、另一个 409，数据库只更新一次，其他行不变； | 两个拿着相同版本的请求同时竞争，只能一个 200、另一个 409。数据库只更新一次，别的行不动。 | 并发更新：两个持相同版本的请求竞争，恰好一个返回 200、另一个返回 409。<br>数据库只更新一次，其他行不变。 | 当两个请求带着相同版本竞争修改时，恰好一个得到 200，另一个得到 409，数据库只能更新一次，其他行不能改变。 |
| C07 | 所有被拒请求均不写库、不推进版本。 | 所有被拒的请求都别写库，也别推进版本。 | 拒绝结果：所有被拒请求均不写库、不推进版本。 | 维护者的请求只要被拒绝，就不能写数据库，也不能使版本前进。 |
| C08 | changes 为空返回 400 EmptyChange； | changes 是空的就返回 400 EmptyChange。 | 空修改：changes 为空时返回 400 EmptyChange。 | 维护者提交空 changes 时，UpdateCourse 返回 400 EmptyChange。 |
| C09 | id 不存在返回 404 CourseNotFound； | id 找不到就返回 404 CourseNotFound。 | 目标不存在：id 不存在时返回 404 CourseNotFound。 | 如果要修改的 id 不存在，返回 404 CourseNotFound。 |
| C10 | 缺 id 或 expectedVersion、未知外层字段或未知 changes 字段均为 400，错误中须指出缺失或未知字段路径； | 缺 id 或 expectedVersion，或者外层、changes 里混了未知字段，都返回 400。错误里要说清缺失或未知字段的路径。 | 请求字段校验：缺 id 或 expectedVersion、未知外层字段、未知 changes 字段均返回 400。<br>错误须指出缺失或未知字段路径。 | 请求漏掉 id 或 expectedVersion，或者在外层或 changes 中带入未知字段时，维护者会得到 400，错误中必须指出缺失或未知字段的路径。 |
| C11 | name 纯空白返回 400 INVALID_REQUEST，fields 中包含 path=changes.name、code=notBlank。 | name 全是空白就返回 400 INVALID_REQUEST，fields 里要有 path=changes.name、code=notBlank。 | name 校验：纯空白返回 400 INVALID_REQUEST，fields 包含 path=changes.name、code=notBlank。 | 维护者把 name 填成纯空白时，返回 400 INVALID_REQUEST，fields 中必须包含 path=changes.name、code=notBlank。 |
| C12 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 |
| S01 | CreateCourse 接收 code、name、可空 description、capacity，创建后返回 201 和 id、code、name、description、capacity、version，保存的初始 version 为 0； | CreateCourse 传 code、name、可空的 description、capacity。创建后返回 201 和 id、code、name、description、capacity、version，保存时 version 从 0 开始。 | CreateCourse：接收 code、name、可空 description、capacity。<br>创建后返回 201 及 id、code、name、description、capacity、version，保存的初始 version 为 0。 | 维护者用 code、name、可空的 description、capacity 调用 CreateCourse，创建后得到 201 和 id、code、name、description、capacity、version，数据库保存的 version 初始为 0。 |
| S02 | name 纯空白返回 400，错误指出 path=name，且不新增行。 | 创建时 name 全是空白，返回 400，错误指出 path=name，别新增行。 | CreateCourse 的 name 校验：纯空白返回 400，错误指出 path=name，且不新增行。 | 若创建课程时 name 只有空白，返回 400，错误指出 path=name，数据库不能新增行。 |
| S03 | GetCourse 通过 id 查询，返回 200 和全部课程字段，未知 id 返回 404 且 code=CourseNotFound。 | GetCourse 按 id 查，返回 200 和全部课程字段。id 未知就返回 404，code=CourseNotFound。 | GetCourse：通过 id 查询，返回 200 及全部课程字段。<br>未知 id 返回 404，且 code=CourseNotFound。 | 维护者查看课程时，GetCourse 根据 id 返回 200 和全部课程字段；若 id 未知，则返回 404 且 code=CourseNotFound。 |
| S04 | UpdateCourse 使用 PATCH /api/update-course，接收 id、expectedVersion、changes，changes 只可包含 name、description、capacity； | UpdateCourse 用 PATCH /api/update-course，传 id、expectedVersion、changes。changes 里只能放 name、description、capacity。 | UpdateCourse：使用 PATCH /api/update-course，接收 id、expectedVersion、changes。<br>changes 仅允许 name、description、capacity。 | 维护者通过 PATCH /api/update-course 调用 UpdateCourse，提交 id、expectedVersion、changes，其中 changes 只能包含 name、description、capacity。 |
| S05 | 没传的字段保持原值，description 显式 null 才清空，code 不可修改； | 没传的字段别动。description 只有明确传 null 才清空，code 不让改。 | 局部修改规则：未传字段保持原值。<br>description 仅在显式 null 时清空。<br>code 不可修改。 | 维护者没有提交的字段保持原值，只有明确将 description 传为 null 才清空它，code 则不能修改。 |
| S06 | 成功返回合并后的完整课程字段，version 从数据库当前值加一。 | 改成功后返回合并后的完整课程字段，version 按数据库当前值加一。 | 更新成功：返回合并后的完整课程字段，version 从数据库当前值加一。 | 修改成功后，维护者拿到合并后的完整课程字段，version 必须从数据库当前值加一。 |
| S07 | 必须按记录实际版本比较 expectedVersion，过旧返回 409 StaleVersion； | expectedVersion 要跟记录的实际版本比。版本旧了返回 409 StaleVersion。 | 版本比较：expectedVersion 必须与记录实际版本比较。<br>过旧返回 409 StaleVersion。 | 请求中的 expectedVersion 要和该记录的实际版本比较，维护者持有的版本若已过旧，则返回 409 StaleVersion。 |
| S08 | 两个持相同版本的请求竞争时恰有一个 200、另一个 409，数据库只更新一次，其他行不变； | 两个拿着相同版本的请求同时竞争，只能一个 200、另一个 409。数据库只更新一次，别的行不动。 | 并发更新：两个持相同版本的请求竞争，恰好一个返回 200、另一个返回 409。<br>数据库只更新一次，其他行不变。 | 当两个请求带着相同版本竞争修改时，恰好一个得到 200，另一个得到 409，数据库只能更新一次，其他行不能改变。 |
| S09 | 所有被拒请求均不写库、不推进版本。 | 所有被拒的请求都别写库，也别推进版本。 | 拒绝结果：所有被拒请求均不写库、不推进版本。 | 维护者的请求只要被拒绝，就不能写数据库，也不能使版本前进。 |
| S10 | changes 为空返回 400 EmptyChange； | changes 是空的就返回 400 EmptyChange。 | 空修改：changes 为空时返回 400 EmptyChange。 | 维护者提交空 changes 时，UpdateCourse 返回 400 EmptyChange。 |
| S11 | id 不存在返回 404 CourseNotFound； | id 找不到就返回 404 CourseNotFound。 | 目标不存在：id 不存在时返回 404 CourseNotFound。 | 如果要修改的 id 不存在，返回 404 CourseNotFound。 |
| S12 | 缺 id 或 expectedVersion、未知外层字段或未知 changes 字段均为 400，错误中须指出缺失或未知字段路径； | 缺 id 或 expectedVersion，或者外层、changes 里混了未知字段，都返回 400。错误里要说清缺失或未知字段的路径。 | 请求字段校验：缺 id 或 expectedVersion、未知外层字段、未知 changes 字段均返回 400。<br>错误须指出缺失或未知字段路径。 | 请求漏掉 id 或 expectedVersion，或者在外层或 changes 中带入未知字段时，维护者会得到 400，错误中必须指出缺失或未知字段的路径。 |
| S13 | name 纯空白返回 400 INVALID_REQUEST，fields 中包含 path=changes.name、code=notBlank。 | name 全是空白就返回 400 INVALID_REQUEST，fields 里要有 path=changes.name、code=notBlank。 | name 校验：纯空白返回 400 INVALID_REQUEST，fields 包含 path=changes.name、code=notBlank。 | 维护者把 name 填成纯空白时，返回 400 INVALID_REQUEST，fields 中必须包含 path=changes.name、code=notBlank。 |
| S14 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 | 返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。 |

自查结论：已逐条对照原需求及 traceability.md；没有检查点缺失或改动，未新增要求；名称、数字、保留行为及末句均保持原意或原样。
