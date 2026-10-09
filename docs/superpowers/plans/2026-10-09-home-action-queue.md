# Home Action Queue Implementation Plan

> **For agentic workers:** Ralph native implementation lanes have disjoint ownership. Backend executor owns the queue controller/tests; frontend executor owns queue/journey/UI files. Leader owns OpenAPI/generated types, documentation, integration and final evidence. Preserve pre-existing dirty work; do not commit or push.

**Goal:** Surface current shared hiring steps and deadlines on both home screens with direct dialogue links.

**Architecture:** One actor-scoped, original-grant-gated, cursor-paged read-only projection from existing accepted invitations and unfinished next steps. A shared frontend hook/component handles the live queue with scoped generation guards and clearing before revalidation. Candidate priority selector adds known own shared action after attempt/pending invitations.

**Tech Stack:** Existing Kotlin/Spring JDBC/OpenAPI/React/TypeScript; no new dependencies or migration.

## Task 1: Contract and regression shape — leader

- [x] Save context and source/dirty-status baseline under local/ralph-iteration-2026-10-09/.
- [x] Add NextStepQueueItem/Page and envelope plus GET /me/next-steps to docs/contracts/openapi.v1.yaml. Names/limits/privacy follow the design.
- [x] Regenerate frontend/src/api/schema.d.ts; give implementers stable contract.

## Task 2: Backend queue — backend executor

Files: new invitations/NextStepQueueController.kt and new invitations/NextStepQueueIntegrationTest.kt. Do not modify existing detail/write controllers or other workers' files.

- [x] Write HTTP tests before implementation; targeted missing route must fail with 404 vs expected200, then save RED output.
- [x] Use current candidate auth, verified company-owner auth, accepted invitation and exact active original grant scope. Reuse ConsentService.publicationGateSql and PublicTextRedactor for names; exclude deleted users/completed/missing steps.
- [x] Implement bounded limit+1 ordering and actor-bound cursor with own priority, nullable due_at, updated_at and invitation_id. Validate cursor strictly with 400 errors; no numeric wrapping or invalid datetime normalization.
- [x] Validate paging through own/waiting and dated/null rows, deterministic ties, role ownership, unverified/non-owner company, revoke/replacement grant, hidden candidate name redaction, completed/non-accepted/missing exclusions, bad limit/cursor.
- [x] Run targeted tests and bootJar with JDK21, isolated fsp_test and /private/tmp/fsp-lct-gradle-home. Save log and stop further Gradle commands before leader full-suite run.

## Task 3: Frontend queue — frontend executor

Files: features/hiring/useNextStepQueue.ts, NextStepQueue.tsx and owned CSS; features/journey.ts + journey.test.ts, new queue.test.ts/helpers as necessary; App.tsx home integration.

- [x] Add failing regression assertions for own action CTA priority below active attempt/pending invitation, queue page merge/no duplicates, partial empty proof and stale generation rejection.
- [x] Build typed shared hook for both roles/user IDs: initial page, next page, explicit error/empty/partial state; clear rows before visible refocus/30-second refresh and denial; discard old identities/generations and overlapping page responses.
- [x] Build compact shared list with role-safe names, proposed actor/deadline/overdue copy and direct dialogue link. Reuse nextStepStatus and existing brand tokens/components; do not embed editor or fetch per invitation.
- [x] Integrate one hook instance per homepage and selected known own invitation in candidateNextStep. Preserve old priorities and pending count lower-bound semantics.
- [x] Run targeted/full frontend tests and typecheck/build. No backend Gradle, contracts or generated type edits.

## Task 4: Runtime and architect — leader

- [x] Run full backend test/bootJar sequentially after backend executor finishes; frontend tests/typecheck/build and other required checks. Read actual reports.
- [x] Start loopback QA API/Vite with a new synthetic database; exercise both roles, pagination, due/overdue/null, priority, refocus, hide/revoke, reload, keyboard, desktop/mobile.
- [x] Native architect verifies changed code, requirements and evidence. Fix rejection findings and re-run relevant tests before approval.

## Task 5: Deslop, regression and completion — leader/cleaner

- [x] Run oh-my-codex:ai-slop-cleaner standard mode on Ralph-owned changed files only, with explicit smell plan and existing regression lock. No broad cleanup.
- [x] Re-run all appropriate tests/typecheck/build after cleanup. Record explicit gaps when LSP/lint tools do not exist and compiler alternatives.
- [x] Update README/DESIGN/public validation report; verify frozen hashes and unrelated dirty files; save changed-file diff/report.
- [x] Write/read Ralph completion_audit with requirement-to-evidence mappings; inspect get_goal (no active goal at intake). Stop only when all selected requirements are verified.
- [x] Run exact-scope cancel, preserving completion audit in iteration artifacts; stop own temporary servers/tabs. No push retry.
