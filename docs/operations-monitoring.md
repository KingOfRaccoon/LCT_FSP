# Операционный мониторинг

Опциональный overlay добавляет Prometheus и Grafana к основному `compose.yml`. Он слушает только localhost: Prometheus — `127.0.0.1:9090`, Grafana — `127.0.0.1:3000`.

## Запуск

Из корня репозитория:

```sh
sh infra/monitoring/setup-secrets.sh
docker compose --env-file local/monitoring/compose.env \
  -f compose.yml -f compose.monitoring.yml up -d
```

Скрипт создаёт случайные 256-битные токен метрик и пароль администратора Grafana в `local/monitoring/`, не выводя их значения. Существующие файлы он не перезаписывает. `local/` уже исключён из Git. Не помещайте эти файлы в общие каталоги и резервные копии без защиты.

Переменная `FSP_METRICS_TOKEN_FILE` задаёт исходный файл токена для Compose; по умолчанию это `local/monitoring/fsp_metrics_token`. API получает тот же файл как `/run/secrets/fsp_metrics_token` через `FSP_METRICS_TOKEN_FILE`, а Prometheus передаёт его в `Authorization: Bearer` при scrape. Переменная `FSP_GRAFANA_ADMIN_PASSWORD_FILE` задаёт файл пароля администратора. `FSP_MONITORING_SECRET_GID` берётся из основной группы пользователя, создавшего файлы, чтобы Prometheus/Grafana могли прочитать групповой файл с режимом `0640`.

Перейдите на `http://localhost:3000` и войдите как `admin`; пароль находится в `local/monitoring/grafana_admin_password`. Анонимный просмотр и самостоятельная регистрация отключены. У Prometheus локальная защита интерфейса не настроена, поэтому его порт намеренно не опубликован на внешние интерфейсы.

Остановить только мониторинг можно командой:

```sh
docker compose --env-file local/monitoring/compose.env \
  -f compose.yml -f compose.monitoring.yml stop prometheus grafana
```

## Что собирается

Prometheus опрашивает защищённый `GET /api/v1/operations/metrics` раз в 15 секунд. В наборах метрик есть доступность снимка БД, текущие счётчики HTTP-запросов и 5xx, гистограмма задержки, объёмы outbox по состояниям, возраст самой старой ожидающей доставки, heartbeat воркера и вызовы реестра/SMTP.

Воркер outbox запускается отдельным headless-процессом и не имеет HTTP endpoint. Его heartbeat хранится в общей БД, поэтому API экспортирует время последней отметки. Счётчики SMTP доставки остаются локальными счётчиками процесса: headless-воркер не скрейпится, и его локальные счётчики не попадают в API. Пока scrape идёт с API, SMTP outcome в сводке API равен `null`, а соответствующие Prometheus counters — `NaN`: это означает «неизвестно», а не нулевую активность. HTTP/upstream counters API сбрасываются при перезапуске API; Prometheus counter-rate корректно переживает сброс. Статистика очереди и heartbeat берутся из общей БД.

Dashboard `FSP / FSP operations` включает снимок БД, возраст heartbeat, очередь outbox, старейшее событие, HTTP traffic/5xx, p95 и состояния outbox. Правила предупреждают о недоступном scrape, БД, HTTP 5xx, стареющей очереди и отсутствующем либо устаревшем heartbeat. Thresholds находятся в `infra/monitoring/alerts.yml`.

Метрики используют ограниченные имена route templates, HTTP методы, коды состояния и фиксированные upstream/outcome labels. URL запроса, query-параметры, идентификаторы, email, ИНН и тексты ошибок не включаются.

## Версии и проверка

Образы зафиксированы на Prometheus `v3.15.0` и Grafana `13.2.2` по официальным релизам: [Prometheus 3.15.0](https://github.com/prometheus/prometheus/releases/tag/v3.15.0), [Grafana 13.2.2](https://github.com/grafana/grafana/releases/tag/v13.2.2). Обновляйте теги явным изменением overlay и проверяйте соответствующий официальный changelog.

Для проверки Compose-конфигурации и правил Prometheus:

```sh
docker compose --env-file local/monitoring/compose.env \
  -f compose.yml -f compose.monitoring.yml config --quiet
docker compose --env-file local/monitoring/compose.env \
  -f compose.yml -f compose.monitoring.yml run --rm --no-deps \
  --entrypoint /bin/promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose --env-file local/monitoring/compose.env \
  -f compose.yml -f compose.monitoring.yml run --rm --no-deps \
  --entrypoint /bin/promtool prometheus check rules /etc/prometheus/alerts.yml
(cd infra/monitoring && promtool test rules alerts.test.yml)
```

Реальный scrape появится после запуска основного API и применения миграций V12/V13. Секрет требуется для старта API при включённом файле проверки; не запускайте API overlay до генерации файлов.
