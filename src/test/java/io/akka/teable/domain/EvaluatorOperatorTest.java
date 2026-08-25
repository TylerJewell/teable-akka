package io.akka.teable.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R16–R21, R29, R33, R35. Every expected value here was read off the running original, not
 * off the source's in-process evaluator, which answers several of these differently.
 */
class EvaluatorOperatorTest {

  private static final String A = "fldAAAAAAAAAAAAAAAA";
  private static final String B = "fldBBBBBBBBBBBBBBBB";

  private static CellValue eval(String expression, CellValue a, CellValue b) {
    Map<String, CellValue> cells = new LinkedHashMap<>();
    cells.put(A, a);
    cells.put(B, b);
    return Evaluator.evaluate(Formula.parse(expression), cells);
  }

  private static CellValue eval(String expression) {
    return Evaluator.evaluate(Formula.parse(expression), Map.of());
  }

  private static final CellValue BLANK = CellValue.blank();

  private static CellValue num(double value) {
    return CellValue.number(value);
  }

  private static CellValue text(String value) {
    return CellValue.text(value);
  }

  // R16

  @Test
  void plusTreatsBlankAsZero() {
    assertThat(eval("{" + A + "} + {" + B + "}", num(4), num(2))).isEqualTo(num(6));
    assertThat(eval("{" + A + "} + {" + B + "}", BLANK, num(3))).isEqualTo(num(3));
    assertThat(eval("{" + A + "} + {" + B + "}", num(7), BLANK)).isEqualTo(num(7));
    assertThat(eval("{" + A + "} + {" + B + "}", BLANK, BLANK)).isEqualTo(num(0));
  }

  @Test
  void minusTreatsBlankAsZero() {
    assertThat(eval("{" + A + "} - {" + B + "}", num(4), num(2))).isEqualTo(num(2));
    assertThat(eval("{" + A + "} - {" + B + "}", BLANK, num(3))).isEqualTo(num(-3));
    assertThat(eval("{" + A + "} - {" + B + "}", num(7), BLANK)).isEqualTo(num(7));
    assertThat(eval("{" + A + "} - {" + B + "}", BLANK, BLANK)).isEqualTo(num(0));
  }

  @Test
  void timesTreatsBlankAsZero() {
    assertThat(eval("{" + A + "} * {" + B + "}", num(4), num(2))).isEqualTo(num(8));
    assertThat(eval("{" + A + "} * {" + B + "}", num(7), BLANK)).isEqualTo(num(0));
    assertThat(eval("{" + A + "} * {" + B + "}", BLANK, num(3))).isEqualTo(num(0));
    assertThat(eval("{" + A + "} * {" + B + "}", num(-2.5), num(1.5))).isEqualTo(num(-3.75));
  }

  // R17

  @Test
  void divideProducesBlankOnABlankOrZeroRightOperandAndZeroOnABlankLeftOne() {
    assertThat(eval("{" + A + "} / {" + B + "}", num(4), num(2))).isEqualTo(num(2));
    assertThat(eval("{" + A + "} / {" + B + "}", num(0), num(0))).isEqualTo(BLANK);
    assertThat(eval("{" + A + "} / {" + B + "}", num(7), BLANK)).isEqualTo(BLANK);
    assertThat(eval("{" + A + "} / {" + B + "}", BLANK, num(3))).isEqualTo(num(0));
    assertThat(eval("{" + A + "} / {" + B + "}", BLANK, BLANK)).isEqualTo(BLANK);
    assertThat(eval("1 / 0")).isEqualTo(BLANK);
  }

  @Test
  void moduloFollowsTheSameGuardAsDivide() {
    assertThat(eval("{" + A + "} % {" + B + "}", num(4), num(2))).isEqualTo(num(0));
    assertThat(eval("{" + A + "} % {" + B + "}", num(0), num(0))).isEqualTo(BLANK);
    assertThat(eval("{" + A + "} % {" + B + "}", num(7), BLANK)).isEqualTo(BLANK);
    assertThat(eval("{" + A + "} % {" + B + "}", BLANK, num(3))).isEqualTo(num(0));
    assertThat(eval("{" + A + "} % {" + B + "}", num(-2.5), num(1.5))).isEqualTo(num(-1));
    assertThat(eval("1 % 0")).isEqualTo(BLANK);
  }

  // R18

  @Test
  void unaryMinusOfBlankIsBlankAndOfZeroIsZero() {
    assertThat(eval("-{" + A + "}", num(4), BLANK)).isEqualTo(num(-4));
    assertThat(eval("-{" + A + "}", num(0), BLANK)).isEqualTo(num(0));
    assertThat(eval("-{" + A + "}", BLANK, BLANK)).isEqualTo(BLANK);
    assertThat(eval("-0")).isEqualTo(num(0));
  }

  // R19

  @Test
  void concatenationRendersBlankAsTheEmptyStringRatherThanProducingBlank() {
    assertThat(eval("{" + A + "} & {" + B + "}", text("hello"), text("lo")))
        .isEqualTo(text("hellolo"));
    assertThat(eval("{" + A + "} & {" + B + "}", BLANK, text("x"))).isEqualTo(text("x"));
    assertThat(eval("{" + A + "} & {" + B + "}", text("Hello"), BLANK)).isEqualTo(text("Hello"));
    assertThat(eval("{" + A + "} & {" + B + "}", BLANK, BLANK)).isEqualTo(text(""));
  }

