# Project Hardening Baseline

Frozen measurable starting state for Wave 0 of the Wenjin project-hardening plan.

| Field | Value |
| --- | --- |
| **Date** | 2026-07-10 |
| **Baseline commit (HEAD)** | `04b5a39533c632e3da0b9b8540719383e4f756ea` |
| **Branch** | `codex/project-hardening` |
| **Worktree path** | `D:\Fordo-hardening` |

---

## Backend (`wenjin-server`)

Command (from worktree):

```powershell
Set-Location D:\Fordo-hardening\wenjin-server
.\mvnw.cmd test -q
```

| Metric | Result |
| --- | --- |
| Exit code | 0 |
| Tests run | **668** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **1** |
| Surefire elapsed (sum of suite times) | **36.429 s** |
| Test suites (Surefire XML reports) | 87 |

Notes:

- Aggregated from `target/surefire-reports/TEST-*.xml` after a clean successful run.
- The single skip is `com.wenjin.eval.AdaptiveVsFixedEvalTest` (1 skipped).
- No test failures or errors; baseline green for backend unit/integration tests.

---

## Frontend unit tests (`wenjin-web`)

Commands:

```powershell
Set-Location D:\Fordo-hardening\wenjin-web
npm ci
node --test
```

| Metric | Result |
| --- | --- |
| Exit code | 0 |
| Tests | **18** |
| Pass | **18** |
| Fail | **0** |
| Cancelled | 0 |
| Skipped | 0 |
| Todo | 0 |
| Duration | **960.9055 ms** (~0.96 s) |

Coverage (files under `test/`):

- `adaptiveProgress.test.mjs`
- `companion-messages.test.mjs`
- `companion-persistence.test.mjs`
- `companion-sse.test.mjs`
- `graphLayout.test.mjs`
- `m4-e2e-script-contract.test.mjs`

Notes:

- Worktree had no `node_modules`; `npm ci` was required before `node --test` (missing `axios` otherwise).
- Observed pass count is **18**, not 21. Baseline records the measured value on this tree at the baseline commit.

---

## Frontend production build (`wenjin-web`)

Command:

```powershell
npm run build
```

| Metric | Result |
| --- | --- |
| Tooling | Vite v5.4.21 |
| Exit code | 0 |
| Modules transformed | 699 |
| Build wall time | **13.87 s** |
| Status | **Success** |

### Largest JS chunk

| Asset | Raw | Gzip |
| --- | --- | --- |
| `dist/assets/index-Bb6yjXMn.js` | **1,034.91 kB** | **343.41 kB** |
| Next largest JS: `dist/assets/index-EZPO9vZl.js` | 173.05 kB | 65.42 kB |

Build emitted a Rollup/Vite warning: some chunks are larger than 500 kB after minification (driven by the main vendor/app chunk above).

---

## Schema / migration policy (baseline constraints)

1. **`wenjin-server/src/main/resources/schema.sql` is a local-only destructive script.**  
   It is designed for repeatable local bootstrap (`DROP IF EXISTS` then `CREATE`). It must not be used as the production schema evolution path.

2. **Production must use incremental migrations only.**  
   Apply changes via scripts under `wenjin-server/src/main/resources/migration/` (and any later approved migration process). Do not re-run full `schema.sql` against shared or production databases.

---

## Baseline summary

| Surface | Result |
| --- | --- |
| Backend tests | 668 run, 0 fail, 0 error, 1 skip (~36.4 s Surefire) |
| Frontend `node --test` | 18 pass / 18 total (~0.96 s) |
| Frontend `npm run build` | Success; max JS chunk **1034.91 kB** (gzip **343.41 kB**) |

This document freezes the starting metrics for subsequent hardening work (security, data integrity, concurrency, frontend races, CI).
