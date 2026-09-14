docker run --name catalogue-db -p 5432:5432 -e POSTGRES_DB=catalogue -e POSTGRES_USER=catalogue -e POSTGRES_PASSWORD=catalogue postgres:17.4-alpine

docker run --name manager-db -p 5433:5432 -e POSTGRES_DB=manager -e POSTGRES_USER=manager -e POSTGRES_PASSWORD=manager postgres:17.4-alpine

docker run --name selmag-keycloak -p 8082:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin -v ./config/keycloak/import:/opt/keycloak/data/import quay.io/keycloak/keycloak:26.1.3 start-dev --import-realm

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