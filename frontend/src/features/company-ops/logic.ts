export type VerificationStatus =
  | 'unverified' | 'pending' | 'registry_confirmed' | 'reviewed' | 'needs_changes'
  | 'rejected' | 'unavailable' | 'expired' | 'superseded' | 'demo';

export type PublicCompanyVerificationSummary = {
  status: VerificationStatus;
  demo: boolean;
  checked_at?: string | null;
  expires_at?: string | null;
  company_version?: number;
  checks?: { code: 'registry' | 'legal_name' | 'corporate_email' | 'representative'; status: 'passed' | 'failed' | 'unknown' }[];
  claims_legal_authority: false;
};

const statusLabels: Record<VerificationStatus, string> = {
  unverified: 'Не проверена', pending: 'Ожидает проверки', registry_confirmed: 'Сверено с реестром',
  reviewed: 'Проверена оператором', needs_changes: 'Нужны уточнения', rejected: 'Проверка отклонена',
  unavailable: 'Проверка недоступна', expired: 'Срок проверки истёк', superseded: 'Данные компании изменились',
  demo: 'Демонстрационная проверка',
};

export function verificationBadgeModel(summary?: Pick<PublicCompanyVerificationSummary, 'status' | 'demo' | 'claims_legal_authority'>) {
  if (!summary) return { label: 'Не проверена', tone: 'neutral', canGrantContactAccess: false as const };
  const base = statusLabels[summary.status] ?? 'Статус проверки неизвестен';
  return {
    label: summary.demo ? `ДЕМО · ${base}` : base,
    tone: summary.demo ? 'neutral' : summary.status === 'registry_confirmed' || summary.status === 'reviewed' ? 'good' : summary.status === 'needs_changes' || summary.status === 'pending' ? 'warm' : 'neutral',
    canGrantContactAccess: false as const,
  };
}

export function isValidRussianInn(value: string): boolean {
  const inn = value.replace(/\s/g, '');
  if (!/^\d{10}(?:\d{2})?$/.test(inn)) return false;
  const digits = [...inn].map(Number);
  const checkDigit = (values: number[], weights: number[]) => values.reduce((sum, digit, index) => sum + digit * weights[index], 0) % 11 % 10;
  if (digits.length === 10) return checkDigit(digits.slice(0, 9), [2, 4, 10, 3, 5, 9, 4, 6, 8]) === digits[9];
  const first = checkDigit(digits.slice(0, 10), [7, 2, 4, 10, 3, 5, 9, 4, 6, 8]);
  const second = checkDigit(digits.slice(0, 11), [3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8]);
  return first === digits[10] && second === digits[11];
}

export type CompanyReviewDraft = {
  outcome: 'approve' | 'request_changes' | 'reject';
  evidence_reference: string;
  registry_inn: string;
  registry_legal_name: string;
  registry_status: 'ACTIVE' | 'LIQUIDATING' | 'TERMINATED' | 'UNKNOWN';
  representative_reviewed: boolean;
  comment: string;
};

export function validateCompanyReview(draft: CompanyReviewDraft, expected?: { inn: string; legal_name: string }): string | undefined {
  if (!draft.evidence_reference.trim() || !draft.comment.trim()) return 'Укажите проверенный источник и комментарий к решению.';
  if (draft.outcome === 'approve') {
    if (!draft.representative_reviewed) return 'Для подтверждения проверьте представителя компании.';
    if (!isValidRussianInn(draft.registry_inn) || !draft.registry_legal_name.trim() || draft.registry_status !== 'ACTIVE') {
      return 'Подтверждение требует корректный ИНН, название и действующий статус в реестре.';
    }
    if (expected && (draft.registry_inn.replace(/\s/g, '') !== expected.inn.replace(/\s/g, '') || normalizeLegalName(draft.registry_legal_name) !== normalizeLegalName(expected.legal_name))) {
      return 'Данные из источника должны совпадать с ИНН и юридическим наименованием компании.';
    }
  }
  if (draft.outcome !== 'approve' && !draft.comment.trim()) return 'Добавьте комментарий к решению.';
  return undefined;
}

function normalizeLegalName(value: string) { return value.trim().toLocaleLowerCase('ru-RU').replace(/\s+/g, ' '); }
