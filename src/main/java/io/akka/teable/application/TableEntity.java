package io.akka.teable.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.teable.domain.CellValue;
import io.akka.teable.domain.FieldDef;
import io.akka.teable.domain.Table;
import io.akka.teable.domain.FormulaException;
import io.akka.teable.domain.TableException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One table. Its fields and records are one aggregate because a formula's value depends on
 * every field the table has, so a definition that closes a cycle has to be refused against the
 * whole field list rather than against one field at a time.
 */
@Component(id = "table")
public class TableEntity extends EventSourcedEntity<TableState, TableEvent> {

  public record DefineField(FieldDef field) {}

  public record UpsertRecord(String recordId, Map<String, CellValue> cells) {}

  public record SetCell(String recordId, String fieldId, CellValue value) {}

  public record Order(List<String> fieldIds) {}

  private final String tableId;

  public TableEntity(akka.javasdk.eventsourcedentity.EventSourcedEntityContext context) {
    this.tableId = context.entityId();
  }

  @Override
  public TableState emptyState() {
    return TableState.empty(tableId);
  }

  public Effect<TableState> defineField(DefineField command) {
    Table next;
    try {
      next = currentState().toTable().withField(command.field());
    } catch (TableException | FormulaException refused) {
      // A parse error and a bad identifier come out of the formula rather than the table, and
      // an uncaught RuntimeException here is not an error the caller can read -- it reaches
      // them as an unexpected failure with a correlation id and no reason.
      return effects().error(refused.getMessage());
    }
    return effects()
        .persist(new TableEvent.FieldDefined(command.field(), next.records()))
        .thenReply(state -> state);
  }

  public Effect<TableState> upsertRecord(UpsertRecord command) {
    Table next;
    try {
      next = currentState().toTable().withRecord(command.recordId(), command.cells());
    } catch (TableException | FormulaException refused) {
      return effects().error(refused.getMessage());
    }
    return effects()
        .persist(new TableEvent.RecordUpserted(command.recordId(), next.record(command.recordId())))
        .thenReply(state -> state);
  }

  public Effect<TableState> setCell(SetCell command) {
    Table next;
    try {
      next = currentState().toTable().withCell(command.recordId(), command.fieldId(), command.value());
    } catch (TableException | FormulaException refused) {
      return effects().error(refused.getMessage());
    }
    return effects()
        .persist(new TableEvent.RecordUpserted(command.recordId(), next.record(command.recordId())))
        .thenReply(state -> state);
  }

  public ReadOnlyEffect<TableState> read() {
    return effects().reply(currentState());
  }

  public ReadOnlyEffect<Order> computationOrder() {
    return effects().reply(new Order(currentState().toTable().computationOrder()));
  }

  @Override
  public TableState applyEvent(TableEvent event) {
    return switch (event) {
      case TableEvent.FieldDefined defined -> {
        // Redefining a field keeps its position: the declaration index is what breaks ties
        // in the computation order, so appending instead would silently reorder the table.
        var fields = new java.util.ArrayList<FieldDef>();
        var replaced = false;
        for (var existing : currentState().fields()) {
          if (existing.id().equals(defined.field().id())) {
            fields.add(defined.field());
            replaced = true;
          } else {
            fields.add(existing);
          }
        }
        if (!replaced) {
          fields.add(defined.field());
        }
        yield new TableState(currentState().id(), List.copyOf(fields), defined.records());
      }
      case TableEvent.RecordUpserted upserted -> {
        var records = new LinkedHashMap<>(currentState().records());
        records.put(upserted.recordId(), upserted.cells());
        yield new TableState(
            currentState().id(),
            currentState().fields(),
            java.util.Collections.unmodifiableMap(records));
      }
    };
  }
}
