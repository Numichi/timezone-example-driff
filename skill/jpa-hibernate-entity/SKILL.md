---
name: jpa-hibernate-entity
description: Design and generate JPA/Hibernate entities in Spring Boot. Use whenever the user creates or edits an @Entity, ORM mapping, or DB-table object — even without the words "JPA"/"Hibernate" (e.g. "make a User class for the table", "link these two tables", "add an enum field", "I need a multi-column key"). Covers Lombok on entities, inheritance-to-table strategies, composite keys (IdClass vs Embedded), the four relationship types (ManyToOne, OneToMany, ManyToMany, OneToOne), and enum mapping. This file holds the decisions and the questions to ask; references/ holds the code and pitfalls.
---

# JPA / Hibernate entity design

You are generating correct JPA/Hibernate entities. This file tells you which option to pick and what to ask when context is missing. Read the matching `references/*.md` for code and pitfalls before writing the mapping.

## Target stack

Assume Spring Boot 4.1 (2026): **Hibernate ORM 7.x**, **Jakarta Persistence 3.2** (confirmed pinned as 3.2.0), **Spring Framework 7.1**, Spring Data release train **2026.x**. Baseline Java 17+, `jakarta.*` imports (never `javax.*`). This enables newer options the references use — `@EnumeratedValue` (JPA 3.2), Hibernate `@SoftDelete` (since 6.4), the Spring Data Scroll API (`Window`/`ScrollPosition`), auto-enabled filters (`@FilterDef(autoEnabled = true)`). Hibernate 7 also **removed** legacy `Session.save/update/saveOrUpdate/delete` (use `persist`/`merge`/`remove`) and tightened temporal type↔column alignment. HQL gained (Hibernate 6+, so present here) window functions, CTEs, set operations, lateral joins, and `limit`/`offset`/`fetch first` — so native SQL is rarely required (see the native-SQL section). Features cited as "Hibernate 6+" hold across all 7.x. If the user is on an older stack, fall back to the pre-3.2 forms noted in the references.

## Ask before you guess

When generating or editing an entity, multiple valid mappings usually exist. Do not guess the decisions below. If the user has not supplied what a decision needs, ask in chat first — batch several missing items into one round, prefer tappable options. Skip the question when the answer is trivial or already given.

## Lombok on entities

Never put `@Data` on an entity; never use bare `@EqualsAndHashCode` or default `@ToString` either — they break JPA identity, trigger lazy loads, and cause infinite recursion on bidirectional links.
Default to `@Getter` + `@Setter`. Write `equals`/`hashCode` by hand (ID-based, null-safe) or not at all.
Always exclude relationships and lazy fields from `equals`/`hashCode`/`toString`.

Ask when unclear:
- Is there a stable, immutable business (natural) key for equality? If not, use ID-based with null handling.
- Is `equals`/`hashCode` even needed (does the entity go into a `Set` / `Map` key)?

Code and pitfalls → `references/lombok.md`

## Inheritance: one physical table or many

On class-based `extends`, pick the DB shape:
- Share fields only, parent not queryable, no own table → `@MappedSuperclass`.
- One table for the whole hierarchy, fastest, subclass columns nullable → `@Inheritance(SINGLE_TABLE)` + `@DiscriminatorColumn`.
- Normalized, one table per subclass joined to parent → `@Inheritance(JOINED)`.
- A full standalone table per class, polymorphic queries costly → `@Inheritance(TABLE_PER_CLASS)`.

Ask when unclear:
- Is the parent a queryable entity, or just shared fields?
- Do any subclass columns need to be NOT NULL? (If yes, avoid SINGLE_TABLE.)
- Are polymorphic queries on the parent type required?

Strategies and trade-offs → `references/inheritance.md`

## Primary key generation

