# Промпт: Eureka service discovery и клиентский load balancing

## Контекст

Проект `sc-parent` — мультимодульное приложение на **Java 21 / Spring Boot 4.0.6 / Spring Cloud 2025.1.4 / Maven**. Корневой `pom.xml` не использует Spring Boot parent: оба BOM импортируются в `dependencyManagement` (сначала `spring-boot-dependencies`, затем `spring-cloud-dependencies`). Сейчас в проекте шесть модулей: четыре бизнес-сервиса (`catalogue-service`, `manager-app`, `customer-app`, `feedback-service`), `admin-server` (Spring Boot Admin) и `eureka-server`.

Вся конфигурация сервисов лежит **только** в `src/main/resources/application-standalone.y(a)ml` — файлов `application.yml` по умолчанию нет, поэтому сервисы запускаются с `SPRING_PROFILES_ACTIVE=standalone` (см. `README.MD`).

Архитектура и текущие потоки описаны в `CLAUDE.md`, мониторинг — в `docs/MONITORING.md`.

### Что уже есть

- Модуль `eureka-server` (`spring-cloud-starter-netflix-eureka-server`, `@SpringBootApplication @EnableEurekaServer`, порт 8761) и его `application-standalone.yml` с `register-with-eureka: false`, `fetch-registry: false`, `defaultZone`.
- В `eureka-server/pom.xml` дополнительно объявлены `spring-boot-starter-oauth2-client` и `spring-boot-starter-oauth2-resource-server`, но исходника `SecurityConfig` в модуле нет (в `target/classes` остался устаревший `SecurityConfig.class` от прежней сборки). `spring-boot-starter-oauth2-client` тянет `spring-boot-starter-security`, поэтому приложение без `SecurityConfig` получит дефолтную basic-аутентификацию Boot и закроет `/eureka/**`.
- Значения `spring.application.name`: `selmag-catalogue-service`, `selmag-feedback-service`, `selmag-customer-app`, `selmag-manager-app`.
- Исходящие HTTP-клиенты:
  - `manager-app/src/main/java/com/drm/sandbox/manager/config/ClientBeans.java` — `RestClientProductsRestClient` на авто-конфигурированном `RestClient.Builder` (observations, клиентские спаны, `traceparent`) + `OAuthClientHttpRequestInterceptor`;
  - `customer-app/src/main/java/com/drm/sandbox/customer/config/ClientConfig.java` — три `WebClient` клиента на авто-конфигурированном `WebClient.Builder` + `ServerOAuth2AuthorizedClientExchangeFilterFunction` (token relay).
- Черновик `customer-app/src/main/resources/application-cloud.yml`: `lb://…`, адреса `172.17.0.1`, legacy `management.zipkin.*`, лишние OAuth2-регистрации `metrics`/`discovery`, `spring.boot.admin.client.enabled: false`, секрет клиента, отличающийся от standalone.

### Чего нет

- Ни в одном бизнес-модуле нет `spring-cloud-starter-netflix-eureka-client`; `spring-cloud-starter-loadbalancer` не объявлен нигде.
- Нет `@LoadBalanced`, `@EnableDiscoveryClient`, `DiscoveryClient`/`ServiceInstance` в коде и тестах.
- Нет cloud-профиля у `catalogue-service`, `feedback-service`, `manager-app`; черновик `customer-app` инертен без зависимости клиента.
- Нет документации по discovery.

### Сервисы, порты и роли

| Сервис | Порт | В Eureka | SBA-клиент | Исходящие вызовы |
|---|---|---|---|---|
| `eureka-server` | 8761 | сервер (сам не регистрируется) | нет | — |
| `admin-server` | 8085 | **нет** (осознанно) | сервер SBA | `/actuator/**` клиентов через `OAuthHttpHeadersProvider` |
| `catalogue-service` | 8081 | регистрируется | да | нет |
| `feedback-service` | 8084 | регистрируется | да | нет |
| `manager-app` | 8080 | регистрируется | нет | `catalogue-service` (RestClient, MVC) |
| `customer-app` | 8083 | регистрируется | нет | `catalogue-service`, `feedback-service` (WebClient, WebFlux) |

