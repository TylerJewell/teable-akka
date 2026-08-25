package io.akka.teable.domain;

import java.util.List;

/**
 * One field of a table. `expression` is set for a formula field and null for a value field;
 * `valueType` is the other way round. Kept as one record with a `kind` discriminator rather
 * than two variants of a sealed interface, because this record travels inside a command and a
 * nested sealed interface does not survive the runtime's wire serializer.
 */
public record FieldDef(String id, String name, Kind kind, ValueType valueType, String expression) {

  public enum Kind {
    VALUE,
    FORMULA
  }

  public static FieldDef value(String id, String name, ValueType valueType) {
    return new FieldDef(id, name, Kind.VALUE, valueType, null);
  }

  public static FieldDef formula(String id, String name, String expression) {
    return new FieldDef(id, name, Kind.FORMULA, null, expression);
  }

  public boolean isFormula() {
    return kind == Kind.FORMULA;
  }

  /** Parses on every call rather than caching, so a FieldDef stays a plain serialisable record. */
  public Formula formula() {
    return Formula.parse(expression);
  }

  public List<String> dependencies() {
    return isFormula() ? formula().references() : List.of();
  }
}
