# Промпт: Spring Cloud Config Server с native-бэкендом (первый этап)

## Контекст

Проект `sc-parent` — мультимодульное приложение на **Java 21 / Spring Boot 4.0.6 / Spring Cloud 2025.1.4 / Maven**.
Корневой `pom.xml` не использует Spring Boot parent: оба BOM импортируются в `dependencyManagement`
(сначала `spring-boot-dependencies`, затем `spring-cloud-dependencies`). Модули: четыре бизнес-сервиса
(`catalogue-service`, `manager-app`, `customer-app`, `feedback-service`), `admin-server`
(Spring Boot Admin), `eureka-server` и новый `config-server`.

Вся конфигурация сервисов лежит **только** в профильных файлах `src/main/resources/application-<profile>.y(a)ml` —
базовых `application.yml` нет. Сервисы запускаются с `SPRING_PROFILES_ACTIVE=standalone` (локально)
или `SPRING_PROFILES_ACTIVE=cloud` (Eureka); см. `README.MD` и `docs/CLOUD-SERVICES.md`.
Архитектура и потоки описаны в `CLAUDE.md`.

Задача этого этапа — **только native-бэкенд** Spring Cloud Config: Config Server читает конфиги
из локального каталога `config/cloud/`, а `catalogue-service` получает их через новый профиль
`cloudconfig`. Git/Vault-бэкенд, остальные сервисы, OAuth2-защита Config Server, refresh и
Spring Cloud Bus — следующие этапы.

### Что уже есть

- В корневом `pom.xml` модуль `config-server` уже добавлен в `<modules>`.
- `config-server/pom.xml`: `spring-boot-starter-web`, `spring-cloud-config-server`,
  `spring-boot-starter-security`; **нет** `spring-boot-maven-plugin`.
- `config-server/src/main/java/com/drm/sandbox/config/ConfigServerApplication.java` —
  `@SpringBootApplication @EnableConfigServer`.
- Единственный файл настроек `config-server/src/main/resources/application-standalone.yml`:

  ```yaml
  server:
    port: 8888
  spring:
    application:
      name: selmag-config-server
    cloud:
      config:
        server:
          native:
            search-locations: file://${user.home}/prog/sel/sc-parent/config/cloud/{application}/{profile}
  ```

- Черновик native-конфига:
  `config/cloud/catalogue-service/cloudconfig /selmag-catalogue-service.yml`
  (в имени каталога `cloudconfig ` — **пробел в конце**; первый сегмент — короткое имя
  `catalogue-service`, а не `selmag-catalogue-service`; внутри issuer `http://172.17.0.1:8082/...`,
  client-id `catalogue-service` и чужой секрет, набор свойств неполный).
- Новый клиентский профиль `catalogue-service/src/main/resources/application-cloudconfig.yml`:

  ```yaml
  server:
    port: 8081
  spring:
    application:
      name: selmag-catalogue-service
    config:
      import: 'optional:configserver:'
    cloud:
      config:
        uri: http://config:config-password@localhost:8888
  ```

- `catalogue-service/src/main/resources/application-cloud.yaml` — полная рабочая конфигурация
  `catalogue-service` под Eureka (datasource, Flyway, JPA, Keycloak resource-server и
  `client_credentials`-регистрация `keycloak` для SBA, springdoc, `management.*`, OTLP-трейсинг,
  `eureka.*`); `spring.application.name: selmag-catalogue-service`.
- В `catalogue-service/src/main/resources/application-standalone.yaml` уже есть
  `spring.cloud.loadbalancer.enabled: false`; в `src/test/resources/application.yml` —
  `spring.cloud.loadbalancer.enabled: false` и `eureka.client.enabled: false`.
- Клиента Config Server (`spring-cloud-starter-config`) сейчас нет ни в одном модуле;
  `spring.config.import` нигде не используется.

### Чего нет

- Native-бэкенд не активирован: при запуске без профиля `native` Config Server поднимается с
  git-бэкендом по умолчанию и конфиги не отдаёт.
- Нет Basic-аутентификации: `spring-boot-starter-security` на classpath даёт дефолтную цепочку
  Boot с паролем, который печатается в лог, — клиент с `config:config-password` получит 401.
