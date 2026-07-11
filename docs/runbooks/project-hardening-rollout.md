# Project Hardening — Phased Rollout, Monitoring & Safe Rollback

> **Audience**: release owners / on-call.  
> **Scope**: document the production execution path for project-hardening waves.  
> **This runbook is executable documentation** — do not treat paste-only SQL as auto-remediation.  
> Related: [DEPLOYMENT.md](../../DEPLOYMENT.md), `ops/sql/*`, Flyway `V3`–`V9`.

---

## 0. Preconditions

| Gate | Requirement |
| --- | --- |
| Backup | `mysqldump --single-transaction --routines --triggers` of `wenjin` **before every migrate** |
| Secrets | `SPRING_PROFILES_ACTIVE=prod`, non-default `WENJIN_AUTH_SECRET` |
| DB roles | App account DML-only; migrator only for Flyway jobs |
| Register | Public `/api/register` **must** force student (`role=2`); never re-open client-chosen role |
| Audits | `ops/sql/security-role-audit.sql` + `ops/sql/preflight-integrity.sql` **all bad_count = 0** before V9 |
| Flags (prod defaults) | `WENJIN_FEATURE_FIXED_DIAGNOSTIC=false`, `WENJIN_FEATURE_GRAPH_RECONCILE=false`, `WENJIN_FEATURE_AI_JOB_DISPATCH=false` until phase enables them |

---

## 1. Phased deploy order

Ship **one phase per change window** unless a dry-run proves combined safety. Prefer expand → dual-write/read → contract.

### Phase A — Frontend non-2xx handling first

**Goal**: clients tolerate new authz / validation / conflict responses before backend tightens.

1. Deploy `wenjin-web` build that:
   - Surfaces API `code` / HTTP non-2xx without infinite retry storms
   - Uses centralized session store (no stale course/graph races)
   - Does **not** assume open teacher self-register
2. Smoke: login, student course switch, teacher 403 path, diagnostic error toast.
3. **Do not** enable new backend feature flags yet.

**Rollback**: revert static `dist/` only; no DB change.

---

### Phase B — Wave-1 backend (authz / ownership / safe errors)

**Goal**: server enforces principal-derived identity, teacher ownership, admin role change, structured errors.

1. Deploy backend jar **without** new Flyway versions if already on V2 baseline; or with migrations only through currently approved expand set.
2. Confirm:
   - Register always creates student
   - Teacher routes require TEACHER/ADMIN + course ownership
   - Cross-tenant graph ops → 404/403 (not leak)
3. Keep feature flags **off** in prod:
   ```bash
   # omit or false
   WENJIN_FEATURE_FIXED_DIAGNOSTIC=false
   WENJIN_FEATURE_GRAPH_RECONCILE=false
   WENJIN_FEATURE_AI_JOB_DISPATCH=false
   ```

**Rollback**: roll app jar to previous version; **do not** restore open registration behavior even if rolling code.

---

### Phase C — Expand migrations V3–V8 (data-compatible)

**Order** (Flyway sequential; migrator job only):

| Version | Purpose |
| --- | --- |
| V3 | Fixed diagnostic session freeze + composite question key |
| V4 | `archived_at` soft-archive on course/node/edge |
| V5 | Path concurrency guards (`active_guard`, `current_path_id`) |
| V6 | `question.stem_hash` + unique `(course_id, stem_hash)` |
| V7 | `ai_job` recoverable writes |
| V8 | Companion context summary columns |

Steps:

1. Backup.
2. `flyway info` → expected pending V3…V8 (or subset if partial).
3. `flyway migrate` (or `WENJIN_FLYWAY_ENABLED=true` one-shot migrator job).
4. On non-zero exit: **stop release**, keep old app instances, restore dump if schema half-applied and unclean.
5. Spot-check columns/tables; app still runs with flags off.

**Rollback**: restore pre-migrate dump if migrate fails mid-way. **Never** `DROP` business tables to “undo”. Prefer forward-fix `Vn+1` after restore investigation.

---

### Phase D — Flags-off smoke (post V3–V8)

With **all three** feature flags false:

1. Health / login / student map / teacher list courses
2. Practice loop still works (M1)
3. Companion chat (sync path) if AI keys present
4. No AI job worker claiming, no graph reconcile writes, fixed diagnostic path disabled or guarded

**Rollback**: N/A (flags already off). If smoke fails, roll app only.

---

### Phase E — Frontend `sessionToken` (fixed diagnostic client)

1. Deploy FE that sends `sessionToken` for fixed-paper diagnostic submit (matches V3 freeze).
2. Smoke with flag still **off** (no prod traffic on fixed path), then proceed to Phase F.

**Rollback**: revert FE assets.

---

### Phase F — Enable graph reconcile (feature flag)

```bash
export WENJIN_FEATURE_GRAPH_RECONCILE=true
# rolling restart app instances only
```

1. Teacher graph import/reconcile on a canary course.
2. Monitor archive vs identity-preserving updates (`archived_at`, stable `node_code`).
3. Run `ops/sql/preflight-integrity.sql` after first production reconcile batch.

**Rollback**: set `WENJIN_FEATURE_GRAPH_RECONCILE=false` first (no migrate rollback).

---

### Phase G — Enable fixed diagnostic (feature flag)

