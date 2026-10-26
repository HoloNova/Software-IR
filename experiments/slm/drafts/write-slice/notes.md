# 出题备注

## removed

从 `valid/course-admin.sir` 仅删除以下完整声明：

- capability：UpdateCourse
- entity：无
- view：无
- input：UpdateCourseInput
- error：EmptyChange、StaleVersion
- enum：无


保留声明和外壳逐字不动；未改 software、namespace、声明顺序及格式。删除功能被追溯表中的对应 HTTP 检查命中，缺路由时不能通过原判卷；本轮未运行业务 IT，失败结论是依据路由缺失作出的静态推断。

## 隐含要求

三态合并和版本推进由整行 JDBC 指纹断言，不得把 null 当作未传；错误路径需带 changes. 前缀。新行版本为0而非其它题默认1。测试最后 finalRowFingerprint 只是报告，真正判定以此前逐行断言为准。无额外 SQL 条数预算。

## family 建议

`课程`。同家族必须整体分配到同一个 train/dev/blind 集合，不按改写/从零拆分。

## 静态检查

检查器预编译一次：`mvn -B -q -pl kcg-cli -am compile`，成功。执行时包在 systemd scope 中：MemoryMax=1400M、CPUQuota=125%，Maven JVM Xmx=768m、MaxMetaspaceSize=256m；实际 nproc=2、总内存3875MiB、当时可用1839MiB；无 Maven -T，无测试、数据库或业务应用。

base 静态检查：PASS。按指定 `checker_command()` 调用检查器，输入为当前文件全文与 id=base 的 JSON；11 份逐个执行于 MemoryMax=600M、CPUQuota=100% 的 systemd scope，Java Xmx=256m、MaxMetaspaceSize=128m，本份退出码为 0。最终原始输出：

```json
{"id":"base","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":17,"digest":"88f6bb6ddd6b5aab85bd9d5e482f3c969e1b5b7a7520f399c219ad17d75a9ba4","sourceSha256":"e65aaf7561048cc71b8c5a2df40c40b41d6cd214f937123669d18fc8b4f3db1e"}
```

## 证据边界

只完成草稿与静态生成检查；业务判卷 NOT_RUN。没有运行 prepare/generate/repair，也没有创建 task.json、修改正式题目或提交。
