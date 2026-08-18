---
name: mapstruct-mapper
description: Design and generate MapStruct mappers in a Spring application. Use whenever the user creates or edits a MapStruct `@Mapper`, DTO↔entity mapping, or bean-to-bean converter — even without the word "MapStruct" (e.g. "map this entity to a DTO", "convert the request to a domain object", "add a mapper for X"). Covers Spring wiring, `@Named`/qualifier selection, ignoring strategies, `@BeforeMapping`/`@AfterMapping`, `@Context`, and restricting lifecycle methods when several mappings target the same type. This file holds the decisions and questions to ask; references/ holds the code.
---

# MapStruct mapper design (Spring)

You are generating correct MapStruct mappers. This file says which option to pick and what to ask when context is missing. Read the matching `references/*.md` for code before writing the mapper.

## Target stack

MapStruct **1.6.x**, Spring (`componentModel = "spring"`), Java 17+, `jakarta.*`. Add `org.mapstruct:mapstruct` + the `mapstruct-processor` annotation processor; with Lombok also add `lombok-mapstruct-binding` and keep Lombok before MapStruct in the processor path. 1.6 note: `ignoreByDefault` applies to **target** properties only (the 1.5 source-side behavior was reverted).

## Ask before you guess

When generating a mapper, don't invent behavior. If a decision below needs input the user didn't give, ask in chat first — batch missing items into one round. Skip trivial or already-answered ones.

## Spring wiring

- Always `@Mapper(componentModel = "spring")` so the mapper is a Spring bean; inject it by constructor.
- Share settings via a central `@MapperConfig` and `@Mapper(config = ...)`; don't repeat `componentModel`/policies per mapper.
- Compose mappers with `uses = { OtherMapper.class }`; prefer `injectionStrategy = CONSTRUCTOR`.

Ask when unclear:
- Is there an existing central `@MapperConfig` to reuse?
- Should unmapped target properties be an error (strictness)?

→ `references/setup-spring.md`

## Custom value methods and selection — @Named / qualifiers

- A per-property transformation (format, uppercase, code→label) → a `@Named("x")` method, referenced by `@Mapping(target=..., source=..., qualifiedByName="x")`.
- Ambiguous candidate methods (same source/target types) → disambiguate with `qualifiedByName` / a custom `@Qualifier` annotation.

Ask when unclear:
- Does a field need a custom transformation, or a plain 1:1 map?
- Are there multiple methods MapStruct could pick between (needs a qualifier)?

→ `references/named-qualifiers.md`

## Ignoring target properties

- A few unmapped targets → `@Mapping(target="x", ignore=true)`.
- Many ignores / whitelist intent → `@BeanMapping(ignoreByDefault = true)`, then list only the `@Mapping`s you want; everything else is ignored. Prefer this over a long ignore list.
- Force completeness → `unmappedTargetPolicy = ReportingPolicy.ERROR` (on `@MapperConfig`/`@Mapper`): every target must be mapped or explicitly ignored, or it fails at compile time.

Ask when unclear:
- Whitelist (map a few, ignore the rest) or blacklist (map most, ignore a few)?
- Should unmapped targets fail the build?

→ `references/ignoring.md`

## Before/after hooks — @BeforeMapping / @AfterMapping

- Post-fill or adjust the target (derived fields, defaults, normalization) → `@AfterMapping` with `@MappingTarget`.
- Pre-checks / short-circuit / prepare the target → `@BeforeMapping`.
- These run for **every** mapping whose source/target types match — see the qualifier trick below to scope them.

Ask when unclear:
- Does the target need post-processing MapStruct can't express as a `@Mapping`?
- Should the hook run for all mappings to this type, or only some?

→ `references/lifecycle.md`

## Passing extra data — @Context

- Data needed during mapping but not itself a mapped property (locale, current user, a repository to resolve references, cycle-tracking) → a `@Context` parameter, threaded through the call chain.
- A `@Context` object may carry its own `@BeforeMapping`/`@AfterMapping` methods (e.g. graph cycle avoidance).

Ask when unclear:
- Does the mapping need outside data or a service to resolve references?
- Is there a cycle risk in the object graph (bidirectional links)?

→ `references/context.md`

## Two mappings to the same target — scoping lifecycle methods

When two mapping methods produce the same target type but only one should trigger a given `@AfterMapping`/`@BeforeMapping`:

- Put a qualifier on the lifecycle method (`@Named("x")` or a custom `@Qualifier`).
- On the mapping that should trigger it, add `@BeanMapping(qualifiedByName = "x")`.
- The other mapping (without that qualifier) won't invoke it. Unqualified lifecycle methods still run for all matching mappings — so qualify the one you want to scope.

Ask when unclear:
- Which of the same-target mappings should run the hook?
- Should the hook be opt-in (qualified) or always-on (unqualified)?

→ `references/lifecycle.md`

## Conditional, polymorphic, enum, and reuse features (often-missed)

Reach for these when the shape fits:
- Map a property only when present/non-blank → `@Condition`; guard a whole source parameter → `@SourceParameterCondition` (1.6). Inline: `conditionQualifiedByName` / `conditionExpression`.
- Polymorphic source hierarchy → `@SubclassMapping` (dispatch per subclass, no instanceof ladder).
- Reverse or update method mirroring another → `@InheritInverseConfiguration` / `@InheritConfiguration`.
- Enum→enum → `@ValueMapping` (with `ANY_REMAINING`/`NULL` defaults); unmapped constants are a compile error by default.
- Fixed/derived values → `constant`, `defaultValue`, `defaultExpression`, `expression` (prefer a `@Named` method over long expressions).

Ask when unclear:
- Should some properties map only conditionally?
- Is the source an inheritance hierarchy?
- Is there a mirrored reverse mapping?

→ `references/advanced.md`

## Mappers as Spring converters (optional)

- Want one injected `ConversionService` (`convert(x, Y.class)`) instead of many mappers, or converters composed via Spring → MapStruct Spring Extensions: mappers implement `Converter<S,T>`, a `@SpringMapperConfig` generates the adapter + auto-registration.
- Otherwise plain `@Mapper(componentModel="spring")` + constructor injection — don't add the extension without a reason.

Ask when unclear:
- Is the `ConversionService` indirection actually wanted, or is direct injection enough?

→ `references/spring-extensions.md`

## Workflow

1. Identify what applies (Spring wiring, custom values, ignoring, lifecycle hooks, context, same-target scoping, conditional/polymorphic/enum/reuse, converter registry).
2. Check the needed context for each; batch-ask what's missing before generating.
3. Read the relevant `references/*.md` for exact code.
4. Generate the mapper and briefly state the choices made.
