# Hardening Review Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate every P0/P1 defect confirmed by the post-hardening review while preserving staged rollout compatibility and historical data.

**Architecture:** Apply six independently verifiable waves: authentication, migrations, transactional consistency, AI jobs, frontend request generations, and quality gates. Database mutations use explicit locks or lease-qualified CAS; frontend mutations use request generations; every behavior change is developed red-green-refactor and committed separately.

**Tech Stack:** Java 21, Spring Boot 3, Spring MVC, MyBatis-Plus, MySQL 8, Flyway, JUnit 5, Testcontainers, Vue 3, Pinia, Axios, Vitest, Playwright, GitHub Actions.

---

## Baseline and execution rules

- Work only in `D:\Fordo-hardening` on `codex/project-hardening`.
- Preserve untracked `_verify_commit.mjs` and `_verify_out.txt`; never stage them.
- V6 and V9 have not run in a persistent environment, so these unreleased files may be corrected in place.
- For every production behavior change: write a failing test, run it and record the expected failure, implement the minimum fix, rerun the focused test, then run the module regression set.
- Each task ends with a focused commit. Do not combine unrelated task files.

### Task 1: Make authentication context and signing-secret checks fail closed

**Files:**
- Modify: `wenjin-server/src/main/java/com/wenjin/config/AuthContextInterceptor.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/security/AuthSecretGuard.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/config/AuthContextInterceptorTest.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/security/AuthSecretGuardTest.java`

- [ ] **Step 1: Write failing context-cleanup tests**

Add tests that seed `CurrentUser` before an anonymous request and before async handoff:

```java
@Test
void preHandle_clearsPrincipalLeftByPreviousRequest() {
    CurrentUser.set(new CurrentPrincipal(99L, UserRole.STUDENT));
    interceptor.preHandle(new MockHttpServletRequest("GET", "/api/course/my"), response, new Object());
    assertThat(CurrentUser.getPrincipal()).isNull();
}

@Test
void afterConcurrentHandlingStarted_clearsOriginalServletThread() {
    CurrentUser.set(new CurrentPrincipal(7L, UserRole.STUDENT));
    interceptor.afterConcurrentHandlingStarted(
            new MockHttpServletRequest("POST", "/api/companion/chat"), response, new Object());
    assertThat(CurrentUser.getPrincipal()).isNull();
}
```

- [ ] **Step 2: Write the failing no-profile secret test**

Replace the old permissive expectation with:

```java
@Test
void noProfile_defaultSecret_throws() {
    assertThatThrownBy(() ->
            AuthSecretGuard.enforce(TokenService.DEFAULT_DEV_SECRET, new String[]{}))
            .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 3: Run RED tests**

Run:

```powershell
Set-Location D:\Fordo-hardening\wenjin-server
.\mvnw.cmd -q -Dtest=AuthContextInterceptorTest,AuthSecretGuardTest test
```

Expected: failures because stale context remains, async cleanup does not exist, and no-profile default secret is currently allowed.

- [ ] **Step 4: Implement minimal fail-closed behavior**

Make the interceptor implement `AsyncHandlerInterceptor` and use unconditional cleanup:

```java
public class AuthContextInterceptor implements AsyncHandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        CurrentUser.clear();
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        // existing Bearer verification and PrincipalLoader lookup
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        CurrentUser.clear();
    }
}
```

Require an explicit relaxed profile:

```java
private static boolean isDevOrTest(String[] activeProfiles) {
    return activeProfiles != null
            && activeProfiles.length > 0
            && Arrays.stream(activeProfiles).allMatch(p -> "dev".equals(p) || "test".equals(p));
}
```

- [ ] **Step 5: Run GREEN and auth regression tests**

```powershell
.\mvnw.cmd -q -Dtest=AuthContextInterceptorTest,AuthSecretGuardTest,StudentAuthChainIntegrationTest,CompanionControllerTest test
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```powershell
git add wenjin-server/src/main/java/com/wenjin/config/AuthContextInterceptor.java wenjin-server/src/main/java/com/wenjin/security/AuthSecretGuard.java wenjin-server/src/test/java/com/wenjin/config/AuthContextInterceptorTest.java wenjin-server/src/test/java/com/wenjin/security/AuthSecretGuardTest.java
git commit -m "fix(auth): close async context and secret fail-open paths"
```

### Task 2: Scope 401 handling to the token that sent the request

**Files:**
- Modify: `wenjin-web/src/api/http.js`
- Modify: `wenjin-web/src/api/companion.js`
- Modify: `wenjin-web/src/api/__tests__/http.test.js`
- Modify: `wenjin-web/test/companion-sse.spec.js`

- [ ] **Step 1: Write failing stale-token and SSE tests**

