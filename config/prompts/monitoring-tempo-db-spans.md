# Промпт: спаны JDBC и MongoDB в трейсах Grafana Tempo

## Контекст

Проект `sc-parent` — мультимодульное приложение на **Java 21 / Spring Boot 4.0.6 / Maven**. Трейсинг уже работает: все четыре бизнес-сервиса экспортируют спаны по OTLP/HTTP в Grafana Tempo (`http://localhost:4318/v1/traces`), sampling `1.0` в профиле `standalone`, OTLP-метрики отключены.

Подробности — в `MONITORING.md` (разделы «Трейсы» и «Как это работает»), архитектура — в `CLAUDE.md`.

### Что видно в Tempo сейчас

- серверные HTTP-спаны: `http get /catalogue/products/list`, `http post /customer/products/{productId}/favourite` и т. п.;
- клиентские HTTP-спаны (`http get …`) на межсервисных вызовах; контекст передаётся заголовком W3C `traceparent`;
- спаны Spring Security (`security filterchain before/after`, `authorize exchange`, `secured request`) и спаны приложения;
- шум: `http get /actuator/prometheus` от VictoriaMetrics и клиентские спаны регистрации в Spring Boot Admin.

### Чего нет

Спанов слоя данных. `spring-boot-starter-opentelemetry` даёт OTel SDK, Micrometer Tracing bridge и OTLP-экспортёр, но **не инструментирует JDBC и MongoDB** — это отдельный слой инструментирования.

### Сервисы и хранилища

| Сервис | Стек | Хранилище | Что добавить |
|---|---|---|---|
| `catalogue-service` | Spring MVC, JPA | PostgreSQL (`localhost:5432/catalogue`), HikariCP | спаны JDBC |
| `manager-app` | Spring MVC, JPA | PostgreSQL (`localhost:5433/manager`), HikariCP | спаны JDBC |
| `feedback-service` | WebFlux, Spring Data MongoDB Reactive | MongoDB (`localhost:27017/feedback`) | спаны Mongo |
| `customer-app` | WebFlux | нет БД (только HTTP-клиенты) | ничего |
| `admin-server` | Spring MVC, Spring Boot Admin | нет БД | ничего — трассировка намеренно не включена |

Полезные точки входа в коде и конфигурации:

- `catalogue-service/src/main/resources/application-standalone.yaml`, `manager-app/src/main/resources/application-standalone.yaml` — datasource, tracing;
- `feedback-service/src/main/resources/application-standalone.yml` — MongoDB URI, tracing;
- `feedback-service/src/main/java/com/drm/sandbox/feedback/config/` — `SecurityConfig`, `MongoConfig`, `ClientConfig`;
- `*/src/test/resources/application.yml` — в тестах экспорт спанов отключён (`management.tracing.export.enabled: false`).

## Задача

Добавить инструментирование слоя данных, чтобы в Tempo в одном трейсе HTTP-запроса были видны спаны SQL-запросов и команд MongoDB:

1. **JDBC** — в `catalogue-service` и `manager-app` (включая запросы Hibernate/JPA и Flyway-трафик).
2. **MongoDB** — в `feedback-service` (реактивный драйвер, `ReactiveMongoTemplate` и репозитории).

HTTP-трейсинг, формат `traceparent`, список и поведение сервисов менять не нужно.

## Требования

### 1. JDBC: `catalogue-service`, `manager-app`

1. Учесть, что Spring Boot 4 **сам не инструментирует JDBC**: в официальной документации (Observability) для этого предлагается отдельный проект **Datasource Micrometer**. Никаких самодельных обёрток `DataSource` и ручных `Observation` вокруг репозиториев.
2. Добавить зависимости в оба модуля:
   - `net.ttddyy.observation:datasource-micrometer-spring-boot`;
   - `net.ttddyy.observation:datasource-micrometer-opentelemetry` (даёт семантические конвенции OTel для запросов).
   Версию подобрать по таблице совместимости библиотеки: ветка **2.x — для Spring Boot 4.x**. Для Boot 4.0 использовать 2.2.x (собран против 4.0), не 2.3.x (собран против 4.1). Версию указать явно в `pom.xml` — Boot BOM её не управляет.
