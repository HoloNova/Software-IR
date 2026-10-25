# Lab 平台真实冒烟证据

2026-10-25，用户指定本地Forgejo和ML平台。无GitHub写操作，无本项目提交/推送。

- Forgejo：已验证`holonova`身份，真实创建/查询/删除私有空仓库、创建/关闭Issue；未创建Git提交。Actions开关开启，但仓库可用runner返回空列表，自动CI NOT_RUN。
- 首次探针在所有上述API断言和清理之后，附加读取app.ini遇到Python ConfigParser不接受根级配置项而退出1；`forgejo-result.txt`保留原异常。之后仅补配置读取，`forgejo-config-followup.json`通过，未重复已成功的API操作；这不是Forgejo API失败或整轮脚本无错误的证据。
- ML签名API：`ml-smoke/result.json`保存成功/退出7/超时三任务及真实cgroup观测，`cancel.json`保存看到READY之后取消并最终canceled的证据。任务完整详情/日志/成功产物同目录保存。
- 使用的是专用公钥`b9ed486aec9c6453`，read/submit/cancel，无admin。私钥、Forgejo令牌、服务器环境凭据均未归档。
- 默认ML镜像无Java/Maven，故原SIR真实HTTP/MySQL验收走同机SSH原生scope；结果另见`datasets/sir/runs/dual-token-lab-v1/`，不记为Forgejo Actions或ML API默认镜像通过。

工具操作指南仅以独立Prompt文本提供：Forgejo仅局域网直连，任务通过公网ML API派发，不依赖SSH。原指南文件与临时SSH参考目录已按负责人要求删除，历史证据保留。`artifact-index.json`覆盖归档文件字节哈希，不包含索引本身。
