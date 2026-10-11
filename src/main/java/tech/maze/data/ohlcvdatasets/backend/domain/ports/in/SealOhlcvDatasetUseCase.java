package tech.maze.data.ohlcvdatasets.backend.domain.ports.in;

import java.util.UUID;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OhlcvDataset;

/**
 * Seals a pending OHLCV dataset: write Parquet to long-term storage and persist the key.
 */
public interface SealOhlcvDatasetUseCase {
  /**
   * Seal a dataset by id.
   *
   * @param datasetId dataset identifier
   * @return sealed dataset with long-term storage key set
   */
  OhlcvDataset seal(UUID datasetId);
}
