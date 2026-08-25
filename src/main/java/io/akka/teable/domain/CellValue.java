package io.akka.teable.domain;

/**
 * One cell's value. Blank is a kind of its own rather than a null slot, because several
 * rules turn on the difference between blank, zero and the empty string.
 *
 * <p>Kept as one flat record with a discriminator rather than a sealed interface with a
 * variant per kind: a sealed interface nested inside a command record does not survive the
 * runtime's wire serializer, which was measured before this model was chosen.
 */
public record CellValue(Kind kind, Double number, String text, Boolean flag) {

  public enum Kind {
    BLANK,
    NUMBER,
    TEXT,
    BOOLEAN
  }

  private static final CellValue BLANK = new CellValue(Kind.BLANK, null, null, null);

  public static CellValue blank() {
    return BLANK;
  }

  public static CellValue number(double value) {
    return new CellValue(Kind.NUMBER, value, null, null);
  }

  public static CellValue text(String value) {
    return value == null ? BLANK : new CellValue(Kind.TEXT, null, value, null);
  }

  public static CellValue bool(boolean value) {
    return new CellValue(Kind.BOOLEAN, null, null, value);
  }

  public boolean isBlank() {
    return kind == Kind.BLANK;
  }

  /** The value as a number, with blank reading as zero. Callers that must distinguish check first. */
  public double asNumber() {
    return switch (kind) {
      case NUMBER -> number;
      case BOOLEAN -> flag ? 1 : 0;
      case TEXT -> parseNumber(text);
      case BLANK -> 0;
    };
  }

  private static double parseNumber(String raw) {
    try {
      return Double.parseDouble(raw.trim());
    } catch (RuntimeException notANumber) {
      return 0;
    }
  }

  /** How a value reads when concatenated. A whole number loses its fraction; blank is empty. */
  public String asText() {
    return switch (kind) {
      case BLANK -> "";
      case TEXT -> text;
      case BOOLEAN -> flag ? "true" : "false";
      case NUMBER -> formatNumber(number);
    };
  }

  public static String formatNumber(double value) {
    if (value == Math.rint(value) && !Double.isInfinite(value)) {
      return String.valueOf((long) value);
    }
    return String.valueOf(value);
  }

  /** Truthiness where a value is used as a condition: non-zero, non-empty, not blank. */
  public boolean asCondition() {
    return switch (kind) {
      case BLANK -> false;
      case BOOLEAN -> flag;
      case NUMBER -> number != 0;
      case TEXT -> !text.isEmpty();
    };
  }

  /**
   * Zero is stored as positive zero so that a negated zero compares equal to one that was
   * never negated -- the original stores the negative zero, and it reads back as zero.
   */
  public static CellValue normalisedNumber(double value) {
    return number(value == 0 ? 0 : value);
  }
}