```javascript
it('does not clear a newer session for an old-token 401', async () => {
  localStorage.setItem('wj_token', 'token-b')
  await expect(unwrapFailure({ status: 401, config: { __wjAuthToken: 'token-a' }, headers: {} },
    { code: 401, message: 'expired' })).rejects.toMatchObject({ code: 401 })
  expect(localStorage.getItem('wj_token')).toBe('token-b')
  expect(window.location.assign).not.toHaveBeenCalled()
})
```

Add an SSE test whose fetch returns 401 and assert the shared token-scoped handler is invoked only for the token captured before fetch.

- [ ] **Step 2: Run RED tests**

```powershell
Set-Location D:\Fordo-hardening\wenjin-web
npx vitest run src/api/__tests__/http.test.js test/companion-sse.spec.js
```

Expected: stale 401 clears `token-b`, and SSE does not use central auth handling.

- [ ] **Step 3: Implement token-scoped clearing**

Store the token on Axios config and pass it through failures:

```javascript
export function clearSessionAndRedirect(expectedToken) {
  const current = localStorage.getItem('wj_token')
  if (expectedToken != null && current !== expectedToken) return false
  // existing store/localStorage clear and redirect
  return true
}

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('wj_token')
  config.__wjAuthToken = token
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
```

Capture the SSE token once and call the same function on HTTP 401.

- [ ] **Step 4: Run GREEN tests and commit**

```powershell
npx vitest run src/api/__tests__/http.test.js test/companion-sse.spec.js
git add wenjin-web/src/api/http.js wenjin-web/src/api/companion.js wenjin-web/src/api/__tests__/http.test.js wenjin-web/test/companion-sse.spec.js
git commit -m "fix(web): scope auth failures to request tokens"
```

### Task 3: Correct V5, V6, V9 and staged Flyway execution

**Files:**
- Modify: `wenjin-server/src/main/resources/db/migration/V5__concurrency_guards.sql`
- Modify: `wenjin-server/src/main/resources/db/migration/V6__question_stem_hash.sql`
- Modify: `wenjin-server/src/main/resources/db/migration/V9__relational_integrity.sql`
- Modify: `wenjin-server/src/main/resources/schema.sql`
- Modify: `wenjin-server/src/test/java/com/wenjin/integration/SchemaMigrationIT.java`
- Modify: `docs/runbooks/project-hardening-rollout.md`
- Modify: `DEPLOYMENT.md`

- [ ] **Step 1: Add failing migration integration cases**

Add four Testcontainers cases with these exact arrangements and assertions:

1. Migrate an empty database to V4, insert two `learning_path_item` rows with one `path_id/step_order`, migrate to V5, assert SQLSTATE 45000, and assert both item IDs remain.
2. Migrate to V5, insert two questions in one course whose stems differ only by surrounding whitespace, migrate to V6, assert SQLSTATE 45000, and assert `columnExists("question", "stem_hash")` is false.
3. Configure Flyway with `.target("8")`, migrate, assert successful history versions are exactly 1 through 8, and assert `constraintNameExists("fk_course_teacher")` is false.
4. Migrate to V8, insert an active student, course, question, adaptive session, and scene-1 `answer_record.session_id` reference; migrate to V9, insert a second adaptive answer, and assert both rows exist.

Use a target-aware helper:

```java
private static Flyway createFlyway(String target) {
    return Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("2")
            .validateOnMigrate(true)
            .target(target)
            .load();
}
```

- [ ] **Step 2: Run RED migration test**

```powershell
Set-Location D:\Fordo-hardening\wenjin-server
.\mvnw.cmd -q -Dtest=SchemaMigrationIT test
```

Expected with Docker available: failures demonstrate V5 deletion, V6 half-application, and the invalid V9 practice FK. If Docker is unavailable, retain the test and record the environmental failure; do not treat it as GREEN.

- [ ] **Step 3: Replace destructive V5 dedupe with a precheck**

Run a stored-procedure precheck before the first `ALTER TABLE`:

```sql
SELECT COUNT(*) INTO v_cnt FROM (
  SELECT path_id, step_order FROM learning_path_item
  GROUP BY path_id, step_order HAVING COUNT(*) > 1
) d;
IF v_cnt > 0 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V5 precheck: duplicate learning_path_item step_order';
END IF;
```

Repeat for `(path_id,node_id)` and remove both `DELETE` statements.

- [ ] **Step 4: Make V6 a retryable nullable expand**

Precheck duplicate normalized stems before adding the column:

```sql
SELECT COUNT(*) INTO v_cnt FROM (
  SELECT course_id, SHA2(TRIM(stem), 256) AS h
  FROM question GROUP BY course_id, h HAVING COUNT(*) > 1
) d;
```

Then add nullable `stem_hash`, backfill, and add the unique key without `MODIFY ... NOT NULL`. V9 retains the null audit and contract.

- [ ] **Step 5: Remove the invalid polymorphic FK**

