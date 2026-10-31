# 健身房打卡题判卷追溯（草稿）

本目录落实待审的 [spec.md](spec.md)，family `fitness.check-in`、split `train`。`requirement-change.md`、`requirement-scratch.md` 和 `contract.json` 分别逐字／逐对象取自规格 §5、§4；DDL/种子写在规格 §2、§3，尚未落成 `.sql` 文件。

**状态：规格草稿。**没有 `FitnessCheckInBusinessConformanceIT`、没有 task、没有 CI 证据、没有模型调用。下文“检查”一栏指规格 §6 里设计的检查编号，“代码位置”一栏尚不存在，因此不填。参考与 base 的静态检查结果见规格 §0.2：两份都 GENERATION 通过、无诊断（45 与 36 个生成文件）。

## 1. 题目与参考

- 参考：[reference.sir](reference.sir)，7 个能力，与规格 §1.3 声明清单一致；将来落库为 `sir-toolchain-application/src/test/resources/valid/fitness-check-in.sir`（待确认）。
- base：[base.sir](base.sir)，从参考按能力块切掉 MyMonthHours、MyMonthAverage、CoachCheckIns、CoachMonthVisits，其余字节不变；生成 36 个文件，缺四个 Controller 与对应 Service、`ExactAggregate`。这是静态负例，没有冒称运行过 base 的 HTTP。
- 夹具：规格 §2 的四张表、§3 的种子（10 条打卡，其中 5 条贴着 Asia/Shanghai 的月界）。
- 合同：[contract.json](contract.json)。`member`、`program` 是教练筛选的 HTTP 字段名，`programId` 是 LogWorkout 的 HTTP 字段名（规格 §7 发现 F2）。

## 2. 逐句追溯

S01–S11 是 [requirement-scratch.md](requirement-scratch.md) 的非空行顺序；C01–C08 是 [requirement-change.md](requirement-change.md) 的非空行顺序。`ref` 列是 `reference.sir` 中对应能力的起始行，`R` 是 `docs/SIR_LANGUAGE_REFERENCE.md` 行号。

| scratch | change | 能力（ref 行） | 检查（规格 §6） | 语言依据 |
|---|---|---|---|---|
| S01 身份/匿名/角色 | C02 | 全部；`AccessToken`、`Role` | B04、W02、K01 | R:372–389（凭据）、R:856–895（角色检查与 actor 读取） |
| S02 项目列表 | C02、C01（保留） | ListPrograms（66） | B01、B02 | R:1115–1162（find、order by、Page） |
| S03 分页 | C06 | ListPrograms、CoachCheckIns | B02、B03、K02、K06 | R:402、R:1162 |
| S04 记录打卡与校验 | C01（保留 LogWorkout） | LogWorkout（79） | W01–W06、Q08、K08 | R:178–192（精度）、R:253（约束）、R:1348–1356（create）、R:402（必填） |
| S05 本人月统计 | C03（时长/平均）、C01（保留次数） | MyMonthVisits（98）、MyMonthHours（112）、MyMonthAverage（126） | Q01–Q08、I01 | R:1778–1795（month）、R:1624–1631（count）、R:1681–1716（sum）、R:1718–1724（avg） |
| S06 教练查询 | C04 | CoachCheckIns（140） | K01–K06、K08 | R:1222–1250（可选筛选）、R:2043–2050（真值表） |
| S07 教练月统计 | C05 | CoachMonthVisits（158） | Q07、K07、K08、I01 | R:1778–1795、R:1624–1631、R:1222–1250 |
| S08 精确投影 | C07 | CheckInView、ProgramView | I02 | R:450–470（视图投影） |
| S09 拒绝/成功写范围/错误信封 | C07 | 全部 | 全部 UNCHANGED、I03 | R:588–593（错误信封） |
| S10 合同/当前用户只由令牌决定 | C07 | LogWorkout、MyMonth* | W02、W06、Q06 | R:897–918（expose 与路径） |
| S11 输出格式 | C08 | — | 静态检查（不算业务 HTTP 检查） | — |

## 3. 结构覆盖（对 new-problem-plan §1）

| 结构 | 能力 | 说明 |
|---|---|---|
| `count … where` | MyMonthVisits、CoachMonthVisits | 输出 `Serial` |
| `sum` | MyMonthHours | 十进制，见发现 F1 |
| `avg` | MyMonthAverage | 半进位两位 |
| `month … in … as since, until` | 四个月度接口 | 只读 query，区域为字面量 `Asia/Shanghai` |
| 可选筛选 | CoachCheckIns、CoachMonthVisits | `Optional<Ref<…>>`，发现 F2 |
| `Page` | ListPrograms、CoachCheckIns | |
| `Money`（十进制） | `Hours` 类型与 `Money` 输出 | 不是金额 |

## 4. 与规格的已知缺口

- 实现阶段的文件（DDL、种子、IT、task）都未创建；规格 §6.7 说明本题不设并发轮次。
- 规格 §7.2 的 F1（整数 `sum`/`avg`）、F2（可选 Ref 的 HTTP 名）等待负责人决定；F4 的校验错误 code 与 `0999-12` 需要首次 CI 实测。
- 不得给任何教师候选继承参考的通过证据；教师资格与参考资格分开。
