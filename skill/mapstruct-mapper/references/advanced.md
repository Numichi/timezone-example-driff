# Advanced mapping features

## Presence checks — @Condition / @SourceParameterCondition (1.6)

Decide whether a property (or a whole source parameter) gets mapped.

```java
public class PresenceUtils {
    @Condition
    public boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}

@Mapper(componentModel = "spring", uses = PresenceUtils.class)
public interface UserMapper {
    UserDto toDto(User u);   // String props mapped only when isNotBlank
}
```

- Access the property name (1.6): `@Condition boolean isInit(Customer c, @TargetPropertyName String prop)`.
- Guard the entire source parameter (1.6) — skip mapping / return null when absent:

```java
@SourceParameterCondition
public boolean hasCustomer(OrderDTO dto) {
    return dto != null && dto.getCustomerName() != null;
}
```

- Inline per property: `@Mapping(target = "customer", source = "dto", conditionQualifiedByName = "hasCustomer")` or `conditionExpression = "java(dto.getCustomerName() != null)"`.

## Polymorphism — @SubclassMapping

Map a base type to a base DTO, dispatching by concrete subclass (MapStruct generates the `instanceof` dispatch, so you don't hand-write it). `@SubclassMapping` is `@Experimental`.

```java
@Mapper(componentModel = "spring")
public interface ShapeMapper {
    @SubclassMapping(source = Circle.class, target = CircleDto.class)
    @SubclassMapping(source = Square.class, target = SquareDto.class)
    ShapeDto toDto(Shape shape);
}
```

To pick which method maps a given subclass, use the qualifier attributes **on `@SubclassMapping` itself** — `@SubclassMapping(..., qualifiedByName = "x")` or `qualifiedBy` (1.6). Note: a `@BeanMapping(qualifiedByName)` on the method does **not** propagate to the subclass mappings — use `@SubclassMapping`'s own attributes.

## Reuse config — @InheritConfiguration / @InheritInverseConfiguration

Don't repeat `@Mapping`s.

- `@InheritConfiguration` — another method (e.g. an update with `@MappingTarget`) reuses a create method's mappings.
- `@InheritInverseConfiguration` — the reverse method inherits the inverse of the forward mappings.

```java
@Mapping(target = "fullName", source = "name")
UserDto toDto(User u);

@InheritInverseConfiguration
User toEntity(UserDto dto);
```

## Enum-to-enum — @ValueMapping

Map constants, including defaults and unmapped handling.

```java
@ValueMapping(source = "EXTRA", target = "SPECIAL")
@ValueMapping(source = MappingConstants.ANY_REMAINING, target = "UNKNOWN")
@ValueMapping(source = MappingConstants.NULL, target = "UNKNOWN")
StatusDto map(Status status);
```

Without `ANY_REMAINING`/`ANY_UNMAPPED`, an unmapped constant is a compile error — a safe default that catches new enum values.

## Constants, defaults, expressions

- `@Mapping(target = "active", constant = "true")` — fixed value.
- `@Mapping(target = "name", source = "name", defaultValue = "N/A")` — used when source is null.
- `@Mapping(target = "id", source = "id", defaultExpression = "java(UUID.randomUUID().toString())")`.
- `@Mapping(target = "full", expression = "java(u.getFirst() + \" \" + u.getLast())")` — arbitrary Java; import types via `@Mapper(imports = {...})`.

Prefer a `@Named`/qualified method over long `expression=` strings — it's testable and reusable.

## Clarify

- Should a property map only when present/non-blank (→ `@Condition`)?
- Polymorphic source hierarchy (→ `@SubclassMapping`)?
- A reverse mapper mirroring the forward one (→ `@InheritInverseConfiguration`)?
- Enum→enum with defaults / unmapped values (→ `@ValueMapping`)?
