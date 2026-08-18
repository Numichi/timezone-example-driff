# @BeforeMapping / @AfterMapping

Hooks around a generated mapping. Selected by parameter types: a hook runs when its params can be filled from the mapping's source and target.

## @AfterMapping — post-process the target

Use `@MappingTarget` to touch the just-built target (derived fields, defaults, normalization).

```java
@Mapper(componentModel = "spring")
public interface OrderMapper {

    OrderDto toDto(Order order);

    @AfterMapping
    default void afterToDto(Order order, @MappingTarget OrderDto dto) {
        dto.setTotal(order.getLines().stream().map(Line::getAmount)
                          .reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
```

## @BeforeMapping — prepare / short-circuit

Runs before property mapping; can inspect the source or prime the target.

```java
@BeforeMapping
default void beforeToDto(Order order, @MappingTarget OrderDto dto) {
    dto.setImportedAt(Instant.now());
}
```

Hook parameter options: source object(s), `@MappingTarget` target, `@TargetType Class<?>`, and `@Context` params. Hooks may live in the mapper, in a `uses` mapper, or on a `@Context` object.

## Scoping a hook when two mappings share the target type

Problem: a hook whose params match the target type runs for **every** mapping producing that type. To run it for only one mapping, qualify the hook and request it from that mapping. `@BeanMapping(qualifiedBy/qualifiedByName)` filters which lifecycle methods apply.

```java
@Mapper(componentModel = "spring")
public interface CustomerMapper {

    // triggers the audit hook
    @BeanMapping(qualifiedByName = "withAudit")
    CustomerDto toAuditedDto(Customer c);

    // same target type, must NOT run the audit hook
    CustomerDto toPlainDto(Customer c);

    @AfterMapping
    @Named("withAudit")                    // qualifier scopes this hook
    default void addAudit(Customer c, @MappingTarget CustomerDto dto) {
        dto.setAuditedAt(Instant.now());
    }
}
```

- The `@Named("withAudit")` hook runs only for mappings whose `@BeanMapping` requests `withAudit`.
- `toPlainDto` has no such qualifier → the hook is skipped there.
- A hook **without** any qualifier still runs for all matching mappings — so put the qualifier on the hook you want to scope, not on the always-on ones.
- A custom `@Qualifier` annotation works the same as `@Named` and is type-safe.

## Clarify

- Post-processing needed that a `@Mapping` can't express (→ `@AfterMapping`)?
- Should the hook run for all mappings to this target, or only some (→ qualify it + `@BeanMapping`)?
