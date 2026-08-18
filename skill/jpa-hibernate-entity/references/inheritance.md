# Inheritance: mapping class extends to table(s)

Four approaches. Choose by whether the parent is a standalone entity, and by speed vs. normalization.

| Goal | Use |
|---|---|
| Share fields only; parent NOT queryable, no table | `@MappedSuperclass` |
| One table for the whole hierarchy, fast | `@Inheritance(SINGLE_TABLE)` |
| Normalized, one table per subclass, joined | `@Inheritance(JOINED)` |
| Full standalone table per concrete class | `@Inheritance(TABLE_PER_CLASS)` |

## @MappedSuperclass — code sharing only

Parent is not an entity: no table, not queryable, cannot be a relationship target. Its fields land as columns in each subclass's own table. Typical: shared audit fields.

```java
@MappedSuperclass
public abstract class Auditable {
    @Column(nullable = false)
    private Instant createdAt;
    private Instant updatedAt;
}

@Entity
public class Customer extends Auditable {
    @Id @GeneratedValue
    private Long id;
    private String name;
}
```

## SINGLE_TABLE — one table, discriminator

Whole hierarchy in one table; a `@DiscriminatorColumn` distinguishes types. Default when unspecified.

- Pros: no join, fastest read/write, trivial polymorphic queries.
- Cons: subclass-specific columns cannot be NOT NULL (empty for other types); wide, sparse table.

```java
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "payment_type")
public abstract class Payment {
    @Id @GeneratedValue
    private Long id;
    private BigDecimal amount;
}

@Entity @DiscriminatorValue("CARD")
public class CardPayment extends Payment {
    private String cardNumber;   // NULL for non-CARD rows
}

@Entity @DiscriminatorValue("TRANSFER")
public class TransferPayment extends Payment {
    private String iban;
}
```

## JOINED — normalized, per table

Parent has its own table (shared fields); each subclass has its own table (own fields + PK/FK to parent). Loading joins.

- Pros: normalized, subclass columns can be NOT NULL, no redundancy.
- Cons: every query joins; deep hierarchies get expensive.

```java
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
public abstract class Vehicle {
    @Id @GeneratedValue
    private Long id;
    private String manufacturer;
}

@Entity
public class Car extends Vehicle {
    private int seatCount;   // separate car table, can be NOT NULL
}
```

## TABLE_PER_CLASS — full table per class

Each concrete class has its own complete table (inherited + own fields). No parent table.

- Pros: single-type query hits one table, no join.
- Cons: polymorphic query (`from Vehicle`) needs `UNION`, costly; limited PK generation (no plain `IDENTITY`); duplicated column defs. Rarely recommended.

```java
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
public abstract class Report {
    @Id @GeneratedValue(strategy = GenerationType.TABLE)
    private Long id;
    private String title;
}
```

## Picking

- Shared fields only, parent not a domain concept → MappedSuperclass.
- Speed first, few subclass columns, nullable acceptable → SINGLE_TABLE.
- NOT NULL constraints and a clean schema matter, join acceptable → JOINED.
- Subclasses almost always handled separately, few polymorphic queries → TABLE_PER_CLASS (with care).

## Clarify before generating

- Should the parent be a queryable entity, or just supply fields?
- Any subclass column that must be NOT NULL? (If yes, SINGLE_TABLE is a poor fit.)
- Polymorphic queries on the parent type (`List<Payment>`)?
- How many subclasses, how deep?
