# CLAUDE.md

This project implements the system described in `SPEC.md`. Refer to that file for detailed architectural, database, and technical specifications.

Keep replies concise and focused on key information. Avoid unnecessary verbosity.

When working with third-party libraries, always consult official documentation to ensure up-to-date information. For efficient documentation lookup, prioritize the `context7` MCP server (`mcp__context7__*`), executing multiple queries in parallel when beneficial. If context7 does not yield sufficient results, fall back to `WebSearch` or `WebFetch`, focusing on official sources and concise information.

## Commands

- **Build Project:** `./mvnw clean install` (from project root; builds all four modules, runs unit + integration tests via Failsafe, and generates the REST Docs HTML for `catalogue-service`).
- **Executable JAR:** each service's `spring-boot-maven-plugin` (`repackage` goal) bundles the app and its dependencies into a self-contained executable JAR runnable via `java -jar`, excludes Lombok, and adds the `-exec` classifier.
- **Run Tests:** `./mvnw test` (from project root; runs unit tests in all four modules — Surefire only picks up `*Test`). Integration tests (`*IT`) run through Failsafe in the `verify` phase, so `./mvnw verify` runs unit + integration tests. Single unit test: `./mvnw -pl catalogue-service -Dtest=ProductsRestControllerTest test`; single IT: `./mvnw -pl catalogue-service -Dit.test=ProductRestControllerIT verify`. The `catalogue-service` and `manager-app` integration tests use Testcontainers PostgreSQL and `feedback-service` ITs use Testcontainers MongoDB, so Docker must be running for those; `customer-app` ITs stub the downstream services with WireMock on port `54321` and need no Docker.
- **WireMock (tests):** `customer-app` ITs (`ProductsControllerIT`, `ProductControllerIT`) use `@WireMockTest(httpPort = 54321)` and a test `TestConfig` that mocks the reactive OAuth2 client repositories and overrides the production `WebClient*Client` beans with `@Primary` ones pointed at WireMock, bypassing the token-relay filter. `manager-app`'s IT also stubs `catalogue-service` with WireMock.
- **Testcontainers (tests):** the `catalogue-service` integration tests start an ephemeral PostgreSQL container (`postgres:17.4-alpine`) via the `jdbc:tc:postgresql:...` JDBC URL in `catalogue-service/src/test/resources/application.yml`; `TC_DAEMON=true` keeps the container running and reused across test runs. The same applies to `manager-app/src/test/resources/application.yml`. `feedback-service` ITs instead declare `MongoDBContainer("mongo:8")` as a `@ServiceConnection` bean in `feedback-service/src/test/java/com/drm/sandbox/feedback/config/TestConfig.java` (which also mocks `ReactiveJwtDecoder` — no Keycloak in tests).
- **Run `manager-app`:** `./mvnw -pl manager-app spring-boot:run` (from project root, starts on port `8080`)
- **Run `catalogue-service`:** `./mvnw -pl catalogue-service spring-boot:run` (from project root, starts on port `8081`)
- **Run `customer-app`:** `./mvnw -pl customer-app spring-boot:run` (from project root, starts on port `8083`; needs Keycloak — the storefront is an OAuth2 client and redirects to the login page)
- **Run `feedback-service`:** `./mvnw -pl feedback-service spring-boot:run` (from project root, starts on port `8084`)
- **Access UI:** manager UI `http://localhost:8080/catalogue/products/list` (requires Keycloak sign-in); customer storefront `http://localhost:8083/customer/products/list` (also requires Keycloak sign-in — it is an OAuth2 client now, not a public page); Swagger UIs `http://localhost:8081/swagger-ui/index.html` (`catalogue-service`) and `http://localhost:8084/swagger-ui/index.html` (`feedback-service`) — public pages whose Authorize button runs an authorization-code + PKCE flow against Keycloak as the matching `…-swagger-ui` client.
- **Database:** Two PostgreSQL databases are required — `catalogue` (port `5432`, user `catalogue`/`catalogue`) for `catalogue-service`, and `manager` (port `5433`, user `manager`/`manager`) for `manager-app`. See `README.MD` for `docker run` commands. `feedback-service` uses MongoDB instead (`mongodb://localhost:27017/feedback`).
- **Keycloak:** Required for OAuth2 — run `selmag-keycloak` on port `8082` (realm `selmag`). See `README.MD` for the `docker run` command; realm config is at `config/keycloak/import/realm-export.json`. Needed by **all four** services: `manager-app` and `customer-app` sign users in (`oauth2Login`) and relay that user's token downstream, and both resource servers resolve their JWT decoder from the realm issuer — with Keycloak down no service can authenticate anything (and none of them falls back to running unsecured). The exported realm already defines four clients — `manager-app` (redirect URI `http://localhost:8080/*`), `customer-app` (root URL `http://localhost:8083` with the relative redirect URI `/*`), `catalogue-service-swagger-ui` (public PKCE client, redirect URI `http://localhost:8081/swagger-ui/*`, Web origins `+` — required because Swagger UI exchanges the code for a token from the browser, cross-origin) and `feedback-service-swagger-ui` (same shape, redirect URI `http://localhost:8084/swagger-ui/*`, Web origins `http://localhost:8084`) — but **no human users**, only the two service accounts, so a realm user has to be created (and put into the `managers` / `customers` group) before either UI can be signed into.

