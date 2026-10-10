# Post-merge workflow fixes — 2026-10-10

Base: `54f755b68c06583b912495dffaa6091840fc893b`; approved scope M01–M04.

## Result

- Candidate profile explains both invitation acceptance and separately selected contacts in a voluntary application. Publication alone does not open contacts; server permissions are unchanged.
- Assessment history opens the existing attempt route. Active attempts offer continuation, submitted attempts inspection, and completed/invalidated attempts their result. A separate action row keeps dates readable at390px.
- Methodist report/export success and error callbacks are bound to current draft ID, version, selection lifetime, request generation and access generation. A→B→A, new drafts, version changes and unmount invalidate older responses. Loaded artifacts from an older selection/version are hidden. Current access-denial responses retain protected-state clearing.
- A need-insights version conflict offers an explicit refresh of the owned need and analysis at the returned version. The refreshed snapshot stays inside analytics; current role-task edits and parent conditions are preserved. Older refresh snapshots are rejected, transient retries remain separate, and a second conflict does not automatically loop.

## Fresh verification

- Baseline frontend tests:115/115. Final primary-checkout frontend tests:155/155, no failures/skips.
- RED/GREEN regressions: candidate3, methodist13, insights24. Deterministic hooks/HTTP boundaries test actual TSX callbacks; separate real-browser checks cover React/production assets.
- TypeScript, production build and whitespace checks pass. No standalone linter is configured. Existing main JS chunk warning remains (>500kB; current622.84kB, gzip170.74kB).
- Independent specification review:PASS. Independent code-quality review:APPROVE, final CSS whitespace finding resolved and re-reviewed.
- Real in-app-browser checks on built production assets with a synthetic loopback API: profile copy; history→detailed result; an unavailable attempt renders an error; delayed report/export A complete after B and do not replace B; V1→409→owned needV2→insightsV2 preserves an unsaved role-task textarea;390px history remains readable with no row overflow.
- Browser fixtures prove frontend integration, not real backend authorization, real contact withdrawal, grade calibration or hiring outcomes. Backend, API contract and dependencies were not changed; a full backend suite was not rerun for these frontend-only changes.
- Unrelated tracked files and existing deletions retain their exact pre-task state. Local QA artifacts remain in `local/post-merge-fixes/2026-10-10/`. Implementation was verified locally before the separately requested commit/push.

## Changed files

`frontend/src/App.tsx`, `frontend/src/brand.css`,
`frontend/src/features/methodist/MethodistWorkspace.tsx`,
`frontend/src/features/adoption/NeedInsightsPanel.tsx`,
`frontend/tests/helpers/component-harness.mjs`,
`frontend/tests/post-merge-candidate.test.mjs`,
`frontend/tests/methodist-response.test.mjs`,
`frontend/tests/need-insights-recovery.test.mjs`,
`docs/superpowers/plans/2026-10-10-post-merge-workflow-fixes.md`, this report.

Simplification: the insights initial load/retry/recovery now share one guarded loader.
Private browser proof, request logs, source hashes and review receipts are recorded in
`local/post-merge-fixes/2026-10-10/verification.json` and sibling files.
