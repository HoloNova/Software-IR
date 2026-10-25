# SIR 数据首批工具

范围：冻结题目与输入、少量教师采样、保留失败与反馈修复、复用 `kcg check` 做静态筛选、只导出有四门证据的训练答案。不实现第二套 SIR 语义，不训练模型，不租 GPU，不在脚本里构建/启动应用或数据库。

首题沿用已采用的双 Token 登录。该业务家族固定属于 `train`，是公开开发题，不是盲测集。现有参考答案标为 `existing_reference`；教师输出标为 `teacher`。初始采样、反馈修复与独立题目不能混算。

## 安装与命令

Python 3.11+，Java 21；Python 依赖见 `requirements.txt`，可选的CPU分词计数依赖在 `requirements-profile.txt`。安装到自己的虚拟环境，不向项目 Java 模块添加依赖。检查器使用当前模块的 `target/classes` 与 ANTLR 4.13.2；先按项目资源约束编译受影响模块，工具不会自动运行 Maven。

```bash
python -m pip install -r experiments/slm/requirements.txt
python -m experiments.slm.data --help

# 每批使用一个尚不存在的新目录；保留数据不要只放在会被 mvn clean 删除的 target 中。
python -m experiments.slm.data prepare --bundle datasets/sir/pilots/next-run

# 只从环境读取 DEEPSEEK_API_KEY，不支持将密钥作为命令行参数。
# 默认单次调用禁用自动重试。一次命令最多5次，每次最多16384输出tokens。
python -m experiments.slm.data generate --bundle datasets/sir/pilots/next-run --count 3 --max-tokens 6144

python -m experiments.slm.data check --bundle datasets/sir/pilots/next-run --maven-repo "$HOME/.m2/repository"
```

本机 Maven 仓库实际是 `D:/maven-repo`，Windows 检查时显式传入该值。命令参数是 argv，不拼接 shell。CLI 的进程退出码 0 不等于每个样本通过，必须核对每行 `ok`、`stage` 和诊断。

## 反馈修复

先从 `checked.jsonl` 选择一个 `static=FAILED` 的样本 ID，再显式调用一次修复。下面使用本轮实际 ID；新一批应改为那一批的 ID。

```bash
python -m experiments.slm.data repair \
  --bundle datasets/sir/pilots/dual-token-v1 \
  --sample-id auth-dual-token-v1-teacher-f4b4a6071888

# 检查结果写到新文件，不覆盖首轮结果。
python -m experiments.slm.data check \
  --bundle datasets/sir/pilots/dual-token-v1 \
  --maven-repo "$HOME/.m2/repository" \
  --output datasets/sir/pilots/dual-token-v1/checked-next.jsonl
```

没有自动“修到通过”的循环。每轮原始答案、完整 SDK 响应、请求、用量、源哈希和 `parent_id` 都保留。修复反馈包括实际诊断、语法规则澄清和题目原有 DDL，不提供完整参考答案。SDK 序列化的完整响应不是 HTTP 原始字节；`raw_response_sha256` 指保存的 `response.json`。API 模型别名与 `system_fingerprint` 也不是开源权重的固定 revision。

## Prompt 版本

`prompts/` 里每个版本是一个单独的文件，改 prompt 就新建文件，不改已用过的文件：已有批次的 `system.md` 和候选里的 `messages` 都是旧文件的原文，改了旧文件，这些批次就无法复现。`prepare` 默认用 `sir-0.3-auth.md`（v1），`repair` 默认用 `repair-auth-v1.md`；要用新版本时显式传入，只接受 `prompts/` 里的 `.md` 文件。`import` 不看这两个参数，直接冻结来源批次自己的 `system.md`。

v2（2026-10-25）补的是首批暴露的两处提示不足：实体成员的固定顺序；新建的行要先 persist 才有主键（编译器从 `bca868b` 起报 `SIR-FLOW-007`）。

```bash
python -m experiments.slm.data prepare --bundle datasets/sir/pilots/dual-token-v2 \
  --system-prompt experiments/slm/prompts/sir-0.3-auth-v2.md
python -m experiments.slm.data repair --bundle datasets/sir/runs/dual-token-recheck-v2 \
  --sample-id auth-dual-token-v1-teacher-ef9ddc30eb79 \
  --feedback experiments/slm/prompts/repair-auth-v2.md
```

测试：`PYTHONPATH=$PWD:$PWD/experiments/slm/tests python -m unittest experiments.slm.tests.test_pipeline experiments.slm.tests.test_corpus experiments.slm.tests.test_dataset_io`（在仓库根目录运行；`test_dataset_io` 从 tests 目录导入 `test_corpus`）。

## 跨环境导入

原批次指纹绑定实际源码字节，不能靠换行转换或修改manifest搬到Linux。先在目标环境编译CLI/测试适配器，再执行：

```bash
python -m experiments.slm.data import \
  --from-bundle datasets/sir/pilots/dual-token-v1 \
  --bundle datasets/sir/runs/dual-token-cloud-v1
python -m experiments.slm.data check \
  --bundle datasets/sir/runs/dual-token-cloud-v1 \
  --maven-repo "$HOME/.m2/repository"
```

import验证原冻结文件、响应/请求/源码及metadata，保持原批次不变，重新冻结本环境工具链/oracle；`imported_from`保留原指纹。只复制教师原始响应与校验过的收集器，不调用API，不迁移任何PASSED或生成摘要。当前参考另作为种子重新加入。必须在新批次重做静态和实际业务门，再导出；不要把`from-bundle`当运行批次。

