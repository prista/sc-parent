# Режим Eureka: запуск сервисов с профилем `cloud`

Практическая инструкция по запуску проекта с service discovery (Spring Cloud Netflix Eureka). Архитектура — в `../CLAUDE.md`, локальный запуск без Eureka — в `../README.MD`, наблюдаемость (метрики/логи/трейсы) — в `MONITORING.md`.

## Главное правило

При включённой Eureka профиль `cloud` получают **только четыре бизнес-сервиса**. `eureka-server` и `admin-server` запускаются с профилем `standalone` — другого профиля у них нет.

| Модуль | Профиль запуска | Порт | В Eureka | Роль |
|---|---|---|---|---|
| `eureka-server` | `standalone` (единственный) | 8761 | сервер реестра, сам не регистрируется | discovery |
| `catalogue-service` | **`cloud`** | 8081 | да, `selmag-catalogue-service` | REST API |
| `feedback-service` | **`cloud`** | 8084 | да, `selmag-feedback-service` | REST API |
| `manager-app` | **`cloud`** | 8080 | да, `selmag-manager-app` | админский UI |
| `customer-app` | **`cloud`** | 8083 | да, `selmag-customer-app` | storefront |
| `admin-server` | `standalone` (единственный) | 8085 | **нет, намеренно** | Spring Boot Admin |

Почему так:

- **`eureka-server`** — сам реестр; ему некуда регистрироваться, cloud-профиль отсутствует по определению. Запускается со своим единственным `application-standalone.yml`.
- **`admin-server`** — Spring Boot Admin-сервер, который сознательно оставлен вне Eureka: SBA-клиенты (`catalogue-service`, `feedback-service`) регистрируются в нём напрямую по `spring.boot.admin.client.url: http://localhost:8085`, а не через реестр. Cloud-профиля у модуля нет, Eureka-зависимости в `pom.xml` тоже.
- **Четыре бизнес-сервиса** переключаются на `cloud`, чтобы регистрироваться в Eureka и находить друг друга по service-id.

## Порядок запуска

1. Инфраструктура в Docker: Keycloak (`8082`), PostgreSQL (`5432` для `catalogue`, `5433` для `manager`), MongoDB (`27017`) — команды `docker run` в `../README.MD`. Для метрик/логов/трейсов дополнительно VictoriaMetrics, Loki, Tempo, Grafana — см. `MONITORING.md`.
2. `eureka-server` с профилем `standalone`.
3. Четыре бизнес-сервиса с профилем `cloud`.
4. `admin-server` с профилем `standalone` — в любой момент; SBA-клиенты повторяют регистрацию в фоне, перезапускать их после старта админки не нужно.

```bash
# 1. Реестр
SPRING_PROFILES_ACTIVE=standalone ./mvnw -pl eureka-server spring-boot:run

# 2. Бизнес-сервисы — все с cloud
SPRING_PROFILES_ACTIVE=cloud ./mvnw -pl catalogue-service spring-boot:run
SPRING_PROFILES_ACTIVE=cloud ./mvnw -pl feedback-service spring-boot:run
SPRING_PROFILES_ACTIVE=cloud ./mvnw -pl manager-app spring-boot:run
SPRING_PROFILES_ACTIVE=cloud ./mvnw -pl customer-app spring-boot:run

# 3. Админка — остаётся на standalone
SPRING_PROFILES_ACTIVE=standalone ./mvnw -pl admin-server spring-boot:run
```

Профиль обязателен для любого из модулей: без `SPRING_PROFILES_ACTIVE` файл `application-<profile>.*` не загрузится, и приложение стартует без портов, datasource, Keycloak-регистраций и т. п.

Чтобы сервис дополнительно отправлял логи в Loki, добавьте переменную `LOKI` (подробности — в `README.MD`):

```bash
SPRING_PROFILES_ACTIVE=cloud LOKI=http://127.0.0.1:3100 \
  ./mvnw -pl catalogue-service spring-boot:run
```

Порядок «Eureka → сервисы» не обязателен, но убирает шум в логах: Eureka-клиенты повторяют попытки подключения к реестру в фоне.

## Что меняется относительно `standalone`

`application-cloud.*` — полная копия standalone-конфигурации, отличий ровно три:

