package io.akka.teable.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.teable.application.TableEntity;
import io.akka.teable.application.TableState;
import io.akka.teable.domain.CellValue;
import io.akka.teable.domain.FieldDef;
import io.akka.teable.domain.ValueType;

import akka.javasdk.CommandException;
import akka.javasdk.http.HttpException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** The port's own surface: define fields, write cells, read the table and the order it computes in. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/table")
public class TableEndpoint extends AbstractHttpEndpoint {

  public record FieldRequest(
      String id, String name, String kind, String valueType, String expression) {}

  public record RecordRequest(String recordId, Map<String, CellValue> cells) {}

  public record CellRequest(String fieldId, CellValue value) {}

  private final ComponentClient componentClient;

  public TableEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/{tableId}/field")
  public TableState defineField(String tableId, FieldRequest request) {
    FieldDef field;
    if ("formula".equalsIgnoreCase(request.kind())) {
      field = FieldDef.formula(request.id(), request.name(), request.expression());
    } else {
      ValueType valueType;
      try {
        valueType =
            ValueType.valueOf(
                request.valueType() == null ? "TEXT" : request.valueType().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException unknown) {
        // An unmapped enum name would otherwise leave the endpoint as an opaque 500.
        throw HttpException.badRequest(
            "valueType must be one of NUMBER, TEXT or CHECKBOX");
      }
      field = FieldDef.value(request.id(), request.name(), valueType);
    }
    return refusalAsBadRequest(
        () ->
            componentClient
                .forEventSourcedEntity(tableId)
                .method(TableEntity::defineField)
                .invoke(new TableEntity.DefineField(field)));
  }

  /**
   * A refusal from the entity is the caller's mistake, not the service's: a cycle, a reference to
   * a field that is not there, or a formula that cannot be evaluated over the records already
   * stored. Left uncaught these surface as whatever the default mapping makes of them, with the
   * reason buried.
   */
  private static TableState refusalAsBadRequest(Supplier<TableState> call) {
    try {
      return call.get();
    } catch (CommandException refused) {
      throw HttpException.badRequest(refused.getMessage());
    }
  }

  @Post("/{tableId}/record")
  public TableState upsertRecord(String tableId, RecordRequest request) {
    return refusalAsBadRequest(
        () ->
            componentClient
                .forEventSourcedEntity(tableId)
                .method(TableEntity::upsertRecord)
                .invoke(new TableEntity.UpsertRecord(request.recordId(), request.cells())));
  }

  @Put("/{tableId}/record/{recordId}/cell")
  public TableState setCell(String tableId, String recordId, CellRequest request) {
    return refusalAsBadRequest(
        () ->
            componentClient
                .forEventSourcedEntity(tableId)
                .method(TableEntity::setCell)
                .invoke(new TableEntity.SetCell(recordId, request.fieldId(), request.value())));
  }

  @Get("/{tableId}")
  public TableState read(String tableId) {
    return componentClient.forEventSourcedEntity(tableId).method(TableEntity::read).invoke();
  }

  /**
   * The record ids to return are read from the query string explicitly. A method parameter that
   * is not in the path is not bound to anything, and a request that forgot to read it here would
   * compile and pass every ComponentClient-based test.
   */
  @Get("/{tableId}/record")
  public List<Map<String, CellValue>> records(String tableId) {
    var state =
        componentClient.forEventSourcedEntity(tableId).method(TableEntity::read).invoke();
    var wanted = requestContext().queryParams().getAll("recordId");
    return state.records().entrySet().stream()
        .filter(entry -> wanted.isEmpty() || wanted.contains(entry.getKey()))
        .map(Map.Entry::getValue)
        .toList();
  }

  @Get("/{tableId}/order")
  public TableEntity.Order order(String tableId) {
    return componentClient
        .forEventSourcedEntity(tableId)
        .method(TableEntity::computationOrder)
        .invoke();
  }
}
