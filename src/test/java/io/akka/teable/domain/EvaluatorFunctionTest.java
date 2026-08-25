package io.akka.teable.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R20–R28, R30–R32, R34. Every expected value was read off the running original. Where a
 * function disagrees with the source's other, unreachable evaluator, this file follows the
 * running one -- see question-log row 11.
 */
class EvaluatorFunctionTest {

  private static final String N = "fldNNNNNNNNNNNNNNNN";
  private static final String S = "fldSSSSSSSSSSSSSSSS";

  private static final CellValue BLANK = CellValue.blank();

  private static CellValue num(double value) {
    return CellValue.number(value);
  }

  private static CellValue text(String value) {
    return CellValue.text(value);
  }

  private static CellValue eval(String expression, CellValue number, CellValue string) {
    Map<String, CellValue> cells = new LinkedHashMap<>();
    cells.put(N, number);
    cells.put(S, string);
    return Evaluator.evaluate(Formula.parse(expression), cells);
  }

  private static CellValue eval(String expression) {
    return Evaluator.evaluate(Formula.parse(expression), Map.of());
  }

  // R22 / R23 -- the two aggregate families do not treat blank the same way.

  @Test
  void sumIsBlankIfAnyArgumentIsBlank() {
    assertThat(eval("SUM(1, 2, 3)")).isEqualTo(num(6));
    assertThat(eval("SUM(1, BLANK(), 3)")).isEqualTo(BLANK);
    assertThat(eval("SUM({" + N + "}, 1)", num(4), BLANK)).isEqualTo(num(5));
    assertThat(eval("SUM({" + N + "}, 1)", BLANK, BLANK)).isEqualTo(BLANK);
  }

  @Test
  void averageIsBlankIfAnyArgumentIsBlank() {
    assertThat(eval("AVERAGE(4, 2)")).isEqualTo(num(3));
    assertThat(eval("AVERAGE(1, BLANK(), 3)")).isEqualTo(BLANK);
    assertThat(eval("AVERAGE({" + N + "}, 1.5)", num(-2.5), BLANK)).isEqualTo(num(-0.5));
  }

  @Test
  void maxAndMinSkipBlankArguments() {
    assertThat(eval("MAX(1, BLANK(), 3)")).isEqualTo(num(3));
    assertThat(eval("MIN(1, BLANK(), 3)")).isEqualTo(num(1));
    assertThat(eval("MAX({" + N + "}, BLANK())", num(7), BLANK)).isEqualTo(num(7));
    assertThat(eval("MAX(BLANK(), BLANK())")).isEqualTo(BLANK);
    assertThat(eval("MIN(BLANK(), BLANK())")).isEqualTo(BLANK);
  }

  // R24

  @Test
  void countAndCountaBothCountEveryNonBlankArgumentWhateverItsType() {
    assertThat(eval("COUNT(1, \"x\", 2)")).isEqualTo(num(3));
    assertThat(eval("COUNTA(\"x\", BLANK())")).isEqualTo(num(1));
    assertThat(eval("COUNTA({" + N + "}, BLANK(), {" + S + "})", num(4), text("hello")))
        .isEqualTo(num(2));
    assertThat(eval("COUNTA({" + N + "}, BLANK(), {" + S + "})", BLANK, BLANK)).isEqualTo(num(0));
    assertThat(eval("COUNT({" + N + "}, {" + S + "})", num(7), BLANK)).isEqualTo(num(1));
  }

  // R25 / R31 -- one input each would have made four of these look alike.

  @Test
  void roundBreaksATieAwayFromZero() {
    assertThat(eval("ROUND(2.5)")).isEqualTo(num(3));
    assertThat(eval("ROUND(-2.5)")).isEqualTo(num(-3));
    assertThat(eval("ROUND(-3.5)")).isEqualTo(num(-4));
    assertThat(eval("ROUND(0.5)")).isEqualTo(num(1));
    assertThat(eval("ROUND(-0.5)")).isEqualTo(num(-1));
    assertThat(eval("ROUND(3.14159, 2)")).isEqualTo(num(3.14));
    assertThat(eval("ROUND({" + N + "}, 1)", num(-2.5), BLANK)).isEqualTo(num(-2.5));
  }

