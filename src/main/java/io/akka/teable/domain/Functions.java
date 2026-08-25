package io.akka.teable.domain;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The named functions, and the arities the original accepts.
 *
 * <p>The blank handling is not uniform and is not guessable: SUM and AVERAGE go blank if any
 * argument is blank, MAX and MIN skip blanks, COUNT and COUNTA count every non-blank argument
 * whatever its type, and every text function goes blank when its subject does. Each of those
 * was measured over a table of operand classes rather than read off one example.
 */
final class Functions {

  private record Arity(int min, int max) {}

  private static final int ANY = Integer.MAX_VALUE;

  private static final Map<String, Arity> ARITIES =
      Map.ofEntries(
          Map.entry("SUM", new Arity(1, ANY)),
          Map.entry("AVERAGE", new Arity(1, ANY)),
          Map.entry("MAX", new Arity(1, ANY)),
          Map.entry("MIN", new Arity(1, ANY)),
          Map.entry("COUNT", new Arity(1, ANY)),
          Map.entry("COUNTA", new Arity(1, ANY)),
          Map.entry("ABS", new Arity(1, 1)),
          Map.entry("ROUND", new Arity(1, 2)),
          Map.entry("ROUNDUP", new Arity(1, 2)),
          Map.entry("ROUNDDOWN", new Arity(1, 2)),
          Map.entry("CEILING", new Arity(1, 2)),
          Map.entry("FLOOR", new Arity(1, 2)),
          Map.entry("INT", new Arity(1, 1)),
          Map.entry("EVEN", new Arity(1, 1)),
          Map.entry("ODD", new Arity(1, 1)),
          Map.entry("POWER", new Arity(2, 2)),
          Map.entry("EXP", new Arity(1, 1)),
          Map.entry("SQRT", new Arity(1, 1)),
          Map.entry("LOG", new Arity(1, 2)),
          Map.entry("MOD", new Arity(2, 2)),
          Map.entry("VALUE", new Arity(1, 1)),
          Map.entry("CONCATENATE", new Arity(1, ANY)),
          Map.entry("UPPER", new Arity(1, 1)),
          Map.entry("LOWER", new Arity(1, 1)),
          Map.entry("LEN", new Arity(1, 1)),
          Map.entry("TRIM", new Arity(1, 1)),
          Map.entry("LEFT", new Arity(1, 2)),
          Map.entry("RIGHT", new Arity(1, 2)),
          Map.entry("MID", new Arity(3, 3)),
          Map.entry("REPLACE", new Arity(4, 4)),
          Map.entry("SUBSTITUTE", new Arity(3, 4)),
          Map.entry("FIND", new Arity(2, 3)),
          Map.entry("SEARCH", new Arity(2, 3)),
          Map.entry("REPT", new Arity(2, 2)),
          Map.entry("T", new Arity(1, 1)),
          Map.entry("IF", new Arity(3, 3)),
          Map.entry("SWITCH", new Arity(3, ANY)),
          Map.entry("AND", new Arity(1, ANY)),
          Map.entry("OR", new Arity(1, ANY)),
          Map.entry("NOT", new Arity(1, 1)),
          Map.entry("BLANK", new Arity(0, 0)),
          Map.entry("IS_ERROR", new Arity(1, 1)));

  /** Functions whose first argument being blank makes the whole call blank. */
  static final Set<String> BLANK_IN_BLANK_OUT =
      Set.of(
          "UPPER", "LOWER", "LEN", "TRIM", "LEFT", "RIGHT", "MID", "REPLACE", "SUBSTITUTE",
          "FIND", "SEARCH", "REPT", "T", "ABS", "ROUND", "ROUNDUP", "ROUNDDOWN", "CEILING",
          "FLOOR", "INT", "EVEN", "ODD", "POWER", "EXP", "SQRT", "LOG", "VALUE");

  private Functions() {}

  static void checkSignature(String name, int argumentCount) {
    var arity = ARITIES.get(name);
    if (arity == null) {
      throw new FormulaException("Function name " + name + " is not found");
    }
    if (argumentCount < arity.min()) {
      throw new FormulaException(
          name + " needs at least " + arity.min() + " param" + (arity.min() == 1 ? "" : "s"));
    }
    if (argumentCount > arity.max()) {
      throw new FormulaException(
          name + " needs at most " + arity.max() + " param" + (arity.max() == 1 ? "" : "s"));
    }
  }

  /**
   * FIND is case-sensitive, SEARCH is not, both are 1-based, and both answer 0 rather than
   * blank when the needle is absent from a subject that is present.
   */
  static CellValue findIn(String subject, String needle, int from, boolean caseSensitive) {
    var haystack = caseSensitive ? subject : subject.toLowerCase(Locale.ROOT);
    var target = caseSensitive ? needle : needle.toLowerCase(Locale.ROOT);
    var start = Math.max(0, from - 1);
    if (start > haystack.length()) {
      return CellValue.number(0);
    }
    return CellValue.number(haystack.indexOf(target, start) + 1);
  }

  static double even(double x) {
    return 2 * Math.floor(x / 2 + 0.5);
  }

  static double odd(double x) {
    return 2 * Math.floor(x / 2) + 1;
  }

  /** Half away from zero, which is what ROUND does and what none of the others do. */
  static double roundHalfAwayFromZero(double x, int digits) {
    var scale = Math.pow(10, digits);
    var scaled = x * scale;
    var rounded = scaled < 0 ? -Math.floor(-scaled + 0.5) : Math.floor(scaled + 0.5);
    return rounded / scale;
  }

  static double scaledCeil(double x, int digits) {
    var scale = Math.pow(10, digits);
    return Math.ceil(x * scale) / scale;
  }

  static double scaledFloor(double x, int digits) {
    var scale = Math.pow(10, digits);
    return Math.floor(x * scale) / scale;
  }

  static boolean anyBlank(List<CellValue> values) {
    return values.stream().anyMatch(CellValue::isBlank);
  }
}
