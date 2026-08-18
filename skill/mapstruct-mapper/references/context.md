# @Context

A `@Context` parameter is passed through the whole mapping call chain but is **not** itself a mapped property. Use it for data/services needed during mapping, or for graph state.

## Pass-through data or a service

Thread a locale, current user, or a repository to resolve references.

```java
@Mapper(componentModel = "spring")
public interface OrderMapper {

    OrderDto toDto(Order order, @Context Locale locale);

    @AfterMapping
    default void localize(@MappingTarget OrderDto dto, @Context Locale locale) {
        dto.setStatusLabel(dto.getStatus().label(locale));
    }
}
```

The `@Context` value flows into nested mappings and lifecycle methods automatically — declare the same `@Context` param where you need it.

## Context object with its own lifecycle methods — cycle avoidance

A `@Context` object may define `@BeforeMapping`/`@AfterMapping` methods. The canonical use is breaking cycles in bidirectional graphs: remember already-mapped instances and return them instead of remapping.

```java
public class CycleAvoidingContext {
    private final Map<Object, Object> known = new IdentityHashMap<>();

    @BeforeMapping
    public <T> T getMapped(Object source, @TargetType Class<T> targetType) {
        return (T) known.get(source);   // non-null → MapStruct reuses it, stops recursion
    }

    @BeforeMapping
    public void storeMapped(Object source, @MappingTarget Object target) {
        known.put(source, target);
    }
}

@Mapper(componentModel = "spring")
public interface NodeMapper {
    NodeDto toDto(Node node, @Context CycleAvoidingContext ctx);
}
```

Callers pass a fresh context per top-level call: `mapper.toDto(node, new CycleAvoidingContext())`.

## Clarify

- Does the mapping need outside data or a service (→ `@Context`)?
- Bidirectional/self-referencing graph that could recurse (→ cycle-avoiding `@Context`)?
- Should the context be per-call (stateful, like cycle tracking) or a shared bean?
