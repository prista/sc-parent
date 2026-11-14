Контекст:
CRM-система для B2B-клиентов.
Существующие сервисы: CustomerService, DealService, ContactService.
Стек: Java 21, Spring Boot 3.2, Spring Data JPA, PostgreSQL, Kafka.
Архитектура: Hexagonal Architecture с разделением на domain, application, infrastructure.

Задача:
Спроектируй новый сервис NotificationService для отправки уведомлений клиентам.

Требования:
- Поддержка email и SMS каналов
- Шаблонизация сообщений (Thymeleaf)
- Асинхронная отправка через Kafka
- Отслеживание статуса доставки

Формат:
1. Структура пакетов (domain, application, infrastructure)
2. Основные интерфейсы и классы
3. REST API эндпоинты
4. События Kafka (publish/consume)
5. Схема таблиц БД

Ограничения:
- Использовать существующий Kafka-кластер.
- Не добавлять новые внешние зависимости кроме Spring Boot стартеров.
- Интеграция с существующим CustomerService через внутренний API.