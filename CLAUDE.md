# CLAUDE.md

This project implements the system described in `SPEC.md`. Refer to that file for detailed architectural, database, and technical specifications.

Keep replies concise and focused on key information. Avoid unnecessary verbosity.

When working with third-party libraries, always consult official documentation to ensure up-to-date information. For efficient documentation lookup, prioritize the `context7` MCP server (`mcp__context7__*`), executing multiple queries in parallel when beneficial. If context7 does not yield sufficient results, fall back to `WebSearch` or `WebFetch`, focusing on official sources and concise information.

## Commands

- **Build Project:** `./mvnw clean install` (from project root; builds all four modules)
- **Executable JAR:** each service's `spring-boot-maven-plugin` (`repackage` goal) bundles the app and its dependencies into a self-contained executable JAR runnable via `java -jar`, excludes Lombok, and adds the `-exec` classifier.
- **Run Tests:** `./mvnw test` (from project root; runs all four modules). For a single test class: `./mvnw -pl catalogue-service -Dtest=ProductsRestControllerIT test`. The `catalogue-service` and `manager-app` integration tests use Testcontainers, so Docker must be running; `customer-app` has controller unit tests, `feedback-service` has none yet.
- **Testcontainers (tests):** the `catalogue-service` integration tests start an ephemeral PostgreSQL container (`postgres:17.4-alpine`) via the `jdbc:tc:postgresql:...` JDBC URL in `catalogue-service/src/test/resources/application.yml`; `TC_DAEMON=true` keeps the container running and reused across test runs. The same applies to `manager-app/src/test/resources/application.yml`.
- **Run `manager-app`:** `./mvnw -pl manager-app spring-boot:run` (from project root, starts on port `8080`)
- **Run `catalogue-service`:** `./mvnw -pl catalogue-service spring-boot:run` (from project root, starts on port `8081`)
- **Run `customer-app`:** `./mvnw -pl customer-app spring-boot:run` (from project root, starts on port `8083`; needs Keycloak — the storefront is an OAuth2 client and redirects to the login page)
- **Run `feedback-service`:** `./mvnw -pl feedback-service spring-boot:run` (from project root, starts on port `8084`)
- **Access UI:** manager UI `http://localhost:8080/catalogue/products/list` (requires Keycloak sign-in); customer storefront `http://localhost:8083/customer/products/list` (also requires Keycloak sign-in — it is an OAuth2 client now, not a public page).
- **Database:** Two PostgreSQL databases are required — `catalogue` (port `5432`, user `catalogue`/`catalogue`) for `catalogue-service`, and `manager` (port `5433`, user `manager`/`manager`) for `manager-app`. See `README.MD` for `docker run` commands. `feedback-service` uses MongoDB instead (`mongodb://localhost:27017/feedback`).
- **Keycloak:** Required for OAuth2 — run `selmag-keycloak` on port `8082` (realm `selmag`). See `README.MD` for the `docker run` command; realm config is at `config/keycloak/import/realm-export.json`. Needed by **all four** services: `manager-app` and `customer-app` sign users in (`oauth2Login`) and relay that user's token downstream, and both resource servers resolve their JWT decoder from the realm issuer — with Keycloak down no service can authenticate anything (and none of them falls back to running unsecured). The exported realm already defines both clients — `manager-app` (redirect URI `http://localhost:8080/*`) and `customer-app` (root URL `http://localhost:8083` with the relative redirect URI `/*`) — but **no human users**, only the two service accounts, so a realm user has to be created (and put into the `managers` / `customers` group) before either UI can be signed into.

## Architecture

This is a multi-module Spring Boot application comprised of four services plus Keycloak as the identity/authorization provider. Two are blocking Spring MVC apps; two are reactive WebFlux apps:

