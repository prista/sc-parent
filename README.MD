docker run --name catalogue-db -p 5432:5432 -e POSTGRES_DB=catalogue -e POSTGRES_USER=catalogue -e POSTGRES_PASSWORD=catalogue postgres:17.4-alpine

docker run --name manager-db -p 5433:5432 -e POSTGRES_DB=manager -e POSTGRES_USER=manager -e POSTGRES_PASSWORD=manager postgres:17.4-alpine

docker run --name selmag-keycloak -p 8082:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin -v ./config/keycloak/import:/opt/keycloak/data/import quay.io/keycloak/keycloak:26.1.3 start-dev --import-realm

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