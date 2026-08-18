# Register mappers as Spring converters (Spring Extensions)

Optional add-on: expose mappers through Spring's `ConversionService`, so callers inject one service and do `conversionService.convert(x, Y.class)` instead of wiring many mappers. Converters also compose (one can use another via the service).

Separate artifact: `org.mapstruct.extensions.spring:mapstruct-spring-extensions` — add it to the annotation-processor path alongside `mapstruct-processor`.

## Make a mapper a Converter

Implement Spring's `Converter<S, T>`; add the reverse with `@DelegatingConverter`.

```java
@Mapper(config = MapStructConfig.class)
public interface UserMapper extends Converter<User, UserDto> {

    UserDto convert(User source);

    @InheritInverseConfiguration
    @DelegatingConverter
    User invert(UserDto dto);   // reverse direction, also registered
}
```

## Config: generate the adapter + auto-registration

```java
@MapperConfig(componentModel = "spring")
@SpringMapperConfig(
    conversionServiceAdapterPackage   = "com.app.mapping",
    conversionServiceAdapterClassName = "ConversionServiceAdapter",
    externalConversions = @ExternalConversion(sourceType = String.class, targetType = Locale.class)
)
public interface MapStructConfig { }
```

The processor generates a `ConversionServiceAdapter` (delegates to `ConversionService`) and a `ConverterRegistrationConfiguration` that registers every `Converter` bean. Multiple `ConversionService` beans → set `conversionServiceBeanName`. `externalConversions` pulls in Spring's built-in conversions (e.g. `String→Locale`) so mappers can use them too.

## Use it

```java
UserDto dto = conversionService.convert(user, UserDto.class);
```

## When to use

- You want a single injected `ConversionService`, or converters composed through Spring.
- Otherwise plain `@Mapper(componentModel = "spring")` + constructor injection is simpler — skip the extension.

## Clarify

- Do you need the `ConversionService` indirection, or is direct mapper injection enough?
- One or multiple `ConversionService` beans?
