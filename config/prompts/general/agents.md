# Instructions for AI Assistant: CRM-система

## Проект
Enterprise CRM для B2B-клиентов. Управление customers, deals, contacts.
Микросервисная архитектура с event-driven communication.

## Стек
- Java 21, Spring Boot 3.2, Spring Data JPA
- PostgreSQL 16, Redis 7 (cache), Kafka (events)
- Docker, Kubernetes, Jenkins CI/CD
- JUnit 5, Mockito, Testcontainers

## Архитектура
- Hexagonal Architecture: domain → application → infrastructure
- Constructor injection (без @Autowired на полях)
- Все публичные API версионированы (/api/v1/...)
- События между сервисами через Kafka

## Code Standards
- Google Java Style Guide, Checkstyle в CI
- Покрытие тестами > 80% (JaCoCo)
- Structured logging через SLF4J + Logback

## Типовые задачи для ИИ
- Code review (транзакции, N+1, error handling)
- Генерация JUnit 5 тестов
- Оптимизация JPA-запросов
- Рефакторинг legacy-кода

## Ограничения
- Не менять публичные API без согласования
- Не добавлять зависимости вне Spring Boot starters
- Liquibase-миграции должны быть reversible
