# Java 21 — актуальные проверки

Код: `0102a73a6ee6d5b91227df846e19d3278c3cb033`.
[GitHub Actions](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38043507242), 10 октября 2026.

| Проверка | Результат |
|---|---|
| Maven, compiler release 21, JDK 21 | Успешно: 53 теста обнаружено, 52 выполнено, 1 условный ExternalKafkaTest пропущен; 0 failures/errors |
| PostgreSQL 16.6 + Kafka 3.9, Testcontainers | Успешно: миграции, ledger, outbox, broker, consumer, JPA projection |
| Playwright | Успешно: 16 проверок, настоящий UI/backend, IdP endpoints mocked |
| Demo HTTP smoke | Успешно: 5 VERIFIED клиентов, по одному счёту с 1000 EUR |
| Trivy vuln/secret/misconfig HIGH/CRITICAL | Успешно |
| CodeQL | Текущий прогон ещё выполняется. Предыдущий блокирован: GitHub сообщает «Code scanning is not enabled for this repository». Успех CodeQL не заявляется; требуется настройка этой функции в GitHub |

Проект использует только Java 21: compiler release 21, CI JDK 21, Docker build/runtime 21. Старые Java 17 отчёты относятся к предыдущим ревизиям.

Исправлены причины первых неуспешных прогонов: Trivy action tag; Kafka 3.9 effective controller listener; Instant параметры PostgreSQL JDBC; уязвимые зависимости; pod security context и writable root filesystem.

Используются Spring Boot 3.5.16, PostgreSQL JDBC 42.7.14, Spring Kafka 3.3.16 и Trivy action v0.36.0. Версии проверены по первичным repository refs/releases. HIGH/CRITICAL gate сохранён; замечания не скрывались allowlist или отключением сканера.

Полный Compose с живым Keycloak, Schema Registry и Kafka Streams, deployment и реальная платёжная сеть всё ещё не проверены. Browser IdP mock не подтверждает настоящее SCA/MFA. Независимый pentest и подтверждение всех применимых SEPA/ISO scheme rules отсутствуют: обязательные критерии промышленной приёмки в [PRODUCTION-READINESS.md](PRODUCTION-READINESS.md) остаются открытыми.

Первичный локальный отчёт и нагрузочные метрики сохранены отдельно; они не доказывают промышленную ёмкость.
