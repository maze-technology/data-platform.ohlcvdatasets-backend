package tech.maze.data.ohlcvdatasets.backend.api.eventstream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.maze.data.ohlcvdatasets.backend.domain.usecases.IngestArchiveChunkService.ArchiveChunk;

class OhlcvDatasetsEventStreamConfigurationTest {
  @Test
  void parsesArchiveChunkJson() {
    final String json = """
        {
          "dataProvider":"firstrate",
          "listingType":"stock",
          "exchange":"firstrate",
          "base":"AAPL",
          "quote":"USD",
          "marketType":"SPOT",
          "timeframeSeconds":86400,
          "positionStart":0,
          "isLast":true,
          "bars":[
            {"open":"100","high":"110","low":"90","close":"105","volume":"1000","createdAtEpochMs":1704153600000}
          ]
        }
        """;
    final ArchiveChunk chunk = OhlcvDatasetsEventStreamConfiguration.parseChunkJson(json);
    assertThat(chunk.dataProvider()).isEqualTo("firstrate");
    assertThat(chunk.base()).isEqualTo("AAPL");
    assertThat(chunk.last()).isTrue();
    assertThat(chunk.bars()).hasSize(1);
    assertThat(chunk.bars().get(0).close()).isEqualTo("105");
  }
}
