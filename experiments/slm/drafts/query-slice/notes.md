# 出题备注

## removed

从 `valid/course-catalog.sir` 仅删除以下完整声明：

- capability：SearchCourses
- entity：无
- view：无
- input：无
- error：无
- enum：无

待审核例外：参考软件只有 SearchCourses 一个业务接口，不能同时满足“删除完整业务功能”和“保留其他业务主体”。此草稿保留 Course、CourseSummary、SearchCoursesInput、InvalidPageParam 及完整外壳，只删除唯一搜索流程；未伪造第二接口，不能宣称已满足保留业务接口要求。
保留声明和外壳逐字不动；未改 software、namespace、声明顺序及格式。删除功能被追溯表中的对应 HTTP 检查命中，缺路由时不能通过原判卷；本轮未运行业务 IT，失败结论是依据路由缺失作出的静态推断。

## 隐含要求

明确字面搜索、稳定排序、投影不泄露和整表不变；无 SQL 条数、锁等待或并发断言。

## family 建议

`课程`。同家族必须整体分配到同一个 train/dev/blind 集合，不按改写/从零拆分。

## 静态检查

检查器预编译一次：`mvn -B -q -pl kcg-cli -am compile`，成功。执行时包在 systemd scope 中：MemoryMax=1400M、CPUQuota=125%，Maven JVM Xmx=768m、MaxMetaspaceSize=256m；实际 nproc=2、总内存3875MiB、当时可用1839MiB；无 Maven -T，无测试、数据库或业务应用。

base 静态检查：PASS。按指定 `checker_command()` 调用检查器，输入为当前文件全文与 id=base 的 JSON；11 份逐个执行于 MemoryMax=600M、CPUQuota=100% 的 systemd scope，Java Xmx=256m、MaxMetaspaceSize=128m，本份退出码为 0。最终原始输出：

```json
{"id":"base","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":12,"digest":"9749e701460d51f179fb0d54f4f1e2e85ab9dffc3c2417a46b332c122d976c53","sourceSha256":"0904d8524bb294d2e7af9ce185f1bd091d074ed3875a6599c0ed48eaa25ff2ca"}
```

## 证据边界

只完成草稿与静态生成检查；业务判卷 NOT_RUN。没有运行 prepare/generate/repair，也没有创建 task.json、修改正式题目或提交。
