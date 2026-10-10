# GitHub Actions — результаты перед переходом на Java 21 only

Проверенный код: `952d981f27752ea1e3ac42bf67e972ffd66b89f2`.
[Прогон](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38043188693), 10 октября 2026.

| Проверка | Результат |
|---|---|
| JDK 17, Maven containers verify | Успешно: 53 теста обнаружено, 52 выполнено, 1 условный ExternalKafkaTest пропущен; 0 failures/errors |
| JDK 21, Maven containers verify | Успешно с тем же набором |
| PostgreSQL 16.6 + Kafka 3.9, Testcontainers | Настоящие контейнеры, миграции, деньги, outbox, broker, consumer и JPA projection прошли |
| Playwright | Успешно: 16 проверок; настоящие UI/backend, только IdP endpoints mocked |
| Demo HTTP smoke | Успешно: 5 VERIFIED клиентов, по одному счёту с 1000 EUR |
| Trivy vuln/secret/misconfig HIGH/CRITICAL | Успешно после исправлений |
| CodeQL | На момент сохранения отчёта выполняется; успех не заявлен. Предыдущие прогоны показывали отсутствие настройки Code scanning для private repository |

Исправлены причины первых неуспешных прогонов: неверный Trivy action tag; Kafka 3.9 effective controller listener; прямые Instant параметры, не поддерживаемые PostgreSQL JDBC; устаревшие Spring Boot/PostgreSQL JDBC/Spring Kafka; pod security context и writable root filesystem.

Используются Spring Boot 3.5.16, PostgreSQL JDBC 42.7.14, Spring Kafka 3.3.16 и Trivy action v0.36.0. Даты и версии проверены по первичным repository refs/releases. HIGH/CRITICAL gate сохранён; уязвимости не скрывались allowlist или отключением сканера.

Полный Compose с живым Keycloak, Schema Registry и Kafka Streams, deployment и реальная платёжная сеть всё ещё не проверены. Browser IdP mock не подтверждает настоящее SCA/MFA. Независимый pentest и подтверждение применимых SEPA/ISO scheme rules отсутствуют: критерии обязательной промышленной приёмки в PRODUCTION-READINESS.md остаются открытыми.

Первичный локальный отчёт и нагрузочные метрики сохранены отдельно; они не заменяют приведённый remote CI и не доказывают промышленную ёмкость.

Следующая ревизия устанавливает compiler release 21 и CI только JDK 21. Повторный прогон обязателен; приведённая таблица относится к указанному выше commit.
