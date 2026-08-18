# Spring wiring

## Basic Spring mapper

`componentModel = "spring"` makes the generated impl a `@Component`; inject the interface by constructor.

```java
@Mapper(componentModel = "spring")
public interface UserMapper {
    UserDto toDto(User user);
    User toEntity(UserDto dto);
}

@Service
public class UserService {
    private final UserMapper userMapper;
    public UserService(UserMapper userMapper) { this.userMapper = userMapper; }
}
```

## Central config — don't repeat settings

Put shared options once; reference from each mapper.

```java
@MapperConfig(
    componentModel = "spring",
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR   // strict: every target must be mapped or ignored
)
public interface CentralMapperConfig { }

@Mapper(config = CentralMapperConfig.class, uses = { AddressMapper.class })
public interface UserMapper {
    UserDto toDto(User user);
}
```

- `uses = {...}` lets this mapper delegate nested types to other mappers.
- `injectionStrategy = CONSTRUCTOR` — inject `uses` mappers via constructor (testable, final fields).

## Build setup

Annotation processors: `org.mapstruct:mapstruct` (API) + `org.mapstruct:mapstruct-processor` (processor). With Lombok, also add `org.projectlombok:lombok-mapstruct-binding` and keep Lombok's processor **before** MapStruct's so getters/setters exist when MapStruct runs.

## Clarify

- Reuse an existing `@MapperConfig`, or create one?
- Strict (`ReportingPolicy.ERROR`) or lenient (`WARN`) on unmapped targets?
- Any nested types needing their own mapper in `uses`?
