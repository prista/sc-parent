docker run --name catalogue-db -p 5432:5432 -e POSTGRES_DB=catalogue -e POSTGRES_USER=catalogue -e POSTGRES_PASSWORD=catalogue postgres:17.4-alpine

docker run --name manager-db -p 5433:5432 -e POSTGRES_DB=manager -e POSTGRES_USER=manager -e POSTGRES_PASSWORD=manager postgres:17.4-alpine

docker run --name selmag-keycloak -p 8082:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin -v ./config/keycloak/import:/opt/keycloak/data/import quay.io/keycloak/keycloak:26.1.3 start-dev --import-realm

docker run --name feedback-db -p 27017:27017 mongo:8

docker exec -it feedback-db mongosh feedback

lsof -i tcp:3000
kill -9 <PID>

## SSR

`manager-app` — это **SSR** (Server-Side Rendering): HTML-страницы собираются на сервере из Thymeleaf-шаблонов и отдаются браузеру уже готовыми, а не рендерятся на клиенте JavaScript'ом (как в JS-SPA). В интеграционных тестах это видно по проверкам `view().name("...")` и `model().attribute(...)` — сервер сам решает, какой шаблон отдать и что положить в модель.

## OAuth2 Flow

Роли: `manager-app` — OAuth2 клиент, `catalogue-service` — resource server, Keycloak (realm `selmag`) — IdP.

1. Браузер → `manager-app` (сессии нет) → редирект на Keycloak (`oauth2Login`).
2. Юзер логинится в Keycloak; обратно приходит `authorization_code`.
3. `manager-app` обменивает code на ID token + access token (scopes `view_catalogue`, `edit_catalogue`) и хранит их в сессии юзера.
4. `manager-app` → `catalogue-service`: `OAuthClientHttpRequestInterceptor` достаёт access token и шлёт его как `Authorization: Bearer …` — это **token relay**: токен юзера, а не отдельный сервисный аккаунт.
5. `catalogue-service` валидирует JWT и проверяет `SCOPE_view_catalogue` / `SCOPE_edit_catalogue`.

Нюанс: каталог авторизует только по scope (атрибут клиента), хотя токен несёт identity юзера (`sub`, `email`, роли). Если права должны зависеть от юзера — проверять роли (`ROLE_MANAGER`) в `catalogue-service`, а не только scope; если юзер в решении не участвует — честнее `client_credentials`.

###  теперь давай разберемся в oauth2 flow. У нас есть manager клиент, есть catalogue resource server. Приходит юзер, стучится на продукт, его редиректит на кейклок, он
логинится и всё ок. Взаимодействие с каталогом межсервисное в этом случае? Юзер то залогинился в manager-app? или я не понял чего-то/


⏺ Да, ты понял правильно — юзер логинится именно в manager-app (через Keycloak). А вот звонок к каталогу — это уже серверный запрос от manager-app к catalogue-service, но с    
токеном пользователя, а не с отдельным сервисным токеном. Это ключевой момент, который легко спутать.
Что происходит по шагам

1. Браузер → GET /catalogue/products/4 (manager-app). Сессии нет → oauth2Login редиректит на Keycloak.
2. Юзер логинится в Keycloak (в браузере).
3. Keycloak редиректит обратно на manager-app с authorization_code.
4. manager-app обменивает code на токены (ID token + access token + refresh token). Access token содержит scopes view_catalogue, edit_catalogue (их запросил клиент           
   manager-app).
5. manager-app хранит этот OAuth2AuthorizedClient в сессии юзера.
6. Контроллер ProductController.product() дёргает productsRestClient.findProduct(4) → OAuthClientHttpRequestInterceptor достаёт сохранённый access token и шлёт GET           
   /catalogue-api/products/4 с Authorization: Bearer <токен юзера>.
7. catalogue-service валидирует JWT и проверяет scope view_catalogue.

«Межсервисное» ли это?

