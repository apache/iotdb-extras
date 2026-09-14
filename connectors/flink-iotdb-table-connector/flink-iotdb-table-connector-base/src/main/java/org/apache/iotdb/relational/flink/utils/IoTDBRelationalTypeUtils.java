/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.relational.flink.utils;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.types.DataType;
import org.apache.tsfile.enums.TSDataType;

/** Utilities for converting between Flink and IoTDB table-model data types. */
public final class IoTDBRelationalTypeUtils {

  private IoTDBRelationalTypeUtils() {}

  public static TSDataType toIoTDBDataType(DataType dataType) {
    switch (dataType.getLogicalType().getTypeRoot()) {
      case BOOLEAN:
        return TSDataType.BOOLEAN;
      case TINYINT:
      case SMALLINT:
      case INTEGER:
        return TSDataType.INT32;
      case BIGINT:
        return TSDataType.INT64;
      case FLOAT:
        return TSDataType.FLOAT;
      case DOUBLE:
        return TSDataType.DOUBLE;
      case CHAR:
      case VARCHAR:
        return TSDataType.STRING;
      case BINARY:
      case VARBINARY:
        return TSDataType.BLOB;
      case DATE:
        return TSDataType.DATE;
      case TIMESTAMP_WITHOUT_TIME_ZONE:
      case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
        return TSDataType.TIMESTAMP;
      default:
        throw new CatalogException("Unsupported Flink data type for IoTDB: " + dataType);
    }
  }

  public static DataType toFlinkDataType(TSDataType dataType) {
    switch (dataType) {
      case BOOLEAN:
        return DataTypes.BOOLEAN();
      case INT32:
        return DataTypes.INT();
      case INT64:
        return DataTypes.BIGINT();
      case FLOAT:
        return DataTypes.FLOAT();
      case DOUBLE:
        return DataTypes.DOUBLE();
      case TEXT:
      case STRING:
        return DataTypes.STRING();
      case BLOB:
        return DataTypes.BYTES();
      case DATE:
        return DataTypes.DATE();
      case TIMESTAMP:
        return DataTypes.TIMESTAMP(3);
      default:
        throw new CatalogException("Unsupported IoTDB data type: " + dataType);
    }
  }
}