Delete `fk_answer_practice_session` from V9 and the schema snapshot. Document `session_id` as a logical scene-dependent compatibility field; retain `fk_answer_fixed_diag`.

- [ ] **Step 6: Make rollout targets executable**

Use explicit commands:

```bash
SPRING_FLYWAY_TARGET=8 WENJIN_FLYWAY_ENABLED=true java -jar wenjin-server.jar
SPRING_FLYWAY_TARGET=9 WENJIN_FLYWAY_ENABLED=true java -jar wenjin-server.jar
```

Phase C uses target 8 only; Phase H uses target 9 only.

- [ ] **Step 7: Run GREEN migration test and commit**

```powershell
.\mvnw.cmd -q -Dtest=SchemaMigrationIT test
git add -f wenjin-server/src/main/resources/db/migration/V5__concurrency_guards.sql wenjin-server/src/main/resources/db/migration/V6__question_stem_hash.sql wenjin-server/src/main/resources/db/migration/V9__relational_integrity.sql wenjin-server/src/main/resources/schema.sql wenjin-server/src/test/java/com/wenjin/integration/SchemaMigrationIT.java docs/runbooks/project-hardening-rollout.md DEPLOYMENT.md
git commit -m "fix(db): make hardening migrations staged and data-safe"
```

### Task 4: Join mastery and learning-path writes to caller transactions

**Files:**
- Modify: `wenjin-server/src/main/java/com/wenjin/service/impl/MasteryServiceImpl.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/service/LearningPathWriter.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/service/MasteryServiceImplTest.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/service/PathServiceImplTest.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/integration/MasteryConcurrencyIT.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/integration/PathConcurrencyIT.java`

- [ ] **Step 1: Add failing propagation and rollback tests**

Use a recording `PlatformTransactionManager` in unit tests and real MySQL in ITs. Assert an ambient diagnostic/practice transaction produces no `REQUIRES_NEW` begin and that a forced exception after mastery/path generation leaves all affected rows unchanged.

```java
assertThat(transactionDefinition.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
```

- [ ] **Step 2: Run RED tests**

```powershell
.\mvnw.cmd -q -Dtest=MasteryServiceImplTest,PathServiceImplTest,MasteryConcurrencyIT,PathConcurrencyIT test
```

Expected: propagation or rollback assertions fail on current `REQUIRES_NEW` behavior.

- [ ] **Step 3: Implement REQUIRED semantics**

Use `PROPAGATION_REQUIRED` for the transaction template and writer. If an ambient transaction exists, execute the locked update once and let the command boundary handle rollback; only standalone calls wrap a new REQUIRED transaction in bounded deadlock retry.

```java
if (TransactionSynchronizationManager.isActualTransactionActive()) {
    applyAnswersLocked(studentId, courseId, answers,
            difficultyByQuestion, nodesByQuestion, nodeIds);
    return;
}
runWithDeadlockRetry(() -> requiredTx.executeWithoutResult(status ->
        applyAnswersLocked(studentId, courseId, answers,
                difficultyByQuestion, nodesByQuestion, nodeIds)));
```

- [ ] **Step 4: Run GREEN tests and commit**

```powershell
.\mvnw.cmd -q -Dtest=MasteryServiceImplTest,PathServiceImplTest,PracticeServiceImplTest,DiagnosticServiceImplTest,AdaptiveDiagnosticServiceTest test
git add wenjin-server/src/main/java/com/wenjin/service/impl/MasteryServiceImpl.java wenjin-server/src/main/java/com/wenjin/service/LearningPathWriter.java wenjin-server/src/test/java/com/wenjin/service/MasteryServiceImplTest.java wenjin-server/src/test/java/com/wenjin/service/PathServiceImplTest.java wenjin-server/src/test/java/com/wenjin/integration/MasteryConcurrencyIT.java wenjin-server/src/test/java/com/wenjin/integration/PathConcurrencyIT.java
git commit -m "fix(tx): keep mastery and path writes atomic"
```

### Task 5: Serialize every graph mutation on the course row

**Files:**
- Create: `wenjin-server/src/main/java/com/wenjin/security/CourseMutationLock.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/mapper/CourseAccessMapper.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/service/impl/GraphServiceImpl.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/service/impl/TeacherGraphServiceImpl.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/service/GraphServiceImplTest.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/service/TeacherGraphServiceImplTest.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/integration/GraphIdentityPreservationIT.java`

- [ ] **Step 1: Add failing lock-order and concurrency tests**

Add unit assertions that mutation methods reject writes while the graph flag is off and call the lock before graph snapshot reads. Add MySQL races for concurrent full imports and reverse prerequisite edges; assert the result equals one complete import and contains no cycle.

- [ ] **Step 2: Run RED tests**

```powershell
.\mvnw.cmd -q -Dtest=GraphServiceImplTest,TeacherGraphServiceImplTest,GraphIdentityPreservationIT test
```

