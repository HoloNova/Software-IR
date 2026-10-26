# 出题备注

## removed

从 `valid/shop-conformance.sir` 仅删除以下完整声明：

- capability：AddToCart、ChangeCartQuantity、RemoveFromCart、MyCart、ClearCart、Checkout
- entity：CartItem
- view：CartItemView
- input：NewCartItem、CartItemRef、CartQuantity
- error：AlreadyInCart、CartItemNotFound、CartTooLarge、CartEmpty
- enum：无

所有 CartItem 使用处及专属输入/视图/错误已整块移除，余下代码不再引用 CartItem；Coupon/Redemption 保留，因为 CancelOrder 也会退券。
保留声明和外壳逐字不动；未改 software、namespace、声明顺序及格式。删除功能被追溯表中的对应 HTTP 检查命中，缺路由时不能通过原判卷；本轮未运行业务 IT，失败结论是依据路由缺失作出的静态推断。

## 隐含要求

namespace 必须为 example.multiplication，测试复制 ShopSessionFixture 到此包接入实际保护路由。取消明细存在性+上限101有序读取、100/101界、按商品统一锁顺序、父订单/房间真实等待及获锁后重查、晚失败已到达2个商品更新、全部源读取白名单都明写。非严格会话头 X-Fixture-Sql-Mode 是夹具自检而非要求业务生成该响应头；库存边界拒绝仍是业务要求。该混合题与课程、状态流转及图书馆二手市场有相似流程，划分集合须考虑这些关联。

## family 建议

`商店（混合课程计数、房间预约）`。同家族必须整体分配到同一个 train/dev/blind 集合，不按改写/从零拆分。

## 静态检查

检查器预编译一次：`mvn -B -q -pl kcg-cli -am compile`，成功。执行时包在 systemd scope 中：MemoryMax=1400M、CPUQuota=125%，Maven JVM Xmx=768m、MaxMetaspaceSize=256m；实际 nproc=2、总内存3875MiB、当时可用1839MiB；无 Maven -T，无测试、数据库或业务应用。

base 静态检查：PASS（1 条非阻断警告）。按指定 `checker_command()` 调用检查器，输入为当前文件全文与 id=base 的 JSON；11 份逐个执行于 MemoryMax=600M、CPUQuota=100% 的 systemd scope，Java Xmx=256m、MaxMetaspaceSize=128m，本份退出码为 0。最终原始输出：

```json
{"id":"base","ok":true,"stage":"GENERATION","diagnostics":[{"code":"SIR-WARN-001","severity":"WARNING","stage":"SEMANTIC","message":"先检查再插入，并发的两个请求可能同时通过检查：给这一步用到的行加 load … for update，或者给被插入的实体声明 unique","span":{"sourceId":"sample.sir","startLine":582,"startColumn":9,"endLine":582,"endColumn":131,"startCodePointOffset":19869,"endCodePointOffset":19991},"related":[],"fixes":[],"sourceSymbol":null,"sourceNodeId":null,"loweredNodeId":null,"relativePath":null}],"fileCount":152,"digest":"971cc4e7d61ecce670438e3e7f912e0ec405054c4876fe0c2e7fb3d9adaf807f","sourceSha256":"e0eca9d0245cd148b8cb1cc45b899176e6007726a3c767ce3ef3c0cfb624a521"}
```

警告位于保留的 BookAfterRead 预读检查；为遵守保留部分逐字不动的约束，没有改该流程消警告。

## 证据边界

只完成草稿与静态生成检查；业务判卷 NOT_RUN。没有运行 prepare/generate/repair，也没有创建 task.json、修改正式题目或提交。
