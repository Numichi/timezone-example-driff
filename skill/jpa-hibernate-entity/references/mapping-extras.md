# Often-missed entity features

Suggest these when the domain hints at the need. Most need a **config hook** (bean or property), not just the annotation.

## @Version — optimistic locking

When two requests can edit the same row: adds a version column, second commit fails with `OptimisticLockException`. No DB locks.

```java
@Version
private Long version;   // int/Integer, long/Long, short, or a timestamp (Instant/Timestamp)
```

Real DB locks only for hot contention: `@Lock(LockModeType.PESSIMISTIC_WRITE)` on the repository method.

## Auditing — created/updated tracking

Annotations mark the fields; separate hooks fill them.

Enable, then declare the fields:

```java
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider", dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig { }

@EntityListeners(AuditingEntityListener.class)
@MappedSuperclass
public abstract class Auditable {
    @CreatedDate      private Instant createdAt;
    @LastModifiedDate private Instant updatedAt;
    @CreatedBy        private String  createdBy;
    @LastModifiedBy   private String  updatedBy;
}
```

`@CreatedBy`/`@LastModifiedBy` are filled by an `AuditorAware<T>` bean (`T` = the field type). To use a user id/entity instead of a username, change the field type and the bean generic together.

```java
@Bean
public AuditorAware<String> auditorProvider() {
    return () -> Optional.ofNullable(SecurityContextHolder.getContext())
        .map(SecurityContext::getAuthentication)
        .filter(Authentication::isAuthenticated)
        .map(Authentication::getName);
}
```

`@CreatedDate`/`@LastModifiedDate` type follows the field (`Instant`, `LocalDateTime`, `OffsetDateTime`, `Date`, `Long`). To control clock/zone, add a `DateTimeProvider` bean:

```java
@Bean
public DateTimeProvider auditingDateTimeProvider() {
    return () -> Optional.of(OffsetDateTime.now(ZoneOffset.UTC));
}
```

Without Spring / user tracking: `@CreationTimestamp` / `@UpdateTimestamp`.

Hibernate 7: temporal↔column alignment is stricter — `Instant`/`OffsetDateTime` may need an explicit `@Column(columnDefinition = "timestamp(6) with time zone")` or a matching field type.

## Soft delete — never hard-delete

Native `@SoftDelete` (since Hibernate 6.4, `@Incubating`): manages the indicator column and rewrites every query and delete.

```java
@Entity
@SoftDelete(columnName = "deleted", strategy = SoftDeleteType.DELETED) // or ACTIVE for a Y/N flag
public class Account {
    @Id @GeneratedValue
    private Long id;
}
```

`@SoftDelete` is `@Incubating` — pin the version. Legacy / bespoke SQL: `@SQLDelete` + `@SQLRestriction` (static filter).

```java
@Entity
@SQLDelete(sql = "update account set deleted = true where id = ?")
@SQLRestriction("deleted = false")   // older: @Where(clause = "deleted = false")
public class Account {
    private boolean deleted = false;
}
```

## @Filter — dynamic, parameterized filter

Off by default, enabled per session with parameters (vs. the static `@SQLRestriction`). For multi-tenant or "include deleted" views.

```java
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Entity
public class Document { /* ... */ }

// enable per request:
entityManager.unwrap(Session.class).enableFilter("tenantFilter").setParameter("tenantId", currentTenantId);
```

Auto-enable (skip per-session `enableFilter`): put `autoEnabled = true` on the **`@FilterDef`** (not `@Filter`), and supply parameters via `@ParamDef(resolver = ...)`. Available since Hibernate 6.x.

## @NaturalId — stable business key

Real-world unique key beside the surrogate id; enables `session.byNaturalId(...)`. Cache with `@NaturalIdCache`.

```java
@NaturalId
@Column(nullable = false, unique = true, updatable = false)
private String email;
```

## @Immutable — read-only reference data

Reference entities that never change after load; Hibernate skips dirty checking.

```java
@Entity @Immutable
public class Country { /* ... */ }
```

## @DynamicUpdate / @DynamicInsert

Wide table, few columns change per update: SQL with only the changed columns; avoids clobbering unloaded columns.

```java
@Entity @DynamicUpdate
public class Product { /* many columns */ }
```

## @Formula — derived read-only column

Value computed in SQL (dialect-specific, runs on every load), no column.

```java
@Formula("(select avg(r.score) from review r where r.product_id = id)")
private Double averageScore;
```

## @ElementCollection / @Embedded — value objects, no identity

Collection of basic/embeddable values owned by the entity:

```java
@ElementCollection
@CollectionTable(name = "user_tag", joinColumns = @JoinColumn(name = "user_id"))
@Column(name = "tag")
private Set<String> tags = new HashSet<>();
```

Single embeddable inlined into the owner's table:

```java
@Embeddable public class Address { private String city; private String zip; }

@Entity public class User {
    @Embedded private Address address;
}
```

Reuse one `@Embeddable` twice with `@AttributeOverrides` to remap columns (billing vs shipping).

## Ordered collections

- `@OrderBy("name asc")` — sort by a field on load.
- `@OrderColumn(name = "position")` — persist list order in a column.
- `@MapKey` — key a `Map<K, V>` by an attribute of the target entity.

## Large data / transient

- `@Lob` for CLOB/BLOB; `@Basic(fetch = LAZY)` loads big columns on access (needs bytecode enhancement).
- `@Transient` for computed, non-persisted fields.

## Second-level cache

Hot, rarely-changing entities across sessions. Annotation + enable + provider.

```java
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE) // READ_ONLY | NONSTRICT_READ_WRITE | READ_WRITE | TRANSACTIONAL
public class Currency { /* ... */ }
```

```properties
spring.jpa.properties.hibernate.cache.use_second_level_cache=true
spring.jpa.properties.hibernate.cache.region.factory_class=jcache
spring.jpa.properties.hibernate.cache.use_query_cache=true
```

`READ_ONLY` for never-updated data, `READ_WRITE` when it changes. Reference data only, not volatile rows.

## Clarify before generating

- Can two requests edit the same row (→ `@Version`)?
- Deletes reversible / audited (→ soft delete)?
- Created/updated (by whom) tracking (→ auditing; what type/source → `AuditorAware`/`DateTimeProvider`)?
- Real-world unique key besides the id (→ `@NaturalId`)?
- Reference data that never changes (→ `@Immutable` / 2nd-level cache)?
- Visibility depends on runtime context like tenant (→ `@Filter`)?
