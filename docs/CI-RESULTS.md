# Java 21 — исправления CI и жизненный цикл мандатов

Код: `c35a91e08c9ebb4388937ada1971a5c9bff18761`. [GitHub Actions](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38051899042), 10 октября 2026: **SUCCESS**.

| Проверка | Результат |
|---|---|
| Maven / Java 21 | 89 тестов обнаружено, 88 выполнено, 1 условный ExternalKafkaTest пропущен; 0 failures/errors |
| PostgreSQL / Kafka | Успешно, включая блокировку повторного OOFF, отзыв после CLEAR, отсутствие списания и проекцию REJECTED через outbox/Kafka |
| Миграции | V3 ledger и V4→V5 с существующими платежами; даты/тип коллекции, последняя активность, неиспользованный мандат без выдуманной активности |
| Playwright | 24 проверки прошли |
| Нагрузочные тесты / mTLS / security HTTP e2e | Прошли; SDD: 120 запросов, 8 работников, p95 46 ms, p99 59 ms на H2 |
| Semgrep Community | 6 правил, 6 групп тестовых примеров прошли; 28 файлов приложения, 0 находок, SARIF сохранён |
| Trivy HIGH/CRITICAL | Прошёл |
| CodeQL | Явно пропущен: нужна доступная и включённая возможность Code Scanning и CODEQL_ENABLED=true. Не считается успешной проверкой |

Предыдущий сбой Actions был ошибкой конфигурации CodeQL при загрузке SARIF в приватный репозиторий. Semgrep добавлен как ограниченная блокирующая проверка исходного кода, не эквивалент CodeQL. Ошибки тестов и сканеров не скрываются через continue-on-error.

Новые проверки: гонка двух OOFF, идемпотентный повтор, исправление после Reject, запрет повторного использования после расчёта/refund, права владельца мандата, отзыв до SETTLE, снимки FRST/RCUR и даты XML, 36 календарных месяцев после последнего принятия инструкции. Описание и ограничения: [SDD-MANDATES.md](SDD-MANDATES.md).

**Полное соответствие SEPA/ISO и промышленная готовность не подтверждены.** Метрики — короткая регрессия целостности/времени ответа, не банковский SLA или промышленная ёмкость. [Отчёт](SEPA-ISO-CONFORMANCE.md).

## Предыдущие проверки

# Java 21 — SDD refunds, disputes and recovery

Код: `ddbe24ae0fa2a2b104eca6d77826b6692b683d10`.
[GitHub Actions](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38048465852), 10 октября 2026.

| Проверка | Результат |
|---|---|
| Maven / JDK 21 / compiler release 21 | Успешно: 82 теста обнаружено, 81 выполнено, 1 условный ExternalKafkaTest пропущен; 0 failures/errors |
| PostgreSQL / Kafka Testcontainers | Успешно: возврат, частичное взыскание, миграции, outbox/consumer; отдельный реальный контейнерный тест |
| Playwright / HTTP demo smoke | Успешно: 24 браузерные проверки; возврат клиента, неавторизованный SDD, независимое расследование, взыскание |
| SDD load regression | 120 запросов / 8 работников / 12 списаний / 10 попыток на платёж; p95 40 ms, p99 51 ms; H2, короткий тест |
| Существующая HTTP load regression / mTLS / security e2e | Успешно |
| Trivy HIGH/CRITICAL vuln/secret/misconfig | Успешно |
| CodeQL | Завершился configuration failure: Code scanning is not enabled for this repository. Не засчитывается как успешная проверка |

Отдельно проверены: пустой или зарезервированный счёт кредитора; календарные границы 8 недель/13 месяцев; високосный февраль; дата валютирования; однократное зачисление при параллельных повторах; отдельное частичное взыскание; self-review и MFA; полное rollback при ошибке аудита; обновление существующего ledger с V3 на V4.

Метрики [sdd-performance-results.json](sdd-performance-results.json) — регрессия финансовой целостности и времени ответа, не промышленная ёмкость/SLA. CI upload сохраняет результаты обоих нагрузочных тестов, JUnit/JaCoCo и браузерные скриншоты, включая кабинет оператора.

**Полное соответствие SEPA и промышленная готовность не подтверждены.** Реализация: [SDD-REFUNDS.md](SDD-REFUNDS.md); ограничения: [SEPA-ISO-CONFORMANCE.md](SEPA-ISO-CONFORMANCE.md).

