import assert from 'node:assert/strict';
import test from 'node:test';
import { candidateNextStep, isProfileDiscoverable, pendingInvitationCount } from './journey.ts';

const ready = {
  activeAttemptId: null, pendingInvitations: 0, processingAccepted: true,
  profileComplete: true, verifiedGrade: 'junior', discoverable: true,
};

test('an existing assessment remains resumable even with incoming invitations', () => {
  const next = candidateNextStep({ ...ready, activeAttemptId: 'attempt-1', pendingInvitations: 2 });
  assert.equal(next.href, '/assessment/attempt-1');
});

test('an invitation stays reachable despite incomplete profile or revoked processing consent', () => {
  assert.equal(candidateNextStep({ ...ready, pendingInvitations: 1, processingAccepted: false, profileComplete: false }).href, '/invitations');
});

test('processing consent precedes profile editing and the qualification path', () => {
  assert.equal(candidateNextStep({ ...ready, processingAccepted: false, profileComplete: false, verifiedGrade: null }).href, '/consents');
  assert.equal(candidateNextStep({ ...ready, profileComplete: false, verifiedGrade: null }).href, '/profile');
});

test('unknown invitation state does not act like an empty inbox', () => {
  assert.equal(candidateNextStep({ ...ready, pendingInvitations: null }).href, '/invitations');
  assert.match(candidateNextStep({ ...ready, pendingInvitations: null }).description, /проверить/i);
});

test('a confirmed public candidate is not asked to repeat qualification', () => {
  assert.equal(candidateNextStep(ready).href, '/profile/preview');
});

test('an unconfirmed profile can reach assessment without promising publication', () => {
  assert.equal(candidateNextStep({ ...ready, verifiedGrade: null, discoverable: false }).href, '/assessment');
});

test('a deliberately hidden qualified profile leads to controls without automatic publication', () => {
  const next = candidateNextStep({ ...ready, discoverable: false });
  assert.equal(next.href, '/consents');
  assert.equal(next.label, 'Управлять видимостью');
});

test('discoverability requires public visibility, a complete profile and both current consents', () => {
  const visible = { visibility: 'public', profileComplete: true, processingAccepted: true, publicationAccepted: true };
  assert.equal(isProfileDiscoverable(visible), true);
  for (const override of [{ visibility: 'hidden' }, { profileComplete: false }, { processingAccepted: false }, { publicationAccepted: false }]) {
    assert.equal(isProfileDiscoverable({ ...visible, ...override }), false);
  }
});

test('a full first page without pending invitations cannot prove an empty inbox', () => {
  const page = { items: Array.from({ length: 20 }, () => ({ state: 'accepted' })), page: { next_cursor: 'next-page' } };
  assert.equal(pendingInvitationCount([page]), null);
  assert.equal(candidateNextStep({ ...ready, pendingInvitations: pendingInvitationCount([page]) }).href, '/invitations');
  assert.equal(pendingInvitationCount([page, { items: [{ state: 'pending' }], page: { next_cursor: null } }]), 1);
});

test('only an exhausted list proves zero pending; a partial positive count is a lower bound', () => {
  assert.equal(pendingInvitationCount([]), null);
  assert.equal(pendingInvitationCount([{ items: [{ state: 'declined' }], page: { next_cursor: null } }]), 0);
  assert.equal(pendingInvitationCount([{ items: [{ state: 'pending' }], page: { next_cursor: 'more' } }]), 1);
});
