package io.akka.teable.domain;

/**
 * An expression that parses but cannot produce a value for a particular record. Distinct from
 * a blank result: a definition is refused when this is raised over any existing record, and a
 * blank result never refuses anything.
 */
public class FormulaEvaluationException extends RuntimeException {
  public FormulaEvaluationException(String message) {
    super(message);
  }
}
