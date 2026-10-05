# Technical Specification – Product Catalogue Manager

## 1. Overview

A web application for managing a product catalogue and collecting customer feedback. The system is built with a distributed, four-service architecture: a backend REST API for product data, a reactive feedback REST API, an admin web application for catalogue management, and a public customer storefront.

### Core Features

- Product Management (Create, Read, Update, Delete) via a REST API and an admin UI.
- A clear REST API for managing products.
- Product feedback: favourite products and product reviews (reactive REST API).
- A server-side rendered admin interface for managing the catalogue.
- A public, server-side rendered customer storefront for browsing products, adding favourites, and leaving reviews.

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
  - In-memory repositories (no database)
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
  - Maven

## 2. Architecture

### 2.1 High-Level Architecture

The application follows a distributed, four-service model plus a third-party identity provider:

- **`catalogue-service`:** A stateless backend service that exposes a RESTful API. It is the single source of truth for all product data and contains all business logic related to product management. It acts as an OAuth2 **resource server**: it validates JWT access tokens (issued by Keycloak). Reads (`GET`) are public (`permitAll()`), while writes (`POST`/`PATCH`/`DELETE`) require the `SCOPE_edit_catalogue` authority.
- **`feedback-service`:** A reactive (WebFlux) backend service that stores product feedback — favourite products and product reviews — in **in-memory repositories** (no database). It exposes a reactive REST API under `/api/v1/feedback-api/**` and is currently unsecured.
- **`manager-app`:** A server-side rendered (Spring MVC) web application that serves an admin interface for managing products. It is an OAuth2 **client** of the `catalogue-service`: end users authenticate against Keycloak (`oauth2Login`), and each service-to-service call to `catalogue-service` carries a Bearer access token obtained via the OAuth2 client.
- **`customer-app`:** A reactive (WebFlux), server-side rendered storefront. It is **public** (no authentication) and communicates with `catalogue-service` (products) and `feedback-service` (favourites/reviews) through reactive `WebClient` clients.
- **`Keycloak`:** The identity/authorization provider (realm `selmag`). It authenticates users, issues access tokens, and defines the realm roles (`ROLE_MANAGER`, `ROLE_CUSTOMER`), groups (`managers`, `customers`) and client scopes (`view_catalogue`, `edit_catalogue`) used across the system.

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
- `manager-app` → `catalogue-service`: the `ProductsRestClient` (`RestClientProductsRestClient`) uses a blocking `RestClient` built in `ClientBeans` with an `OAuthClientHttpRequestInterceptor` that, on every outgoing request, obtains an OAuth2 access token via the `OAuth2AuthorizedClientManager` (client registration `keycloak`) and attaches it as a Bearer token.
- `customer-app` → `catalogue-service` / `feedback-service`: reactive `WebClient` clients (`WebClientProductsClient`, `WebClientFavouriteProductsClient`, `WebClientProductReviewsClient`) built in `ClientConfig` (base URLs `selmag.services.catalogue.uri` and `selmag.services.feedback.uri`). They consume `Mono`/`Flux` and translate `WebClientResponseException` errors into `ClientBadRequestException` / empty results.
- `catalogue-service` provides a formal REST API contract at `/catalogue-api/products`; `feedback-service` at `/api/v1/feedback-api/**`.

**Security Layer**
- `catalogue-service` secures its API as an OAuth2 resource server (`SecurityConfig`): `GET /catalogue-api/**` is `permitAll()` (no token required), while `POST`/`PATCH`/`DELETE` require `SCOPE_edit_catalogue`. Everything else is denied (`denyAll()`).
- `manager-app` enables `oauth2Login` and `oauth2Client` (`SecurityConfig`). A custom `OAuth2UserService` flattens the user's authorities from the ID token together with `groups`-claim entries prefixed with `ROLE_`; all UI requests require the `ROLE_MANAGER` role.
- `customer-app` and `feedback-service` are currently unsecured (no Spring Security configured).

**Service Layer**
- `catalogue-service` (`DefaultProductService`): core product business logic — validation, persistence, and the optional title `filter`.
- `feedback-service` (`DefaultFavouriteProductsService`, `DefaultProductReviewsService`): favourite/review business logic over the in-memory repositories.

