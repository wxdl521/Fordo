# Project Hardening Review Remediation Design

**Date:** 2026-07-12

**Status:** Approved by user instruction to apply the recommended full-remediation approach without further clarification pauses.

## 1. Objective

Resolve every P0/P1 issue confirmed by the independent review of
`04b5a39533c632e3da0b9b8540719383e4f756ea..cd816570fa296387374453011333cb769e17e9dc`
without weakening the original hardening requirements. The result must be safe to deploy in staged releases, must preserve historical business data, and must make the automated gates exercise the concurrency and migration paths they claim to protect.

The reviewed V6 and V9 migrations have not run in any environment whose data must be preserved. They may therefore be corrected in place before release. Once the corrected migrations are applied anywhere persistent, subsequent changes must use forward migrations.

## 2. Non-negotiable invariants

1. An HTTP request can never inherit `CurrentUser` from another request, including Spring MVC asynchronous dispatch.
2. A deployment without an explicit secure authentication secret fails to start unless an explicit `dev` or `test` profile is active.
3. V3-V8 expand migrations and the V9 contract migration are independently deployable artifacts or explicitly targeted Flyway runs.
4. No migration silently deletes or rewrites ambiguous business data.
5. `answer_record.session_id` remains a legacy polymorphic logical reference and is not bound to one physical parent table. Fixed diagnostic continues to use its explicit column.
6. A diagnostic or practice submission either commits its answers, mastery, session status, and path effects together or commits none of them.
7. Every graph mutation for one course is serialized on the same database lock.
8. Disabling AI jobs stops both dispatch and claiming. A disabled or demoted actor cannot execute a queued write.
9. A stale AI worker cannot update a newer attempt. An operation with an uncertain side-effect outcome is not automatically replayed.
10. Frontend asynchronous responses are committed only when they still belong to the current token, course, conversation, or job attempt.
11. CI must execute real `*IT` tests, enforce the documented coverage thresholds, and fail on committed whitespace or conflict markers.

## 3. Remediation architecture

### 3.1 Authentication context and client session safety

`AuthContextInterceptor` will implement `AsyncHandlerInterceptor`. It will clear `CurrentUser` before processing every dispatch, populate it only after token verification and database principal loading, clear it in `afterConcurrentHandlingStarted`, and clear it again in `afterCompletion`. This makes cleanup correct for synchronous requests, the original servlet thread of an async request, and the completion dispatch.

`AuthSecretGuard` will treat the absence of active profiles as a secure environment. Relaxed secret checks require at least one active profile and every active profile must be `dev` or `test`. Tests will cover no profile, mixed profiles, short secrets, default secrets, and explicit development mode.

Axios requests will record the token attached to each request. A 401 will clear session state only if that token still equals the current token. Native companion SSE will call the same token-scoped auth-failure function. A delayed response from an old login can therefore not log out a newer session.

### 3.2 Corrected migration chain

`V5__concurrency_guards.sql` will run duplicate-path-item prechecks before any durable schema change. Duplicate `(path_id, step_order)` or `(path_id, node_id)` data will raise SQLSTATE 45000 with no deletion. Multi-active learning paths may still be resolved by retaining the newest active path and marking older paths inactive because all paths and items remain available for audit. The migration will never orphan `practice_session.path_item_id`.

`V6__question_stem_hash.sql` will first calculate legacy hashes directly from `TRIM(stem)` and abort before `ALTER TABLE` if duplicates exist. It will then add a nullable `stem_hash`, backfill it, and add the course-scoped unique key while keeping the column nullable. New code continues dual-writing the hash; old binaries remain write-compatible during the expand window. V9 performs the final null audit and `NOT NULL` contract.

`V9__relational_integrity.sql` will not add an FK from polymorphic `answer_record.session_id` to `practice_session`. The schema snapshot will document this column as logical compatibility data. V9 will retain the explicit fixed-diagnostic FK and all valid non-polymorphic constraints.

The rollout runbook and deployment guide will set `SPRING_FLYWAY_TARGET=8` for expand and `SPRING_FLYWAY_TARGET=9` for contract. Verification commands will assert the current and pending versions before continuing.

### 3.3 Transaction and graph consistency

Mastery updates will use `PROPAGATION_REQUIRED`. When called inside a diagnostic or practice transaction they participate in that transaction and do not perform an inner deadlock retry after the ambient transaction is marked rollback-only. Standalone mastery calls may retain bounded retry around a newly created outer transaction.

`LearningPathWriter.persistPath` will also use `PROPAGATION_REQUIRED`, so practice submission cannot commit a new current path and then roll back the answer/session update.

A single course-row locking primitive will be introduced and used by graph import/reconcile, node create/update/archive, edge create/accept/reject, and course archive/restore. Ownership is checked before expensive work; the course row is locked immediately before reading the graph snapshot used for reconciliation or cycle detection. Cycle validation and insert occur while holding the lock.

Archiving a course sets `status=0` and archives its graph in one transaction. Normal management guards exclude archived courses. A separate explicit restore service path accepts owner/admin access to an archived course, restores it as unpublished, and restores graph rows only after uniqueness validation. Graph import never implicitly restores a course.

Fixed-paper composition will select questions only when their main node belongs to the same course and is not archived. Submission will load and lock the session first; a completed session can replay its saved result even when new fixed-diagnostic writes are disabled. Incomplete submissions still return 503 before any mutation when the flag is off.