## 参考环境中的真实验收

本机不执行。只在已经配置好受限 MySQL 权限、Maven 依赖和资源限制的 Linux CI/参考环境运行。继续使用原有 `DualTokenBusinessConformanceIT`、DDL、种子、锁、HTTP/JDBC 断言和清理，不另起一套判题。普通 CI 不设置候选属性，仍检查原参考答案。

外部候选只接受单文件 `candidate.sir`，通过生产 `ProjectSourceReader` 的有界、目录句柄及 NOFOLLOW 读取。不支持安全目录句柄的 Windows 会拒绝，不能为实验降低读取要求。源码必须包括本轮测试适配器，不能拿旧 CI 的固定夹具结果代替候选验收。

下面是完整调用模板；先按 `nproc`、`free` 确定外层限制，设置 `MEMORY_LIMIT`、`CPU_QUOTA`，并沿用已有 `KCG_CONF_CONTROL_JDBC_URL`、`KCG_CONF_CONTROL_USERNAME/PASSWORD`、`KCG_CONF_RUNTIME_USERNAME/PASSWORD`。密码不进参数或数据文件。

```bash
: "${MEMORY_LIMIT:?Set a safe scope limit after checking free}"
: "${CPU_QUOTA:?Set a safe scope quota after checking nproc}"
: "${MAVEN_REPO:?Set the absolute frozen Maven repository path}"
REPO="$(pwd)"
BUNDLE="$REPO/datasets/sir/runs/dual-token-cloud-v1"
CANDIDATE="$BUNDLE/samples/auth-dual-token-v1-teacher-ef9ddc30eb79"

MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2' \
systemd-run --scope -q -p "MemoryMax=$MEMORY_LIMIT" -p "CPUQuota=$CPU_QUOTA" -- \
  mvn -B -o -pl sir-toolchain-application -am test \
  -Dtest=DualTokenBusinessConformanceIT -Dsurefire.failIfNoSpecifiedTests=false \
  '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2' \
  -Dkcg.dual-token-conformance.enabled=true \
  -Dkcg.query-conformance.schema-name=kcg_conf_run \
  -Dkcg.conformance.work-parent="$REPO/ci-work" \
  -Dkcg.conformance.evidence-parent="$REPO/ci-evidence" \
  -Dkcg.conformance.maven-executable="$(command -v mvn)" \
  -Dkcg.conformance.maven-repo="$MAVEN_REPO" \
  -Dkcg.conformance.maven-xmx-mb=384 \
  -Dkcg.conformance.application-xmx-mb=512 \
  -Dkcg.conformance.child-metaspace-mb=128 \
  -Dkcg.slm.candidate-root="$CANDIDATE" \
  -Dkcg.slm.bundle-root="$BUNDLE" \
  -Dkcg.slm.repo-root="$REPO"
```

共用同一 schema 时串行运行。环境不齐为 `NOT_RUN`；测试会先检查开关及环境，不会因此自动启动数据库。失败不是自动扩大权限或创建另一 schema 的理由。候选运行会在该次 `ci-evidence` 子目录产出 `slm-runtime.json`，绑定候选、题目、oracle、工具链和生成摘要；同目录日志及业务报告也有哈希。

实际参考环境已在负责人指定的`ssh lab`上跑通（原生systemd scope，不是Forgejo Actions或ML默认镜像）：参考35/0、四门通过；教师`ef9ddc30eb79`静态/构建/启动通过、业务19/14失败，故新教师导出仍0条。Linux批次在`datasets/sir/runs/dual-token-lab-v1/`，原采样批次不回写。本次临时参考目录后按负责人要求删除，历史证据保留。工具使用指南仅以独立Prompt文本提供：Forgejo仅局域网直连，任务通过公网ML API派发，不依赖SSH。

## 导出与边界

```bash
# 没有真实运行证据时，正确结果是0条，不是把静态候选当训练答案。
python -m experiments.slm.data export \
  --bundle datasets/sir/pilots/dual-token-v1 \
  --checked datasets/sir/pilots/dual-token-v1/checked-r2.jsonl \
  --output datasets/sir/pilots/dual-token-v1/train-new.jsonl

# 有真实证据时，使用实际运行的新bundle与它的checked.jsonl，
# 额外传 --runtime-evidence /absolute/run-directory/slm-runtime.json
```

四门均 `PASSED`、绑定一致、证据附件实际存在且哈希一致，才导出为 TRL conversational prompt/completion；索引另存，不把元数据当语言模型标签。同题相同生成摘要去重；development/test/public_regression 不进入训练。完整修复历史会保留在 prompt 中，失败答案不作为 completion；训练前必须按实际分词长度分桶，不能静默截断。

每批 JSON/JSONL 有16 MiB预算，批量静态接口另有10000行上限。本工具是小批试跑，不是大规模调度平台。训练/开发/盲测按业务家族隔离，不以改名视为新题；冲突检查覆盖传入批次，汇合多批数据时还须检查合并索引。当前没有冻结盲测集，也未开始小模型评估。

本轮结果见 [首批报告](../../docs/qualification/SLM_DATA_PILOT_REPORT.md)，模型与云 GPU 建议见 [基座调研](../../docs/qualification/SLM_MODEL_SELECTION.md)。
