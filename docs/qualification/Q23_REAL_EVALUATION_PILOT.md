# Q23 真实工程评测试跑（2026-10-09）

## 1. 结论

两个SIR均通过Q23静态校验，均实际生成22文件工程、离线Maven构建并启动Spring Boot。正确样本满足课程需求；只漏掉报名状态过滤的样本仍能编译/启动，但业务断言拒绝它。**静态通过不能作为业务正确标签；评测必须绑定原始需求，而非由候选SIR推导期望值。**

| 样本 | STATIC / GENERATION | BUILD | START | BUSINESS |
|---|---|---|---|---|
| correct：既有course-enrollment.sir | PASSED | PASSED | PASSED | **PASSED**：4门CS101、CS102、MAT101、ZOO101 |
| missing-status：仅删除ACTIVE状态过滤 | PASSED | PASSED | PASSED | **FAILED**：6门，误返回只有CANCELLED报名的ART101、ENG101 |

这不是生成器缺陷：错误样本的SIR描述了错误业务，生成工程忠实实现了它。本轮是**既有题目的正例/受控反例评测链验证**，不是SLM实际输出集合或模型成功率。真实SLM样本、手册和新题目测试包尚未取得，不宣称覆盖任意SIR/业务。

## 2. 场景与证据

原始需求和数据沿Q11：至少一条ACTIVE报名才能选择课程，根去重/按code排序/分页；关联投影保留匹配课程其他状态报名；非法页400；不修改三表。题目包见[README](../../sir-toolchain-application/src/test/resources/conformance/slm/README.md)。两个样本使用同一DDL/seed、同一HTTP/JDBC oracle，不随错误样本修改期望值。

- 正样本第一页total=4、四门课程；第二页size=2为MAT101/ZOO101。
- 错误样本第一页total=6、codes ART101/CS101/CS102/ENG101/MAT101/ZOO101；第二页变为CS102/ENG101，两项需求断言失败。报告`businessErrorCode=BUSINESS_ACTIVE_ENROLLMENT_FILTER_MISMATCH`、`business=FAILED`，不会将它标业务通过。
- 两者嵌套Student/Enrollment投影和非法页400成立。独立JDBC比较course 7行、enrollment 37行、student 3行的完整行列表，前后完全一致；指纹均`d50f80b14a79a612e138f2602e38319fa56abb36854c49989649b899539104c1`。指纹不替代原列表相等断言。
- 新入口GENERATION digest与实际落盘22文件再计算的digest一致，逐文件字节核验；没有改生成Java/POM来让它通过。
- `SlmGeneratedProjectEvaluationIT`实际启用，1个JUnit用例覆盖2样本，**1/0/0/0**；正确样本Harness13通过/0失败，错误样本11通过/2预期业务失败。JUnit通过表示能正确接受/拒绝两者，不表示错误候选BUSINESS通过。
- 可选测试子JVM限额校验`ConformanceJvmLimitsTest` **2/0/0/0**（默认空参数不改变旧行为、显式限额/非法选项拒绝）。只改test harness和新增测试资源，不修改任何产品编译语义/静态JSONL协议。

## 3. 源码与资格绑定

本轮基于未提交的Q23工作区，不是同SHA CI证据。基线HEAD=`f8a2f92bee936359e819d24c9768e188c9275ca5`；实际受测源树（生产/测试Java、Grammar、POM、SIR/SQL和测试资源共756文件）SHA：

`980cfa09233901fe3ae1ca89b5642db2bc0840f1873fd2b6ac64a5dc74c1c643`

源树散列域：ASCII `KCG-LOCAL-EVAL-SOURCE-TREE-V1\0`，按路径分量字典序（Python Path排序），每文件uint64BE路径UTF8长度/路径、uint64BE原字节长度/原字节；排除target/.git。manifest逐文件SHA/字节数与源码archive保存，运行后复核全部相同。报告中的`generatedFiles combinedSha256`是旧Harness散列域，**不是**Q23 `generatedDigest`。

