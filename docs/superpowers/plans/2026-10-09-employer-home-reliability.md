# Employer home reliability implementation plan

> **For agentic workers:** Execute the approved N1 scope inline with TDD and verification-before-completion. One owner changes App.tsx; independent review is read-only.

**Goal:** Make employer-home counts honest and preserve independent sections during loading/errors.

**Architecture:** Keep current query hooks and queue. Derive pending count with pendingInvitationCount; partial counts display lower bounds or unknown. Render loading/error in the affected statistic/card only.

**Tech Stack:** React 19, TanStack Query 5, existing Vite 8 and Node test runner.

## Task 1: Lock actual screen behavior

Files: create frontend/tests/employer-home.test.mjs. Preserve production until tests reproduce defects.

- [x] Configure Vite middleware SSR with ws:false, hmr:false and watch:null; render the existing App in MemoryRouter and QueryClientProvider.
- [x] Seed /auth/me; use real QueryClient success/pending/error states for needs and sentInvitations. Assert visible stat value/detail, queue heading, main links and affected error.
- [x] Run `rtk proxy node --experimental-strip-types --test tests/employer-home.test.mjs` from frontend. Expected failures: partial zero/positive count and sections hidden by sibling loading/error.

## Task 2: Minimal screen change

Files: frontend/src/App.tsx; frontend/package.json.

- [x] Remove the two whole-screen needs/sent early returns from EmployerHome.
- [x] Compute pending only for sent.isSuccess via `pendingInvitationCount([sent.data])`; distinguish partial/nonpartial captions. Unavailable count is —.
- [x] Compute successful needs state independently; mark partial active count and list incompleteness. Error card runs only needs.refetch; invitation error runs only sent.refetch.
- [x] Keep queue, quick links and header mounted. Never render cached block data when its query has an error.
- [x] Add the SSR regression file to the existing npm test command; no dependencies.
- [x] Run the targeted tests, then all frontend tests/typecheck/build.

## Task 3: Review and evidence

Files: docs/validation/employer-home-reliability-2026-10-09.md and local/home-reliability/2026-10-09/ artifacts.

- [x] Test desktop/mobile against an isolated synthetic fixture service; loading/error of each endpoint, exact/partial counts and local retry. No live data or outbound messages.
- [x] Ask independent reviewer to inspect correctness, authorization-related cached/error behavior and scope.
- [x] Verify /usr/bin/git diff --check and that preexisting dirty file hashes/status match baseline. Record actual test counts, render/runtime proof boundaries and remaining unmeasured user benefit.
