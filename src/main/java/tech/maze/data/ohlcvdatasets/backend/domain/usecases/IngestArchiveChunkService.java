package tech.maze.data.ohlcvdatasets.backend.domain.usecases;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OhlcvDataset;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OhlcvDatasetMarket;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OrderedOhlcvData;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.in.SaveOhlcvDatasetUseCase;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.in.SaveOrderedOhlcvDataUseCase;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.in.SealOhlcvDatasetUseCase;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.out.SearchOhlcvDatasetsPort;

/**
 * Ingests First Rate (or other provider) archive chunks into Postgres and seals on isLast.
 */
@Service
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Slf4j
public class IngestArchiveChunkService {
  SaveOhlcvDatasetUseCase saveOhlcvDatasetUseCase;
  SaveOrderedOhlcvDataUseCase saveOrderedOhlcvDataUseCase;
  SealOhlcvDatasetUseCase sealOhlcvDatasetUseCase;
  SearchOhlcvDatasetsPort searchOhlcvDatasetsPort;

  /**
   * Applies one archive chunk.
   *
   * @param chunk parsed archive chunk
   * @return dataset id
   */
  @Transactional
  public UUID ingest(ArchiveChunk chunk) {
    final OhlcvDatasetMarket market = new OhlcvDatasetMarket(
        chunk.marketType(),
        chunk.exchange(),
        chunk.base(),
        chunk.quote()
    );
    final Duration timeframe = Duration.ofSeconds(chunk.timeframeSeconds());

    final OhlcvDataset dataset = findOrCreateDataset(chunk, market, timeframe);
    final List<OrderedOhlcvData> rows = new ArrayList<>();
    for (int i = 0; i < chunk.bars().size(); i++) {
      final ArchiveBar bar = chunk.bars().get(i);
      final int position = chunk.positionStart() + i;
      rows.add(new OrderedOhlcvData(dataset.id(), position, bar.toJsonBytes()));
    }
    if (!rows.isEmpty()) {
      saveOrderedOhlcvDataUseCase.saveAll(rows);
    }

    final int newOrderCount = Math.max(
        dataset.orderCount(),
        chunk.positionStart() + chunk.bars().size()
    );
    final Instant endAt = chunk.bars().isEmpty()
        ? dataset.endAt()
        : Instant.ofEpochMilli(chunk.bars().get(chunk.bars().size() - 1).createdAtEpochMs());
    final OhlcvDataset updated = new OhlcvDataset(
        dataset.id(),
        dataset.dataProvider(),
        dataset.timeframe(),
        dataset.startAt(),
        endAt,
        newOrderCount,
        dataset.createdAt(),
        dataset.market(),
        dataset.longTermStorageKey()
    );
    saveOhlcvDatasetUseCase.save(updated);

    if (chunk.last()) {
      try {
        sealOhlcvDatasetUseCase.seal(dataset.id());
      } catch (RuntimeException ex) {
        log.warn("Seal skipped/failed for dataset {}: {}", dataset.id(), ex.getMessage());
      }
    }
    return dataset.id();
  }

  private OhlcvDataset findOrCreateDataset(
      ArchiveChunk chunk,
      OhlcvDatasetMarket market,
      Duration timeframe
  ) {
    final Instant startAt = chunk.bars().isEmpty()
        ? Instant.EPOCH
        : Instant.ofEpochMilli(chunk.bars().get(0).createdAtEpochMs());

    final Optional<OhlcvDataset> existing = searchOhlcvDatasetsPort.findAll().stream()
        .filter(d -> d.dataProvider().equals(chunk.dataProvider()))
        .filter(d -> d.timeframe().equals(timeframe))
        .filter(d -> d.market().base().equalsIgnoreCase(market.base()))
        .filter(d -> d.market().quote().equalsIgnoreCase(market.quote()))
        .filter(d -> d.longTermStorageKey() == null || d.longTermStorageKey().isBlank())
        .findFirst();

    if (existing.isPresent()) {
      return existing.get();
    }

    final OhlcvDataset created = new OhlcvDataset(
        UUID.randomUUID(),
        chunk.dataProvider(),
        timeframe,
        startAt,
        startAt,
        0,
        Instant.now(),
        market,
        null
    );
    return saveOhlcvDatasetUseCase.save(created);
  }

  /**
   * Archive chunk envelope (JSON from firstrate provider).
   */
  public record ArchiveChunk(
      String dataProvider,
      String listingType,
      String exchange,
      String base,
      String quote,
      String marketType,
      long timeframeSeconds,
      int positionStart,
      boolean last,
      List<ArchiveBar> bars
  ) {
  }

  /**
   * One bar inside an archive chunk.
   */
  public record ArchiveBar(
      String open,
      String high,
      String low,
      String close,
      String volume,
      long createdAtEpochMs
  ) {
    byte[] toJsonBytes() {
      final String json = "{"
          + "\"open\":\"" + open + "\","
          + "\"high\":\"" + high + "\","
          + "\"low\":\"" + low + "\","
          + "\"close\":\"" + close + "\","
          + "\"volume\":\"" + volume + "\","
          + "\"createdAtEpochMs\":" + createdAtEpochMs
          + "}";
      return json.getBytes(StandardCharsets.UTF_8);
    }
  }
}