## REST Docs

- `catalogue-service` uses Spring REST Docs with MockMvc. `ProductRestControllerIT` is annotated with `@AutoConfigureRestDocs` (from `spring-boot-restdocs`) and `@ExtendWith(RestDocumentationExtension.class)`; its `find_all` test calls `document("catalogue/products/find_all", …)` with `responseFields(...)`, after `prettyPrint()` and removing the `Vary` header.
- Snippets go to the default `catalogue-service/target/generated-snippets`.
- `catalogue-service/src/asciidoc/documentation.adoc` includes `{snippets}/catalogue/products/find_all/response-body.adoc` and `response-fields.adoc`.
- The parent `pom.xml` binds `asciidoctor-maven-plugin` (3.2.0, with `spring-restdocs-asciidoctor` 4.0.0) to the `post-integration-test` phase, so after `./mvnw verify` / `install` the HTML lands in `catalogue-service/target/generated-docs/documentation.html`.

## Architecture

This is a multi-module Spring Boot application comprised of four services plus Keycloak as the identity/authorization provider. Two are blocking Spring MVC apps; two are reactive WebFlux apps:

- **`catalogue-service`**: A backend REST API for product management. Stateless single source of truth for product data; exposes REST at `/catalogue-api/products`. OAuth2 **resource server** — validates JWT access tokens against the Keycloak issuer. Reads (`GET`) are public (`permitAll()`); writes (`POST`/`PATCH`/`DELETE`) require `SCOPE_edit_catalogue`.
- **`manager-app`**: A server-side rendered (Spring MVC) admin UI, acting as an OAuth2 **client** to the `catalogue-service`. End users sign in via Keycloak (`oauth2Login`); outgoing service-to-service calls attach a Bearer access token (`OAuthClientHttpRequestInterceptor`).
- **`feedback-service`**: A reactive (WebFlux) REST API for product feedback — favourite products and product reviews — backed by **MongoDB** (`spring-boot-starter-data-mongodb-reactive`). Exposes `/api/v1/feedback-api/**`. OAuth2 **resource server** — validates JWT access tokens against the Keycloak issuer and requires authentication on **every** exchange except the Swagger UI/docs paths (`/swagger-ui/**`, `/webjars/**`, `/swagger-ui.html`, `/v3/api-docs/**` are `permitAll()`) with no `SCOPE_*` check, stateless, CSRF disabled. Favourites are **per user**: the controllers take a `Mono<JwtAuthenticationToken>` and pass `token.getToken().getSubject()` (the Keycloak `sub`) down into the service and repository, so a `FavouriteProduct` carries a `userId` and every favourite read/delete is scoped by it.
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
- `org.springframework.boot:spring-boot-starter-webflux-test` (test, `customer-app`, `feedback-service`) — `WebTestClient` + `@AutoConfigureWebTestClient` (Boot 4 package `org.springframework.boot.webtestclient.autoconfigure`)
- `org.springframework.boot:spring-boot-starter-security-test` (test, `customer-app`, `feedback-service`) — reactive `mockUser()` / `csrf()` / `mockJwt()` mutators
- `io.projectreactor:reactor-test` (test, `customer-app`, `feedback-service`) — `StepVerifier`
- `org.springframework.restdocs:spring-restdocs-mockmvc` (test, `catalogue-service`)
- `org.springframework.restdocs:spring-restdocs-webtestclient` (test, `feedback-service`) — added for WebTestClient-based REST Docs, not yet used by the current tests
- `org.springdoc:springdoc-openapi-starter-webmvc-ui` / `springdoc-openapi-starter-webflux-ui` (3.0.3, `catalogue-service` / `feedback-service`) — OpenAPI spec at `/v3/api-docs`, Swagger UI at `/swagger-ui/index.html`; OAuth2 scheme declared with `@SecurityScheme` on each application class
- `org.springframework.boot:spring-boot-restdocs` (test, `catalogue-service`) — provides `@AutoConfigureRestDocs`
- `org.asciidoctor:asciidoctor-maven-plugin` (parent `build`, with `spring-restdocs-asciidoctor` 4.0.0) — renders the REST Docs HTML
- `org.testcontainers:testcontainers-postgresql` (test, `catalogue-service`, `manager-app`)
- `org.testcontainers:testcontainers-mongodb` (test, `feedback-service`) — `MongoDBContainer`
- `org.springframework.boot:spring-boot-testcontainers` (test, `feedback-service`) — `@ServiceConnection` glue
- `org.wiremock:wiremock-standalone` (test, `customer-app`, `manager-app`)

