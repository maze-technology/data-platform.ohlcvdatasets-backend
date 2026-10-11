package tech.maze.data.ohlcvdatasets.backend.domain.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OhlcvDataset;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OhlcvDatasetMarket;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OrderedOhlcvData;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.out.LoadOhlcvDatasetPort;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.out.LoadOrderedOhlcvDataPort;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.out.OhlcvDatasetStoragePort;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.out.SaveOhlcvDatasetPort;

@ExtendWith(MockitoExtension.class)
class SealOhlcvDatasetServiceTest {
  @Mock
  private LoadOhlcvDatasetPort loadOhlcvDatasetPort;
  @Mock
  private LoadOrderedOhlcvDataPort loadOrderedOhlcvDataPort;
  @Mock
  private OhlcvDatasetStoragePort ohlcvDatasetStoragePort;
  @Mock
  private SaveOhlcvDatasetPort saveOhlcvDatasetPort;

  @InjectMocks
  private SealOhlcvDatasetService service;

  @Test
  void sealsDatasetAndPersistsStorageKey() {
    final UUID id = UUID.randomUUID();
    final OhlcvDataset dataset = sample(id, null);
    final List<OrderedOhlcvData> rows = List.of(new OrderedOhlcvData(id, 0, new byte[] {9}));

    when(loadOhlcvDatasetPort.findById(id)).thenReturn(Optional.of(dataset));
    when(loadOrderedOhlcvDataPort.findByDatasetId(id)).thenReturn(rows);
    when(ohlcvDatasetStoragePort.storeDataset(eq(dataset), eq(rows))).thenReturn("ohlcv-datasets/" + id + ".parquet");
    when(saveOhlcvDatasetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

    final OhlcvDataset sealed = service.seal(id);

    assertThat(sealed.longTermStorageKey()).isEqualTo("ohlcv-datasets/" + id + ".parquet");
    verify(saveOhlcvDatasetPort).save(any(OhlcvDataset.class));
  }

  @Test
  void returnsExistingWhenAlreadySealed() {
    final UUID id = UUID.randomUUID();
    final OhlcvDataset dataset = sample(id, "already/key.parquet");
    when(loadOhlcvDatasetPort.findById(id)).thenReturn(Optional.of(dataset));

    assertThat(service.seal(id)).isSameAs(dataset);
  }

  @Test
  void rejectsEmptyOrderedData() {
    final UUID id = UUID.randomUUID();
    when(loadOhlcvDatasetPort.findById(id)).thenReturn(Optional.of(sample(id, null)));
    when(loadOrderedOhlcvDataPort.findByDatasetId(id)).thenReturn(List.of());

    assertThatThrownBy(() -> service.seal(id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot seal");
  }

  private OhlcvDataset sample(UUID id, String key) {
    return new OhlcvDataset(
        id,
        "binance",
        Duration.ofMinutes(1),
        Instant.parse("2024-01-01T00:00:00Z"),
        Instant.parse("2024-01-01T01:00:00Z"),
        1,
        Instant.parse("2024-01-01T01:00:00Z"),
        new OhlcvDatasetMarket("SPOT", "binance", "BTC", "USDT"),
        key
    );
  }
}
