/**
 * Copyright From 2025, IoTDB.
 *
 * <p>Mix.java
 */
package org.apache.iotdb.mybatis.plugin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * table: test.mix of model class
 *
 * @author IoTDB
 * @date 2025-06-04 16:07:49
 */
@Schema(title = "test.mix", description = "")
@Data
public class Mix implements Serializable {
  /** class serial version id */
  private static final long serialVersionUID = 1L;

  private Date time;

  private String deviceId;

  private String region;

  private String plantId;

  private String modelId;

  private String maintenance;

  private Double temperature;

  private Double humidity;

  private Boolean status;

  private Date arrivalTime;
}