- [ ] **Step 3: Add one shared locking primitive**

```java
@Select("SELECT id FROM course WHERE id=#{courseId} AND archived_at IS NULL FOR UPDATE")
Long lockActiveCourse(@Param("courseId") Long courseId);

@Component
public class CourseMutationLock {
    public void lockActive(Long courseId) {
        if (mapper.lockActiveCourse(courseId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
    }
}
```

Inject and call it before `reconcileNodes`, `buildPrerequisiteAdj`, or any node/edge update. Re-read the affected edge/node after acquiring the lock. Call `ensureGraphReconcileEnabled()` before every graph mutation, including edge create and accept.

- [ ] **Step 4: Run GREEN tests and commit**

```powershell
.\mvnw.cmd -q -Dtest=GraphServiceImplTest,TeacherGraphServiceImplTest test
git add wenjin-server/src/main/java/com/wenjin/security/CourseMutationLock.java wenjin-server/src/main/java/com/wenjin/mapper/CourseAccessMapper.java wenjin-server/src/main/java/com/wenjin/service/impl/GraphServiceImpl.java wenjin-server/src/main/java/com/wenjin/service/impl/TeacherGraphServiceImpl.java wenjin-server/src/test/java/com/wenjin/service/GraphServiceImplTest.java wenjin-server/src/test/java/com/wenjin/service/TeacherGraphServiceImplTest.java wenjin-server/src/test/java/com/wenjin/integration/GraphIdentityPreservationIT.java
git commit -m "fix(graph): serialize course graph mutations"
```

### Task 6: Make course archive and restore explicit and unpublished

**Files:**
- Modify: `wenjin-server/src/main/java/com/wenjin/mapper/CourseAccessMapper.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/security/CourseAccessService.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/service/TeacherCourseService.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/service/impl/TeacherCourseServiceImpl.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/controller/TeacherCourseController.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/service/impl/GraphServiceImpl.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/service/TeacherCourseServiceImplTest.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/controller/TeacherCourseControllerTest.java`

- [ ] **Step 1: Write failing archive/restore tests**

Test that archive sets both `status=0` and `archived_at`, normal management rejects archived courses, import cannot restore them, and explicit restore returns the course as unpublished.

- [ ] **Step 2: Run RED tests**

```powershell
.\mvnw.cmd -q -Dtest=TeacherCourseServiceImplTest,TeacherCourseControllerTest,GraphServiceImplTest test
```

- [ ] **Step 3: Implement active-only guards and explicit restore**

Normal ownership SQL includes `archived_at IS NULL`. Add an including-archived owner/admin assertion used only by:

```java
@PatchMapping("/{id}/restore")
public Result<TeacherCourseVO> restore(@PathVariable @Positive Long id) {
    return Result.ok(service.restore(id));
}
```

Archive sets `status=0`; restore clears course/node/edge archives only after locking and restores `status=0`. Remove implicit course unarchive from graph import.

- [ ] **Step 4: Run GREEN tests and commit**

```powershell
.\mvnw.cmd -q -Dtest=TeacherCourseServiceImplTest,TeacherCourseControllerTest,CourseAccessServiceTest,GraphServiceImplTest test
git add wenjin-server/src/main/java/com/wenjin/mapper/CourseAccessMapper.java wenjin-server/src/main/java/com/wenjin/security/CourseAccessService.java wenjin-server/src/main/java/com/wenjin/service/TeacherCourseService.java wenjin-server/src/main/java/com/wenjin/service/impl/TeacherCourseServiceImpl.java wenjin-server/src/main/java/com/wenjin/controller/TeacherCourseController.java wenjin-server/src/main/java/com/wenjin/service/impl/GraphServiceImpl.java wenjin-server/src/test/java/com/wenjin/service/TeacherCourseServiceImplTest.java wenjin-server/src/test/java/com/wenjin/controller/TeacherCourseControllerTest.java
git commit -m "fix(course): make archive restore explicit and unpublished"
```

### Task 7: Exclude archived-node questions and preserve completed diagnostic replay

**Files:**
- Modify: `wenjin-server/src/main/java/com/wenjin/service/impl/DiagnosticServiceImpl.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/service/DiagnosticServiceImplTest.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/integration/FixedDiagnosticSessionIT.java`

- [ ] **Step 1: Write failing composition and flag tests**

Add one approved question linked only to an archived node and assert it is absent. Create a completed session, disable the flag, submit the same token, and assert the stored result is replayed without new writes.

- [ ] **Step 2: Run RED tests**

```powershell
.\mvnw.cmd -q -Dtest=DiagnosticServiceImplTest,FixedDiagnosticSessionIT test
```

- [ ] **Step 3: Implement eligible question filtering and replay ordering**

Build the eligible list from approved questions whose weight-1 node exists in the active course node map. In submit, lock and authorize the session, replay completed results, then enforce the write feature flag before processing an incomplete session.

