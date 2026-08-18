# Primary key generation (`@GeneratedValue`)

Choosing the surrogate `@Id` strategy. Assumes Spring Boot 4 (Hibernate 7, JPA 3.2).

## Strategy choice

| Strategy | When | Note |
|---|---|---|
| `SEQUENCE` | Default choice on Hibernate | Batchable with a pooled optimizer; needs DB sequence support |
| `IDENTITY` | Simple, low-volume inserts | DB auto-increment; **disables JDBC insert batching** |
| `UUID` | Distributed / client-generated / opaque ids | JPA 3.1+; store as native `uuid` / `binary(16)`; prefer v7 (see below) |
| `TABLE` | Portable last resort | Emulates a sequence via a table; row-lock contention, slow |
| `AUTO` | Let the provider pick | Explicit is clearer; Hibernate resolves per dialect |

Why `SEQUENCE` over `IDENTITY`: with `IDENTITY` Hibernate must run each INSERT immediately to read the generated key, so it **cannot batch inserts**. `SEQUENCE` pre-fetches ids (pooled optimizer), enabling batch inserts and fewer round trips. Sequences are supported on PostgreSQL, Oracle, H2, SQL Server; MySQL/MariaDB emulate them.

## @SequenceGenerator

```java
@Entity
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
    @SequenceGenerator(name = "order_seq", sequenceName = "order_seq", allocationSize = 50)
    private Long id;
}
```

- `allocationSize` — how many ids Hibernate reserves per DB hit (pooled optimizer). It **must match the DB sequence's `INCREMENT BY`**, or ids collide/skip. Hibernate default is 50.
- Also available: `initialValue`, `sequenceName`, `schema`/`catalog`.

## JPA 3.2 ergonomics (Spring Boot 4)

Less boilerplate than before:

- **Package-level generators** — declare a `@SequenceGenerator` once in `package-info.java` and reuse it across entities.
- **Optional generator name** — a package-level `@SequenceGenerator`/`@TableGenerator` with a defaulted name is applied to a bare `@GeneratedValue(strategy = SEQUENCE)` (no `generator`) in that package, so you don't repeat the generator reference on each entity.
- **PrePersist timing clarified** — `SEQUENCE`/`TABLE`/`UUID` ids are available in `@PrePersist`; an `IDENTITY` id only exists after the INSERT.

```java
// package-info.java
@SequenceGenerator(name = "app_seq", sequenceName = "app_seq", allocationSize = 50)
package com.app.domain;

// entity — no generator name needed, resolves to the package generator
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE)
private Long id;
```

## UUID keys

`GenerationType.UUID` (JPA 3.1) yields a **random v4** UUID. Random v4 scatters index inserts (page splits, poor locality), so for a primary key on a large table prefer a **time-ordered UUIDv7** — its leading bits are a timestamp, so inserts stay roughly sequential. Store as native `uuid` (PostgreSQL) or `binary(16)`.

```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
private UUID id;   // random v4 — fine, but worse index locality on big tables
```

Prefer UUIDv7. Two ways on Spring Boot 4.1 (Hibernate 7):

Built-in (cleanest) — `@UuidGenerator(style = Style.VERSION_7)` (the v6/v7 styles are `@Incubating`). Note `Style.TIME` is version 1, not v7 — don't use it for locality.

```java
@Id
@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
private UUID id;
```

Custom generator (library-backed, or on Hibernate < the v7 style) — a `@IdGeneratorType` annotation over a `BeforeExecutionGenerator`, using the `com.github.f4b6a3.uuid` library (`getTimeOrderedEpoch()` = v7):

```java
@IdGeneratorType(Uuid7Generator.class)
@Retention(RUNTIME)
@Target({ FIELD, METHOD })
public @interface Uuid7Generated { }

public class Uuid7Generator implements BeforeExecutionGenerator {
    @Override
    public EnumSet<EventType> getEventTypes() {
        return EnumSet.of(EventType.INSERT);
    }
    @Override
    public Object generate(SharedSessionContractImplementor session, Object owner,
                           Object currentValue, EventType eventType) {
        if (currentValue != null) return currentValue;   // respect a pre-set id
        return UuidCreator.getTimeOrderedEpoch();          // f4b6a3 UUIDv7
    }
}

@Id
@Uuid7Generated
private UUID id;
```

Use UUID keys when ids must be generated before persist, merged across systems, or must not be guessable/enumerable.

## Clarify before generating

- DB dialect — real sequence support, or emulation (MySQL/MariaDB)?
- Bulk/high-volume inserts expected (→ `SEQUENCE` + pooled, not `IDENTITY`)?
- Distributed / client-generated / opaque ids (→ `UUID`, prefer time-ordered)?
- Does `allocationSize` match the DB sequence increment?