Формально да — HTTP-запрос делает manager-app → catalogue-service, браузер в нём не участвует. Но это не классический machine-to-machine (client_credentials), где сервис     
аутентифицируется своим client_id + secret и получает токен для своего service-аккаунта.

Здесь — token relay / propagation: токен, который manager-app пересылает в каталог, выпущен на юзера (subject = юзер). Это видно по коду интерцептора:

OAuth2AuthorizeRequest.withClientRegistrationId(this.registrationId)                                                                                                          
.principal(this.securityContextHolderStrategy.getContext().getAuthentication())  // текущий юзер

Именно из-за .principal(...) менеджер возвращает user-bound access token (и рефрешит его, если протух), а не делает отдельный client_credentials запрос.

Доказательство, что каталог видит именно пользователя, а не сервисный аккаунт — в catalogue-service:

log.info("Principal {}", ((JwtAuthenticationToken) principal).getToken().getClaimAsString("email"));

То есть в JWT на стороне resource server читается email юзера.

Итог по терминам

┌──────────────────────────────────┬────────────────────────────────────────────────────────────────────┐                                                                     
│                                  │                           В этом проекте                           │                                                                     
├──────────────────────────────────┼────────────────────────────────────────────────────────────────────┤                                                                     
│ Кто аутентифицируется в Keycloak │ Пользователь (authorization code flow)                             │                                                                     
├──────────────────────────────────┼────────────────────────────────────────────────────────────────────┤                                                                     
│ Кто «логинится» в manager-app    │ Пользователь (сессия + OidcUser)                                   │                                                                     
├──────────────────────────────────┼────────────────────────────────────────────────────────────────────┤                                                                     
│ Кто ходит в catalogue-service    │ manager-app (сервер-сервер), но с токеном пользователя             │                                                                     
├──────────────────────────────────┼────────────────────────────────────────────────────────────────────┤                                                                     
│ Что проверяет catalogue-service  │ Валидность JWT + scope SCOPE_view_catalogue / SCOPE_edit_catalogue │                                                                     
├──────────────────────────────────┼────────────────────────────────────────────────────────────────────┤                                                                     
│ Тип flow                         │ Authorization code + token relay (НЕ client_credentials)           │                                                                     
└──────────────────────────────────┴────────────────────────────────────────────────────────────────────┘

Один нюанс на будущее: каталог авторизует только по scope, а не по конкретному юзеру. То есть права определяются клиентскими scope'ами (view_catalogue/edit_catalogue),       
которые manager-app запросил — а не тем, какой именно юзер залогинился. Если понадобится разграничение «этот юзер может менять, а этот — только смотреть», придётся проверять
роли/группы юзера в самом catalogue-service, а не только scope.

## Security (OAuth2): роли сервисов

**`catalogue-service` — resource server.** Валидирует JWT на входящих запросах (`.oauth2ResourceServer` + `jwt()`), работает без сессии (`STATELESS`), CSRF отключён. Авторизует по scope:

- чтение (`GET`) → `SCOPE_view_catalogue`;
- запись (`POST`/`PATCH`/`DELETE`) → `SCOPE_edit_catalogue`;
- всё остальное → `denyAll`.

**`manager-app` — OAuth2 client в двух ролях одновременно** (но **не** resource server):

1. **Логин пользователей** — `oauth2Login`: юзер логинится через Keycloak (authorization code flow), `manager-app` получает ID token + access token и хранит в сессии. Входящие запросы — session-based (в `SecurityContext` лежит `OidcUser`), JWT на входе не валидируется.
2. **Исходящие вызовы** — `oauth2Client` + `OAuthClientHttpRequestInterceptor`: берёт из сессии access token юзера и шлёт в `catalogue-service` как `Authorization: Bearer …` (token relay).

Роли: кастомный `oAuth2UserService` читает `groups`-claim из ID token, оставляет только `ROLE_`-записи и маппит их в `SimpleGrantedAuthority`, поэтому работает правило `.anyRequest().hasRole("MANAGER")` — все UI-маршруты требуют `ROLE_MANAGER`.