- [ ] **Step 4: Run GREEN tests and commit**

```powershell
.\mvnw.cmd -q -Dtest=DiagnosticServiceImplTest test
git add wenjin-server/src/main/java/com/wenjin/service/impl/DiagnosticServiceImpl.java wenjin-server/src/test/java/com/wenjin/service/DiagnosticServiceImplTest.java wenjin-server/src/test/java/com/wenjin/integration/FixedDiagnosticSessionIT.java
git commit -m "fix(diagnostic): honor archives and replay completed sessions"
```

### Task 8: Add durable AI job leases, cancellation, and effect boundaries

**Files:**
- Modify: `wenjin-server/src/main/resources/db/migration/V7__ai_job.sql`
- Modify: `wenjin-server/src/main/resources/schema.sql`
- Modify: `wenjin-server/src/main/java/com/wenjin/entity/AiJob.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/mapper/AiJobMapper.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/service/AiJobService.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/support/AiJobErrorCodes.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/service/AiJobServiceTest.java`
- Modify: `wenjin-server/src/test/java/com/wenjin/integration/AiJobRecoveryIT.java`

- [ ] **Step 1: Write failing durable-state and stale-worker tests**

Cover persisted `cancel_requested_at`, unique lease creation, heartbeat extension, lease-qualified progress/success CAS, requeue before effect start, and `UNKNOWN_OUTCOME` after effect start.

```java
assertThat(service.markSucceeded(jobId, oldLease, result)).isFalse();
assertThat(service.markSucceeded(jobId, currentLease, result)).isTrue();
```

- [ ] **Step 2: Run RED tests**

```powershell
.\mvnw.cmd -q -Dtest=AiJobServiceTest,AiJobRecoveryIT test
```

- [ ] **Step 3: Extend the unreleased V7 schema**

```sql
`cancel_requested_at` DATETIME NULL,
`lease_token` CHAR(36) NULL,
`lease_until` DATETIME NULL,
`heartbeat_at` DATETIME NULL,
`effect_started_at` DATETIME NULL,
KEY `idx_ai_job_lease` (`status`,`lease_until`)
```

- [ ] **Step 4: Implement lease-qualified mapper transitions**

Every worker update includes:

```sql
WHERE id=#{jobId} AND status=1 AND lease_token=#{leaseToken}
```

Stale recovery requeues only expired rows with `effect_started_at IS NULL`; expired effect-started rows become FAILED with `UNKNOWN_OUTCOME`.

- [ ] **Step 5: Replace in-memory cancellation**

Remove the `ConcurrentHashMap`. Pending cancellation uses a status-qualified UPDATE; running cancellation sets `cancel_requested_at` and workers read it from the database.

- [ ] **Step 6: Run GREEN tests and commit**

```powershell
.\mvnw.cmd -q -Dtest=AiJobServiceTest test
git add wenjin-server/src/main/resources/db/migration/V7__ai_job.sql wenjin-server/src/main/resources/schema.sql wenjin-server/src/main/java/com/wenjin/entity/AiJob.java wenjin-server/src/main/java/com/wenjin/mapper/AiJobMapper.java wenjin-server/src/main/java/com/wenjin/service/AiJobService.java wenjin-server/src/main/java/com/wenjin/support/AiJobErrorCodes.java wenjin-server/src/test/java/com/wenjin/service/AiJobServiceTest.java wenjin-server/src/test/java/com/wenjin/integration/AiJobRecoveryIT.java
git commit -m "fix(jobs): persist cancellation and lease ownership"
```

### Task 9: Enforce job kill switch and real actor identity in workers

**Files:**
- Modify: `wenjin-server/src/main/java/com/wenjin/service/AiJobWorker.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/service/AiJobService.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/mapper/AiJobMapper.java`
- Modify: `wenjin-server/src/main/java/com/wenjin/support/AiJobPayloadStore.java`
- Create: `wenjin-server/src/main/java/com/wenjin/support/AiJobPayloadCleanup.java`
- Create: `wenjin-server/src/test/java/com/wenjin/service/AiJobWorkerTest.java`
- Create: `wenjin-server/src/test/java/com/wenjin/support/AiJobPayloadCleanupTest.java`

- [ ] **Step 1: Write failing worker tests**

Test that a disabled flag performs no recover/claim, a disabled owner fails before domain service invocation, ADMIN remains ADMIN, heartbeat uses the current lease, and lease loss stops terminal writes. Add payload tests in which a shared hash is retained while any referencing job is pending/running and deleted only after every referencing job is terminal and older than the retention threshold.

- [ ] **Step 2: Run RED test**

```powershell
.\mvnw.cmd -q -Dtest=AiJobWorkerTest test
```

- [ ] **Step 3: Implement worker execution guards**

`tick()` returns before recovery when the feature is off. Load the actor through `PrincipalLoader` immediately before execution:

