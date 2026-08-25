package io.akka.teable.api;

import akka.NotUsed;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import io.akka.teable.application.TableEntity;
import io.akka.teable.application.TableState;

import java.time.Duration;
import java.util.List;

/**
 * The stream the interface subscribes to, in place of the original's socket (RENDERING.md R1).
 *
 * <p>The first frame is the table's whole current state, so a view renders without a second
 * request, and every later frame is the whole state again rather than a delta. That is the
 * decision R1.3 turns on: a client that reconnects receives current state on its first frame
 * and needs no replay position, so a gap costs a full re-read and never a missing cell. The
 * cost is that a subscriber cannot tell which cells changed, which the original's socket could
 * say — declared as a divergence.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("")
public class TableStreamEndpoint {

  private static final Duration TICK = Duration.ofMillis(100);

  private final ComponentClient componentClient;

  public TableStreamEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/api/table/{tableId}/stream")
  public HttpResponse stream(String tableId) {
    Source<TableState, NotUsed> source =
        Source.tick(Duration.ZERO, TICK, "")
            .map(ignored -> snapshot(tableId))
            .statefulMapConcat(
                () -> {
                  var previous = new TableState[1];
                  return state -> {
                    if (state.equals(previous[0])) {
                      return List.of();
                    }
                    previous[0] = state;
                    return List.of(state);
                  };
                })
            .mapMaterializedValue(ignored -> NotUsed.getInstance());

    return HttpResponses.serverSentEvents(source);
  }

  private TableState snapshot(String tableId) {
    return componentClient.forEventSourcedEntity(tableId).method(TableEntity::read).invoke();
  }
}
