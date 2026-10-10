# Project adoption implementation plan

**Goal:** Deliver reviewable PDF profile import, need diagnostics, source-linked methodist drafts/pilot and aggregate bank monitoring.

**Architecture:** Extend the existing Kotlin/React application and contracts. Reuse PDFBox, MatchingPolicy and consent gates; methodist permissions are explicitly provisioned grants. Live assessment bank and release gates remain separate from drafts/pilot.

**Tech stack:** Kotlin, Spring JDBC/Flyway, PDFBox 3.0.6; React/TypeScript and existing UI tokens. No new dependencies.

## Task 1 — contracts and permissions (root)

- [x] Extend OpenAPI with resume draft, salary preference, need insights, methodist requests/responses. Generate frontend API types.
- [x] Add `UserMe.methodist_access` using server membership, provision a demo-only methodist account without changing public registration.
- [x] Extend multipart API transport with CSRF, idempotency and preserved browser boundary.
- [x] Verify transport test fails before implementation and passes afterwards.

## Task 2 — PDF import (resume_backend; adoption_frontend)

- [x] Add `ResumeImportIntegrationTest` for no DB mutation and explicit file errors, confirm RED.
- [x] Implement `profiles/ResumeImport*.kt` using bounded in-memory PDF extraction; add conservative parser unit fixtures.
- [x] Add `features/adoption/ResumeImportPanel.tsx` and selection helpers: editable suggestions, explicit selected fields, additive collections, conflict preservation.
- [x] Run targeted backend tests and frontend selection tests; exercise actual multipart PDF and profile update in runtime.

## Task 3 — need insights (need_analytics; root; adoption_frontend)

- [x] Add `NeedInsightsHttpIntegrationTest`, confirm 200 expectation initially fails as endpoint404.
- [x] Implement `NeedInsights*.kt` using `MatchingService.insightProfiles()` and `MatchingPolicy`.
- [x] Add optional private candidate salary declaration with validation, preserving qualification and visibility.
- [x] Add `NeedInsightsPanel` to saved need cards and `SalaryPreferenceEditor` to own profile.
- [x] Verify foreign ownership/stale version/archive/privacy/small samples and same-policy counterfactual group transition.

## Task 4 — methodist workbench/monitor (methodist_backend; methodist_frontend)

- [x] Add access tests without grant, confirm current404 vs expected401/403.
- [x] Add V9 permission, versioned draft/review/pilot tables and private methods; no external fetch or live-bank write.
- [x] Implement source-linked question editor, review, version-bound experimental observations and separate export.
- [x] Implement aggregate bank inventory/exposure/repetition/difficulty signals with small-sample unknowns.
- [x] Add keyboard/mobile workbench, explicit private-key handling and distinct synthetic/pilot labels.
- [x] Verify permission boundaries, optimistic conflicts, idempotency, revision isolation and no personal data in reports.

## Task 5 — integration and proof (root)

- [x] Mount components in existing profile/needs and provisioned methodist navigation without unrelated refactor.
- [x] Run full backend tests + bootJar, frontend tests/typecheck/build, Python test suite and diff check.
- [x] Exercise own candidate import/review/save, employer diagnostics, methodist create/review/pilot/export/monitor on a separate synthetic runtime.
- [x] Review security/privacy and full flow; fix concrete findings and rerun affected checks.
- [x] Record changed files/evidence/limitations in validation report; preserve preexisting dirty files.

Gradle is owned by root and runs sequentially to avoid shared build races. Each implementation slice has separate file ownership. Existing branch changes are preserved; no blanket staging/reset/cleanup is allowed.
