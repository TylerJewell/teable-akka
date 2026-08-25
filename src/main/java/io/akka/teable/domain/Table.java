package io.akka.teable.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One table: its fields in declaration order, and its records.
 *
 * <p>Every mutation returns a new Table whose formula cells are already consistent, so there is
 * no state in which a formula cell is stale. A change that cannot leave the table consistent --
 * one that would close a cycle, name a field that is not there, or fail to evaluate over a
 * record already stored -- is refused and the previous Table is what the caller keeps.
 *
 * <p>The whole table is one aggregate because a formula's dependencies span the field list, so a
 * definition that would close a cycle has to be refused against all of it at once. That bounds
 * how large a table this can hold: state and events replicate as a unit and the runtime's
 * ceiling is a megabyte, so {@link #RECORD_LIMIT} refuses growth past a size that stays under
 * it rather than letting a table quietly become unreplicable.
 */
public record Table(String id, List<FieldDef> fields, Map<String, Map<String, CellValue>> records) {

  /**
   * A cell serialises to about sixty bytes and the table is persisted whole, so this many
   * records across a dozen fields sits an order of magnitude under the replication ceiling.
   */
  public static final int RECORD_LIMIT = 2000;

  public static Table empty(String id) {
    return new Table(id, List.of(), Map.of());
  }

  /**
   * Records keep the order they were added in. An unordered copy would still hold every cell, so
   * nothing in the domain notices -- but the grid draws its rows in the order it is handed them,
   * and the original numbers its rows by insertion.
   */
  private static Map<String, Map<String, CellValue>> ordered(
      Map<String, Map<String, CellValue>> records) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(records));
  }

  public List<String> fieldIds() {
    return fields.stream().map(FieldDef::id).toList();
  }

  /** The order the fields are computed in. Ties break on declaration index, never on edge order. */
  public List<String> computationOrder() {
    return plan().order();
  }

  /**
   * Everything a recomputation needs, worked out once: the order, each formula parsed, and any
   * cycles.
   *
   * <p>Parsing is the expensive half and an expression does not change between records, so one
   * plan per pass replaces one parse per formula per record. Defining a field over a thousand
   * records used to parse every expression in the table a thousand times, and sort the graph as
   * often.
   */
  private record Plan(
      List<String> order, Map<String, Formula> formulas, List<List<String>> cycles) {}

  private Plan plan() {
    var formulas = new LinkedHashMap<String, Formula>();
    var nodes = new ArrayList<FieldOrder.Node>();
    for (var field : fields) {
      if (field.isFormula()) {
        var formula = field.formula();
        formulas.put(field.id(), formula);
        nodes.add(new FieldOrder.Node(field.id(), formula.references()));
      } else {
        nodes.add(new FieldOrder.Node(field.id(), List.of()));
      }
    }
    var sorted = FieldOrder.sort(nodes);
    return new Plan(sorted.order(), formulas, sorted.cycles());
  }

  public CellValue cell(String recordId, String fieldId) {
    return records.getOrDefault(recordId, Map.of()).getOrDefault(fieldId, CellValue.blank());
  }

  public Map<String, CellValue> record(String recordId) {
    return records.getOrDefault(recordId, Map.of());
  }

  public Table withField(FieldDef field) {
    var next = new ArrayList<FieldDef>();
    var replaced = false;
    for (var existing : fields) {
      if (existing.id().equals(field.id())) {
        next.add(field);
        replaced = true;
      } else {
        next.add(existing);
      }
    }
    if (!replaced) {
      next.add(field);
    }
    var candidate = new Table(id, List.copyOf(next), records);
    var plan = candidate.plan();
    candidate.refuseUnresolvedReferences(plan);
    candidate.refuseCycles(plan);
    return candidate.recomputeEveryRecord(plan);
  }

  public Table withRecord(String recordId, Map<String, CellValue> cells) {
    refuseGrowthPastTheReplicationCeiling(recordId);
    var next = new LinkedHashMap<>(records);
    next.put(recordId, Map.copyOf(storable(cells)));
    return new Table(id, fields, ordered(next)).recomputeRecord(recordId);
  }

  public Table withCell(String recordId, String fieldId, CellValue value) {
    refuseGrowthPastTheReplicationCeiling(recordId);
    var cells = new LinkedHashMap<>(records.getOrDefault(recordId, Map.of()));
    cells.put(fieldId, storableCell(fieldId, value));
    var next = new LinkedHashMap<>(records);
    next.put(recordId, Map.copyOf(cells));
    return new Table(id, fields, ordered(next)).recomputeRecord(recordId);
  }

  /**
   * A checkbox cell set to false is stored blank. Unchecked and never-set are one state in the
   * original, and every formula reading such a cell sees a blank rather than a false; keeping
   * the false would agree on `NOT(...)` and disagree on the cell itself.
   */
  private Map<String, CellValue> storable(Map<String, CellValue> cells) {
    var out = new LinkedHashMap<String, CellValue>();
    cells.forEach((fieldId, value) -> out.put(fieldId, storableCell(fieldId, value)));
    return out;
  }

  private CellValue storableCell(String fieldId, CellValue value) {
    if (value == null || value.kind() != CellValue.Kind.BOOLEAN || Boolean.TRUE.equals(value.flag())) {
      return value;
    }
    for (var field : fields) {
      if (field.id().equals(fieldId) && !field.isFormula()
          && field.valueType() == ValueType.CHECKBOX) {
        return CellValue.blank();
      }
    }
    return value;
  }

  private void refuseGrowthPastTheReplicationCeiling(String recordId) {
    if (!records.containsKey(recordId) && records.size() >= RECORD_LIMIT) {
      throw new TableException(
          "Table "
              + id
              + " already holds "
              + RECORD_LIMIT
              + " records, which is as many as one aggregate replicates");
    }
  }

  private void refuseUnresolvedReferences(Plan plan) {
    var known = new HashSet<>(fieldIds());
    for (var field : fields) {
      var formula = plan.formulas().get(field.id());
      if (formula == null) {
        continue;
      }
      var missing = new ArrayList<String>();
      for (var dependency : formula.references()) {
        if (!known.contains(dependency)) {
          missing.add(dependency);
        }
      }
      if (!missing.isEmpty()) {
        throw new TableException(
            "Formula field references not found: "
                + String.join(", ", missing)
                + ". These field IDs do not exist in the table.");
      }
    }
  }

  private void refuseCycles(Plan plan) {
    if (plan.cycles().isEmpty()) {
      return;
    }
    var paths = plan.cycles().stream().map(cycle -> String.join(" -> ", cycle)).toList();
    throw new TableException(
        "Formula field dependency cycle detected: " + String.join("; ", paths));
  }

  private Table recomputeEveryRecord(Plan plan) {
    var next = new LinkedHashMap<String, Map<String, CellValue>>();
    for (var entry : records.entrySet()) {
      next.put(entry.getKey(), recompute(plan, entry.getKey(), entry.getValue()));
    }
    return new Table(id, fields, ordered(next));
  }

  private Table recomputeRecord(String recordId) {
    var plan = plan();
    var next = new LinkedHashMap<>(records);
    next.put(recordId, recompute(plan, recordId, records.getOrDefault(recordId, Map.of())));
    return new Table(id, fields, ordered(next));
  }

  /**
   * One pass in computation order. Each formula reads the map being built, so a field placed
   * later sees what an earlier one produced and a chain of any depth settles without a second
   * pass.
   */
  private Map<String, CellValue> recompute(
      Plan plan, String recordId, Map<String, CellValue> cells) {
    var working = new LinkedHashMap<>(cells);
    for (var fieldId : plan.order()) {
      var formula = plan.formulas().get(fieldId);
      if (formula == null) {
        continue;
      }
      try {
        working.put(fieldId, Evaluator.evaluate(formula, working));
      } catch (FormulaEvaluationException raised) {
        throw new TableException(
            "Failed to backfill computed fields ["
                + fieldId
                + "(dbFieldName="
                + nameOf(fieldId)
                + ")] (table="
                + id
                + ", record="
                + recordId
                + "): "
                + raised.getMessage());
      }
    }
    return Collections.unmodifiableMap(working);
  }

  private String nameOf(String fieldId) {
    for (var field : fields) {
      if (field.id().equals(fieldId)) {
        return field.name();
      }
    }
    return fieldId;
  }
}
