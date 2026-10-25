这是同一道冻结题目的反馈修复，不是新任务。保持原始接口、令牌期限、鉴权、原子刷新和已有能力不变；只返回修复后的完整 sir 0.3 文档。

以下澄清来自现有语言参考和题目原有 DDL，不改变判题标准：

1. 实体声明顺序固定：identity，所有 field，联合 unique（若有），credential（若有），scope（若有）。credential 必须放在所有字段之后。
2. capability 必须声明 output 类型；return 后必须有返回表达式，不存在裸 return;。退出登录可以返回已更新的刷新令牌的投影视图，不能省略输出来逃避语言规则。
3. 带 when 的 update 必须带 else 错误名；普通 validate 后无条件写入不代替原子竞争守卫。
4. 普通 view 的字段须有对应的实体字段，不能仅靠声明响应字段就从关联实体隐式取值。该题的 TokenPair 持久化列已经由下附 DDL 固定，不能增加 owner、access 引用和 refresh 引用替代那四个令牌/期限列。
5. 新创建刷新令牌的 used 必须明确初始化为 false，或由实体 default false 提供；每条新行与修改都按原有事务规则持久化。
6. 允许使用内置 DualTokenLogin，也允许手写展开。不要复制与 use 展开重名的实体、输入、视图或能力。
7. 保留编译器原始诊断。部分旧诊断文案里写着 v0.1，不代表可以把当前根文档降为旧版本；当前唯一目标仍为 sir 0.3。

依据：docs/SIR_LANGUAGE_REFERENCE.md 的实体、credential、能力与守卫更新章节；sir-parser/src/main/antlr4/io/kcg/sir/internal/Sir.g4 的 entityDecl、capabilityDecl、guardClause 与 returnStep。
