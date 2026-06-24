/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iotdb.extras.thingsboard.table;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the IoTDB Table Mode DAO backend. Bound from {@code iotdb.*} in
 * Spring application config.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "iotdb")
public class IoTDBTableConfig {

  @NotBlank private String host = "127.0.0.1";

  @Min(1)
  @Max(65535)
  private int port = 6667;

  @Min(1)
  @Max(1024)
  private int sessionPoolSize = 8;

  /**
   * Target IoTDB database. The bootstrap splices this name verbatim into {@code CREATE DATABASE} /
   * {@code USE} DDL, so it is constrained to the IoTDB identifier rule (letter or underscore first,
   * then letters, digits or underscores) to reject names that could break or inject into that DDL.
   */
  @NotBlank
  @Pattern(
      regexp = "^[A-Za-z_][A-Za-z0-9_]*$",
      message =
          "must be a valid IoTDB identifier: a letter or underscore followed by letters, digits or"
              + " underscores")
  private String database = "thingsboard";

  /**
   * Affects ThingsBoard storage data-point accounting only; it does not configure IoTDB physical
   * retention.
   */
  @Min(-1)
  private long defaultTtlMs = -1L;

  @NotBlank private String username = "root";

  @NotBlank private String password = "root";

  @Min(100)
  private int connectionTimeoutMs = 5000;

  private boolean enableCompression = false;

  @Valid private Ts ts = new Ts();

  @Data
  public static class Ts {
    /**
     * Explicit opt-in for the raw-only backend. This backend currently implements write, raw read,
     * and delete only; time-bucketed aggregation is outside the current scope, so it is not
     * production-complete and must be enabled deliberately.
     */
    private boolean experimentalRawOnly = false;

    @Valid private Save save = new Save();
    @Valid private Read read = new Read();
  }

  @Data
  public static class Read {
    @Min(1)
    private int threads = 4;

    @Min(1)
    private int queueCapacity = 10000;
  }

  @Data
  public static class Save {
    @Min(1)
    private int batchSize = 500;

    @Min(1)
    private long maxLingerMs = 20L;

    @Min(1)
    private int queueCapacity = 50000;

    @Min(1)
    private long shutdownDrainTimeoutMs = 5000L;

    /**
     * Number of dedicated flush workers. The writer uses a single drain/flush worker by design, so
     * this is fixed at 1. It is surfaced as validated config (constrained to exactly 1) so an
     * operator who tries to raise it gets a clear, fail-fast binding error rather than a silently
     * ignored setting.
     */
    @Min(1)
    @Max(
        value = 1,
        message =
            "iotdb.ts.save.flush-threads is fixed at 1 (the writer uses a single dedicated flush"
                + " worker)")
    private int flushThreads = 1;

    @Min(1)
    private int retryMaxAttempts = 3;

    @Min(0)
    private long retryInitialBackoffMs = 50L;

    @Min(0)
    private long retryMaxBackoffMs = 1000L;
  }
}
