Контекст:
{вставить AGENTS-файл или его релевантную часть}

Текущий код:
```java
public record CustomerDTO(Long id, String name, String email) {}

@GetMapping("/api/v1/customers")
public List<CustomerDTO> listCustomers() {
    return customerService.findAll();
}
```

Задача:
Добавь cursor-based пагинацию для эндпоинта списка клиентов.

Требования:
1. Cursor-based (не offset-based) для стабильности при добавлении данных
2. Размер страницы по умолчанию 20, максимум 100
3. Возврат метаданных пагинации (hasNext, cursor)

Формат:
1. Изменённый контроллер
2. DTO для ответа с пагинацией
3. Метод в репозитории (Spring Data JPA)
4. Пример запроса и ответа

Ограничения:
- Использовать Pageable и Slice из Spring Data
- Не менять существующий endpoint без пагинации (добавить новый)
- Cursor через Base64-encoded ID