Service-id для `http://…` — это `spring.application.name`: `selmag-catalogue-service`, `selmag-feedback-service`, `selmag-customer-app`, `selmag-manager-app`.

### Ключевые файлы

- `pom.xml` (корень), `eureka-server/pom.xml`, `eureka-server/src/main/resources/application-standalone.yml`
- `{catalogue-service,manager-app,customer-app,feedback-service}/pom.xml`
- `{catalogue-service,manager-app,customer-app,feedback-service}/src/main/resources/application-standalone.y(a)ml`
- `manager-app/src/main/java/com/drm/sandbox/manager/config/ClientBeans.java`
- `customer-app/src/main/java/com/drm/sandbox/customer/config/ClientConfig.java`
- `{catalogue-service,manager-app,customer-app,feedback-service}/src/test/resources/application.yml`
- `manager-app/src/test/java/com/drm/sandbox/manager/config/TestingBeans.java`, `customer-app/src/test/java/com/drm/sandbox/customer/config/TestConfig.java`
- `README.MD`, `CLAUDE.md`, `docs/MONITORING.md`

## Задача

1. Все четыре бизнес-сервиса регистрируются в `eureka-server` и находят друг друга по service-id. `admin-server` в Eureka **не** добавляется и продолжает работать как раньше (SBA-клиенты регистрируются по `spring.boot.admin.client.url`).
2. Добавить полноценный профиль `cloud` (самостоятельная конфигурация, **не** слой поверх `standalone`) в четыре бизнес-модуля; запуск — `SPRING_PROFILES_ACTIVE=cloud`.
3. Донастроить `eureka-server` для одного локального узла.
4. Сохранить без изменений OAuth2 token relay, безопасность, Swagger, порты, OTLP-трейсинг в Tempo, метрики Prometheus/VictoriaMetrics и регистрацию в Spring Boot Admin.

## Требования

### 0. Версии, URI и принципиальные ограничения

1. **Зависимость.** В `catalogue-service`, `feedback-service`, `manager-app`, `customer-app` добавить только:
   ```xml
   <dependency>
       <groupId>org.springframework.cloud</groupId>
       <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
   </dependency>
   ```
   Без `<version>` — управляется Spring Cloud BOM. `spring-cloud-starter-loadbalancer` **отдельно не добавлять**: он приходит транзитивно (`spring-cloud-starter-netflix-eureka-client:5.0.3` → `spring-cloud-starter-loadbalancer:5.0.4`). Проверить `./mvnw dependency:tree`.
2. **`@EnableDiscoveryClient` не нужен.** Наличие реализации `DiscoveryClient` на classpath включает регистрацию автоматически. Не добавлять аннотацию и не добавлять `spring.cloud.discovery.enabled` в конфигурацию.
3. **URI сервисов — только `http://<service-id>`**, например `http://selmag-catalogue-service`, `http://selmag-feedback-service`. **`lb://` запрещён.**
   - `LoadBalancerInterceptor` (blocking) и `ReactorLoadBalancerExchangeFilterFunction` (reactive) берут service-id из `URI.getHost()`.
   - `LoadBalancerUriTools.reconstructURI` подставляет схему из `ServiceInstance.getScheme()`, а если её нет — сохраняет исходную. У Eureka `EurekaServiceInstance` схему отдаёт, поэтому `lb://` может «случайно работать», но это не документированное поведение и ломается на других discovery-клиентах. Каноническая форма — `http://<service-id>/…`.
