# 活动报名缴费题判卷追溯（草稿）

本目录落实待审的 [spec.md](spec.md)，family `events.registration`、split `train`。`requirement-*.md` 与 `contract.json` 分别逐字／逐对象取自规格 §5、§4；DDL/种子在规格 §2、§3，尚未落成 `.sql`。

**状态：规格草稿。**没有业务 IT、task、CI 证据、模型调用。参考与 base 静态检查：均 GENERATION 通过、无诊断（42 与 35 个生成文件，规格 §0.2）。

## 1. 题目与参考

- 参考：[reference.sir](reference.sir)，6 能力。base：[base.sir](base.sir)，切掉 Register、EventRevenue、EventRegistrationCount，缺三个 Controller 与 Service、`ExactAggregate`（静态负例，未运行 base）。
- 夹具：规格 §2 的四张表（含真实的 `(event_id, attendee_id)` 唯一键、无业务 CHECK）与 §3 种子（9 活动、9 报名）。

## 2. 逐句追溯

S01–S13 为 [requirement-scratch.md](requirement-scratch.md) 非空行顺序；C01–C08 为 [requirement-change.md](requirement-change.md)。`ref` 为 reference.sir 中能力起始行：CreateEvent 79、ListEvents 98、Register 111、MyRegistrations 140、EventRevenue 155、EventRegistrationCount 171。

| scratch | change | 能力 | 检查（规格 §6） | 语言依据（R） |
|---|---|---|---|---|
| S01 身份 | C02 | 全部 | B01、B02 | 372–389 |
| S02 创建与校验 | C01（保留） | CreateEvent | C01–C04 | 94、178–192、253、402、1348–1356 |
| S03 列表 | C01（保留） | ListEvents | B01、L01、L02、R12 | 1115–1162、454–472 |
| S04 分页 | C01（保留） | ListEvents、MyRegistrations | L01–L03、M02 | 402、1162 |
| S05 报名 | C03 | Register | R01–R13、V05 | 261、1376–1378、1437–1530、1532、1624–1631 |
| S06 并发 | C04 | Register | X01、X02、I01 | 1488–1530、1679 |
| S07 本人报名 | C01（保留） | MyRegistrations | M01、M02、R13 | 1115–1162 |
| S08 收入 | C05 | EventRevenue | V01–V05、R11、I01 | 1681–1716、856–895 |
| S09 人数 | C06 | EventRegistrationCount | V01–V05 | 1624–1631 |
| S10 精确投影 | C01、C07 | EventView、RegistrationView | I02 | 450–472 |
| S11 拒绝/写范围/信封 | C07 | 全部 | 全部 UNCHANGED、I03 | 588–593 |
| S12 合同/路径优先/不能自设计数 | C07 | Register、CreateEvent | R09、C04 | 897–918 |
| S13 输出格式 | C08 | — | 静态检查 | — |

## 3. 结构覆盖（对 new-problem-plan §1）

`Money` 精度（Fee）、`sum`（EventRevenue）、`count`（EventRegistrationCount）、`unique (event, attendee)`、`update … when` 名额守卫（Register）、`exists` + `persist … else`（Register）、`Page`（ListEvents、MyRegistrations）。

## 4. 已知缺口

- 实现阶段文件均未创建；并发两轮（X01、X02）待实现。
- 规格 §7.2：E1（与 course.course-selection 核心同构）、E2（有意不做缴费状态）待负责人决定；E5 待首次 CI 实测。
