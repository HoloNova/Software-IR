# SIR 首轮基座与云算力选择

2026-10-25。范围是已核对的官方模型卡、配置、模型目录与真实分词长度，不是权重推理、训练或 SIR 成功率排名。当前没有下载模型权重、租用 GPU 或进行小模型评估。

## 结论

沿用原方向，首轮以 **`deepseek-ai/deepseek-coder-1.3b-instruct`** 做低成本研究基线；现代对照选 **`Qwen/Qwen3-4B-Instruct-2507`**。两者先在同一题目与工具预算下做未微调基线，再决定扩大训练规模。不是认定 DeepSeek 1.3B 已经最优，也不因旧 DeepSeek API 叫法把教师模型和学生权重混成同一个模型。

1.3B 是较早的代码指令模型，适合检验 SIR 是否能降低小模型负担。第一轮优先 BF16 LoRA，不为了节省很少的基座权重内存引入额外量化变量；显存紧张或扩到较大基座再选 QLoRA。微调前后比较必须保持权重精度、提示、工具和修复预算一致。框架复用 TRL/PEFT，不从零预训练，不先做 DPO/RL。

## 官方资料核对

| 模型 | 权重规模 | 文档/配置上下文 | 本项目定位 |
| --- | --- | --- | --- |
| [DeepSeek-Coder-1.3B-Instruct](https://huggingface.co/deepseek-ai/deepseek-coder-1.3b-instruct) | 1.3B，dense | 16K | 研究基线，成本低；不能据模型卡旧通用榜单推断 SIR 成绩 |
| [DeepSeek-Coder-6.7B-Instruct](https://huggingface.co/deepseek-ai/deepseek-coder-6.7b-instruct) | 6.7B，dense | 16K | 后续较强 DeepSeek 对照，不在数据阶段先下载 |
| [DeepSeek-Coder-7B-Instruct-v1.5](https://huggingface.co/deepseek-ai/deepseek-coder-7b-instruct-v1.5) | 7B，dense | 官方卡与配置均为4K | 不作为首选，已有修复轨迹超过4K，不能仅看v1.5名字升级 |
| [DeepSeek-Coder-V2-Lite-Instruct](https://huggingface.co/deepseek-ai/DeepSeek-Coder-V2-Lite-Instruct) | **16B总权重，约2.4B激活**，MoE | 官方卡128K；model配置163840、tokenizer配置16384，不能直接拿任一字段代替已验证有效窗口 | 不按2.4B dense预算；首轮暂不承担MoE装配和显存复杂度 |
| [Qwen2.5-Coder-3B-Instruct](https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct) | 3.09B，dense，GQA | 32K | 代码专项备选；该3B型号模型卡是Qwen Research许可，不误写成全系列Apache |
| [Qwen3-4B-Instruct-2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507) | 4B，dense，GQA | 模型卡/模型配置262144；不能拿tokenizer的更大声明当已验证窗口 | 现代、非thinking模式对照；Apache-2.0，避免长思考输出干扰首轮SIR输出任务 |

官方DeepSeek代码模型目录中仍可核对到上述1.3B、6.7B、v1.5及V2系列；不把目录下载量、仓库更新时间当成发布时间或业务质量证据。第一方来源另有 [DeepSeek-Coder](https://github.com/deepseek-ai/DeepSeek-Coder) 与 [V2](https://github.com/deepseek-ai/DeepSeek-Coder-V2)。原始抓取在 `target/slm-model-research/raw/`；轻量模型卡/配置及长度结果另随数据研究目录保存，权重和大分词器缓存不入数据集。

本次可解析到的权重仓库revision：

- DeepSeek 1.3B：`e063262dac8366fc1f28a4da0ff3c50ea66259ca`。
- Qwen2.5-Coder 3B：`488639f1ff808d1d3d0ba301aef8c11461451ec5`。
- Qwen3 4B：`cdbee75f17c01a7cc42f958dc650907174af0554`。

训练时应按固定revision与依赖版本拉取，并核对下载资产哈希；本次分词器来自抓取时的main快照，报告另外记录实际文件SHA256，不声称下载了这些revision的权重。

## 本批实际长度

使用 `tokenizers` 和官方chat template在受限Jinja sandbox中计数，关闭padding与truncation，没有加载模型。六个记录包括一个参考、三个初始候选及同一分支两次修复；不是六道独立题。

| 分词器 | 初始输入 | 参考答案完整训练序列 | 最长完整修复序列 |
| --- | ---: | ---: | ---: |
| DeepSeek 1.3B | 1937 | 3376 | **10489** |
| Qwen2.5-Coder 3B | 1557 | 2664 | **8348** |
| Qwen3 4B | 1557 | 2664 | **8348** |

本批不支持“统一max_length=4096/8192就够”的假设。首轮生成任务与修复轨迹应分桶；长轨迹要选择能完整容纳的窗口，或另行设计并重新验证压缩输入，不能丢掉最后的返回/守卫代码。两种Qwen tokenizer在这批文本上的长度相同，不表示权重表现相同。

复现工具：`python -m experiments.slm.profile_tokens --bundle datasets/sir/pilots/dual-token-v1 --models-dir target/slm-model-research/raw --output target/slm-model-research/token-profile-new.json`。可选依赖是本次已存在的 `tokenizers==0.23.1`、`Jinja2==3.1.6`；见实验目录的profile依赖说明。

## 云平台租用建议

数据采样和静态筛选阶段先不租GPU。正式开始基线/微调时，优先按小时短租一张 **24GB RTX 4090级别** GPU，Linux，建议主机内存至少32GB、磁盘预留100GB；单卡串行跑，不同时加载两种学生模型。平台可从AutoDL/RunPod等按实际可用性选择，未核验实时报价，不估造小时价或总训练费用。

这些是起租预算建议，**不是已测最低要求**。先用真实batch=1、实际4K/8K长度和所选精度测峰值，再测12K/16K长样本。LoRA、梯度检查点与兼容的高效注意力可降低训练内存；不要仅按“参数量×4bit”算总显存。较大的DeepSeek 6.7B或长序列若24GB余量不足，再考虑48GB档，而不是先租多卡A100/H100。

租赁前应已经有可运行的数据包、参考环境/业务判题入口和冻结评测方案；当前业务门仍NOT_RUN，因此尚不开始训练或购买算力。
