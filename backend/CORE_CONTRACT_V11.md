# v1.1 API integration contract

This document supplements `CORE_CONTRACT.md` for the additive FSP-MVP-1.1
work. It is the integration boundary for G002-G005. The OpenAPI document is
authoritative for wire names, nullability, bounds, response envelopes, and
status codes. Keep the v1 scoring, rank order, consent, session, CSRF, Origin,
and idempotency behavior intact.

## Shared rules

- All paths below are under `/api/v1`; successful JSON responses keep the
  existing `{ "data": ... }` envelope. HTTP errors keep the existing
  `{ "error": ... }` envelope.
- Cookie authentication and active database role/membership checks apply on
  every request. Candidate endpoints must call the candidate role guard;
  employer endpoints must require an email-verified employer and resolve
  `company_id` from the active membership, never from request input.
- Browser writes and all POST routes require the established CSRF token and
  same-origin validation. Mutating calls also require `Idempotency-Key` and
  run through `MutationService`; read-only `POST /candidates/compare` uses
  CSRF/Origin but has no idempotency reservation because it has no effects.
- Every new read that may contain a profile, fit, event, preview, or company
  record returns `Cache-Control: private, no-store`. Check current visibility,
  ownership, and grant inside the transaction/query for each request. A hidden
  or cross-company/candidate resource returns the established non-disclosing
  404 where the distinction would reveal private state.
- New arrays have `[]` defaults for old rows and are emitted as required
  arrays. Their PATCH/create request counterparts remain optional. Explicit
  empty arrays clear the collection; omitted fields preserve it.
- Do not accept provenance from clients. Experience/project public response
  entries have only `candidate_declared`; stored entries are candidate claims
  and never enter assessment qualification or matching evidence.
- Collection semantics are strict: an omitted collection preserves its
  current value, `[]` clears it, and `null` is invalid. Entry IDs are
  client-generated UUIDs scoped to the owning candidate and that collection.
  Enforce uniqueness within each submitted array and ownership of every
  existing ID; never fetch/adopt an entry from another owner or collection.

## Candidate profile additions

`GET/PATCH /me/profile` remains the existing endpoint and returns
`CandidatePrivate`. Add optional `experience_entries` and `project_entries`
to `CandidateProfilePatch`; emit both arrays on private and public candidate
projections. Each entry's stable `id` is a client-generated UUID. A profile
may hold at most 10 entries of each kind.

- Experience: `id`, nullable `company`, `role`, `description`,
  `start_month` and nullable `end_month` in `YYYY-MM`, plus `skill_ids`.
  Start cannot follow end. `YYYY-MM` is a year-month value, not an ISO date.
- Project: `id`, `title`, `description`, nullable HTTPS `url` without
  userinfo (maximum 2048 characters), and `skill_ids`. Never fetch/preview
  the URL server-side.
- `skill_ids` must be from the current skill taxonomy and contain no
  duplicates. Each read entry includes response-only
  `provenance: "candidate_declared"`.
- Replacing either collection increments the existing profile version and
  uses existing `expected_version` and `Idempotency-Key` semantics. Preserve
  every omitted legacy profile property.
- The public mapper sanitizes direct email/phone strings consistently with
  the public profile and PDF. It keeps the original candidate-entered text in
  the private profile and never treats these entries as verified skills.

`PATCH /needs` and `PATCH /needs/{need_id}` gain optional `role_tasks`; create
requests may also provide it. Responses always include the array. Each of up
to 10 entries is `{id, description, skill_ids}` with a stable UUID,
description of 1–1000 characters, and at least one taxonomy-only unique
skill. Every task skill must also occur in that need's requirements; validate
the complete submitted need after merging fields and on read migration of
legacy records. Updating tasks uses the existing need version,
idempotency, material-edit draft transition, and explicit competency
confirmation rules. It does not create an assessment or affect candidate
ordering.

## Candidate decision views

### Comparison

`POST /candidates/compare` accepts `need_id` and exactly 2 or 3 distinct
`candidate_ids`. It requires a verified employer, an active need owned by
that employer, and candidates currently public. The entire request fails
with 404 (`NOT_FOUND`) if the need or any candidate is unavailable; do not
return partial results. Return `need`, `policy_version`, `generated_at`, and
ordered `items`, each containing the fresh `candidate`, `fit`, and
`interview_kit`. Reuse the existing MatchingPolicy result and conditions;
there is no new score or alternate ordering. Contacts and recruiting notes
are excluded. The endpoint is read-only and private/no-store.

### Single-candidate review / interview kit

`GET /needs/{need_id}/candidate-review?candidate_id={uuid}` has the same
verified ownership and current-visibility checks. It returns the same
need-relative fit plus the shared kit, not contacts or employer notes. For a
given need and target grade, `common_questions` and their behavior anchors
must be stable across candidates. `personal_followups` may refer only to
that candidate's declared/unknown skills or provided experience/project
claims. A `not_assessed` outcome is explicit; interview levels 1-3 are not
the platform grade. Questions are employer-reviewed prompts; they do not
alter rank or qualification and never reveal assessment answer keys/options.

