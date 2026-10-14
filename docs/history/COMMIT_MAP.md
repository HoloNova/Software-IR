# 提交记录对照表

2026-10-14 整理提交记录（81 个整理为 22 个，每个新提交的内容取自旧序列中的一个真实状态）。旧提交完整保存在远端分支 `backup/pre-squash-0350e8f`，旧 CI 运行与文档中引用的旧 SHA 都可以在那里找到。

| 新提交 | 日期 | 包含的旧提交 | 说明 |
| --- | --- | --- | --- |
| `3a52cbd` | 2026-07-13 | `054cd27` | Initial commit |
| `c489418` | 2026-08-11 | `b7406c3`, `55fc610`, `23fc0c0`, `b33df32`, `7ddc6bd`, `bd4c420`, `93cbf14`, `5bba6ea` | Recover the project baseline and lock the generator contracts (Q1B1-Q1D) |
| `aa6cdb9` | 2026-09-22 | `b8efb83`, `902a7a5`, `24eec6d`, `92c505c` | Qualify the existing chain (G0, Q1-Q8) |
| `05f9ea3` | 2026-09-23 | `6fad608` | feat: G1 关联过滤/批量读取（Q11）、变更闭环（Q13）与两张前置修复单（Q14/Q15）+ CI 验证 |
| `1bb4056` | 2026-09-26 | `b2f5436`, `b40b7ab` | Persistent identities and read-only rename plans (Q16) |
| `551f3e6` | 2026-09-28 | `ab2ce09` | feat: Q17 单声明改名的原子文件事务（applyRename + V4 日志/恢复 + 故障矩阵） |
| `e01e1e0` | 2026-10-04 | `638eaa7` | feat: Q18 多文件首次生成、源快照与 V2 Graph |
| `6a64ead` | 2026-10-05 | `f3d9ef7` | feat: Q19 多源基线保存与独立重开核验 |
| `3b84841` | 2026-10-08 | `e404208` | phase(Q20):多源基线可生成并复核单能力工作流的只读更新计划 |
| `3dbc267` | 2026-10-09 | `f8a2f92` | fe(Q21): 多源更新、保存发布、连续更新与显式恢复已验证 |
| `a006275` | 2026-10-09 | `dac0463` | feat(Q23): SIR 批量校验入口 |
| `c4270b5` | 2026-10-13 | `3555ec5`, `831df82`, `7fea588`, `4b54028`, `74c73d3`, `a642874`, `aa8a067`, `35ccac4`, `5c312f1`, `bb9d54e`, `3fba8fa`, `50f6559`, `3e61813`, `2fdd43a` | Layered architecture: typed software schema and command editing (design, R1, R2) |
| `d2b5593` | 2026-10-13 | `2260a04`, `58aa90b`, `191bbd9` | Unified compilation and read-only update preview (R3) |
| `c45082a` | 2026-10-13 | `abcbf68`, `21100be`, `7dc6bbb`, `0f58c62`, `cb48a68`, `b26706e`, `26e95f1`, `630ed2c` | Version change against the stored project: plan, journaled apply and recovery (R4) |
| `6ad8799` | 2026-10-13 | `121e7f5`, `1e14100`, `efb1b65`, `e199aa6`, `21ee1cf`, `98d8ada` | Independent verification: build, start and business levels behind ports (R5) |
| `b5f3842` | 2026-10-14 | `0d1e46b`, `57fd548`, `a01372c`, `7d803d2`, `5c6f3ac`, `8d0da8e`, `bd1ad70`, `be2feb3` | R5 follow-up: lock fixes, state-directory rollover, full-path verification |
| `14a6786` | 2026-10-14 | `e0e32f1`, `9d32f17`, `9902970`, `82cbeeb`, `718644b`, `a725516`, `04b1724`, `e2b404a`, `d7efcd4`, `07f29f6` | Use Git as the version history (ADR-027, G1-G4) |
| `a5a4587` | 2026-10-14 | `100097d`, `3609d45`, `b89714d` | Delete unused code, named exit codes, and the update preview that version-plan replaced |
| `47fc210` | 2026-10-14 | `d4104f9`, `25bc1bd`, `1b5afd2`, `e39ab84` | One document form: a software in one file is a one-file source set |
| `cf07569` | 2026-10-14 | `24efabd` | Remove the first graph, snapshot and lowering-IR versions and the dead baseline bundle |
| `8b53c82` | 2026-10-14 | `e56134c` | Tidy what the unification left behind: neutral names and helpers nothing calls |
| `本提交` | 2026-10-14 | `0350e8f` | Consolidate the documentation: finished and retired material moves to docs/history, current docs are |
