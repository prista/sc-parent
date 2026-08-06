package com.drm.sandbox.manager.client;

import com.drm.sandbox.manager.controller.payload.NewProductPayload;
import com.drm.sandbox.manager.controller.payload.UpdateProductPayload;
import com.drm.sandbox.manager.entity.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Реализация {@link ProductsRestClient} на основе Spring {@link RestClient}.
 * Обращается к REST API каталога товаров по базовому URL, настроенному для {@link RestClient}.
 * Преобразует HTTP-ошибки (400 Bad Request, 404 Not Found) в доменные исключения
 * {@link BadRequestException} и {@link NoSuchElementException}.
 */
@RequiredArgsConstructor
public class RestClientProductsRestClient implements ProductsRestClient {

    // Тип-ссылка для List<Product> нужна, чтобы RestClient смог десериализовать обобщённый тип:
    // из-за стирания типов простой Product.class не сохранит информацию о List<Product>.
    private static final ParameterizedTypeReference<List<Product>> PRODUCTS_TYPE_REFERENCE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    /**
     * Получает список всех товаров, выполняя GET-запрос к {@code /catalogue-api/products}.
     *
     * @return список товаров; пустой список, если товаров нет
     */
    @Override
    public List<Product> findAllProducts() {
        return this.restClient
                // Строим GET-запрос; baseUrl уже задан в настроенном бине RestClient.
                .get()
                // Путь добавляется к baseUrl.
                .uri("/catalogue-api/products")
                // Инициируем выполнение запроса; по умолчанию для 4xx/5xx бросает HttpClientErrorException.
                .retrieve()
                // Десериализуем тело ответа (JSON-массив) в List<Product> с помощью тип-ссылки.
                .body(PRODUCTS_TYPE_REFERENCE);
    }

    /**
     * Создаёт новый товар, выполняя POST-запрос к {@code /catalogue-api/products}
     * с JSON-телом {@link NewProductPayload}.
     *
     * @param title   название товара
     * @param details описание товара
     * @return созданный товар
     * @throws BadRequestException если сервер вернул 400 Bad Request с ошибками валидации
     */
    @Override
    public Product createProduct(String title, String details) {
        try {
            return this.restClient
                    // Строим POST-запрос к эндпоинту создания товара.
                    .post()
                    .uri("/catalogue-api/products")
                    // Тело будет сериализовано в JSON.
                    .contentType(MediaType.APPLICATION_JSON)
                    // DTO- payload сериализуется в тело запроса.
                    .body(new NewProductPayload(title, details))
                    // Выполняем запрос; при 400 Bad Request бросает HttpClientErrorException.BadRequest.
                    .retrieve()
                    // Десериализуем созданный товар из ответа в Product и возвращаем его.
                    .body(Product.class);
        // Сервер отклонил payload (ошибки валидации) — читаем тело ошибки как ProblemDetail.
        } catch (HttpClientErrorException.BadRequest exception) {
            // Десериализуем тело ошибки (RFC 9457 ProblemDetail) из ответа.
            ProblemDetail problemDetail = exception.getResponseBodyAs(ProblemDetail.class);
            // Достаём список сообщений об ошибках из свойства "errors" и пробрасываем доменное исключение.
            throw new BadRequestException((List<String>) problemDetail.getProperties().get("errors"));
        }
    }

    /**
     * Находит товар по идентификатору, выполняя GET-запрос к {@code /catalogue-api/products/{productId}}.
     *
     * @param productId идентификатор товара
     * @return товар, обёрнутый в {@link Optional}; {@link Optional#empty()}, если сервер вернул 404 Not Found
     */
    @Override
    public Optional<Product> findProduct(int productId) {
        try {
            // Если сервер вернёт null-тело, оборачиваем его в пустой Optional.
            return Optional.ofNullable(this.restClient.get()
                    // Подставляем productId в URI-шаблон.
                    .uri("/catalogue-api/products/{productId}", productId)
                    // Выполняем запрос; при 404 бросает HttpClientErrorException.NotFound.
                    .retrieve()
                    // Десериализуем товар; null, если тело пустое.
                    .body(Product.class));
        // Товар не найден — возвращаем пустой Optional вместо исключения.
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        }
    }

    /**
     * Обновляет товар, выполняя PATCH-запрос к {@code /catalogue-api/products/{productId}}
     * с JSON-телом {@link UpdateProductPayload}.
     *
     * @param productId идентификатор товара
     * @param title     новое название товара
     * @param details   новое описание товара
     * @throws BadRequestException если сервер вернул 400 Bad Request с ошибками валидации
     */
    @Override
    public void updateProduct(int productId, String title, String details) {
        try {
            this.restClient
                    // Строим PATCH-запрос для частичного обновления товара.
                    .patch()
                    // Подставляем productId в URI-шаблон.
                    .uri("/catalogue-api/products/{productId}", productId)
                    // Тело будет сериализовано в JSON.
                    .contentType(MediaType.APPLICATION_JSON)
                    // DTO- payload сериализуется в тело запроса.
                    .body(new UpdateProductPayload(title, details))
                    // Выполняем запрос; при 400 Bad Request бросает HttpClientErrorException.BadRequest.
                    .retrieve()
                    // Нам не нужно тело ответа, поэтому запрашиваем bodiless-ответ (ResponseEntity<Void>).
                    .toBodilessEntity();
        // Сервер отклонил payload (ошибки валидации) — читаем тело ошибки как ProblemDetail.
        } catch (HttpClientErrorException.BadRequest exception) {
            // Десериализуем тело ошибки (RFC 9457 ProblemDetail) из ответа.
            ProblemDetail problemDetail = exception.getResponseBodyAs(ProblemDetail.class);
            // Достаём список сообщений об ошибках и пробрасываем доменное исключение.
            throw new BadRequestException((List<String>) problemDetail.getProperties().get("errors"));
        }
    }

    /**
     * Удаляет товар, выполняя DELETE-запрос к {@code /catalogue-api/products/{productId}}.
     *
     * @param productId идентификатор товара
     * @throws NoSuchElementException если сервер вернул 404 Not Found (товар не найден)
     */
    @Override
    public void deleteProduct(int productId) {
        try {
            this.restClient
                    // Строим DELETE-запрос для удаления товара.
                    .delete()
                    // Подставляем productId в URI-шаблон.
                    .uri("/catalogue-api/products/{productId}", productId)
                    // Выполняем запрос; при 404 бросает HttpClientErrorException.NotFound.
                    .retrieve()
                    // Тело ответа не нужно — запрашиваем bodiless-ответ.
                    .toBodilessEntity();
        // Товар не найден — пробрасываем как доменное NoSuchElementException.
        } catch (HttpClientErrorException.NotFound exception) {
            throw new NoSuchElementException(exception);
        }
    }
}