`CandidateFit` uses the existing group (`confirmed`, `needs_clarification`,
`needs_confirmation`, or `excluded`) and a bounded list of typed checks.
Checks expose the condition type, expected value, candidate-visible observed
value when present, status (`met`, `declared`, `unknown`, `mismatch`), and a
plain reason. The specialization check reports the exact currently supported
specialization; the result group still comes directly from `MatchingPolicy`.
Do not expose raw cross-form scores or infer a skill from sanitized free text.

## Company-private recruiting records

`GET /recruiting/records?need_id=&candidate_id=&cursor=&limit=` lists only the
active company's records, newest update first with stable UUID tie-break;
page size defaults to 20 and caps at 100. Filters are optional. `PUT
/recruiting/records/{candidate_id}` upserts the active company's one record
for the candidate. Body: `expected_version` (0 only for creation), nullable
`need_id`, `stage`, and nullable `note` (max 2000). The candidate must be
currently public at first creation. Updating that company's existing record
remains allowed after the candidate hides; the response includes only the
record and visibility status with a null display name, never a public profile
projection. The company and actor IDs come from the session. Each successful
write increments version, is idempotent, and may
update `updated_at`; stale versions return `VERSION_CONFLICT` with current
version. Supported stages are `reviewing`, `shortlisted`, `invited`,
`discussion`, and `closed`. The stage is a private human workflow label; it
does not create an invitation/grant or attest to employment. Once written,
the note remains visible only to that same company even if the candidate
later hides the profile; no private candidate fields are included. Each list
item includes `candidate_visibility`; return
`candidate_display_name: null` while hidden so a saved note cannot look like
a cached public passport. `need_title` is null if the linked need is
unavailable or no longer belongs to this company.

## Candidate preview and contact access journal

`GET /me/public-preview` is candidate-only and returns the current
`visibility` plus the exact privacy-safe public projection of that candidate.
It works for a hidden own profile, does not publish it, and does not alter any
version, qualification, or grant. There is no ID parameter.

`GET /me/contact-access-events?cursor=&limit=` is candidate-only. It returns
the caller's own journal, newest first with stable ID tie-break, up to 100
entries per page (default 20). The minimal public entry contains only `id`,
`company_id`, `company_name`, `grant_id`, `occurred_at`, and `kind`
(`contact_view` or `profile_export`). The internal row also records
`candidate_id` and `actor_user_id` for accountability; these IDs are omitted
from the candidate response. The journal never stores or returns contact
values, PDF bytes, tokens, request bodies, or idempotency keys. Append exactly
one event for each successful contacts GET that actually returns at least
one contact channel; each fresh GET, including a retry, is a distinct read
event. A denied or revoked read, or a successful response with no contact
values, creates no event. For PDF export, hold/lock and recheck the active
grant, render the PDF, then append one event only after rendering succeeds
and before committing the read transaction. A failed render creates no
event. Revocation blocks later reads.

## Shared domain DTO names

Use the canonical `ExperienceEntry`, `ProjectEntry`, `RoleTask`,
`CandidateFit`, `FitCheck`, `InterviewQuestion`, `InterviewKit`,
`CandidateReview`, `CandidateComparisonRequest`, `CandidateComparison`,
`RecruitingRecord`, `RecruitingRecordPutRequest`,
`RecruitingRecordPage`, `PublicProfilePreview`, `ContactAccessEvent`, and
`ContactAccessEventPage` schemas in OpenAPI. Avoid parallel DTOs that differ
only by property naming or loosen the privacy/ownership rules above.

`InterviewKit.template_version` is the fixed value `interview-kit-v1.0`;
`personal_followups` is capped at three. Reusable questions and anchors are
selected from that versioned server-owned template, with task/requirement
context supplied separately; candidate data must not become executable
template content.

## Verification hooks for implementers

For each endpoint, verify role/ownership, unknown/hidden-resource 404,
no-store headers, response contract, bounds, and replay behavior. Add focused
PostgreSQL-backed tests for candidate-declared provenance, existing profile
PATCH preservation, need material-edit semantics, deterministic fit reuse,
same-need interview-kit consistency, company record isolation/versioning,
preview without publication, journal minimal fields, successful PDF/contact
event insertion, and revoke followed by denied read with no success event.

InterviewQuestion additionally exposes skill_id, importance and task_ids (all matching role_tasks IDs in stable order); nullable task_id is the first linked task for compatibility. InterviewFollowup skill_id is nullable for unknown work conditions, with required nullable condition_type (grade/work_mode/city/workload); skill followups set condition_type=null. Core prompts/anchors come from docs/contracts/interview-kit.v1.json and never incorporate candidate fields.
