# g13 两份排班教师：独立四门验收与原件归档

受测提交 **46671b9a089d7d56b4518e19d7098dea118ac180**，不是collector来源fdbb83a5或参考CI提交5fe4c833。每个候选只派发一次slm-business-gate.yml，使用已提交的本批checked-initial.jsonl和StaffingShiftRosterBusinessConformanceIT。第二次POST后列表存在短暂延迟，单次只读对账找到已有run，未重新派发。

| 原题 | teacher后缀 | 候选CI | 四门 | 业务 | X01/X02/X03不同连接与事务数 |
|---|---|---|---|---|---|
| staffing-shift-roster-change-v1 | 23ed7f759fcd | [#94 / 41203150293](https://github.com/HoloNova/Software-IR/actions/runs/41203150293) | 全PASSED | **871/0** | 6/3/6 |
| staffing-shift-roster-scratch-v1 | 2027a6702825 | [#95 / 41203161844](https://github.com/HoloNova/Software-IR/actions/runs/41203161844) | 全PASSED | **871/0** | 6/3/6 |

两份候选各自真实构建/启动/HTTP/JDBC及三轮锁等待屏障通过，完整生成56/57文件，生成摘要分别为185ed5d90adbbbf494d32fe66fbc7d4e2626bff18f3b158f02fdd98ba6c81a38、16f22022556db08d223bf6ebea67f39d4465eb1a3056c6a12499146224117f2f。environment.test_revision为题目revision=1，不冒充Git SHA；MySQL8.4.13、Java21.0.13。JUnit仅核对各次实际模块的StaffingShiftRosterBusinessConformanceIT，不把ZIP附带的历史报告混算本次测试。

## 原归档器与精确绑定

使用未修改的archive-gate，逐一核验run/head_sha、artifact原ZIP的GitHub SHA256、受测Git blob、四项候选绑定、生成摘要、原报告/日志附件及实际模块JUnit。两次verified.json和archived.json记录输出；原件分别在：

- datasets/sir/pilots/staffing-shift-roster-change-g13/runtime/staffing-shift-roster-change-v1-teacher-23ed7f759fcd/github-41203150293-attempt-1/
- datasets/sir/pilots/staffing-shift-roster-scratch-g13/runtime/staffing-shift-roster-scratch-v1-teacher-2027a6702825/github-41203161844-attempt-1/

每目录保留gate-run.json、GitHub元数据/两个原ZIP、原报告/日志、完整JUnit附件和MANIFEST。被忽略的ZIP、日志与深层JUnit必须显式纳Git，并核对暂存blob原字节，不以本机存在代替已发布证据。dispatch.json、completed.json、run-*.json是现场对账，不代替上述原始绑定。

## 全库清单缺项与隔离核验

首次在主工作区归档于archive_index.plan的全库清单验证阶段拒绝：10次既有fitness/events运行共300份清单文件未纳Git/未在本机存在，其中含20个ZIP、日志和嵌套历史JUnit。不是这两份候选失败或Windows路径长度造成的300份缺项，也不能删清单、放宽校验来处理。

本轮不改该工作线。在独立Git身份的LF快照里，依据受测Git原清单及各原artifact元数据下载20个原ZIP，仅恢复隔离目录的300份缺项，每份严格匹配已记录SHA256；随后完整校验原全库清单，再由原archive-gate执行本轮两个新归档。只将两个新排班归档与原归档器生成的清单增量回写主工作区，fitness/events目录、旧清单条目和原件不改。isolated-verification.json给出完整路径、恢复计数和核验方式；主工作区的历史缺项仍应交原工作线另行补全，不宣称本轮修好了它们。

隔离导出使用Windows长路径前缀和安全的逐项Git tar读取，不改全局Git配置、Windows设置、采集器或归档器。初次直接tar解包因扩展路径混用斜杠失败后换用规范路径；没有重发模型请求、重判CI或放宽哈希检查。原失败stderr保留以解释现场。

## 当前资格与后续边界

本轮新增四门合格教师 **2份，均排班原题**；报修两份各一轮修复仍失败封存，不再追修。初采加修复6次累计70419 tokens；归档不调用模型。学生v5仍57条/56题原字节不变，新增两份尚未export/student包装，未训练、未扩采12变体。这里只增记本轮新增两份资格；其他工作线已有待补原件/待导出候选，不自动重算或继承，不能把v5覆盖数等同全库资格总数。新增两份须在另获导出安排后进入学生数据，不与已导出的57条混淆。
