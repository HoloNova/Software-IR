# 出题备注

## removed

从 `valid/crud-access.sir` 仅删除以下完整声明：

- capability：无
- entity：Book
- view：无
- input：无
- error：无
- enum：无

另删除 Book 对应的完整 use Crud 声明；Post 对应声明逐字保留。
保留声明和外壳逐字不动；未改 software、namespace、声明顺序及格式。删除功能被追溯表中的对应 HTTP 检查命中，缺路由时不能通过原判卷；本轮未运行业务 IT，失败结论是依据路由缺失作出的静态推断。

## 隐含要求

角色检查必须先于业务行存在性检查；拒绝未知 stock/state 字段不是“忽略”。以独立 JDBC 核对拒写、默认值和版本。base 移除 Book 及它整块管理接口声明，保留账号凭据与完整文章主体。

## family 建议

`鉴权`。同家族必须整体分配到同一个 train/dev/blind 集合，不按改写/从零拆分。

## 静态检查

检查器预编译一次：`mvn -B -q -pl kcg-cli -am compile`，成功。执行时包在 systemd scope 中：MemoryMax=1400M、CPUQuota=125%，Maven JVM Xmx=768m、MaxMetaspaceSize=256m；实际 nproc=2、总内存3875MiB、当时可用1839MiB；无 Maven -T，无测试、数据库或业务应用。

base 静态检查：PASS。按指定 `checker_command()` 调用检查器，输入为当前文件全文与 id=base 的 JSON；11 份逐个执行于 MemoryMax=600M、CPUQuota=100% 的 systemd scope，Java Xmx=256m、MaxMetaspaceSize=128m，本份退出码为 0。最终原始输出：

```json
{"id":"base","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":36,"digest":"2fe27725bf86c26bb6d95136e82a14b3c6a221f798849e78956ed6816fbfa441","sourceSha256":"666f2709f02c163abe5ef2a947a4c0dea7f82f361ce02f2c8aef7a1ac1021eed"}
```

## 证据边界

只完成草稿与静态生成检查；业务判卷 NOT_RUN。没有运行 prepare/generate/repair，也没有创建 task.json、修改正式题目或提交。
