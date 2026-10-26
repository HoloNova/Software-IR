# 出题备注

## removed

从 `valid/money.sir` 仅删除以下完整声明：

- capability：SumMeasured、TaggedSum、LiteralSum、AverageMeasured
- entity：Measurement、MeasurementTag
- view：无
- input：SumProbe、SumTagProbe
- error：无
- enum：无

已另读3份注入探针与 GroupedSumReadBudget，未新增测试路由。上述测试控制/环境自检在追溯表中列为未覆盖（基础设施原因）；告警拒绝与快照结果仍作为业务要求覆盖。
保留声明和外壳逐字不动；未改 software、namespace、声明顺序及格式。删除功能被追溯表中的对应 HTTP 检查命中，缺路由时不能通过原判卷；本轮未运行业务 IT，失败结论是依据路由缺失作出的静态推断。

## 隐含要求

namespace 必须为 example.money；外部探针直接引用 domain.Measurement/User/SummaryEntry、persistence.MeasurementMapper/SummaryEntryMapper/ExactAggregate，静态合法不代表可替换这些固定接口。需求明确基础包，contract 固定业务名字。严格/非严格当前 Statement 告警、钱包实际连接严格模式、独立快照屏障、SQL 去重计数+分组分页恰2读及历史UTC读写均明写。/api/fixture/* 由测试注入，不要求教师自行实现；测试观测连接的环境设置、arm/release 控制响应、未保护CAST返回9.99属于夹具自检，不能冒充业务要求。

## family 建议

`资金`。同家族必须整体分配到同一个 train/dev/blind 集合，不按改写/从零拆分。

## 静态检查

检查器预编译一次：`mvn -B -q -pl kcg-cli -am compile`，成功。执行时包在 systemd scope 中：MemoryMax=1400M、CPUQuota=125%，Maven JVM Xmx=768m、MaxMetaspaceSize=256m；实际 nproc=2、总内存3875MiB、当时可用1839MiB；无 Maven -T，无测试、数据库或业务应用。

base 静态检查：PASS。按指定 `checker_command()` 调用检查器，输入为当前文件全文与 id=base 的 JSON；11 份逐个执行于 MemoryMax=600M、CPUQuota=100% 的 systemd scope，Java Xmx=256m、MaxMetaspaceSize=128m，本份退出码为 0。最终原始输出：

```json
{"id":"base","ok":true,"stage":"GENERATION","diagnostics":[],"fileCount":134,"digest":"645c20449e9aee150111728f558e5b67d23c920882203bd7e61ee5dd6f3374e7","sourceSha256":"d31859675be9b5d011ff5440b52d848c05523ac16c8bcf8fa9cccfc429dad27e"}
```

## 证据边界

只完成草稿与静态生成检查；业务判卷 NOT_RUN。没有运行 prepare/generate/repair，也没有创建 task.json、修改正式题目或提交。
