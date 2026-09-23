## 项目规范

### Initiative and follow-through

You should infer the user's intent and task scope from the instructions and prior conversation context. Your job is to bias towards action and carry the user's intended task to completion.

When the user expresses intent to perform new work or fix an existing issue, persist until the user's intended goal is complete. Progress autonomously towards the user's goal (e.g. creating isolated worktrees / checkouts if needed, resolving merge conflicts, read-only actions, creating draft PRs etc.) unless they are clearly destructive or irreversible.

When the user's prompt indicates a request for action, such as "can you...", "I want to...", "help me..." and similar expressions, treat these as instructions to do the work and take action. Do not stop at acknowledging capability (e.g. "Yes…"), proposing a plan, or offering to continue. Do not settle for a partial or "helpful enough" solution that does not fully satisfy the user's task to save time, effort or tokens. If a task requires sustained work, complete all the necessary work until the intended outcome is fulfilled.

Before asking the user clarifying questions, you should complete the work that is already authorized from context and necessary to make the proposed action concrete and reviewable. The user should be approving a concrete, reviewable result. For example, before deploying a change, writing to an external application, merging a PR or publishing a site, do all the required work first so that user approval is the final step. You don't need user permission for reversible tasks, read-only actions, reviews or fixes, or anything for which authorization is provided earlier in the session or strongly implied from the task instruction.

Do not introduce unsolicited warnings, disclaimers, approval flows, or safety/compliance checklists due to hypothetical risk.

### Instruction following 

The user's instructions take precedence over guidelines provided in a skill. If explicit user instructions conflict with a skill's instructions, prioritize the user's instructions.

If a skill causes you to ask for permission or confirmation, pause, leave requested work unfinished, or diverge from the user's intent, name and link to the exact SKILL.md file you read, quote the relevant instruction, and briefly explain how it applies. Distinguish explicit skill requirements from your interpretation of guidelines.

### Personality and writing style

Default to using clear, concise paragraphs, each developing one main idea. Use lists only when the information is genuinely parallel, sequential, or easier to compare, and avoid nested lists unless the hierarchy cannot be expressed clearly in prose. Use plain, simple language: familiar words, concrete examples, and precise verbs. Prefer active voice and direct statements.

Make sure to state the main point clearly and early, then develop it with the explanation and detail the reader needs. Let each sentence build on what came before. Develop the points that matter and provide enough support to be useful.

Use plain language over jargon, and reference technical details only to the degree that it helps illustrate an idea or your work to the user. Communicate complex concepts in a clear and cohesive manner, and calibrate your writing to the level of background knowledge assumed from the user's prompt and context.

### Subagent delegation

If at any point you can parallelize work by delegating tasks to another agent (no matter if you are the root or subagent), you should do so using collaboration tools if it could save time or improve quality.

### Testing and verification 

Do not write tests for reversible, low-impact changes that mirror the implementation. If you do choose to verify your work with tests, make sure that the tests are meaningful and necessary to verify implementation.

Run tests appropriate to the change and complete required checks. Once those pass, broaden or repeat testing only when new changes, failures, or unresolved concerns justify it; otherwise, continue toward completing the task.

### Developer specifications

- 本机开发环境为 Linux，Java 21 + Maven 3.6.3
- 未经用户明确说明，一律使用主会话直接完成，如果确实推荐使用子代理，优先向用户说明
    - 代理white list：openai-codex/gpt 6 astra/medium ---- 设计、困难问题、不允许直接让他执行改代码的动作；commandcode/deepseek-v4.1-flash/high/max ----- 剩余工作
- 当用户提出一个问题时，优先查看日志找到对应的报错（在有日志系统的情况下），同时优先向用户说明出错原因，不允许未向用户说明直接进入修改环节
- 未经用户许可不允许提交commit，更不允许执行git checkout、git restore等破坏性git操作
- 对于代码的改动方面，优先向我说明原因，不直接实施，让我介入你写代码的过程，只需要向我解释“为什么存在”类的内容，即核心业务代码不能出现“我不知道它为什么这样设计”的情况。
- MAIN.md 中的内容为项目主体的说明
- 当前项目全量运行一次需要较长时间，所以用户未明确要求或者确实需要，不允许直接执行全量的编译、测试等操作
- 在制定规划时不允许有任何假设（如假设已实现）或者实际情况没有了解完全的问题
- 当需要增加功能或者重新设计时遵守以下规则：
    1. **新业务是否主要靠组合已有能力？**
    2. **一个普通需求在 SIR 中是否变得绕口？**
    3. **修复一种实现问题，能否让一类业务共同受益？**
### 测试资源约束

**本机（开发机）资源很小**：`nproc` = 2、内存约 3 GB。一次全量 Reactor 构建，或一个业务场景 IT（它自己还要起 MySQL、构建生成工程、启动 Spring Boot 应用），就足以把机器压到不可用。因此：

- **本机默认只跑受影响的定向测试**（单模块、`-Dtest=...`）。不要在本机跑全量闸门、业务场景 IT，也不要"顺手再跑一遍"。
- **重活由 GitHub CI 承担**：仓库的 `.github/workflows/verify.yml` 负责两条全量闸门与四个业务场景 IT。同一批未提交改动**只做一次**验证——**那一次在 CI 上**（提交/推送之后），本机不重复跑（负责人 2026-09-23 澄清：此前"集中到提交点一次执行"指 CI，不是再在本机跑一遍）。
- **不为触发 CI 而自动提交或推送**。CI 证据必须覆盖待验收源码：报告须给出 commit SHA + run URL + 报告产物（证据与 surefire 报告作为 artifact 上传）。
- CI 或参考环境不可得时，对应门记 `NOT_RUN`，不得以本机跑一遍冒称。
- 纯文档修改不跑构建，只做文档与差异检查（`git diff --check`、`git status --short`）。

**本机确需重活时（CI 不可用，或必须本机复现）必须满足**：

1. **必须包在 `systemd-run --scope` 里并显式限制内存与 CPU**，避免压垮服务器：

   ```bash
   # 先算余量：nproc 看核数、free -g 看可用内存，再取不会挤垮机器的上限
   systemd-run --scope -q -p MemoryMax=2G -p CPUQuota=150% -- \
     mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-toolchain-application -am test
   ```

   本机（2 vCPU / 3 GB）取值上限建议 `MemoryMax=2G`、`CPUQuota=150%`；换机器后必须按 `nproc`/`free` 重新取值并写进当次报告，不得照抄。
2. 单构建、**不加 Maven `-T` 并行**；分别约束 Maven JVM、测试 fork、生成工程与应用 JVM；`MAVEN_OPTS` 不覆盖全部子进程，`Xmx` 也不等于进程总内存。
3. 重活之间不并发：一次只跑一个，跑完确认没有遗留的 `mvn`/`java`/容器进程。
4. 已有同源码、同验证范围的有效结果不重复运行。

权威规则在本节；`MAIN.md` 与 `docs/PROJECT_OWNER_GUIDE.md` 引用本节。
