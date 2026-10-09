type CandidateJourney = {
  activeAttemptId: string | null;
  pendingInvitations: number | null;
  processingAccepted: boolean;
  profileComplete: boolean;
  verifiedGrade: string | null;
  discoverable: boolean;
};

type NextStep = { title: string; description: string; label: string; href: string };

/** A positive result is a lower bound until the last page has been loaded. */
export function pendingInvitationCount(pages: readonly { items: readonly { state: string }[]; page?: { next_cursor: string | null } }[]): number | null {
  if (!pages.length) return null;
  const count = pages.reduce((total, page) => total + page.items.filter((item) => item.state === 'pending').length, 0);
  return count === 0 && pages.at(-1)?.page?.next_cursor ? null : count;
}

export function isProfileDiscoverable(state: {
  visibility: string; profileComplete: boolean; processingAccepted: boolean; publicationAccepted: boolean;
}): boolean {
  return state.visibility === 'public' && state.profileComplete && state.processingAccepted && state.publicationAccepted;
}

export function candidateNextStep(state: CandidateJourney): NextStep {
  if (state.activeAttemptId) return {
    title: 'Продолжите начатую оценку', description: 'Ответы сохраняются в текущей попытке. Её срок продолжает идти.',
    label: 'Продолжить оценку', href: `/assessment/${encodeURIComponent(state.activeAttemptId)}`,
  };
  if (state.pendingInvitations !== null && state.pendingInvitations > 0) return {
    title: 'Компании ждут вашего решения', description: 'Посмотрите условия и причины приглашения. Открытие страницы не передаёт контакты.',
    label: 'Посмотреть приглашения', href: '/invitations',
  };
  if (!state.processingAccepted) return {
    title: 'Просмотрите согласие на обработку', description: 'Оно требуется для сохранения профиля и новой оценки. Решение остаётся за вами.',
    label: 'Просмотреть согласие', href: '/consents',
  };
  if (!state.profileComplete) return {
    title: 'Завершите основной профиль', description: 'Укажите обязательные данные и предпочтения. Проекты и опыт можно дополнить позже.',
    label: 'Заполнить профиль', href: '/profile',
  };
  if (state.pendingInvitations === null) return {
    title: 'Проверьте приглашения', description: 'Пока не удалось проверить, ждут ли компании вашего решения.',
    label: 'Проверить приглашения', href: '/invitations',
  };
  if (!state.verifiedGrade) return {
    title: 'Подтвердите компетенции', description: 'Оценка подтверждает уровень по методике платформы; тестовые данные помечаются demo. Видимость настраивается отдельно.',
    label: 'К оценке', href: '/assessment',
  };
  if (!state.discoverable) return {
    title: 'Уровень подтверждён. Профиль пока скрыт', description: 'Выберите, показывать ли его компаниям. Можно оставить профиль скрытым; контакты открываются отдельно.',
    label: 'Управлять видимостью', href: '/consents',
  };
  return {
    title: 'Профиль готов к подбору', description: 'Повторять оценку не требуется. Проверьте, что видит компания; приглашения появятся в отдельном разделе.',
    label: 'Посмотреть свой профиль', href: '/profile/preview',
  };
}
