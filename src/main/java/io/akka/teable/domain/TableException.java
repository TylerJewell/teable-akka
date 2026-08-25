package io.akka.teable.domain;

/** A change the table refuses: a cycle, a missing reference, or a formula it cannot backfill. */
public class TableException extends RuntimeException {
  public TableException(String message) {
    super(message);
  }
}
