package tech.maze.data.ohlcvdatasets.backend.api.eventstream;

import com.google.protobuf.Empty;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Value;
import io.cloudevents.CloudEvent;
import io.cloudevents.CloudEventData;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.maze.commons.eventstream.EventSender;
import tech.maze.commons.eventstream.MazeEventProperties;
import tech.maze.data.ohlcvdatasets.backend.AppProperties;
import tech.maze.data.ohlcvdatasets.backend.domain.usecases.IngestArchiveChunkService;
import tech.maze.data.ohlcvdatasets.backend.domain.usecases.IngestArchiveChunkService.ArchiveBar;
import tech.maze.data.ohlcvdatasets.backend.domain.usecases.IngestArchiveChunkService.ArchiveChunk;

/**
 * Event stream configuration for OHLCV datasets processing.
 */
@Configuration
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@EnableConfigurationProperties({MazeEventProperties.class, AppProperties.class})
@Slf4j
public class OhlcvDatasetsEventStreamConfiguration {
  EventSender eventSender;
  AppProperties appProperties;
  IngestArchiveChunkService ingestArchiveChunkService;

  /**
   * Handles sync / track / untrack control-plane events.
   */
  @Bean
  public Consumer<CloudEvent> ohlcvDatasetsConsumer() {
    return event -> {
      final String eventType = event.getType();

      if (OhlcvDatasetEventTypes.SYNC_OHLCVS_REQUEST.equals(eventType)) {
        handleSyncRequest(event);
        return;
      }

      if (OhlcvDatasetEventTypes.TRACK_REQUEST.equals(eventType)) {
        handleTrackRequest(event);
        return;
      }

      if (OhlcvDatasetEventTypes.UNTRACK_REQUEST.equals(eventType)) {
        handleUntrackRequest(event);
        return;
      }

      log.warn("Skipping event type {} (no handler registered)", eventType);
    };
  }

  /**
   * Ingests archive chunks published by data providers (e.g. First Rate).
   */
  @Bean
  public Consumer<CloudEvent> archiveChunkConsumer() {
    return event -> {
      if (!"tech.maze.ohlcvdatasets.archive.chunk".equals(event.getType())) {
        log.warn("Skipping event type {} (expected archive.chunk)", event.getType());
        return;
      }
      final ArchiveChunk chunk = parseArchiveChunk(event);
      final UUID datasetId = ingestArchiveChunkService.ingest(chunk);
      log.info(
          "Ingested archive chunk for {}/{} into dataset {} (last={})",
          chunk.base(),
          chunk.quote(),
          datasetId,
          chunk.last()
      );
    };
  }

  private void handleSyncRequest(CloudEvent event) {
    log.info("Received SyncOHLCVs event with id {}", event.getId());
    final String correlationId = Optional.ofNullable(eventSender.resolveCorrelationId(event))
        .filter(id -> !id.isBlank())
        .orElseGet(() -> event.getId() == null ? UUID.randomUUID().toString() : event.getId());
    final String fetchTopic = appProperties.getSync().getOhlcvsFetchRequestTopic();

    for (AppProperties.ArchiveMarket market : appProperties.getSync().getArchiveMarkets()) {
      if (market.getTicker() == null || market.getTicker().isBlank()) {
        continue;
      }
      final String json = "{"
          + "\"listingType\":\"" + market.getListingType() + "\","
          + "\"ticker\":\"" + market.getTicker() + "\","
          + "\"quote\":\"" + market.getQuote() + "\","
          + "\"timeframeSeconds\":" + market.getTimeframeSeconds()
          + "}";
      final Value payload = Value.newBuilder().setStringValue(json).build();
      final boolean sent = eventSender.send(
          fetchTopic,
          payload,
          "tech.maze.ohlcvs.fetch.request",
          Map.of(EventSender.CORRELATION_ID_EXTENSION, correlationId)
      );
      if (!sent) {
        log.error("Failed to fan-out OHLCV fetch for {}", market.getTicker());
      }
    }

    sendReplyIfPresent(event, Empty.getDefaultInstance());
  }

  private void handleTrackRequest(CloudEvent event) {
    log.info("Received TrackRequest event with id {}", event.getId());
    sendReplyIfPresent(event, Empty.getDefaultInstance());
  }

  private void handleUntrackRequest(CloudEvent event) {
    log.info("Received UntrackRequest event with id {}", event.getId());
    sendReplyIfPresent(event, Empty.getDefaultInstance());
  }

