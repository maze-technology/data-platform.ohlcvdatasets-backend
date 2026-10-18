package tech.maze.data.ohlcvdatasets.backend;

import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the application.
 */
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
@ConfigurationProperties(prefix = AppProperties.PREFIX)
public class AppProperties {
  public static final String PREFIX = "maze";

  String name;

  /**
   * Sync/archive fan-out configuration.
   */
  Sync sync = new Sync();

  /**
   * Sync properties for requesting OHLCV archives from data providers.
   */
  @Data
  @FieldDefaults(level = AccessLevel.PRIVATE)
  public static class Sync {
    /**
     * Topic where providers listen for OHLCV fetch/archive requests.
     */
    String ohlcvsFetchRequestTopic = "ohlcvs.fetch.request";

    /**
     * Markets to archive when SyncOHLCVs is received (MVP seed list).
     */
    List<ArchiveMarket> archiveMarkets = new ArrayList<>();
  }

  /**
   * One market to archive on SyncOHLCVs.
   */
  @Data
  @FieldDefaults(level = AccessLevel.PRIVATE)
  public static class ArchiveMarket {
    String listingType = "stock";
    String ticker;
    String quote = "USD";
    long timeframeSeconds = 86400;
  }
}