| | `catalogue-service` | `manager-app` |
|---|---|---|
| Роль в OAuth2 | resource server | client (login + исходящие вызовы) |
| Входящие запросы | валидирует JWT | session-based (`OidcUser`) |
| Авторизация | по `SCOPE_*` | по роли `ROLE_MANAGER` |
| Исходящие вызовы | — | interceptor → Bearer-токен юзера |

### Покрытие тестами

- ✅ `catalogue-service`, проверка `SCOPE_*`: `ProductsRestControllerIT` / `ProductRestControllerIT` через `.with(jwt().jwt(b -> b.claim("scope", "view_catalogue"/"edit_catalogue")))` — в т.ч. негативный кейс (валидный JWT без нужного scope → 403).
- ✅ `manager-app`, проверка `ROLE_MANAGER`: IT-тесты контроллеров (`..._UserIsNotAuthorized_ReturnsForbidden`) — `.with(user("j.daniels"))` без роли → 403.
- ✅ token relay: `OAuthClientHttpRequestInterceptorTest` — проверяет, что при отсутствии `Authorization` интерцептор добавляет `Bearer token`.

## Тестирование

- Юнит-тесты: `./mvnw test`
- Юнит + интеграционные: `./mvnw verify` (интеграционные тесты поднимают PostgreSQL через Testcontainers — нужен запущенный Docker)

### Почему интеграционные тесты не запускались на `verify`

`spring-boot-maven-plugin` по умолчанию на фазе `package` перепаковывает тонкий (thin) jar в fat jar и **подменяет им основной артефакт**. У fat jar другая структура (`BOOT-INF/classes`, `BOOT-INF/lib`), а `maven-failsafe-plugin` гоняет интеграционные тесты на фазе `integration-test`/`verify`, то есть **после** `package`, — и на classpath попадал fat jar вместо тонкого, из-за чего тесты не могли поднять контекст и просто не отрабатывали.

Исправление — задали классификатор в `spring-boot-maven-plugin` (в обоих модулях):

```xml
<configuration>
    <classifier>exec</classifier>
</configuration>
```

Теперь `repackage` кладёт fat jar в отдельный артефакт `<module>-<version>-exec.jar`, а основной артефакт остаётся тонким jar — именно его и видит failsafe на `verify`.

### WireMock: зачем `@WireMockTest(httpPort = 54321)` в интеграционных тестах `manager-app`

`manager-app` сам не хранит товары — контроллеры (`ProductsController`, `ProductController`) на каждый запрос ходят в `catalogue-service` через `ProductsRestClient` → `RestClient` по адресу `/catalogue-api/products...`. В проде `baseUrl` = `http://localhost:8081` (`ClientBeans`), но в тесте настоящий `catalogue-service` не поднимается.

- `@WireMockTest(httpPort = 54321)` поднимает **настоящий HTTP-сервер** WireMock на порту `54321`.
- `TestingBeans` (`@Primary`) подменяет `baseUrl` на `http://localhost:54321`, поэтому все исходящие вызовы `RestClient` попадают в WireMock, а не в реальный сервис.
- Порт `54321` выбран произвольно — важно лишь, чтобы он совпадал со значением из `TestingBeans` (`selmag.services.catalogue.uri`) и не конфликтовал с `8080`/`8081`/`8082`.

В тестах WireMock используется двумя способами:

- **`stubFor(...)`** — «притворяется» `catalogue-service`: задаёт ответ на запрос (товар есть → `200` + JSON, нет → `404`, валидация → `400` + `application/problem+json`). Так управляем сценарием.
- **`verify(...)`** — проверяет, что `manager-app` реально сделал вызов с нужными параметрами (method, path, query, тело через `equalToJson`).

**Почему не Mockito.** Это два разных уровня, на которых «перерезается» зависимость:

| | Mockito | WireMock |
|---|---|---|
| Где перехватывает | вызов метода внутри JVM | реальный HTTP поверх TCP |
| Что тестируется | только логика контроллера | контроллер → `RestClient` → HTTP (сериализация JSON, method/path/query/body, разбор статусов) |
| Ловит ли неверный path/метод/десериализацию | нет | да (`verify` + `stubFor`) |

