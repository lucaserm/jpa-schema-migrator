# Annotate Migrate

An early prototype for the idea: use Jakarta Persistence annotations as the schema definition, compare them with a committed snapshot, and generate a **reviewable PostgreSQL SQL migration**. It does not connect to or modify a database.

## Current scope

- `@Entity`, `@Table`, `@Id`, and `@Column`
- Simple scalar fields: `String`, `int`/`Integer`, `long`/`Long`, `boolean`/`Boolean`, `LocalDate`, `LocalDateTime`, and `BigDecimal`
- PostgreSQL `CREATE TABLE` and `ALTER TABLE ... ADD COLUMN`
- Explicit entity class names (entity discovery and relationships are not implemented yet)
- Added columns are nullable by default; use `@Column(nullable = false)` for required columns

This prototype intentionally errors on unsupported changes instead of guessing. In particular, it does not yet infer renames, drops, indexes, foreign keys, or data backfills.

## Try it

Requires Java 17+ and Maven.

```sh
mvn -q compile exec:java -Dexec.args='baseline --snapshot schema.json --entities dev.kiaz.migrate.example.User'
```

Edit the entity, then generate a migration against the prior snapshot:

```sh
mvn -q compile exec:java -Dexec.args='generate --snapshot schema.json --output-dir src/main/resources/db/migration --version 2 --description add_user_field --entities dev.kiaz.migrate.example.User'
```

The command writes a migration file and a `.next.json` snapshot beside the old snapshot. Review the SQL first. After the migration is accepted/applied in your workflow, replace the old snapshot with the next snapshot:

```sh
mv schema.next.json schema.json
```

Example output: `V2__add_user_field.sql`.

## Example entity

```java
@Entity
@Table(name = "users")
public class User {
    @Id
    private Long id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;
}
```

## Next steps

1. Add generated identity handling for `@GeneratedValue`.
2. Add indexes and relationships.
3. Add explicit rename hints and destructive-change confirmation.
4. Support one more dialect after the schema model stabilizes.
5. Integrate Flyway for applying already-reviewed files.
