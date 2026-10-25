# SIR 数据首批闭环报告

2026-10-25，主会话执行。负责人确认“先做数据”，并委托小模型基座调研。没有训练学生模型、租用GPU、自动提交/推送或触发CI。

## 实际完成

- 冻结公开双Token题目、接口、SIR 0.3、业务家族和train归属；原有参考答案明确标为existing_reference，不冒充新教师输出或盲测。
- 新增 `experiments/slm` 的prepare/import/generate/repair/check/export。API密钥只从环境读取；单次采样有数量/token上限，禁用自动重试；保留完整SDK响应、请求、用量、源哈希、收集器快照及父子关系。
- 外部候选复用原 `DualTokenBusinessConformanceIT`、DDL、seed、HTTP/JDBC断言与清理。测试适配器经生产安全读源入口加载候选，不在输入失败时退回参考答案；共享harness增加已读不可变快照入口。Parser/Semantic/Lowering/Generator/CLI生产代码未改。
- 静态校验调用现有CLI完整GENERATION。WARNING保留；解析通过不算完整静态成功；运行证据与题目/oracle/工具链/源码/生成摘要均须绑定，附件须实际存在且哈希一致，才导出正式训练答案。

## 第一阶段真实采样结果（当时尚未运行真库；后续见下文）

教师API：`deepseek-flash`，只读模型列表当时显示DeepSeek-V4.1-Flash；响应仍回传别名，system_fingerprint为`e72d3c7f663d3e1383b00b3884cb4501`。这不是本地DeepSeek-Coder-1.3B学生权重，也不等于固定开源revision。

| 数据 | 数量 | 结果 |
| --- | ---: | --- |
| 既有参考种子 | 1 | 完整静态生成通过；旧CI资格不自动移植给新候选 |
| 初始真实教师候选 | 3 | 全部在PARSE失败，先暴露credential放在field之前 |
| 第一轮反馈修复 | 1 | 解析通过，SEMANTIC拒绝实体行绑定到Token标量、fails次序等 |
| 第二轮反馈修复 | 1 | 完整GENERATION通过，45个生成文件 |
| 新候选真实BUILD/START/BUSINESS | 0次执行 | **NOT_RUN** |
| 正式可训练业务答案 | **0** | 空导出是门的正确行为，不是任务成功率 |

修复分支：`f4b4a6071888 → 6702e9b70653 → ef9ddc30eb79`（完整ID均以`auth-dual-token-v1-teacher-`开头）。两次修复是同一道题的一条轨迹，不算两道新题，也不能把1/5当小模型泛化成功率。采用原始诊断、公开语法规则澄清和题目既有DDL，没有手改SIR答案或放宽oracle；完整参考答案没有发给教师。

五次实际API调用合计：input **15651 tokens**、output **5763 tokens**、total **21414 tokens**。保存缓存命中用量，不以token总数冒充实际账单金额。本轮不再自动扩大调用。

最终静态通过候选：

- ID：`auth-dual-token-v1-teacher-ef9ddc30eb79`。
- source SHA256：`a5cd4c7155a309916fd111531cbd46f7fea5981b0395e0ad37955b71e606122a`。
- generated digest（KCG checker域）：`4cbb1dff526bd13a0b2324020922da538210f05245eab4dafb9c4e6e60a8123d`。

该候选登出返回TokenPairView而非参考的LoggedOutView，且创建/持久化编排也不完全相同。静态通过不证明接口行为、主键赋值时序、并发或事务正确；当前保持候选状态，未评为业务正确或规范答案。不为收样本而改原语言/测试规则。

## 数据与复现

持久数据入口：`datasets/sir/pilots/dual-token-v1/`，避免只放在会被Maven clean删除的target。保留task/system/base/reference、manifest、6条候选记录、5次SDK响应和请求、2轮父子关系、三批静态结果、空SFT导出及索引。原始运行目录仍在 `target/slm-data/dual-token-pilot-v1/`；不重复执行API或检查来搬运数据。

方法与参考环境完整调用模板见 [实验README](../../experiments/slm/README.md)。显式import支持在Linux重新冻结工具链/oracle并导入原教师响应，保留原指纹与原始数据，将所有门重置NOT_RUN，不带走旧静态资格；实际5个响应的CLI导入已验证，新增API调用0次。真实判题需要本轮适配器源码和完整参考环境，Windows安全目录读取限制不放宽。代码/任务/oracle变动导致哈希失配时，必须建新批次，不改旧manifest冒充同一实验。数据目录的Git属性关闭换行转换，以保留原始字节哈希。

## 定向验证与边界

- Python按模块最新结果 **31项通过**（corpus17、dataset_io7、pipeline7）：拒绝静态冒充业务、源码/附件篡改、重复JSON键/NaN、缺失输入、截断响应、跨split同族/同源码泄漏；完整SDK响应绑定、参考种子不自动晋级、一次反馈修复与无密钥数据路径；导入不继承旧宿主指纹/通过资格，拒绝被改过的响应。先取得缺模块RED，再取得四处防篡改/输入边界的真实运行RED，补齐后转绿。
- Java **4类23项通过，0失败/错误/跳过**：DualTokenCandidateSupportTest 8、BusinessSliceCompilationTest 6、BusinessSliceHarnessEvidenceTest 5、KcgCheckTest 4。先以缺少候选适配器/快照重载取得编译RED，再实现；包括静态合法但TTL错误的候选不被替换成参考，以及编译成功也只能产出运行门NOT_RUN证据。
- 实际候选经真实CLI校验，不用模拟检查器冒充产品通过。部分Python测试用mock教师和证据形状验证传输/资格规则，这些不计入五次真实API样本。
- 本机只做静态校验与生成文件的测试物化，没有构建/启动生成应用、运行MySQL/HTTP IT、全量门或浏览器。覆盖率未测。AFT诊断请求桥超时，不能记为诊断清洁；Java编译以本轮Maven和实际XML为准。
- XML哈希、日志及Python RED/GREEN在 `target/slm-data-verification/`，关键摘要随数据verification子目录保存（新增导入测试首次因测试漏import json报错，修正后仅复跑pipeline7项，未重复已通过的另外24项；原日志保留）；不与#184的1970项累加。业务账本83/1/0不变。