**Data Access Layer**
- `catalogue-service`: Spring Data JPA repository (`ProductRepository`) over the `catalogue` schema.
- `manager-app`: Spring Data JPA repository (`UserRepository`) over the `user_management` schema.
- `feedback-service`: in-memory repositories (`InMemoryFavouriteProductRepository`, `InMemoryProductReviewRepository`) — no database, data is lost on restart.
- Flyway manages the evolution of the PostgreSQL schemas (`catalogue`, `manager`) through SQL migration scripts.

### 2.3 OAuth2 / Keycloak Scheme

The system delegates authentication and authorization to **Keycloak** (realm `selmag`, issuer `http://localhost:8082/realms/selmag`).

- **End-user authentication (`manager-app` → browser):** `oauth2Login` redirects unauthenticated users to Keycloak. After the authorization-code flow completes, the user's authorities are built from the ID token plus the `groups` claim (only entries prefixed with `ROLE_` are kept, mapped to `SimpleGrantedAuthority`). The whole admin UI is gated by `ROLE_MANAGER`.
- **Service-to-service (`manager-app` → `catalogue-service`):** the client registration `keycloak` (client id `manager-app`) requests scopes `openid`, `view_catalogue`, `edit_catalogue`, `microprofile-jwt`. `OAuthClientHttpRequestInterceptor` uses an `OAuth2AuthorizedClientManager` to obtain an access token for the current principal and sends it as `Authorization: Bearer …`. `catalogue-service` validates the token against the Keycloak issuer and checks the `SCOPE_*` authorities declared in `SecurityConfig` for write endpoints.
- **Reads are public:** `catalogue-service` marks `GET /catalogue-api/**` as `permitAll()`. This lets the token-less `customer-app` browse products without any OAuth2 client configuration; only the `manager-app` performs OAuth2.
- **Realm configuration:** roles `ROLE_MANAGER` / `ROLE_CUSTOMER`; groups `managers` / `customers` (each group maps to its realm role); client scopes `view_catalogue`, `edit_catalogue`, and `microprofile-jwt` (with `upn` and `groups` protocol mappers). Note the `groups` mapper is actually an `oidc-usermodel-realm-role-mapper`, so the `groups` claim carries the user's **realm roles** (`ROLE_MANAGER`, …) — this is what the app's `OAuth2UserService` filters on. The realm is exported at `config/keycloak/import/realm-export.json` and imported by the Keycloak container.

## 3. Functional Requirements

### 3.1 Product Management (Admin Web UI — `manager-app`)

A user accessing the `manager-app` can:
- **View a list of all products:** The main page displays a table with all products.
- **Create a new product:** A dedicated form allows the user to enter a title and details for a new product.
- **View a single product's details:** Clicking on a product leads to a page showing its details.
- **Edit a product:** From the details page, a user can navigate to an edit form to update the title and details.
- **Delete a product:** A button on the product details page allows for its removal from the system.

### 3.2 Customer Storefront (`customer-app`)

A visitor to the `customer-app` can:
- **Browse products** with an optional title filter (case-insensitive "contains" search).
- **View a single product's details**, including its reviews.
- **Add / remove a product to/from favourites.**
- **Leave a product review** with a 1–5 rating and free-text (validated server-side).

### 3.3 Product API (`catalogue-service`)

The API provides endpoints for full CRUD functionality on products, plus title-based filtering. It is stateless and secured as an OAuth2 resource server: `GET` endpoints are public (`permitAll()`), while `POST`/`PATCH`/`DELETE` require a valid JWT carrying the `SCOPE_edit_catalogue` authority.

### 3.4 Feedback API (`feedback-service`)

The reactive API stores and returns favourites and reviews. Data lives in in-memory repositories, so it resets on restart.

## 4. Non-Functional Requirements

**Reliability**
- The system should gracefully handle errors, such as database connection issues or failures in the communication between services.

**Maintainability**
- The strict separation of concerns between the backend APIs and the frontend UIs allows for independent development, testing, and deployment.