- **`catalogue-service`**: A backend REST API for product management. Stateless single source of truth for product data; exposes REST at `/catalogue-api/products`. OAuth2 **resource server** — validates JWT access tokens against the Keycloak issuer. Reads (`GET`) are public (`permitAll()`); writes (`POST`/`PATCH`/`DELETE`) require `SCOPE_edit_catalogue`.
- **`manager-app`**: A server-side rendered (Spring MVC) admin UI, acting as an OAuth2 **client** to the `catalogue-service`. End users sign in via Keycloak (`oauth2Login`); outgoing service-to-service calls attach a Bearer access token (`OAuthClientHttpRequestInterceptor`).
- **`feedback-service`**: A reactive (WebFlux) REST API for product feedback — favourite products and product reviews — backed by **MongoDB** (`spring-boot-starter-data-mongodb-reactive`). Exposes `/api/v1/feedback-api/**`. OAuth2 **resource server** — validates JWT access tokens against the Keycloak issuer and requires authentication on **every** exchange (`anyExchange().authenticated()`, no `SCOPE_*` check), stateless, CSRF disabled. Favourites are **per user**: the controllers take a `Mono<JwtAuthenticationToken>` and pass `token.getToken().getSubject()` (the Keycloak `sub`) down into the service and repository, so a `FavouriteProduct` carries a `userId` and every favourite read/delete is scoped by it.
- **`customer-app`**: A reactive (WebFlux) server-side rendered storefront (reactive Thymeleaf). It is an OAuth2 **client** as well: `config/SecurityConfig` (`ServerHttpSecurity`/`SecurityWebFilterChain`) requires an authenticated user on **every** exchange (`anyExchange().authenticated()`) — the same reactive shape as `feedback-service`, but with `oauth2Login` + `oauth2Client` instead of a resource server, and with CSRF **enabled** (so the POST forms need a token). Browsing the storefront therefore redirects to the Keycloak login page. Its calls to `catalogue-service` (products) and `feedback-service` (favourites/reviews) go through reactive `WebClient` clients built from a `WebClient.Builder` that carries `ServerOAuth2AuthorizedClientExchangeFilterFunction` (default client registration `keycloak`) — a **token relay** that attaches the signed-in user's access token, which is what lets it call the secured `feedback-service` and lets that service key favourites off the user's `sub`.
- **`Keycloak`**: Authorization server / identity provider (realm `selmag`, issuer `http://localhost:8082/realms/selmag`). Realm config is exported at `config/keycloak/import/realm-export.json`.

### OAuth2 flow

Two clients sign users in; both relay the user's token downstream (no `client_credentials`).

1. Browser → `manager-app` (no session): redirected to Keycloak login; authorization-code flow returns an ID token + access token.
2. `manager-app` resolves the user's authorities from the ID token and the `groups` claim (only `ROLE_`-prefixed entries); all UI routes require `ROLE_MANAGER`.
3. `manager-app` → `catalogue-service`: `OAuthClientHttpRequestInterceptor` takes the signed-in user's access token (registration `keycloak`, scopes `view_catalogue`/`edit_catalogue`) and sends it as `Authorization: Bearer …`.
4. `catalogue-service` validates the JWT and checks the `SCOPE_*` authorities declared in its `SecurityConfig`.
5. Browser → `customer-app` (no session): same authorization-code flow, registration `keycloak` (client id `customer-app`, scopes `openid`/`view_catalogue`), every exchange requires authentication. On the way out, the `WebClient.Builder`'s `ServerOAuth2AuthorizedClientExchangeFilterFunction` attaches that user's token to the calls to `catalogue-service` and `feedback-service`.
6. `feedback-service` is a second resource server: it validates the same Keycloak-issued JWTs against the same issuer (`spring.security.oauth2.resourceserver.jwt.issuer-uri`), but its rule is `anyExchange().authenticated()` — every endpoint needs a token and no scope is checked. The reactive equivalent of the servlet config: `ServerHttpSecurity`/`SecurityWebFilterChain`, `authorizeExchange`, `NoOpServerSecurityContextRepository`. It reads the token's `sub` to scope favourites per user.

`catalogue-service` still marks `GET` as `permitAll()`, but nothing reaches it unauthenticated any more: `customer-app` demands a login before rendering any page and relays the user's token on every outgoing call, and `feedback-service` has no public endpoint at all.

### Tech Stack

