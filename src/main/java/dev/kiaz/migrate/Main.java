package dev.kiaz.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.kiaz.migrate.model.ColumnModel;
import dev.kiaz.migrate.model.SchemaModel;
import dev.kiaz.migrate.model.TableModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class Main {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Main() {}

    public static void main(String[] args) throws Exception {
        args = normalizeArgs(args);
        if (args.length == 0 || args[0].equals("help")) {
            help();
            return;
        }
        Options options = Options.parse(args);
        SchemaModel current = readEntities(options.entities());
        Path snapshot = Path.of(options.snapshot());

        switch (args[0]) {
            case "baseline" -> {
                Path outputDir = Path.of(options.outputDir());
                Files.createDirectories(outputDir);

                String fileName = "V" + options.version() + "__" + options.description() + ".sql";
                Path migration = outputDir.resolve(fileName);

                if (Files.exists(migration)) {
                    throw new IllegalArgumentException("Migration already exists: " + migration);
                }

                List<String> statements = current.tables().stream()
                        .map(Main::createTable)
                        .toList();

                Files.writeString(migration,
                        String.join(System.lineSeparator() + System.lineSeparator(), statements)
                                + System.lineSeparator());

                Files.writeString(snapshot, JSON.writeValueAsString(current) + System.lineSeparator());

                System.out.println("Initial migration written to " + migration);
                System.out.println("Baseline snapshot written to " + snapshot);
            }
            case "generate" -> generate(options, current, snapshot);
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static String[] normalizeArgs(String[] args) {
        if (args.length != 1 || !args[0].matches(".*\\s+.*")) return args;

        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < args[0].length(); i++) {
            char c = args[0].charAt(i);

            if (quote != 0) {
                if (c == quote) quote = 0;
                else token.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (!token.isEmpty()) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(c);
            }
        }

        if (quote != 0) {
            throw new IllegalArgumentException("Unclosed quote in command arguments");
        }
        if (!token.isEmpty()) tokens.add(token.toString());

        return tokens.toArray(String[]::new);
    }

    private static void generate(Options options, SchemaModel current, Path snapshotPath) throws Exception {
        if (!Files.exists(snapshotPath)) {
            throw new IllegalArgumentException("Snapshot not found: " + snapshotPath + ". Run baseline first.");
        }
        SchemaModel previous = JSON.readValue(snapshotPath.toFile(), SchemaModel.class);
        List<String> statements = new ArrayList<>();
        for (TableModel table : current.tables()) {
            TableModel oldTable = previous.tables().stream().filter(t -> t.name().equals(table.name())).findFirst().orElse(null);
            if (oldTable == null) {
                statements.add(createTable(table));
                continue;
            }
            for (ColumnModel column : table.columns()) {
                ColumnModel oldColumn = oldTable.columns().stream().filter(c -> c.name().equals(column.name())).findFirst().orElse(null);
                if (oldColumn == null) {
                    if (!column.nullable()) {
                        throw new IllegalArgumentException("Cannot add required column " + table.name() + "." + column.name()
                                + " without a default/backfill. Add it nullable first or implement an explicit migration.");
                    }
                    statements.add("ALTER TABLE " + q(table.name()) + " ADD COLUMN " + columnDefinition(column) + ";");
                } else if (!oldColumn.equals(column)) {
                    throw new IllegalArgumentException("Column change detected for " + table.name() + "." + column.name()
                            + " (" + oldColumn + " -> " + column + "). Automatic type/nullability changes are not supported yet.");
                }
            }
            for (ColumnModel oldColumn : oldTable.columns()) {
                boolean remains = table.columns().stream().anyMatch(c -> c.name().equals(oldColumn.name()));
                if (!remains) throw new IllegalArgumentException("Column removal detected for " + table.name() + "." + oldColumn.name()
                        + ". Destructive migrations require an explicit migration.");
            }
        }
        for (TableModel oldTable : previous.tables()) {
            if (current.tables().stream().noneMatch(t -> t.name().equals(oldTable.name()))) {
                throw new IllegalArgumentException("Table removal detected: " + oldTable.name() + ". Destructive migrations require an explicit migration.");
            }
        }
        if (statements.isEmpty()) {
            System.out.println("No schema changes detected.");
            return;
        }
        Path outputDir = Path.of(options.outputDir());
        Files.createDirectories(outputDir);
        String fileName = "V" + options.version() + "__" + options.description() + ".sql";
        Path migration = outputDir.resolve(fileName);
        if (Files.exists(migration)) throw new IllegalArgumentException("Migration already exists: " + migration);
        Files.writeString(migration, String.join(System.lineSeparator() + System.lineSeparator(), statements) + System.lineSeparator());
        Path nextSnapshot = snapshotPath.resolveSibling(snapshotPath.getFileName().toString().replaceFirst("(\\.[^.]+)?$", ".next.json"));
        Files.writeString(nextSnapshot, JSON.writeValueAsString(current) + System.lineSeparator());
        System.out.println("Migration written to " + migration);
        System.out.println("Next snapshot written to " + nextSnapshot + " (promote it after reviewing the migration).");
    }

    private static SchemaModel readEntities(List<String> names) throws Exception {
        List<TableModel> tables = new ArrayList<>();
        for (String name : names) {
            Class<?> type = Class.forName(name);
            if (!type.isAnnotationPresent(Entity.class)) throw new IllegalArgumentException(name + " is missing @Entity");
            Table tableAnnotation = type.getAnnotation(Table.class);
            String tableName = tableAnnotation != null && !tableAnnotation.name().isBlank()
                    ? tableAnnotation.name() : type.getSimpleName();
            List<Field> fields = allFields(type).stream().filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers())).toList();
            List<ColumnModel> columns = new ArrayList<>();
            boolean hasId = false;
            for (Field field : fields) {
                boolean id = field.isAnnotationPresent(Id.class);
                hasId |= id;
                Column annotation = field.getAnnotation(Column.class);
                String columnName = annotation != null && !annotation.name().isBlank() ? annotation.name() : field.getName();
                String sqlType = sqlType(field.getType(), annotation == null ? 255 : annotation.length());
                boolean nullable = !id && (annotation == null || annotation.nullable());
                columns.add(new ColumnModel(columnName, sqlType, nullable, id));
            }
            if (!hasId) throw new IllegalArgumentException(name + " must have an @Id field");
            columns.sort(Comparator.comparing(ColumnModel::name));
            tables.add(new TableModel(tableName, columns));
        }
        tables.sort(Comparator.comparing(TableModel::name));
        return new SchemaModel(tables);
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
        }
        return fields;
    }

    private static String sqlType(Class<?> type, int length) {
        if (type == String.class) return "VARCHAR(" + length + ")";
        if (type == long.class || type == Long.class) return "BIGINT";
        if (type == int.class || type == Integer.class) return "INTEGER";
        if (type == boolean.class || type == Boolean.class) return "BOOLEAN";
        if (type == LocalDate.class) return "DATE";
        if (type == LocalDateTime.class) return "TIMESTAMP";
        if (type == BigDecimal.class) return "NUMERIC";
        throw new IllegalArgumentException("Unsupported field type: " + type.getName());
    }

    private static String createTable(TableModel table) {
        List<String> definitions = new ArrayList<>();
        for (ColumnModel column : table.columns()) definitions.add("    " + columnDefinition(column));
        List<String> ids = table.columns().stream().filter(ColumnModel::primaryKey).map(c -> q(c.name())).toList();
        definitions.add("    PRIMARY KEY (" + String.join(", ", ids) + ")");
        return "CREATE TABLE " + q(table.name()) + " (\n" + String.join(",\n", definitions) + "\n);";
    }

    private static String columnDefinition(ColumnModel column) {
        return q(column.name()) + " " + column.sqlType() + (column.nullable() ? "" : " NOT NULL");
    }

    private static String q(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
        return "\"" + identifier + "\"";
    }

    private static void help() {
        System.out.println("Annotate Migrate prototype\n"
                + "  baseline --snapshot schema.json --entities package.Entity[,package.Other]\n"
                + "  generate --snapshot schema.json --output-dir db/migration --version 2 --description add_email --entities package.Entity");
    }

    private record Options(String snapshot, String outputDir, String version, String description, List<String> entities) {
        static Options parse(String[] args) {
            String snapshot = "schema.json", outputDir = "src/main/resources/db/migration", version = "1", description = "schema_update";
            List<String> entities = List.of();
            for (int i = 1; i < args.length; i++) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
                String value = args[++i];
                switch (args[i - 1]) {
                    case "--snapshot" -> snapshot = value;
                    case "--output-dir" -> outputDir = value;
                    case "--version" -> version = value;
                    case "--description" -> description = value;
                    case "--entities" -> entities = Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i - 1]);
                }
            }
            if (entities.isEmpty()) throw new IllegalArgumentException("Provide at least one --entities class name");
            if (!version.matches("[0-9]+(?:\\.[0-9]+)*")) throw new IllegalArgumentException("Version must contain digits and dots only");
            if (!description.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Description may contain only letters, digits, and underscores");
            return new Options(snapshot, outputDir, version, description, entities);
        }
    }
}