Pick the surrogate `@Id` strategy:
- Prefer `SEQUENCE` (with a pooled optimizer) — it allows JDBC batch inserts.
- Avoid `IDENTITY` when inserting in bulk — Hibernate can't batch inserts with it.
- Use `UUID` when IDs must be client/distributed-generated or opaque; on large tables prefer time-ordered **UUIDv7** for index locality — built-in `@UuidGenerator(style = VERSION_7)`, or a custom `BeforeExecutionGenerator` (e.g. f4b6a3 `UuidCreator.getTimeOrderedEpoch()`). Plain `GenerationType.UUID` is random v4.
- `TABLE` only as a last-resort portable fallback (contention).
- Tune with `@SequenceGenerator` (`allocationSize` must match the DB sequence increment). JPA 3.2 lets you declare generators at package level, make the generator name optional, and auto-pick the closest generator.

Ask when unclear:
- DB dialect — does it support sequences (Postgres/Oracle yes; MySQL/MariaDB emulate)?
- Are bulk inserts expected (→ `SEQUENCE` + pooled)?
- Need client-generated / distributed / opaque IDs (→ `UUID`)?

Strategies, `@SequenceGenerator`, JPA 3.2 ergonomics → `references/identifiers.md`

## Composite (multi-column) primary key

Choose the composite-key style:
- Key is a standalone, named value object handled as a unit → `@EmbeddedId` (default).
- Key columns wanted as flat fields directly on the entity → `@IdClass`.
- A key column is also a relationship's foreign key → `@MapsId`.

Ask when unclear:
- Which columns form the key?
- Is the key a named concept (Embedded) or flat fields (IdClass)?
- Is any key column also a foreign key of a relationship?

Code and Serializable/equals/hashCode rules → `references/composite-keys.md`

## Relationships: ManyToOne, OneToMany, ManyToMany, OneToOne

Map the association, set the owning side, decide uni- vs bidirectional:
- Put the FK and ownership on the `@ManyToOne` side; set it `LAZY` (default EAGER).
- Make `@OneToMany` the inverse side with `mappedBy`; avoid join-column-less unidirectional OneToMany.
- Use `@ManyToMany` only when the link has no own attribute; if it does (quantity, date, grade), split into a join entity with two `@ManyToOne`.
- For `@OneToOne`, choose shared PK (`@MapsId`) or a separate FK; lazy on the optional side is unreliable.

Ask when unclear:
- Uni- or bidirectional?
- Which side owns the FK / join table?
- Does the link carry its own fields (→ join entity instead of ManyToMany)?
- Which `fetch` (LAZY/EAGER), `cascade`, and `orphanRemoval`?
- OneToOne: shared PK or separate FK column?

Owning side, cascade, join-entity patterns → `references/relationships.md`

## Enum mapping

Pick how the enum is stored:
- Default to `@Enumerated(EnumType.STRING)` — readable, reorder-safe.
- Never default to `EnumType.ORDINAL`; reordering/inserting constants corrupts data.
- For a stable explicit DB code (external system, compact): prefer `@EnumeratedValue` (JPA 3.2) — a `final` field on the enum holds the stored value, no converter class. Use an `AttributeConverter` only for non-trivial/legacy conversions.
- For a large, business-maintained, growing value set → a lookup table, not an enum.

Ask when unclear:
- Readable name (STRING) or a custom code in the column?
- Can the value set grow; does backward compatibility matter?
- Do values carry extra attributes / business upkeep (→ lookup table)?

STRING vs ORDINAL vs converter → `references/enums.md`

## Read side: fetching, paging, projections

Default read methods to `@Transactional(readOnly = true)`.
Never paginate a query that `join fetch`-es a collection — Hibernate pages in memory (`HHH000104`); use `@EntityGraph`, id-then-fetch, or `@BatchSize`.
Return a projection (interface/DTO/constructor expression), not the whole entity, on read-heavy endpoints.
Use `Slice` when no total count is needed, `Page` when it is; for large/endless lists prefer keyset paging via the Spring Data Scroll API (`Window<T>` + `ScrollPosition`) over `OFFSET`.
Fix N+1 with `join fetch` or `@EntityGraph`; use `@BatchSize` / `default_batch_fetch_size` for collections.
Use `@Modifying` JPQL for mass update/delete (bypasses the context — mind cascade/version).

