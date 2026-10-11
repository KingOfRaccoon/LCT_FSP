# Подготовка серверной записи прокторинга

По умолчанию запись выключена; для явно разрешённого HTTPS-стенда `hirundo-team.space` действует настройка запуска, описанная ниже. Файлы попадают в отдельный именованный Docker-том `proctoring-media`, смонтированный только в API по адресу `/var/lib/fsp/proctoring`; Nginx этот том не видит. Лимит одной части — 4 МиБ, одной сессии — 256 МиБ. Срок в семь дней — технический параметр хранения, а не заключение о правовом основании или достаточности срока.

Для локальной разработки можно оставить HTTP на localhost. Для публичного IP серверная запись должна включаться только после настройки HTTPS и проверки writable private storage. TLS-overlay требует уже выпущенный доверенный сертификат; без него обычный HTTP compose продолжает работать, а HTTPS overlay запускать нельзя.

## Текущий IP-стенд

Публичный адрес стенда: `158.160.223.177`. Для IP-сертификата Let’s Encrypt необходим Certbot 5.4 или новее, `webroot` challenge и короткоживущий профиль сертификата. Сертификат действует шесть дней, поэтому обновление нужно запускать автоматически несколько раз в день. Certbot сам не устанавливает такой сертификат в Nginx; deploy hook ниже сначала проверяет Nginx-конфигурацию с новым сертификатом и лишь затем делает graceful reload.

Certbot и публичный ACME endpoint здесь не запускаются. Оператор сервера должен предоставить управляемый адрес электронной почты, доступ к серверу и возможность направить внешний порт 80 на bootstrap Nginx, а порт 443 — на TLS Nginx.

1. Настроить на сервере действительные `TLS_EMAIL`, `TLS_HOST=158.160.223.177`, `TLS_LETSENCRYPT_DIR=./local/tls/letsencrypt`, `TLS_ACME_WEBROOT=./local/tls/webroot`, `TLS_BIND_ADDRESS=0.0.0.0`, `TLS_HTTP_PORT=80` и `TLS_HTTPS_PORT=443`. Не копировать placeholder из `.env.production.example` как рабочий секрет.
2. До запуска проверить владельцев портов: `sudo ss -ltnp '( sport = :80 or sport = :443 )'` и `docker compose ps`. Если 80 или 443 уже заняты, не останавливать найденный процесс и не запускать overlay с теми же host-портами. Сначала выяснить у оператора, управляет ли публичным трафиком внешний reverse proxy. Если это так, оператор должен настроить ACME challenge и HTTPS-переадресацию к выбранным `TLS_HTTP_PORT`/`TLS_HTTPS_PORT`; `TLS_BIND_ADDRESS=127.0.0.1` и свободные внутренние порты позволяют избежать прямого конфликта, но сами по себе не настраивают этот ingress. Если маршрут и владение портами неизвестны, здесь нужно остановиться: проверить текущий маршрут без доступа к серверу невозможно.
3. Проверить локальные условия командой `infra/proctoring-tls.sh check`. Создать каталог webroot: `mkdir -p local/tls/webroot/.well-known/acme-challenge`.
4. Запустить временный HTTP-only ingress без зависимости от сертификата: `docker compose -f compose.yml -f infra/compose.proctoring-acme-bootstrap.yml up -d --build web`. Он обслуживает ACME challenge на порту `TLS_HTTP_PORT` (по умолчанию 80); API и обычный стенд остаются на текущих настройках, запись прокторинга выключена.
5. Создать временный файл в `local/tls/webroot/.well-known/acme-challenge/` и проверить его с внешней сети через `curl -fsS http://158.160.223.177/.well-known/acme-challenge/<имя>`. При использовании существующего ingress проверить публичный путь через него. До успешного теста не запрашивать сертификат.
6. После успешного внешнего challenge-теста явно запросить сертификат: `infra/proctoring-tls.sh issue`. Скрипт читает `TLS_HOST`, `TLS_EMAIL`, `TLS_LETSENCRYPT_DIR` и `TLS_ACME_WEBROOT` из серверного `.env`; команда обращается к публичному удостоверяющему центру и принимает его условия от имени указанного оператора.
7. Переключить конфигурацию без `compose down`: `docker compose -f compose.yml -f infra/compose.proctoring-acme-bootstrap.yml stop web`, затем `docker compose -f compose.yml -f infra/compose.proctoring-tls.yml up -d --build web api`. TLS overlay публикует `TLS_HTTP_PORT`/`TLS_HTTPS_PORT` (по умолчанию 80/443), перенаправляет обычный HTTP на HTTPS и оставляет ACME challenge доступным. Он включает secure cookies, но сохраняет `FSP_PROCTORING_ENABLED=false` по умолчанию.
8. Добавить системный таймер, выполняющий `infra/proctoring-tls.sh renew` каждые 6 часов. После успешного обновления deploy hook запускает `nginx -t`; reload выполняется только если проверка проходит.
9. Проверить с внешней сети сертификат, HTTP→HTTPS redirect, secure cookie, `/api/health` и `/api/v1/proctoring/config`. API должен сообщить недоступность функции, если secure origin или закрытый записываемый каталог не подтверждены.