```java
CurrentPrincipal actor = principalLoader.load(job.getOwnerId())
        .orElseThrow(() -> new BusinessException(ResultCode.UNAUTHORIZED, "JOB_ACTOR_REVOKED"));
try (WorkerPrincipalScope ignored = new WorkerPrincipalScope(actor)) {
    executeClaimed(job, job.getLeaseToken());
}
```

Schedule heartbeat while executing, mark the effect boundary before `runKind`, and pass `leaseToken` to every state transition.

- [ ] **Step 4: Add safe payload retention cleanup**

Query distinct `fileHash` values from terminal job JSON only when no PENDING/RUNNING row references the same hash. `AiJobPayloadCleanup` runs on a configured retention interval and calls `deleteQuietly`; content hashes shared by active jobs are never removed.

```sql
SELECT DISTINCT JSON_UNQUOTE(JSON_EXTRACT(done.request_json,'$.fileHash')) AS file_hash
FROM ai_job done
WHERE done.status IN (2,3,4)
  AND done.finished_at < #{before}
  AND JSON_EXTRACT(done.request_json,'$.fileHash') IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM ai_job live
    WHERE live.status IN (0,1)
      AND JSON_UNQUOTE(JSON_EXTRACT(live.request_json,'$.fileHash')) =
          JSON_UNQUOTE(JSON_EXTRACT(done.request_json,'$.fileHash'))
  )
```

- [ ] **Step 5: Run GREEN tests and commit**

```powershell
.\mvnw.cmd -q -Dtest=AiJobWorkerTest,AiJobServiceTest,AiJobPayloadCleanupTest test
git add wenjin-server/src/main/java/com/wenjin/service/AiJobWorker.java wenjin-server/src/main/java/com/wenjin/service/AiJobService.java wenjin-server/src/main/java/com/wenjin/mapper/AiJobMapper.java wenjin-server/src/main/java/com/wenjin/support/AiJobPayloadStore.java wenjin-server/src/main/java/com/wenjin/support/AiJobPayloadCleanup.java wenjin-server/src/test/java/com/wenjin/service/AiJobWorkerTest.java wenjin-server/src/test/java/com/wenjin/support/AiJobPayloadCleanupTest.java
git commit -m "fix(jobs): honor kill switch and actor revocation"
```

### Task 10: Serialize job polling and make idempotency keys action-scoped

**Files:**
- Modify: `wenjin-web/src/composables/useJob.js`
- Modify: `wenjin-web/src/composables/__tests__/useJob.test.js`
- Modify: `wenjin-web/src/views/TeacherQuestionPool.vue`
- Modify: `wenjin-web/src/views/TeacherGraphReview.vue`
- Modify: `wenjin-web/src/views/__tests__/TeacherGraphReview.spec.js`

- [ ] **Step 1: Write failing overlap, cancel, course, and repeat-action tests**

Use deferred promises to resolve a newer terminal poll before an older RUNNING poll and assert terminal state remains. Assert cancel invalidates the deferred poll. Assert two completed clicks receive different keys, while two in-flight clicks share one key. Assert a course-A job cannot call a course-B success callback.

- [ ] **Step 2: Run RED tests**

```powershell
npx vitest run src/composables/__tests__/useJob.test.js src/views/__tests__/TeacherGraphReview.spec.js
```

- [ ] **Step 3: Replace interval polling with serialized timeout polling**

```javascript
async function pollLoop(myEpoch) {
  await pollOnce(myEpoch)
  if (!disposed && myEpoch === epoch && !isJobTerminal(job.value)) {
    pollTimer = setTimeout(() => void pollLoop(myEpoch), pollIntervalMs)
  }
}
```

Increment `epoch` before cancel and course switch. Bind both teacher graph jobs to the reactive course source and capture course code at dispatch.

- [ ] **Step 4: Make keys action-scoped and cancel batch server-side**

Remove deterministic course/node/file hashes. Generate a random key at the start of each action and retain it in the in-flight action state. Store the active batch job ID and call `cancelJob(activeId)` before marking local cancellation.

- [ ] **Step 5: Run GREEN tests and commit**

```powershell
npx vitest run src/composables/__tests__/useJob.test.js src/views/__tests__/TeacherGraphReview.spec.js
git add wenjin-web/src/composables/useJob.js wenjin-web/src/composables/__tests__/useJob.test.js wenjin-web/src/views/TeacherQuestionPool.vue wenjin-web/src/views/TeacherGraphReview.vue wenjin-web/src/views/__tests__/TeacherGraphReview.spec.js
git commit -m "fix(web): serialize jobs and scope idempotency actions"
```

### Task 11: Add take-latest gates to every reviewed course loader

