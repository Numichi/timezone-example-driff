# Ignoring target properties

## A few ignores (blacklist)

```java
@Mapping(target = "id", ignore = true)
@Mapping(target = "createdAt", ignore = true)
UserEntity toEntity(UserDto dto);
```

## Many ignores → whitelist with ignoreByDefault

When most targets should stay unset and you only want to map a handful, don't write a long ignore list. Flip the default and list only what to map.

```java
@BeanMapping(ignoreByDefault = true)
@Mapping(target = "username", source = "name")
@Mapping(target = "email", source = "email")
UserEntity toEntity(UserDto dto);   // every other target is ignored, no warnings
```

1.6 note: `ignoreByDefault` applies to **target** properties only (the 1.5 behavior of also ignoring unmapped sources was reverted).

## Force completeness (catch forgotten fields)

Make unmapped targets a compile error, so adding a new field forces an explicit map-or-ignore decision.

```java
@MapperConfig(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CentralMapperConfig { }
```

- `ReportingPolicy.ERROR` — fail the build on any unmapped target.
- `WARN` (default) — log only. `IGNORE` — silent.
- `unmappedSourcePolicy` does the same for unused source properties.

## Choosing

- Map most, drop a few → per-property `ignore = true`.
- Map a few, drop most → `@BeanMapping(ignoreByDefault = true)` whitelist.
- Want new fields to never slip through unmapped → `unmappedTargetPolicy = ERROR`.

## Clarify

- Whitelist or blacklist intent?
- Should unmapped targets fail the build or just warn?