  @Test
  void plusOnTextConcatenatesTheSameWay() {
    assertThat(eval("{" + A + "} + {" + B + "}", text("hello"), text("lo")))
        .isEqualTo(text("hellolo"));
    assertThat(eval("{" + A + "} + {" + B + "}", BLANK, text("x"))).isEqualTo(text("x"));
  }

  // R35

  @Test
  void aNumberRendersIntoTextWithoutATrailingZeroFractionAndABooleanAsTrueOrFalse() {
    assertThat(eval("{" + A + "} & {" + B + "}", num(4), text("hello"))).isEqualTo(text("4hello"));
    assertThat(eval("{" + A + "} & {" + B + "}", num(0), BLANK)).isEqualTo(text("0"));
    assertThat(eval("{" + A + "} & {" + B + "}", num(-2.5), text("  pad  ")))
        .isEqualTo(text("-2.5  pad  "));
    assertThat(eval("\"a\" & 1 = 1")).isEqualTo(text("atrue"));
  }

  // R29

  @Test
  void blankComparesAsZeroOnRelationalOperators() {
    assertThat(eval("{" + A + "} > {" + B + "}", num(7), BLANK)).isEqualTo(CellValue.bool(true));
    assertThat(eval("{" + A + "} > {" + B + "}", BLANK, num(3))).isEqualTo(CellValue.bool(false));
    assertThat(eval("{" + A + "} >= {" + B + "}", BLANK, BLANK)).isEqualTo(CellValue.bool(true));
    assertThat(eval("{" + A + "} <= {" + B + "}", BLANK, num(3))).isEqualTo(CellValue.bool(true));
    assertThat(eval("{" + A + "} < {" + B + "}", num(0), num(0))).isEqualTo(CellValue.bool(false));
  }

  @Test
  void blankEqualsBlankForBothNumbersAndText() {
    assertThat(eval("{" + A + "} = {" + B + "}", BLANK, BLANK)).isEqualTo(CellValue.bool(true));
    assertThat(eval("{" + A + "} = {" + B + "}", num(7), BLANK)).isEqualTo(CellValue.bool(false));
    assertThat(eval("{" + A + "} = {" + B + "}", num(0), num(0))).isEqualTo(CellValue.bool(true));
    assertThat(eval("{" + A + "} = {" + B + "}", text("hello"), text("lo")))
        .isEqualTo(CellValue.bool(false));
    assertThat(eval("{" + A + "} != {" + B + "}", BLANK, BLANK)).isEqualTo(CellValue.bool(false));
  }

  @Test
  void logicalOperatorsAnswerABooleanEvenWhenBothSidesAreBlank() {
    assertThat(eval("{" + A + "} && {" + A + "}", CellValue.bool(true), BLANK))
        .isEqualTo(CellValue.bool(true));
    assertThat(eval("{" + A + "} && {" + A + "}", CellValue.bool(false), BLANK))
        .isEqualTo(CellValue.bool(false));
    assertThat(eval("{" + A + "} && {" + A + "}", BLANK, BLANK)).isEqualTo(CellValue.bool(false));
    assertThat(eval("{" + A + "} || {" + A + "}", BLANK, BLANK)).isEqualTo(CellValue.bool(false));
    assertThat(eval("{" + A + "} || {" + A + "}", CellValue.bool(true), BLANK))
        .isEqualTo(CellValue.bool(true));
  }

  // R33

  @Test
  void ampersandBindsLooserThanEverythingElse() {
    assertThat(eval("1 + 2 & \"x\"")).isEqualTo(text("3x"));
  }

  @Test
  void unaryMinusBindsTighterThanMultiplication() {
    assertThat(eval("-{" + A + "} * 2", num(-3.5), BLANK)).isEqualTo(num(7));
  }

  @Test
  void additionBindsTighterThanComparison() {
    assertThat(eval("{" + A + "} + 1 > 2", num(1.5), BLANK)).isEqualTo(CellValue.bool(true));
    assertThat(eval("{" + A + "} + 1 > 2", num(1), BLANK)).isEqualTo(CellValue.bool(false));
  }

  @Test
  void bracketsOverridePrecedence() {
    assertThat(eval("({" + A + "} + {" + B + "}) * 2", num(4), num(2))).isEqualTo(num(12));
    assertThat(eval("{" + A + "} + {" + B + "} * 2", num(4), num(2))).isEqualTo(num(8));
    assertThat(eval("((1 + 2) * (3 - 1))")).isEqualTo(num(6));
  }

  @Test
  void literalsParseAsThemselves() {
    assertThat(eval("TRUE")).isEqualTo(CellValue.bool(true));
    assertThat(eval("FALSE")).isEqualTo(CellValue.bool(false));
    assertThat(eval("\"a\\nb\"")).isEqualTo(text("a\nb"));
    assertThat(eval("12.5")).isEqualTo(num(12.5));
  }
}
