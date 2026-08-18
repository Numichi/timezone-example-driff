# Enum mapping

Three main options, in typical order of preference.

## @Enumerated(EnumType.STRING) — default

Stores the enum name as text. Readable; reordering/adding constants stays safe.

```java
public enum Status { NEW, ACTIVE, CLOSED }

@Entity
public class Ticket {
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Status status;
}
```

- Pros: stable, self-documenting, safe to add constants.
- Cons: more storage than an int; renaming a constant breaks existing data (avoid, or migrate).

## EnumType.ORDINAL — avoid

Stores the ordinal (0, 1, 2…). Inserting/reordering constants silently shifts the meaning of every existing row. Use only if order can never change and compactness is critical — even then, prefer `@EnumeratedValue` with an explicit numeric field (you control the numbers) over raw ORDINAL.

## @EnumeratedValue — custom code, no converter (JPA 3.2 / Hibernate 7)

JPA 3.2 (Spring Boot 4). Mark a `final` enum field as the stored value — no converter class. Prefer over `AttributeConverter` for simple fixed codes: self-contained, add constants without a DB migration.

```java
public enum Priority {
    LOW("L"), MEDIUM("M"), HIGH("H");

    @EnumeratedValue          // this field's value is stored (idiomatically final, distinct per constant)
    private final String code; // String -> string column; byte/short/int -> numeric column
    Priority(String code) { this.code = code; }
}

@Entity
public class Ticket {
    @Column(length = 1, nullable = false)
    private Priority priority;   // stores "L"/"M"/"H"
}
```

Give each constant a distinct value (needed for the reverse lookup on read); fields are conventionally `final` and set in the constructor. A `String` field maps to a string column; `byte`/`short`/`int` to a numeric column — stable, unlike raw ORDINAL, because you control the numbers.

## AttributeConverter — for non-trivial or legacy conversions

Use when the mapping isn't a simple 1:1 field (computed value, external lookup, or an existing schema you must match). On Spring Boot 4 reach for `@EnumeratedValue` first; keep the converter for the harder cases.

```java
public enum Priority {
    LOW("L"), MEDIUM("M"), HIGH("H");

    private final String code;
    Priority(String code) { this.code = code; }
    public String getCode() { return code; }

    public static Priority fromCode(String code) {
        for (Priority p : values())
            if (p.code.equals(code)) return p;
        throw new IllegalArgumentException("Unknown code: " + code);
    }
}

@Converter(autoApply = true)   // applies to every Priority field
public class PriorityConverter implements AttributeConverter<Priority, String> {
    @Override public String convertToDatabaseColumn(Priority p) {
        return p == null ? null : p.getCode();
    }
    @Override public Priority convertToEntityAttribute(String code) {
        return code == null ? null : Priority.fromCode(code);
    }
}
```

- Pros: DB value independent of Java constant name and order.
- Without `autoApply`, annotate the field: `@Convert(converter = PriorityConverter.class)`.

## Enum vs lookup table

- Keep an enum when the value set is part of the code, small, and rarely changes (states, types).
- Use a lookup entity/table (via FK) when the set is large, business-maintained, growing, or has extra attributes (display name, active flag, order). Grows without code changes.

| Situation | Use |
|---|---|
| General case, readability | `@Enumerated(STRING)` |
| Custom stable code, simple 1:1 field | `@EnumeratedValue` (JPA 3.2) |
| External-agreed or computed/legacy conversion | `AttributeConverter` |
| Large, business-maintained, growing set | lookup table (not enum) |
| Compact int, order guaranteed fixed | `@EnumeratedValue`/converter (never raw ORDINAL) |

## Clarify before generating

- Readable name (STRING) or a custom code in the column?
- Can the value set grow; does backward compatibility matter?
- Extra attributes / business upkeep on the values (→ lookup table)?
