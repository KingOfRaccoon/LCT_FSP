# ФСП · Таланты

Прототип обратного подбора ИТ-специалистов: кандидат подтверждает категорию тестом, работодатель ищет по потребности или по банку и первым отправляет предложение с зарплатой. Контакты открываются по согласию кандидата.

## Реализовано

- Кабинеты кандидата и компании, подтверждение email.
- Профиль, опыт, проекты, роли и гибкие навыки; PDF-паспорт.
- Банк кандидатов без потребности, фильтры категории/грейда/навыков/ФСП, шорт-листы.
- Объяснимый подбор, сравнение и вопросы для интервью.
- Явные версионированные согласия, собственное превью и журнал открытия контактов.
- Приглашение без вакансии, обязательная зарплата, неизменяемые сведения компании; принятие/отказ и отзыв доступа.
- Приватные заметки и этапы компании.

Каталог оценки: Backend Python junior/middle. Формы: 12 заданий, 24 ответа, четыре блока; порог 18/24 и минимум 3/6 в каждом блоке. Срок 30 минут, повтор того же уровня через 24 часа, ограничение смены грейда 30 дней. Заявленные сведения, тест и demo ФСП отображаются раздельно.

## Стек

Spring Boot 3.5.9 / Kotlin 2.2.20 / Java 21, PostgreSQL 17 / Flyway; React 19 / TypeScript / Vite, Node >=22.12.0. Серверный язык Kotlin не связан с языком категории Backend Python.

## Запуск с Docker

```bash
docker compose up --build
```

Приложение: http://localhost:8080. Локальная почта Mailpit: http://localhost:8025. Compose запускает базу, API, worker и web.

Демо-аккаунты: `candidate@example.test`, `employer@example.test`, `other-employer@example.test`; пароль `DemoPassword123!`. Все данные синтетические. Явно примите текущие согласия; прежний public-флаг сам по себе не открывает профиль.

`.env.example` содержит только демонстрационные значения. Для собственной среды задайте параметры подключения и session secret отдельно. Реальные грейды вне demo mode заблокированы до содержательной проверки банка.

## Нативная разработка

Нужны JDK 21, Node >=22.12.0 и PostgreSQL 17 с базами `fsp_dev`/`fsp_test`. Экспортируйте переменные из `.env.example`; Spring не загружает этот файл автоматически.

```bash
./scripts/backend-local.sh
# В другом терминале:
npm --prefix frontend ci
npm --prefix frontend run dev -- --host 127.0.0.1
```

Открыть http://localhost:5173. Для подтверждения email настройте локальный SMTP на 127.0.0.1:1025 и включите отдельный worker:

```bash
./scripts/backend-local.sh --spring.main.web-application-type=none --fsp.outbox-enabled=true --fsp.seed-demo=false
```

На новой машине используйте Compose или установленный PostgreSQL 17. Вспомогательные скрипты и бинарники рабочей станции находятся в `local/` и в Git не хранятся.

## Проверки

```bash
./backend/gradlew -p backend --no-daemon --max-workers=1 test bootJar
npm --prefix frontend run types:api
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

Backend-проверки ограничены отдельной базой `fsp_test`. Последний локальный прогон: 107 тестов без ошибок. Синтетические test-retest и matching benchmark воспроизводятся тестами; их отчёты создаются в `backend/build/reports`. Они не доказывают качество реального найма или человеческую валидность грейда. Контейнерная конфигурация подготовлена; в исходной рабочей сессии проверялся нативный runtime.

## Контракты и материалы

- [OpenAPI](docs/contracts/openapi.v1.yaml)
- [Методика оценки](docs/assessment/assessment-policy.v1.json) и [банк](docs/assessment/question-bank.v1.json)
- [Политика подбора](docs/contracts/matching-policy.v1.json)
- [Шаблон интервью](docs/contracts/interview-kit.v1.json)
- [PPTX](submission/FSP_Pitch.pptx), [сопроводительный PDF](submission/FSP_Companion_Documentation.pdf), [видео 2:36](submission/FSP_Demo.mp4)
- [Версии компонентов](submission/dependency-versions.txt)

Фирменные SVG/шрифты ФСП и их лицензии находятся в `frontend/public/brand`. Тексты согласий — проект для утверждения оператором. ФСП-интеграция демонстрационная; API реестра/ATS и юридическая готовность оператора не заявляются.

Рабочие исследования, планы, внутренние обсуждения, отчёты агентов, промежуточные рендеры и заметки докладчика хранятся в `local/` и исключены из Git.