```bash
export WENJIN_FEATURE_FIXED_DIAGNOSTIC=true
```

1. Canary students: start paper → hold `sessionToken` → submit exact set → idempotent re-submit.
2. Confirm no duplicate `answer_record` for same fixed session + question.

**Rollback**: flag off first.

---

### Phase H — V9 contract migration (**separate release**)

> **Hard rule**: V9 is **not** bundled with V3–V8 expand in the same first production cut.  
> Deploy only after audits are zero on production (or restored staging clone).

1. Run read-only:
   - `ops/sql/security-role-audit.sql`
   - `ops/sql/preflight-integrity.sql`
2. Any `bad_count > 0` → human ticket; optional `ops/sql/quarantine-orphans.sql` / manual DML; re-audit.
3. Backup again.
4. Migrator: apply **only** `V9__relational_integrity.sql` (Flyway pending version 9).
5. Run `ops/sql/post-migration-verification.sql` — all checks green.
6. Restart app (still DML account). Smoke ownership + practice + diagnostic.

V9 enforces:

- Precheck `SIGNAL SQLSTATE '45000'` on residual orphans / cross-course / bad roles / NULL or dup stem_hash / NULL teacher_id
- Named physical FKs (Task 7 matrix)
- `course.teacher_id NOT NULL`
- `question.stem_hash NOT NULL` (re-assert)
- `CHECK (sys_user.role IN (1,2,3))`

**Rollback**:

- Prefer **restore dump taken immediately before V9** if migrate fails or verification red.
- Do **not** drop FK tables or data tables to reverse contract.
- Do **not** roll product behavior back to open teacher register.
- App code can roll back independently only if it does not depend on NOT NULL/FK violations for writes.

---

### Phase I — AI jobs (feature flag last)

```bash
export WENJIN_FEATURE_AI_JOB_DISPATCH=true
```

1. Canary: one generate/annotate job; confirm `ai_job` row lifecycle PENDING→RUNNING→SUCCEEDED/FAILED.
2. Worker poll interval / payload dir writable.
3. Cancel + recovery smoke.

**Rollback**: `WENJIN_FEATURE_AI_JOB_DISPATCH=false` (jobs remain in table; no drop).

---

## 2. Monitoring (per phase)

| Signal | Where | Alert if |
| --- | --- | --- |
| HTTP 401/403/409/422 rate | nginx / app logs | Spike vs baseline after authz wave |
| Flyway job exit code | CI/CD | ≠ 0 |
| `flyway_schema_history.success` | SQL | any 0 for latest version |
| Audit `bad_count` | `security-role-audit.sql` | > 0 before V9 / after graph reconcile |
| Multi-active paths | audit query | > 0 (V5 should prevent new) |
| Dup stem_hash | audit / V6 | > 0 |
| AI job stuck RUNNING | `ai_job` | age > SLA |
| Companion SSE errors | nginx buffering | stream aborts |
| Error budget | synthetic login + teacher course list | fail 2 consecutive |

Suggested post-deploy SQL pulse (read-only):

```sql
SELECT version, success, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
SELECT status, COUNT(*) FROM ai_job GROUP BY status;
SELECT role, COUNT(*) FROM sys_user GROUP BY role;
```

---

## 3. Rollback rules (summary)

1. **Feature flags first** — turn off reconcile / fixed diagnostic / AI dispatch before touching DB.
2. **No DROP of data tables** — never “fix” with `schema.sql` or `DROP TABLE` in prod.
3. **No rollback to open register** — student-only public register is a security floor, not a feature flag.
4. **Migrate failure** → stop app rollout; restore pre-migrate dump if schema unclean; fix forward with new version after root cause.
5. **V9 separate** — contract FK/NOT NULL only after expand + audit zero; reverse via backup restore, not by deleting constraints casually in place without a plan.
6. **Frontend-only revert** is always safe relative to DB; backend revert must remain compatible with already-applied Flyway versions (expand migrations are sticky).

---

## 4. Checklist (copy into change ticket)

- [ ] Backup taken and restore tested on staging
- [ ] Phase A FE non-2xx deployed
- [ ] Phase B wave-1 backend deployed; register=student verified
- [ ] Phase C V3–V8 migrated; flags still off
- [ ] Phase D smoke green
- [ ] Phase E sessionToken FE deployed
- [ ] Phase F graph reconcile enabled + preflight clean
- [ ] Phase G fixed diagnostic enabled + idempotent submit OK
- [ ] Phase H audits zero → V9 → post-migration verification zero
- [ ] Phase I AI job dispatch enabled + canary job OK
- [ ] Monitoring dashboards / log queries bookmarked
- [ ] Rollback owner named for the window

---

## 5. File map

| Artifact | Role |
| --- | --- |
| `ops/sql/security-role-audit.sql` | Pre-V9 / ongoing role & integrity audit (read-only) |
| `ops/sql/preflight-integrity.sql` | Graph archive / orphan preflight |
| `ops/sql/quarantine-orphans.sql` | Soft quarantine helpers (not auto-run) |
| `ops/sql/post-migration-verification.sql` | Post-V9 FK / zero-orphan / flyway checks |
| `db/migration/V3`–`V8` | Expand |
| `db/migration/V9__relational_integrity.sql` | Contract (separate release) |
| `DEPLOYMENT.md` | Prod topology, migrator/app split, bootstrap admin |
