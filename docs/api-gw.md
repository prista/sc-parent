# API Gateway (`api-gateway`)

## Что это

Spring Cloud Gateway реализует API Gateway — единую точку входа (entrypoint) для всех наших сервисов: REST, WebSocket, веб-приложений, а также gRPC (через фильтр `JsonToGrpc`). Шлюз — единственный публичный компонент: он проксирует вызовы во внутренние сервисы, которые сами наружу не выставлены.

По идее это близко к реверсивному проксированию, которое заложено в веб-серверы вроде Nginx, но с программируемыми фильтрами (аутентификация, rate limiting, retry, circuit breaker) на уровне Spring.

## Запуск и проверка

Модуль `api-gateway` слушает порт `8086` и, как и бизнес-сервисы, имеет три самодостаточных профиля:

| Профиль | Файл | Маршруты | Eureka |
|---|---|---|---|
| `standalone` | `application-standalone.yml` | `http://127.0.0.1:8081`, `http://127.0.0.1:8084` | выключена (`eureka.client.enabled: false`) |
| `cloud` | `application-cloud.yml` | `lb://selmag-catalogue-service`, `lb://selmag-feedback-service` | клиент включён, `fetch-registry: true`, но `register-with-eureka: false` |
| `cloudconfig` | `application-cloudconfig.yml` + `config/cloud/selmag-api-gateway/cloudconfig/…` | из config-server (`lb://…`) | из config-server |

В `cloudconfig` локально заданы только порт, имя приложения и адрес config-server (`http://config:config-password@localhost:8888`); маршруты, security, management и Eureka приходят из удалённого файла.

В Eureka гейтвей **не регистрируется**: он entrypoint, по service-id его никто не вызывает — нужен только чтение реестра для `lb://`. Поэтому блок `eureka.instance` (lease, `prefer-ip-address`) не нужен.

`lb://` здесь — штатный механизм гейтвея (`ReactiveLoadBalancerClientFilter`): правило проекта «`http://<service-id>`, никогда `lb://`» относится к `RestClient`/`WebClient` бизнес-сервисов с `DeferringLoadBalancerInterceptor`, а не к маршрутам SCG.

1. `catalogue-service` с профилем `standalone` (порт `8081`):

   ```bash
   SPRING_PROFILES_ACTIVE=standalone ./mvnw -pl catalogue-service spring-boot:run
   ```

2. Гейтвей (порт `8086`):

   ```bash
   # standalone: прямые адреса на localhost
   SPRING_PROFILES_ACTIVE=standalone ./mvnw -pl api-gateway spring-boot:run

   # cloud: сервисы находятся через Eureka (нужен запущенный eureka-server)
   SPRING_PROFILES_ACTIVE=cloud ./mvnw -pl api-gateway spring-boot:run
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

## Что маршрутизируем (и что нет)

Гейтвей здесь — **API gateway**: через него идут только REST-сервисы — `catalogue-service` и `feedback-service`.

`manager-app` и `customer-app` намеренно не маршрутизируются — это самостоятельные entrypoint'ы для браузера, а не сервисы, которые кто-то вызывает по service-id:

- у них своя аутентификация (`oauth2Login`, `ROLE_MANAGER` / `authenticated()`), сессии, CSRF и Thymeleaf — гейтвей ничего к этому не добавляет;
- их публичный адрес зашит в Keycloak (`http://localhost:8080/*`, `http://localhost:8083`); за гейтвеем пришлось бы менять redirect URI/root URL, `forward-headers`/`PreserveHostHeader` и cookie-домен — это уже reverse proxy для UI;
- их вызовы к catalogue/feedback и так идут напрямую по service-id, минуя гейтвей.

## Логи

Как и у остальных сервисов — `logback.xml` с `Loki4jAppender` (зависимость `com.github.loki4j:loki-logback-appender` 2.0.3). Appender включается, только когда определён `LOKI` (например, `LOKI=http://localhost:3100`), и пишет в `${LOKI}/loki/api/v1/push` с метками `app = selmag-api-gateway`, `host`, `level`. Проверено: записи с `app="selmag-api-gateway"` появляются в Loki (батчи уходят с задержкой до 60 с — `batchTimeout` по умолчанию).

## Eureka без безопасности

В отличие от проекта ментора (`DiscoveryBeans` с `client_credentials`-токеном для транспорта Eureka), у нас `eureka-server` намеренно неаутентифицирован. Поэтому гейтвею достаточно `spring-cloud-starter-netflix-eureka-client`: никаких `oauth2-client`-регистраций, токенов и `DiscoveryBeans` нет — в профиле `cloud` добавляется только блок `eureka.*`. Закрывать реестр OAuth2 в локальном окружении — лишняя сложность: реестр не публикуется наружу, а токен для инфраструктуры только усложняет запуск.

## Метрики

По аналогии с остальными сервисами:

- `spring-boot-starter-actuator` + `micrometer-registry-prometheus` → `/actuator/prometheus`;
- `SecurityConfig` — отдельная `SecurityWebFilterChain` с `securityMatcher("/actuator/**")`: reactive resource server (JWT, `issuer-uri` Keycloak) и `SCOPE_metrics`. Бизнес-маршруты цепочка не трогает — токен проверяют downstream-сервисы. Это тот же приём, что у `customer-app`, но без UI-цепочки;
- job `selmag_api_gateway` в `config/victoria-metrics/promscrape.yaml` (target `host.docker.internal:8086`, `client_credentials`, scope `metrics`).

Проверено: `/actuator/prometheus` → `401` без токена, `403` без scope `metrics`, `200` с токеном; в выдаче есть `spring_cloud_gateway_requests_seconds_*` с метками `routeId`/`routeUri`.

`api-gateway` — Spring Boot Admin client только в профиле `standalone` (клиент `api-gateway-metrics-client`, scope `metrics_server`; регистрация образца `feedback-service`). В `cloud` SBA выключен (`spring.boot.admin.client.enabled: false`), oauth2-регистрации для регистрации нет.
