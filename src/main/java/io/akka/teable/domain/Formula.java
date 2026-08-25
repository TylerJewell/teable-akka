package io.akka.teable.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A parsed formula expression and the field ids it reads.
 *
 * <p>Precedence, tightest first: unary minus; {@code * / %}; {@code + -}; {@code > < >= <=};
 * {@code = !=}; {@code &&}; {@code ||}; {@code &}. The last one is the surprise -- string
 * concatenation binds looser than every comparison, so {@code "a" & 1 = 1} concatenates the
 * result of the comparison.
 */
public final class Formula {

  /** A field id as the original writes it: the `fld` prefix and sixteen more characters. */
  private static final Pattern FIELD_ID = Pattern.compile("fld[A-Za-z0-9]{16}");

  public sealed interface Node {
    record Literal(CellValue value) implements Node {}

    record Reference(String fieldId) implements Node {}

    record Unary(Node operand) implements Node {}

    record Binary(String operator, Node left, Node right) implements Node {}

    record Call(String name, List<Node> arguments) implements Node {}
  }

  private final String expression;
  private final Node root;
  private final List<String> references;

  private Formula(String expression, Node root, List<String> references) {
    this.expression = expression;
    this.root = root;
    this.references = references;
  }

  public static Formula parse(String expression) {
    var root = new Parser(expression).parseWhole();
    Set<String> found = new LinkedHashSet<>();
    collectReferences(root, found);
    var invalid = new ArrayList<String>();
    for (var reference : found) {
      if (!FIELD_ID.matcher(reference).matches()) {
        invalid.add(reference);
      }
    }
    if (!invalid.isEmpty()) {
      throw new FormulaException(
          "Formula references not found: "
              + String.join(", ", invalid)
              + ". Formulas must use field IDs (fldXXXXXXXXXXXXXXXX format), not field names.");
    }
    return new Formula(expression, root, List.copyOf(found));
  }

  public String expression() {
    return expression;
  }

  public Node root() {
    return root;
  }

  /** De-duplicated, in first-occurrence order. */
  public List<String> references() {
    return references;
  }

  private static void collectReferences(Node node, Set<String> into) {
    switch (node) {
      case Node.Reference reference -> into.add(reference.fieldId());
      case Node.Unary unary -> collectReferences(unary.operand(), into);
      case Node.Binary binary -> {
        collectReferences(binary.left(), into);
        collectReferences(binary.right(), into);
      }
      case Node.Call call -> call.arguments().forEach(argument -> collectReferences(argument, into));
      case Node.Literal ignored -> {}
    }
  }

  /** Recursive descent over the grammar's precedence ladder. */
  private static final class Parser {

    private final String source;
    private int at;

    Parser(String source) {
      this.source = source;
      this.at = 0;
    }

    Node parseWhole() {
      var node = parseConcat();
      skipSpace();
      if (at < source.length()) {
        throw new FormulaException(
            "Formula expression " + source + " parse error: extraneous input '"
                + source.substring(at) + "'");
      }
      return node;
    }

    private Node parseConcat() {
      var left = parseOr();
      while (matchOperator("&", "&&")) {
        left = new Node.Binary("&", left, parseOr());
      }
      return left;
    }

    private Node parseOr() {
      var left = parseAnd();
      while (matchOperator("||")) {
        left = new Node.Binary("||", left, parseAnd());
      }
      return left;
    }

    private Node parseAnd() {
      var left = parseEquality();
      while (matchOperator("&&")) {
        left = new Node.Binary("&&", left, parseEquality());
      }
      return left;
    }

    private Node parseEquality() {
      var left = parseRelational();
      while (true) {
        if (matchOperator("!=")) {
          left = new Node.Binary("!=", left, parseRelational());
        } else if (matchOperator("=", "==")) {
          left = new Node.Binary("=", left, parseRelational());
        } else {
          return left;
        }
      }
    }

    private Node parseRelational() {
      var left = parseAdditive();
      while (true) {
        if (matchOperator(">=")) {
          left = new Node.Binary(">=", left, parseAdditive());
        } else if (matchOperator("<=")) {
          left = new Node.Binary("<=", left, parseAdditive());
        } else if (matchOperator(">")) {
          left = new Node.Binary(">", left, parseAdditive());
        } else if (matchOperator("<")) {
          left = new Node.Binary("<", left, parseAdditive());
        } else {
          return left;
        }
      }
    }

    private Node parseAdditive() {
      var left = parseMultiplicative();
      while (true) {
        if (matchOperator("+")) {
          left = new Node.Binary("+", left, parseMultiplicative());
        } else if (matchOperator("-")) {
          left = new Node.Binary("-", left, parseMultiplicative());
        } else {
          return left;
        }
      }
    }

    private Node parseMultiplicative() {
      var left = parseUnary();
      while (true) {
        if (matchOperator("*")) {
          left = new Node.Binary("*", left, parseUnary());
        } else if (matchOperator("/")) {
          left = new Node.Binary("/", left, parseUnary());
        } else if (matchOperator("%")) {
          left = new Node.Binary("%", left, parseUnary());
        } else {
          return left;
        }
      }
    }

