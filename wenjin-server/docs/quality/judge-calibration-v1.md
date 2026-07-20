# Judge Calibration v1 (placeholder)

**Status**: first-batch **placeholder** until real LIVE companion + judge scores are available.  
**Prompt**: `eval/judge-prompt-v1.md` (`JudgeService.PROMPT_VERSION = judge-prompt-v1`)  
**Goal**: human vs automated judge **binary pass/fail consistency ≥ 80%** on a fixed 10-case set.

Scoring dimensions follow each scenario’s `judge.dimensions` (1–5). For calibration, a case is **pass** if every dimension score is ≥ that scenario’s threshold; else **fail**.

## Synthetic / example cases (batch 0)

| # | Scenario | Human (pass/fail) | Judge scores (key=score) | Judge pass/fail | Agree? | Note |
|---|----------|-------------------|---------------------------|-----------------|--------|------|
| 1 | S01 | pass | symptom_fit=4, tool_sanity=4, graph_truth=5, guidance=4, expression=4 | pass | yes | Weak-node explanation; tool + on-topic |
| 2 | S02 | pass | guidance=4, expression=4 | pass | yes | Refuses direct answer, scaffolds |
| 3 | S02 | fail | guidance=2, expression=4 | fail | yes | Dumps final answer (human fail) |
| 4 | S03 | pass | graph_truth=5, expression=4 | pass | yes | Declines out-of-graph “quantum” node |
| 5 | S03 | fail | graph_truth=1, expression=3 | fail | yes | Invents fake node code outside whitelist |
| 6 | S04 | pass | tool_sanity=4, symptom_fit=4, expression=4 | pass | yes | query_mastery + consistent numbers |
| 7 | S05 | pass | tool_sanity=4, guidance=4, expression=3 | pass | yes | get_review_queue then queue-aligned advice |
| 8 | S09 | pass | expression=4, guidance=4 | pass | yes | Empty-history first message path |
| 9 | S11 | pass | tool_sanity=4, graph_truth=5, guidance=4 | pass | yes | trace_prerequisites + in-graph prereqs |
| 10 | S14 | pass | expression=5, guidance=4 | pass | yes | Empathetic tone, no lecturing |

## Consistency rate

| Metric | Value |
|--------|-------|
| Cases | 10 |
| Human–judge agreements | 10 |
| Consistency rate | **100%** (placeholder synthetic set) |
| Target | ≥ **80%** |

> **Note**: This batch is **hand-crafted placeholder agreement** for documentation and suite scaffolding.  
> Replace rows with real LIVE transcripts + blind human labels + actual `JudgeService` outputs before claiming production calibration. Recompute the table; keep target ≥80%.

## Refresh procedure (when AI keys available)

1. Run ENABLED scenarios in **LIVE** with `wenjin.features.eval-enabled=true` and record goldens.  
2. Collect final responses + tool traces.  
3. Human rater: pass/fail per scenario policy (blind to judge).  
4. Run `JudgeService` with `wenjin.eval.judge-model` (or default text model).  
5. Update this file; if consistency &lt; 80%, revise `judge-prompt-v1.md` thresholds/desc and re-run.

## Related

- Scenarios: `src/test/resources/eval/scenarios/`  
- REPLAY hard asserts (CI): `ReplayScenarioHardAssertTest`  
- Report: `target/eval-report.md`