3. Инструментирование должно автоматически покрывать SQL, который Hibernate/JPA отправляет через `DataSource`; отдельного инструментирования Hibernate не добавлять.
4. Спаны запросов должны быть **дочерними** по отношению к серверному HTTP-спану и иметь общий `traceId`; новых трейсов на каждый SQL быть не должно.
5. Ожидаемые имена и атрибуты (вариант с OTel-модулем):
   - имя спана по семантике OTel: операция + объект, например `SELECT products` / `SELECT catalogue` (точный формат зафиксировать фактическим значением из теста);
   - атрибуты: `db.system.name`, `db.operation.name`, `db.namespace`, `db.collection.name`, `db.query.text` (для prepared statements — шаблон SQL без значений), `server.address`, `server.port`.
6. Не включать `jdbc.datasource-proxy.include-parameter-values` и любые другие настройки, попадающие в спаны со значениями bind-параметров (персональные данные, токены).
7. Набор наблюдаемых операций (`CONNECTION`, `QUERY`, `KEYS`, `FETCH`) оставить по умолчанию. Если для уменьшения шума ограничивать через `jdbc.includes`, объяснить выбор и показать, что спаны запросов остались.
8. Учесть шум от Flyway при старте и health-check'ов: решить, нужны ли эти спаны, и если нет — отфильтровать их предсказуемым способом (например, `ObservationPredicate`), не задевая бизнес-запросы.

### 2. MongoDB: `feedback-service`

1. Учесть, что Boot 4.0 управляет драйвером MongoDB **5.6.5**, а нативная наблюдаемость драйвера (`ObservabilitySettings`) появилась только в 5.7. Поэтому **не обновлять драйвер** и не тянуть сторонние библиотеки: использовать штатную наблюдаемость Spring Data MongoDB (`org.springframework.data.mongodb.observability`).
2. Зарегистрировать `MongoClientSettingsBuilderCustomizer` (Boot 4: `org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer`), который добавляет `ContextProviderFactory.create(registry)` и `MongoObservationCommandListener`:

   ```java
   @Configuration(proxyBeanMethods = false)
   class MongoObservationConfig {

       @Bean
       MongoClientSettingsBuilderCustomizer mongoObservability(ObservationRegistry registry) {
           return builder -> builder
                   .contextProvider(ContextProviderFactory.create(registry))
                   .addCommandListener(new MongoObservationCommandListener(registry));
       }
   }
   ```

3. Ожидаемые спаны: имя `spring.data.mongodb.command`, contextual name вида `<collection>.<command>`; low-cardinality атрибуты `db.operation`, `db.name`, `db.mongodb.collection`, `db.system`, `net.peer.*`.
4. Обеспечить связь с серверным HTTP-спаном в реактивном приложении. В WebFlux серверная observation кладётся в Reactor Context (`HttpWebHandlerAdapter`), а `ReactiveContextProvider` читает её оттуда — дополнительная настройка обычно не нужна. Проверить это тестом и трейсом в Tempo; если родитель теряется, включить `spring.reactor.context-propagation: auto` (или явный `contextWrite`) и описать причину.
5. Не отключать существующие метрики команд Mongo (`management.metrics.mongodb.command.enabled`, по умолчанию `true`), если нет измеренной причины: спаны и метрики не конфликтуют. Обратить внимание, что в документации Spring Data встречается устаревшее имя свойства `management.metrics.mongo.command.enabled`.
6. Служебные команды (`ping`, `hello`, `buildInfo`, команды БД `admin`) не должны засорять трейсы; штатный `MongoObservationCommandListener` уже пропускает `admin`. Если шум остаётся, отфильтровать его `ObservationPredicate` и обосновать.

