# Technical Specification – Product Catalogue Manager

## 1. Overview

A web application for managing a product catalogue and collecting customer feedback. The system is built with a distributed, five-module architecture: a backend REST API for product data, a reactive feedback REST API, an admin web application for catalogue management, a public customer storefront, and a Spring Boot Admin monitoring server that aggregates actuator data from the services.

### Core Features

- Product Management (Create, Read, Update, Delete) via a REST API and an admin UI.
- A clear REST API for managing products.
- Product feedback: favourite products and product reviews (reactive REST API).
- A server-side rendered admin interface for managing the catalogue.
- A server-side rendered customer storefront for browsing products, adding favourites, and leaving reviews. Visitors sign in through Keycloak: the storefront is an OAuth2 client and relays the user's token to the backend services.
- Monitoring: a Spring Boot Admin server (`admin-server`) with a Keycloak-protected UI that aggregates health, metrics and environment data from the registered service instances.

### Tech Stack

- **Backend (`catalogue-service`):**
  - Java 21 & Spring Boot
  - Spring MVC (blocking REST)
  - Spring Data JPA
  - PostgreSQL
  - Flyway for database migrations
  - Spring Security (OAuth2 resource server, JWT)
  - Maven
- **Feedback API (`feedback-service`):**
  - Java 21 & Spring Boot
  - Spring WebFlux (reactive REST)
  - Reactor (`Mono`/`Flux`)
  - Spring Data MongoDB (reactive)
  - MongoDB
  - Spring Security (OAuth2 resource server, JWT; plus an OAuth2 `client_credentials` client for the Spring Boot Admin registration)
  - Spring Boot Admin Client 4.0.4
  - Bean Validation (`jakarta.validation`)
  - Maven
- **Admin Frontend (`manager-app`):**
  - Java 21 & Spring Boot
  - Spring MVC
  - Thymeleaf for server-side template rendering
  - Spring Data JPA (user management data)
  - PostgreSQL (own `manager` database)
  - Flyway for database migrations
  - Spring Security (OAuth2 login + client)
  - Maven
- **Customer Frontend (`customer-app`):**
  - Java 21 & Spring Boot
  - Spring WebFlux
  - Thymeleaf (reactive) for server-side template rendering
  - Reactive `WebClient` for service-to-service calls
  - Spring Security (OAuth2 client: `oauth2Login` + token relay on outgoing calls)
  - Maven
- **Monitoring (`admin-server`):**
  - Java 21 & Spring Boot
  - Spring Boot Admin Server 4.0.4 (Spring MVC)
  - Spring Security (OAuth2 resource server for instance registration, `oauth2Login` for the UI, `client_credentials` client for polling instance actuator endpoints)
  - Maven
- **Monitored instances:** `catalogue-service` and `feedback-service` both run Spring Boot Admin Client 4.0.4 and register their actuator endpoints with the admin server.

## 2. Architecture

### 2.1 High-Level Architecture

The application follows a distributed, five-module model — four business services plus a Spring Boot Admin monitoring server — and a third-party identity provider:

