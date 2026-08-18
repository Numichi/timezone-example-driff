# Read side: fetching, paging, projections

Default read methods to `@Transactional(readOnly = true)` — skips dirty checking, hints the driver.

## Paging: Page vs Slice vs keyset (Scroll API)

- `Page<T>` — runs an extra `COUNT` for total pages. Use only when the UI needs a total/last page.
- `Slice<T>` — no count query, just "is there a next page". Cheaper; use for infinite scroll / "load more".
- Keyset (seek) pagination — for large or endless datasets. `OFFSET` scans and discards skipped rows (slow deep in the list); keyset filters by the last seen key.

On Spring Boot 4, do keyset (and offset) paging with the Spring Data **Scroll API**: a method taking a `ScrollPosition` and returning `Window<T>`. Don't hand-roll the `where id > :lastId` query.

```java
interface UserRepository extends Repository<User, Long> {
    // sort fields must be returned by the query; keep the sort stable and indexed
    Window<User> findFirst20ByActiveTrueOrderById(ScrollPosition position);
}

Window<User> page = repo.findFirst20ByActiveTrueOrderById(ScrollPosition.keyset()); // or .offset()
// next page:
Window<User> next = repo.findFirst20ByActiveTrueOrderById(page.positionAt(page.size() - 1));
```

`Page` and `Slice` still exist and are fine for classic UI paging; use the Scroll API when you specifically want keyset/seek behavior. `WindowIterator` can iterate all windows.

## Pagination + collection fetch pitfall (HHH000104)

Do NOT combine `join fetch` on a collection with `Pageable`/`setMaxResults`. Hibernate cannot paginate a joined collection in SQL, so it **loads everything and pages in memory** (warning `HHH000104`). Instead:

- Use `@EntityGraph` (Hibernate issues a separate select for the collection), or
- Two steps: page the IDs first, then fetch the collection for those IDs, or
- Use `@BatchSize` and let the collection load in batches.

```java
@EntityGraph(attributePaths = "roles")
Page<User> findAll(Pageable pageable);   // safe: paging on root, roles fetched separately
```

## Projections — don't select whole entities for reads

Interface projection (Spring picks only these columns):

```java
interface UserView { Long getId(); String getUsername(); }
List<UserView> findByActiveTrue();
```

DTO via constructor expression (JPQL):

```java
@Query("select new com.app.UserDto(u.id, u.username) from User u")
List<UserDto> findAllDtos();
```

Use projections on read-heavy endpoints: less data, no managed entities, avoids lazy pitfalls.

## N+1 and fetching

Symptom: one query for the list, then one per row for an association. Fixes:

- `join fetch` in JPQL for a single association (not paginated with a collection — see above).
- `@EntityGraph(attributePaths = {...})` on the repository method — ad hoc or named.
- `@BatchSize(size = N)` on the collection/entity, or global `hibernate.default_batch_fetch_size`, to load associations in IN-batches instead of one-by-one.

```java
@Entity
public class Order {
    @OneToMany(mappedBy = "order")
    @BatchSize(size = 50)
    private List<OrderLine> lines;
}
```

## Bulk update / delete

Repository writes go row-by-row through the persistence context. For mass changes use `@Modifying` JPQL — one SQL statement, but it **bypasses the context** (no cascade, no version bump unless written).

```java
@Modifying(clearAutomatically = true)   // clear stale entities from context after
@Query("update User u set u.active = false where u.lastLogin < :cutoff")
int deactivateStale(@Param("cutoff") Instant cutoff);
```

## Clarify before generating

- Does the endpoint need a total count (`Page`) or just next-page (`Slice`)?
- Offset or keyset paging (large dataset / infinite scroll)?
- Full entity or a projection — and which fields?
- Any paginated query that also fetches a collection (→ `@EntityGraph`, not `join fetch`)?

For when raw SQL is (or isn't) needed — and why `LIMIT`/window/CTE no longer require it — see `native-sql.md`.