- **Runtime:** Java 21
- **Framework:** Spring Boot 4.0
- **Build Tool:** Maven
- **Blocking (MVC):** `manager-app` (Spring MVC + Thymeleaf) and `catalogue-service` (Spring MVC REST) — Spring Web + `RestClient`.
- **Reactive (WebFlux):** `customer-app` (WebFlux + reactive Thymeleaf) and `feedback-service` (WebFlux REST) — Reactor (`Mono`/`Flux`) + reactive `WebClient`.
- **Database:** PostgreSQL (two separate databases, managed by Flyway for migrations) for `catalogue-service` and `manager-app`; MongoDB (reactive, schema-less) for `feedback-service`.
- **Templating (Frontend):** Thymeleaf
- **Security:** Spring Security — OAuth2 resource server (JWT) in `catalogue-service` (servlet: `HttpSecurity`/`SecurityFilterChain`) and in `feedback-service` (reactive: `ServerHttpSecurity`/`SecurityWebFilterChain`); OAuth2 login + client (token relay) in `manager-app` (servlet) and `customer-app` (reactive); Keycloak as IdP

### Key Dependencies

- `org.springframework.boot:spring-boot-starter-web` (`catalogue-service`, `manager-app`)
- `org.springframework.boot:spring-boot-starter-webflux` (`customer-app`, `feedback-service`)
- `org.springframework.boot:spring-boot-starter-data-jpa`
- `org.springframework.boot:spring-boot-starter-data-mongodb-reactive` (`feedback-service`)
- `org.springframework.boot:spring-boot-starter-security`
- `org.springframework.boot:spring-boot-starter-oauth2-resource-server` (`catalogue-service`, `feedback-service`)
- `org.springframework.boot:spring-boot-starter-oauth2-client` (`manager-app`, `customer-app`)
- `org.springframework.boot:spring-boot-starter-validation` (`catalogue-service`, `feedback-service`)
- `org.springframework.boot:spring-boot-starter-flyway`
- `org.flywaydb:flyway-database-postgresql`
- `org.postgresql:postgresql`
- `org.springframework.boot:spring-boot-starter-thymeleaf`
- `org.projectlombok:lombok` (for DTOs and entities)
- `org.springframework.boot:spring-boot-starter-test` (test)
- `org.springframework.boot:spring-boot-starter-webmvc-test` (test) — provides `@AutoConfigureMockMvc`
- `org.springframework.security:spring-security-test` (test)
- `org.testcontainers:testcontainers-postgresql` (test, `catalogue-service`)
- `org.wiremock:wiremock-standalone` (test, `manager-app`)

### Project Structure

- `./` - Maven parent project
- `catalogue-service/` - Backend REST API module (blocking MVC)
  - `src/main/java/com/drm/sandbox/catalogue/` - Java source
  - `src/main/resources/application-standalone.yaml` - Service configuration
  - `src/main/resources/db/migration/` - Flyway SQL migration scripts
  - `src/test/java/com/drm/sandbox/catalogue/config/TestingBeans.java` - mock `JwtDecoder` for integration tests
  - `src/test/java/com/drm/sandbox/catalogue/controller/ProductsRestControllerIT.java` - integration test
  - `src/test/resources/application.yml` - Testcontainers PostgreSQL datasource
  - `src/test/resources/sql/products.sql` - seed data for tests
- `manager-app/` - Admin web application module (blocking MVC)
  - `src/main/java/com/drm/sandbox/manager/` - Java source
  - `src/main/java/com/drm/sandbox/manager/entity/` - JPA entities (`User`, `Authority`, `Product`)
  - `src/main/java/com/drm/sandbox/manager/repository/` - Spring Data repositories (`UserRepository`)
  - `src/main/java/com/drm/sandbox/manager/security/` - `OAuthClientHttpRequestInterceptor` (Bearer token for service-to-service calls); `MUserDetailService` (legacy, not wired into the filter chain)
  - `src/main/java/com/drm/sandbox/manager/config/` - `SecurityConfig` (OAuth2 login + client), `ClientBeans` (RestClient + OAuth interceptor)
  - `src/main/resources/application-standalone.yaml` - Application configuration
  - `src/main/resources/db/migration/` - Flyway SQL migration scripts
  - `src/main/resources/templates/` - Thymeleaf HTML templates
  - `src/test/java/com/drm/sandbox/manager/config/TestingBeans.java` - mock OAuth2 client beans for integration tests
  - `src/test/java/com/drm/sandbox/manager/controller/ProductsControllerTest.java` - unit test (Mockito)
  - `src/test/java/com/drm/sandbox/manager/controller/ProductsControllerIT.java` - integration test
