docker run --name catalogue-db -p 5432:5432 -e POSTGRES_DB=catalogue -e POSTGRES_USER=catalogue -e POSTGRES_PASSWORD=catalogue postgres:17.4-alpine

docker run --name manager-db -p 5433:5432 -e POSTGRES_DB=manager -e POSTGRES_USER=manager -e POSTGRES_PASSWORD=manager postgres:17.4-alpine

docker run --name selmag-keycloak -p 8082:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin -v ./config/keycloak/import:/opt/keycloak/data/import quay.io/keycloak/keycloak:26.1.3 start-dev --import-realm

## OAuth2 Flow

Роли: `manager-app` — OAuth2 клиент, `catalogue-service` — resource server, Keycloak (realm `selmag`) — IdP.

### Вход юзера в UI (authorization code)

1. Браузер → `manager-app` (сессии нет) → редирект на Keycloak (`oauth2Login`).
2. Юзер логинится в Keycloak; обратно приходит `authorization_code`.
3. `manager-app` обменивает code на ID token + access token и хранит их в сессии юзера.
4. Все маршруты UI требуют роль `ROLE_MANAGER` (берётся из `groups` claim ID token).

### Межсервисный вызов (machine-to-machine, `client_credentials`)

1. `manager-app` → `catalogue-service`: `OAuthClientHttpRequestInterceptor` через `AuthorizedClientServiceOAuth2AuthorizedClientManager` получает **service-account** access token (регистрация `catalogue-service`, grant `client_credentials`, scopes `view_catalogue` / `edit_catalogue`) и шлёт его как `Authorization: Bearer …`.
2. Вызов не зависит от залогиненного юзера: в каталог ходит токен сервисного аккаунта `service-account-manager-app`, а не токен юзера.
3. `catalogue-service` валидирует JWT и проверяет `SCOPE_view_catalogue` / `SCOPE_edit_catalogue`.

Нюанс: каталог авторизует только по scope, поэтому identity юзера в нём отсутствует (в логах `email` = null, `sub` = service account). Если права должны зависеть от конкретного юзера — нужно вернуться к token relay (проброс токена юзера) или Token Exchange, а не `client_credentials`.
