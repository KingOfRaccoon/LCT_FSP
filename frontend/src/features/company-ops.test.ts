import assert from 'node:assert/strict';
import test from 'node:test';
import { isValidRussianInn, verificationBadgeModel, validateCompanyReview } from './company-ops/logic.ts';

test('accepts valid 10 and 12 digit Russian INNs and rejects checksum errors', () => {
  assert.equal(isValidRussianInn('7707083893'), true);
  assert.equal(isValidRussianInn('500100732259'), true);
  assert.equal(isValidRussianInn('7707083894'), false);
  assert.equal(isValidRussianInn('500100732258'), false);
  assert.equal(isValidRussianInn('770708389'), false);
});

test('demo verification is visibly marked and never grants contact access', () => {
  const badge = verificationBadgeModel({ status: 'registry_confirmed', demo: true, claims_legal_authority: false });
  assert.equal(badge.label.startsWith('ДЕМО'), true);
  assert.equal(badge.canGrantContactAccess, false);
});

test('all reviews require source and comment, while approval also requires matching active registry evidence and representative review', () => {
  const draft = {
    outcome: 'approve', evidence_reference: 'registry-check-2026-10', registry_inn: '7707083893',
    registry_legal_name: 'ПАО Сбербанк', registry_status: 'ACTIVE', representative_reviewed: true, comment: 'Сверено по реестру',
  } as const;
  assert.equal(validateCompanyReview(draft), undefined);
  assert.equal(validateCompanyReview(draft, { inn: '7707083893', legal_name: 'ПАО   Сбербанк' }), undefined);
  assert.match(validateCompanyReview(draft, { inn: '7707083893', legal_name: 'ООО Другая компания' }) ?? '', /совпадать/);
  assert.equal(validateCompanyReview(draft, { inn: '7707083894', legal_name: 'ПАО Сбербанк' })?.includes('совпадать'), true);
  assert.equal(validateCompanyReview({
    outcome: 'approve', evidence_reference: '', registry_inn: '7707083893',
    registry_legal_name: 'ПАО Сбербанк', registry_status: 'ACTIVE', representative_reviewed: false, comment: '',
  }), 'Укажите проверенный источник и комментарий к решению.');
  assert.equal(validateCompanyReview({
    outcome: 'request_changes', evidence_reference: 'case-123', registry_inn: '',
    registry_legal_name: '', registry_status: 'UNKNOWN', representative_reviewed: false, comment: '',
  }), 'Укажите проверенный источник и комментарий к решению.');
  assert.equal(validateCompanyReview({
    outcome: 'reject', evidence_reference: '', registry_inn: '',
    registry_legal_name: '', registry_status: 'UNKNOWN', representative_reviewed: false, comment: 'Проверка завершена',
  }), 'Укажите проверенный источник и комментарий к решению.');
});