**Files:**
- Modify: `wenjin-web/src/views/TeacherGraphReview.vue`
- Modify: `wenjin-web/src/views/TeacherQuestionPool.vue`
- Modify: `wenjin-web/src/views/ColorMap.vue`
- Modify: `wenjin-web/src/views/__tests__/TeacherGraphReview.spec.js`
- Modify: `wenjin-web/src/views/__tests__/ColorMap.spec.js`
- Create: `wenjin-web/src/views/__tests__/TeacherQuestionPool.spec.js`

- [ ] **Step 1: Write deferred A-to-B course-switch tests**

For graph, pending edges, question page, graph nodes, and review queue: start A, switch to B, resolve B first and A second, then assert only B data/loading/error is visible.

- [ ] **Step 2: Run RED tests**

```powershell
npx vitest run src/views/__tests__/TeacherGraphReview.spec.js src/views/__tests__/TeacherQuestionPool.spec.js src/views/__tests__/ColorMap.spec.js
```

- [ ] **Step 3: Apply request generations**

Use `createLatestRequest()` per independently loaded resource. Capture the ID at dispatch, pass its AbortSignal into the API, and guard data, error, and loading writes with `isCurrent()`.

- [ ] **Step 4: Run GREEN tests and commit**

```powershell
npx vitest run src/views/__tests__/TeacherGraphReview.spec.js src/views/__tests__/TeacherQuestionPool.spec.js src/views/__tests__/ColorMap.spec.js
git add wenjin-web/src/views/TeacherGraphReview.vue wenjin-web/src/views/TeacherQuestionPool.vue wenjin-web/src/views/ColorMap.vue wenjin-web/src/views/__tests__/TeacherGraphReview.spec.js wenjin-web/src/views/__tests__/TeacherQuestionPool.spec.js wenjin-web/src/views/__tests__/ColorMap.spec.js
git commit -m "fix(web): gate course loaders by request generation"
```

### Task 12: Commit companion conversation switches atomically

**Files:**
- Modify: `wenjin-web/src/views/Companion.vue`
- Modify: `wenjin-web/test/Companion.spec.js`

- [ ] **Step 1: Write failing slow-switch and failure tests**

Open conversation 10, start opening 20, assert 10 remains selected and send is disabled until 20 succeeds. On success assert ID/messages change together; on failure assert 10 remains selected and visible.

- [ ] **Step 2: Run RED test**

```powershell
npx vitest run test/Companion.spec.js
```

- [ ] **Step 3: Implement atomic detail commit**

Use `conversationLoadingTarget` and assign `conversationId` only after the current response and snapshot merge are complete. Include the loading target in `canSend`.

- [ ] **Step 4: Run GREEN and commit**

```powershell
npx vitest run test/Companion.spec.js test/companion-sse.spec.js test/companion-persistence.test.js
git add wenjin-web/src/views/Companion.vue wenjin-web/test/Companion.spec.js
git commit -m "fix(companion): commit conversation switches atomically"
```

### Task 13: Execute real integration tests and enforce documented coverage

**Files:**
- Modify: `wenjin-server/pom.xml`
- Modify: `.github/workflows/ci.yml`
- Modify: `wenjin-web/package.json`
- Modify: `wenjin-web/vitest.config.js`
- Create: `wenjin-web/src/api/__tests__/endpoints.spec.js`
- Create: `wenjin-web/src/store/__tests__/graphStore.spec.js`

- [ ] **Step 1: Add coverage tests before raising thresholds**

Mock the shared HTTP instance and invoke every exported API wrapper with exact path/body/config assertions. Add graph-store tests for invalid course reset, successful load, stale response suppression, error handling, and prerequisites.

- [ ] **Step 2: Run tests and record current coverage**

```powershell
npx vitest run src/api/__tests__/endpoints.spec.js src/store/__tests__/graphStore.spec.js
npm run test:coverage
```

Expected before new tests: line coverage below 80%; after tests, line coverage at least 80% and branch coverage at least 70%.

- [ ] **Step 3: Configure Maven Failsafe and JaCoCo gates**

Bind `maven-failsafe-plugin` to `integration-test` and `verify` with `**/*IT.java`. Set JaCoCo line minimum to `0.70` and add branch minimum `0.60`.

- [ ] **Step 4: Remove the obsolete legacy script and repair CI gates**

Delete `test:legacy` because all tests now use Vitest. CI uploads Failsafe reports and checks that at least one `TEST-*IT.xml` exists. For whitespace, PR builds run `git diff --check ${{ github.event.pull_request.base.sha }}...HEAD`; push builds run `git diff --check HEAD^..HEAD`.

- [ ] **Step 5: Raise Vitest thresholds**

```javascript
thresholds: {
  lines: 80,
  statements: 80,
  branches: 70,
  functions: 70
}
```

- [ ] **Step 6: Run GREEN unit/coverage gates and commit**

