# @Named and qualifiers

Use to (a) apply a custom per-property transformation, or (b) disambiguate when several methods could be selected.

## @Named value method

Reference a named helper method from a property mapping with `qualifiedByName`.

```java
@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "displayName", source = "name", qualifiedByName = "toUpper")
    UserDto toDto(User user);

    @Named("toUpper")
    default String toUpper(String s) {
        return s == null ? null : s.toUpperCase();
    }
}
```

Without `qualifiedByName`, MapStruct would apply **any** matching `String→String` method — name it and reference it to be explicit.

## Disambiguating candidate methods

When two methods map the same source→target types, an unqualified mapping is ambiguous. Tag each and select per use.

```java
@Named("full")  UserDto toFullDto(User u);
@Named("light") UserDto toLightDto(User u);

// select one:
@Mapping(target = "owner", source = "owner", qualifiedByName = "light")
TeamDto toDto(Team team);
```

## Custom qualifier annotation (type-safe alternative to @Named)

For reusable, compile-checked qualifiers instead of strings:

```java
@Qualifier
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface TrimmedString {}

@TrimmedString
default String trim(String s) { return s == null ? null : s.strip(); }

@Mapping(target = "code", source = "code", qualifiedBy = TrimmedString.class)
ProductDto toDto(Product p);
```

## Clarify

- Plain 1:1 map, or a custom transformation (→ `@Named`)?
- Are multiple methods selectable for a type (→ qualifier to pick one)?
- String qualifier (`@Named`) enough, or a reusable typed one (`@Qualifier`)?
