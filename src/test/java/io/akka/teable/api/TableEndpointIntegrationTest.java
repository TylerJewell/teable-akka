package io.akka.teable.api;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.teable.application.TableEntity;
import io.akka.teable.application.TableState;
import io.akka.teable.domain.CellValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the port the way something outside a test drives it: over HTTP, through the wire
 * serializer, rather than through the ComponentClient. Both halves matter -- a command whose
 * shape the serializer refuses passes every ComponentClient-based test, and a query parameter
 * nothing reads does too.
 */
public class TableEndpointIntegrationTest extends TestKitSupport {

  private static final String QTY = "fldQTYQTYQTYQTYQTY0";
  private static final String PRICE = "fldPRICEPRICEPRICE0";
  private static final String TOTAL = "fldTOTALTOTALTOTAL0";
  private static final String TAXED = "fldTAXEDTAXEDTAXED0";

  private TableState post(String path, Object body) {
    return httpClient.POST(path).withRequestBody(body).responseBodyAs(TableState.class)
        .invoke().body();
  }

  private void defineChain(String tableId) {
    post("/api/table/" + tableId + "/field",
        new TableEndpoint.FieldRequest(QTY, "qty", "value", "NUMBER", null));
    post("/api/table/" + tableId + "/field",
        new TableEndpoint.FieldRequest(PRICE, "price", "value", "NUMBER", null));
    post("/api/table/" + tableId + "/field",
        new TableEndpoint.FieldRequest(
            TOTAL, "total", "formula", null, "{" + QTY + "} * {" + PRICE + "}"));
    post("/api/table/" + tableId + "/field",
        new TableEndpoint.FieldRequest(TAXED, "taxed", "formula", null, "{" + TOTAL + "} * 1.1"));
  }

  @Test
  public void aChainSettlesInOnePass() {
    defineChain("tblA");
    var state =
        post("/api/table/tblA/record",
            new TableEndpoint.RecordRequest(
                "rec1", Map.of(QTY, CellValue.number(2), PRICE, CellValue.number(10))));

    assertThat(state.records().get("rec1").get(TOTAL)).isEqualTo(CellValue.number(20));
    assertThat(state.records().get("rec1").get(TAXED)).isEqualTo(CellValue.number(22));
  }

  @Test
  public void oneCellEditTravelsTheWholeChainOverHttp() {
    defineChain("tblB");
    post("/api/table/tblB/record",
        new TableEndpoint.RecordRequest(
            "rec1", Map.of(QTY, CellValue.number(2), PRICE, CellValue.number(10))));

    var state =
        httpClient
            .PUT("/api/table/tblB/record/rec1/cell")
            .withRequestBody(new TableEndpoint.CellRequest(QTY, CellValue.number(7)))
            .responseBodyAs(TableState.class)
            .invoke()
            .body();

    assertThat(state.records().get("rec1").get(TOTAL)).isEqualTo(CellValue.number(70));
    assertThat(state.records().get("rec1").get(TAXED)).isEqualTo(CellValue.number(77));
  }

  @Test
  public void theOrderIsReadableFromOutside() {
    defineChain("tblC");
    var order =
        httpClient.GET("/api/table/tblC/order").responseBodyAs(TableEntity.Order.class)
            .invoke().body();
    assertThat(order.fieldIds()).containsExactly(QTY, PRICE, TOTAL, TAXED);
  }

  @Test
  public void aRecordIdInTheQueryStringSelectsThatRecord() {
    defineChain("tblD");
    post("/api/table/tblD/record",
        new TableEndpoint.RecordRequest("rec1", Map.of(QTY, CellValue.number(2), PRICE,
            CellValue.number(10))));
    post("/api/table/tblD/record",
        new TableEndpoint.RecordRequest("rec2", Map.of(QTY, CellValue.number(3), PRICE,
            CellValue.number(5))));

    var all = httpClient.GET("/api/table/tblD/record").responseBodyAsListOf(Map.class)
        .invoke().body();
    var one = httpClient.GET("/api/table/tblD/record?recordId=rec2")
        .responseBodyAsListOf(Map.class).invoke().body();

    assertThat(all).hasSize(2);
    assertThat(one).hasSize(1);
  }

  @Test
  public void aCycleIsRefusedOverHttpAndTheTableIsLeftAsItWas() {
    defineChain("tblE");
    var response =
        httpClient
            .POST("/api/table/tblE/field")
            .withRequestBody(
                new TableEndpoint.FieldRequest(
                    TOTAL, "total", "formula", null, "{" + TAXED + "}"))
            .invoke();

    assertThat(response.status().intValue()).isEqualTo(400);
    assertThat(response.body().utf8String()).contains("Formula field dependency cycle detected");

    var order =
        httpClient.GET("/api/table/tblE/order").responseBodyAs(TableEntity.Order.class)
            .invoke().body();
    assertThat(order.fieldIds()).containsExactly(QTY, PRICE, TOTAL, TAXED);
  }