- Нет `spring-boot-maven-plugin` — `spring-boot:run` и исполняемый jar не работают.
- Раскладка каталогов не совпадает с `search-locations` (`{application}` = `selmag-catalogue-service`,
  `{profile}` = `cloudconfig`), в имени каталога лишний пробел.
- В native-файле неверные issuer и Keycloak-клиент, не хватает большей части конфигурации.
- В `catalogue-service` нет `spring-cloud-starter-config`; профиль `cloudconfig` использует
  `optional:`, поэтому при недоступном Config Server сервис стартует с половиной конфигурации.
- Документация (`CLAUDE.md`, `README.MD`, `docs/CLOUD-SERVICES.md`) запрещает `spring.config.import`
  в профиле `cloud` и ничего не знает про `cloudconfig`.
- Тестов у `config-server` нет.

### Ключевые файлы

- `pom.xml` (корень), `config-server/pom.xml`, `catalogue-service/pom.xml`
- `config-server/src/main/resources/application-standalone.yml`
- `config-server/src/main/java/com/drm/sandbox/config/ConfigServerApplication.java`
- `config/cloud/catalogue-service/cloudconfig /selmag-catalogue-service.yml`
- `catalogue-service/src/main/resources/application-cloudconfig.yml`
- `catalogue-service/src/main/resources/application-standalone.yaml` / `application-cloud.yaml`
- `catalogue-service/src/test/resources/application.yml`
- `README.MD`, `CLAUDE.md`, `docs/CLOUD-SERVICES.md`
- образец стиля промпта и требований — `config/prompts/eureka-service-discovery.md`

### Профили и порты

| Компонент | Профиль запуска | Порт | Источник конфигурации |
|---|---|---|---|
| `config-server` | **`standalone` + `native`** | 8888 | `config/cloud/{application}/{profile}/` |
| `catalogue-service` (этот этап) | **`cloudconfig`** | 8081 | Config Server (`configserver:`), локально — только bootstrap |
| `catalogue-service` (как раньше) | `standalone` / `cloud` | 8081 | локальные файлы, Config Server выключен |
| `manager-app`, `customer-app`, `feedback-service` | без изменений | 8080/8083/8084 | локальные файлы |

Порядок запуска: `config-server` (`standalone,native`) → Keycloak и PostgreSQL → `catalogue-service`
(`cloudconfig`).

## Задача

1. Config Server запускается с профилями `standalone` и `native` и отдаёт конфигурацию из
   `config/cloud/{application}/{profile}/<application>.yml`, защищённый HTTP Basic
   (`config` / `config-password`).
2. `catalogue-service` с профилем `cloudconfig` получает конфигурацию с Config Server; локально
   остаются только bootstrap-свойства (имя приложения, порт, импорт, URI/креды Config Server).
3. Профили `standalone` и `cloud`, а также тесты `catalogue-service` продолжают работать **без**
   запущенного Config Server.
4. Остальные сервисы, Keycloak realm, Eureka и `admin-server` не меняются.
5. Документация обновлена, у `config-server` появляется smoke-IT.

## Требования

### 0. Версии и принципиальные ограничения

1. Версии зависимостей **не указывать**: `spring-cloud-config-server` и
   `spring-cloud-starter-config` управляются Spring Cloud BOM 2025.1.4 (фактически 5.0.6).
   Не менять `spring-boot.version` и `spring-cloud.version`.
2. **Native-бэкенд включается только активным профилем `native`** (`NativeRepositoryConfiguration`
   помечен `@Profile("native")`). На этом этапе файл настроек остаётся
   `application-standalone.yml`, поэтому запускать нужно **оба профиля**:
   `SPRING_PROFILES_ACTIVE=standalone,native` — `standalone` загружает файл, `native` включает
   файловый бэкенд. `spring.profiles.active` внутри профильного файла
   (`application-standalone.yml`) Boot 2.4+ запрещает — не добавлять его туда;
   `spring.profiles.group` не использовать. Переименование файла в `application-native.yml`
   (один профиль) — отложено.
