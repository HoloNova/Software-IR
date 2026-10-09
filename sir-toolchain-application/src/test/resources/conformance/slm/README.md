# Q23限定真实评测试跑：需求与受控错误样本

这不是SLM输出数据集，不用于报告模型成功率。既有原始需求/独立测试包复用Q11：只返回至少有一条ACTIVE报名的课程，根去重、按code排序并分页；根过滤不删掉返回投影中的其他状态报名；Student/Enrollment嵌套投影；无报名/只有CANCELLED报名的课程不匹配；非法分页400；读取不得改course/student/enrollment三表。

正样本为既有/valid/course-enrollment.sir。course-enrollment-missing-status.sir只删除`and status == EnrollmentStatus.ACTIVE`，其余原字节不变，IT在运行前断言此差异。它是合法的SIR，但不满足上述需求。不能从该错误SIR重新推导新的期望值来让它通过。

DDL/seed复用既有/conformance/mysql/course-enrollment-{ddl,seed}.sql。业务oracle固定：第一页total4/codes CS101,CS102,MAT101,ZOO101；第二页size2为MAT101,ZOO101。ART101、ENG101只有取消报名，PHY101无报名。错误样本实际漏入ART101、ENG101，必须BUSINESS FAILED，而STATIC/BUILD/START必须分别实测PASSED。三表JDBC原行列表前后一致。

报告分别标sampleId、原文SHA、Q23生成digest、sourceTree SHA/base SHA（dirty/非同SHA CI）、DDL/seed SHA、HTTP响应与独立数据库指纹、build/start/business状态、cleanup及资源。预期错误样本的业务FAILED是正确的评测结果，不是编译器缺陷，也不标成BUSINESS PASSED或数据集正确标签。

只在明确启用`kcg.slm-evaluation.enabled=true`且参考环境完整/显式child JVM限额时试跑；不是新的CLI运行命令、通用模型评测平台或自动CI门。手册/SLM样本未取得，其余评测设计仍待另审。