  private void sendReplyIfPresent(CloudEvent event, com.google.protobuf.Message response) {
    final String replyTo = eventSender.resolveReplyTo(event);
    if (replyTo == null || replyTo.isBlank()) {
      return;
    }

    final boolean sent = eventSender.send(replyTo, response);
    if (!sent) {
      log.error("Failed to dispatch response for event {}", event.getId());
    }
  }

  private ArchiveChunk parseArchiveChunk(CloudEvent event) {
    final CloudEventData data = event.getData();
    if (data == null) {
      throw new IllegalArgumentException("archive chunk payload is required");
    }
    String json;
    try {
      final Value value = Value.parseFrom(data.toBytes());
      json = value.hasStringValue()
          ? value.getStringValue()
          : new String(data.toBytes(), StandardCharsets.UTF_8);
    } catch (InvalidProtocolBufferException ex) {
      json = new String(data.toBytes(), StandardCharsets.UTF_8);
    }
    return parseChunkJson(json);
  }

  static ArchiveChunk parseChunkJson(String json) {
    final String dataProvider = required(json, "dataProvider");
    final String listingType = optional(json, "listingType").orElse("stock");
    final String exchange = optional(json, "exchange").orElse("firstrate");
    final String base = required(json, "base");
    final String quote = optional(json, "quote").orElse("USD");
    final String marketType = optional(json, "marketType").orElse("SPOT");
    final long timeframeSeconds = Long.parseLong(
        optional(json, "timeframeSeconds").orElse("86400")
    );
    final int positionStart = Integer.parseInt(
        optional(json, "positionStart").orElse("0")
    );
    final boolean last = Boolean.parseBoolean(optional(json, "isLast").orElse("false"));
    final List<ArchiveBar> bars = parseBars(json);
    return new ArchiveChunk(
        dataProvider,
        listingType,
        exchange,
        base,
        quote,
        marketType,
        timeframeSeconds,
        positionStart,
        last,
        bars
    );
  }

  private static List<ArchiveBar> parseBars(String json) {
    final List<ArchiveBar> bars = new ArrayList<>();
    final int barsIdx = json.indexOf("\"bars\"");
    if (barsIdx < 0) {
      return bars;
    }
    final int arrayStart = json.indexOf('[', barsIdx);
    final int arrayEnd = json.indexOf(']', arrayStart);
    if (arrayStart < 0 || arrayEnd < 0) {
      return bars;
    }
    final String array = json.substring(arrayStart + 1, arrayEnd);
    int cursor = 0;
    while (true) {
      final int objStart = array.indexOf('{', cursor);
      if (objStart < 0) {
        break;
      }
      final int objEnd = array.indexOf('}', objStart);
      if (objEnd < 0) {
        break;
      }
      final String obj = array.substring(objStart, objEnd + 1);
      bars.add(new ArchiveBar(
          required(obj, "open"),
          required(obj, "high"),
          required(obj, "low"),
          required(obj, "close"),
          required(obj, "volume"),
          Long.parseLong(required(obj, "createdAtEpochMs"))
      ));
      cursor = objEnd + 1;
    }
    return bars;
  }

  private static String required(String json, String key) {
    return optional(json, key)
        .orElseThrow(() -> new IllegalArgumentException(key + " is required"));
  }

  private static Optional<String> optional(String json, String key) {
    final String needle = "\"" + key + "\"";
    final int keyIdx = json.indexOf(needle);
    if (keyIdx < 0) {
      return Optional.empty();
    }
    final int colon = json.indexOf(':', keyIdx + needle.length());
    if (colon < 0) {
      return Optional.empty();
    }
    int i = colon + 1;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
      i++;
    }
    if (i >= json.length()) {
      return Optional.empty();
    }
    if (json.charAt(i) == '"') {
      final int end = json.indexOf('"', i + 1);
      if (end < 0) {
        return Optional.empty();
      }
      return Optional.of(json.substring(i + 1, end));
    }
    int end = i;
    while (end < json.length()
        && (Character.isDigit(json.charAt(end))
        || json.charAt(end) == '-'
        || json.charAt(end) == '.'
        || json.charAt(end) == 't'
        || json.charAt(end) == 'r'
        || json.charAt(end) == 'u'
        || json.charAt(end) == 'e'
        || json.charAt(end) == 'f'
        || json.charAt(end) == 'a'
        || json.charAt(end) == 'l'
        || json.charAt(end) == 's')) {
      end++;
    }
    return Optional.of(json.substring(i, end));
  }
}
