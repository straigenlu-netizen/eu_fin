# Требования вакансии и честная карта проекта

| Требование | Что можно показать | Что остаётся |
|---|---|---|
| Java 21, Spring Boot 3 | Сборка release 21, CI JDK 21, REST, DI | Текущий проект требует только Java 21; предыдущий прогон JDK 21 прошёл |
| Spring Data/Hibernate/JPA | Read model через JPA; command SQL + Spring transactions | Другие database vendors не проверены |
| OAuth2/OIDC/JWT/mTLS | Resource server, PKCE UI, Keycloak config, реальные JWT/HTTPS tests | Полный Keycloak login здесь не прогонялся |
| Microservices, gateway, circuit breaker | Границы модулей, nginx template, circuit breaker | Независимые сервисы и gateway deployment отсутствуют |
| Saga/outbox/CQRS/event sourcing | Payment lifecycle, компенсация, durable outbox/inbox, projection | Нет полноценного event sourcing и распределённого saga orchestrator |
| Kafka/partitioning/Streams/registry | Реальный broker test, key-based partition, Streams topology, schema registration | Registry и Streams cluster compose здесь не запускались |
| Contract-first OpenAPI | Спецификация API и route coverage test | Нет Pact/Spring Cloud Contract consumer suites |
| ISO 20022 pain/pacs/camt | Официальные XSD, импорт подмножества, XML export tests | Нет всех сообщений и usage guidelines сети |
| SWIFT MT/MX | Ограниченный MT103 preview | Не CBPR+/FIN migration |
| SEPA SCT/Inst/SDD, TIPS/RT1 | Правила учебного EUR ledger, сроки, мандат; независимый SDD refund, 13-month claims, расследование и отдельное взыскание | Реальные подключения/сертификация отсутствуют |
| EU regulation | Объяснение в BANKING и SECURITY | Не юридическое compliance подтверждение |
| AI/spec-driven | Документированные инварианты, OpenAPI, тесты и ограничения AI | Нет отдельной платформы генерации/контроля AI |
| Database/locking/migrations | SQL, транзакции, ordered locks, Flyway, Postgres container test | Postgres/Kafka test прошёл в CI; Oracle/MS SQL нет |
| Docker/Kubernetes/CI | Compose, Dockerfile, Kubernetes template, GitHub Actions | Инфраструктура здесь не развёрнута |
| Quality/testing/security | JUnit, Mockito, HTTP e2e, TLS, load regression, JaCoCo | Docker/browser suites прошли в CI; независимого pentest нет |
| Scheduling | Spring scheduler, expiry worker, shell/python lab tools | UC4/AutoSys/Control-M не подключены |

На интервью ценнее объяснить инварианты, failure modes и границы, чем утверждать, что все названия из вакансии уже реализованы. Это проект для подготовки, а не замена hands-on опыта промышленного payments-проекта.
