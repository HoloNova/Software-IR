# 出题备注

## removed

从 `topics/course/project.sir` 仅删除以下完整声明：

- capability：CreateCourse、AddPrerequisite、RemovePrerequisite、PublishCourse、RecordCompletion
- entity：无
- view：无
- input：NewCourse、PrerequisiteInput、PrerequisiteRef、CompletionInput
- error：无
- enum：无


保留声明和外壳逐字不动；未改 software、namespace、声明顺序及格式。删除功能被追溯表中的对应 HTTP 检查命中，缺路由时不能通过原判卷；本轮未运行业务 IT，失败结论是依据路由缺失作出的静态推断。

## 隐含要求

需求明确学生公共行的实际锁等待、等待后重查、8抢3和8退1、四表全量不变及后置插入/删除失败回滚；不是只写“线程安全”。没有 SQL 条数预算。Prerequisite/CompletedCourse 虽属于新增管理功能的数据，但现有 Enroll 仍读取它们，所以必须保留，不能因管理入口移除而删表定义。

## family 建议

`课程`。同家族必须整体分配到同一个 train/dev/blind 集合，不按改写/从零拆分。

## 静态检查

检查器预编译一次：`mvn -B -q -pl kcg-cli -am compile`，成功。执行时包在 systemd scope 中：MemoryMax=1400M、CPUQuota=125%，Maven JVM Xmx=768m、MaxMetaspaceSize=256m；实际 nproc=2、总内存3875MiB、当时可用1839MiB；无 Maven -T，无测试、数据库或业务应用。

base 静态检查：PASS。按指定 `checker_command()` 调用检查器，输入为当前文件全文与 id=base 的 JSON；11 份逐个执行于 MemoryMax=600M、CPUQuota=100% 的 systemd scope，Java Xmx=256m、MaxMetaspaceSize=128m，本份退出码为 0。最终原始输出：

```json
{"id":"base","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":81,"digest":"797210b1ed0857aa8095bcb72832866a0934512a714f9c70fb121a9d71ed29ea","sourceSha256":"584d0563a51fe6f14cc0908a5c55d22036a1041aa58b4db5a12f16afb2e9adbf"}
```

## 证据边界

只完成草稿与静态生成检查；业务判卷 NOT_RUN。没有运行 prepare/generate/repair，也没有创建 task.json、修改正式题目或提交。