  @Test
  void roundupIsCeilingAndRounddownIsFloor() {
    // Not away-from-zero and toward-zero, which is what the positive half looks like.
    for (double x : new double[] {-3.5, -2.5, -1.5, -0.5, 0.5, 1.5, 2.5, 3.5}) {
      assertThat(eval("ROUNDUP(" + x + ", 0)")).isEqualTo(eval("CEILING(" + x + ")"));
      assertThat(eval("ROUNDDOWN(" + x + ", 0)")).isEqualTo(eval("FLOOR(" + x + ")"));
      assertThat(eval("INT(" + x + ")")).isEqualTo(eval("FLOOR(" + x + ")"));
    }
    assertThat(eval("ROUNDUP(-3.5, 0)")).isEqualTo(num(-3));
    assertThat(eval("ROUNDDOWN(-3.5, 0)")).isEqualTo(num(-4));
    assertThat(eval("CEILING(-2.5)")).isEqualTo(num(-2));
    assertThat(eval("FLOOR(-2.5)")).isEqualTo(num(-3));
  }

  @Test
  void evenAndOddRoundTheWayTheOriginalDoesRatherThanTheWayASpreadsheetDoes() {
    double[] inputs = {-3.5, -3, -2.5, -2, -1.5, -1, -0.5, 0, 0.5, 1, 1.5, 2, 2.5, 3, 3.5};
    double[] evens = {-4, -2, -2, -2, -2, 0, 0, 0, 0, 2, 2, 2, 2, 4, 4};
    double[] odds = {-3, -3, -3, -1, -1, -1, -1, 1, 1, 1, 1, 3, 3, 3, 3};
    for (int i = 0; i < inputs.length; i++) {
      assertThat(eval("EVEN({" + N + "})", num(inputs[i]), BLANK))
          .as("EVEN(%s)", inputs[i])
          .isEqualTo(num(evens[i]));
      assertThat(eval("ODD({" + N + "})", num(inputs[i]), BLANK))
          .as("ODD(%s)", inputs[i])
          .isEqualTo(num(odds[i]));
    }
  }

  // R32

  @Test
  void theModFunctionDisagreesWithTheModuloOperatorOnABlankLeftOperand() {
    assertThat(eval("MOD({" + N + "}, 3)", BLANK, BLANK)).isEqualTo(BLANK);
    assertThat(eval("{" + N + "} % 3", BLANK, BLANK)).isEqualTo(num(0));
    assertThat(eval("MOD(4, 2)")).isEqualTo(num(0));
    assertThat(eval("MOD(-2.5, 1.5)")).isEqualTo(num(-1));
    assertThat(eval("MOD(0, 0)")).isEqualTo(BLANK);
  }

  // R20 / R21

  @Test
  void everyTextFunctionIsBlankWhenItsSubjectIsBlank() {
    for (String call :
        new String[] {
          "UPPER({s})", "LOWER({s})", "LEN({s})", "TRIM({s})", "LEFT({s}, 2)",
          "RIGHT({s}, 2)", "MID({s}, 2, 3)", "REPLACE({s}, 1, 1, \"J\")",
          "SUBSTITUTE({s}, \"l\", \"L\")", "FIND(\"l\", {s})", "SEARCH(\"l\", {s})",
          "REPT({s}, 2)", "T({s})"
        }) {
      var expression = call.replace("{s}", "{" + S + "}");
      assertThat(eval(expression, BLANK, BLANK)).as(expression).isEqualTo(BLANK);
    }
    assertThat(eval("LEN(BLANK())")).isEqualTo(BLANK);
    assertThat(eval("UPPER(BLANK())")).isEqualTo(BLANK);
  }

  @Test
  void theTextFunctionsOnASubjectThatIsThere() {
    var s = text("hello");
    assertThat(eval("UPPER({" + S + "})", BLANK, s)).isEqualTo(text("HELLO"));
    assertThat(eval("LOWER({" + S + "})", BLANK, text("HeLLo"))).isEqualTo(text("hello"));
    assertThat(eval("LEN({" + S + "})", BLANK, s)).isEqualTo(num(5));
    assertThat(eval("TRIM({" + S + "})", BLANK, text("  pad  "))).isEqualTo(text("pad"));
    assertThat(eval("LEFT({" + S + "}, 2)", BLANK, s)).isEqualTo(text("he"));
    assertThat(eval("RIGHT({" + S + "}, 2)", BLANK, s)).isEqualTo(text("lo"));
    assertThat(eval("MID({" + S + "}, 2, 3)", BLANK, s)).isEqualTo(text("ell"));
    assertThat(eval("REPLACE({" + S + "}, 1, 1, \"J\")", BLANK, s)).isEqualTo(text("Jello"));
    assertThat(eval("SUBSTITUTE({" + S + "}, \"l\", \"L\")", BLANK, s)).isEqualTo(text("heLLo"));
    assertThat(eval("REPT({" + S + "}, 2)", BLANK, text("lo"))).isEqualTo(text("lolo"));
    assertThat(eval("T({" + S + "})", BLANK, s)).isEqualTo(s);
    assertThat(eval("T({" + N + "})", num(4), BLANK)).isEqualTo(text("4"));
    assertThat(eval("T({" + N + "})", num(-2.5), BLANK)).isEqualTo(text("-2.5"));
    assertThat(eval("CONCATENATE({" + S + "}, \"-\", {" + N + "})", num(4), s))
        .isEqualTo(text("hello-4"));
    assertThat(eval("CONCATENATE(BLANK(), BLANK())")).isEqualTo(text(""));
  }

