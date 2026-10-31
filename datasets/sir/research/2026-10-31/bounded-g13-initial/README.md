# 报修/排班 g13 四道原题初采

任务、16个仅参考批次与参考CI原件已于 `fdbb83a5d3a38a3bce9c3504f1a7aa54e5a0c4a1` 提交并普通推送。此提交仅为task/data/docs，带 `[skip ci]`，未改变工具链或判卷；既有参考验收仍绑定 `5fe4c833` / [verify 41194982453](https://github.com/HoloNova/Software-IR/actions/runs/41194982453)。本目录及四个原题批次的新增采样记录尚未另行提交。

负责人确认四道原题各初采1次，预算18万tokens、每次最大8192输出，零自动重试，不含失败修复。实际用原collector在有独立Git身份的LF导出运行目录执行，8份首方源码逐字节匹配已提交blob；collector SHA256=`66dfb137a02619b145e67be0e9392aae4f42c06b6add6de7c4090a9e18140216`，完整来源闭包在 `preflight.json` / `summary.json` 及每份candidate中。不执行归档程序副本，不改采集器或冻结manifest。

## 四次真实调用

请求与实际模型均 `deepseek-flash`，thinking禁用；四次 `finish_reason=stop`，无截断、拒收或传输失败。表中tokens为API返回的usage，含缓存命中输入，不是按字符估算，也不是美元账单。

| task | teacher后缀 | input | output | total | 首轮静态 |
|---|---|---:|---:|---:|---|
| maintenance-work-order-change-v1 | 66485d05ad4a | 9508 | 1919 | 11427 | PARSE失败，4×SIR-LEX-001 |
| maintenance-work-order-scratch-v1 | 1b3afbe59ca1 | 8770 | 1906 | 10676 | PARSE失败，SIR-PARSE-001 |
| staffing-shift-roster-change-v1 | 23ed7f759fcd | 8873 | 1567 | 10440 | GENERATION通过，56文件 |
| staffing-shift-roster-scratch-v1 | 2027a6702825 | 8047 | 1623 | 9670 | GENERATION通过，57文件 |
| 合计 | 4次、4候选 | 35198 | 7015 | **42213** | **2通过/2失败** |

输入中缓存命中16896、未命中18302。四次初采低于18万tokens预算；未使用的137787不能自动转成修复/变体授权。调用边界与用量在 `events.jsonl`、四份 `call-N.json`；原生request/response/attempt/candidate在各原题g13批次的 `samples/`。事件日志不是重试调度器，已经消耗的四次不重发。

## 原始诊断与失败原因

报修改写 `66485d05ad4a` 的156–159行使用四个 `input.x.present ? ... : ...`，问号触发SIR-LEX-001。其后可能还有其他问题，但PARSE未过，不能声称语义/生成已检查完成。从零写 `1b3afbe59ca1` 第243行写 `input.firstDueAt + index * input.intervalDays days`，动态days写法不属于该语法，SIR-PARSE-001。手册已有PATCH直接绑定和addDays的说明；本轮只记录教师未遵守，不擅自改源码、补提示或修工具链。

四个新教师候选由该参考CI的原CLI在同一JVM做完整静态检查，原输入/stdout/stderr/执行参数在 `checks/`，结果在 `checked-teachers.jsonl`。中文诊断原JSON正确，Windows控制台的显示乱码不回写。两份排班到GENERATION，digest分别为 `185ed5d90adbbbf494d32fe66fbc7d4e2626bff18f3b158f02fdd98ba6c81a38`、`16f22022556db08d223bf6ebea67f39d4465eb1a3056c6a12499146224117f2f`，不是参考digest，不能替换。

## 证据与当前边界

四个原题批次各从1份existing_reference追加1份teacher，原 `checked.jsonl` 和 `checks/` 不覆盖；`checked-initial.jsonl` 合并原参考静态行与本次教师新结果。12个变体不采、不修改。冻结的task/base/reference/system/manifest原字节不变，代码、oracle、DDL/seed、fitness/events、旧批次及学生v5不变。

所有新教师的build/start/business均 **NOT_RUN**，未送业务门、未进行任何反馈修复、未export、未新增四门合格答案或训练记录，学生仍57条/56题。两份报修可在另获预算后各做一轮原生静态反馈修复；再独立送业务门。剩余预算和参考资格不构成这两步的自动授权。