4. **`@LoadBalanced` в этой задаче не используется.** Почему:
   - `LoadBalancerRestClientBuilderBeanPostProcessor` и `LoadBalancerWebClientBuilderBeanPostProcessor` добавляют интерсептор/фильтр **в бин-билдер**, помеченный `@LoadBalanced` (`ApplicationContext.findAnnotationOnBean`).
   - В Boot 4.0.6 `restClientBuilder` и `webClientBuilder` объявлены как `@Bean @Scope("prototype") @ConditionalOnMissingBean`, поэтому собственный `@LoadBalanced RestClient.Builder`/`WebClient.Builder` **подавляет** авто-конфигурированный и остаётся единственным билдером в контексте.
   - Транспорт Eureka-клиента (`DiscoveryClientOptionalArgsConfiguration`) выбирает билдер через `ObjectProvider<RestClient.Builder>.getIfAvailable(…)` и клонирует его (`RestClientTransportClientFactory.newClient`). LB-билдер достался бы транспорту Eureka: запросы к `http://localhost:8761/eureka/` пошли бы через лоад-балансер, `localhost` был бы принят за service-id, регистрация сломалась бы (`No instances available for localhost`). При двух билдерах без `@Primary` `getIfAvailable()` бросит `NoUniqueBeanDefinitionException`.
   - Плюс `RestClient.builder()`/`WebClient.builder()` вместо авто-конфигурированного билдера теряет observations (клиентские спаны, метрики, `traceparent`), что прямо противоречит `CLAUDE.md`.
   → Вместо аннотации внедрять **те же самые бины, которые добавляет `@LoadBalanced`**: `DeferringLoadBalancerInterceptor` (blocking) и `DeferringLoadBalancerExchangeFilterFunction` (reactive), и добавлять их в конкретный клиент. Механизм идентичен, но не затрагивает транспорт Eureka и не подавляет авто-конфигурацию Boot.
5. **Cloud-профиль самодостаточен.** Никаких `spring.profiles.group`, `spring.config.import` и ссылок на `standalone`. `application-cloud.y(a)ml` — полная копия standalone-конфигурации с точечными отличиями (см. п. 9).
6. **Совместимость версий.** Spring Cloud 2025.1.4 собран против Boot 4.0.9, проект фиксирует 4.0.6. Не менять версии «на всякий случай»; поднимать `spring-boot.version` до 4.0.9 допустимо только если сборка/запуск реально упадут на отсутствующем API Boot.
7. **Keycloak и realm не меняются.** Eureka-сервер в этой задаче без аутентификации, поэтому регистрация и получение реестра токена не требуют. Следствия:
   - `config/keycloak/import/realm-export.json` **не редактировать**; новых клиентов и client scope'ов (`discovery` и любых других) не добавлять; ручных шагов в Keycloak не появляется.
   - В реестре сейчас нет скоупа `discovery` — он существует только в примере из курса `sc24` (ветка `SC24EP16-eureka`), где Eureka защищена: `/eureka/apps/**` требует `SCOPE_discovery`, а каждый сервис добывает `client_credentials`-токен через регистрацию `discovery` и подставляет его в транспорт Eureka (`DiscoveryBeans` + Apache-интерсептор). Наш промпт эту схему **не** воспроизводит.
   - Регистрации `discovery` и `metrics` в черновике `customer-app/application-cloud.yml` — наследство той схемы; их нужно удалить (см. п. 5).
   - Код из урока не портируется: в Spring Cloud Netflix 5.0.3 транспорт Eureka переведён на `RestClient`/`WebClient`, а `DefaultEurekaClientHttpRequestFactorySupplier` имеет конструктор `(TimeoutProperties, Set<RequestConfigCustomizer>)` без списка `HttpRequestInterceptor`.
   - Если защита Eureka понадобится позже — это отдельная задача: скоуп и клиенты в Keycloak, `SecurityConfig` в `eureka-server`, свой `RestClientDiscoveryClientOptionalArgs` с Bearer-интерсептором в каждом модуле. В текущем объёме этого делать не нужно.

### 1. `eureka-server`