Ключевая логика, которую покрывает WireMock, живёт внутри `RestClientProductsRestClient` и зависит от HTTP-ответа: `200` → `Product`, `404` → `Optional.empty()`, `400` + `problem+json` → исключение со списком `errors`, `204` → ок. Замокав метод Mockito, ты подставляешь конечный результат и эту логику не тестируешь.

В проекте оба варианта есть рядом: `ProductsControllerTest` — unit-тест на Mockito (мокает `ProductsRestClient`), `ProductsControllerIT` / `ProductControllerIT` — интеграционные на WireMock.

## Документация API (Spring REST Docs)

В проекте есть пример Spring REST Docs — документация API генерируется **из интеграционных тестов**, а не из аннотаций, как в SpringDoc/OpenAPI (Swagger).

- `catalogue-service` — `ProductRestControllerIT` (тест `find_all`) документирует получение товара: сниппеты пишутся в `catalogue-service/target/generated-snippets/catalogue/products/find_all/`, исходник документа — `catalogue-service/src/asciidoc/documentation.adoc`.
- `feedback-service` — `ProductReviewsRestControllerTestIT` (тест создания отзыва) документирует `POST /api/v1/feedback-api/product-reviews`: сниппеты пишутся в `feedback-service/target/generated-snippets/feedback/product_reviews/create_product_review/`, исходник — `feedback-service/src/main/asciidoc/documentation.adoc`.

Как это работает: тест выполняет реальный запрос через MockMvc/WebTestClient и вызывает `document("...")` — REST Docs сохраняет сниппеты по **фактическому** запросу и ответу. Затем `documentation.adoc` подключает их через `include::{snippets}/...`, а `asciidoctor-maven-plugin` (настроен в родительском `pom.xml`) собирает HTML.

Поэтому документация появляется только после `./mvnw verify` или `./mvnw install` (не после `./mvnw test`); для `catalogue-service` и `feedback-service` при этом нужен Docker (Testcontainers). HTML-результат — в `target/generated-docs/documentation.html` соответствующего модуля.

**Отличие от SpringDoc.** SpringDoc строит OpenAPI-спецификацию автоматически — из аннотаций (или прямо из рантайма запущенного приложения), и тесты для этого не нужны. REST Docs наоборот делает источником правды IT-тесты: примеры в документации — это реальные запросы/ответы, поэтому документация не может разойтись с кодом (устаревший пример ломает тест). Цена — нужно писать и поддерживать тесты, и задокументированы только те эндпоинты, которые ими покрыты.

## Swagger UI (SpringDoc) и авторизация через Keycloak

OpenAPI-спецификацию параллельно с REST Docs отдают два сервиса:

| Сервис | Зависимость | UI | Спека |
|---|---|---|---|
| `catalogue-service` (Spring MVC) | `springdoc-openapi-starter-webmvc-ui` 3.0.3 | `http://localhost:8081/swagger-ui/index.html` | `http://localhost:8081/v3/api-docs` |
| `feedback-service` (WebFlux) | `springdoc-openapi-starter-webflux-ui` 3.0.3 | `http://localhost:8084/swagger-ui/index.html` | `http://localhost:8084/v3/api-docs` |

Пути `/swagger-ui/**`, `/webjars/**`, `/swagger-ui.html` и `/v3/api-docs/**` открыты в `SecurityConfig` (`permitAll()`) — иначе resource server требовал бы JWT даже на страницу документации.

Схема безопасности описана аннотацией `@SecurityScheme(name = "keycloak", type = OAUTH2, flows = authorizationCode …)` на главном классе каждого сервиса (`CatalogueServiceApplication`, `FeedbackServiceApplication`): `authorizationUrl` и `tokenUrl` указывают на realm `selmag`.

### `catalogue-service`

Кнопка **Authorize** настраивается в `application-standalone.yaml`:

| Свойство | Значение | Зачем |
|---|---|---|
| `springdoc.swagger-ui.oauth.client-id` | `catalogue-service-swagger-ui` | клиент, от имени которого Swagger UI ходит в Keycloak |
| `springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant` | `true` | клиент публичный (без секрета), `code` обменивается с PKCE |
| `springdoc.swagger-ui.oauth2-redirect-url` | `http://localhost:8081/swagger-ui/oauth2-redirect.html` | страница, куда Keycloak вернёт `code` |
| `springdoc.swagger-ui.persist-authorization` | `true` | токен не теряется при перезагрузке страницы |

В Keycloak заведён отдельный клиент `catalogue-service-swagger-ui` (`config/keycloak/import/realm-export.json`):

- `publicClient: true`, `standardFlowEnabled: true`, `directAccessGrantsEnabled: false`;
- `redirectUris: ["http://localhost:8081/swagger-ui/*"]`;
- `webOrigins: ["+"]`;
- `edit_catalogue` / `view_catalogue` — в `optionalClientScopes`, чтобы их можно было запросить.

### `feedback-service` (WebFlux)

Та же схема, но клиент реактивный, и кастомных scope'ов у сервиса нет: `SecurityConfig` требует только аутентификацию (`anyExchange().authenticated()`), поэтому в `@SecurityScheme` достаточно дефолтных `openid` / `microprofile-jwt` (это отмечено комментарием в `FeedbackServiceApplication`).

| Свойство | Значение |
|---|---|
| `springdoc.swagger-ui.oauth.client-id` | `feedback-service-swagger-ui` |
| `springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant` | `true` |
| `springdoc.swagger-ui.oauth2-redirect-url` | **не задан** → springdoc сам подставляет `http://localhost:8084/swagger-ui/oauth2-redirect.html` |

Клиент `feedback-service-swagger-ui` в Keycloak: публичный, PKCE, `redirectUris: ["http://localhost:8084/swagger-ui/*", "http://localhost:8084/webjars/swagger-ui/*"]`, `webOrigins: ["http://localhost:8084"]`.

`@SecurityRequirement(name = "keycloak")` здесь выставлен только на `GET /api/v1/feedback-api/product-reviews/by-product-id/{productId}`. Остальные операции (создание отзыва, favourites) в спеке без `security`, хотя токен нужен всем — для них Try-it-out пойдёт без `Authorization` и получит 401.

### Откуда берётся путь `oauth2-redirect.html` (и при чём тут `webjars`)

Swagger UI — это JS-бандл, который распространяется как **WebJar** (`org.webjars:swagger-ui`): файлы лежат в jar'е под `META-INF/resources/webjars/swagger-ui/<версия>/…`, и Spring Boot автоматически раздаёт эту папку по URL `/webjars/**` (резолвер умеет подставлять версию, поэтому путь без неё тоже работает). Там же лежит `oauth2-redirect.html` — страничка, которая принимает `code` и отдаёт его родительскому окну.

springdoc дополнительно публикует те же файлы по короткому алиасу `/swagger-ui/**` (плюс `swagger-ui.html` → редирект на `index.html`), поэтому страница доступна по обоим путям:

```bash
curl -o /dev/null -s -w "%{http_code}\n" http://localhost:8084/swagger-ui/oauth2-redirect.html          # 200
curl -o /dev/null -s -w "%{http_code}\n" http://localhost:8084/webjars/swagger-ui/oauth2-redirect.html  # 200
```

Но Keycloak сверяет `redirect_uri` посимвольно (с `*` только в конце паттерна), а какой именно путь уйдёт в запрос, решает springdoc — он кладёт его в конфиг UI:

```bash
curl -s http://localhost:8084/v3/api-docs/swagger-config
# {"configUrl":"/v3/api-docs/swagger-config","oauth2RedirectUrl":"http://localhost:8084/swagger-ui/oauth2-redirect.html", ...}
```