  @Test
  void findAndSearchGoBlankWhenEitherArgumentIsBlankButNotOnAnEmptyLiteral() {
    // An empty string literal is not a blank cell: the original finds it at position 1 and
    // goes blank only when one of the two arguments is genuinely absent.
    assertThat(eval("FIND({" + S + "}, \"Hello\")", BLANK, BLANK)).isEqualTo(BLANK);
    assertThat(eval("SEARCH({" + S + "}, \"Hello\")", BLANK, BLANK)).isEqualTo(BLANK);
    assertThat(eval("FIND(\"lo\", {" + S + "})", BLANK, BLANK)).isEqualTo(BLANK);
    assertThat(eval("FIND(\"\", {" + S + "})", BLANK, text("hello"))).isEqualTo(num(1));
    assertThat(eval("SEARCH(\"\", {" + S + "})", BLANK, text("hello"))).isEqualTo(num(1));
    assertThat(eval("FIND(BLANK(), {" + S + "})", BLANK, text("hello"))).isEqualTo(BLANK);
  }

  @Test
  void findIsCaseSensitiveAndSearchIsNotAndBothAnswerZeroWhenAbsent() {
    assertThat(eval("FIND(\"lo\", {" + S + "})", BLANK, text("hello"))).isEqualTo(num(4));
    assertThat(eval("FIND(\"LL\", {" + S + "})", BLANK, text("hello"))).isEqualTo(num(0));
    assertThat(eval("FIND(\"LL\", {" + S + "})", BLANK, text("Hello"))).isEqualTo(num(0));
    assertThat(eval("SEARCH(\"lo\", {" + S + "})", BLANK, text("hello"))).isEqualTo(num(4));
    assertThat(eval("SEARCH(\"LL\", {" + S + "})", BLANK, text("hello"))).isEqualTo(num(3));
    assertThat(eval("SEARCH(\"LL\", {" + S + "})", BLANK, text("Hello"))).isEqualTo(num(3));
    assertThat(eval("SEARCH(\"LL\", {" + S + "})", BLANK, text("  pad  "))).isEqualTo(num(0));
  }

  // R26 / R27 / R34

  @Test
  void ifNeedsThreeArguments() {
    assertThatThrownBy(() -> Formula.parse("IF(1 > 0, \"big\")"))
        .isInstanceOf(FormulaException.class)
        .hasMessageContaining("IF needs at least 3 params");
  }

  @Test
  void aNumberConditionIsTrueWhenNonZeroAndFalseWhenZeroOrBlank() {
    assertThat(eval("IF({" + N + "}, \"set\", \"unset\")", num(4), BLANK)).isEqualTo(text("set"));
    assertThat(eval("IF({" + N + "}, \"set\", \"unset\")", num(0), BLANK)).isEqualTo(text("unset"));
    assertThat(eval("IF({" + N + "}, \"set\", \"unset\")", BLANK, BLANK)).isEqualTo(text("unset"));
    assertThat(eval("IF({" + N + "}, \"set\", \"unset\")", num(-2.5), BLANK))
        .isEqualTo(text("set"));
    assertThat(eval("IF({" + N + "} > 1, \"big\", \"small\")", num(4), BLANK))
        .isEqualTo(text("big"));
    assertThat(eval("IF({" + N + "} > 1, \"big\", \"small\")", BLANK, BLANK))
        .isEqualTo(text("small"));
  }

