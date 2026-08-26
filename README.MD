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