**Security**
- Service-to-service communication between `manager-app` and `catalogue-service` is protected with OAuth2. `catalogue-service` acts as a resource server, restricting write endpoints (`POST`/`PATCH`/`DELETE` on `/catalogue-api/**`) to requests carrying a valid JWT with `SCOPE_edit_catalogue`; `manager-app` attaches a Bearer token via `OAuthClientHttpRequestInterceptor`. `GET` endpoints are public.
- End-user authentication for the admin web UI is handled by Keycloak through `oauth2Login`; the whole admin UI requires the `ROLE_MANAGER` role. A custom `OAuth2UserService` merges ID-token authorities with `ROLE_`-prefixed entries from the `groups` claim.
- `customer-app` and `feedback-service` are currently unsecured.
- The `user_management` schema, `UserRepository`, `User`/`Authority` entities and `MUserDetailService` are retained from the earlier HTTP-Basic / DB-backed auth approach and are no longer wired into the active security filter chain.

## 5. Data Model & Database Schema (PostgreSQL)

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

### 5.3 `feedback-service` — in-memory storage

The `feedback-service` has no database. It stores `FavouriteProduct` and `ProductReview` records in `Collections.synchronizedList`-backed repositories (`InMemoryFavouriteProductRepository`, `InMemoryProductReviewRepository`). All data is lost when the service restarts.

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
- **Access:** public (`permitAll()`).
- **Response 200:** `[ { "id": 1, "title": "Product 1", "details": "..." }, ... ]`

##### `POST /`

- **Description:** Creates a new product.
- **Request Body:** `NewProductPayload`
- **Access:** requires `SCOPE_edit_catalogue`.
- **Response 201:** The newly created product object. `{ "id": 1, "title": "New Product", "details": "..." }`

##### `GET /{productId}`

- **Description:** Retrieves a single product by its ID.
- **Access:** public (`permitAll()`).
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

All endpoints are relative to the base path `/api/v1/feedback-api`. The service is reactive (returns `Mono`/`Flux`) and unsecured.

#### Favourite Products — `/favourite-products`

- **`GET /`** → `Flux<FavouriteProduct>` — lists all favourite products.
- **`GET /by-product-id/{productId}`** → `Mono<FavouriteProduct>` — returns the favourite for a product (empty → 404 if none).
- **`POST /`** → `Mono<ResponseEntity<FavouriteProduct>>` — adds a product to favourites. Body: `NewFavouriteProductPayload { "productId": 1 }`. Returns `201 Created` with a `Location` header.
- **`DELETE /by-product-id/{productId}`** → `Mono<ResponseEntity<Void>>` — removes a product from favourites. Returns `204 No Content`.

#### Product Reviews — `/product-reviews`

- **`GET /by-product-id/{productId}`** → `Flux<ProductReview>` — lists reviews for a product.
- **`POST /`** → `Mono<ResponseEntity<ProductReview>>` — creates a review. Body: `NewProductReviewPayload { "productId": 1, "rating": 5, "review": "..." }`. Returns `201 Created` with a `Location` header.

#### Entities

- **`FavouriteProduct`**: `{ "id": "uuid", "productId": 1 }`
- **`ProductReview`**: `{ "id": "uuid", "productId": 1, "rating": 5, "review": "..." }`

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

- `POST /customer/products/{productId}/add-to-favourites` — adds the product to favourites, redirects back to the product page.

- `POST /customer/products/{productId}/remove-from-favourites` — removes the product from favourites, redirects back.

- `POST /customer/products/{productId}/create-review` — submits a review; on validation error re-renders the product page with errors.

#### Client-Side Communication

The `customer-app` uses reactive `WebClient` clients (built in `ClientConfig`, base URLs `selmag.services.catalogue.uri` = `http://localhost:8081` and `selmag.services.feedback.uri` = `http://localhost:8084`):
- `WebClientProductsClient` → `catalogue-service` (`/catalogue-api/products`).
- `WebClientFavouriteProductsClient` → `feedback-service` (`/api/v1/feedback-api/favourite-products`).
- `WebClientProductReviewsClient` → `feedback-service` (`/api/v1/feedback-api/product-reviews`).