  @Test
  void switchMatchesALiteralAndATextFieldAndNeverMatchesANumberField() {
    assertThat(eval("SWITCH(4, 4, \"four\", \"other\")")).isEqualTo(text("four"));
    assertThat(eval("SWITCH(\"a\", \"a\", \"A\", \"other\")")).isEqualTo(text("A"));
    assertThat(eval("SWITCH({" + S + "}, \"hello\", \"H\", \"miss\")", BLANK, text("hello")))
        .isEqualTo(text("H"));
    assertThat(eval("SWITCH({" + S + "}, \"hello\", \"H\", \"miss\")", BLANK, text("nope")))
        .isEqualTo(text("miss"));
    assertThat(eval("SWITCH({" + N + "}, 0, \"zero\", 4, \"four\", \"other\")", num(4), BLANK))
        .isEqualTo(text("other"));
    assertThat(eval("SWITCH({" + N + "}, 0, \"zero\", 4, \"four\", \"other\")", num(0), BLANK))
        .isEqualTo(text("other"));
    assertThat(eval("SWITCH({" + N + "}, 4, \"four\")", num(4), BLANK)).isEqualTo(BLANK);
  }

  @Test
  void theBooleanFunctions() {
    assertThat(eval("AND({" + N + "} > 1, 2 > 1)", num(4), BLANK)).isEqualTo(CellValue.bool(true));
    assertThat(eval("AND({" + N + "} > 1, 2 > 1)", num(0), BLANK)).isEqualTo(CellValue.bool(false));
    assertThat(eval("OR({" + N + "} > 1, 3 > 1)", BLANK, BLANK)).isEqualTo(CellValue.bool(true));
    assertThat(eval("OR({" + N + "} > 1, 0 > 1)", num(0), BLANK)).isEqualTo(CellValue.bool(false));
    assertThat(eval("NOT({" + N + "})", CellValue.bool(true), BLANK))
        .isEqualTo(CellValue.bool(false));
    assertThat(eval("NOT({" + N + "})", CellValue.bool(false), BLANK))
        .isEqualTo(CellValue.bool(true));
    assertThat(eval("NOT({" + N + "})", BLANK, BLANK)).isEqualTo(CellValue.bool(true));
    assertThat(eval("BLANK()")).isEqualTo(BLANK);
  }

  // R28

  @Test
  void isErrorSeparatesBeingBlankFromBeingAnError() {
    assertThat(eval("IS_ERROR(1)")).isEqualTo(CellValue.bool(false));
    assertThat(eval("IS_ERROR(1 / 0)")).isEqualTo(CellValue.bool(true));
    assertThat(eval("IS_ERROR(BLANK())")).isEqualTo(BLANK);
    assertThat(eval("1 / 0")).isEqualTo(BLANK);
  }

  // R30

  @Test
  void valueParsesANumericTextAndIsBlankOnAnythingElse() {
    assertThat(eval("VALUE(\"12.5\")")).isEqualTo(num(12.5));
    assertThat(eval("VALUE({" + S + "})", BLANK, text("hello"))).isEqualTo(BLANK);
    assertThat(eval("VALUE({" + S + "})", BLANK, text("  pad  "))).isEqualTo(BLANK);
    assertThat(eval("VALUE({" + S + "})", BLANK, BLANK)).isEqualTo(BLANK);
    assertThat(eval("VALUE({" + N + "} & \"\")", num(-2.5), BLANK)).isEqualTo(num(-2.5));
  }

  @Test
  void theRemainingNumericFunctions() {
    assertThat(eval("ABS({" + N + "})", num(-2.5), BLANK)).isEqualTo(num(2.5));
    assertThat(eval("ABS({" + N + "})", BLANK, BLANK)).isEqualTo(BLANK);
    assertThat(eval("POWER({" + N + "}, 2)", num(-2.5), BLANK)).isEqualTo(num(6.25));
    assertThat(eval("EXP(0)")).isEqualTo(num(1));
    assertThat(((Double) eval("EXP(2)").number())).isCloseTo(7.38905609893065, within());
    assertThat(eval("SQRT(9)")).isEqualTo(num(3));
    assertThat(eval("LOG(1)")).isEqualTo(num(0));
  }

  // R15 -- the raise that makes a definition fail is raised here.

  @Test
  void sqrtAndLogOfANegativeNumberRaise() {
    assertThatThrownBy(() -> eval("SQRT({" + N + "})", num(-2.5), BLANK))
        .isInstanceOf(FormulaEvaluationException.class)
        .hasMessageContaining("square root");
    assertThatThrownBy(() -> eval("LOG({" + N + "})", num(-2.5), BLANK))
        .isInstanceOf(FormulaEvaluationException.class)
        .hasMessageContaining("logarithm");
  }

  private static org.assertj.core.data.Offset<Double> within() {
    return org.assertj.core.data.Offset.offset(1e-12);
  }
}
