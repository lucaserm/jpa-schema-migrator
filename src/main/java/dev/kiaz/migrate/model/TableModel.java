package dev.kiaz.migrate.model;

import java.util.List;

public record TableModel(String name, List<ColumnModel> columns) {}