- **`catalogue-service`:** A stateless backend service that exposes a RESTful API. It is the single source of truth for all product data and contains all business logic related to product management. It acts as an OAuth2 **resource server**: it validates JWT access tokens (issued by Keycloak). Reads (`GET`) require `SCOPE_view_catalogue`, writes (`POST`/`PATCH`/`DELETE`) require `SCOPE_edit_catalogue`, actuator endpoints (`/actuator/**`) require `SCOPE_metrics`; the Swagger UI/docs paths stay `permitAll()` and everything else is denied. It also carries a Spring Boot Admin **client** that registers itself with `admin-server`.
- **`feedback-service`:** A reactive (WebFlux) backend service that stores product feedback — favourite products and product reviews — in **MongoDB**. It exposes a reactive REST API under `/api/v1/feedback-api/**` and acts as an OAuth2 **resource server**: it validates JWT access tokens (issued by Keycloak) and requires authentication on **every** exchange (`anyExchange().authenticated()`). Unlike `catalogue-service` it has no public endpoints and, apart from `/actuator/**` (which requires `SCOPE_metrics`), no scope-based rules — a valid realm token is enough. It is also a Spring Boot Admin **client** that registers itself with `admin-server` under the name `feedback-service`. Both document types carry a `userId`, and the favourite endpoints are scoped by it: the controllers take a `Mono<JwtAuthenticationToken>` and pass the token's `sub` (the Keycloak user id) into the service and repository, so a user only sees and deletes their own favourites. (Reviews are still listed per product for every signed-in user; only their creation records the author's `userId`.)
- **`manager-app`:** A server-side rendered (Spring MVC) web application that serves an admin interface for managing products. It is an OAuth2 **client** of the `catalogue-service`: end users authenticate against Keycloak (`oauth2Login`), and each service-to-service call to `catalogue-service` carries a Bearer access token obtained via the OAuth2 client.
- **`customer-app`:** A reactive (WebFlux), server-side rendered storefront. It is an OAuth2 **client** as well: `config/SecurityConfig` (`ServerHttpSecurity` / `SecurityWebFilterChain`) puts the whole UI behind `oauth2Login` (`anyExchange().authenticated()`), so every page — including the product list — requires a signed-in user, and CSRF protection stays **enabled** (the storefront's POST forms therefore submit a CSRF token). Outgoing calls to `catalogue-service` (products) and `feedback-service` (favourites/reviews) are made through reactive `WebClient` clients built from a `WebClient.Builder` that carries `ServerOAuth2AuthorizedClientExchangeFilterFunction` with the default client registration `keycloak`, i.e. a **token relay**: the signed-in user's access token is attached to each request. That is what lets the storefront call the secured `feedback-service` and lets that service attribute favourites to the right user.
- **`admin-server`:** A Spring Boot Admin server (Spring MVC + `spring-boot-admin-starter-server` 4.0.4) that serves the monitoring UI on port `8085` and polls the registered instances' actuator endpoints. It plays three roles at once: an OAuth2 **resource server** for instances that register themselves (requests carrying `Authorization: Bearer …` need `SCOPE_metrics_server`), an OAuth2 **login client** for its own UI (`oauth2Login`, registration `login`), and an OAuth2 **`client_credentials` client** that fetches a token to poll each instance's `/actuator/**` (`OAuthHttpHeadersProvider`, registration `keycloak`, scope `metrics`).
- **`Keycloak`:** The identity/authorization provider (realm `selmag`). It authenticates users, issues access tokens, and defines the realm roles (`ROLE_MANAGER`, `ROLE_CUSTOMER`), groups (`managers`, `customers`) and client scopes (`view_catalogue`, `edit_catalogue`, `metrics`, `metrics_server`) used across the system. Together the services use seven clients: `manager-app`, `customer-app`, `catalogue-service-swagger-ui`, `feedback-service-swagger-ui`, `admin-service`, `catalogue-service-metrics-client` and `feedback-service-metrics-client`.

### 2.2 Application Layers

**Blocking Presentation Layer (`manager-app`)**
- Spring MVC controllers handle incoming HTTP requests.
- Thymeleaf templates render the dynamic HTML pages.
- Delivers a complete HTML admin interface to the browser.

**Reactive Presentation Layer (`customer-app`)**
- WebFlux controllers (`@Controller` returning `Mono<String>`) handle incoming HTTP requests.
- Reactive Thymeleaf templates render the dynamic HTML pages.
- Delivers a complete HTML storefront to the browser.

**Client & API Layer**
- `manager-app` → `catalogue-service`: the `ProductsRestClient` (`RestClientProductsRestClient`) uses a blocking `RestClient` built in `ClientBeans` with an `OAuthClientHttpRequestInterceptor` that, on every outgoing request, obtains an OAuth2 access token via the `OAuth2AuthorizedClientManager` (client registration `keycloak`) for the **current principal** and attaches it as a Bearer token — a token relay, not a `client_credentials` service account.
- `customer-app` → `catalogue-service` / `feedback-service`: reactive `WebClient` clients (`WebClientProductsClient`, `WebClientFavouriteProductsClient`, `WebClientProductReviewsClient`) built in `ClientConfig` from a **prototype** `WebClient.Builder` bean (base URLs `selmag.services.catalogue.uri` and `selmag.services.feedback.uri`, the latter defaulting to `http://localhost:8084`). That builder installs a `ServerOAuth2AuthorizedClientExchangeFilterFunction` with `setDefaultClientRegistrationId("keycloak")`, so every outgoing request carries the signed-in user's access token — the reactive counterpart of the servlet interceptor above, and the reason `feedback-service` now sees a real user. The clients consume `Mono`/`Flux` and translate `WebClientResponseException` errors into `ClientBadRequestException` / empty results.
- `catalogue-service` provides a formal REST API contract at `/catalogue-api/products`; `feedback-service` at `/api/v1/feedback-api/**`.

**Security Layer**
- `catalogue-service` secures its API as an OAuth2 resource server (`security/SecurityConfig`): `GET` requests require `SCOPE_view_catalogue`, `POST`/`PATCH`/`DELETE` under `/catalogue-api/products` require `SCOPE_edit_catalogue`, and `/actuator/**` requires `SCOPE_metrics`; the Swagger UI/docs paths are `permitAll()`, everything else is denied (`denyAll()`), with CSRF disabled and `SessionCreationPolicy.STATELESS`. Its Spring Boot Admin **client** (`security/ClientConfig`) replaces the auto-configured `RegistrationClient` with a `RestClientRegistrationClient` whose interceptor obtains a `client_credentials` token (client `catalogue-service-metrics-client`, scope `metrics_server`) and sends it as `Authorization: Bearer …` when registering with `admin-server`.
- `manager-app` enables `oauth2Login` and `oauth2Client` (`SecurityConfig`). A custom `OAuth2UserService` flattens the user's authorities from the ID token together with `groups`-claim entries prefixed with `ROLE_`; all UI requests require the `ROLE_MANAGER` role.
- `feedback-service` secures its API as an OAuth2 resource server too (`config/SecurityConfig`), but in its **reactive** form: `ServerHttpSecurity` + `SecurityWebFilterChain` instead of `HttpSecurity` + `SecurityFilterChain`, `authorizeExchange(...)` instead of `authorizeHttpRequests(...)`, and `NoOpServerSecurityContextRepository.getInstance()` instead of `SessionCreationPolicy.STATELESS`. The rules are `permitAll()` for the Swagger UI/docs paths, `SCOPE_metrics` for `/actuator/**` and `anyExchange().authenticated()` for everything else — no business endpoint requires a scope — with CSRF disabled. Its Spring Boot Admin **client** (`config/ClientConfig`) replaces the auto-configured `RegistrationClient` with a `RestClientRegistrationClient` whose interceptor obtains a `client_credentials` token (client `feedback-service-metrics-client`, scope `metrics_server`) and sends it as `Authorization: Bearer …` when registering with `admin-server`. Because Spring Boot Admin 4.0 dropped the reactive `ReactiveRegistrationClient` (registration is always blocking `RestClient`), the interceptor drives the reactive `AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager` and `block()`s on it — safe here, since registration runs on the SBA scheduler thread, never on a Reactive Netty event loop.
- `customer-app` is now secured as an OAuth2 **client** (`config/SecurityConfig`, reactive): `oauth2Login(Customizer.withDefaults())` + `oauth2Client(Customizer.withDefaults())` behind `anyExchange().authenticated()`. There is no `SCOPE_*`/role rule (any authenticated realm user may browse) and no custom `OAuth2UserService` — unlike `manager-app` it does not map the `groups` claim to authorities. Unlike `feedback-service`, CSRF is **not** disabled, so the storefront's POST forms must carry a token; `ProductController` exposes the `Mono<CsrfToken>` as a model attribute and republishes it under `CsrfRequestDataValueProcessor.DEFAULT_CSRF_ATTR_NAME`, which makes reactive Thymeleaf's `CsrfRequestDataValueProcessor` inject the hidden `_csrf` field into every `th:action` form automatically.
- `admin-server` uses **two ordered `SecurityFilterChain`s**. The first (`@Priority(0)`) matches any request carrying an `Authorization: Bearer …` header and treats it as a resource-server call, requiring `SCOPE_metrics_server` — this is the instance-registration path. The second (`@Priority(1)`) matches everything else as the UI chain: `oauth2Client` + `oauth2Login`, with `anyRequest().authenticated()`. Outgoing actuator calls are authenticated by `OAuthHttpHeadersProvider`, which asks an `AuthorizedClientServiceOAuth2AuthorizedClientManager` for the `keycloak` registration (`client_credentials`, scope `metrics`) and attaches the resulting Bearer token.

**Service Layer**
- `catalogue-service` (`DefaultProductService`): core product business logic — validation, persistence, and the optional title `filter`.
- `feedback-service` (`DefaultFavouriteProductsService`, `DefaultProductReviewsService`): favourite/review business logic over the reactive MongoDB repositories. The favourites methods take the caller's `userId` (`sub` from the JWT) and pass it to the repository, so all favourite queries and deletes are user-scoped.

**Data Access Layer**
- `catalogue-service`: Spring Data JPA repository (`ProductRepository`) over the `catalogue` schema.
- `manager-app`: Spring Data JPA repository (`UserRepository`) over the `user_management` schema.
- `feedback-service`: reactive Spring Data MongoDB repositories (`FavouriteProductRepository`, `ProductReviewRepository`, both `ReactiveCrudRepository`) over the `feedback` database on `mongodb://localhost:27017`; the `UUID` `_id` encoding is configured in `MongoConfig`. `FavouriteProductRepository` derives its queries from the method names — `findAllByUserId`, `findByProductIdAndUserId`, `deleteByProductIdAndUserId`.
- Flyway manages the evolution of the PostgreSQL schemas (`catalogue`, `manager`) through SQL migration scripts.

### 2.3 OAuth2 / Keycloak Scheme

The system delegates authentication and authorization to **Keycloak** (realm `selmag`, issuer `http://localhost:8082/realms/selmag`).

- **End-user authentication (`manager-app` → browser):** `oauth2Login` redirects unauthenticated users to Keycloak. After the authorization-code flow completes, the user's authorities are built from the ID token plus the `groups` claim (only entries prefixed with `ROLE_` are kept, mapped to `SimpleGrantedAuthority`). The whole admin UI is gated by `ROLE_MANAGER`.
- **Service-to-service (`manager-app` → `catalogue-service`):** the client registration `keycloak` (client id `manager-app`) requests scopes `openid`, `view_catalogue`, `edit_catalogue`, `microprofile-jwt`. `OAuthClientHttpRequestInterceptor` uses an `OAuth2AuthorizedClientManager` to obtain an access token for the current principal and sends it as `Authorization: Bearer …`. `catalogue-service` validates the token against the Keycloak issuer and checks the `SCOPE_*` authorities declared in `SecurityConfig` for write endpoints.
- **Reactive client (`customer-app` → browser / `catalogue-service` / `feedback-service`):** the same authorization-code flow against the `keycloak` provider (`issuer-uri`, `user-name-attribute: preferred_username`), with client id `customer-app` and scopes `openid`, `view_catalogue`. `oauth2Login` gates the entire UI; `oauth2Client` plus the `ServerOAuth2AuthorizedClientExchangeFilterFunction` on the `WebClient.Builder` (default client registration `keycloak`) relays **the user's** access token on every outgoing call. No `client_credentials`: the downstream services see the storefront visitor, not a service account — which is precisely what lets `feedback-service` store favourites under that user's `sub`.
- **Reactive resource server (`feedback-service`):** the same Keycloak issuer is configured via `spring.security.oauth2.resourceserver.jwt.issuer-uri`, so `feedback-service` validates the very same access tokens and requires an authenticated principal on **every** exchange. It does not check `SCOPE_*` — any valid token from the realm is accepted. Keycloak must therefore be reachable: the decoder resolves the realm's OIDC metadata from `/.well-known/openid-configuration`, and when Keycloak is down the decoder cannot be initialized (the failure surfaces either at startup or on the first token decode, depending on the version) — the service never falls back to running unsecured.
- **Admin UI login (`admin-server` → browser):** a second registration, `login` (client id `admin-service`, scopes `openid`, `metrics`), drives `oauth2Login` for the Spring Boot Admin UI. It shares the `admin-service` Keycloak client with the `client_credentials` registration but is a separate `ClientRegistration`, because a single registration has exactly one grant type and `client_credentials` cannot authenticate a browser user.
- **Instance registration (`catalogue-service` / `feedback-service` → `admin-server`):** each SBA client posts its instance metadata to `http://localhost:8085/instances`. The token comes from a dedicated service account (`client_credentials`) — `catalogue-service-metrics-client` for `catalogue-service`, `feedback-service-metrics-client` for `feedback-service` — and carries `SCOPE_metrics_server`, which the admin server's API chain requires. The two services need separate clients because the registration token identifies the instance.
- **Actuator polling (`admin-server` → service):** `OAuthHttpHeadersProvider` obtains a token from the `admin-service` service account (registration `keycloak`, scope `metrics`) and attaches it to every `/actuator/**` request, which both `catalogue-service` and `feedback-service` guard with `SCOPE_metrics`.
- **Identity, not just scope:** `catalogue-service` authorizes on `SCOPE_*` alone, so it never looks at *who* is calling. `feedback-service` is the opposite: it checks no scope and instead reads the JWT's `sub` claim, using it as the `userId` that scopes favourites.
- **No anonymous API surface remains:** `catalogue-service` requires `SCOPE_view_catalogue` on `GET` and `SCOPE_edit_catalogue` on writes; its only `permitAll()` paths are the Swagger UI/docs. `feedback-service` has no public endpoint, and `customer-app` demands a login before rendering any page and relays the user's token on every outgoing call. The `admin-server` UI itself requires a logged-in Keycloak user, while instance registration requires `SCOPE_metrics_server`.
- **Realm configuration:** roles `ROLE_MANAGER` / `ROLE_CUSTOMER`; groups `managers` / `customers` (each group maps to its realm role); **seven clients**: **confidential (`client-secret`)** — `manager-app` (valid redirect URI `http://localhost:8080/*`, web origin `http://localhost:8080`), `customer-app` (root URL + admin URL `http://localhost:8083`, valid redirect URI `/*` — relative, so Keycloak resolves it against that root URL), `admin-service` (root URL `http://localhost:8085`, redirect URI `/*`, with both **standard flow** and **service account** enabled — the standard flow backs the admin UI's `login` registration, the service account backs the `client_credentials` registration used to poll actuator endpoints), `catalogue-service-metrics-client` and `feedback-service-metrics-client` (both service account only, standard flow disabled — each backs one SBA client's registration token); **public PKCE** — `catalogue-service-swagger-ui` and `feedback-service-swagger-ui`. Client scopes `view_catalogue`, `edit_catalogue`, `metrics` and `metrics_server` are all mounted as **optional** scopes on the clients that need them, so a client only gets them by asking — which is exactly why each registration lists its scopes explicitly. `microprofile-jwt` carries the `upn` mapper and the `groups` mapper; the latter is an `oidc-usermodel-realm-role-mapper`, so the `groups` claim actually carries the user's **realm roles** (`ROLE_MANAGER`, …) — the very thing `manager-app`'s `OAuth2UserService` filters on (`customer-app` requests neither `edit_catalogue` nor `microprofile-jwt`, and does not read `groups` at all). The realm is exported at `config/keycloak/import/realm-export.json` and imported by the Keycloak container; note the export contains **no human users** — only the service accounts created for the clients — so a developer has to add a user and place them in `managers` / `customers` before signing in.

## 3. Functional Requirements

### 3.1 Product Management (Admin Web UI — `manager-app`)

A user accessing the `manager-app` can:
- **View a list of all products:** The main page displays a table with all products.
- **Create a new product:** A dedicated form allows the user to enter a title and details for a new product.
- **View a single product's details:** Clicking on a product leads to a page showing its details.
- **Edit a product:** From the details page, a user can navigate to an edit form to update the title and details.
- **Delete a product:** A button on the product details page allows for its removal from the system.

### 3.2 Customer Storefront (`customer-app`)

The storefront requires the visitor to sign in through Keycloak first (`oauth2Login`; every exchange is authenticated), so "visitor" below means an authenticated user whose access token the storefront relays to the backend services. A signed-in visitor can:
- **Browse products** with an optional title filter (case-insensitive "contains" search).
- **View a single product's details**, including its reviews.
- **Add / remove a product to/from favourites** — the request reaches `feedback-service` with the visitor's token, so the favourite is recorded for *that* user.
- **Leave a product review** with a 1–5 rating and free-text (validated server-side).

### 3.3 Product API (`catalogue-service`)

The API provides endpoints for full CRUD functionality on products, plus title-based filtering. It is stateless and secured as an OAuth2 resource server: `GET` endpoints require a valid JWT carrying `SCOPE_view_catalogue`, while `POST`/`PATCH`/`DELETE` require `SCOPE_edit_catalogue`. The actuator endpoints exposed to Spring Boot Admin (`/actuator/**`) require `SCOPE_metrics`; the Swagger UI/docs paths are `permitAll()`.

### 3.4 Feedback API (`feedback-service`)

The reactive API stores and returns favourites and reviews in MongoDB. It is secured as an OAuth2 resource server: **every** endpoint requires a valid JWT (no public access, no scope check), while `/actuator/**` additionally requires `SCOPE_metrics` so that only the admin server can read the metrics. Favourites are **user-scoped** — the caller's `sub` claim is stored as `userId` on the document and filters every favourite read/delete; reviews record the author's `userId` on creation but are listed per product for all authenticated users.

### 3.5 Monitoring (`admin-server`)

The Spring Boot Admin server aggregates health, metrics and environment data from the instances that register with it. A signed-in user (any authenticated Keycloak user — the UI chain has no role restriction) can browse the instance list and drill down into each instance's actuator data. `catalogue-service` and `feedback-service` register themselves at startup under the names `catalogue-service` and `feedback-service` (URLs are published by IP so the admin server can reach the instances at `http://<ip>:8081/...` and `http://<ip>:8084/...`). The monitoring link is secured end-to-end: registration requires `SCOPE_metrics_server`, and polling `/actuator/**` requires `SCOPE_metrics`.

## 4. Non-Functional Requirements

**Reliability**
- The system should gracefully handle errors, such as database connection issues or failures in the communication between services.

**Maintainability**
- The strict separation of concerns between the backend APIs and the frontend UIs allows for independent development, testing, and deployment.

**Security**
- Service-to-service communication between `manager-app` and `catalogue-service` is protected with OAuth2. `catalogue-service` acts as a resource server: `GET` endpoints require `SCOPE_view_catalogue`, write endpoints (`POST`/`PATCH`/`DELETE` on `/catalogue-api/products`) require `SCOPE_edit_catalogue`, and `/actuator/**` requires `SCOPE_metrics`; `manager-app` attaches the signed-in user's Bearer token via `OAuthClientHttpRequestInterceptor` (token relay).
- End-user authentication for the admin web UI is handled by Keycloak through `oauth2Login`; the whole admin UI requires the `ROLE_MANAGER` role. A custom `OAuth2UserService` merges ID-token authorities with `ROLE_`-prefixed entries from the `groups` claim.
- `feedback-service` is an OAuth2 resource server as well (`config/SecurityConfig`), but stricter than the catalogue: `anyExchange().authenticated()` means every endpoint needs a valid JWT and no `SCOPE_*` authority is required. It goes one step further than the catalogue by authorizing per **identity**: the token's `sub` becomes the `userId` that scopes favourites, so one user cannot read or delete another's. Reviews are the exception — they are listed per product for any authenticated user.
- `customer-app` is an OAuth2 **client** (`config/SecurityConfig`), not a resource server: `oauth2Login` + `oauth2Client` behind `anyExchange().authenticated()`, with CSRF enabled for its POST forms. It relays the visitor's access token to `catalogue-service` and `feedback-service` through `ServerOAuth2AuthorizedClientExchangeFilterFunction`, which is what makes the (otherwise `401`-guarded) feedback endpoints callable and lets `feedback-service` attribute favourites to the right user. It applies no role or scope rule of its own, and it does not map the `groups` claim to authorities.
- `admin-server` is both a resource server and a login client: requests with `Authorization: Bearer …` must carry `SCOPE_metrics_server` (instance registration), while the UI requires an authenticated Keycloak user via `oauth2Login`. It calls instance `/actuator/**` endpoints with a `client_credentials` token carrying `SCOPE_metrics` (`OAuthHttpHeadersProvider`).
- The only unauthenticated surfaces are the Swagger UI/docs pages of `catalogue-service` and `feedback-service` and the Keycloak endpoints themselves. Every API call carries a token: user tokens (token relay) for the business flows and `client_credentials` tokens for the admin flows (`metrics`, `metrics_server`).
- The `user_management` schema, `UserRepository`, `User`/`Authority` entities and `MUserDetailService` are retained from the earlier HTTP-Basic / DB-backed auth approach and are no longer wired into the active security filter chain.

## 5. Data Model & Database Schema

### 5.1 Tables

#### `catalogue.t_product`

This table stores all product information.

```sql
create table catalogue.t_product
(
    id        serial primary key,
    c_title   varchar(50) not null check (length(trim(c_title)) >= 3),
    c_details varchar(1000)
);
```

| Field     | Type          | Description                                             |
|-----------|---------------|---------------------------------------------------------|
| `id`      | `serial`      | Unique identifier and primary key for the product.      |
| `c_title` | `varchar(50)` | The title of the product. Must be at least 3 chars.     |
| `c_details`| `varchar(1000)`| A detailed description of the product (optional).       |

### 5.2 `user_management` Schema (`manager-app`) — legacy

These tables store user accounts and their authorities for authentication/authorization. They live in the `manager` database and are mapped by the `manager-app` JPA entities (`User`, `Authority`). **Note:** with the migration to Keycloak this schema is no longer used by the active security configuration; it is retained from the previous HTTP-Basic / DB-backed authentication approach.

```sql
create schema if not exists user_management;

create table user_management.t_user (
    id         serial primary key,
    c_username varchar not null check (length(trim(c_username)) > 0) unique,
    c_password varchar
);

create table user_management.t_authority (
    id serial primary key,
    c_authority varchar not null check (length(trim(c_authority)) > 0) unique
);

create table user_management.t_user_2_authority (
    id serial primary key,
    id_user int not null references user_management.t_user(id),
    id_authority int not null references user_management.t_authority(id),
    constraint uk_user_authority unique (id_user, id_authority)
);
```

| Table                  | Purpose                                                     |
|------------------------|-------------------------------------------------------------|
| `t_user`               | User accounts (`c_username`, `c_password`).                 |
| `t_authority`          | Authority/role values (`c_authority`).                      |
| `t_user_2_authority`   | Many-to-many join between users and authorities.            |

### 5.3 `feedback-service` — MongoDB storage

The `feedback-service` does not use PostgreSQL at all. It stores `FavouriteProduct` and `ProductReview` documents in MongoDB (`mongodb://localhost:27017/feedback`) through two reactive repositories, `FavouriteProductRepository` and `ProductReviewRepository` (both extend `ReactiveCrudRepository`, returning `Mono`/`Flux`). There is no schema and no Flyway; Spring Data derives the collections from the entity class names (`productReview`, `favouriteProduct`). Both documents carry a `userId` — the `sub` claim of the JWT that created them — and `FavouriteProductRepository` derives user-scoped queries from its method names (`findAllByUserId`, `findByProductIdAndUserId`, `deleteByProductIdAndUserId`). See `MONGO.md` for a walkthrough of the Mongo model.

### 5.4 MongoDB UUID Representation (`feedback-service`)

`feedback-service` persists `UUID` ids. Spring Data MongoDB 5.x no longer applies a default `uuidRepresentation`, and Spring Boot 4.0 removed the `spring.data.mongodb.uuid-representation` property, so saving a `UUID` fails with `CodecConfigurationException: The uuidRepresentation has not been specified, so the UUID cannot be encoded.`

Set it explicitly via a `MongoClientSettingsBuilderCustomizer` bean:

```java
@Bean
MongoClientSettingsBuilderCustomizer uuidRepresentationCustomizer() {
    return builder -> builder.uuidRepresentation(UuidRepresentation.STANDARD);
}
```

## 6. Backend API Design

### 6.1 `catalogue-service`

All endpoints are relative to the base path `/catalogue-api/products`.

#### Payloads (DTOs)

- **`NewProductPayload`**: `{ "title": "string", "details": "string" }`
- **`UpdateProductPayload`**: `{ "title": "string", "details": "string" }`

#### Endpoints

##### `GET /`

- **Description:** Retrieves a list of products, optionally filtered by title.
- **Query param:** `filter` (optional) — case-insensitive substring match on the title (`LIKE '%filter%'`); when blank/absent, all products are returned.
- **Access:** requires `SCOPE_view_catalogue`.
- **Response 200:** `[ { "id": 1, "title": "Product 1", "details": "..." }, ... ]`

##### `POST /`

- **Description:** Creates a new product.
- **Request Body:** `NewProductPayload`
- **Access:** requires `SCOPE_edit_catalogue`.
- **Response 201:** The newly created product object. `{ "id": 1, "title": "New Product", "details": "..." }`

##### `GET /{productId}`

- **Description:** Retrieves a single product by its ID.
- **Access:** requires `SCOPE_view_catalogue`.
- **Response 200:** A single product object.
- **Response 404:** If no product with the given ID is found.

##### `PATCH /{productId}`

- **Description:** Updates the details of an existing product.
- **Request Body:** `UpdateProductPayload`
- **Access:** requires `SCOPE_edit_catalogue`.
- **Response 204:** No Content, on successful update.
- **Response 404:** If no product with the given ID is found.

##### `DELETE /{productId}`

- **Description:** Deletes a product by its ID.
- **Access:** requires `SCOPE_edit_catalogue`.
- **Response 204:** No Content, on successful deletion.
- **Response 404:** If no product with the given ID is found.

### 6.2 `feedback-service`

All endpoints are relative to the base path `/api/v1/feedback-api`. The service is reactive (returns `Mono`/`Flux`) and secured as an OAuth2 resource server: **every** request below requires an `Authorization: Bearer <JWT>` header carrying a token issued by the Keycloak realm `selmag`, otherwise the service answers `401 Unauthorized`. No specific scope is required. The handlers receive the token as a `Mono<JwtAuthenticationToken>` and read its `sub` claim, which becomes the `userId` stored on the document — so **favourites are per user**: `GET`/`DELETE` only ever see the calling user's own favourites, and adding a favourite is always attributed to the caller (the `userId` is taken from the token, never from the request body).

#### Favourite Products — `/favourite-products`

- **`GET /`** → `Flux<FavouriteProduct>` — lists the **calling user's** favourite products.
- **`GET /by-product-id/{productId}`** → `Mono<FavouriteProduct>` — returns the calling user's favourite for that product (empty → 404 if none).
- **`POST /`** → `Mono<ResponseEntity<FavouriteProduct>>` — adds a product to the calling user's favourites. Body: `NewFavouriteProductPayload { "productId": 1 }`. Returns `201 Created` with a `Location` header.
- **`DELETE /by-product-id/{productId}`** → `Mono<ResponseEntity<Void>>` — removes the product from the calling user's favourites. Returns `204 No Content`.

#### Product Reviews — `/product-reviews`

- **`GET /by-product-id/{productId}`** → `Flux<ProductReview>` — lists reviews for a product (all users' reviews — this read is *not* user-scoped).
- **`POST /`** → `Mono<ResponseEntity<ProductReview>>` — creates a review authored by the calling user. Body: `NewProductReviewPayload { "productId": 1, "rating": 5, "review": "..." }`. Returns `201 Created` with a `Location` header.

#### Entities

- **`FavouriteProduct`**: `{ "id": "uuid", "productId": 1, "userId": "<jwt sub>" }`
- **`ProductReview`**: `{ "id": "uuid", "productId": 1, "rating": 5, "review": "...", "userId": "<jwt sub>" }`

#### Validation & errors

Request bodies are validated with Bean Validation (`@Valid @RequestBody Mono<...>`):
- `NewFavouriteProductPayload.productId` — `@NotNull`.
- `NewProductReviewPayload.productId` — `@NotNull`; `rating` — `@NotNull`, `@Min(1)`, `@Max(5)`; `review` — `@Size(max = 1000)`.

Validation failures throw `WebExchangeBindException`, handled by `ExceptionHandlingControllerAdvice`, which returns `400 Bad Request` as `application/problem+json` with an `errors` array of localized messages (from `messages.properties`).

## 7. Frontend Design

### 7.1 Admin UI (`manager-app`)

The admin frontend is a classic server-side rendered application using Spring MVC and Thymeleaf.

#### URL Routes & Corresponding Templates

- `GET /catalogue/products/list`
  - **Description:** Displays the list of all products.
  - **Template:** `catalogue/products/list.html`

- `GET /catalogue/products/create`
  - **Description:** Shows the form to create a new product.
  - **Template:** `catalogue/products/new_product.html`

- `POST /catalogue/products/create`
  - **Description:** Handles the submission of the new product form. Redirects to the product details page on success.

- `GET /catalogue/products/{productId}`
  - **Description:** Displays the details of a specific product.
  - **Template:** `catalogue/products/product.html`

- `GET /catalogue/products/{productId}/edit`
  - **Description:** Shows the form to edit an existing product.
  - **Template:** `catalogue/products/edit.html`

- `POST /catalogue/products/{productId}/edit`
  - **Description:** Handles the submission of the product update form.

- `POST /catalogue/products/{productId}/delete`
    - **Description:** Deletes the product and redirects to the product list.

#### Client-Side Communication

The `RestClientProductsRestClient` class encapsulates all logic for making HTTP calls to the `catalogue-service` REST API. It handles request creation, response parsing, and error translation. The underlying `RestClient` is built in `ClientBeans` with an `OAuthClientHttpRequestInterceptor` that obtains an access token via the `OAuth2AuthorizedClientManager` and injects it as a Bearer token (client registration id and base URI configured under `selmag.services.catalogue.*`) into every outgoing request.

### 7.2 Customer Storefront (`customer-app`)

The customer storefront is a reactive, server-side rendered application using Spring WebFlux and reactive Thymeleaf.

#### URL Routes & Corresponding Templates

- `GET /customer/products/list`
  - **Description:** Displays all products with an optional title `filter` query param. Links to favourites.
  - **Template:** `customer/products/list.html`

- `GET /customer/products/favourites`
  - **Description:** Displays the favourite products (optionally filtered by title).
  - **Template:** `customer/products/favourites.html`

- `GET /customer/products/{productId}`
  - **Description:** Displays a single product's details, its reviews, and favourite add/remove + review forms.
  - **Template:** `customer/products/product.html`

- `POST /customer/products/{productId}/add-to-favourites` — adds the product to favourites (for the signed-in user), redirects back to the product page.

- `POST /customer/products/{productId}/remove-from-favourites` — removes the product from favourites, redirects back.

- `POST /customer/products/{productId}/create-review` — submits a review; on validation error re-renders the product page with errors.

**Access:** every route above requires an authenticated user — the storefront's filter chain is `anyExchange().authenticated()` with `oauth2Login`, so an anonymous request is redirected to Keycloak before it ever reaches a controller. **CSRF:** the chain leaves CSRF protection enabled, so the three `POST` routes must submit a token. The storefront's forms use `th:action`, and `ProductController` contributes a `@ModelAttribute` that exposes the `Mono<CsrfToken>` and republishes it under `CsrfRequestDataValueProcessor.DEFAULT_CSRF_ATTR_NAME`; reactive Thymeleaf's `CsrfRequestDataValueProcessor` then injects the hidden `_csrf` input into those forms automatically (no template changes are needed). **404:** `ProductController`'s `NoSuchElementException` handler is annotated with `@ResponseStatus(HttpStatus.NOT_FOUND)`, so a missing product renders the `errors/404` template with an actual `404 Not Found` status.

#### Client-Side Communication

The `customer-app` uses reactive `WebClient` clients (built in `ClientConfig`, base URLs `selmag.services.catalogue.uri` = `http://localhost:8081` and `selmag.services.feedback.uri` = `http://localhost:8084`):
- `WebClientProductsClient` → `catalogue-service` (`/catalogue-api/products`).
- `WebClientFavouriteProductsClient` → `feedback-service` (`/api/v1/feedback-api/favourite-products`).
- `WebClientProductReviewsClient` → `feedback-service` (`/api/v1/feedback-api/product-reviews`).

All three are built from the same prototype `WebClient.Builder` bean, which installs a `ServerOAuth2AuthorizedClientExchangeFilterFunction` whose default client registration is `keycloak`. Every outgoing request therefore carries the signed-in user's access token as `Authorization: Bearer …` (token relay) — that is how the storefront is able to call the secured `feedback-service` at all, and how `feedback-service` can attribute a favourite to the right user. The filter resolves the token from the request's `ServerWebExchange` context, so the clients must be invoked within a request bound to a user's `OAuth2AuthorizedClient`.

Each returns `Mono`/`Flux`; controllers compose them reactively (e.g. `collectList()`, `flatMap`, `thenReturn`) and return a `Mono<String>` view name. `WebClientResponseException.BadRequest` is translated into `ClientBadRequestException` carrying the server's validation `errors`.

## 8. Testing

The project ships unit tests and Spring Boot integration tests in all four business modules (`admin-server` has no tests yet). Surefire runs the unit tests (`*Test`), while Failsafe runs the integration tests (`*IT`) in the `verify` phase — so `./mvnw test` executes only unit tests and `./mvnw verify` executes both.

### 8.1 Unit tests

- **`manager-app`:** `ProductsControllerTest` uses plain Mockito (`@ExtendWith(MockitoExtension.class)`, `@Mock` / `@InjectMocks`) with no Spring context. It stubs the `ProductsRestClient` and asserts the returned view name / redirect target and model attributes (success and validation-error paths).
- **`manager-app`:** `ProductControllerTest` covers the item-level routes the same way (view, edit form, update, delete, 404 page), and `OAuthClientHttpRequestInterceptorTest` verifies that the interceptor adds the `Authorization: Bearer …` header.
- **`catalogue-service`:** `ProductsRestControllerTest` / `ProductRestControllerTest` (Mockito) exercise the controllers, and `DefaultProductServiceTest` covers the service layer.
- **`customer-app`:** `ProductsControllerTest` / `ProductControllerTest` (Mockito + `StepVerifier`) assert the reactive controllers' view names, redirects, and model attributes.
- **`feedback-service`:** `FavouriteProductsRestControllerTest` / `ProductReviewsRestControllerTest` (Mockito + `StepVerifier`) exercise the reactive controllers with no Spring context — listing, creation (including the `201 Created` `Location` header, which carries the full `/api/v1/feedback-api/...` path), validation-driven behaviour, and deletion.

### 8.2 Integration tests

The servlet integration tests use `@SpringBootTest` with `@AutoConfigureMockMvc` (Spring Boot 4.0 package `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`); the reactive ones use `@AutoConfigureWebTestClient`. Either way the web layer is exercised end-to-end without a live server.

- **`catalogue-service` — `ProductsRestControllerIT`:** boots against an embedded Testcontainers PostgreSQL (datasource URL `jdbc:tc:postgresql:...` in `src/test/resources/application.yml`), seeds data with `@Sql("/sql/products.sql")`, and rolls back each test via `@Transactional`. The JWT is simulated with `SecurityMockMvcRequestPostProcessors.jwt()` and a mocked `JwtDecoder` (`TestingBeans`). It covers the collection `GET` and `POST` endpoints.
- **`catalogue-service` — `ProductRestControllerIT`:** the same setup for item-level `GET`/`PATCH`/`DELETE`. It is also the REST Docs-enabled test (see 8.5): annotated with `@AutoConfigureRestDocs` and `@ExtendWith(RestDocumentationExtension.class)`, its `find_all` test writes the `catalogue/products/find_all` snippets.
- **`catalogue-service` — `ProductRepositoryIT`:** a `@DataJpaTest` against the same Testcontainers PostgreSQL, covering the repository queries.
- **`manager-app` — `ProductsControllerIT` / `ProductControllerIT`:** use a Testcontainers PostgreSQL (the `jdbc:tc:postgresql:...` URL in `src/test/resources/application.yml`, database `manager`) and simulate a signed-in user with `SecurityMockMvcRequestPostProcessors.user().roles("MANAGER")`. The OAuth2 client beans (`ClientRegistrationRepository`, `OAuth2AuthorizedClientRepository`) are mocked in `TestingBeans`, and `catalogue-service` is stubbed with WireMock.
- **`customer-app` — `ProductsControllerIT` / `ProductControllerIT`:** reactive integration tests using `@SpringBootTest` + `@AutoConfigureWebTestClient` (Spring Boot 4.0 package `org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient`) and `@WireMockTest(httpPort = 54321)`, which stubs both `catalogue-service` and `feedback-service`. `config/TestConfig` mocks `ReactiveClientRegistrationRepository` / `ServerOAuth2AuthorizedClientRepository` and supplies `@Primary` `WebClient*Client` beans pointing at `http://localhost:54321`, so the token-relay filter is bypassed. Requests are authenticated with the reactive `mockUser()` mutator (and `csrf()` for `POST`); the tests cover the product list with/without a filter, the favourites page, adding to favourites, the 404 path, and the redirect to Keycloak for an unauthenticated request.
- **`feedback-service` — `FavouriteProductsRestControllerIT` / `ProductReviewsRestControllerTestIT`:** reactive `@SpringBootTest` + `@AutoConfigureWebTestClient` (the same Boot 4.0 annotation package as `customer-app`), backed by a Testcontainers `MongoDBContainer("mongo:8")`. `config/TestConfig` declares the container as a bean (`initMethod = "start"`, `destroyMethod = "stop"`) annotated with `@ServiceConnection`, so Spring Boot wires `spring.data.mongodb.*` from it, and supplies a mocked `ReactiveJwtDecoder` because no Keycloak runs during tests. Each test seeds documents through `ReactiveMongoTemplate` and removes them afterwards. Requests are authenticated with the reactive `mockJwt().jwt(builder -> builder.subject(...))` mutator — the `sub` is exactly the `userId` the service stores — and unauthenticated calls assert `401 Unauthorized`. All URIs include the full `/api/v1/feedback-api/...` prefix. (`ProductReviewsRestControllerTestIT` keeps its historical name; Failsafe still picks it up because it ends in `IT`.)

### 8.3 Test dependencies

- `org.springframework.boot:spring-boot-starter-test` (test)
- `org.springframework.boot:spring-boot-starter-webmvc-test` (test) — provides `@AutoConfigureMockMvc`
- `org.springframework.security:spring-security-test` (test)
- `org.springframework.boot:spring-boot-starter-webflux-test` (test, `customer-app`, `feedback-service`) — provides `WebTestClient` and `@AutoConfigureWebTestClient`
- `org.springframework.boot:spring-boot-starter-security-test` (test, `customer-app`, `feedback-service`) — reactive `mockUser()` / `csrf()` / `mockJwt()` mutators
- `io.projectreactor:reactor-test` (test, `customer-app`, `feedback-service`) — `StepVerifier`
- `org.testcontainers:testcontainers-postgresql` (test, `catalogue-service`, `manager-app`)
- `org.testcontainers:testcontainers-mongodb` (test, `feedback-service`) — the `MongoDBContainer` behind its ITs
- `org.springframework.boot:spring-boot-testcontainers` (test, `feedback-service`) — `@ServiceConnection` / container lifecycle glue
- `org.wiremock:wiremock-standalone` (test, `manager-app`, `customer-app`)
- `org.springframework.restdocs:spring-restdocs-mockmvc` (test, `catalogue-service`)
- `org.springframework.restdocs:spring-restdocs-webtestclient` (test, `feedback-service`) — declared for WebTestClient-based REST Docs (not yet used by the current tests)
- `org.springframework.boot:spring-boot-restdocs` (test, `catalogue-service`) — provides `@AutoConfigureRestDocs`

### 8.4 Testcontainers (`catalogue-service`, `manager-app`, `feedback-service`)

The `catalogue-service` integration tests run against an ephemeral PostgreSQL container managed by [Testcontainers](https://www.testcontainers.org/) — no local database is required.

- **Configuration:** the test datasource is defined in `catalogue-service/src/test/resources/application.yml` and overrides the production `application-standalone.yaml` datasource:
  ```yaml
  spring:
    datasource:
      url: jdbc:tc:postgresql:17.4-alpine:///selmag?TC_DAEMON=true
      username: selmag
      password: selmag
  ```
- **JDBC driver approach:** the `jdbc:tc:postgresql:<tag>:///<database>` URL is handled by the Testcontainers JDBC driver, which pulls the `postgres:17.4-alpine` image and starts the container on a random port before Flyway runs.
- **`TC_DAEMON=true`:** runs the container in daemon mode — it is left running after the test JVM exits and reused by subsequent runs, avoiding a cold start each time.
- **Database lifecycle:** Flyway applies the migrations to the container, `@Sql("/sql/products.sql")` seeds rows before each test, and `@Transactional` rolls them back afterwards.
- **`manager-app`:** the same `jdbc:tc:postgresql:...` approach against the `manager` database (see `manager-app/src/test/resources/application.yml`).
- **`feedback-service`:** no JDBC URL here — `config/TestConfig` declares `MongoDBContainer("mongo:8")` as a Spring bean with `@ServiceConnection`, so Testcontainers starts the container and Spring Boot derives the MongoDB connection from it. `spring-boot-testcontainers` provides `@ServiceConnection`; `testcontainers-mongodb` provides the container class. Docker is required.
- **Requirement:** Docker must be running.

### 8.5 Spring REST Docs (`catalogue-service`)

The catalogue API documentation is generated from the integration tests by Spring REST Docs — the tests are the source of truth, so the docs cannot drift from the actual responses.

- **Test support:** `ProductRestControllerIT` is annotated with `@AutoConfigureRestDocs` (from the `spring-boot-restdocs` test module) and `@ExtendWith(RestDocumentationExtension.class)`. Its `find_all` test documents the `GET /catalogue-api/products/1` response through `document("catalogue/products/find_all", …)`, using `preprocessResponse(prettyPrint(), modifyHeaders().remove("Vary"))` and `responseFields(...)` for `id`, `title`, and `details`.
- **Snippets:** generated into the default `catalogue-service/target/generated-snippets/catalogue/products/find_all/` (`response-body.adoc`, `response-fields.adoc`, and the usual REST Docs snippets).
- **Source document:** `catalogue-service/src/asciidoc/documentation.adoc` is the hand-written Asciidoctor document ("REST API каталога"); it includes the two snippets above via the `{snippets}` attribute.
- **Rendering:** the parent `pom.xml` configures `asciidoctor-maven-plugin` 3.2.0 (with `spring-restdocs-asciidoctor` 4.0.0, which supplies the `{snippets}` attribute) to run `process-asciidoc` in the `post-integration-test` phase with the `html` backend and `book` doctype. The plugin finds sources under `src/asciidoc` and, with `preserveDirectories`, writes the result to `catalogue-service/target/generated-docs/documentation.html`.
- **When it runs:** because the phase is `post-integration-test`, the documentation is produced by `./mvnw verify` / `./mvnw install` (not by `./mvnw test`). The `catalogue-service` ITs need Docker for Testcontainers.

## 9. Development Workflow

1.  **Database Setup:** Ensure a PostgreSQL instance is running and accessible. The system uses two separate databases:
    - `catalogue` (port `5432`), user `catalogue` / password `catalogue` — used by `catalogue-service`.
    - `manager` (port `5433`), user `manager` / password `manager` — used by `manager-app` for user management.
    - `feedback-service` uses MongoDB instead — `mongodb://localhost:27017/feedback` (`docker run --name feedback-db -p 27017:27017 mongo:8`).
2.  **Keycloak Setup:** Start Keycloak (`selmag-keycloak`, port `8082`, realm `selmag`). See `README.MD` for the `docker run` command and `config/keycloak/import/realm-export.json` for the realm configuration. Keycloak is required by **all five modules**: `manager-app` and `customer-app` sign users in through `oauth2Login` and relay the resulting token, both resource servers — `catalogue-service` and `feedback-service` — resolve `issuer-uri` at startup, and `admin-server` needs it both for its UI login and for the `client_credentials` tokens used by the monitoring flows. If Keycloak is unreachable, `feedback-service` cannot initialise its JWT decoder (the error shows up at startup or on the first request, depending on the version) — it never falls back to running unsecured. The export defines the clients and scopes used above, but `--import-realm` only applies it on the **first** realm creation. On an existing container, add the admin pieces manually: the client scope `metrics_server`, the confidential clients `catalogue-service-metrics-client` and `feedback-service-metrics-client` with `metrics_server` among their optional client scopes, and Standard Flow enabled on `admin-service` (`metrics` is already an optional scope there). The export contains no human users, just service accounts — create a user in the realm and add it to the `managers` group (admin UI) and/or the `customers` group (storefront); any authenticated user may open the Spring Boot Admin UI.
3.  **Build Project:** From the project root, run `./mvnw clean install` to build all five modules; this also runs the integration tests and generates the `catalogue-service` REST Docs HTML (`catalogue-service/target/generated-docs/documentation.html`).
4.  **Run Backend Service:** Run `./mvnw -pl catalogue-service spring-boot:run` (port `8081`; Flyway applies database migrations; registers itself with `admin-server` when it is up).
5.  **Run Feedback Service:** Run `./mvnw -pl feedback-service spring-boot:run` (port `8084`; needs MongoDB and Keycloak running). Its endpoints now require a Bearer token — without one every call returns `401`, and favourites are stored per user (`sub` from the token).
6.  **Run Admin Frontend:** Run `./mvnw -pl manager-app spring-boot:run` (port `8080`).
7.  **Run Customer Storefront:** Run `./mvnw -pl customer-app spring-boot:run` (port `8083`; needs Keycloak running — every page requires a login).
8.  **Run Admin Server:** Run `./mvnw -pl admin-server spring-boot:run` (port `8085`; needs Keycloak; `catalogue-service` must be running to show up in the instance list).
9.  **Access UI:**
    - Admin: `http://localhost:8080/catalogue/products/list` (redirected to Keycloak to sign in).
    - Customer storefront: `http://localhost:8083/customer/products/list` (also redirected to Keycloak to sign in).
    - Monitoring: `http://localhost:8085` (Spring Boot Admin UI, also redirected to Keycloak to sign in).
10. **Run Tests:** From the project root, run `./mvnw test` for the unit tests or `./mvnw verify` for unit + integration tests (and REST Docs generation). The `catalogue-service`, `manager-app` and `feedback-service` integration tests use Testcontainers (PostgreSQL or MongoDB), so Docker must be running; `customer-app` ITs use WireMock on port `54321` and need no Docker. `admin-server` currently has no tests.