## Предыдущие проверки

# Java 21 — проверки после аудита SEPA/ISO

Код: `6f8bc26900769cdedec3f2333a9a4a0d953eabbd`.
[GitHub Actions](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38046761126), 10 октября 2026.

| Проверка | Результат |
|---|---|
| Maven / JDK 21 / compiler release 21 | Успешно: 64 теста обнаружено, 63 выполнено, 1 условный ExternalKafkaTest пропущен; 0 failures/errors |
| PostgreSQL / Kafka Testcontainers | Успешно; отдельный реальный контейнерный тест, несмотря на пропуск условного внешнего Kafka |
| Playwright / HTTP demo smoke | Успешно: 16 браузерных проверок и пять демо-клиентов |
| Нагрузочная регрессия / mTLS / security e2e | Успешно; это не промышленная оценка ёмкости или независимый pentest |
| Trivy HIGH/CRITICAL vuln/secret/misconfig | Успешно |
| CodeQL | Завершился ошибкой конфигурации: Code scanning is not enabled for this repository. Code scanning для приватного репозитория отключён; проверка не засчитывается |

Новые проверки: настоящие сохранённые реквизиты мандата, отказ экспорта без них, отказ неполных/будущих реквизитов, неизменная millisecond acceptance timestamp SCT Inst, идентификаторы со слешами, граница имени 70 символов, контрольные суммы семи ISO XSD.

Первый прогон аудита выявил несовпадение хешей из-за CRLF/LF при публикации. Исправлено: оригинальный `sha256` и `storedSha256` различаются явно, сохранённые XSD имеют LF. Повторный тест целостности прошёл.

**Полное соответствие SEPA не подтверждено.** Требования, доказательства и пробелы: [SEPA-ISO-CONFORMANCE.md](SEPA-ISO-CONFORMANCE.md); машиночитаемый статус: [sepa-iso-assessment.json](sepa-iso-assessment.json).

## Предыдущая проверка Java 21

Код: `0102a73a6ee6d5b91227df846e19d3278c3cb033`.
[GitHub Actions](https://github.com/straigenlu-netizen/eu_fin/actions/runs/38043507242), 10 октября 2026.

| Проверка | Результат |
|---|---|
| Maven, compiler release 21, JDK 21 | Успешно: 53 теста обнаружено, 52 выполнено, 1 условный ExternalKafkaTest пропущен; 0 failures/errors |
| PostgreSQL 16.6 + Kafka 3.9, Testcontainers | Успешно: миграции, ledger, outbox, broker, consumer, JPA projection |
| Playwright | Успешно: 16 проверок, настоящий UI/backend, IdP endpoints mocked |
| Demo HTTP smoke | Успешно: 5 VERIFIED клиентов, по одному счёту с 1000 EUR |
| Trivy vuln/secret/misconfig HIGH/CRITICAL | Успешно |
| CodeQL | Прогон блокирован: GitHub сообщает «Code scanning is not enabled for this repository». Успех CodeQL не заявляется; требуется настройка этой функции в GitHub |

Проект использует только Java 21: compiler release 21, CI JDK 21, Docker build/runtime 21. Старые Java 17 отчёты относятся к предыдущим ревизиям.

Исправлены причины первых неуспешных прогонов: Trivy action tag; Kafka 3.9 effective controller listener; Instant параметры PostgreSQL JDBC; уязвимые зависимости; pod security context и writable root filesystem.

Используются Spring Boot 3.5.16, PostgreSQL JDBC 42.7.14, Spring Kafka 3.3.16 и Trivy action v0.36.0. Версии проверены по первичным repository refs/releases. HIGH/CRITICAL gate сохранён; замечания не скрывались allowlist или отключением сканера.

Полный Compose с живым Keycloak, Schema Registry и Kafka Streams, deployment и реальная платёжная сеть всё ещё не проверены. Browser IdP mock не подтверждает настоящее SCA/MFA. Независимый pentest и подтверждение всех применимых SEPA/ISO scheme rules отсутствуют: обязательные критерии промышленной приёмки в [PRODUCTION-READINESS.md](PRODUCTION-READINESS.md) остаются открытыми.

Первичный локальный отчёт и нагрузочные метрики сохранены отдельно; они не доказывают промышленную ёмкость.
