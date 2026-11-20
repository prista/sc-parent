# API Gateway (`api-gateway`)

## Что это

Spring Cloud Gateway реализует API Gateway — единую точку входа (entrypoint) для всех наших сервисов: REST, WebSocket, веб-приложений, а также gRPC (через фильтр `JsonToGrpc`). Шлюз — единственный публичный компонент: он проксирует вызовы во внутренние сервисы, которые сами наружу не выставлены.

По идее это близко к реверсивному проксированию, которое заложено в веб-серверы вроде Nginx, но с программируемыми фильтрами (аутентификация, rate limiting, retry, circuit breaker) на уровне Spring.

## Запуск и проверка

Модуль `api-gateway` слушает порт `8086`. Отдельный профиль ему пока не нужен — вся конфигурация в `application.yml`.

1. `catalogue-service` с профилем `standalone` (порт `8081`):

   ```bash
   SPRING_PROFILES_ACTIVE=standalone ./mvnw -pl catalogue-service spring-boot:run
   ```

2. Гейтвей (без профиля):

   ```bash
   ./mvnw -pl api-gateway spring-boot:run
   ```

3. Swagger UI `catalogue-service` через гейтвей — рабочий URL:

   ```text
   http://catalogue.api.sandbox.drm.com.127.0.0.1.nip.io:8086/swagger-ui/index.html#/
   ```

## Маршруты и `Host`-предикат

- Хостнеймы построены на `nip.io`: `catalogue.api.sandbox.drm.com.127.0.0.1.nip.io` и `feedback.api.sandbox.drm.com.127.0.0.1.nip.io` резолвятся в `127.0.0.1`, поэтому править `/etc/hosts` не нужно.
- **Предикат `Host` в SCG 5.0 сравнивает заголовок вместе с портом** (`HostRoutePredicateFactory.includePort = true`, начиная с 4.2). Браузер шлёт `Host: ...nip.io:8086`, поэтому в паттерне порт обязателен: `Host=catalogue.api.sandbox.drm.com.127.0.0.1.nip.io:8086`. Конфиг ментора (`SelMag-sc24`, SCG 4.1) без порта здесь не сработает и даст `404` от гейтвея.
- Ключ конфигурации в SCG 5.0 — `spring.cloud.gateway.server.webflux.routes` (старый `spring.cloud.gateway.routes` не действует).
- Артефакт зависимости — `spring-cloud-starter-gateway-server-webflux`: `spring-cloud-starter-gateway` переименован в Spring Cloud `2025.1`.
