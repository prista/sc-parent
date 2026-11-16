# MongoDB: разбор для тех, кто пришёл из SQL

`feedback-service` хранит избранное и отзывы в MongoDB (`mongodb://localhost:27017/feedback`), а не в PostgreSQL.

Взаимодействие с БД вручную:

```
docker exec -it feedback-db mongosh feedback
```

## Иерархия

```
mongod (сервер)
└── database  "feedback"
    └── collection  "productReview"
        └── document  { ... }
            └── field  productId: 3
```

## Коллекция ≈ таблица, но с оговорками

| RDBMS (PostgreSQL) | MongoDB |
|---|---|
| database | database |
| table | **collection** |
| row | **document** |
| column | **field** |
| primary key | `_id` (уникальный индекс создаётся всегда, автоматически) |
| schema + DDL + Flyway-миграции | схемы **нет**, миграций тоже |
| `JOIN` | вложенные документы / `$lookup` |
| типы колонок (`int`, `varchar`) | у каждого поля свой BSON-тип, но он не объявлен централизованно |

## Разбор реального вывода

```
feedback> db.productReview.find()
[
  {
    _id: UUID('93d99d29-11bf-41f3-8a64-16544ddc4092'),
    productId: 3,
    rating: 4,
    review: 'first review new',
    _class: 'com.drm.sandbox.feedback.entity.ProductReview'
  },
  ...
]
```

- `db` — текущая БД (`feedback`, та же, что в `mongodb://localhost:27017/feedback`).
- `productReview` — имя коллекции. Spring Data вывел его из класса `ProductReview` (первая буква в нижний регистр). Переопределяется через `@Document(collection = "...")`.
- `find()` без аргументов = фильтр `{}` = «все документы». mongosh печатает первые 20 в pretty-формате, остальные — по `it`.

Документ — это тот же объект, что и Java-entity `feedback-service/src/main/java/com/drm/sandbox/feedback/entity/ProductReview.java`:

| В монге | В Java | Комментарий |
|---|---|---|
| `_id: UUID('…')` | `@Id private UUID id` | Первичный ключ. Mongo генерирует `_id` сам, **только если поля нет** — тогда это был бы `ObjectId('…')`. Здесь `id` задаёт приложение (UUID), поэтому в BSON лежит binary subtype 4. |
| `productId: 3` | `int productId` | |
| `rating: 4` | `int rating` | |
| `review: 'first review new'` | `String review` | |
| `_class: 'com.drm…ProductReview'` | — | **Артефакт Spring Data**, не Mongo. |

### Про `_class`

Spring Data MongoDB пишет в каждый документ FQCN класса, чтобы при чтении понять, в какой Java-тип его десериализовать (нужно для полиморфизма/наследования). Mongo сам такого поля не требует: если писать через `mongosh` вручную, его не будет, и Spring всё равно прочитает документ в `ProductReview` — поле просто игнорируется. Отключается кастомизацией `MongoMappingContext`/`MappingMongoConverter`, но обычно не стоит.

### Про `UUID('…')` вместо `ObjectId`

Работает благодаря `feedback-service/src/main/java/com/drm/sandbox/feedback/config/MongoConfig.java` — там выставлен `UuidRepresentation.STANDARD` (subtype 4):

```java
@Bean
MongoClientSettingsBuilderCustomizer uuidRepresentationCustomizer() {
    return builder -> builder.uuidRepresentation(UuidRepresentation.STANDARD);
}
```

Без этого драйвер по умолчанию пишет UUID в legacy-формате (subtype 3), и `mongosh` показывает мусорные бинари, а данные, записанные из монги, читаются криво. Классическая грабля.

## Чем коллекция **не** таблица

1. **Нет схемы.** Два документа в одной коллекции могут иметь разные наборы полей. БД не помешает записать `{productId: 3, foo: "bar"}` в ту же коллекцию. Схема живёт только в голове разработчика и в маппере Spring Data — если поля нет, при чтении в Java-поле будет `0`/`null`, и никто не пожалуется.
2. **FK и JOIN отсутствуют.** `productId` — просто число. Ссылочной целостности нет: товар №3 удалили — отзывы остались. Связи делают либо вложением (embed — отзывы внутри документа товара), либо `$lookup` на лету (дорого).
3. **Атомарность — на уровне документа.** Одна операция над одним документом атомарна. Транзакции есть (multi-document), но дороже и не разносят на все случаи.
4. **Нет Flyway.** Миграции схемы не нужны — но взамен ручная работа с уже разъехавшимися данными.
5. **Денормализация — норма.** В реляционке всё выносят в отдельные таблицы и джойнят; здесь данные кладут рядом и дублируют, чтобы читать одним запросом.

## Как это связано с кодом

`ProductReviewRepository` (`feedback-service/src/main/java/com/drm/sandbox/feedback/repository/ProductReviewRepository.java`):

```java
public interface ProductReviewRepository
        extends ReactiveCrudRepository<ProductReview, UUID> {

    Flux<ProductReview> findAllByProductId(int productId);
}
```

`findAllByProductId(3)` — это `db.productReview.find({productId: 3})`; Spring Data выводит запрос из имени метода. Репозиторий реактивный (`ReactiveCrudRepository` → `Mono`/`Flux`), потому что `feedback-service` — WebFlux-приложение.

## Полезное в mongosh

```js
show dbs                          // список БД
show collections                  // коллекции текущей БД
db.productReview.findOne()        // один документ
db.productReview.find({productId: 3})
db.productReview.countDocuments({productId: 3})
db.productReview.getIndexes()     // какие индексы есть (для _id — всегда)
db.productReview.deleteMany({})   // очистить коллекцию
db.productReview.drop()           // удалить коллекцию
```

## На что обратить внимание в этом проекте

Стоит выполнить `db.productReview.getIndexes()` — там будет только индекс по `_id`. То есть `productId` **не** проиндексирован, и `findAllByProductId` — full collection scan. В проде под такой запрос нужен:

```js
db.productReview.createIndex({productId: 1})
db.favouriteProduct.createIndex({productId: 1})
```

Spring Data умеет создавать индексы декларативно — через `@Indexed` на поле или `@CompoundIndex` на классе (при `spring.data.mongodb.auto-index-creation: true`), либо через `IndexOperations` в `MongoConfig`.