### 3. Общие требования

- Один HTTP-запрос — один трейс; спаны БД вложены в серверный спан соответствующего сервиса.
- Конфигурация OTLP-экспорта, sampling и `service.name` не меняется.
- OTLP-метрики остаются выключенными (`management.otlp.metrics.export.enabled: false`); метрики продолжают идти через Prometheus/VictoriaMetrics.
- OTel Collector, Zipkin, Brave и смена трейсинг-бэкенда не добавляются.
- `customer-app` и `admin-server` не трогать: в первом нет БД, второй намеренно не трассируется.
- Бизнес-логику, публичные API, безопасность и названия сервисов не менять.
- Тесты с Testcontainers (`catalogue-service`, `manager-app`, `feedback-service`) и WireMock (`customer-app`, `manager-app`) должны остаться рабочими; Docker — обязательное условие IT.

## Формат ответа

1. **План** — какие модули и файлы затрагиваются, почему выбран такой способ инструментирования.
2. **Изменения `pom.xml`** — точные зависимости и версии (объяснить выбор версии `datasource-micrometer`).
3. **Код конфигурации** — новые классы/бины для JDBC (если нужны) и MongoDB; полные импорты.
4. **Изменения конфигурации** — фрагменты `application-standalone.y*ml` (и тестовых `application.yml`, если нужно).
5. **Тесты** — как в IT перехватываются observations/спаны, какие имена и родительские связи проверяются; тесты не должны пытаться отправлять спаны в Tempo.
6. **Diff или патч** — минимальный и применимый целиком.
7. **Чек-лист проверки в Tempo** — какие спаны и атрибуты должны появиться в дереве трейса.
8. **Обновлённая документация** — какие разделы `MONITORING.md`, `README.MD`, `CLAUDE.md` изменены.

## Ограничения

- Не менять HTTP-инструментирование, `traceparent` и существующие имена HTTP-спанов.
- Не добавлять dependency management/BOM целиком ради одной библиотеки; версия задаётся точечно.
- Не включать в спаны SQL-параметры, тела запросов, токены и другие чувствительные данные.
- Не менять sampling и не добавлять выборочное сэмплирование «по типу спана» — это исказит трейсы.
- Не добавлять новые сервисы, контейнеры и внешние системы.
- Не выдавать предположения за факты: перед выбором API и свойств свериться с официальной документацией Spring Boot 4 (Observability), Spring Data MongoDB (Observability) и Datasource Micrometer (Getting Started / How-to). Если версия или API не совпадает с проектом — явно указать это и предложить безопасный вариант.

## Критерии приёмки

- [ ] В Tempo у трейса `http get /catalogue/products/list` есть дочерние JDBC-спаны с тем же `traceId` и `parentSpanId` серверного спана.
- [ ] В Tempo у трейса `http get /customer/products/list` цепочка `customer-app` → `feedback-service` дополнена дочерним спаном Mongo внутри серверного спана `feedback-service`.
- [ ] Нет «осиротевших» корневых спанов БД на каждый запрос.
- [ ] В спанах есть `db.system.name`, `db.operation.name`, `db.namespace`/`db.name`, адрес и порт БД; SQL передан как шаблон без значений.
- [ ] `./mvnw -pl catalogue-service -Dit.test=ProductsRestControllerIT verify` проходит.
- [ ] `./mvnw -pl manager-app -Dit.test=ProductsControllerIT verify` проходит.
- [ ] `./mvnw -pl feedback-service -Dit.test=FavouriteProductsRestControllerIT verify` проходит.
- [ ] `./mvnw verify` проходит (Docker запущен), REST Docs генерируется.
- [ ] `MONITORING.md` описывает новый слой: имена спанов, иерархию, настройки, шум и способы проверки.
- [ ] `CLAUDE.md` и `README.MD` дополнены сведениями о новых зависимостях и спанах БД.
