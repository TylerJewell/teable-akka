package io.akka.teable.api;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.teable.domain.CellValue;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** RENDERING.md R1, over the real event stream. */
public class TableStreamIntegrationTest extends TestKitSupport {

  private static final Duration WAIT = Duration.ofSeconds(20);

  private static final String QTY = "fldQTYQTYQTYQTYQTY0";
  private static final String PRICE = "fldPRICEPRICEPRICE0";
  private static final String TOTAL = "fldTOTALTOTALTOTAL0";

  private void defineChain(String tableId) {
    httpClient.POST("/api/table/" + tableId + "/field")
        .withRequestBody(new TableEndpoint.FieldRequest(QTY, "qty", "value", "NUMBER", null))
        .invoke();
    httpClient.POST("/api/table/" + tableId + "/field")
        .withRequestBody(new TableEndpoint.FieldRequest(PRICE, "price", "value", "NUMBER", null))
        .invoke();
    httpClient.POST("/api/table/" + tableId + "/field")
        .withRequestBody(
            new TableEndpoint.FieldRequest(
                TOTAL, "total", "formula", null, "{" + QTY + "} * {" + PRICE + "}"))
        .invoke();
  }

  private void putRecord(String tableId, String recordId, double qty, double price) {
    httpClient.POST("/api/table/" + tableId + "/record")
        .withRequestBody(
            new TableEndpoint.RecordRequest(
                recordId, Map.of(QTY, CellValue.number(qty), PRICE, CellValue.number(price))))
        .invoke();
  }

  @Test
  public void theFirstFrameCarriesCurrentStateSoAViewRendersWithoutASecondRequest() {
    defineChain("strA");
    putRecord("strA", "rec1", 2, 10);

    var frames =
        testKit.getSelfSseRouteTester().receiveFirstN("/api/table/strA/stream", 1, WAIT);

    assertThat(frames).hasSize(1);
    assertThat(frames.get(0).getData()).contains("20");
  }

  @Test
  public void aRecomputationArrivesWithoutTheClientAskingAgain() throws Exception {
    defineChain("strB");
    putRecord("strB", "rec1", 2, 10);

    var frames =
        CompletableFuture.supplyAsync(
            () -> testKit.getSelfSseRouteTester().receiveFirstN("/api/table/strB/stream", 2, WAIT));

    Thread.sleep(500);
    httpClient.PUT("/api/table/strB/record/rec1/cell")
        .withRequestBody(new TableEndpoint.CellRequest(QTY, CellValue.number(7)))
        .invoke();

    var received = frames.get(30, TimeUnit.SECONDS);
    assertThat(received).hasSize(2);
    assertThat(received.get(1).getData()).contains("70");
  }

  @Test
  public void aSubscriberReconnectingLaterGetsCurrentStateRatherThanADiff() {
    defineChain("strC");
    putRecord("strC", "rec1", 2, 10);
    httpClient.PUT("/api/table/strC/record/rec1/cell")
        .withRequestBody(new TableEndpoint.CellRequest(QTY, CellValue.number(7)))
        .invoke();

    // A subscriber that was not connected for the edit above still opens on the post-edit
    // state, which is what makes a dropped stream cost a re-read rather than a missing cell.
    var frames =
        testKit.getSelfSseRouteTester().receiveFirstN("/api/table/strC/stream", 1, WAIT);

    assertThat(frames).hasSize(1);
    assertThat(frames.get(0).getData()).contains("70");
  }

  @Test
  public void aSubscriberToAnotherTableNeverSeesThisOne() throws Exception {
    defineChain("strD");
    defineChain("strE");
    putRecord("strE", "recE", 1, 1);

    var framesForE =
        CompletableFuture.supplyAsync(
            () -> testKit.getSelfSseRouteTester().receiveFirstN("/api/table/strE/stream", 1, WAIT));

    Thread.sleep(500);
    putRecord("strD", "recD", 9, 9);

    var received = framesForE.get(30, TimeUnit.SECONDS);
    assertThat(received).hasSize(1);
    assertThat(received.get(0).getData()).doesNotContain("recD");
  }
}