    private Node parseUnary() {
      skipSpace();
      if (matchOperator("-")) {
        return new Node.Unary(parseUnary());
      }
      return parsePrimary();
    }

    private Node parsePrimary() {
      skipSpace();
      if (at >= source.length()) {
        throw new FormulaException(
            "Formula expression " + source + " parse error: mismatched input '<EOF>'");
      }
      var c = source.charAt(at);
      if (c == '(') {
        at++;
        var inner = parseConcat();
        skipSpace();
        expect(')');
        return inner;
      }
      if (c == '{') {
        return parseReference();
      }
      if (c == '"' || c == '\'') {
        return new Node.Literal(CellValue.text(parseString(c)));
      }
      if (Character.isDigit(c) || (c == '.' && at + 1 < source.length()
          && Character.isDigit(source.charAt(at + 1)))) {
        return new Node.Literal(CellValue.number(parseNumber()));
      }
      if (Character.isLetter(c) || c == '_') {
        return parseWord();
      }
      throw new FormulaException(
          "Formula expression " + source + " parse error: mismatched input '" + c + "'");
    }

    private Node parseReference() {
      var close = source.indexOf('}', at);
      if (close < 0) {
        throw new FormulaException(
            "Formula expression " + source + " parse error: mismatched input '<EOF>'");
      }
      var identifier = source.substring(at + 1, close).trim();
      at = close + 1;
      if (identifier.isEmpty()) {
        throw new FormulaException("FieldId {} is a invalid field id");
      }
      return new Node.Reference(identifier);
    }

    private String parseString(char quote) {
      at++;
      var out = new StringBuilder();
      while (at < source.length()) {
        var c = source.charAt(at);
        if (c == '\\' && at + 1 < source.length()) {
          at++;
          out.append(unescape(source.charAt(at)));
          at++;
          continue;
        }
        if (c == quote) {
          at++;
          return out.toString();
        }
        out.append(c);
        at++;
      }
      throw new FormulaException(
          "Formula expression " + source + " parse error: mismatched input '<EOF>'");
    }

    private static String unescape(char c) {
      return switch (c) {
        case 'n' -> "\n";
        case 'r' -> "\r";
        case 't' -> "\t";
        case 'b' -> "\b"; // source-hygiene: intentional -- the backspace character, not a regex word boundary
        case 'f' -> "\f";
        case '\\' -> "\\";
        case '"' -> "\"";
        case '\'' -> "'";
        default -> "\\" + c;
      };
    }

    private double parseNumber() {
      var start = at;
      while (at < source.length()
          && (Character.isDigit(source.charAt(at)) || source.charAt(at) == '.')) {
        at++;
      }
      return Double.parseDouble(source.substring(start, at));
    }

    private Node parseWord() {
      var start = at;
      while (at < source.length()
          && (Character.isLetterOrDigit(source.charAt(at)) || source.charAt(at) == '_')) {
        at++;
      }
      var word = source.substring(start, at);
      var upper = word.toUpperCase(Locale.ROOT);
      skipSpace();
      if (at >= source.length() || source.charAt(at) != '(') {
        if (upper.equals("TRUE")) {
          return new Node.Literal(CellValue.bool(true));
        }
        if (upper.equals("FALSE")) {
          return new Node.Literal(CellValue.bool(false));
        }
        throw new FormulaException(
            "Formula expression " + source + " parse error: mismatched input '" + word + "'");
      }
      at++;
      List<Node> arguments = new ArrayList<>();
      skipSpace();
      if (at < source.length() && source.charAt(at) == ')') {
        at++;
      } else {
        while (true) {
          arguments.add(parseConcat());
          skipSpace();
          if (at < source.length() && source.charAt(at) == ',') {
            at++;
            continue;
          }
          expect(')');
          break;
        }
      }
      Functions.checkSignature(upper, arguments.size());
      return new Node.Call(upper, arguments);
    }

    private void expect(char c) {
      skipSpace();
      if (at >= source.length()) {
        throw new FormulaException(
            "Formula expression " + source + " parse error: extraneous input '<EOF>'");
      }
      if (source.charAt(at) != c) {
        throw new FormulaException(
            "Formula expression " + source + " parse error: mismatched input '"
                + source.charAt(at) + "'");
      }
      at++;
    }

    /**
     * Matches an operator only when it is not the prefix of a longer one that starts here --
     * `&` must not consume the first half of `&&`.
     */
    private boolean matchOperator(String operator, String... shadowedBy) {
      skipSpace();
      if (!source.startsWith(operator, at)) {
        return false;
      }
      for (var longer : shadowedBy) {
        if (source.startsWith(longer, at)) {
          return false;
        }
      }
      at += operator.length();
      return true;
    }

    private void skipSpace() {
      while (at < source.length() && Character.isWhitespace(source.charAt(at))) {
        at++;
      }
    }
  }
}
