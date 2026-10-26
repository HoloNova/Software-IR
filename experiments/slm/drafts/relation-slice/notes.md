# 出题备注

## removed

从 `valid/course-enrollment.sir` 仅删除以下完整声明：

- capability：SearchCourseEnrollments
- entity：无
- view：无
- input：无
- error：无
- enum：无

待审核例外：与 query-slice 一样，参考软件只有唯一业务接口 SearchCourseEnrollments。为遵守“只做删除且保留原文”，base 保留全部数据、关联、输入和响应定义，但移除唯一业务流程；无法同时保留其他业务接口，需负责人裁决。
保留声明和外壳逐字不动；未改 software、namespace、声明顺序及格式。删除功能被追溯表中的对应 HTTP 检查命中，缺路由时不能通过原判卷；本轮未运行业务 IT，失败结论是依据路由缺失作出的静态推断。

## 隐含要求

除结果外，判卷观测具体 SQL 形状：EXISTS (SELECT 1 FROM enrollment、course_id IN、id IN，需求以“关联存在性检查、按编号集合批量读取”明确要求，contract 固定测量形状。业务读取4/2/0次，连接@@变量探测另计；单一只读事务也单独断言。

## family 建议

`课程`。同家族必须整体分配到同一个 train/dev/blind 集合，不按改写/从零拆分。

## 静态检查

检查器预编译一次：`mvn -B -q -pl kcg-cli -am compile`，成功。执行时包在 systemd scope 中：MemoryMax=1400M、CPUQuota=125%，Maven JVM Xmx=768m、MaxMetaspaceSize=256m；实际 nproc=2、总内存3875MiB、当时可用1839MiB；无 Maven -T，无测试、数据库或业务应用。

base 静态检查：PASS。按指定 `checker_command()` 调用检查器，输入为当前文件全文与 id=base 的 JSON；11 份逐个执行于 MemoryMax=600M、CPUQuota=100% 的 systemd scope，Java Xmx=256m、MaxMetaspaceSize=128m，本份退出码为 0。最终原始输出：

```json
{"id":"base","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":19,"digest":"6c86f3dbe9450708cb8f274d86a8bbf13fecc3497e83a2a48b8d134c887aa0ee","sourceSha256":"9faf609a955460b106cc8fd5abb6ee712b524db1df8378b8f136a6f58d6f87f8"}
```

## 证据边界

只完成草稿与静态生成检查；业务判卷 NOT_RUN。没有运行 prepare/generate/repair，也没有创建 task.json、修改正式题目或提交。