| 样本 | sourceSha256 | Q23 generatedDigest |
|---|---|---|
| correct | b5e6bd5b7507e8558def599b4cb713729c71bfd4653cfefdd1749d92b443ead7 | 9edf258e2337f16d3a2deb4d44c309d42819dbb886802ac99cbb539e24de90ac |
| missing-status | 213cd142e79d5ad417fdfff5385831d483a2016d878a8b34b29cbc5c05009d4e | f67f40d871dcabc4c3c0bd66b6d0c1ca01ee418959197bf40c572a9c0541cb25 |

DDL SHA `8ae2eb5325cb2db5533503c7afefadcf0efe893b12c696e684416040f40517b9`；seed SHA `8d41f7d6d3d69f01e8564a06bd68edd286dfd8c0ac4105de313adfa95418247d`。test package revision=`Q11_ACTIVE_ENROLLMENT_GOLDEN_V1`，由受测源码manifest绑定。

## 4. 环境、资源与清理

运行前2vCPU、总内存3875MiB、available2469MiB；当次源码未提交，GitHub现有workflow无法覆盖，所以按负责人明确要求本机限定串行试跑，不全量/不重复五IT。Java21.0.12.1/Maven3.6.3，生成工程依赖冻结于/root/.m2/repository，全程Maven offline；MySQL8.4.11临时容器，镜像digest `sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242`、server UUID `49137bde-c394-11f1-87f0-42bc1b11d681`。

- Java任务`systemd-run --scope -p MemoryMax=1536M -p CPUQuota=100%`；MySQL另限512MiB/CPU50%，合计资源上限2GiB/150%。Docker daemon的容器不在Java scope里，不以父命令scope冒称限制到了容器。
- 主Maven Xmx256m/Metaspace128m，Surefire Xmx192m/Metaspace128m，生成Maven与应用各Xmx256m/Metaspace128m；旧child env不继承宿主MAVEN_OPTS，使用明确的test-only参数补齐。无Maven -T或并发重活。
- 两样本的IT 63.48s；包含定向Reactor准备的命令93.49s，不包含镜像拉取/数据库预备。GNU time最大单进程RSS249312KiB（约243.5MiB），**不是全部JVM合计峰值**；内核未提供scope memory.peak，不能编造总峰值。scope memory.events max/oom/oom_kill均0；容器结束检查约474.9MiB/512MiB、OOMKilled=false，该值是快照，不是峰值。
- 仅loopback33306/18080，沿env.sh的kcg_conf_run schema与runtime权限，既有advisory lock串行；数据库由测试DDL/seed初始化，不声称产品初始化/迁移能力。
- 两应用退出、两轮schema删除且不存在、锁释放均报告证明。最后进程表无java/mvn/mysqld、两个端口无监听；本轮新建容器/卷/新拉镜像均移除，env.sh与已有卷不改。错误工程原源码归档到证据后清掉work，不留数据库/应用/构建进程。

## 5. 原始产物与后续边界

本机证据：

- `/root/kcg-conformance/evidence/slm-correct-1a11ec8b559-961/`：report、脱敏构建/应用日志。
- `/root/kcg-conformance/evidence/slm-missing-status-1a11ec93939-4165/`：错误样本report与脱敏日志。
- `/root/kcg-conformance/evidence/q23-real-evaluation-source/`：source-manifest.json、tested-source-tree.tar.gz、negative-generated-project.tar.gz、两XML、运行/预备日志。
- `/tmp/q23-real-evaluation.log`：唯一有效真实运行；`/tmp/q23-real-eval-compile.log`：限额测试与新IT编译。

同SHA双全量/五既有IT仍NOT_RUN；当前限定新IT未加入CI，需后续负责人决定CI接入，不沿用Q21证据。手册/新题目/真实SLM样本评测、BUILD失败/环境归因完整矩阵仍NOT_RUN。通用运行平台未实施，Q22仍搁置，未提交/推送/部署生产。Q23当前仍AWAITING_CI，不提前验收。
