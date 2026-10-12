# Motion: мгновенный отклик и спокойная иерархия

Явный запрос пользователя разрешает реализацию в существующем проекте. Основа — Apple Motion / Designing Fluid Interfaces, с локальными проверяемыми параметрами. Фирменный стиль ФСП сохраняется.

## Выбранный подход

Используем native CSS для одноразовых коротких появлений и rAF critically damped spring для press/release. Одна общая установка на корне App, только eligible native controls, без React-рендера на каждом кадре. Текущее положение и скорость сохраняются при перенаправлении цели; фоновые/отсоединённые элементы не анимируются. Новая motion-зависимость и чисто CSS easing для пружины рассматривались: первая добавляет ненужный пакет, второй не передаёт текущую скорость при повторном вводе.

Нажатие получает немедленный native визуальный отклик и небольшое сжатие, release плавно возвращает масштаб к1. Действие совершает существующий обработчик; motion не запускает click, не preventDefault/stopPropagation и не pointer-capture. Enter/Space обрабатываются лишь в рамках native semantics соответствующего button/link; repeats не создают новый цикл. Drag-away/cancel/blur завершают press.

## Поверхности

Заголовок нового экрана: короткий fade +6px displacement. Не анимировать workspace/main-area/route-wrapper и не держать old route DOM. Статические data cards не получают общий float/hover.

Новые notifications: короткая opacity entry; содержание и role=status/alert доступны сразу. Диалоги: opacity backdrop и небольшой scale/translation собственного panel, без анимации blur; Escape/backdrop/close немедленно удаляют DOM и сохраняют существующий возврат фокуса. На fixed-предках transform/opacity animation не добавляется. Native mobile details остаётся native; caret получает прерываемый поворот.

## Accessibility / privacy / lifecycle

Reduced motion полностью убирает movement, оставляя цвет/контраст/focus feedback. Media-query change останавливает активную пружину немедленно. Contrast/transparency/forced-colors поддержка существующего проекта сохраняется. Очередь не проигрывает entry/exit при каждом30s refresh; private rows уходят немедленно, как раньше.

На hidden/blur, detach и cleanup animation прекращается и стили возвращаются к исходным. Размер hit target/layout и form state не меняется. Новая анимация не меняет серверные политики, время допуска, permissions или состояние выполнения запросов.

## Acceptance

Тесты current-value/velocity retarget, settle, reduced-motion/dynamic cancellation и cleanup; интеграция с настоящим App остаётся green. Browser доказательства press/release/rapid reversal/cancel/keyboard, dialog placement/trap/return, route navigation, private refresh immediate clear и reduced/contrast. Проверка390/1280px. Полный test/typecheck/build, changed-files cleaner, независимые APPROVE+CLEAR и invariant audit до завершения goal.
