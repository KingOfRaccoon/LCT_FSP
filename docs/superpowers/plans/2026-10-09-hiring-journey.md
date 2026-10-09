# Hiring Journey Implementation Plan

> **For agentic workers:** Use subagent-driven-development for the bounded backend slice and review; the leader owns contract, frontend and integration. Work task-by-task; preserve unrelated changes. Git metadata was absent during implementation, so source baselines and file diffs were used. Publication later restored the existing prototype branch from the repository recorded in the submission manifest.

**Goal:** Show the employer's hiring plan before acceptance and a shared proposed next step after acceptance.

**Architecture:** Add optional snapshot-bound hiring_plan to existing invitation requests/responses. Keep next steps in an invitation-keyed table with optimistic versioning and the same original-grant access boundary as messaging. Existing recruiting records remain private.

**Tech Stack:** Kotlin/Spring JDBC/Flyway, PostgreSQL, OpenAPI JSON, React/TypeScript; no new dependencies.

## Task 1: Contract and preserved baseline

- [x] Preserve frontend/src, backend/src, contracts, assessment policies, README/DESIGN and manifests in local/hiring-journey/2026-10-09/baseline, with SHA-256 manifest.
- [x] Add HiringPlanStage, HiringPlan, HiringNextStep, InvitationNextStep, InvitationNextStepPutRequest and envelope schemas to docs/contracts/openapi.v1.yaml.
- [x] Add optional nullable hiring_plan to create/supersede and required nullable response field; add authenticated GET/PUT next-step endpoint, including CSRF/idempotency security and documented 400/404/409 responses.
- [x] Regenerate frontend/src/api/schema.d.ts with `rtk npm --prefix frontend run types:api`.

## Task 2: Backend (executor ownership)

Files: InvitationsController.kt, new InvitationNextStepController.kt, V8__invitation_next_steps.sql, new HiringJourneyIntegrationTest.kt.

- [x] Write HTTP integration tests first. Verify missing endpoint/new snapshot behaviour fails before implementation; save red output.
- [x] Extend snapshot() and DTO with hiring_plan. Validate trimmed stage titles/criteria; optional absent/null semantics follow spec. Snapshot creation call passes body["hiring_plan"]; supersede preserves old snapshot when request omits field.
- [x] Add table with invitation_id PK/FK, positive version, action, responsible_party, nullable due_at, completed and updated_at.
- [x] Implement accepted-invitation/actor checks, original-grant row locking, and access checks before idempotency replay; PUT locks invitation then grant and runs MutationService, checking expected_version against row version or zero.
- [x] Parse due_at through Instant.parse; convert validation errors to 400. Return nullable step with accurate can_update/archived and snapshot plan.
- [x] Run targeted integration tests, including unauthorized reads/writes, revoke replay, different new grant, immutable snapshots, version conflict and idempotent replay. Command: `rtk ./backend/gradlew -p backend --no-daemon --max-workers=1 test --tests '*HiringJourneyIntegrationTest'` using JDK21 and isolated fsp_test.

## Task 3: Frontend (leader ownership)

Files: features/hiring/*, features/hiring.test.ts, App.tsx, api.ts, extensions/ConversationPage.tsx.

- [x] Write failing tests for plan construction/bounds, due_at conversion, total effort, overdue/completed distinction and retry-key behaviour.
- [x] Add reusable plan editor/display. Blank optional plan remains null; enabling it requires complete stages and response time. Create/supersede use the same form parser and include plan in payload.
- [x] Display saved plan in candidate/company invitation cards, with a plain absence state.
- [x] Add next-step panel to accepted conversation: load current step, candidate read-only, company editor, optional local date-time converted to ISO, completion checkbox and explicit proposed-by-company copy.
- [x] Preserve pending payload/key on network failure; discard on edit. On 409 reload authoritative version while preserving draft and require explicit resubmit; on access denial remove data/editor. Guard responses by request/access generations; revalidate on focus and every 30 seconds when visible.
- [x] Run frontend tests/typecheck/build; regenerate API types again to verify determinism.

## Task 4: Integration and review

- [x] Run all backend tests plus bootJar; frontend test/typecheck/build and Python suites where contract changes might affect tools.
- [x] Use a separate local synthetic QA database/backend port for browser verification. Confirm plan creation/read/supersede, next-step company write/candidate read, reload, invalid input, mobile widths and revocation.
- [x] Independent spec compliance review followed by code quality/security review; resolve findings and rerun affected checks.
- [x] Update README/DESIGN with final behaviour and evidence limits, save verification/change manifest and source diff under local/hiring-journey/2026-10-09/.
- [x] Verify frozen assessment and matching-policy hashes match baseline. Deliver concise result with concrete test evidence and limitations.