Ask when unclear:
- Total count (`Page`) or just next-page (`Slice`)?
- Offset or keyset paging (large dataset / infinite scroll)?
- Full entity or a projection (which fields)?

Paging, projections, N+1, bulk ops → `references/querying-and-paging.md`

## Native SQL vs JPQL/HQL

Default to JPQL/HQL; reach for `nativeQuery = true` only when HQL genuinely can't express it. `LIMIT` is **not** a reason — use `Pageable`/`setMaxResults`, and Hibernate 6+ HQL has `limit`/`offset`/`fetch first` keywords too.

Native is required (or the only sane option), even on Hibernate 7:
- A table/view with **no mapped entity** — HQL can't name it. Most common mandatory case.
- **DDL** (CREATE/ALTER/DROP) — HQL is DML only.
- **DB-specific syntax** — Oracle `CONNECT BY`/`PIVOT`, Postgres `RETURNING`, optimizer hints (`/*+ ... */`), JSON/array/geospatial/full-text operators.
- Multiple statements / stored procedures (prefer `@NamedStoredProcedureQuery` / `@Procedure`).

Do **not** go native for these — HQL handles them since Hibernate 6: window functions (`OVER`, `ROW_NUMBER`), CTEs / `WITH` (incl. recursive), set ops (`UNION`/`INTERSECT`/`EXCEPT`), `LATERAL` joins, tuple/row-value comparison. `FOR UPDATE SKIP LOCKED` is also expressible without native (`@Lock` + lock-timeout hint / `LockMode.UPGRADE_SKIPLOCKED`).

Ask when unclear:
- Is there a mapped entity for every table involved (if not → native)?
- Is the blocker a real DB-specific construct, or just `LIMIT`/window/CTE (→ HQL)?
- Native result → map to an entity, a `@SqlResultSetMapping`/DTO, or an interface projection?

→ `references/native-sql.md`

## Often-missed features (surface proactively)

Consider and suggest these even when unasked, when the domain hints at them:
- Concurrent edits on the same row → `@Version` (optimistic locking).
- Created/updated (by whom) columns → auditing (`@CreatedDate`/`@LastModifiedDate` + `AuditingEntityListener`, or `@CreationTimestamp`/`@UpdateTimestamp`).
- Rows must never be hard-deleted → Hibernate `@SoftDelete` (native, since 6.4); use `@SQLDelete` + `@SQLRestriction` only for custom/legacy schemes.
- Real-world unique key beside the surrogate id → `@NaturalId`.
- Reference rows that never change → `@Immutable` (+ second-level cache for hot reads).
- Wide table, few columns change per update → `@DynamicUpdate`.
- Derived read-only value → `@Formula`.
- Collection of basic/embeddable values with no identity → `@ElementCollection` / `@Embedded`.
- Ordered collection → `@OrderBy` (DB sort) or `@OrderColumn` (persisted position).

Ask when unclear:
- Can two requests edit the same row (→ `@Version`)?
- Must deletes be reversible / audited (→ soft delete)?
- Does the row need created/updated tracking (→ auditing), and what type/source for the values?

Many of these need a config hook (a bean or property), not just the annotation — e.g. `@CreatedBy` needs an `AuditorAware` bean, the audit timestamp type/zone a `DateTimeProvider`, second-level cache a provider + properties.

Locking, auditing (with `AuditorAware`/`DateTimeProvider` config), soft delete, filters, caching → `references/mapping-extras.md`

## Workflow

1. Identify which sections apply (id generation, key, inheritance, relationships, enum, Lombok, read/paging, native SQL, often-missed features).
2. For each, check the needed context; batch-ask what is missing before generating.
3. Read the relevant `references/*.md` for exact code and pitfalls.
4. Generate the entity per the decisions and briefly state which option you picked and why.