1. `selmag.services.catalogue.uri` / `selmag.services.feedback.uri` указывают на service-id: `http://selmag-catalogue-service`, `http://selmag-feedback-service` (у `manager-app` — только catalogue).
2. Добавлен блок `eureka.*`: `prefer-ip-address: true`, lease 5 с, expiration 10 с, fetch registry 5 с, `defaultZone: http://localhost:8761/eureka/`.
3. `manager-app` явно задаёт `server.port: 8080` (cloud-файл самодостаточен), `customer-app` — явно прописывает URI feedback-сервиса.

Всё остальное не меняется: порты, PostgreSQL/MongoDB, клиенты Keycloak, регистрация в Spring Boot Admin, OTLP-трейсы в Tempo, `/actuator/prometheus`, метки Loki, liveness/readiness. `spring.application.name` остаётся прежним, поэтому `service.name` в Tempo и метка `app` в Loki при переходе на `cloud` те же.

## Проверка

Реестр (ожидаемо четыре приложения):

```bash
curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(sorted(a['name'] for a in d['applications']['application']))"
# ['SELMAG-CATALOGUE-SERVICE', 'SELMAG-CUSTOMER-APP', 'SELMAG-FEEDBACK-SERVICE', 'SELMAG-MANAGER-APP']
```

`selmag-eureka-server` в списке отсутствует (сам не регистрируется) — это норма. Дашборд: `http://localhost:8761/`; предупреждение `SELF PRESERVATION MODE IS TURNED OFF` ожидаемо.

Админка: `http://localhost:8085` (вход через Keycloak) — в списке инстансов должны появиться `catalogue-service` и `feedback-service` (регистрация SBA не зависит от Eureka). Storefront: `http://localhost:8083/customer/products/list`; manager UI: `http://localhost:8080/catalogue/products/list`. Обе страницы ходят в `catalogue-service` уже через load balancer по service-id.

## Типичные ошибки

**`Failed to register application as Application(name=feedback-service, …) at spring-boot-admin (http://localhost:8085/instances): … Connection refused`**

Не запущен `admin-server` (или запущен без профиля `standalone`, поэтому нет `application-standalone.yaml` с `server.port: 8085`). К Eureka это отношения не имеет: регистрация в реестре при этом проходит — в логе видно `registration status: 204`. SBA-клиент повторяет попытку каждые 10 секунд, дальнейшие неудачи пишутся на уровне DEBUG, поэтому после старта `admin-server` инстанс появится в UI сам. Исправление:

```bash
SPRING_PROFILES_ACTIVE=standalone ./mvnw -pl admin-server spring-boot:run
```

**`Unable to load io.netty.resolver.dns.macos.MacOSDnsServerAddressStreamProvider`**

Безобидное предупреждение на macOS: Netty не нашла нативную DNS-библиотеку и использует системный resolver. На работу с Eureka и локальными сервисами не влияет.

**`No instances available for selmag-catalogue-service`**

`manager-app`/`customer-app` не находят `catalogue-service` в реестре. Проверьте, что `catalogue-service` запущен именно с `cloud` и зарегистрировался (`curl … /eureka/apps`), и что `eureka-server` тот же самый (`localhost:8761`).

**Сервис не виден в Eureka**

Скорее всего, он запущен с `standalone` или вовсе без профиля: в standalone-конфигурациях стоят `eureka.client.enabled: false` и `spring.cloud.loadbalancer.enabled: false`.

## Ограничения

- **URI — только `http://<service-id>`, не `lb://<service-id>`**: service-id берётся из `URI.getHost()`, схему подставляет `LoadBalancerUriTools`.
- В cloud-профилях запрещены `lb://`, `172.17.0.1`, `zipkin`, порт `9411`, `spring.profiles.group` и `spring.config.import`.
- `@LoadBalanced` не используется: LB-интерсептор/фильтр подставляется в конкретные клиенты через `ObjectProvider` (`manager-app`, `customer-app`).
- Eureka в этой конфигурации без аутентификации — клиентских секретов и scope в Keycloak для discovery нет.
- Тесты и `standalone` Eureka не требуют: `./mvnw test` и `./mvnw verify` проходят без запущенного реестра.
