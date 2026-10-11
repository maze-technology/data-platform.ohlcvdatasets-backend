package tech.maze.data.ohlcvdatasets.backend.infrastructure.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.OutputFile;
import org.springframework.stereotype.Component;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OhlcvDataset;
import tech.maze.data.ohlcvdatasets.backend.domain.models.OrderedOhlcvData;

/**
 * Writes OHLCV datasets to Apache Parquet (Avro-backed) payloads.
 */
@Component
@Slf4j
public class ParquetOhlcvDatasetWriter {
  private static final Schema SCHEMA = new Schema.Parser().parse("""
      {
        "type": "record",
        "name": "OrderedOhlcv",
        "namespace": "tech.maze.data.ohlcvdatasets",
        "fields": [
          {"name": "datasetId", "type": "string"},
          {"name": "position", "type": "int"},
          {"name": "data", "type": "bytes"},
          {"name": "dataProvider", "type": "string"},
          {"name": "marketType", "type": "string"},
          {"name": "exchange", "type": ["null", "string"], "default": null},
          {"name": "base", "type": "string"},
          {"name": "quote", "type": "string"},
          {"name": "timeframeSeconds", "type": "long"},
          {"name": "startAtEpochMs", "type": "long"},
          {"name": "endAtEpochMs", "type": "long"}
        ]
      }
      """);

  /**
   * Serializes a dataset and its ordered entries to a Parquet byte payload.
   *
   * @param dataset dataset metadata
   * @param orderedData ordered OHLCV data entries
   * @return Parquet file bytes
   */
  public byte[] write(OhlcvDataset dataset, List<OrderedOhlcvData> orderedData) {
    Objects.requireNonNull(dataset, "dataset must not be null");
    Objects.requireNonNull(orderedData, "orderedData must not be null");

    Path tempFile = null;
    try {
      tempFile = Files.createTempFile("ohlcv-dataset-", ".parquet");
      // LocalOutputFile.create() refuses an existing path; temp file is only a name reservation.
      Files.deleteIfExists(tempFile);
      final OutputFile outputFile = new LocalOutputFile(tempFile);

      try (var writer = AvroParquetWriter.<GenericRecord>builder(outputFile)
          .withSchema(SCHEMA)
          .withConf(new PlainParquetConfiguration())
          .withCompressionCodec(CompressionCodecName.SNAPPY)
          .build()) {
        for (OrderedOhlcvData entry : orderedData) {
          writer.write(toRecord(dataset, entry));
        }
      }

      return Files.readAllBytes(tempFile);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to write OHLCV dataset Parquet payload", ex);
    } finally {
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (IOException ex) {
          log.warn("Failed to delete temp Parquet file {}", tempFile, ex);
        }
      }
    }
  }

  private GenericRecord toRecord(OhlcvDataset dataset, OrderedOhlcvData entry) {
    final GenericRecord record = new GenericData.Record(SCHEMA);
    final UUID datasetId = dataset.id() == null ? entry.datasetId() : dataset.id();
    record.put("datasetId", datasetId.toString());
    record.put("position", entry.position());
    record.put("data", ByteBuffer.wrap(entry.data()));
    record.put("dataProvider", dataset.dataProvider());
    record.put("marketType", dataset.market().type());
    record.put("exchange", dataset.market().exchange());
    record.put("base", dataset.market().base());
    record.put("quote", dataset.market().quote());
    record.put("timeframeSeconds", dataset.timeframe().getSeconds());
    record.put("startAtEpochMs", dataset.startAt().toEpochMilli());
    record.put("endAtEpochMs", dataset.endAt().toEpochMilli());
    return record;
  }
}
