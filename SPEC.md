# Technical Specification – Product Catalogue Manager

## 1. Overview

A web application for managing a product catalogue. The system is built with a two-service architecture: a backend REST API for data management and a separate frontend web application that provides the user interface.

### Core Features

- Product Management (Create, Read, Update, Delete).
- A clear REST API for managing products.
- A server-side rendered web interface for administrators to manage the catalogue.

### Tech Stack

- **Backend (`catalogue-service`):**
  - Java 21 & Spring Boot
  - Spring Data JPA
  - PostgreSQL
  - Flyway for database migrations
  - Spring Security (OAuth2 resource server, JWT)
  - Maven
- **Frontend (`manager-app`):**
  - Java 21 & Spring Boot
  - Thymeleaf for server-side template rendering
  - Spring MVC
  - Spring Data JPA (user management data)
  - PostgreSQL (own `manager` database)
  - Flyway for database migrations
  - Spring Security (OAuth2 login + client)
  - Maven

## 2. Architecture

### 2.1 High-Level Architecture

The application follows a distributed, two-service model plus a third-party identity provider:

- **`catalogue-service`:** A stateless backend service that exposes a RESTful API. It is the single source of truth for all product data and contains all business logic related to product management. It acts as an OAuth2 **resource server**: it validates JWT access tokens (issued by Keycloak) and authorizes each endpoint by the token's scopes — `SCOPE_view_catalogue` for reads and `SCOPE_edit_catalogue` for writes.
- **`manager-app`:** A server-side rendered web application that serves a user interface for managing products. It is an OAuth2 **client** of the `catalogue-service`: end users authenticate against Keycloak (`oauth2Login`), and each service-to-service call to `catalogue-service` carries a Bearer access token obtained via the OAuth2 client.
- **`Keycloak`:** The identity/authorization provider (realm `selmag`). It authenticates users, issues access tokens, and defines the realm roles (`ROLE_MANAGER`, `ROLE_CUSTOMER`), groups (`managers`, `customers`) and client scopes (`view_catalogue`, `edit_catalogue`) used across the system.

### 2.2 Application Layers

**Presentation Layer (`manager-app`)**
- Spring MVC controllers handle incoming HTTP requests.
- Thymeleaf templates render the dynamic HTML pages.
- Delivers a complete HTML user interface to the browser.

**Client & API Layer**
- The `manager-app` contains a REST client (`ProductsRestClient`) responsible for communicating with the `catalogue-service`.
- The `RestClient` is built in `ClientBeans` with an `OAuthClientHttpRequestInterceptor` that, on every outgoing request, obtains an OAuth2 access token via the `OAuth2AuthorizedClientManager` (client registration `keycloak`) and attaches it as a Bearer token.
- The `catalogue-service` provides a formal REST API contract at `/catalogue-api/products`.

**Security Layer**
- `catalogue-service` secures its API (`/catalogue-api/**`) as an OAuth2 resource server (`SecurityConfig`): every request must carry a valid JWT, and each endpoint requires the appropriate scope — `SCOPE_view_catalogue` (GET) or `SCOPE_edit_catalogue` (POST/PATCH/DELETE). Everything else is denied (`denyAll()`).
- `manager-app` enables `oauth2Login` and `oauth2Client` (`SecurityConfig`). A custom `OAuth2UserService` flattens the user's authorities from the ID token together with `groups`-claim entries prefixed with `ROLE_`; all UI requests require the `ROLE_MANAGER` role.

**Service Layer (`catalogue-service`)**
- Contains the core business logic within `DefaultProductService`.
- Orchestrates data validation and persistence operations.

**Data Access Layer**
- `catalogue-service`: Spring Data JPA repository (`ProductRepository`) over the `catalogue` schema.
- `manager-app`: Spring Data JPA repository (`UserRepository`) over the `user_management` schema.
- Flyway manages the evolution of both PostgreSQL schemas through SQL migration scripts.

### 2.3 OAuth2 / Keycloak Scheme

The system delegates authentication and authorization to **Keycloak** (realm `selmag`, issuer `http://localhost:8082/realms/selmag`).

- **End-user authentication (`manager-app` → browser):** `oauth2Login` redirects unauthenticated users to Keycloak. After the authorization-code flow completes, the user's authorities are built from the ID token plus the `groups` claim (only entries prefixed with `ROLE_` are kept, mapped to `SimpleGrantedAuthority`). The whole UI is gated by `ROLE_MANAGER`.
- **Service-to-service (`manager-app` → `catalogue-service`):** the client registration `keycloak` (client id `manager-app`) requests scopes `openid`, `view_catalogue`, `edit_catalogue`, `microprofile-jwt`. `OAuthClientHttpRequestInterceptor` uses an `OAuth2AuthorizedClientManager` to obtain an access token for the current principal and sends it as `Authorization: Bearer …`. `catalogue-service` validates the token against the Keycloak issuer and checks the `SCOPE_*` authorities declared in `SecurityConfig`.
- **Realm configuration:** roles `ROLE_MANAGER` / `ROLE_CUSTOMER`; groups `managers` / `customers` (each group maps to its realm role); client scopes `view_catalogue`, `edit_catalogue`, and `microprofile-jwt` (with `upn` and `groups` protocol mappers). Note the `groups` mapper is actually an `oidc-usermodel-realm-role-mapper`, so the `groups` claim carries the user's **realm roles** (`ROLE_MANAGER`, …) — this is what the app's `OAuth2UserService` filters on. The realm is exported at `config/keycloak/import/realm-export.json` and imported by the Keycloak container.