3. Config Server — **только Spring MVC**: `spring-boot-starter-web`, не WebFlux.
4. `spring.config.import` объявляется **только** в `application-cloudconfig.yml`. В профилях
   `standalone`, `cloud` и в тестовом `application.yml` config client явно выключается
   (`spring.cloud.config.enabled: false`), а не «пустым» импортом.
   Профили `cloud` и `cloudconfig` одновременно не включать.
5. **Приоритет импорта:** документы, импортированные через `spring.config.import`, перекрывают
   ключи файла, объявившего импорт. Поэтому bootstrap-ключи (`spring.application.name`,
   `server.port`, `spring.config.import`, `spring.cloud.config.*`) остаются в локальном
   `application-cloudconfig.yml`, а в native-файле их быть не должно.
6. **`optional:` убрать.** Если Config Server недоступен, профиль `cloudconfig` должен падать на
   старте (`ConfigClientFailFastException`), а не подниматься с половиной конфигурации.
   `spring.cloud.config.fail-fast` не нужен — достаточно необязательного импорта без `optional:`.
7. YAML-файлы в native-репозитории — ровно `<application>.yml`, где `<application>` совпадает с
   `spring.application.name` клиента (`selmag-catalogue-service`). Один профиль — один каталог.
8. Boot отклоняет дублирующиеся ключи YAML (`found duplicate key`): все ключи одного уровня
   должны быть в одном блоке, повторных `spring:` быть не может.