Поэтому в клиенте обязателен `/swagger-ui/oauth2-redirect.html` (покрывается паттерном `/swagger-ui/*`). Если оставить только `/webjars/swagger-ui/*`, Keycloak ответит **400** страницей `Invalid parameter: redirect_uri` — в попапе Swagger UI это выглядит как ошибка авторизации:

```bash
# /swagger-ui/... → 400 (redirect_uri не разрешён), /webjars/swagger-ui/... → 302 на форму логина
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8082/realms/selmag/protocol/openid-connect/auth?client_id=feedback-service-swagger-ui&response_type=code&scope=openid&redirect_uri=http%3A%2F%2Flocalhost%3A8084%2Fswagger-ui%2Foauth2-redirect.html"
```

Паттерн `/webjars/swagger-ui/*` функционально не нужен: используется только тот путь, который springdoc положил в `oauth2RedirectUrl`. Он понадобится, лишь если явно задать `springdoc.swagger-ui.oauth2-redirect-url` на webjars-путь.

### Зачем `webOrigins` (иначе `TypeError: Failed to fetch`)

Swagger UI — это JS в браузере: страница с `http://localhost:8081` сама делает `fetch()` на token-endpoint Keycloak (`http://localhost:8082`), обменивая `code` на access token. Это cross-origin запрос, а Keycloak отдаёт заголовок `Access-Control-Allow-Origin` только для origin'ов, перечисленных в **Web origins** клиента. При пустом списке заголовка нет → браузер выбрасывает ответ → в окне Authorize появляется `Auth Error: TypeError: Failed to fetch`. Значение `+` означает «взять origin'ы из Valid redirect URIs» (в нашем случае `http://localhost:8081`).

Проверить без браузера:

```bash
curl -s -i -X POST http://localhost:8082/realms/selmag/protocol/openid-connect/token \
  -H "Origin: http://localhost:8081" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code&code=fake&client_id=catalogue-service-swagger-ui&redirect_uri=http://localhost:8081/swagger-ui/oauth2-redirect.html" \
  | grep -i access-control-allow-origin
```

Должен быть `Access-Control-Allow-Origin: http://localhost:8081` (`invalid_grant` в теле — норма, код фейковый). Заодно видно разницу: у клиента, упавшего на аутентификации (например `customer-app` без секрета), Keycloak вызывает `allowAllOrigins()` и заголовок появляется — поэтому «на одном клиенте работает, на другом нет».

### Подводные камни

- `--import-realm` применяет `realm-export.json` только при **первом** создании реалма. Если контейнер `selmag-keycloak` уже существует, правки в файле на него не влияют: Web origins / Valid redirect URIs нужно править в консоли (Clients → `…-swagger-ui` → соответствующая вкладка) или пересоздавать контейнер.
- Swagger UI подставляет `Authorization: Bearer` в Try-it-out только для операций, помеченных security-требованием. Оно задаётся **на операции** (`security = @SecurityRequirement(name = "keycloak")` в `@Operation`), а не глобально: сейчас так помечены `POST /catalogue-api/products` и `GET /api/v1/feedback-api/product-reviews/by-product-id/{productId}`. Остальные операции в спеке без требования — если их тоже нужно дергать из Swagger с токеном, аннотацию надо добавить и туда (или задать глобально через `@OpenAPIDefinition(security = …)`).
- Экспорт реалма из админки Keycloak **маскирует** `secret` клиентов как `"**********"`. Такой файл нельзя коммитить как есть: на чистом `--import-realm` секрет не совпадёт с тем, что прописан в `manager-app` / `customer-app` (`application-standalone.*`), и оба приложения сломаются на обмене `code` → token.
- В scopes схемы, кроме дефолтных `openid` / `microprofile-jwt`, должны быть `view_catalogue` и `edit_catalogue` — иначе нужный scope не выбрать в окне Authorize, в токене не будет `SCOPE_edit_catalogue`, и `POST` вернёт 403. У клиента `catalogue-service-swagger-ui` оба scope'а лежат в `optionalClientScopes`.