1. Убрать из `eureka-server/pom.xml` неиспользуемые `spring-boot-starter-oauth2-client` и `spring-boot-starter-oauth2-resource-server`. Обоснование: исходника `SecurityConfig` нет, а эти стартеры приносят `spring-boot-starter-security` → дефолтная basic-аутентификация Boot закроет `/eureka/**` и клиенты получат 401. Eureka-сервер в этой задаче остаётся без аутентификации (внутренний инфраструктурный компонент, как `admin-server` вне Eureka); альтернативный вариант — полноценный `SecurityConfig`, но он выходит за рамки задачи.
2. Привести `application-standalone.yml` к виду:
   ```yaml
   server:
     port: 8761

   spring:
     application:
       name: selmag-eureka-server

   eureka:
     instance:
       hostname: localhost
     client:
       register-with-eureka: false
       fetch-registry: false
       service-url:
         defaultZone: http://localhost:8761/eureka/
     server:
       enable-self-preservation: false      # один локальный узел: мёртвые инстансы должны исчезать
       eviction-interval-timer-in-ms: 5000  # по умолчанию 60000
       use-read-only-response-cache: false  # дашборд/реестр обновляются сразу
   ```
   `defaultZone` оставить даже при `register-with-eureka: false` / `fetch-registry: false` — это официальный пример standalone-сервера; имя ключа чувствительно к регистру (`defaultZone`, не `default-zone`).
3. Не добавлять модулю cloud-профиль (у него нет межсервисных вызовов).
4. Выполнить `./mvnw -pl eureka-server clean` и убедиться, что в `eureka-server/target/classes` остались только `EurekaServerApplication.class` и `application-standalone.yml` — устаревший `SecurityConfig.class` удалён.
5. В `README.MD`/`CLAUDE.md` зафиксировать: предупреждение «SELF PRESERVATION MODE IS TURNED OFF» на дашборде — ожидаемое поведение локальной конфигурации.

### 2. `catalogue-service`

1. Добавить eureka-стартер (п. 0.1).
2. Создать `src/main/resources/application-cloud.yaml` — полная копия `application-standalone.yaml` со всеми ключами:
   `spring.application.name`, `server.port: 8081`, `spring.datasource.url/username/password` (`jdbc:postgresql://localhost:5432/catalogue`, `catalogue`/`catalogue`), `spring.flyway.schemas`, `spring.jpa.show-sql`, `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `spring.security.oauth2.client.registration.keycloak.*` (`catalogue-service-metrics-client`, тот же секрет, `client_credentials`, `metrics_server`), `spring.security.oauth2.client.provider.keycloak.issuer-uri`, `spring.boot.admin.client.url`/`instance.name`/`instance.service-host-type`/`enabled`, `logging.level.sql`, `springdoc.swagger-ui.oauth.*`, `keycloak.uri`, все `management.*` (tracing sampling `1.0`, OTLP endpoint `http://localhost:4318/v1/traces`, `transport: http`, OTLP-метрики выключены, liveness/readiness, `exposure.include: '*'`).
3. Добавить блок:
   ```yaml
   eureka:
     instance:
       prefer-ip-address: true
       lease-renewal-interval-in-seconds: 5       # локальная разработка; в prod — 30
       lease-expiration-duration-in-seconds: 10   # локальная разработка; в prod — 90
     client:
       service-url:
         defaultZone: http://localhost:8761/eureka/
       registry-fetch-interval-seconds: 5         # локальная разработка; в prod — 30
   ```
4. Java-код не менять: сервис никого не вызывает, SBA `ClientConfig` продолжает работать на обычном авто-конфигурированном `RestClient.Builder` (он не `@LoadBalanced`, транспорт Eureka не затрагивается).

### 3. `feedback-service`

Аналогично п. 2, с сохранением расширения `.yml` и своих ключей: `server.port: 8084`, `spring.application.name`, `spring.boot.admin.client.*`, `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `spring.security.oauth2.client.registration.keycloak.*` (`feedback-service-metrics-client`, тот же секрет, `client_credentials`, `metrics_server`), провайдер `keycloak`, `spring.mongodb.uri: mongodb://localhost:27017/feedback`, `springdoc.swagger-ui.oauth.*`, `keycloak.uri`, все `management.*`. Плюс тот же блок `eureka.*`. Java-код не менять.

