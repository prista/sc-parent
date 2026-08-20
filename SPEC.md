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
  - Maven
- **Frontend (`manager-app`):**
  - Java 21 & Spring Boot
  - Thymeleaf for server-side template rendering
  - Spring MVC
  - Maven

## 2. Architecture

### 2.1 High-Level Architecture

The application follows a distributed, two-service model:

- **`catalogue-service`:** A stateless backend service that exposes a RESTful API. It is the single source of truth for all product data and contains all business logic related to product management.
- **`manager-app`:** A frontend web application that serves a user interface for managing products. It is a client of the `catalogue-service` and interacts with it via its REST API.

### 2.2 Application Layers

**Presentation Layer (`manager-app`)**
- Spring MVC controllers handle incoming HTTP requests.
- Thymeleaf templates render the dynamic HTML pages.
- Delivers a complete HTML user interface to the browser.

**Client & API Layer**
- The `manager-app` contains a REST client (`ProductsRestClient`) responsible for communicating with the `catalogue-service`.
- The `catalogue-service` provides a formal REST API contract at `/catalogue-api/products`.

**Service Layer (`catalogue-service`)**
- Contains the core business logic within `DefaultProductService`.
- Orchestrates data validation and persistence operations.

**Data Access Layer (`catalogue-service`)**
- Spring Data JPA repositories (`ProductRepository`) provide an abstraction over the database.
- Flyway manages the evolution of the PostgreSQL database schema through SQL migration scripts.

## 3. Functional Requirements

### 3.1 Product Management (Web UI)

A user accessing the `manager-app` can:
- **View a list of all products:** The main page displays a table with all products.
- **Create a new product:** A dedicated form allows the user to enter a title and details for a new product.
- **View a single product's details:** Clicking on a product leads to a page showing its details.
- **Edit a product:** From the details page, a user can navigate to an edit form to update the title and details.
- **Delete a product:** A button on the product details page allows for its removal from the system.

### 3.2 Product API (`catalogue-service`)

The public API provides endpoints for full CRUD functionality on products. The API is stateless and does not handle authentication or authorization.

## 4. Non-Functional Requirements

**Reliability**
- The system should gracefully handle errors, suchs as database connection issues or failures in the communication between the two services.

**Maintainability**
- The strict separation of concerns between the backend API and the frontend UI allows for independent development, testing, and deployment.

**Security**
- The current implementation has no security measures. The API is open and accessible without authentication. For a production system, this would need to be addressed (e.g., using Spring Security).

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

The `RestClientProductsRestClient` class encapsulates all logic for making HTTP calls to the `catalogue-service` REST API. It handles request creation, response parsing, and error translation.

## 8. Development Workflow

1.  **Database Setup:** Ensure a PostgreSQL instance is running and accessible. Create a database named `catalogue` with a user `catalogue` and password `catalogue`.
2.  **Build Project:** From the project root, run `./mvnw clean install` to build both modules.
3.  **Run Backend Service:** Navigate to the `catalogue-service` directory and run `../mvnw spring-boot:run`. The service will start on port `8081` and Flyway will apply database migrations.
4.  **Run Frontend Application:** In a new terminal, navigate to the `manager-app` directory and run `../mvnw spring-boot:run`. The web application will start on port `8080`.
5.  **Access UI:** Open a web browser and go to `http://localhost:8080/catalogue/products/list`.
