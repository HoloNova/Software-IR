# 出题备注

## removed

从 `valid/token-desk.sir` 仅删除以下完整声明：

- capability：Register、Login
- entity：无
- view：无
- input：Credentials
- error：BadLogin
- enum：无


保留声明和外壳逐字不动；未改 software、namespace、声明顺序及格式。删除功能被追溯表中的对应 HTTP 检查命中，缺路由时不能通过原判卷；本轮未运行业务 IT，失败结论是依据路由缺失作出的静态推断。

## 隐含要求

需求明确不变值写入仍成功（连接必须按匹配行数判断成功）、同名多行报500，以及摘要前缀与明文/响应保密。DDL刻意未给 account.name 建唯一索引以放入同名脏数据；不得把它改成任选一行。GetCredential 等原业务由保留句承接。

## family 建议

`鉴权`。同家族必须整体分配到同一个 train/dev/blind 集合，不按改写/从零拆分。

## 静态检查

检查器预编译一次：`mvn -B -q -pl kcg-cli -am compile`，成功。执行时包在 systemd scope 中：MemoryMax=1400M、CPUQuota=125%，Maven JVM Xmx=768m、MaxMetaspaceSize=256m；实际 nproc=2、总内存3875MiB、当时可用1839MiB；无 Maven -T，无测试、数据库或业务应用。

base 静态检查：PASS。按指定 `checker_command()` 调用检查器，输入为当前文件全文与 id=base 的 JSON；11 份逐个执行于 MemoryMax=600M、CPUQuota=100% 的 systemd scope，Java Xmx=256m、MaxMetaspaceSize=128m，本份退出码为 0。最终原始输出：

```json
{"id":"base","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":30,"digest":"12a2cdcc84b5ad57fe2af51ddf4ad74035fcbec74aaee4632dfd6ea8dca6f61f","sourceSha256":"20dc2844a72c72dfe25b4f6e47b3ba0f653e197474b533dbdaa911c7b349a4a5"}
```

## 证据边界

只完成草稿与静态生成检查；业务判卷 NOT_RUN。没有运行 prepare/generate/repair，也没有创建 task.json、修改正式题目或提交。