9. Ничего не выдумывать: имена свойств и поведение сверять с официальной документацией
   Spring Cloud Config 2025.1.x (https://docs.spring.io/spring-cloud-config/reference/) и
   Spring Boot 4.0 (https://docs.spring.io/spring-boot/4.0/reference/). Если API не совпадает —
   явно указать это и предложить безопасный вариант.

### 1. `config-server`: сборка

1. В `config-server/pom.xml` добавить `spring-boot-maven-plugin` по образцу
   `eureka-server/pom.xml` (Lombok в модуле нет, поэтому `excludes` не нужен):

   ```xml
   <build>
       <plugins>
           <plugin>
               <groupId>org.springframework.boot</groupId>
               <artifactId>spring-boot-maven-plugin</artifactId>
               <version>${spring-boot.version}</version>
               <configuration>
                   <classifier>exec</classifier>
               </configuration>
               <executions>
                   <execution>
                       <goals>
                           <goal>repackage</goal>
                       </goals>
                   </execution>
               </executions>
           </plugin>
       </plugins>
   </build>
   ```

2. Добавить тестовую зависимость:

   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-test</artifactId>
       <scope>test</scope>
   </dependency>
   ```

3. `./mvnw -pl config-server clean package` должен собрать исполняемый
   `config-server/target/config-server-0.0.1-SNAPSHOT-exec.jar`.

### 2. `config-server`: конфигурация и безопасность

1. Файл `src/main/resources/application-standalone.yml` **не переименовывать** — на этом этапе
   он остаётся профильным файлом `standalone`, а `native` передаётся вторым профилем при запуске.
   Добавить в него учётные данные Basic-аутентификации. Итоговое содержимое:

   ```yaml
   server:
     port: 8888

   spring:
     application:
       name: selmag-config-server
     security:
       user:
         name: config
         password: config-password
     cloud:
       config:
         server:
           native:
             # dev-only: путь к native-репозиторию на машине разработчика.
             # {application} = spring.application.name клиента, {profile} = его активный профиль.
             search-locations: file://${user.home}/prog/sel/sc-parent/config/cloud/{application}/{profile}
   ```

   Запуск на этом этапе:

   ```bash
   SPRING_PROFILES_ACTIVE=standalone,native ./mvnw -pl config-server spring-boot:run
   ```

   Без `standalone` не загрузится `application-standalone.yml` (не будет порта, имени,
   `search-locations` и учёток), без `native` не активируется `NativeRepositoryConfiguration`
   (сервер поднимется с git-бэкендом и не отдаст конфиги). Это временная схема; переход на
   единственный профиль `native` (переименование файла) — отдельный следующий этап.

   Путь `file://${user.home}/prog/sel/sc-parent/config/cloud/...` оставить как есть — это
   осознанное локальное значение; вынос в переменную окружения/CI-профиль — следующий этап.

2. Добавить `config-server/src/main/java/com/drm/sandbox/config/security/SecurityConfig.java`
   в стиле остальных модулей (явная цепочка вместо дефолтной цепочки Boot — предсказуемый 401
   без form-login и редиректов):

   ```java
   package com.drm.sandbox.config.security;

   import org.springframework.context.annotation.Bean;
   import org.springframework.context.annotation.Configuration;
   import org.springframework.security.config.Customizer;
   import org.springframework.security.config.annotation.web.builders.HttpSecurity;
   import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
   import org.springframework.security.config.http.SessionCreationPolicy;
   import org.springframework.security.web.SecurityFilterChain;

   @Configuration
   public class SecurityConfig {

       @Bean
       public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
           return http
                   .authorizeHttpRequests(authorizeHttpRequests -> authorizeHttpRequests
                           .anyRequest().authenticated())
                   .httpBasic(Customizer.withDefaults())
                   .csrf(CsrfConfigurer::disable)
                   .sessionManagement(sessionManagement -> sessionManagement
                           .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                   .build();
       }
   }
   ```

   Пользователь `config/config-password` берётся из `spring.security.user.*`: объявление
   собственного `SecurityFilterChain` **не** отключает авто-конфигурацию `UserDetailsService`
   (она отключается только собственным `UserDetailsService`/`AuthenticationProvider`/
   `AuthenticationManager`). CSRF для Config Server не нужен: он только читает.
   OAuth2-защита Config Server (resource server на Keycloak) — отдельный следующий этап;
   сейчас — только HTTP Basic.

3. Проверить, что `GET /selmag-catalogue-service/cloudconfig` без `Authorization` возвращает
   `401`, с Basic-кредами — `200`.

### 3. Native-репозиторий

1. Перенести черновик, исправив имя каталога (пробел в конце и короткое имя):

   ```bash
   mkdir -p config/cloud/selmag-catalogue-service/cloudconfig
   git mv "config/cloud/catalogue-service/cloudconfig /selmag-catalogue-service.yml" \
          config/cloud/selmag-catalogue-service/cloudconfig/selmag-catalogue-service.yml
   rmdir "config/cloud/catalogue-service/cloudconfig " config/cloud/catalogue-service 2>/dev/null || true
   ```

   Пустые каталоги `config/cloud/catalogue-service/` (включая `cloudconfig ` с пробелом) удалить.
   Итоговая схема: `config/cloud/{application}/{profile}/<application>.yml`, где
   `{application}` = `spring.application.name`, `{profile}` = активный профиль клиента.

2. Содержимое `config/cloud/selmag-catalogue-service/cloudconfig/selmag-catalogue-service.yml` —
   полный рабочий аналог `catalogue-service/src/main/resources/application-cloud.yaml` **без**
   bootstrap-ключей (`spring.application.name`, `server.port`, `spring.config.*`) и с исправлениями
   (issuer — `localhost`, а не `172.17.0.1`; правильный Keycloak-клиент; никаких `lb://`):

   ```yaml
   spring:
     security:
       oauth2:
         resourceserver:
           jwt:
             issuer-uri: http://localhost:8082/realms/selmag
         client:
           registration:
             keycloak:
               client-id: catalogue-service-metrics-client
               client-secret: XzsC20nVY1uDO1xDAE1o7QsqkRqRyban
               authorization-grant-type: client_credentials
               scope:
                 - metrics_server
           provider:
             keycloak:
               issuer-uri: http://localhost:8082/realms/selmag
     boot:
       admin:
         client:
           url: http://localhost:8085
           instance:
             name: catalogue-service
             # Register URLs by IP instead of hostname http://<ip>:8081/...
             service-host-type: ip
           enabled: true
     datasource:
       url: jdbc:postgresql://localhost:5432/catalogue
       username: catalogue
       password: catalogue
     flyway:
       schemas:
         - public
         - catalogue
     jpa:
       show-sql: true
   logging:
     level:
       sql: info
   springdoc:
     swagger-ui:
       oauth:
         client-id: catalogue-service-swagger-ui
         use-pkce-with-authorization-code-grant: true
   keycloak:
     uri: http://localhost:8082
   management:
     tracing:
       sampling:
         probability: 0.1
     opentelemetry:
       tracing:
         export:
           otlp:
             endpoint: http://localhost:4318/v1/traces
             transport: http
     otlp:
       metrics:
         export:
           enabled: false # metrics stay in Prometheus / VictoriaMetrics
     health:
       livenessstate:
         enabled: true
       readinessstate:
         enabled: true
     endpoints:
       web:
         exposure:
           include: '*'
   eureka:
     instance:
       prefer-ip-address: true
       lease-renewal-interval-in-seconds: 5
       lease-expiration-duration-in-seconds: 10
     client:
       service-url:
         defaultZone: http://localhost:8761/eureka/
       registry-fetch-interval-seconds: 5
   ```

   Пояснения, которые должны попасть в итоговый ответ:

   - `spring.boot.admin.client.enabled: true` и `eureka.*` сохранены для паритета с
     `cloud`-профилем. Если `admin-server`/`eureka-server` не запущены, приложение всё равно
     стартует, но пишет ошибки регистрации — это ожидаемо, не «лечить» отключением.
   - Секрет `catalogue-service-metrics-client` уже присутствует в репозитории
     (`application-cloud.yaml`/`application-standalone.yaml`) — новых секретов не добавлять.
   - Те же значения, что в `application-cloud.yaml`, кроме issuer/client-id/secret (в черновике
     они были от другого окружения) — расхождений с рабочей конфигурацией быть не должно.

3. Никаких `spring.config.import`, `spring.profiles.active`, `spring.application.name`,
   `server.port` в native-файле. Native-репозиторий не должен содержать файлов других сервисов
   (это следующий этап).

### 4. `catalogue-service`: зависимость и профили

1. В `catalogue-service/pom.xml` добавить (без версии):

   ```xml
   <dependency>
       <groupId>org.springframework.cloud</groupId>
       <artifactId>spring-cloud-starter-config</artifactId>
   </dependency>
   ```

2. **Критично.** Появление config client на classpath включает проверку
   `ConfigServerConfigDataMissingEnvironmentPostProcessor`: при `spring.cloud.config.enabled=true`
   (по умолчанию) и отсутствии `spring.config.import=configserver:` приложение падает на старте
   с `No spring.config.import property has been defined`. Кроме того, регистрируется
   `ConfigServerHealthIndicator` (условие — только наличие health-инфраструктуры и
   `management.health.config.enabled`, а не `spring.cloud.config.enabled`); без импортированных
   property sources он отдаёт `UNKNOWN`, и `/actuator/health` перестаёт быть `UP`.
   Поэтому в трёх конфигах добавить **и** `spring.cloud.config.enabled: false`, **и**
   `management.health.config.enabled: false`:

   - `application-standalone.yaml` — в существующие блоки:

     ```yaml
     spring:
       cloud:
         loadbalancer:
           enabled: false # standalone profile - no service discovery
         config:
           enabled: false # standalone profile - configuration comes from local files
     management:
       health:
         config:
           enabled: false # config client is disabled in this profile
     ```

   - `application-cloud.yaml` — новый блок внутри `spring:` (блока `spring.cloud` там сейчас нет)
     и дополнение в существующий `management.health`:

     ```yaml
     spring:
       cloud:
         config:
           enabled: false # cloud profile - configuration comes from local files
     management:
       health:
         config:
           enabled: false
     ```

   - `src/test/resources/application.yml` — рядом с `loadbalancer.enabled: false` и в блоке
     `management`:

     ```yaml
     spring:
       cloud:
         loadbalancer:
           enabled: false
         config:
           enabled: false # tests must not contact Config Server
     management:
       health:
         config:
           enabled: false
     ```

   Без этих правок `standalone`, `cloud` и тесты перестанут стартовать или начнут отдавать
   `UNKNOWN` в `/actuator/health`. `spring.cloud.config.import-check.enabled: false` вместо
   `spring.cloud.config.enabled: false` **не использовать**: он оставляет клиент включённым и
   маскирует будущие ошибки конфигурации.

   Побочный эффект зависимости `spring-cloud-context`: в приложении появятся эндпоинты
   `/actuator/refresh`, `/actuator/restart`, `/actuator/pause`, `/actuator/resume` и
   health-индикатор `refresh`. В `catalogue-service` все `/actuator/**` уже закрыты
   `SCOPE_metrics`, поэтому дыры нет; в этой итерации эндпоинты не используются и не
   документируются как фича — не менять `management.endpoints.web.exposure.include`.

3. `application-cloudconfig.yml` привести к виду (меняется только импорт — убираем `optional:`):

   ```yaml
   server:
     port: 8081
   spring:
     application:
       name: selmag-catalogue-service
     config:
       import: 'configserver:'
     cloud:
       config:
         uri: http://config:config-password@localhost:8888
   ```

   Пояснить в ответе: `spring.cloud.config.uri` используется для `configserver:`-импорта
   (location в `spring.config.import` имеет приоритет над этим свойством, но здесь он пуст);
   креды в URI — документированный способ Basic-аутентификации, альтернатива —
   `spring.cloud.config.username`/`password` (они перекрывают креды из URI).

4. Проверить, что профиль `cloudconfig` не влияет на тесты: базового `application.yml` нет,
   импорт объявлен только в `application-cloudconfig.yml`, в тестовом `application.yml` config
   client выключен. `./mvnw -pl catalogue-service test` должен проходить без Config Server.

### 5. Тест `config-server`

1. Добавить фикстуру
   `config-server/src/test/resources/config-repo/selmag-catalogue-service/cloudconfig/selmag-catalogue-service.yml`:

   ```yaml
   selmag:
     test:
       marker: native-ok
   ```

2. Добавить `config-server/src/test/java/com/drm/sandbox/config/ConfigServerApplicationIT.java`
   (класс оканчивается на `IT` → Failsafe; Docker и Keycloak не нужны):

   ```java
   package com.drm.sandbox.config;

   import java.net.URI;
   import java.net.http.HttpClient;
   import java.net.http.HttpRequest;
   import java.net.http.HttpResponse;
   import java.nio.charset.StandardCharsets;
   import java.util.Base64;

   import org.junit.jupiter.api.Test;
   import org.springframework.boot.test.context.SpringBootTest;
   import org.springframework.boot.test.web.server.LocalServerPort;
   import org.springframework.test.context.ActiveProfiles;

   import static org.assertj.core.api.Assertions.assertThat;

   @SpringBootTest(
           webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
           properties = "spring.cloud.config.server.native.search-locations=classpath:/config-repo/{application}/{profile}")
   @ActiveProfiles({"standalone", "native"})
   class ConfigServerApplicationIT {

       private final HttpClient httpClient = HttpClient.newHttpClient();

       @LocalServerPort
       private int port;

       @Test
       void servesNativeConfigurationForApplicationAndProfile() throws Exception {
           HttpResponse<String> response = this.httpClient.send(
                   request("/selmag-catalogue-service/cloudconfig", true),
                   HttpResponse.BodyHandlers.ofString());

           assertThat(response.statusCode()).isEqualTo(200);
           assertThat(response.body()).contains("selmag.test.marker").contains("native-ok");
       }

       @Test
       void rejectsRequestWithoutCredentials() throws Exception {
           HttpResponse<String> response = this.httpClient.send(
                   request("/selmag-catalogue-service/cloudconfig", false),
                   HttpResponse.BodyHandlers.ofString());

           assertThat(response.statusCode()).isEqualTo(401);
       }

       private HttpRequest request(String path, boolean withCredentials) {
           HttpRequest.Builder builder = HttpRequest
                   .newBuilder(URI.create("http://localhost:" + this.port + path))
                   .header("Accept", "application/json")
                   .GET();
           if (withCredentials) {
               String credentials = Base64.getEncoder()
                       .encodeToString("config:config-password".getBytes(StandardCharsets.UTF_8));
               builder.header("Authorization", "Basic " + credentials);
           }
           return builder.build();
       }
   }
   ```

   `@SpringBootTest(properties=…)` перекрывает `search-locations` из `application-standalone.yml`
   (профиль `standalone` загружает файл, `native` включает файловый бэкенд; без `standalone`
   тест поднимется без `spring.security.user.*`);
   плейсхолдеры `{application}`/`{profile}` разворачивает сам native-репозиторий.
   `TestRestTemplate` в Boot 4 из `spring-boot-test` убран, поэтому используется JDK `HttpClient`;
   `@LocalServerPort` — `org.springframework.boot.test.web.server.LocalServerPort`.

### 6. Документация

1. `README.MD`: команда запуска Config Server
   (`SPRING_PROFILES_ACTIVE=standalone,native`), порт 8888, порядок запуска
   (`config-server` → Keycloak/PostgreSQL → `catalogue-service` с `SPRING_PROFILES_ACTIVE=cloudconfig`),
   Basic-креды `config`/`config-password`, оговорка про dev-only путь native-репозитория.
2. Новый `docs/CONFIG-SERVER.md`: зачем нужен Config Server; схема
   `config/cloud/{application}/{profile}/<application>.yml`; матрица профилей
   (`standalone` + `native` у сервера, `cloudconfig` у клиента, `standalone`/`cloud` клиента
   не меняются); почему нужны оба профиля сервера; Basic-аутентификация;
   проверка через `curl`; типичные ошибки — пробел/короткое имя каталога, несовпадение
   `{application}` с `spring.application.name`, дубли ключей YAML, `optional:` вместо fail-fast,
   запуск без `standalone` или без `native`, отсутствие `spring.cloud.config.enabled: false`
   в остальных профилях.
3. `CLAUDE.md`: модуль `config-server` в архитектуре/структуре/командах; зависимость
   `spring-cloud-starter-config` у `catalogue-service`; уточнить, что запрет `spring.config.import`
   относится к профилю `cloud`, а `cloudconfig` — осознанное исключение.
4. `docs/CLOUD-SERVICES.md`: та же оговорка про исключение `cloudconfig` (в разделе «Ограничения»
   сейчас запрет сформулирован для всех cloud-профилей).

### 7. Общие требования

- Не менять бизнес-логику, публичные API, безопасность, OAuth2-потоки, Swagger, порты,
  `spring.application.name`, метки Loki и `service.name` в Tempo.
- Не менять `manager-app`, `customer-app`, `feedback-service`, `admin-server`, `eureka-server`
  и `config/keycloak/import/realm-export.json`.
- Не добавлять git/vault/jdbc-бэкенды, Spring Cloud Bus, `/actuator/refresh`, шифрование,
  discovery-first lookup через Eureka, gateway и новые контейнеры.
- Не включать OTLP-метрики; не менять настройки трейсинга.
- Не добавлять версии к управляемым зависимостям и не менять версии BOM.
- Не коммитить изменения.

## Формат ответа

1. **План** — какие файлы затрагиваются и почему.
2. **Изменения `pom.xml`** — `config-server` (plugin + test dependency) и `catalogue-service`
   (`spring-cloud-starter-config`).
3. **Полный текст `application-standalone.yml`** и `SecurityConfig.java`.
4. **Native-конфиг** — полный текст `config/cloud/selmag-catalogue-service/cloudconfig/selmag-catalogue-service.yml`
   и команды `git mv`.
5. **Клиент** — полные тексты `application-cloudconfig.yml` и точечные правки
   `application-standalone.yaml` / `application-cloud.yaml` / `src/test/resources/application.yml`.
6. **Тест** — полный текст `ConfigServerApplicationIT` и фикстуры.
7. **Diff или патч**, применимый целиком.
8. **Чек-лист проверки** с фактическими результатами команд.
9. **Обновлённая документация** — какие разделы и файлы изменены.

## Ограничения

- Native-бэкенд — только при активном профиле `native`; на этом этапе config-server запускается
  с `SPRING_PROFILES_ACTIVE=standalone,native` (файл остаётся `application-standalone.yml`).
  Не задавать `spring.profiles.active` в профильных файлах.
- Только HTTP Basic для Config Server; OAuth2/Keycloak-защита сервера — не в этом этапе.
- `spring.config.import` — только в `application-cloudconfig.yml`; не добавлять импорт в
  `standalone`/`cloud`/тесты и не использовать `optional:`.
- Не менять существующие ключи `standalone`/`cloud`-конфигов, кроме добавления
  `spring.cloud.config.enabled: false`.
- Не выдумывать имена свойств и классы Boot 4; при сомнении сверяться с официальной
  документацией и исходниками используемых версий.
- Не трогать Keycloak realm, секреты и клиентов; в native-файле использовать уже существующие
  значения из `application-cloud.yaml`.

## Критерии приёмки

- [ ] `./mvnw -pl config-server clean package` собирается, создан
      `config-server/target/config-server-0.0.1-SNAPSHOT-exec.jar`.
- [ ] `./mvnw -pl config-server verify` проходит; `ConfigServerApplicationIT` зелёный
      (200 с кредами и маркером `native-ok`, 401 без кредов), Docker не требуется.
- [ ] `SPRING_PROFILES_ACTIVE=standalone,native ./mvnw -pl config-server spring-boot:run` поднимает
      сервер на `8888`; в логе нет ошибок про git-репозиторий.
- [ ] `curl -s -u config:config-password http://localhost:8888/selmag-catalogue-service/cloudconfig`
      возвращает `200` и JSON, в `propertySources` есть источник
      `.../config/cloud/selmag-catalogue-service/cloudconfig/selmag-catalogue-service.yml`
      с ключами `spring.datasource.url`, `spring.security.oauth2.resourceserver.jwt.issuer-uri` и т. п.;
      тот же запрос без `-u` возвращает `401`.
- [ ] `curl -s -u config:config-password http://localhost:8888/selmag-catalogue-service/default`
      не роняет сервер (пустой `propertySources` — норма: каталога `default` нет).
- [ ] При работающих Keycloak (`8082`) и PostgreSQL (`5432`) запуск
      `SPRING_PROFILES_ACTIVE=cloudconfig ./mvnw -pl catalogue-service spring-boot:run` поднимает
      сервис на `8081`; в логе есть `Fetching config from server at http://localhost:8888` и
      `Located environment: name=selmag-catalogue-service, profiles=[cloudconfig]`; `jpa.show-sql`
      и issuer взяты с Config Server.
- [ ] Остановленный Config Server + запуск `catalogue-service` с `cloudconfig` → старт падает с
      `ConfigClientFailFastException` (никакого `optional:`).
- [ ] `SPRING_PROFILES_ACTIVE=standalone ./mvnw -pl catalogue-service spring-boot:run` работает
      без Config Server (datasource, Keycloak, Swagger — как раньше); `/actuator/health` — `UP`
      (health-индикатор `config` отключён, не `UNKNOWN`).
- [ ] `./mvnw -pl catalogue-service test` и `./mvnw verify` проходят без Config Server —
      подтверждает `spring.cloud.config.enabled: false` в standalone/cloud/тестовых конфигах.
- [ ] В `config/cloud` больше нет каталога с пробелом в имени и короткого `catalogue-service/`:
      `find config/cloud -type d` показывает `config/cloud/selmag-catalogue-service/cloudconfig`.
- [ ] В native-файле нет `172.17.0.1`, `lb://`, `spring.config.import`, `spring.application.name`,
      `server.port`: `grep -n -E "172\.17\.0\.1|lb://|config\.import|application:|port:" <file>`
      не находит совпадений.
- [ ] `config-server` запускается ровно с профилями `standalone` и `native`; файл настроек —
      `application-standalone.yml` (не переименован).
- [ ] `manager-app`, `customer-app`, `feedback-service`, `admin-server`, `eureka-server` и
      `config/keycloak/import/realm-export.json` не изменены (`git status`).
- [ ] `README.MD`, `CLAUDE.md`, `docs/CLOUD-SERVICES.md` обновлены и добавлен
      `docs/CONFIG-SERVER.md`: команда запуска, порт 8888, схема native-репозитория, матрица
      профилей, Basic-аутентификация, порядок запуска, типичные ошибки.
