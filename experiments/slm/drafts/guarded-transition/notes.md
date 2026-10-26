# 出题备注

## removed

从 `valid/guarded-transition.sir` 仅删除以下完整声明：

- capability：Refresh
- entity：无
- view：无
- input：RefreshInput
- error：Expired、Invalid
- enum：无


保留声明和外壳逐字不动；未改 software、namespace、声明顺序及格式。删除功能被追溯表中的对应 HTTP 检查命中，缺路由时不能通过原判卷；本轮未运行业务 IT，失败结论是依据路由缺失作出的静态推断。

## 隐含要求

不能只先读状态再无条件改写：需求用8路并发结果、精确行数及失败不写库规定原子性。测试没有额外 SQL 条数或显式等锁预算。期限检查使用6～8天容差，业务约定仍为7天。此小软件只有查询和轮换两条路径，移除完整轮换后保留全部数据模型和查询主体。

## family 建议

`状态流转（凭据）`。本题与 token-desk 的凭据轮换高度重叠，建议并入 bearer-credential、token-desk 及已完成 dual-token 的鉴权家族；同家族整体分配到一个 train/dev/blind 集合，不按改写/从零拆分。

## 静态检查

检查器预编译一次：`mvn -B -q -pl kcg-cli -am compile`，成功。执行时包在 systemd scope 中：MemoryMax=1400M、CPUQuota=125%，Maven JVM Xmx=768m、MaxMetaspaceSize=256m；实际 nproc=2、总内存3875MiB、当时可用1839MiB；无 Maven -T，无测试、数据库或业务应用。

base 静态检查：PASS。按指定 `checker_command()` 调用检查器，输入为当前文件全文与 id=base 的 JSON；11 份逐个执行于 MemoryMax=600M、CPUQuota=100% 的 systemd scope，Java Xmx=256m、MaxMetaspaceSize=128m，本份退出码为 0。最终原始输出：

```json
{"id":"base","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":14,"digest":"bae86c09b8fce2d4c3c4f10c6d016ec125a1774945c64f797763c53aeaee80e2","sourceSha256":"1c088f2cb261f5c7b36d7e556c07b58d545a5ca5053c236d02ebae62753e3349"}
```

## 证据边界

只完成草稿与静态生成检查；业务判卷 NOT_RUN。没有运行 prepare/generate/repair，也没有创建 task.json、修改正式题目或提交。