### Project Structure

- `./` - Maven parent project
- `catalogue-service/` - Backend REST API module (blocking MVC)
  - `src/main/java/com/drm/sandbox/catalogue/` - Java source
  - `src/main/resources/application-standalone.yaml` - Service configuration
  - `src/main/resources/db/migration/` - Flyway SQL migration scripts
  - `src/asciidoc/documentation.adoc` - REST Docs source; includes snippets generated by the IT
  - `src/test/java/com/drm/sandbox/catalogue/config/TestingBeans.java` - mock `JwtDecoder` for integration tests
  - `src/test/java/com/drm/sandbox/catalogue/controller/ProductRestControllerTest.java` / `ProductsRestControllerTest.java` - controller unit tests (Mockito)
  - `src/test/java/com/drm/sandbox/catalogue/service/DefaultProductServiceTest.java` - service unit test
  - `src/test/java/com/drm/sandbox/catalogue/repository/ProductRepositoryIT.java` - `@DataJpaTest` repository IT
  - `src/test/java/com/drm/sandbox/catalogue/controller/ProductRestControllerIT.java` - integration test for item-level `GET`/`PATCH`/`DELETE`; also the REST Docs-enabled test (`catalogue/products/find_all`)
  - `src/test/java/com/drm/sandbox/catalogue/controller/ProductsRestControllerIT.java` - integration test for collection `GET` and `POST`
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
  - `src/test/java/com/drm/sandbox/manager/security/OAuthClientHttpRequestInterceptorTest.java` - unit test for the Bearer-token interceptor
  - `src/test/java/com/drm/sandbox/manager/controller/ProductsControllerTest.java` / `ProductsControllerIT.java` - list/create unit test (Mockito) and integration test
  - `src/test/java/com/drm/sandbox/manager/controller/ProductControllerTest.java` / `ProductControllerIT.java` - item-level view/edit/delete unit and integration tests
