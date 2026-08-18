# Composite (multi-column) primary key

Two JPA options: `@IdClass` and `@EmbeddedId`. For both, the key class must:
- `implements Serializable`,
- have correct `equals()`/`hashCode()` on the key values,
- have a public no-arg constructor.

| Situation | Use |
|---|---|
| Key is a standalone, named value object, handled as a unit | `@EmbeddedId` |
| Key columns wanted as flat fields on the entity | `@IdClass` |
| A key column is also a relationship FK | `@MapsId` (with either) |

`@EmbeddedId` is the cleaner default; `@IdClass` fits when you want flat key fields and no embedded type.

Note on records: a Java `record` cannot be used as an `@Embeddable`/`@EmbeddedId`/`@IdClass` — these require a public no-arg constructor, which records don't have, so use a normal class (as below). Records are still useful for query projections (`select new com.app.SomeRecord(...)`), just not as the key type.

## @EmbeddedId

Key is an `@Embeddable`; the entity holds one `@EmbeddedId` field.

```java
@Embeddable
public class OrderLineId implements Serializable {
    private Long orderId;
    private Long productId;

    protected OrderLineId() {}                 // required no-arg
    public OrderLineId(Long orderId, Long productId) { /* ... */ }

    @Override public boolean equals(Object o) { /* orderId + productId */ }
    @Override public int hashCode() { return Objects.hash(orderId, productId); }
}

@Entity
public class OrderLine {
    @EmbeddedId
    private OrderLineId id;
    private int quantity;
}
```

Query path: `orderLine.id.orderId`.

## @IdClass

Separate key class (not `@Embeddable`); key fields appear on the entity with separate `@Id`, names matching the key class.

```java
public class OrderLineId implements Serializable {
    private Long order;      // name = entity field name
    private Long product;

    public OrderLineId() {}
    @Override public boolean equals(Object o) { /* order + product */ }
    @Override public int hashCode() { return Objects.hash(order, product); }
}

@Entity
@IdClass(OrderLineId.class)
public class OrderLine {
    @Id private Long order;
    @Id private Long product;
    private int quantity;
}
```

Query path: `orderLine.order`, `orderLine.product`.

## Key column that is also a FK — @MapsId

If a key column is a relationship's FK, don't duplicate it; use `@MapsId` to derive the key field from the association.

```java
@Embeddable
public class OrderLineId implements Serializable {
    private Long orderId;
    private Long productId;
}

@Entity
public class OrderLine {
    @EmbeddedId
    private OrderLineId id;

    @MapsId("orderId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @MapsId("productId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;
}
```

## Clarify before generating

- Which columns form the key?
- Named key concept (Embedded) or flat fields (IdClass)?
- Is any key column also a relationship FK (→ `@MapsId`)?
- Is the key value available before persist (natural), or generated?
