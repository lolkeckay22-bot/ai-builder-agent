---
name: multi-agent
description: Delegate complex work to independent planner, specialist, and reviewer sub-agents, combine their reports, and verify the final artifact.
---
# Multi-Agent Orchestration

Use sub-agents for multi-step builds, risky file transformations, or tasks with separable specialties. Do not spawn them for trivial requests.

1. Give each sub-agent a narrow role, the user request, relevant verified inputs, constraints, and an explicit output contract.
2. Run independent roles concurrently when they do not depend on each other.
3. Use a planner for requirements and sequencing, a domain specialist for implementation, and a reviewer for failure modes and acceptance checks.
4. Keep the coordinator responsible for decisions. Sub-agent reports are advice, not proof.
5. Merge non-conflicting findings, resolve disagreements against the user's request and actual tool results, then perform a final verification.
6. Never let one sub-agent claim another sub-agent's work was executed without checking the resulting files or build output.
