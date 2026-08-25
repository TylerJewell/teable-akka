package io.akka.teable.domain;

/** An expression that cannot become a formula field: it does not parse, or it names nothing real. */
public class FormulaException extends RuntimeException {
  public FormulaException(String message) {
    super(message);
  }
}
