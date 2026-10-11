package tech.maze.data.ohlcvdatasets.backend.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OhlcvDataset;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OhlcvDatasetMarket;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OrderedOhlcvData;

class ParquetOhlcvDatasetWriterTest {
  private final ParquetOhlcvDatasetWriter writer = new ParquetOhlcvDatasetWriter();

  @Test
  void writesNonEmptyParquetPayload() {
    final UUID id = UUID.randomUUID();
    final OhlcvDataset dataset = new OhlcvDataset(
        id,
        "binance",
        Duration.ofMinutes(1),
        Instant.parse("2024-01-01T00:00:00Z"),
        Instant.parse("2024-01-01T01:00:00Z"),
        2,
        Instant.parse("2024-01-01T01:00:00Z"),
        new OhlcvDatasetMarket("SPOT", "binance", "BTC", "USDT"),
        null
    );
    final List<OrderedOhlcvData> rows = List.of(
        new OrderedOhlcvData(id, 0, new byte[] {1, 2, 3}),
        new OrderedOhlcvData(id, 1, new byte[] {4, 5, 6})
    );

    final byte[] payload = writer.write(dataset, rows);

    assertThat(payload).isNotEmpty();
    // Parquet magic / magic footer marker
    assertThat(new String(payload, 0, 4)).isEqualTo("PAR1");
  }
}
