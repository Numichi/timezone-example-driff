# Lombok on JPA/Hibernate entities

`jakarta.persistence.*` imports. Advice unchanged on Hibernate 7. The patterns below are for entity classes; dedicated key classes (`@Embeddable`/`@IdClass`) need their own `equals`/`hashCode` (see composite-keys).

## Rules

- Never `@Data` on an entity (it bundles `@ToString` + `@EqualsAndHashCode` + all-fields `equals`/`hashCode`/`toString`, which break JPA). Use `@Getter` + `@Setter`.
- Always exclude relationships/collections from `equals`/`hashCode`/`toString`.
- Need equality? Prefer a stable business key; else the primary key with null handling. Never in a `Set`/`Map`? Don't override at all.
- `toString`: only a few basics (id, name), via `onlyExplicitlyIncluded = true`.

## Why (justifies the rules)

- Generated ID is `null` until persisted → two unsaved instances compare equal. So a `null`-ID entity must equal only itself.
- ID changes on save → `hashCode` changes → entity lost from a `HashSet`.
- All-fields `equals` breaks across loads (lazy vs initialized) and recurses on cyclic graphs.
- Lazy links are proxies → use `instanceof`, not `getClass()`.
- `equals`/`hashCode`/`toString` touching a lazy field fires a query or `LazyInitializationException`; bidirectional `toString` → `StackOverflowError`.

## Manual equals/hashCode

```java
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@Entity
public class User {

    @Id @GeneratedValue
    @ToString.Include
    private Long id;

    @ToString.Include
    private String username;

    @OneToMany(mappedBy = "user")
    @ToString.Exclude
    private Set<Authority> authorities = new HashSet<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User)) return false;          // instanceof for proxies
        User other = (User) o;
        if (this.id == null || other.id == null) return false; // unsaved: only equal to self
        return this.id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return (id == null ? 0 : id.hashCode());
    }
}
```

The `0 → id.hashCode()` transition can lose a transient-then-saved entity from a `HashSet`. If that matters, return a constant (e.g. `getClass().hashCode()`).

## If generating equals/hashCode with Lombok

Explicit include only, and it still won't handle null-ID — hand-writing is usually better.

```java
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
@Entity
public class Order {

    @Id @GeneratedValue
    @EqualsAndHashCode.Include
    @ToString.Include
    private Long id;

    @ToString.Include
    private String customerName;

    @OneToMany(mappedBy = "order")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Set<OrderItem> items = new HashSet<>();
}
```