### 4. `manager-app`

1. Добавить eureka-стартер (п. 0.1).
2. Создать `application-cloud.yaml` — копия `application-standalone.yaml`, отличия:
   ```yaml
   selmag:
     services:
       catalogue:
         uri: http://selmag-catalogue-service   # было http://localhost:8081
         registration-id: keycloak

   server:
     port: 8080                                 # в standalone порт не задан (дефолт 8080)
   ```
   Остальное копировать без изменений: `spring.application.name`, `spring.datasource.*` (`jdbc:postgresql://localhost:5433/manager`, `manager`/`manager`), `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `spring.security.oauth2.client.provider.keycloak.*`, `spring.security.oauth2.client.registration.keycloak.*` (те же `client-id`, секрет и scopes: `openid`, `view_catalogue`, `edit_catalogue`, `microprofile-jwt`), все `management.*`. Добавить блок `eureka.*` из п. 2.3.
3. В `config/ClientBeans.java` внедрить LB-интерсептор через `ObjectProvider` (иначе в `standalone` и в тестах, где лоад-балансер выключен, бина не будет и контекст не поднимется):
   ```java
   import org.springframework.beans.factory.ObjectProvider;
   import org.springframework.cloud.client.loadbalancer.DeferringLoadBalancerInterceptor;

   @Bean
   public RestClientProductsRestClient productsRestClient(
           @Value("${selmag.services.catalogue.uri:http://localhost:8081}") String catalogueBaseUri,
           // Auto-configured prototype builder: carries observations (client spans, client metrics,
           // traceparent propagation). A bare RestClient.builder() would lose all of that.
           RestClient.Builder restClientBuilder,
           // Present only when the load balancer is enabled (cloud profile); empty in standalone/tests.
           ObjectProvider<DeferringLoadBalancerInterceptor> loadBalancerInterceptor,
           ClientRegistrationRepository clientRegistrationRepository,
           OAuth2AuthorizedClientRepository authorizedClientRepository,
           @Value("${selmag.services.catalogue.registration-id:keycloak}") String registrationId) {
       RestClient.Builder builder = restClientBuilder.baseUrl(catalogueBaseUri);
       // LB interceptor first: it rewrites the service-id host to a real instance URI and passes
       // the rewritten request down the chain; the OAuth interceptor then adds the Bearer token.
       loadBalancerInterceptor.ifAvailable(builder::requestInterceptor);
       builder.requestInterceptor(new OAuthClientHttpRequestInterceptor(
               new DefaultOAuth2AuthorizedClientManager(clientRegistrationRepository,
                       authorizedClientRepository), registrationId));
       return new RestClientProductsRestClient(builder.build());
   }
   ```
   `OAuthClientHttpRequestInterceptor` и token relay не менять.

### 5. `customer-app`

1. Добавить eureka-стартер (п. 0.1).
2. Полностью переписать `application-cloud.yml` (не редактировать черновик точечно). Обязательное содержимое:
   ```yaml
   selmag:
     services:
       catalogue:
         uri: http://selmag-catalogue-service
       feedback:
         uri: http://selmag-feedback-service

   server:
     port: 8083

   spring:
     application:
       name: selmag-customer-app
     security:
       oauth2:
         resourceserver:
           jwt:
             issuer-uri: http://localhost:8082/realms/selmag
         client:
           provider:
             keycloak:
               issuer-uri: http://localhost:8082/realms/selmag
               user-name-attribute: preferred_username
           registration:
             keycloak:
               client-id: customer-app
               client-secret: vKR4vMsL2JtUMh9dN7adffMqY4Qn109m   # значение из standalone
               authorization-grant-type: authorization_code
               scope:
                 - openid
                 - view_catalogue

   management:
     tracing:
       sampling:
         probability: 1.0
     opentelemetry:
       tracing:
         export:
           otlp:
             endpoint: http://localhost:4318/v1/traces
             transport: http
     otlp:
       metrics:
         export:
           enabled: false
     health:
       livenessstate:
         enabled: true
       readinessstate:
         enabled: true
     endpoints:
       web:
         exposure:
           include: '*'

   eureka:  # блок из п. 2.3
   ```
   Ошибки черновика, которые обязаны исчезнуть: `lb://`, `172.17.0.1`, `management.zipkin.*` и порт `9411`, регистрации `metrics`/`discovery`, `spring.boot.admin.client.enabled`, чужой client-secret. Регистрации `discovery`/`metrics` — остатки схемы из курса, где Eureka была защищена OAuth2 (п. 0.7); в нашем проекте `customer-app` не SBA-клиент и не ходит в Eureka с токеном.
