# 出题备注

## removed

从 `valid/g9-topic-conformance.sir` 仅删除以下完整声明：

- capability：BrowseItems
- entity：Item
- view：无
- input：BrowseInput
- error：无
- enum：ItemStatus

另移除 Item 对应的完整 use Crud 声明，其生成的 CreateItem/GetItem/ListItem/UpdateItem/DeleteItem 一并不存在；删除的 Item 未被保留部分引用。
保留声明和外壳逐字不动；未改 software、namespace、声明顺序及格式。删除功能被追溯表中的对应 HTTP 检查命中，缺路由时不能通过原判卷；本轮未运行业务 IT，失败结论是依据路由缺失作出的静态推断。

## 隐含要求

本题实际参考是图书馆与市场的组合，G9TopicChecks 两部分均已覆盖，不能把市场检查当无关而漏掉。目录 SQL 预算辅助 LibraryCountReadBudget 也已读取：分页1次总数+1次根行+1次子聚合，详情1+1，只接受固定查询形状，额外会话探测单列。借还三表与预约四表精确快照、Book 实际等锁及重查、角色撤销、服务器时间/归属和保留 SOLD 均写进需求。建议混合题与 multiplication/商店同组防相似市场流程跨集合。

## family 建议

`图书馆（混合二手市场）`。同家族必须整体分配到同一个 train/dev/blind 集合，不按改写/从零拆分。

## 静态检查

检查器预编译一次：`mvn -B -q -pl kcg-cli -am compile`，成功。执行时包在 systemd scope 中：MemoryMax=1400M、CPUQuota=125%，Maven JVM Xmx=768m、MaxMetaspaceSize=256m；实际 nproc=2、总内存3875MiB、当时可用1839MiB；无 Maven -T，无测试、数据库或业务应用。

base 静态检查：PASS。按指定 `checker_command()` 调用检查器，输入为当前文件全文与 id=base 的 JSON；11 份逐个执行于 MemoryMax=600M、CPUQuota=100% 的 systemd scope，Java Xmx=256m、MaxMetaspaceSize=128m，本份退出码为 0。最终原始输出：

```json
{"id":"base","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":117,"digest":"090c454addc303ff3620697991bd17d5ba728b31761af4457fb0f8ad8c1ce629","sourceSha256":"ef9f40f7dff6632d359451ce78bf9be70e6f6998e9b783a7ec6137ee3f70b18d"}
```

## 证据边界

只完成草稿与静态生成检查；业务判卷 NOT_RUN。没有运行 prepare/generate/repair，也没有创建 task.json、修改正式题目或提交。