## 下一步

参考环境对照与最终候选已实际运行，结果见下文。下一步把真实运行错误反馈给教师，生成新的有界修复子候选并重新验收；不得手改旧答案冒充模型修复。现有`repair`命令只接静态失败，运行反馈须显式保留原证据及新的parent轨迹，不伪造static失败。通过后再冻结下一批初始prompt的实体顺序/物理形状/生成身份时点说明，扩成更多需求组合。首轮3个初始失败同时暴露了参考提示不充分，不能据此断言教师或学生无能力；应分别记录模型、提示、工具链与环境归因。

训练/开发/盲测按需求家族分开，当前只有公开训练种子题，尚未建立盲测集、测小模型基线或启动训练。[基座与云GPU建议](SLM_MODEL_SELECTION.md)已按官方资料和实际长度给出；不为扩样本重开已验收的语言功能。

## 第二阶段：lab 真实参考环境（2026-10-25）

负责人指定Forgejo与`ml.campuslink.vip`所在的`ssh lab`机器作为可用测试环境。已验证Forgejo的`holonova`身份能创建/清理私有空仓库及Issue；Actions runner为空。ML签名API真实执行了成功、非零失败、超时、运行中取消与产物下载，实测cgroup限额。**本节SIR业务实际走SSH原生systemd scope，不是Forgejo Actions或ML API的默认Python镜像**，不等同于已通过公网ML API完成同一业务验收。

原生环境为4核/约7GiB、Java/Javac21.0.12.1、Maven3.9.14、MySQL8.4.11。隔离目录`/home/labadmin/sir-reference/20261025-pilot`，scope上限2304MiB/150%CPU，构建与两次IT串行，外层Maven256/384MiB、JUnit384MiB、生成Maven256MiB、应用384MiB并分别限metaspace。只构建受影响依赖并执行原DualTokenBusinessConformanceIT，不运行全量门或浏览器。

准备阶段保留两次真实失败：只装JRE导致缺`javac/ct.sym`，补同版本headless JDK后解决；第一次源码打包遗漏两个未跟踪测试适配器，补齐后逐文件核对1284份完整输入。源码清单SHA256为`60c59c1828dd99bda4b954deac67e3c4e6b64e872a0f3f7fec07f7aa5abeb86d`。这些是环境/传输问题，不记为教师业务失败；未修改生产源码、候选、DDL、seed或oracle。

目标环境执行import并重做真实静态检查，不迁移原通过资格；本轮教师API调用**0次**。随后同一oracle实际运行：

| 样本 | Static | Build | Start | Business | 检查 |
| --- | --- | --- | --- | --- | --- |
| 既有参考 | PASSED | PASSED | PASSED | PASSED | 35通过/0失败 |
| 教师`ef9ddc30eb79` | PASSED | PASSED | PASSED | FAILED | 19通过/14失败 |

参考run ID为`dual-token-slice-1a1720f89b4-17144`，教师为`dual-token-slice-1a1721082e4-62691`。教师登录HTTP500的首发根因已由真实应用日志和生成代码交叉核对：LoginService先执行`pair.setAccessToken(access.getId())`与`pair.setRefreshToken(refresh.getId())`，随后在persist步骤才为令牌赋`UUID.randomUUID()`；pair已复制null，INSERT token_pair省略了令牌列，MySQL报`Field 'access_token' doesn't have a default value`。后续认证/刷新等14处失败包含该首错的连带结果，不能当14个独立根因，也不能把部分拒绝路径PASS当功能正确。这里只验明失败，不手改模型答案或放宽oracle。

持久证据在`datasets/sir/runs/dual-token-lab-v1/`：两份runtime证据绑定同题、同源、同oracle和生成摘要，附真实JUnit XML、业务/HTTP记录、Maven/Spring日志；verification保存完整输入清单、运行脚本、准备失败、生成Java和oracle源码快照。传回原始归档SHA256为`b08e9a0b130fc72d1bfe26c59adc5afe297c1876f9f1f877d8942eaa3a16dec4`，逐附件哈希检查通过。

实际调用现有导出器：`train-teacher.jsonl`仍为**0条新教师合格答案**；`reference-seed-qualified.jsonl`为1条已实际验收的既有参考，明确单列，不混入新模型数据或小模型成绩。最终检查证明本轮scope/HTTP端口18184、随机schema、两个临时账号与private凭据文件全部清理，保留平台原有MySQL/worker。未提交、推送、触发GitHub或Forgejo CI，未启动模型训练；业务账本83/1/0不变。

后续按负责人要求，已删除`docs/LAB_PLATFORM_GUIDE.md`和部署机的`/home/labadmin/sir-reference`临时目录；上文是历史运行环境，不再作为后续可复用入口。已归档的原始业务证据保留，结论不变。工具操作指南仅以独立Prompt文本提供：Forgejo仅局域网直接访问，任务通过公网ML API派发，不依赖SSH。
