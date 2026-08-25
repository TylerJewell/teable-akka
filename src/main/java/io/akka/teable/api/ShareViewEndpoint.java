package io.akka.teable.api;

import akka.NotUsed;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import io.akka.teable.application.TableEntity;
import io.akka.teable.application.TableState;
import io.akka.teable.domain.CellValue;
import io.akka.teable.domain.FieldDef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The routes teable's own share view calls, answered from this port's table entity.
 *
 * <p>The interface itself is the original's, vendored under `webapp/` and unchanged except
 * for where it gets its data (RENDERING.md R3). These are the ten routes that carry this
 * slice's state; the shapes were captured from the running original rather than read off
 * its source, so an omitted field shows up as a screen that does not render rather than as
 * a difference nobody notices.
 *
 * <p>The share id is the table id. The original keeps a separate share registry with its own
 * access rules, none of which is in this slice.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/share")
public class ShareViewEndpoint extends AbstractHttpEndpoint {

  private static final Duration TICK = Duration.ofMillis(100);

  private final ComponentClient componentClient;

  public ShareViewEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/{shareId}/view")
  public Map<String, Object> shareView(String shareId) {
    var state = read(shareId);
    var view = viewOf(shareId, state);
    return Map.of(
        "shareMeta", Map.of("includeRecords", true),
        "shareId", shareId,
        "tableId", shareId,
        "viewId", viewId(shareId),
        "view", view,
        "fields", fieldsOf(state));
  }

  @Get("/{shareId}/view/row-count")
  public Map<String, Object> rowCount(String shareId) {
    return Map.of("rowCount", read(shareId).records().size());
  }

  @Get("/{shareId}/view/aggregations")
  public Map<String, Object> aggregations(String shareId) {
    return Map.of("aggregations", List.of());
  }

  @Get("/{shareId}/socket/field/doc-ids")
  public Map<String, Object> fieldDocIds(String shareId) {
    return Map.of("ids", read(shareId).fields().stream().map(FieldDef::id).toList());
  }

  @Get("/{shareId}/socket/field/snapshot-bulk")
  public List<Map<String, Object>> fieldSnapshots(String shareId) {
    var wanted = requestContext().queryParams().getAll("ids[]");
    var state = read(shareId);
    var out = new ArrayList<Map<String, Object>>();
    for (var field : fieldsOf(state)) {
      var id = (String) field.get("id");
      if (!wanted.isEmpty() && !wanted.contains(id)) {
        continue;
      }
      out.add(Map.of("id", id, "v", 1, "type", "json0", "data", field));
    }
    return out;
  }

  @Get("/{shareId}/socket/view/doc-ids")
  public Map<String, Object> viewDocIds(String shareId) {
    return Map.of("ids", List.of(viewId(shareId)));
  }

  @Get("/{shareId}/socket/view/snapshot-bulk")
  public List<Map<String, Object>> viewSnapshots(String shareId) {
    var view = viewOf(shareId, read(shareId));
    return List.of(Map.of("id", viewId(shareId), "v", 1, "type", "json0", "data", view));
  }

  public record DocIdsRequest(Integer take, Integer skip, String viewId) {}

  @Post("/{shareId}/socket/record/doc-ids")
  public Map<String, Object> recordDocIds(String shareId, DocIdsRequest request) {
    var ids = new ArrayList<>(read(shareId).records().keySet());
    return Map.of(
        "ids", ids,
        "extra", Map.of("groupPoints", List.of()));
  }

  public record SnapshotBulkRequest(List<String> ids) {}

  @Post("/{shareId}/socket/record/snapshot-bulk")
  public List<Map<String, Object>> recordSnapshots(String shareId, SnapshotBulkRequest request) {
    var state = read(shareId);
    var wanted = request.ids() == null ? List.<String>of() : request.ids();
    var out = new ArrayList<Map<String, Object>>();
    var autoNumber = 0;
    for (var entry : state.records().entrySet()) {
      autoNumber++;
      if (!wanted.isEmpty() && !wanted.contains(entry.getKey())) {
        continue;
      }
      out.add(
          Map.of(
              "id", entry.getKey(),
              "v", 1,
              "type", "json0",
              "data", recordJson(state, entry.getKey(), entry.getValue(), autoNumber)));
    }
    return out;
  }

