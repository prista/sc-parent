# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build the whole project
./mvnw clean package          # from root or manager-app/

# Run the app (Spring Boot with DevTools hot-reload)
./mvnw spring-boot:run        # from manager-app/

# Run a single test
./mvnw test -Dtest=ClassName  # from manager-app/

# Run all tests
./mvnw test                    # from manager-app/
```

The Maven wrapper (`mvnw`) lives in `manager-app/`. All build commands should be run from there unless working at the parent level.

## Architecture

**Multi-module Maven project** — parent POM at root (`com.drm.sandbox:sc-parent`), single child module `manager-app` (`com.drm.sandbox:manager-app`). Java 21, Spring Boot 4.0.6.

**Layered architecture** inside `manager-app/src/main/java/com/drm/sandbox/manager/`:

| Layer | Package | Role |
|-------|---------|------|
| Controller | `controller/` | Spring MVC `@Controller`s, Thymeleaf view resolution, form binding/validation |
| Service | `service/` | Interface (`ProductService`) + impl (`DefaultProductService`), delegates to repository |
| Repository | `repository/` | Interface (`ProductRepository`) + in-memory impl (`InMemoryProductRepository`) |
| Entity | `entity/` | `Product` (Lombok `@Data`, fields: `id`, `title`, `details`) |

**Two controllers**:
- `ProductsController` — `/catalogue/products` — list all, create new product
- `ProductController` — `/catalogue/products/{productId}` — view single, edit, delete

**No database** — `InMemoryProductRepository` uses a `LinkedList`; auto-increments IDs by finding the current max + 1. Data is lost on restart.

**Server-side rendering** with Thymeleaf templates under `templates/catalogue/products/`. Validation payloads are Java `record`s (`NewProductPayload`, `UpdateProductPayload`) using Jakarta Validation annotations. Error messages are resolved from `messages.properties` (Russian locale).

**Key patterns**:
- Controllers use `@ModelAttribute` to pre-load the `Product` into the model before each request via the `product()` method in `ProductController`.
- Form submissions POST back to the same URL; on validation errors the form re-renders with error messages; on success they redirect to the product view or list.
- `NoSuchElementException` thrown for missing products is caught by `@ExceptionHandler` in `ProductController`, which returns a 404 Thymeleaf view.
- `messages.properties` keys follow the convention `catalogue.products.{create|update}.errors.{field}` — add new messages there when adding validation.