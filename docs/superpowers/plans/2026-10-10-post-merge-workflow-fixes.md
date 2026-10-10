# Post-merge workflow corrections implementation plan

> **For agentic workers:** Execute bounded tasks with Codex native executors; preserve separate spec and quality review. Follow test-driven-development for the behavior corrections.

**Goal:** Implement the approved M01–M04 from the post-merge research without changing backend permissions, grading or matching.

**Architecture:** Keep existing routes, components and API endpoints. Bind asynchronous protected data to the selected identity/version before committing a response. Recover insights by explicitly reading the latest owned need; keep that snapshot local to analytics so unsaved role-task edits remain intact.

**Tech Stack:** React19.2, TanStack Query5.90, TypeScript5.9, Vite8, node:test. No new dependencies.

---

## Task1 — candidate contact copy (owner: parent, App.tsx)

- [x] RED: SSR-render the real candidate profile from preloaded QueryClient data. Assert both invitation acceptance and selected contacts of a voluntary application are explained; old invitation-only wording must fail.
- [x] Replace the two strings with:

```text
Контакты доступны компании после принятия вами приглашения или передачи выбранных каналов при самостоятельном отклике.
Включённые каналы можно передать компании, приняв её приглашение. При самостоятельном отклике вы отдельно выбираете, какие контакты передать компании этой вакансии. Публикация профиля сама по себе контакты не открывает.
```

- [x] GREEN: run `rtk proxy node --test tests/post-merge-candidate.test.mjs` in frontend; assert old copy absent and actual application channel UI unchanged.

## Task2 — assessment history (owner: parent, App.tsx)

- [x] RED: preload history with evaluated, invalidated and in_progress entries and two pages. Assert each ID opens the existing attempt route and retains score/status.
- [x] Insert an explicit link inside each history row:

```tsx
<Link className="quiet-link" to={`/assessment/${encodeURIComponent(item.id)}`}>
  {item.status === 'in_progress' ? 'Продолжить →' : item.status === 'submitted' ? 'Открыть попытку →' : 'Посмотреть результат →'}
</Link>
```

- [x] GREEN: run the candidate test. Existing GET ownership enforcement remains unchanged; test inaccessible result stays an error rather than fallback to another attempt.

## Task3 — methodist response ownership (executor owns MethodistWorkspace.tsx and dedicated tests)

- [x] RED: render the actual component callbacks in a deterministic node hook harness. Start report/export A, select B, then resolve/reject A. Cover same-ID version replacement, new draft, latest request and access denial.
- [x] Add a small request generation and selected identity/version check before any state write, including errors. Reset/invalidate the generation when selection or draft version changes; ensure stale responses do not briefly render private data.

```ts
if (request !== currentRequest || access !== currentAccess || draftId !== currentDraftId || version !== currentVersion) return;
```

- [x] Preserve current access-denial behavior for the current request; mismatched payload identity/version must not be presented as current.
- [x] GREEN: run targeted methodist tests and typecheck. No changes to pilot statistics, reviews or permissions.

## Task4 — need insights conflict recovery (executor owns NeedInsightsPanel.tsx and dedicated tests)

- [x] RED: current needV1 insights returns409 VERSION_CONFLICT; explicit recovery must GET `/needs/:id`, then request insights with returnedV2. A transient error retriesV1. Check identity change/unmount/stale callback and denied recovery.
- [x] Consolidate the current duplicated insights fetch/retry path. Track conflict separately from transient errors and label recovery clearly. Store the fresh need only in analytics; do not rewrite parent/form data or PATCH requirements.

```ts
const latest = await api.get<Need>(`/needs/${encodeURIComponent(need.id)}`);
const path = `/needs/${encodeURIComponent(latest.id)}/insights?expected_version=${latest.version}`;
```

- [x] Validate latest owned needID and monotonic version; do not loop automatically if it changes again. Reject stale results and clear old analytics before retry/recovery.
- [x] GREEN: targeted actual-component tests and typecheck, then review against the accepted M04 criteria.

## Integration and verification (parent)

- [x] Tests first in each author lane; retain exact failing and passing outputs under root local/post-merge-fixes/2026-10-10.
- [x] Independent spec review for all four tasks, then independent quality review; resolve findings before delivery.
- [x] Fresh complete `rtk proxy npm test`, `rtk proxy npm run typecheck`, `rtk proxy npm run build`; diff-check.
- [x] Real-browser smoke for candidate links and delayed methodist/need API responses using synthetic fixtures; label this frontend integration proof, not production/backend validation.
- [x] Copy only approved changed files to primary checkout after checking their baseline hashes, preserving all unrelated dirty files/deletions and private research material.
- [x] Save the validation report. Deliver local changes; publication is outside this implementation turn.

Final responsive adjustment: `.history-row` wraps and renders its action on a separate line; 390px browser verification preserves one-line dates and no horizontal overflow.
