# PR #1: корректное поведение по кейсу

Пользователь одобрил исправления после ревью и сверки первичного ТЗ/письменных ответов. Это ремонт уже добавленных функций, без обязательной ATS-интеграции или проверки работодателей.

## Контракт
- Удалить Trust Score, endpoint и фиктивные verified/95/96/signals из UI. Обычный профиль компании остаётся; подтверждение email аккаунта не выдаётся за проверку работодателя.
- При публикации вакансии передать числовую зарплату, RUB/month/basis, специализацию, грейд, формат, город и занятость из потребности. Backend валидирует обязательные данные и принадлежность need. Нет defaults100–200тыс.
- Все мутации вакансий/откликов проходят существующий MutationService: повтор с тем же ключом/телом даёт один эффект, иное тело конфликтует.
- PDF разбирается только при текущем processing consent. Извлечённые поля сначала показаны как предложения; сохранение только по явному подтверждению. Нет выдуманного headline/role/grade; Senior не становится Middle автоматически; подтверждённый грейд не меняется.
- Контакты в ATS только из явно enabled каналов действующего company grant, без fallback account_email. Сохраняются текущие согласия, lock/revoke semantics и журналирование. Экспорт обозначен JSON-экспортом, без обещания настоящей ATS-синхронизации.
- Самостоятельный отклик допустим и явно разрешает передачу выбранных контактов только компании вакансии. Отзыв contact access является отдельным действием кандидата и блокирует новые contact reads. Publication/visibility регулируют общий банк, не отменяют сам отклик. Processing revoke блокирует новые чтения личных полей/контактов отклика; минимальные status/id/date могут сохраняться. Уже выданные копии не обещаем удалить. Не создавать вымышленные accepted invitations ради grant.
- API имеет точные request/response/error schemas; выбранный Swagger отражает реализованные endpoints. Нет новых зависимостей, изменений canonical qualification/ranking и обходов исходных приглашений/grants.

## Проверка
Регрессии RED перед исправлением:disabled email in export,processing-gated resume,real salaries,retry dedup,company render without TrustScore,unsupported grade/unknown resume,publication-vs-contact-vs-processing state,foreign-company access. Затем targeted/full frontend+backend,TypeScript/build,OpenAPI validation,изолированный browser smoke и независимые code-reviewer/architect. Корневые dirty изменения сохраняются.