TLS и активация записи — отдельные настройки. После утверждения текущего текста согласия и процесса обработки оператор может явно задать `FSP_PROCTORING_ENABLED=true` в серверном `.env` и применить `docker compose -f compose.yml -f infra/compose.proctoring-tls.yml up -d api`. В synthetic stand это разрешено для E2E-проверки после отдельного явного включения; реальное использование требует готовности согласия и процесса. Даже при включённом флаге API должен считать функцию недоступной, если HTTPS origin или записываемое private media-root не подтверждены.

Настройка той же TLS-конфигурации в уже существующем внешнем reverse proxy требует согласованной серверной конфигурации и не может считаться завершённой только по файлам этого репозитория.

## Активация на HTTPS-стенде

Для существующего стенда `https://hirundo-team.space` команда API в `compose.yml` запускает `infra/run-api.sh`. Launcher добавляет `--fsp.proctoring.enabled=true` и `--fsp.session-cookie-secure=true` только при точном `PUBLIC_ORIGIN=https://hirundo-team.space` или с одним завершающим `/`. Эти параметры Spring имеют приоритет над переменными окружения `FSP_PROCTORING_ENABLED` и `SESSION_COOKIE_SECURE`, включая явное значение `false`. Для HTTP, localhost, другого домена и пустого `PUBLIC_ORIGIN` остаются прежние аргументы запуска. Worker запускается прежней командой.

Изменение попадает на стенд через существующий автодеплой ветки `prototype`. После пересоздания API проверьте снаружи по HTTPS, что `/api/v1/proctoring/config` сообщает `available=true`, а новая auth cookie содержит атрибут `Secure`. Если доступность остаётся `false`, проверьте состояние private media-root, TLS origin и writable volume: launcher не обходит эти серверные проверки. Для отката сделайте Git revert коммита активации в `prototype`; тот же автодеплой восстановит прежний запуск API. Изменение не меняет TLS, публичные порты, тома, политику оценки или согласия.

## Проверка конфигурации

`docker compose -f compose.yml config` проверяет обычный запуск. Bootstrap проверяется `docker compose -f compose.yml -f infra/compose.proctoring-acme-bootstrap.yml config`. После появления сертификата `docker compose -f compose.yml -f infra/compose.proctoring-tls.yml config` проверяет TLS overlay. Nginx TLS config проверяется контейнерной командой `nginx -t` при продлении до reload.

Никакая публичная политика согласия, правовая формулировка, срок хранения либо серверный отзыв доступа не утверждаются этой инфраструктурой. До реального использования оператор должен утвердить содержание процесса и получить доступ к защищённому серверу.

Официальные инструкции Let’s Encrypt: [IP-сертификаты Certbot](https://letsencrypt.org/2026/03/11/shorter-certs-certbot).