3. В `config/ClientConfig.java` внедрить реактивный LB-фильтр через `ObjectProvider`:
   ```java
   import org.springframework.beans.factory.ObjectProvider;
   import org.springframework.cloud.client.loadbalancer.reactive.DeferringLoadBalancerExchangeFilterFunction;

   @Bean
   public WebClientProductsClient webClientProductsClient(
           @Value("${selmag.services.catalogue.uri:http://localhost:8081}") String catalogueBaseUrl,
           WebClient.Builder webClientBuilder,
           // Present only when the load balancer is enabled (cloud profile).
           ObjectProvider<DeferringLoadBalancerExchangeFilterFunction<?>> loadBalancerFilter,
           ReactiveClientRegistrationRepository clientRegistrationRepository,
           ServerOAuth2AuthorizedClientRepository authorizedClientRepository) {
       loadBalancerFilter.ifAvailable(webClientBuilder::filter);
       return new WebClientProductsClient(webClientBuilder
               .baseUrl(catalogueBaseUrl)
               .filter(keycloakTokenRelay(clientRegistrationRepository, authorizedClientRepository))
               .build());
   }
   ```
   То же для `webClientFavouriteProductsClient` и `webClientProductReviewsClient` (`selmag.services.feedback.uri`).
   - Фильтр LB добавляется первым: `DefaultWebClientBuilder` объединяет фильтры через `ExchangeFilterFunction::andThen`, поэтому LB переписывает URI до того, как token relay добавит `Authorization`.
   - `WebClient.Builder` — prototype, поэтому у каждого из трёх клиентов свой экземпляр и фильтры не накапливаются. Не превращать билдер в singleton.
   - `keycloakTokenRelay(...)` и `setDefaultClientRegistrationId("keycloak")` не менять.
   - `spring.boot.admin.client.*` в customer-app не добавлять: модуль не SBA-клиент.

### 6. `admin-server`

Не менять ничего: ни `pom.xml`, ни конфигурацию, ни Java-код. Он остаётся вне Eureka и продолжает опрашивать `catalogue-service`/`feedback-service` по адресам, которые те сами прислали при регистрации в SBA (`spring.boot.admin.client.url: http://localhost:8085`).

### 7. Совместимость профиля `standalone` (обязательно)

После добавления eureka-зависимости профиль `standalone` без правок начал бы (а) регистрироваться в Eureka по дефолтному `http://localhost:8761/eureka/`, (б) трактовать `http://localhost:8081` как service-id `localhost` при включённом лоад-балансере.

В каждый из четырёх `application-standalone.y(a)ml` (аккуратно вписав в существующие блоки, не создавая второй `spring:`) добавить:
```yaml
eureka:
  client:
    enabled: false

spring:
  cloud:
    loadbalancer:
      enabled: false
```
Поведение `standalone` при этом не меняется: фиксированные `localhost`-URI, никакой регистрации, никакого LB. `spring.cloud.loadbalancer.enabled: false` убирает `LoadBalancerClientFactory` → `BlockingLoadBalancerClient`, поэтому `DeferringLoadBalancerInterceptor` и `DeferringLoadBalancerExchangeFilterFunction` не создаются и `ObjectProvider` в клиентах остаётся пустым.

### 8. Тесты

В каждый из
- `catalogue-service/src/test/resources/application.yml`
- `manager-app/src/test/resources/application.yml`
- `customer-app/src/test/resources/application.yml`
- `feedback-service/src/test/resources/application.yml`

