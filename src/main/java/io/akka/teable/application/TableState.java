package io.akka.teable.application;

import io.akka.teable.domain.CellValue;
import io.akka.teable.domain.FieldDef;
import io.akka.teable.domain.Table;

import java.util.List;
import java.util.Map;

/**
 * The entity's persisted state, which is the domain Table's own shape. Kept as its own record
 * rather than reusing Table so that the domain type stays free of anything the runtime needs.
 */
public record TableState(String id, List<FieldDef> fields, Map<String, Map<String, CellValue>> records) {

  public static TableState empty(String id) {
    return new TableState(id, List.of(), Map.of());
  }

  public Table toTable() {
    return new Table(id, fields, records);
  }
}