### 3.4 AI job execution model

The existing database-backed queue remains; no external broker is introduced.

The job row will gain durable cancellation and lease fields: `cancel_requested_at`, `lease_token`, `lease_until`, `heartbeat_at`, and `effect_started_at`. Claiming assigns a random lease token and bounded lease. Progress, heartbeat, success, failure, and cancellation updates use `WHERE id=? AND status=RUNNING AND lease_token=?` so an expired worker cannot overwrite a later attempt.

The worker checks the existing AI job feature flag before stale recovery and claim. Before execution it reloads the owner through `PrincipalLoader`; missing, disabled, or no-longer-authorized principals fail the job without running domain code. ADMIN remains ADMIN.

Cancellation is stored in the job row and observed by every worker instance. A lightweight heartbeat keeps a legitimate long operation leased. Immediately before entering a command that can write domain data, the worker atomically records `effect_started_at`. Stale recovery may requeue only jobs that never entered the effect phase. An expired job with `effect_started_at` is marked failed with `UNKNOWN_OUTCOME` and is never automatically replayed. This favors data safety over blind at-least-once execution; the operator can reconcile the domain state before explicitly starting a new action.

Job result transitions and cleanup are lease-qualified. Payload retention cleanup may remove a content-addressed file only when no non-terminal or retained job references its hash.

### 3.5 Frontend request generations and job UX

`useJob` will use one recursive timeout after the previous poll completes, not `setInterval`. A poll generation invalidates all in-flight reads on start, course switch, cancel, clear, and unmount. Terminal state can therefore not regress to RUNNING.

Idempotency keys will be random per user action. The same key is retained only while that action is in flight so a double-click or transport retry reuses the same job. Once terminal, a repeated user action receives a new key.

Teacher graph, question pool, map review, conversation list/detail, and other course-scoped loaders will use abort/revision gates. They capture the course/conversation at dispatch and atomically commit data, loading, and error state only when current. Teacher graph jobs receive the reactive course source and success callbacks use the course code captured at dispatch.

Companion conversation switching will keep a separate loading target and commit `conversationId` plus messages together after a current successful response. Sending is disabled during the transition. Batch generation stores the active job ID and sends server cancellation before reporting cancellation locally.

### 3.6 Quality gates

Maven Failsafe will bind `**/*IT.java` to `integration-test` and `verify`, and CI will upload both Surefire and Failsafe reports. CI must fail if no integration-test report is produced. Testcontainers continues to use fake AI implementations.

The completed Node-to-Vitest migration means `test:legacy` will be removed rather than kept as a permanently failing script. Documentation and CI will reference only supported suites.

JaCoCo gates will enforce line coverage at 70% and branch coverage at 60% for security/controller/service. Vitest gates will enforce line coverage at 80% and branch coverage at 70% for store/composables/api/utils. Missing coverage will be supplied with behavior tests; production files will not be excluded to manufacture a pass.

CI whitespace checks will compare the actual commit range. E2E fixtures will authenticate deterministic student and teacher sessions through route interception, then exercise map, companion, teacher graph, mobile behavior, and serious/critical Axe findings. Existing lint warnings in touched files will be removed and lint will run with zero warnings.

## 4. Error handling and rollout behavior

- Authentication-context or signing-secret configuration failures stop startup or reject the request; they never degrade to an anonymous-but-stale context.
- Migration precheck failures stop before the first business-table DDL in that migration and include an actionable SQLSTATE 45000 message.
- Lock contention and optimistic concurrency return stable conflict responses; they do not retry a partial inner transaction.
- AI lease loss prevents the old worker from publishing progress or terminal status.
- `UNKNOWN_OUTCOME` is visible to operators and clients as a stable error code and requires reconciliation before a new action.
- Turning a feature flag off is a write kill switch. Reads and completed-result replay remain available.

## 5. Test strategy

Every behavioral change follows red-green-refactor:

1. Unit tests reproduce stale ThreadLocal, no-profile secret, stale-token 401, overlapping polling, deterministic-key reuse, and completed diagnostic replay.
2. Service tests verify transaction propagation, archived-course behavior, feature flags, true principal reconstruction, lease-qualified CAS, and no automatic replay after the effect boundary.
3. MySQL Testcontainers tests verify V5/V6 dirty-data failure, V3-V8 target deployment, V9 contract deployment, polymorphic adaptive answers, graph serialization, cycle prevention, mastery rollback, and path rollback.
4. Frontend deferred-promise tests verify take-latest behavior for every reviewed loader and conversation switch.
5. Authenticated Playwright fixtures verify the real post-login screens and Axe serious/critical threshold.
6. Final verification runs backend clean verify, frontend install/check/coverage/E2E, migration tests, strict lint, bundle budget, and commit-range whitespace checks from a clean worktree.

## 6. Delivery sequence

1. Security context, signing secret, and token-scoped 401.
2. Corrected migrations and staged Flyway documentation.
3. Transaction propagation, graph locking, archive/restore, and fixed diagnostic behavior.
4. AI job durable cancellation, actor reload, leases, effect boundary, and payload retention.
5. Frontend job/request generations and conversation atomicity.
6. Failsafe, coverage, strict lint, authenticated E2E, and final full verification.

Each sequence is independently tested and committed. No production migration checksum is changed after it has been applied to a persistent environment.
