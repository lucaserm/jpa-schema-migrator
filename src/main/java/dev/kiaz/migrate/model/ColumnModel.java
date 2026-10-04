package dev.kiaz.migrate.model;

public record ColumnModel(String name, String sqlType, boolean nullable, boolean primaryKey) {}
