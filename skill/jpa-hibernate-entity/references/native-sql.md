# Native SQL vs JPQL/HQL

Default to JPQL/HQL. Native queries lose type-safety, dialect portability, and clean entity-state handling — use them only when HQL genuinely can't do the job. Version note: everything below is Hibernate 6+, so it holds on Spring Boot 4.1 (Hibernate 7.x).

## `LIMIT` is not a reason

Row limiting never needed native SQL: use `Pageable` / `setMaxResults()` / `setFirstResult()`. And Hibernate 6+ HQL accepts the keywords directly:

```java
// HQL, no native needed
@Query("from Order o where o.status = :s order by o.createdAt desc limit :n")
List<Order> latest(@Param("s") Status s, @Param("n") int n);
```

## Native is required (even on Hibernate 7)

- **No mapped entity** for the table/view — HQL can only name mapped types. The most common mandatory case. Map results back with an entity (if columns line up), a `@SqlResultSetMapping` + `@ConstructorResult`, or a Spring Data interface/DTO projection.
- **DDL** (CREATE / ALTER / DROP) — HQL is DML only.
- **DB-specific syntax** not modeled by HQL — Oracle `CONNECT BY`, `PIVOT`/`UNPIVOT`; Postgres `RETURNING`; optimizer hints (`/*+ ... */`); JSON / array / geospatial / full-text operators.
- **Multiple statements**, or calling a **stored procedure** — prefer `@NamedStoredProcedureQuery` / Spring Data `@Procedure` over a raw native call.

```java
// native query → interface projection (Spring Data)
@Query(value = "select region, sum(total) as revenue from sales_mv group by region",
       nativeQuery = true)
List<RegionRevenue> revenueByRegion();   // interface with getRegion()/getRevenue()
```

## Do NOT go native for these — HQL handles them (Hibernate 6+)

- Window functions: `over (...)`, `row_number()`, `rank()`, `dense_rank()`, `lag`/`lead`.
- CTEs: `with x as (...)`, including `with recursive`.
- Set operations: `union` / `union all`, `intersect`, `except`.
- `lateral` joins; tuple / row-value comparison `(a, b) = (:x, :y)`.
- `limit` / `offset` / `fetch first`.

If the only blocker was one of these, rewrite in HQL and keep type-safety.

## `FOR UPDATE SKIP LOCKED` without native SQL

A job-queue poll doesn't force native. In a Spring Data repository, the lock mode gives `FOR UPDATE`, the lock-timeout hint gives `SKIP LOCKED`, and `Pageable` gives `LIMIT`:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
@Query("select s from InsuranceScheduleEntity s where s.updateDue <= :when")
List<InsuranceScheduleEntity> claimDue(@Param("when") LocalDateTime when, Pageable page);
```

- `jakarta.persistence.lock.timeout` special values: `-2` = SKIP LOCKED, `0` = NOWAIT, `-1` = wait forever (`jakarta.*` on Spring Boot 4).
- Typed alternative (Hibernate 7): `query.unwrap(org.hibernate.query.Query.class).setHibernateLockMode(LockMode.UPGRADE_SKIPLOCKED)`, or `SelectionQuery.setTimeout(Timeouts.SKIP_LOCKED)`. JPA 3.2 also lets you pass a `LockMode`/`Timeout` straight to `find`/`lock`/`refresh`.

Hibernate generates the same SQL either way — the native version is a style/readability choice here, not a necessity.

## Mapping native results

- Columns match an entity's table → return the entity directly.
- Arbitrary columns → `@SqlResultSetMapping` with `@ConstructorResult` (to a DTO) or `@EntityResult`.
- Spring Data → an interface projection (getters match column aliases) or a class DTO.
- One-off scalars/tuples → `Tuple` or `Object[]`.

## Clarify before generating

- Is every table in the query backed by a mapped entity? If not → native.
- Is the blocker a real DB-specific construct, or just `LIMIT`/window/CTE (→ HQL)?
- How should the native result be mapped (entity / `@SqlResultSetMapping` / projection)?
