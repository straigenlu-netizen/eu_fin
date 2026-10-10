# Первичный локальный отчёт — 10 октября 2026

Актуальные удалённые проверки: см. [CI-RESULTS.md](CI-RESULTS.md). Следующие ограничения относятся к первоначальной локальной среде; PostgreSQL/Kafka и browser затем успешно проверены на GitHub.

Проверки выполнялись на JDK 17. Сборка Maven `-Pperformance verify` завершилась успешно: **51 выполненный тест, 0 ошибок, 0 падений**. Условный ExternalKafkaTest в этом прогоне пропущен и отдельно успешно выполнен с реальным Kafka 3.9.0. Итого **52 различных выполненных JUnit-теста**. Последнее дополнение к проверке очереди исправлений личности отдельно прошло в CustomerSecurityE2ETest (7 тестов). После этого приложение заново упаковано.

| Набор | Выполнено | Что проверяется |
|---|---:|---|
| RulesTest | 13 | IBAN, суммы, маршруты, банковские правила |
| BankIntegrationTest | 21 | Проводки, резервы, возвраты, мандат, конкурентность, ISO XSD, XXE, inbox/outbox, payee и worker |
| CustomerSecurityE2ETest | 7 | HTTP роли, encrypted PII, masked staff view, изменения личности, mass assignment, версии, MFA и headers |
| HttpE2ETest | 2 | Реальные HTTP запросы с JWT и отказные сценарии |
| MtlsE2ETest | 1 | Реальный HTTPS, сертификаты, отказ без client certificate, дополнительный JWT |
| ProfileCipherTest | 2 | GCM integrity и subject AAD |
| RateLimitE2ETest | 1 | HTTP rate limit и Retry-After |
| TopologyTest | 1 | Kafka Streams состояние/статистика и повтор события |
| CircuitBreakerTest | 1 | Открытие/переход breaker |
| ApiContractTest | 1 | Соответствие методов/путей OpenAPI контроллерам |
| PerformanceTest | 1 | HTTP нагрузка и сохранение идемпотентности |
| ExternalKafkaTest, отдельно | 1 | Настоящий Kafka broker, committed outbox и JPA projection |

Дополнительно пройден HTTP smoke собранного приложения с demo-data: ровно пять клиентов, у каждого ожидаемое имя, VERIFIED и один счёт с 1000 EUR. JS проверен `node --check`; Python scripts скомпилированы. Браузерный smoke не подменяет этот факт: проверялся именно API.

## Покрытие

JaCoCo последнего полного verify: 994 из 1166 строк (**85,25%**), 271 из 417 ветвей (**64,99%**). Покрытие не является доказательством безопасности, полноты правил или готовности к production. В CI пока нет обязательного coverage threshold.

## Нагрузка

Файл `performance-results.json`: 200 запросов, 8 параллельных исполнителей, 80% чтение зашифрованного профиля / 20% повторные команды платежа. H2 in-memory, один JVM, короткий локальный прогон. p50 **7 мс**, p95 **15 мс**, p99 **33 мс**, около **895 запросов/с** за **0,223 с**.

Порог регрессии теста — p95 < 2000 мс; дополнительно проверяются один созданный платёж и один резерв при повторах. Это не sustained/soak benchmark, не тест сетевой задержки SEPA и не прогноз ёмкости. Для решения о мощности нужны PostgreSQL, реальные адаптеры, несколько экземпляров, продолжительная нагрузка, больший датасет и наблюдение CPU/GC/locks/lag.

## Подготовлены, но здесь не выполнялись

- PostgresKafkaTest с Testcontainers: Docker отсутствует. Отдельный запуск PostgreSQL также ограничен UID-моделью среды.
- Playwright browser suite: Chromium отсутствует, загрузка с CDN заблокирована. Автоматические сценарии и отдельный CI job сохранены; результат не заявляется.
- Полный Compose с Keycloak, PostgreSQL, Registry и Kafka Streams; Kubernetes deployment.
- GitHub Actions, JDK 21 build, CodeQL и Trivy: удалённый репозиторий ещё не создан, workflow не запускался.
- Independent pentest, реальная PKI, KYC/AML/VOP и сертификация платежных схем.

Чтобы воспроизвести: `./mvnw -Pperformance verify`; с Docker `./mvnw -Pcontainers verify`; браузер — по инструкции e2e. Отдельный Kafka test: запустить Kafka на доступном `127.0.0.1:19092`, затем `EUFIN_EXTERNAL_KAFKA_TEST=true ./mvnw -Dtest=ExternalKafkaTest test`; другой адрес задаётся `EUFIN_TEST_KAFKA`.
