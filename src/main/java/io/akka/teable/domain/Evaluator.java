package io.akka.teable.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates a parsed formula against one record's cells.
 *
 * <p>The answers here are the running original's, not those of the in-process evaluator that
 * also ships in the source. The two disagree on seven measured expressions and only one of
 * them is reachable from a request, so this follows that one.
 */
public final class Evaluator {

  private Evaluator() {}

  public static CellValue evaluate(Formula formula, Map<String, CellValue> cells) {
    return visit(formula.root(), cells);
  }

  private static CellValue visit(Formula.Node node, Map<String, CellValue> cells) {
    return switch (node) {
      case Formula.Node.Literal literal -> literal.value();
      case Formula.Node.Reference reference ->
          cells.getOrDefault(reference.fieldId(), CellValue.blank());
      case Formula.Node.Unary unary -> negate(visit(unary.operand(), cells));
      case Formula.Node.Binary binary -> binary(binary, cells);
      case Formula.Node.Call call -> call(call, cells);
    };
  }

  private static CellValue negate(CellValue operand) {
    if (operand.isBlank()) {
      return CellValue.blank();
    }
    return CellValue.normalisedNumber(-operand.asNumber());
  }

  private static CellValue binary(Formula.Node.Binary node, Map<String, CellValue> cells) {
    var left = visit(node.left(), cells);
    var right = visit(node.right(), cells);
    return switch (node.operator()) {
      case "+" -> isTextual(left) || isTextual(right)
          ? CellValue.text(left.asText() + right.asText())
          : CellValue.normalisedNumber(left.asNumber() + right.asNumber());
      case "-" -> CellValue.normalisedNumber(left.asNumber() - right.asNumber());
      case "*" -> CellValue.normalisedNumber(left.asNumber() * right.asNumber());
      // A blank or zero divisor produces blank; a blank dividend is simply zero.
      case "/" -> right.isBlank() || right.asNumber() == 0
          ? CellValue.blank()
          : CellValue.normalisedNumber(left.asNumber() / right.asNumber());
      case "%" -> right.isBlank() || right.asNumber() == 0
          ? CellValue.blank()
          : CellValue.normalisedNumber(left.asNumber() % right.asNumber());
      case "&" -> CellValue.text(left.asText() + right.asText());
      case "=" -> CellValue.bool(equalValues(left, right));
      case "!=" -> CellValue.bool(!equalValues(left, right));
      case ">" -> CellValue.bool(compare(left, right) > 0);
      case "<" -> CellValue.bool(compare(left, right) < 0);
      case ">=" -> CellValue.bool(compare(left, right) >= 0);
      case "<=" -> CellValue.bool(compare(left, right) <= 0);
      case "&&" -> CellValue.bool(left.asCondition() && right.asCondition());
      case "||" -> CellValue.bool(left.asCondition() || right.asCondition());
      default -> throw new FormulaEvaluationException("Unsupported operator " + node.operator());
    };
  }

  private static boolean isTextual(CellValue value) {
    return value.kind() == CellValue.Kind.TEXT;
  }

  private static boolean equalValues(CellValue left, CellValue right) {
    if (left.isBlank() && right.isBlank()) {
      return true;
    }
    if (left.isBlank() || right.isBlank()) {
      return false;
    }
    if (isTextual(left) || isTextual(right)) {
      return left.asText().equals(right.asText());
    }
    return left.asNumber() == right.asNumber();
  }

  /** Relational comparison reads blank as zero, which is a different rule from equality's. */
  private static int compare(CellValue left, CellValue right) {
    if (isTextual(left) && isTextual(right)) {
      return left.asText().compareTo(right.asText());
    }
    return Double.compare(left.asNumber(), right.asNumber());
  }

