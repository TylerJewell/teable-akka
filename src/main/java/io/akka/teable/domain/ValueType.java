package io.akka.teable.domain;

/** What a value field holds. Formula fields have no declared type; theirs follows the expression. */
public enum ValueType {
  NUMBER,
  TEXT,
  CHECKBOX
}