Each returns `Mono`/`Flux`; controllers compose them reactively (e.g. `collectList()`, `flatMap`, `thenReturn`) and return a `Mono<String>` view name. `WebClientResponseException.BadRequest` is translated into `ClientBadRequestException` carrying the server's validation `errors`.

## 8. Testing

The project ships unit tests and Spring Boot integration tests across the two blocking modules (`catalogue-service`, `manager-app`). The reactive modules (`customer-app`, `feedback-service`) have no tests yet.

### 8.1 Unit tests (`manager-app`)

- `ProductsControllerTest` uses plain Mockito (`@ExtendWith(MockitoExtension.class)`, `@Mock` / `@InjectMocks`) with no Spring context. It stubs the `ProductsRestClient` and asserts the returned view name / redirect target and model attributes (success and validation-error paths).

### 8.2 Integration tests

Integration tests use `@SpringBootTest` with `@AutoConfigureMockMvc` (Spring Boot 4.0 package `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`) to exercise the web layer end-to-end without a live server.

- **`catalogue-service` — `ProductsRestControllerIT`:** boots against an embedded Testcontainers PostgreSQL (datasource URL `jdbc:tc:postgresql:...` in `src/test/resources/application.yml`), seeds data with `@Sql("/sql/products.sql")`, and rolls back each test via `@Transactional`. The JWT is simulated with `SecurityMockMvcRequestPostProcessors.jwt()` and a mocked `JwtDecoder` (`TestingBeans`).
- **`manager-app` — `ProductsControllerIT`:** boots with the `standalone` profile (relies on the local `manager` database) and simulates a signed-in user with `SecurityMockMvcRequestPostProcessors.user().roles("MANAGER")`. The OAuth2 client beans (`ClientRegistrationRepository`, `OAuth2AuthorizedClientRepository`) are mocked in `TestingBeans`, and `catalogue-service` is stubbed with WireMock.

### 8.3 Test dependencies

- `org.springframework.boot:spring-boot-starter-test` (test)
- `org.springframework.boot:spring-boot-starter-webmvc-test` (test) — provides `@AutoConfigureMockMvc`
- `org.springframework.security:spring-security-test` (test)
- `org.testcontainers:testcontainers-postgresql` (test, `catalogue-service`, `manager-app`)
- `org.wiremock:wiremock-standalone` (test, `manager-app`)

### 8.4 Testcontainers (`catalogue-service`)

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
- **Requirement:** Docker must be running.

## 9. Development Workflow

1.  **Database Setup:** Ensure a PostgreSQL instance is running and accessible. The system uses two separate databases:
    - `catalogue` (port `5432`), user `catalogue` / password `catalogue` — used by `catalogue-service`.
    - `manager` (port `5433`), user `manager` / password `manager` — used by `manager-app` for user management.
    - `feedback-service` needs no database (in-memory).
2.  **Keycloak Setup:** Start Keycloak (`selmag-keycloak`, port `8082`, realm `selmag`). See `README.MD` for the `docker run` command and `config/keycloak/import/realm-export.json` for the realm configuration.
3.  **Build Project:** From the project root, run `./mvnw clean install` to build all four modules.
4.  **Run Backend Service:** Run `./mvnw -pl catalogue-service spring-boot:run` (port `8081`; Flyway applies database migrations).
5.  **Run Feedback Service:** Run `./mvnw -pl feedback-service spring-boot:run` (port `8084`).
6.  **Run Admin Frontend:** Run `./mvnw -pl manager-app spring-boot:run` (port `8080`).
7.  **Run Customer Storefront:** Run `./mvnw -pl customer-app spring-boot:run` (port `8083`).
8.  **Access UI:**
    - Admin: `http://localhost:8080/catalogue/products/list` (redirected to Keycloak to sign in).
    - Customer storefront: `http://localhost:8083/customer/products/list` (public).
9.  **Run Tests:** From the project root, run `./mvnw test` to run all tests. The `catalogue-service` integration test uses Testcontainers, so Docker must be running.
