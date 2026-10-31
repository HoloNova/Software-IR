# g13 报修一轮静态反馈与候选业务门准备

负责人确认两份报修各反馈修复一次、静态复检、提交/普通推送原件，再将静态通过教师送候选业务CI并归档；不改工具链或学生数据，不扩采变体。两次修复预算12万tokens，最大输出8192/次，SDK零自动重试。程序来源仍为已提交LF快照 `fdbb83a5d3a38a3bce9c3504f1a7aa54e5a0c4a1`，collector引用与初采相同；不从Windows CRLF源码发请求。

显式使用已有 `experiments/slm/prompts/repair-general-v1.md`，而非鉴权专用默认模板；只附父候选、原静态诊断与冻结DDL，不提供参考答案或手写修法。`approval.json`记录模板哈希、来源与实际内容的保守预算，`events.jsonl`及call-1/call-2记录两次真实调用。

| 原题 | 父→子后缀 | input/output/total | 一轮后静态 |
|---|---|---|---|
| maintenance-work-order-change-v1 | 66485d05ad4a→3a0cc743ec66 | 12801/1919/14720 | PARSE失败，4×SIR-LEX-001；新源码与父完全相同 |
| maintenance-work-order-scratch-v1 | 1b3afbe59ca1→e2bcf34bd576 | 11579/1907/13486 | SEMANTIC失败，SIR-FLOW-003、SIR-SYMBOL-002 |
| 合计 | 2次，2子候选 | 24380/3826/**28206** | 0通过/2失败 |

实际模型均deepseek-flash，finish_reason=stop，无截断、拒收或传输失败；输入缓存命中22016、未命中2364。初采4次42213加本轮2次28206，累计6次**70419 tokens**。未用预算不用于追修；两个失败分支按各一轮封存，原父/子及诊断不覆盖。

改写原样返回156–159行三元表达式，源码SHA256仍为 `6be72486f3ff282870af2b797d98c9ca2ee80047f1ba9738d4099f3fa9b8b565`。从零写改为addDays后通过PARSE，现有语义诊断为第171行load使用FacilityNotFound但本能力缺fails声明，以及第195行读取patch输入不存在的expectedVersion字段；不猜测被语义门阻断的后续业务结果。

付费调用结束后，临时后处理脚本用必填键读取existing_reference不存在的parent_id而中断。两次原生响应和调用日志均已保存；独立离线续作按可选字段筛选新子候选，未重复付费、未重发请求。事故及处理记在summary.json，不修改原collector、工具链或候选源码。

本次仅对两个新子候选做一次原CLI完整静态检查。原输入/stdout/stderr/参数在checks/，绑定结果为checked-repairs.jsonl；两个报修批次各追加checked-r1.jsonl，保留参考、失败父及子。全部报修运行门NOT_RUN，不派发业务门。

## 本轮候选业务门范围

只送两份排班初采教师，沿用各批的checked-initial.jsonl，分别使用StaffingShiftRosterBusinessConformanceIT：

- staffing-shift-roster-change-v1-teacher-23ed7f759fcd，GENERATION，56文件。
- staffing-shift-roster-scratch-v1-teacher-2027a6702825，GENERATION，57文件。

先提交/普通推送初采及一修全部原件和哈希，再派发slm-business-gate.yml。实际受测SHA、run ID与精确资格由后续dispatch/原archive-gate记录，不把collector来源提交或参考CI资格冒充教师资格。失败运行也归档，不自动再修、不export或训练。12变体、fitness/events、旧批次和学生v5原字节不动。