  @Test
  public void aFieldRedefinitionKeepsItsPlaceInTheOrder() {
    defineChain("tblG");
    post("/api/table/tblG/field",
        new TableEndpoint.FieldRequest(
            TOTAL, "total", "formula", null, "{" + QTY + "} + {" + PRICE + "}"));

    var order =
        httpClient.GET("/api/table/tblG/order").responseBodyAs(TableEntity.Order.class)
            .invoke().body();
    assertThat(order.fieldIds()).containsExactly(QTY, PRICE, TOTAL, TAXED);
  }

  @Test
  public void anUnknownValueTypeIsRefusedAsABadRequestRatherThanAServerError() {
    var response =
        httpClient
            .POST("/api/table/tblL/field")
            .withRequestBody(
                new TableEndpoint.FieldRequest("fldQQQQQQQQQQQQQQQQ", "q", "value", "COLOUR", null))
            .invoke();

    assertThat(response.status().intValue()).isEqualTo(400);
    assertThat(response.body().utf8String()).contains("NUMBER, TEXT or CHECKBOX");
  }

  @Test
  public void aFormulaNamingAFieldThatIsNotThereIsRefusedWithTheReason() {
    defineChain("tblM");
    var response =
        httpClient
            .POST("/api/table/tblM/field")
            .withRequestBody(
                new TableEndpoint.FieldRequest(
                    "fldZZZZZZZZZZZZZZZZ", "z", "formula", null, "{fldNOPENOPENOPENOPE}"))
            .invoke();

    assertThat(response.status().intValue()).isEqualTo(400);
    assertThat(response.body().utf8String()).contains("Formula field references not found");
  }

  @Test
  public void anExpressionThatDoesNotParseIsRefusedAsABadRequestRatherThanAServerError() {
    // The refusal comes out of the formula rather than out of the table, so it arrives as a
    // different exception type -- and an uncaught one reaches the caller as an unexpected
    // failure with a correlation id and no reason.
    var response =
        httpClient
            .POST("/api/table/tblN/field")
            .withRequestBody(
                new TableEndpoint.FieldRequest(
                    "fldBROKENBROKENBROKE", "b", "formula", null, "1 +"))
            .invoke();

    assertThat(response.status().intValue()).isEqualTo(400);
    assertThat(response.body().utf8String()).contains("parse error");
  }

  @Test
  public void anIdentifierThatIsNotAFieldIdIsRefusedAsABadRequest() {
    var response =
        httpClient
            .POST("/api/table/tblO/field")
            .withRequestBody(
                new TableEndpoint.FieldRequest(
                    "fldNAMEDNAMEDNAMEDN", "n", "formula", null, "{qty} * 2"))
            .invoke();

    assertThat(response.status().intValue()).isEqualTo(400);
    assertThat(response.body().utf8String()).contains("must use field IDs");
  }

  @Test
  public void everyCellKindSurvivesTheWire() {
    post("/api/table/tblH/field",
        new TableEndpoint.FieldRequest("fldTEXTTEXTTEXTTEXT", "s", "value", "TEXT", null));
    post("/api/table/tblH/field",
        new TableEndpoint.FieldRequest("fldFLAGFLAGFLAGFLAG", "b", "value", "CHECKBOX", null));
    post("/api/table/tblH/field",
        new TableEndpoint.FieldRequest(QTY, "qty", "value", "NUMBER", null));

    var state =
        post("/api/table/tblH/record",
            new TableEndpoint.RecordRequest(
                "rec1",
                Map.of(
                    "fldTEXTTEXTTEXTTEXT", CellValue.text("hello"),
                    "fldFLAGFLAGFLAGFLAG", CellValue.bool(true),
                    QTY, CellValue.number(-2.5))));

    var cells = state.records().get("rec1");
    assertThat(cells.get("fldTEXTTEXTTEXTTEXT")).isEqualTo(CellValue.text("hello"));
    assertThat(cells.get("fldFLAGFLAGFLAGFLAG")).isEqualTo(CellValue.bool(true));
    assertThat(cells.get(QTY)).isEqualTo(CellValue.number(-2.5));
  }

  @Test
  public void twoTablesDoNotShareState() {
    defineChain("tblI");
    defineChain("tblJ");
    post("/api/table/tblI/record",
        new TableEndpoint.RecordRequest("rec1", Map.of(QTY, CellValue.number(2), PRICE,
            CellValue.number(10))));

    var other = httpClient.GET("/api/table/tblJ").responseBodyAs(TableState.class).invoke().body();
    assertThat(other.records()).isEmpty();
  }

  @Test
  public void anEmptyTableAnswersAnEmptyOrder() {
    var order =
        httpClient.GET("/api/table/tblK/order").responseBodyAs(TableEntity.Order.class)
            .invoke().body();
    assertThat(order.fieldIds()).isEqualTo(List.of());
  }
}
