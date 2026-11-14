Контекст:
Микросервис CRM-системы: модуль CustomerService, отвечает за создание и обновление клиентов.
Стек: Java 21, Spring Boot 3.2, Spring Data JPA, PostgreSQL.
Архитектура: Hexagonal Architecture, слой application отделён от domain и infrastructure.

Код для ревью:
```java
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerMapper mapper;

    @Transactional
    public CustomerDTO createCustomer(CreateCustomerRequest request) {
        Customer customer = mapper.toEntity(request);
        customer.setCreatedAt(LocalDateTime.now());
        customerRepository.save(customer);
        return mapper.toDto(customer);
    }

    public CustomerDTO getCustomer(Long id) {
        return customerRepository.findById(id)
            .map(mapper::toDto)
            .orElseThrow(() -> new CustomerNotFoundException(id));
    }
}
```

Задача:
1. Найди потенциальные проблемы с транзакциями и propagation.
2. Оцени обработку ошибок и edge cases.
3. Проверь соответствие лучшим практикам Spring (constructor injection, immutability).

Формат:
Для каждой проблемы:
- Строка/метод
- Описание проблемы
- Рекомендация с примером кода

Ограничения:
- Не меняй public сигнатуры методов.
- Не предлагай смену ORM или фреймворка.