package tech.maze.data.ohlcvdatasets.backend.domain.usecases;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OhlcvDataset;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OrderedOhlcvData;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.in.SealOhlcvDatasetUseCase;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.out.LoadOhlcvDatasetPort;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.out.LoadOrderedOhlcvDataPort;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.out.OhlcvDatasetStoragePort;
import tech.maze.data.ohlcvdatasets.backend.domain.ports.out.SaveOhlcvDatasetPort;

/**
 * Seals pending Postgres OHLCV rows into a Parquet object in long-term storage.
 */
@Service
@RequiredArgsConstructor
public class SealOhlcvDatasetService implements SealOhlcvDatasetUseCase {
  private final LoadOhlcvDatasetPort loadOhlcvDatasetPort;
  private final LoadOrderedOhlcvDataPort loadOrderedOhlcvDataPort;
  private final OhlcvDatasetStoragePort ohlcvDatasetStoragePort;
  private final SaveOhlcvDatasetPort saveOhlcvDatasetPort;

  @Override
  @Transactional
  public OhlcvDataset seal(UUID datasetId) {
    Objects.requireNonNull(datasetId, "datasetId must not be null");

    final OhlcvDataset dataset = loadOhlcvDatasetPort.findById(datasetId)
        .orElseThrow(() -> new IllegalArgumentException("dataset not found: " + datasetId));

    if (dataset.longTermStorageKey() != null && !dataset.longTermStorageKey().isBlank()) {
      return dataset;
    }

    final List<OrderedOhlcvData> orderedData = loadOrderedOhlcvDataPort.findByDatasetId(datasetId);
    if (orderedData.isEmpty()) {
      throw new IllegalStateException(
          "cannot seal dataset without ordered OHLCV rows: " + datasetId
      );
    }

    final String storageKey = ohlcvDatasetStoragePort.storeDataset(dataset, orderedData);
    final OhlcvDataset sealed = new OhlcvDataset(
        dataset.id(),
        dataset.dataProvider(),
        dataset.timeframe(),
        dataset.startAt(),
        dataset.endAt(),
        dataset.orderCount(),
        dataset.createdAt(),
        dataset.market(),
        storageKey
    );
    return saveOhlcvDatasetPort.save(sealed);
  }
}