- `customer-app/` - Customer storefront module (reactive WebFlux)
  - `src/main/java/com/drm/sandbox/customer/` - Java source
  - `src/main/java/com/drm/sandbox/customer/client/` - reactive `WebClient` clients (`WebClientProductsClient`, `WebClientFavouriteProductsClient`, `WebClientProductReviewsClient`) behind interfaces (`ProductsClient`, `FavouriteProductsClient`, `ProductReviewsClient`)
  - `src/main/java/com/drm/sandbox/customer/config/ClientConfig.java` - builds the `WebClient` beans (base URLs `selmag.services.catalogue.uri` / `selmag.services.feedback.uri`, the latter defaulting to `http://localhost:8084` via `@Value`) from a prototype `WebClient.Builder` carrying `ServerOAuth2AuthorizedClientExchangeFilterFunction` (default registration `keycloak`)
  - `src/main/java/com/drm/sandbox/customer/config/SecurityConfig.java` - reactive OAuth2 client: `anyExchange().authenticated()`, `oauth2Login`, `oauth2Client`, CSRF left enabled
  - `src/main/java/com/drm/sandbox/customer/controller/` - `ProductsController`, `ProductController` (return `Mono<String>` view names); `ProductController` also has a `@ModelAttribute` that republishes the `CsrfToken` under `CsrfRequestDataValueProcessor.DEFAULT_CSRF_ATTR_NAME`, which is what makes the reactive Thymeleaf `th:action` POST forms render the hidden `_csrf` field
  - `src/main/java/com/drm/sandbox/customer/entity/` - records `Product`, `FavouriteProduct`, `ProductReview`
  - `src/main/resources/application-standalone.yml` - port `8083`, catalogue URI, OAuth2 client registration `keycloak` (client id `customer-app`)
  - `src/main/resources/templates/customer/products/` - Thymeleaf templates (`list.html`, `favourites.html`, `product.html`)
  - `src/test/java/com/drm/sandbox/customer/config/TestConfig.java` - test beans: mocked reactive OAuth2 repositories and `@Primary` `WebClient*Client` beans pointed at WireMock (`http://localhost:54321`)
  - `src/test/java/com/drm/sandbox/customer/controller/` - controller unit tests (`ProductsControllerTest`, `ProductControllerTest`) and WireMock-backed ITs (`ProductsControllerIT`, `ProductControllerIT`)
- `feedback-service/` - Feedback REST API module (reactive WebFlux)
  - `src/main/java/com/drm/sandbox/feedback/` - Java source
  - `src/main/java/com/drm/sandbox/feedback/controller/` - `FavouriteProductsRestController` (token needed on every handler) and `ProductReviewsRestController` (token needed on `POST` only — reviews are listed per product, not per user), both taking `Mono<JwtAuthenticationToken>` to read the user's `sub`; `ExceptionHandlingControllerAdvice` (validation errors → `ProblemDetail`)
  - `src/main/java/com/drm/sandbox/feedback/service/` - `FavouriteProductsService` / `ProductReviewsService` interfaces + `Default*` implementations; the favourites methods all take the `userId`
  - `src/main/java/com/drm/sandbox/feedback/repository/` - reactive Spring Data MongoDB repositories (`FavouriteProductRepository` — `findAllByUserId`, `findByProductIdAndUserId`, `deleteByProductIdAndUserId`; `ProductReviewRepository`)
  - `src/main/java/com/drm/sandbox/feedback/config/` - `SecurityConfig` (reactive OAuth2 resource server), `MongoConfig` (`UuidRepresentation.STANDARD`)
  - `src/main/java/com/drm/sandbox/feedback/entity/` - `FavouriteProduct`, `ProductReview` (both carry a `userId`, filled from the JWT `sub`)
  - `src/main/resources/application-standalone.yml` - port `8084`, MongoDB URI, Keycloak `issuer-uri`, springdoc OAuth settings (client `feedback-service-swagger-ui`, PKCE; `oauth2-redirect-url` left at the springdoc default)
  - `src/test/java/com/drm/sandbox/feedback/config/TestConfig.java` - test beans: Testcontainers `MongoDBContainer("mongo:8")` with `@ServiceConnection` and a mocked `ReactiveJwtDecoder` (no Keycloak in tests)
  - `src/test/java/com/drm/sandbox/feedback/controller/FavouriteProductsRestControllerTest.java` / `ProductReviewsRestControllerTest.java` - controller unit tests (Mockito + `StepVerifier`)
  - `src/test/java/com/drm/sandbox/feedback/controller/FavouriteProductsRestControllerIT.java` / `ProductReviewsRestControllerTestIT.java` - reactive ITs (`@SpringBootTest` + `@AutoConfigureWebTestClient`, `mockJwt()`, MongoDB container); URIs include the full `/api/v1/feedback-api/...` prefix