добавить те же два свойства (в существующий блок `spring:`):
```yaml
eureka:
  client:
    enabled: false

spring:
  cloud:
    loadbalancer:
      enabled: false
```
Требования:
- Тесты не активируют профиль `cloud`, поэтому настройки из `application-cloud.*` на них не влияют — отключение должно быть именно в тестовых ресурсах.
- Никаких `@ActiveProfiles`, `@EnableDiscoveryClient`, моков `DiscoveryClient`/`ReactiveDiscoveryClient` не добавлять.
- Существующие `@Primary`-клиенты на WireMock (`TestingBeans`, `TestConfig`) остаются как есть: они собираются из `RestClient.builder()`/`WebClient.builder()` и LB не используют.
- Новых тестов не требуется; `./mvnw test` должен проходить **без запущенной Eureka**, `./mvnw verify` — с Docker (Testcontainers) как и раньше.
- Если production-бин клиента в тестовом контексте не находит LB-бин — использовать `ObjectProvider` (п. 4.3/5.3), а не ослаблять production-код.

### 9. Паритет `standalone` ↔ `cloud`

Cloud-файл — полная копия standalone плюс только разрешённые отличия:
1. `selmag.services.*.uri` — `http://<service-id>` вместо `http://localhost:<port>`.
2. Добавлен блок `eureka.*`.
3. `manager-app`: добавлен явный `server.port: 8080`.
4. `customer-app`: добавлен `selmag.services.feedback.uri` (в standalone его нет — работает дефолт `@Value`).
5. Комментарии, объясняющие перечисленное.

Запрещённые в cloud-файлах строки: `lb://`, `172.17.0.1`, `zipkin`, `9411`, `spring.profiles.group`, `spring.config.import`, ссылки на `standalone`.

В ответе привести таблицу «ключ → значение в standalone → значение в cloud» для каждого модуля и явно перечислить только изменённые/добавленные ключи.

### 10. Общие требования

- Не менять бизнес-логику, публичные API, правила безопасности, OAuth2-потоки, Swagger, порты, `service.name` в трейсах.
- Не менять настройки OTLP-трейсинга и не включать OTLP-метрики; метрики остаются в Prometheus/VictoriaMetrics.
- Не добавлять `@LoadBalanced`, `@EnableDiscoveryClient`, `spring-boot-starter-loadbalancer` вручную.
- Не добавлять Eureka в `admin-server`, не защищать `eureka-server` через OAuth2, не добавлять gateway, Feign, Config Server, Kubernetes-манифесты и новые контейнеры.
- Не менять `config/keycloak/import/realm-export.json`: никаких новых клиентов, client scope'ов (`discovery`) и секретов.
- Не выдумывать имена свойств: в Spring Cloud Netflix 5.0.3 нет `eureka.client.restclient.enabled`; переключатели транспорта — `eureka.client.webclient.enabled` и `eureka.client.jersey.enabled`. Не включать `eureka.client.webclient.enabled: true`.
- Не выдавать предположения за факты: перед использованием класса/свойства свериться с официальной документацией Spring Cloud 2025.1 / Boot 4 и с исходниками используемых версий. Если API не совпадает с ожидаемым — явно указать это и предложить безопасный вариант.

## Формат ответа

1. **План** — какие модули и файлы затрагиваются и почему выбран именно такой способ.
2. **Изменения `pom.xml`** — точные зависимости (без версий).
3. **Полные тексты `application-cloud.y(a)ml`** для четырёх модулей.
4. **Изменения существующих конфигураций** — `application-standalone.*` (Eureka/LB off), `eureka-server`, тестовые `application.yml`.
5. **Java-код** — `ClientBeans`, `ClientConfig` с полными импортами; никаких других изменений в коде.
6. **Таблица паритета** standalone ↔ cloud с перечнем отличий.
7. **Diff или патч**, применимый целиком.
8. **Чек-лист проверки** (см. критерии приёмки) с фактическими результатами команд.
9. **Обновлённая документация** — какие разделы `README.MD`, `CLAUDE.md`, `docs/MONITORING.md` изменены.