  /**
   * The stream that replaces the original's socket. Every frame is the whole table, so a
   * client that reconnects converges on its first frame without a replay position.
   */
  @Get("/{shareId}/stream")
  public HttpResponse stream(String shareId) {
    Source<TableState, NotUsed> source =
        Source.tick(Duration.ZERO, TICK, "")
            .map(ignored -> read(shareId))
            .statefulMapConcat(
                () -> {
                  var previous = new TableState[1];
                  return state -> {
                    if (state.equals(previous[0])) {
                      return List.<TableState>of();
                    }
                    previous[0] = state;
                    return List.of(state);
                  };
                })
            .mapMaterializedValue(ignored -> NotUsed.getInstance());
    return HttpResponses.serverSentEvents(source);
  }

  private TableState read(String shareId) {
    return componentClient.forEventSourcedEntity(shareId).method(TableEntity::read).invoke();
  }

  private static String viewId(String shareId) {
    return "viw" + shareId;
  }

  private Map<String, Object> viewOf(String shareId, TableState state) {
    var columnMeta = new LinkedHashMap<String, Object>();
    var order = 0;
    for (var field : state.fields()) {
      columnMeta.put(field.id(), Map.of("order", order++));
    }
    var view = new LinkedHashMap<String, Object>();
    view.put("id", viewId(shareId));
    view.put("name", "Grid view");
    view.put("type", "grid");
    view.put("order", 0);
    view.put("shareId", shareId);
    view.put("enableShare", true);
    view.put("shareMeta", Map.of("includeRecords", true));
    view.put("columnMeta", columnMeta);
    view.put("options", Map.of());
    view.put("filter", null);
    view.put("sort", null);
    view.put("group", null);
    return view;
  }

  private static List<Map<String, Object>> fieldsOf(TableState state) {
    var out = new ArrayList<Map<String, Object>>();
    for (var field : state.fields()) {
      var json = new LinkedHashMap<String, Object>();
      json.put("id", field.id());
      json.put("name", field.name());
      json.put("dbFieldName", field.name());
      json.put("unique", false);
      json.put("notNull", false);
      json.put("isPrimary", out.isEmpty());
      json.put("isComputed", field.isFormula());
      json.put("isMultipleCellValue", false);
      json.put("description", null);
      if (field.isFormula()) {
        json.put("type", "formula");
        json.put("options", Map.of("expression", field.expression()));
        var textual = field.expression().toUpperCase().contains("CONCATENATE");
        json.put("cellValueType", textual ? "string" : "number");
        json.put("dbFieldType", textual ? "TEXT" : "REAL");
      } else {
        switch (field.valueType()) {
          case NUMBER -> {
            json.put("type", "number");
            json.put(
                "options", Map.of("formatting", Map.of("type", "decimal", "precision", 2)));
            json.put("cellValueType", "number");
            json.put("dbFieldType", "REAL");
          }
          case CHECKBOX -> {
            json.put("type", "checkbox");
            json.put("options", Map.of());
            json.put("cellValueType", "boolean");
            json.put("dbFieldType", "BOOLEAN");
          }
          default -> {
            json.put("type", "singleLineText");
            json.put("options", Map.of("showAs", null));
            json.put("cellValueType", "string");
            json.put("dbFieldType", "TEXT");
          }
        }
      }
      out.add(json);
    }
    return out;
  }

  private static Map<String, Object> recordJson(
      TableState state, String recordId, Map<String, CellValue> cells, int autoNumber) {
    var fields = new LinkedHashMap<String, Object>();
    for (var field : state.fields()) {
      var value = cells.get(field.id());
      if (value == null || value.isBlank()) {
        continue;
      }
      fields.put(
          field.id(),
          switch (value.kind()) {
            case NUMBER -> value.number();
            case TEXT -> value.text();
            case BOOLEAN -> value.flag();
            case BLANK -> null;
          });
    }
    var primary = state.fields().isEmpty() ? null : fields.get(state.fields().get(0).id());
    var json = new LinkedHashMap<String, Object>();
    json.put("id", recordId);
    json.put("fields", fields);
    json.put("name", primary == null ? null : String.valueOf(primary));
    json.put("autoNumber", autoNumber);
    json.put("createdTime", "2026-08-24T00:00:00.000Z");
    json.put("lastModifiedTime", "2026-08-24T00:00:00.000Z");
    json.put("createdBy", "anonymous");
    json.put("lastModifiedBy", "anonymous");
    return json;
  }
}
