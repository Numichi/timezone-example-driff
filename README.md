# tz-demo — amikor az `Instant` + `timestamp` (időzóna nélküli) páros elromlik

Egy minimális Spring Boot + Liquibase + Postgres projekt, ami **reprodukálja** azt
az időzóna-driftet, amit akkor kapsz, ha egy Java `Instant`-ot egy Postgres
`timestamp` (== `TIMESTAMP WITHOUT TIME ZONE`) oszlopra mappelsz, és megmutat
két lehetséges javítást is.

## A szituáció

Egy tipikus Liquibase migrációval megegyező:

```yaml
- column:
    name: created_at
    type: timestamp          # -> TIMESTAMP WITHOUT TIME ZONE Postgresen
```

Java oldalon így mappelve:

```java
@Column(name = "created_at")
private Instant createdAt;   // abszolút időpont a timeline-on (UTC)
```

Az `Instant` egy abszolút időpont; a `timestamp without time zone` viszont egy
zóna nélküli faliórás érték. A kettő közti átváltáshoz kell egy **referencia-zóna**,
és ha nincs beállítva a `hibernate.jdbc.time_zone`, ez a zóna a **JVM
alapértelmezett zónája** lesz. Így a tárolt érték attól függ, hol/hogyan fut az
alkalmazás, és eltolódik a környezetek között, valamint a DST-átállásokkor.

Az `example` táblának van egy `created_at_tz` oszlopa is `timestamptz`
(`TIMESTAMP WITH TIME ZONE`) típussal, szintén egy `Instant`-ra mappelve, hogy
a helyes párosítás egymás mellett látszódjon.

## Előfeltételek

- Spring Boot 4.1.0 (Spring Framework 7 / Hibernate 7 alapokon)
- JDK 25 (a Spring Boot 4.1 minimum Java 17, de itt Java 25-öt célzunk)
- Maven
- **Docker** (a tesztek Testcontainers-szel automatikusan indítanak egy Postgrest)

## A Hibernate 7 újdonsága (fontos!)

A Spring Boot 4.1 a Hibernate 7-et hozza. Az `Instant` kezelése lényegesen
eltér a régi (Hibernate 5-ös) viselkedéstől, és ezt érdemes pontosan érteni.

**Mi változott.** A Hibernate 6 óta az `Instant` alapértelmezetten a
`TIMESTAMP_UTC` JDBC-típuskódra képződik le. Ez a típus — ha a dialektus tudja —
`timestamp with time zone` SQL-típust céloz, és csak annak hiányában esik vissza
sima `timestamp`-re. A `TIMESTAMP_UTC` kötés **UTC-naptárral** ír és olvas, tehát
sem a JVM alapértelmezett zónája, sem a `hibernate.jdbc.time_zone` nem befolyásolja.
A régi (Hibernate 5-ös) viselkedést a következővel lehet visszakapcsolni:

```properties
spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP
```

**Mit jelent ez a driftre.** A régi kötéssel (`TIMESTAMP`) az `Instant` a
JVM/JDBC-zónán keresztül konvertálódik, így egy zóna nélküli `timestamp` oszlopon
**elcsúszik**. Az alapértelmezett `TIMESTAMP_UTC` kötéssel a naiv drift **nem**
jelentkezik.

**De ettől a zóna nélküli `timestamp` + `Instant` páros NEM lesz „bátran
használható".** Az alapértelmezés csak akkor tiszta, ha az oszlop is `timestamptz`.
Zóna nélküli `timestamp` oszloppal két probléma marad:

1. **Séma-eltérés / validációs hiba.** A Hibernate az `Instant`-hoz a
   `timestamptz`-t preferálja. Ha bekapcsolod a `spring.jpa.hibernate.ddl-auto=validate`-et,
   az alkalmazás **indításkor eltörik** egy ilyen jellegű hibával:
   `found [timestamp ...], but expecting [timestamp with time zone (Types#TIMESTAMP_UTC)]`.
   Ebben a demóban ez azért nem robban, mert `ddl-auto: none` van beállítva.

2. **Driver/session-függő tárolás.** Ha mégis zóna nélküli oszlopba írsz egy
   `TIMESTAMP_UTC`-kötésű értéket, a Postgres egy implicit `timestamptz -> timestamp`
   castot végez a **session időzónája** szerint (amit a pgjdbc a kapcsolat
   létrejöttekor a JVM-zónából állít be). Vagyis a nyers tárolt érték
   kiszámíthatatlanabbá válik, és a Hibernate-en kívüli olvasók számára továbbra is
   kétértelmű.

