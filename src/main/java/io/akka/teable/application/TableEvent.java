package io.akka.teable.application;

import akka.javasdk.annotations.TypeName;
import io.akka.teable.domain.CellValue;
import io.akka.teable.domain.FieldDef;

import java.util.Map;

/**
 * What the table records about itself.
 *
 * <p>Each event carries the cells as they are *after* recomputation, so replaying the journal
 * never re-evaluates a formula and a recomputation is one atomic transition rather than an
 * edit followed by a fan of writes. The sealed interface is the entity's own Event type, which
 * is the position where the runtime's polymorphic JSON handling resolves; no variant nests
 * another sealed interface as a field.
 */
public sealed interface TableEvent {

  @TypeName("field-defined")
  record FieldDefined(FieldDef field, Map<String, Map<String, CellValue>> records)
      implements TableEvent {}

  @TypeName("record-upserted")
  record RecordUpserted(String recordId, Map<String, CellValue> cells) implements TableEvent {}
}
