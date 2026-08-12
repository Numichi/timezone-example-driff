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
`TIMESTAMP_UTC` JDBC-típuskódra képződik le, amit a Hibernate **UTC-naptárral** ír
és olvas — tehát sem a JVM alapértelmezett zónája, sem a `hibernate.jdbc.time_zone`
nem befolyásolja. A régi (Hibernate 5-ös) viselkedést a következővel lehet
visszakapcsolni:

```properties
spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP
```

**Mit jelent ez a driftre.** A régi kötéssel (`TIMESTAMP`) az `Instant` a
JVM/JDBC-zónán keresztül konvertálódik, így egy zóna nélküli `timestamp` oszlopon
**elcsúszik**. Az alapértelmezett `TIMESTAMP_UTC` kötéssel a naiv drift **nem**
jelentkezik.

**PostgreSQL-en a `timestamp` + `Instant` páros az alapértelmezéssel valójában
működik.** A PostgreSQL Hibernate-dialektusa a `TIMESTAMP_UTC`-t DDL/validáció
szinten **sima `timestamp`-re** képezi (nem `timestamptz`-re), ezért:

- `spring.jpa.hibernate.ddl-auto=validate` **átmegy** — nincs séma-eltérés az
  `Instant` mező és a `timestamp` oszlop között (ezt élőben is kipróbáltuk).
- A round-trip **nem csúszik el**, mert a Hibernate írásnál és olvasásnál is UTC-t
  használ (a `DefaultBindingTest` `drifted? false`-t ír ki a zóna nélküli oszlopra).

Fontos: a Hibernate 6 migrációs útmutató szerint a `TIMESTAMP_UTC` váltás **egyes**
adatbázisokon okozhat séma-validációs hibát (ahol a típus `timestamp with time zone`-ra
képződik) — de a PostgreSQL nem tartozik ezek közé.

**Akkor mégis mi a maradék kockázat?** Két dolog:

1. **Szemantikai kétértelműség kifelé.** A zóna nélküli oszlop nem hordozza, hogy a
   tárolt érték UTC. Amíg csak a Hibernate ír/olvas, konzisztens; de bármely más
   olvasó (natív SQL, riporting eszköz, másik szolgáltatás, DB-oldali
   `CURRENT_TIMESTAMP` default vagy összehasonlítás) a session-zónája szerint
   értelmezheti — itt visszajön a kétértelműség, csak a DB rétegében.

2. **Hordozhatóság és a régi kötés.** Más adatbázison a `TIMESTAMP_UTC` `timestamptz`-ra
   képződhet (séma-validációs eltérés), illetve ha bárki bekapcsolja a
   `preferred_instant_jdbc_type=TIMESTAMP` kötést, a drift visszatér a zóna nélküli
   oszlopon.

**Következtetés.** PostgreSQL + Hibernate 7 alapértelmezéssel a `timestamp` + `Instant`
technikailag rendben van (validate átmegy, nincs drift). Ha viszont egyértelmű,
hordozható és a DB-n kívüli olvasók számára sem félreérthető sémát akarsz, a `timestamptz`
a tiszta választás — és ez teszi feleslegessé a régi kötéssel járó kockázatokat is.

## Tesztek futtatása

```bash
mvn test
```

### Teszt-mátrix (a property-vel és anélkül)

| Teszt | `preferred_instant_jdbc_type` | Oszlop a fókuszban | Mit bizonyít |
|-------|-------------------------------|--------------------|--------------|
| `TimestampVsTimestamptzDriftTest` | `TIMESTAMP` (régi kötés) | `created_at` (`timestamp`) | **Elcsúszik** a JVM-zónák közt; a `timestamptz` oszlop stabil |
| `DefaultBindingTest` | nincs (Hibernate 7 alapértelmezés, `TIMESTAMP_UTC`) | `created_at_tz` (`timestamptz`) | A helyes párosítás **nem csúszik el**; a zóna nélküli oszlopot csak megfigyeljük |
| `JdbcTimeZoneUtcFixTest` | `TIMESTAMP` (régi kötés) + `jdbc.time_zone=UTC` | `created_at` (`timestamp`) | A config-szintű javítás **megszünteti** a driftet a zóna nélküli oszlopon |

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
Hibernate 7 alapértelmezett `TIMESTAMP_UTC` kötése fut. A teszt bizonyítja, hogy a
`timestamptz` oszlopon az `Instant` a JVM-zóna váltása ellenére **nem csúszik el**, és
kiír egy `drifted?` verdiktet a zóna nélküli `timestamp` oszlopra is. PostgreSQL-en ez
utóbbi is `false` (nincs drift), és a `ddl-auto: validate` is átmegy — a zóna nélküli
`timestamp` oszlop tehát az alapértelmezéssel működik; a `timestamptz` viszont
egyértelműbb és hordozhatóbb (lásd fent).

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

## Spring Boot 4 buktató: modularizált auto-config

A Spring Boot 4 szétbontotta a korábban monolitikus `spring-boot-autoconfigure`-t
kisebb, fókuszált modulokra. Emiatt bizonyos auto-configuration már **nem aktiválódik
pusztán attól, hogy a megfelelő library a classpathon van** — külön modult is fel kell
venni. A Liquibase esetén a `LiquibaseAutoConfiguration` a `spring-boot-liquibase`
modulban él, ezért a `pom.xml` tartalmazza:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-liquibase</artifactId>
</dependency>
```

E nélkül a `liquibase-core` önmagában a classpathon van ugyan, de a Liquibase induláskor
nem fut le, a tábla nem jön létre, és minden lekérdezés `relation "example" does not exist`
hibával bukik. (Ugyanez a minta más területeknél is előfordulhat Boot 4-ben, pl. H2 konzol.)

## Megjegyzés a build-ellenőrzésről

Ezt a verziófrissítést (Spring Boot 4.1.0 + Java 25) az elkészítés környezetében
nem tudtam ténylegesen lefordítani/futtatni (nem volt Java 25, és a Maven Central
sem volt elérhető), úgyhogy futtasd le lokálisan a `mvn test`-et. Ha bármelyik
assert eltér a várttól, először a Hibernate 7-es `Instant`-kötést ellenőrizd a
fentiek szerint.

## Futtatás valódi Postgres ellen Testcontainers helyett

```bash
docker compose up -d
# majd indítsd az alkalmazást; a datasource előre be van állítva az application.yml-ben
mvn spring-boot:run
```
