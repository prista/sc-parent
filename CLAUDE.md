# CLAUDE.md

This project implements the system described in `SPEC.md`. Refer to that file for detailed architectural, database, and technical specifications.

Keep replies concise and focused on key information. Avoid unnecessary verbosity.

When working with third-party libraries, always consult official documentation to ensure up-to-date information. For efficient documentation lookup, prioritize the `context7` MCP server (`mcp__context7__*`), executing multiple queries in parallel when beneficial. If context7 does not yield sufficient results, fall back to `WebSearch` or `WebFetch`, focusing on official sources and concise information.

## Commands

- **Build Project:** `mvn clean install` (from project root, requires global Maven)
- **Run `catalogue-service`:** Navigate to `catalogue-service` directory, then `./mvnw spring-boot:run` (starts on port `8081`)
- **Run `manager-app`:** Navigate to `manager-app` directory, then `./mvnw spring-boot:run` (starts on port `8080`)
- **Access UI:** `http://localhost:8080/catalogue/products/list`
- **Database:** Two PostgreSQL databases are required — `catalogue` (port `5432`, user `catalogue`/`catalogue`) for `catalogue-service`, and `manager` (port `5433`, user `manager`/`manager`) for `manager-app`. See `README.MD` for `docker run` commands.
- **Keycloak:** Required for OAuth2 — run `selmag-keycloak` on port `8082` (realm `selmag`). See `README.MD` for the `docker run` command; realm config is at `config/keycloak/import/realm-export.json`.

## Architecture

This is a multi-module Spring Boot application comprised of two Spring Boot services plus Keycloak as the identity/authorization provider:

- **`catalogue-service`**: A backend REST API for product management. Stateless single source of truth for product data; exposes REST at `/catalogue-api/products`. OAuth2 **resource server** — validates JWT access tokens against the Keycloak issuer and authorizes by scopes (`SCOPE_view_catalogue` for reads, `SCOPE_edit_catalogue` for writes).
- **`manager-app`**: A server-side rendered web application providing the user interface, acting as an OAuth2 **client** to the `catalogue-service`. End users sign in via Keycloak (`oauth2Login`); outgoing service-to-service calls attach a Bearer access token (`OAuthClientHttpRequestInterceptor`).
- **`Keycloak`**: Authorization server / identity provider (realm `selmag`, issuer `http://localhost:8082/realms/selmag`). Realm config is exported at `config/keycloak/import/realm-export.json`.

### OAuth2 flow

1. Browser → `manager-app` (no session): redirected to Keycloak login; authorization-code flow returns an ID token + access token.
2. `manager-app` resolves the user's authorities from the ID token and the `groups` claim (only `ROLE_`-prefixed entries); all UI routes require `ROLE_MANAGER`.
3. `manager-app` → `catalogue-service`: `OAuthClientHttpRequestInterceptor` obtains a client access token (registration `keycloak`, scopes `view_catalogue`/`edit_catalogue`) and sends it as `Authorization: Bearer …`.
4. `catalogue-service` validates the JWT and checks the `SCOPE_*` authorities declared in its `SecurityConfig`.

### Tech Stack

- **Runtime:** Java 21
- **Framework:** Spring Boot
- **Build Tool:** Maven
- **Database:** PostgreSQL (two separate databases, managed by Flyway for migrations)
- **Templating (Frontend):** Thymeleaf
- **Security:** Spring Security — OAuth2 resource server (JWT) in `catalogue-service`; OAuth2 login + client in `manager-app`; Keycloak as IdP

### Key Dependencies

- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.boot:spring-boot-starter-data-jpa`
- `org.springframework.boot:spring-boot-starter-security`
- `org.springframework.boot:spring-boot-starter-oauth2-resource-server` (`catalogue-service`)
- `org.springframework.boot:spring-boot-starter-oauth2-client` (`manager-app`)
- `org.springframework.boot:spring-boot-starter-flyway`
- `org.flywaydb:flyway-database-postgresql`
- `org.postgresql:postgresql`
- `org.springframework.boot:spring-boot-starter-thymeleaf`
- `org.projectlombok:lombok` (for DTOs and entities)

### Project Structure

- `./` - Maven parent project
- `catalogue-service/` - Backend REST API module
  - `src/main/java/com/drm/sandbox/catalogue/` - Java source
  - `src/main/resources/application-standalone.yaml` - Service configuration
  - `src/main/resources/db/migration/` - Flyway SQL migration scripts
- `manager-app/` - Frontend web application module
  - `src/main/java/com/drm/sandbox/manager/` - Java source
  - `src/main/java/com/drm/sandbox/manager/entity/` - JPA entities (`User`, `Authority`, `Product`)
  - `src/main/java/com/drm/sandbox/manager/repository/` - Spring Data repositories (`UserRepository`)
  - `src/main/java/com/drm/sandbox/manager/security/` - `OAuthClientHttpRequestInterceptor` (Bearer token for service-to-service calls); `MUserDetailService` (legacy, not wired into the filter chain)
  - `src/main/java/com/drm/sandbox/manager/config/` - `SecurityConfig` (OAuth2 login + client), `ClientBeans` (RestClient + OAuth interceptor)
  - `src/main/resources/application-standalone.yaml` - Application configuration
  - `src/main/resources/db/migration/` - Flyway SQL migration scripts
  - `src/main/resources/templates/` - Thymeleaf HTML templates