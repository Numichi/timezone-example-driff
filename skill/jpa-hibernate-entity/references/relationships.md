# Relationships: ManyToOne, OneToMany, ManyToMany, OneToOne

## Shared concepts

- Owning side: holds the FK / join table (`@JoinColumn` / `@JoinTable`); only its changes are persisted.
- Inverse side (`mappedBy`): navigation only.
- fetch: `@ManyToOne`/`@OneToOne` default EAGER → usually set LAZY. `@OneToMany`/`@ManyToMany` default LAZY (fine).
- cascade: which ops propagate (`PERSIST`, `MERGE`, `REMOVE`, `ALL`). Use `REMOVE`/`ALL` only for true composition.
- orphanRemoval = true: removing a child from the collection deletes it. Only for real ownership.
- Bidirectional: keep both sides in sync via a helper; otherwise the in-memory graph is inconsistent.

## @ManyToOne — most common, the owning side

FK lives here; almost always the owning side.

```java
@Entity
public class OrderLine {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)   // LAZY, not default EAGER
    @JoinColumn(name = "order_id")
    private Order order;
}
```

## @OneToMany — inverse side with mappedBy

Bidirectional OneToMany always uses `mappedBy`; the ManyToOne side owns.

```java
@Entity
public class Order {
    @Id @GeneratedValue
    private Long id;

    @OneToMany(mappedBy = "order",
               cascade = CascadeType.ALL,
               orphanRemoval = true)
    private List<OrderLine> lines = new ArrayList<>();

    public void addLine(OrderLine line) {   // sync helper
        lines.add(line);
        line.setOrder(this);
    }
}
```

Avoid unidirectional `@OneToMany` without `@JoinColumn` — it generates a join table or extra UPDATEs. If unidirectional is required, add `@JoinColumn`, or make it bidirectional.

## @ManyToMany — only for attribute-less links

Join table, no own entity. One side owns (`@JoinTable`), the other `mappedBy`.

```java
@Entity
public class Student {
    @ManyToMany
    @JoinTable(name = "student_course",
        joinColumns = @JoinColumn(name = "student_id"),
        inverseJoinColumns = @JoinColumn(name = "course_id"))
    private Set<Course> courses = new HashSet<>();
}

@Entity
public class Course {
    @ManyToMany(mappedBy = "courses")
    private Set<Student> students = new HashSet<>();
}
```

If the link carries its own fields (enrollment date, grade, quantity), do NOT use `@ManyToMany`; make a join entity with two `@ManyToOne`.

```java
@Entity
public class Enrollment {
    @EmbeddedId
    private EnrollmentId id;

    @MapsId("studentId") @ManyToOne(fetch = FetchType.LAZY)
    private Student student;

    @MapsId("courseId") @ManyToOne(fetch = FetchType.LAZY)
    private Course course;

    private LocalDate enrolledAt;   // link's own attribute
    private String grade;
}
```

## @OneToOne — shared PK or separate FK

Shared primary key (`@MapsId`) — child PK equals parent PK, no extra FK column:

```java
@Entity
public class UserProfile {
    @Id
    private Long id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id")
    private User user;
}
```

Separate FK — owner has `@JoinColumn`, the other `mappedBy`:

```java
@Entity
public class User {
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL)
    private UserProfile profile;
}
```

Lazy OneToOne pitfall: on the optional/nullable side, lazy often doesn't apply (Hibernate must know null vs proxy). Fixes: shared PK (`@MapsId`), `optional = false`, or bytecode enhancement.

## Clarify before generating

- Uni- or bidirectional?
- Which side owns the FK / join table?
- Does the link carry its own fields (→ join entity instead of ManyToMany)?
- `fetch` (LAZY/EAGER), `cascade`, `orphanRemoval`?
- OneToOne: shared PK (`@MapsId`) or separate FK column?