```powershell
npm run test:coverage
npm run check
git add .github/workflows/ci.yml wenjin-server/pom.xml wenjin-web/package.json wenjin-web/vitest.config.js wenjin-web/src/api/__tests__/endpoints.spec.js wenjin-web/src/store/__tests__/graphStore.spec.js
git commit -m "ci: execute integration tests and enforce coverage"
```

### Task 14: Make browser and accessibility gates exercise authenticated screens

**Files:**
- Modify: `wenjin-web/e2e/accessibility.spec.js`
- Modify: `wenjin-web/e2e/mobile-map.spec.js`
- Modify: `wenjin-web/playwright.config.js`
- Modify: `wenjin-web/src/views/TeacherGraphReview.vue`
- Modify: `wenjin-web/src/views/__tests__/TeacherGraphReview.spec.js`

- [ ] **Step 1: Write authenticated route fixtures and failing assertions**

Intercept `/api/me`, course, graph, companion, and teacher endpoints with deterministic fixtures. Tests must assert the target authenticated page is visible before scanning. Axe fails on both `serious` and `critical`; color contrast stays enabled.

- [ ] **Step 2: Run RED browser tests**

```powershell
$env:WJ_PW_CHANNEL='msedge'
npm run test:e2e
```

Expected: current redirect-accepting and login-only tests do not satisfy the authenticated assertions; keyboard edge creation exposes inaccessible controls.

- [ ] **Step 3: Make graph edge controls keyboard-operable**

Use semantic buttons/listbox options with focus, Enter, Space, Escape, and visible focus behavior. Keep existing mouse behavior.

- [ ] **Step 4: Run GREEN browser/unit tests and commit**

```powershell
npx vitest run src/views/__tests__/TeacherGraphReview.spec.js
$env:WJ_PW_CHANNEL='msedge'
npm run test:e2e
git add wenjin-web/e2e/accessibility.spec.js wenjin-web/e2e/mobile-map.spec.js wenjin-web/playwright.config.js wenjin-web/src/views/TeacherGraphReview.vue wenjin-web/src/views/__tests__/TeacherGraphReview.spec.js
git commit -m "test(e2e): cover authenticated hardening flows"
```

### Task 15: Remove warnings, whitespace debt, and run final verification

**Files:**
- Modify: `DEPLOYMENT.md`
- Modify: `docs/quality/project-hardening-baseline.md`
- Modify: `docs/runbooks/project-hardening-rollout.md`
- Modify: frontend files reported by strict ESLint

- [ ] **Step 1: Run strict lint and commit-range whitespace checks as RED gates**

```powershell
Set-Location D:\Fordo-hardening\wenjin-web
npm run lint -- --max-warnings=0
Set-Location D:\Fordo-hardening
git diff --check 04b5a39533c632e3da0b9b8540719383e4f756ea..HEAD
```

Expected before cleanup: 19 unused-variable warnings and trailing whitespace findings.

- [ ] **Step 2: Remove unused code and Markdown trailing spaces**

Delete unused variables/imports instead of suppressing rules. Replace Markdown hard-break spaces with normal paragraphs or explicit line breaks that do not create trailing whitespace.

- [ ] **Step 3: Run all frontend gates**

```powershell
Set-Location D:\Fordo-hardening\wenjin-web
npm ci
npm run lint -- --max-warnings=0
npm run test:coverage
npm run check
$env:WJ_PW_CHANNEL='msedge'
npm run test:e2e
```

Expected: every command exits 0; Vitest meets 80/70; each raw chunk remains at most 512,000 bytes; Playwright passes authenticated flows.

- [ ] **Step 4: Run all backend and migration gates**

```powershell
Set-Location D:\Fordo-hardening\wenjin-server
.\mvnw.cmd clean verify
```

Expected with Docker available: unit and all `*IT` tests execute with zero failures; JaCoCo meets 70/60. If Docker is unavailable, report that environmental blocker separately and still run `-DskipITs` only as a diagnostic, never as completion evidence.

- [ ] **Step 5: Verify repository state and commit cleanup**

```powershell
Set-Location D:\Fordo-hardening
git diff --check 04b5a39533c632e3da0b9b8540719383e4f756ea..HEAD
git status --short
git add DEPLOYMENT.md wenjin-web/src/composables/useJob.js wenjin-web/src/views/ColorMap.vue wenjin-web/src/views/NodePractice.vue wenjin-web/src/views/TeacherGraphReview.vue wenjin-web/src/views/TeacherQuestionPool.vue
git add -f docs/quality/project-hardening-baseline.md docs/runbooks/project-hardening-rollout.md
git commit -m "chore: close hardening quality gates"
```

Expected: diff check exits 0; only the two preserved untracked verification files remain.

- [ ] **Step 6: Request final independent review**

Review the complete remediation range for security, migration safety, transaction atomicity, job lease correctness, frontend request generations, and CI selection. Resolve every P0/P1 comment before branch completion.