## Ограничения

- Никаких `lb://`, `172.17.0.1`, Zipkin и legacy-ключей в cloud-профилях.
- `@LoadBalanced` не использовать; LB подключать только через `DeferringLoadBalancerInterceptor` / `DeferringLoadBalancerExchangeFilterFunction` и только через `ObjectProvider`, чтобы `standalone` и тесты оставались работоспособными.
- Не подавлять авто-конфигурированные `RestClient.Builder`/`WebClient.Builder` собственными бинами того же типа.
- Не делать `WebClient.Builder` singleton'ом и не добавлять token-relay фильтр более одного раза на клиента.
- Не отключать существующие наблюдения: клиентские спаны и `traceparent` должны сохраниться.
- Не менять OAuth2-секреты, роли, scopes и поведение token relay.
- Не добавлять новых тестов и не менять смысл существующих IT; допускается только добавление отключающих свойств в тестовые конфиги.
- Не коммитить и не менять версии зависимостей без необходимости.

## Критерии приёмки

- [ ] `./mvnw -pl eureka-server clean package` собирается; в `target/classes` нет `SecurityConfig.class`; `http://localhost:8761/` открывается без логина.
- [ ] `./mvnw test` проходит **без** запущенной Eureka (доказательство, что тестовые конфиги её отключают).
- [ ] `./mvnw verify` проходит при запущенном Docker (Testcontainers PostgreSQL/MongoDB, WireMock IT).
- [ ] `curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps` при запущенных сервисах с `SPRING_PROFILES_ACTIVE=cloud` содержит ровно четыре приложения — `SELMAG-CATALOGUE-SERVICE`, `SELMAG-FEEDBACK-SERVICE`, `SELMAG-MANAGER-APP`, `SELMAG-CUSTOMER-APP` — по одному `UP`-инстансу; `selmag-eureka-server` и admin-сервис отсутствуют.
- [ ] `manager-app` под `cloud` открывает `http://localhost:8080/catalogue/products/list` (вход `j.dewar`/`password`), список товаров грузится через `http://selmag-catalogue-service`.
- [ ] `customer-app` под `cloud` открывает `http://localhost:8083/customer/products/list` (вход `j.daniels`/`password`), работают избранное и отзывы — то есть вызовы идут в `catalogue-service` и `feedback-service` по service-id.
- [ ] В cloud-файлах нет `lb://`, `172.17.0.1`, `zipkin`, `9411`, `spring.profiles.group`, `spring.config.import`: `grep -R -n -E "lb://|172\.17\.0\.1|zipkin|9411|profiles\.group|config\.import" */src/main/resources/application-cloud.*` ничего не находит.
- [ ] Остановка `catalogue-service` даёт ошибку лоад-балансера с именем `selmag-catalogue-service` (`No instances available for selmag-catalogue-service` / `No servers available for service: selmag-catalogue-service`), а **не** `Connection refused` к `localhost:8081`. После перезапуска сервиса страница восстанавливается.
- [ ] Профиль `standalone` по-прежнему работает для всех четырёх сервисов: нет попыток регистрации в Eureka, вызовы идут на `localhost`-адреса.
- [ ] `admin-server` не изменён и продолжает показывать `catalogue-service` и `feedback-service`.
- [ ] В Tempo у исходящих вызовов сохранены клиентские HTTP-спаны с тем же `traceId`, что у серверного спана.
- [ ] `config/keycloak/import/realm-export.json` не изменён; в cloud-конфигах нет OAuth2-регистраций `discovery` и `metrics`; в Keycloak не добавлено ни одного клиента или скоупа.
- [ ] `README.MD`, `CLAUDE.md`, `docs/MONITORING.md` обновлены: модуль `eureka-server`, порт 8761, матрица профилей, порядок запуска, service-id, правило «URI = service-id, не `lb://`», Eureka в тестах отключена.