  private static CellValue call(Formula.Node.Call node, Map<String, CellValue> cells) {
    var name = node.name();

    // The three that decide whether to evaluate an argument at all.
    switch (name) {
      case "BLANK":
        return CellValue.blank();
      case "IF": {
        var condition = visit(node.arguments().get(0), cells);
        return visit(node.arguments().get(condition.asCondition() ? 1 : 2), cells);
      }
      case "IS_ERROR": {
        var argument = node.arguments().get(0);
        CellValue value;
        try {
          value = visit(argument, cells);
        } catch (FormulaEvaluationException raised) {
          return CellValue.bool(true);
        }
        // A division by zero is an error even though evaluating it on its own gives blank,
        // and BLANK() itself is neither an error nor not one.
        if (isDivisionByZero(argument, cells)) {
          return CellValue.bool(true);
        }
        return value.isBlank() ? CellValue.blank() : CellValue.bool(false);
      }
      case "SWITCH":
        return switchOn(node, cells);
      default:
        break;
    }

    List<CellValue> arguments = new ArrayList<>();
    for (var argument : node.arguments()) {
      arguments.add(visit(argument, cells));
    }

    // FIND and SEARCH go blank if *either* of their two arguments is blank -- an empty string
    // literal is a different thing from a blank cell, and is found at position 1. Every other
    // blank-propagating function turns on its first argument alone.
    if (name.equals("FIND") || name.equals("SEARCH")) {
      if (arguments.get(0).isBlank() || arguments.get(1).isBlank()) {
        return CellValue.blank();
      }
    } else if (Functions.BLANK_IN_BLANK_OUT.contains(name) && arguments.get(0).isBlank()) {
      return CellValue.blank();
    }

    return switch (name) {
      case "SUM" -> Functions.anyBlank(arguments)
          ? CellValue.blank()
          : CellValue.normalisedNumber(arguments.stream().mapToDouble(CellValue::asNumber).sum());
      case "AVERAGE" -> Functions.anyBlank(arguments)
          ? CellValue.blank()
          : CellValue.normalisedNumber(
              arguments.stream().mapToDouble(CellValue::asNumber).average().orElse(0));
      case "MAX" -> extreme(arguments, true);
      case "MIN" -> extreme(arguments, false);
      case "COUNT", "COUNTA" ->
          CellValue.number(arguments.stream().filter(value -> !value.isBlank()).count());
      case "ABS" -> CellValue.normalisedNumber(Math.abs(arguments.get(0).asNumber()));
      case "ROUND" ->
          CellValue.normalisedNumber(
              Functions.roundHalfAwayFromZero(arguments.get(0).asNumber(), digits(arguments)));
      case "ROUNDUP", "CEILING" ->
          CellValue.normalisedNumber(
              Functions.scaledCeil(arguments.get(0).asNumber(), digits(arguments)));
      case "ROUNDDOWN", "FLOOR" ->
          CellValue.normalisedNumber(
              Functions.scaledFloor(arguments.get(0).asNumber(), digits(arguments)));
      case "INT" -> CellValue.normalisedNumber(Math.floor(arguments.get(0).asNumber()));
      case "EVEN" -> CellValue.normalisedNumber(Functions.even(arguments.get(0).asNumber()));
      case "ODD" -> CellValue.normalisedNumber(Functions.odd(arguments.get(0).asNumber()));
      case "POWER" ->
          CellValue.normalisedNumber(
              Math.pow(arguments.get(0).asNumber(), arguments.get(1).asNumber()));
      case "EXP" -> CellValue.normalisedNumber(Math.exp(arguments.get(0).asNumber()));
      case "SQRT" -> squareRoot(arguments.get(0).asNumber());
      case "LOG" -> logarithm(arguments);
      case "MOD" -> modulo(arguments);
      case "VALUE" -> parseValue(arguments.get(0).asText());
      case "CONCATENATE" -> {
        var out = new StringBuilder();
        arguments.forEach(argument -> out.append(argument.asText()));
        yield CellValue.text(out.toString());
      }
      case "UPPER" -> CellValue.text(arguments.get(0).asText().toUpperCase(Locale.ROOT));
      case "LOWER" -> CellValue.text(arguments.get(0).asText().toLowerCase(Locale.ROOT));
      case "LEN" -> CellValue.number(arguments.get(0).asText().length());
      case "TRIM" -> CellValue.text(arguments.get(0).asText().trim());
      case "LEFT" -> {
        var subject = arguments.get(0).asText();
        var take = Math.min(subject.length(), (int) count(arguments, 1, 1));
        yield CellValue.text(subject.substring(0, Math.max(0, take)));
      }
      case "RIGHT" -> {
        var subject = arguments.get(0).asText();
        var take = Math.min(subject.length(), (int) count(arguments, 1, 1));
        yield CellValue.text(subject.substring(subject.length() - Math.max(0, take)));
      }
      case "MID" -> mid(arguments);
      case "REPLACE" -> replace(arguments);
      case "SUBSTITUTE" -> substitute(arguments);
      case "FIND" ->
          Functions.findIn(
              arguments.get(1).asText(), arguments.get(0).asText(), (int) count(arguments, 2, 1),
              true);
      case "SEARCH" ->
          Functions.findIn(
              arguments.get(1).asText(), arguments.get(0).asText(), (int) count(arguments, 2, 1),
              false);
      case "REPT" -> CellValue.text(arguments.get(0).asText()
          .repeat(Math.max(0, (int) arguments.get(1).asNumber())));
      case "T" -> CellValue.text(arguments.get(0).asText());
      case "AND" ->
          CellValue.bool(arguments.stream().allMatch(CellValue::asCondition));
      case "OR" -> CellValue.bool(arguments.stream().anyMatch(CellValue::asCondition));
      case "NOT" -> CellValue.bool(!arguments.get(0).asCondition());
      default -> throw new FormulaEvaluationException("Function name " + name + " is not found");
    };
  }

