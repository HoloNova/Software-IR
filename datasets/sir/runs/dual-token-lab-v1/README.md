# 双 Token Linux 真实运行批次

2026-10-25，在负责人指定的 `ssh lab` 原生参考环境运行，不是 Forgejo Actions 或 ML API任务。原教师响应由 `data import` 导入，新教师API调用0次。原采样批次未修改。

| 样本 | STATIC | BUILD | START | BUSINESS | 业务检查 |
| --- | --- | --- | --- | --- | --- |
| existing_reference | PASSED | PASSED | PASSED | PASSED | 35/0 |
| teacher ef9ddc30eb79 | PASSED | PASSED | PASSED | FAILED | 19/14 |

教师源SHA256为 `a5cd4c7155a309916fd111531cbd46f7fea5981b0395e0ad37955b71e606122a`。首次错误：生成的LoginService先复制尚未赋值的令牌id到pair，后persist才生成UUID，SQL插入token_pair缺access_token而返回500。其余多个失败是连带结果；未手改答案或放宽oracle。

- `checked.jsonl`：本环境重新执行的完整静态结果。
- `runtime/`：两次独立IT的原始JUnit、HTTP/业务报告、构建/应用日志和`slm-runtime.json`；每份证据包含源码/题目/oracle/工具链/生成摘要绑定及附件哈希。
- `verification/summary.json`：准确计数和实际入口。
- `verification/generated/`：解释首次错误的实际生成Java，不是修复后的代码。
- `verification/oracle-source/`：本轮oracle源码快照，仅用于复现证据，不成为第二套判题器。
- `verification/compile-missing-jdk.log`、`compile-incomplete-source.log`：准备失败，不能记为候选业务错误；补齐后当前构建成功。
- `verification/final-environment-and-cleanup.txt`：本轮应用/scope/18184端口、随机schema/账号/private凭据均清理，原平台服务保留。
- `train-teacher.jsonl`：**0条**新教师合格答案；空导出正确拒绝了业务失败候选。
- `reference-seed-qualified.jsonl`：**1条**既有参考种子，单列且不得计为新教师样本或小模型成绩。

源码基线为 `664fd19` 加明确封存的未提交适配器等输入，不声称同Git提交CI通过。完整1284文件清单hash为 `60c59c1828dd99bda4b954deac67e3c4e6b64e872a0f3f7fec07f7aa5abeb86d`；平台为Java21.0.12.1/MySQL8.4.11，scope2304MiB/150%CPU，所有步骤串行。没有全量构建闸门、CI或训练。

本次临时参考目录后按负责人要求删除，原始证据保留；后续任务通过公网ML API派发，不依赖SSH。工具指南仅以独立Prompt文本提供，完整历史分析见 [数据报告](../../../../docs/qualification/SLM_DATA_PILOT_REPORT.md)。
