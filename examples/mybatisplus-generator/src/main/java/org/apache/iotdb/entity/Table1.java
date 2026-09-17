/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iotdb.entity;

import org.apache.iotdb.mybatis.type.IoTDBBlobTypeHandler;
import org.apache.iotdb.mybatis.type.IoTDBLocalDateTypeHandler;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** TIME and all TAGs identify a row; timestamp values use the server's configured precision. */
@Data
@TableName(value = "table1", autoResultMap = true)
public class Table1 {
  @TableField(value = "\"time\"")
  private Long time;

  @TableField(value = "\"region\"")
  private String region;

  @TableField(value = "\"plant_id\"")
  private String plantId;

  @TableField(value = "\"device_id\"")
  private String deviceId;

  @TableField(value = "\"model_id\"")
  private String modelId;

  @TableField(value = "\"maintenance\"")
  private String maintenance;

  @TableField(value = "\"temperature\"")
  private Float temperature;

  @TableField(value = "\"humidity\"")
  private Float humidity;

  @TableField(value = "\"status\"")
  private Boolean status;

  @TableField(value = "\"arrival_time\"")
  private Long arrivalTime;

  @TableField(value = "\"reading_date\"", typeHandler = IoTDBLocalDateTypeHandler.class)
  private java.time.LocalDate readingDate;

  @TableField(value = "\"payload\"", typeHandler = IoTDBBlobTypeHandler.class)
  private byte[] payload;
}