## 3. Functional Requirements

### 3.1 Product Management (Web UI)

A user accessing the `manager-app` can:
- **View a list of all products:** The main page displays a table with all products.
- **Create a new product:** A dedicated form allows the user to enter a title and details for a new product.
- **View a single product's details:** Clicking on a product leads to a page showing its details.
- **Edit a product:** From the details page, a user can navigate to an edit form to update the title and details.
- **Delete a product:** A button on the product details page allows for its removal from the system.

### 3.2 Product API (`catalogue-service`)

The API provides endpoints for full CRUD functionality on products. It is stateless and secured as an OAuth2 resource server: every request must carry a valid JWT access token, and each endpoint requires the appropriate scope — `SCOPE_view_catalogue` (GET) or `SCOPE_edit_catalogue` (POST/PATCH/DELETE).

## 4. Non-Functional Requirements

**Reliability**
- The system should gracefully handle errors, suchs as database connection issues or failures in the communication between the two services.

**Maintainability**
- The strict separation of concerns between the backend API and the frontend UI allows for independent development, testing, and deployment.

**Security**
- Service-to-service communication between `manager-app` and `catalogue-service` is protected with OAuth2. `catalogue-service` acts as a resource server, restricting `/catalogue-api/**` to requests carrying a valid JWT with the required scope (`SCOPE_view_catalogue` / `SCOPE_edit_catalogue`); `manager-app` attaches a Bearer token via `OAuthClientHttpRequestInterceptor`.
- End-user authentication for the web UI is handled by Keycloak through `oauth2Login`; the whole UI requires the `ROLE_MANAGER` role. A custom `OAuth2UserService` merges ID-token authorities with `ROLE_`-prefixed entries from the `groups` claim.
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


## 6. Backend API Design (`catalogue-service`)

All endpoints are relative to the base path `/catalogue-api/products`.

### 6.1 Payloads (DTOs)

- **`NewProductPayload`**: `{ "title": "string", "details": "string" }`
- **`UpdateProductPayload`**: `{ "title": "string", "details": "string" }`

### 6.2 Endpoints

#### `GET /`

- **Description:** Retrieves a list of all products.
- **Response 200:** `[ { "id": 1, "title": "Product 1", "details": "..." }, ... ]`

#### `POST /`

- **Description:** Creates a new product.
- **Request Body:** `NewProductPayload`
- **Response 201:** The newly created product object. `{ "id": 1, "title": "New Product", "details": "..." }`

#### `GET /{productId}`

- **Description:** Retrieves a single product by its ID.
- **Response 200:** A single product object.
- **Response 404:** If no product with the given ID is found.

#### `PATCH /{productId}`

- **Description:** Updates the details of an existing product.
- **Request Body:** `UpdateProductPayload`
- **Response 204:** No Content, on successful update.
- **Response 404:** If no product with the given ID is found.

#### `DELETE /{productId}`

- **Description:** Deletes a product by its ID.
- **Response 204:** No Content, on successful deletion.
- **Response 404:** If no product with the given ID is found.

## 7. Frontend Design (`manager-app`)

The frontend is a classic server-side rendered application using Spring MVC and Thymeleaf.

### 7.1 URL Routes & Corresponding Templates

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

### 7.2 Client-Side Communication

The `RestClientProductsRestClient` class encapsulates all logic for making HTTP calls to the `catalogue-service` REST API. It handles request creation, response parsing, and error translation. The underlying `RestClient` is built in `ClientBeans` with an `OAuthClientHttpRequestInterceptor` that obtains an access token via the `OAuth2AuthorizedClientManager` and injects it as a Bearer token (client registration id and base URI configured under `selmag.services.catalogue.*`) into every outgoing request.

## 8. Development Workflow

1.  **Database Setup:** Ensure a PostgreSQL instance is running and accessible. The system uses two separate databases:
    - `catalogue` (port `5432`), user `catalogue` / password `catalogue` — used by `catalogue-service`.
    - `manager` (port `5433`), user `manager` / password `manager` — used by `manager-app` for user management.
2.  **Keycloak Setup:** Start Keycloak (`selmag-keycloak`, port `8082`, realm `selmag`). See `README.MD` for the `docker run` command and `config/keycloak/import/realm-export.json` for the realm configuration.
3.  **Build Project:** From the project root, run `./mvnw clean install` to build both modules.
4.  **Run Backend Service:** Navigate to the `catalogue-service` directory and run `../mvnw spring-boot:run`. The service will start on port `8081` and Flyway will apply database migrations.
5.  **Run Frontend Application:** In a new terminal, navigate to the `manager-app` directory and run `../mvnw spring-boot:run`. The web application will start on port `8080`.
6.  **Access UI:** Open a web browser and go to `http://localhost:8080/catalogue/products/list` (you will be redirected to Keycloak to sign in).