  /**
   * SWITCH matches a text subject and a number literal, and never matches a number field. The
   * port reproduces that rather than the behaviour anyone would design, because the benchmark
   * compares answers and a repaired SWITCH would disagree with the original on every row.
   */
  private static CellValue switchOn(Formula.Node.Call node, Map<String, CellValue> cells) {
    var arguments = node.arguments();
    var subjectNode = arguments.get(0);
    var subject = visit(subjectNode, cells);
    var subjectIsANumberField =
        subjectNode instanceof Formula.Node.Reference
            && (subject.kind() == CellValue.Kind.NUMBER || subject.isBlank());

    var pairs = (arguments.size() - 1) / 2;
    if (!subjectIsANumberField) {
      for (int i = 0; i < pairs; i++) {
        var pattern = visit(arguments.get(1 + i * 2), cells);
        if (equalValues(subject, pattern)) {
          return visit(arguments.get(2 + i * 2), cells);
        }
      }
    }
    var hasDefault = (arguments.size() - 1) % 2 == 1;
    return hasDefault ? visit(arguments.get(arguments.size() - 1), cells) : CellValue.blank();
  }

  private static boolean isDivisionByZero(Formula.Node node, Map<String, CellValue> cells) {
    if (!(node instanceof Formula.Node.Binary binary)) {
      return false;
    }
    if (!binary.operator().equals("/") && !binary.operator().equals("%")) {
      return false;
    }
    var divisor = visit(binary.right(), cells);
    return divisor.isBlank() || divisor.asNumber() == 0;
  }

  private static CellValue extreme(List<CellValue> arguments, boolean wantMax) {
    Double best = null;
    for (var argument : arguments) {
      if (argument.isBlank()) {
        continue;
      }
      var value = argument.asNumber();
      if (best == null || (wantMax ? value > best : value < best)) {
        best = value;
      }
    }
    return best == null ? CellValue.blank() : CellValue.normalisedNumber(best);
  }

  private static int digits(List<CellValue> arguments) {
    return arguments.size() > 1 ? (int) arguments.get(1).asNumber() : 0;
  }

  private static double count(List<CellValue> arguments, int index, double fallback) {
    return arguments.size() > index ? arguments.get(index).asNumber() : fallback;
  }

  private static CellValue squareRoot(double value) {
    if (value < 0) {
      throw new FormulaEvaluationException("cannot take square root of a negative number");
    }
    return CellValue.normalisedNumber(Math.sqrt(value));
  }

  private static CellValue logarithm(List<CellValue> arguments) {
    var value = arguments.get(0).asNumber();
    if (value < 0) {
      throw new FormulaEvaluationException("cannot take logarithm of a negative number");
    }
    var base = arguments.size() > 1 ? arguments.get(1).asNumber() : 10;
    return CellValue.normalisedNumber(Math.log(value) / Math.log(base));
  }

  /** MOD goes blank on a blank argument where the `%` operator reads a blank left side as zero. */
  private static CellValue modulo(List<CellValue> arguments) {
    var left = arguments.get(0);
    var right = arguments.get(1);
    if (left.isBlank() || right.isBlank() || right.asNumber() == 0) {
      return CellValue.blank();
    }
    return CellValue.normalisedNumber(left.asNumber() % right.asNumber());
  }

  private static CellValue parseValue(String raw) {
    try {
      return CellValue.normalisedNumber(Double.parseDouble(raw.trim()));
    } catch (RuntimeException notANumber) {
      return CellValue.blank();
    }
  }

  private static CellValue mid(List<CellValue> arguments) {
    var subject = arguments.get(0).asText();
    var from = Math.max(0, (int) arguments.get(1).asNumber() - 1);
    var take = Math.max(0, (int) arguments.get(2).asNumber());
    if (from >= subject.length()) {
      return CellValue.text("");
    }
    return CellValue.text(subject.substring(from, Math.min(subject.length(), from + take)));
  }

  private static CellValue replace(List<CellValue> arguments) {
    var subject = arguments.get(0).asText();
    var from = Math.max(0, (int) arguments.get(1).asNumber() - 1);
    var length = Math.max(0, (int) arguments.get(2).asNumber());
    var replacement = arguments.get(3).asText();
    if (from >= subject.length()) {
      return CellValue.text(subject + replacement);
    }
    var end = Math.min(subject.length(), from + length);
    return CellValue.text(subject.substring(0, from) + replacement + subject.substring(end));
  }

  private static CellValue substitute(List<CellValue> arguments) {
    var subject = arguments.get(0).asText();
    var target = arguments.get(1).asText();
    var replacement = arguments.get(2).asText();
    if (target.isEmpty()) {
      return CellValue.text(subject);
    }
    if (arguments.size() == 3) {
      return CellValue.text(subject.replace(target, replacement));
    }
    var occurrence = (int) arguments.get(3).asNumber();
    var at = -1;
    for (int seen = 0; seen < occurrence; seen++) {
      at = subject.indexOf(target, at + 1);
      if (at < 0) {
        return CellValue.text(subject);
      }
    }
    return CellValue.text(
        subject.substring(0, at) + replacement + subject.substring(at + target.length()));
  }
}
