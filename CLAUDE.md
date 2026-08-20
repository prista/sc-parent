# CLAUDE.md

This project implements the system described in `SPEC.md`. Refer to that file for detailed architectural, database, and technical specifications.

Keep replies concise and focused on key information. Avoid unnecessary verbosity.

When working with third-party libraries, always consult official documentation to ensure up-to-date information. For efficient documentation lookup, prioritize the `context7` MCP server (`mcp__context7__*`), executing multiple queries in parallel when beneficial. If context7 does not yield sufficient results, fall back to `WebSearch` or `WebFetch`, focusing on official sources and concise information.

## Commands

- **Build Project:** `mvn clean install` (from project root, requires global Maven)
- **Run `catalogue-service`:** Navigate to `catalogue-service` directory, then `./mvnw spring-boot:run` (starts on port `8081`)
- **Run `manager-app`:** Navigate to `manager-app` directory, then `./mvnw spring-boot:run` (starts on port `8080`)
- **Access UI:** `http://localhost:8080/catalogue/products/list`

## Architecture

This is a multi-module Spring Boot application comprised of two distinct services:

- **`catalogue-service`**: A backend REST API for product management. Stateless single source of truth for product data; exposes REST at `/catalogue-api/products`.
- **`manager-app`**: A server-side rendered web application providing the user interface, acting as a client to the `catalogue-service`.

### Tech Stack

- **Runtime:** Java 21
- **Framework:** Spring Boot
- **Build Tool:** Maven
- **Database:** PostgreSQL (managed by Flyway for migrations)
- **Templating (Frontend):** Thymeleaf

### Key Dependencies

- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.boot:spring-boot-starter-data-jpa`
- `org.postgresql:postgresql`
- `org.flywaydb:flyway-core`
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
  - `src/main/resources/application-standalone.yaml` - Application configuration
  - `src/main/resources/templates/` - Thymeleaf HTML templates