- `customer-app/` - Customer storefront module (reactive WebFlux)
  - `src/main/java/com/drm/sandbox/customer/` - Java source
  - `src/main/java/com/drm/sandbox/customer/client/` - reactive `WebClient` clients (`WebClientProductsClient`, `WebClientFavouriteProductsClient`, `WebClientProductReviewsClient`) behind interfaces (`ProductsClient`, `FavouriteProductsClient`, `ProductReviewsClient`)
  - `src/main/java/com/drm/sandbox/customer/config/ClientConfig.java` - builds the `WebClient` beans (base URLs `selmag.services.catalogue.uri` / `selmag.services.feedback.uri`, the latter defaulting to `http://localhost:8084` via `@Value`) from a prototype `WebClient.Builder` carrying `ServerOAuth2AuthorizedClientExchangeFilterFunction` (default registration `keycloak`)
  - `src/main/java/com/drm/sandbox/customer/config/SecurityConfig.java` - reactive OAuth2 client: `anyExchange().authenticated()`, `oauth2Login`, `oauth2Client`, CSRF left enabled
  - `src/main/java/com/drm/sandbox/customer/controller/` - `ProductsController`, `ProductController` (return `Mono<String>` view names); `ProductController` also has a `@ModelAttribute` that republishes the `CsrfToken` under `CsrfRequestDataValueProcessor.DEFAULT_CSRF_ATTR_NAME`, which is what makes the reactive Thymeleaf `th:action` POST forms render the hidden `_csrf` field
  - `src/main/java/com/drm/sandbox/customer/entity/` - records `Product`, `FavouriteProduct`, `ProductReview`
  - `src/main/resources/application-standalone.yml` - port `8083`, catalogue URI, OAuth2 client registration `keycloak` (client id `customer-app`)
  - `src/main/resources/templates/customer/products/` - Thymeleaf templates (`list.html`, `favourites.html`, `product.html`)
  - `src/test/java/com/drm/sandbox/customer/controller/` - controller unit tests (`ProductsControllerTest`, `ProductControllerTest`)
- `feedback-service/` - Feedback REST API module (reactive WebFlux)
  - `src/main/java/com/drm/sandbox/feedback/` - Java source
  - `src/main/java/com/drm/sandbox/feedback/controller/` - `FavouriteProductsRestController` (token needed on every handler) and `ProductReviewsRestController` (token needed on `POST` only — reviews are listed per product, not per user), both taking `Mono<JwtAuthenticationToken>` to read the user's `sub`; `ExceptionHandlingControllerAdvice` (validation errors → `ProblemDetail`)
  - `src/main/java/com/drm/sandbox/feedback/service/` - `FavouriteProductsService` / `ProductReviewsService` interfaces + `Default*` implementations; the favourites methods all take the `userId`
  - `src/main/java/com/drm/sandbox/feedback/repository/` - reactive Spring Data MongoDB repositories (`FavouriteProductRepository` — `findAllByUserId`, `findByProductIdAndUserId`, `deleteByProductIdAndUserId`; `ProductReviewRepository`)
  - `src/main/java/com/drm/sandbox/feedback/config/` - `SecurityConfig` (reactive OAuth2 resource server), `MongoConfig` (`UuidRepresentation.STANDARD`)
  - `src/main/java/com/drm/sandbox/feedback/entity/` - `FavouriteProduct`, `ProductReview` (both carry a `userId`, filled from the JWT `sub`)
  - `src/main/resources/application-standalone.yml` - port `8084`, MongoDB URI, Keycloak `issuer-uri`
