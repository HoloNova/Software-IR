# 已完成工作单：Q1A Generator 输出契约基线

- 状态：`DONE`
- 所属阶段：路线图阶段 1 / Generator 系统测试
- 本工作单性质：测试与证据优先；只允许修复被新增测试直接证明的 Generator 局部缺陷
- 下一张候选工作单：Q1B Renderer 与 Workflow 行为测试

## 这次只做什么

给现有 `sir-generator-spring-boot` 建立第一层系统行为基线：对仓库已有的 canonical SIR fixtures 生成完整文件集合，验证关键文件存在、路径唯一、输出顺序稳定，并为后续逐个 Renderer 的语义测试提供可复用测试支持。

这张工作单不要求一次完成整个 Generator 资格阶段。

## 为什么先做它

Q1A 开始前 Generator 只有 4 项直接测试，主要证明少量架构回归和 import 排序。仓库已经有：

- `valid/campus-market.sir`
- `valid/unit-output.sir`
- `valid/compound-find.sir`
- `GeneratorTestSupport`
- `GeneratorArchitectureRegressionTest`

但还没有一套清晰、可维护的完整输出契约。后续 Renderer 行为、确定性和离线编译都应该建立在这层基线上。

## 开工前必须读取

1. `AGENTS.md`
2. `docs/PROJECT_STATUS.md`
3. `docs/qualification/CURRENT_QUALIFICATION.md`
4. `docs/qualification/TEST_COVERAGE_INVENTORY.md` 的 Generator 部分
5. `docs/KCG-Code_系统架构与实现指南.md` 的 4.5、6、9、10 节
6. `docs/superpowers/specs/2026-07-16-sir-v0.1-lowering-design.md` 的 4 至 8 节
7. `sir-generator-spring-boot/src/main/java/`
8. `sir-generator-spring-boot/src/test/java/` 和 `src/test/resources/valid/`

## 允许修改的范围

- `sir-generator-spring-boot/src/test/**`
- 被新增失败测试直接证明有问题的 `sir-generator-spring-boot/src/main/**`
- 与实际测试结果同步的：
  - `docs/qualification/TEST_COVERAGE_INVENTORY.md`
  - `docs/qualification/CURRENT_QUALIFICATION.md`
  - 本工作单的状态与交接记录

如需修改 Lowering、Semantic、Parser、Application、CLI、POM 依赖或公共架构契约，先停止并报告，不得在本工作单里顺手扩大范围。

## 明确不做

- 不做生成工程的真实 Maven 离线编译；那属于 Q1D。
- 不一次补完所有 Renderer 的字段级语义；那属于 Q1B。
- 不做跨 Locale、跨工作目录和大规模重复运行矩阵；那属于 Q1C。
- 不修改 Grammar、Normalized model、Lowered IR 契约或 Target Profile。
- 不新增第三方测试库，除非现有 JUnit 明确无法完成并得到单独授权。
- 不进入 Project Graph、Change、conformance、事务或 CLI 工作。

## 实施要求

1. 先运行现有 Generator 测试，记录基线。
2. 先写测试，再根据失败结果决定是否需要修改生产代码。
3. 为 canonical fixtures 建立可复用的生成结果访问方式，避免每个测试重复手写 Parser/Semantic/Lowering 管线。
4. 对 `campus-market.sir` 至少验证：
   - GenerationResult 为 Success；
   - 完整相对路径集合与预期一致；
   - 相对路径无重复且已经确定性排序；
   - `pom.xml`、Application、Entity、Mapper、DTO、Exception、Service、Controller 等主要产物类型确实存在；
   - 每个 `GeneratedFile` 的 path、content、artifactId 和 symbolId 基本不变量成立。
5. 对 `unit-output.sir` 和 `compound-find.sir` 建立最小输出外形回归，先冻结文件集合和关键产物，不在本工作单深入断言所有方法体。
6. 相同 lowered model 连续生成至少两次，确认文件路径、顺序和内容完全一致。更完整的环境确定性留给 Q1C。
7. 测试名称要表达业务契约，失败信息要能指出缺失或多出的文件。

## 定向验证

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-generator-spring-boot -am test
```

## 完成门

- canonical `campus-market` 的完整文件集合拥有直接测试。
- `unit-output` 和 `compound-find` 的最小输出外形拥有直接测试。
- 主要生成产物类型不再只靠人工查看证明存在。
- 重复生成的路径、顺序和内容一致。
- Generator 原有测试不减少、不被禁用；新增 skip 或 exclude 为 0。
- 定向测试通过。
- 全量离线 Reactor 通过：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short
```

- `CURRENT_QUALIFICATION.md` 的测试数与当次 Surefire XML 一致。
- 未把 Q1B、Q1C、Q1D 的未完成内容描述成已经完成。

## 完成报告格式

执行者最后必须提供：

1. 新增/修改文件列表；
2. 冻结了哪些 Generator 输出契约；
3. 是否修改生产代码，若修改，对应哪一个先失败的测试；
4. 定向与全量命令的 run/fail/error/skip；
5. POM exclude 和未运行内容；
6. 仍留给 Q1B/Q1C/Q1D 的内容；
7. 是否建议把状态改为 `AWAITING_ACCEPTANCE`。

## 交接记录

2026-08-11：Q1A 已完成实现并等待项目负责人验收。

- 新增 `GeneratorOutputContractTest` 5 项测试；Generator 直接测试由 4 增至 9，0 failure、0 error、0 skip。
- 冻结 `campus-market` 的 11 文件有序契约、`unit-output` 的 4 文件外形和 `compound-find` 的 8 文件外形。
- 覆盖路径唯一、LF 结尾、project/declaration ownership metadata、LoweredNodeId 唯一，以及同一 Lowered model 重复生成完全相等。
- 首次契约运行出现 1 个预期失败：测试把 Lowered IR 已决定的 `InvalidGoodsPriceException.java` 误写成 `InvalidGoodsPrice.java`；核对 Lowering 后修正测试预期，未修改生产代码。
- 复用入口集中到 `GeneratorTestSupport.generateSuccess(...)` 和 `contentEndingWith(...)`；原有回归继续通过。
- 定向验证：Generator 9/0/0/0，BUILD SUCCESS。
- 全量验证：350 run、0 failure、0 error、10 skip，BUILD SUCCESS。
- 生产代码、POM、依赖、skip 和 exclude 均未改变。
- Q1B Renderer 语义、Q1C 跨环境确定性、Q1D 生成工程离线编译仍为未完成。

2026-08-11：项目负责人已确认本轮完成。Q1A 转入已完成归档，当前工作单推进到 Q1B1。
