# Объяснение сложного концепта

Пример для Java: Virtual Threads (Project Loom) — это механизм легковесных потоков в Java 21. Типичная проблема: использование блокирующих операций в pinned carrier thread.

**Промпт может выглядеть так:**

```text
Контекст: Java 21, Spring Boot 3.2, микросервис CRM.

Задача: Объясни, как работают Virtual Threads на уровне внутреннего устройства:
1. Как устроен scheduling virtual threads на carrier threads
2. Почему synchronized блоки и native calls «пиннят» carrier thread
3. Как правильно интегрировать virtual threads в Spring Boot (spring.threads.virtual.enabled)

Формат:
- Кратко (3–4 параграфа) без академического стиля
- С практическими примерами кода
- Частые ошибки и способы их избежать

Ограничения: фокус на Java 21 + Spring Boot 3.2, без сравнения с Kotlin coroutines.
```