**Következtetés.** A helyes megoldás minden esetben ugyanaz: az oszlop legyen
`timestamptz`. Akkor a Hibernate preferált típusa és a tényleges oszlop egybeesik,
a séma-validáció rendben, és mindenki egyértelmű abszolút időpontként látja.

## Tesztek futtatása

```bash
mvn test
```

### Teszt-mátrix (a property-vel és anélkül)

| Teszt                             | `preferred_instant_jdbc_type`                       | Oszlop a fókuszban              | Mit bizonyít                                                                     |
|-----------------------------------|-----------------------------------------------------|---------------------------------|----------------------------------------------------------------------------------|
| `TimestampVsTimestamptzDriftTest` | `TIMESTAMP` (régi kötés)                            | `created_at` (`timestamp`)      | **Elcsúszik** a JVM-zónák közt; a `timestamptz` oszlop stabil                    |
| `DefaultBindingTest`              | nincs (Hibernate 7 alapértelmezés, `TIMESTAMP_UTC`) | `created_at_tz` (`timestamptz`) | A helyes párosítás **nem csúszik el**; a zóna nélküli oszlopot csak megfigyeljük |
| `JdbcTimeZoneUtcFixTest`          | `TIMESTAMP` (régi kötés) + `jdbc.time_zone=UTC`     | `created_at` (`timestamp`)      | A config-szintű javítás **megszünteti** a driftet a zóna nélküli oszlopon        |

### Mit bizonyítanak az egyes tesztek

**`TimestampVsTimestamptzDriftTest`** — a régi `Instant`-kötés van bekapcsolva
(`preferred_instant_jdbc_type=TIMESTAMP`), hogy a drift Hibernate 7 alatt is
reprodukálódjon. Az egyik „instance" akkor írja a sort, amikor a JVM
`America/New_York` zónában van, a másik pedig akkor olvassa vissza, amikor
`Asia/Tokyo` zónában:

```
Original Instant            : 2024-01-15T10:00:00Z
Stored `timestamp`  (raw)   : 2024-01-15 05:00:00   <- New York-i faliórás érték (10:00Z -> 05:00)
Stored `timestamptz` (UTC)  : 2024-01-15 10:00:00   <- a valódi UTC instant megmarad
Read back `created_at`      : 2024-01-14T20:00:00Z  <- ELCSÚSZOTT
Read back `created_at_tz`   : 2024-01-15T10:00:00Z  <- helyes
```

A `timestamp` érték a New York↔Tokió eltolódással csúszik el; a `timestamptz`
érték stabil marad.

**`DefaultBindingTest`** — nincs beállítva a `preferred_instant_jdbc_type`, tehát a
Hibernate 7 alapértelmezett `TIMESTAMP_UTC` kötése fut. A teszt azt bizonyítja, hogy
a **helyes** párosításnál (`timestamptz` oszlop) az `Instant` a JVM-zóna váltása
ellenére **nem csúszik el**. A zóna nélküli oszlopot itt csak kiírjuk
(megfigyelésként), mert annak tárolt értéke — a fent leírt implicit cast miatt —
driver/session-függő, és éles `ddl-auto: validate` mellett eleve séma-hibát adna.

**`JdbcTimeZoneUtcFixTest`** — ugyanaz mint az első teszt (régi kötéssel), de
beállított `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`-vel. Így a zóna
nélküli `timestamp` oszlop az UTC faliórás értéket tárolja (`10:00`), és többé nem
csúszik el, pedig az oszlop típusa változatlan.


## A két javítás

1. **Séma-szinten (ajánlott):** legyen az oszlop `timestamptz`. Szemantikailag
   ez a helyes párja az `Instant`-nak; nincs más, amire figyelni kell.

   ```yaml
   - column:
       name: created_at
       type: timestamp with time zone
   ```

2. **Konfiguráció-szinten:** maradhat a `timestamp without time zone`, de rögzítsd
   a JDBC-zónát:

   ```yaml
   spring:
     jpa:
       properties:
         hibernate:
           jdbc:
             time_zone: UTC
   ```

   (Vedd ki a kommentből a blokkot a `src/main/resources/application.yml`-ben,
   hogy magára az alkalmazásra is érvényes legyen.)

3. **A Hibernate 7 alapértelmezés meghagyása:** ne állítsd be a
   `preferred_instant_jdbc_type=TIMESTAMP`-ot. Ekkor az `Instant` a `TIMESTAMP_UTC`
   kötést használja, és a naiv drift eleve nem jelentkezik. Ez azonban csak akkor
   igazán tiszta, ha az oszlop is `timestamptz` (különben séma-eltérés marad az
   `Instant` preferált típusa és a tényleges oszloptípus közt). Vagyis ez a
   lehetőség a gyakorlatban visszavezet az 1. ponthoz.
