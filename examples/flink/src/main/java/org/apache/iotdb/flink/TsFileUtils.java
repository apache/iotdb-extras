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

package org.apache.iotdb.flink;

import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.file.metadata.enums.TSEncoding;
import org.apache.tsfile.fileSystem.FSFactoryProducer;
import org.apache.tsfile.read.TsFileReader;
import org.apache.tsfile.read.TsFileSequenceReader;
import org.apache.tsfile.read.common.Path;
import org.apache.tsfile.read.common.RowRecord;
import org.apache.tsfile.read.expression.QueryExpression;
import org.apache.tsfile.read.query.dataset.QueryDataSet;
import org.apache.tsfile.write.TsFileWriter;
import org.apache.tsfile.write.record.TSRecord;
import org.apache.tsfile.write.record.datapoint.DataPoint;
import org.apache.tsfile.write.record.datapoint.LongDataPoint;
import org.apache.tsfile.write.schema.MeasurementSchema;
import org.apache.tsfile.write.schema.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Utils used to prepare source TsFiles for the examples. */
public class TsFileUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(TsFileUtils.class);
  private static final String DEFAULT_TEMPLATE = "template";

  private TsFileUtils() {}

  public static void writeTsFile(String path) {
    try {
      File f = FSFactoryProducer.getFSFactory().getFile(path);
      Files.delete(f.toPath());
      Schema schema = new Schema();
      schema.extendTemplate(
          DEFAULT_TEMPLATE, new MeasurementSchema("sensor_1", TSDataType.FLOAT, TSEncoding.RLE));
      schema.extendTemplate(
          DEFAULT_TEMPLATE,
          new MeasurementSchema("sensor_2", TSDataType.INT32, TSEncoding.TS_2DIFF));
      schema.extendTemplate(
          DEFAULT_TEMPLATE,
          new MeasurementSchema("sensor_3", TSDataType.INT32, TSEncoding.TS_2DIFF));

      try (TsFileWriter tsFileWriter = new TsFileWriter(f, schema)) {

        // construct TSRecord
        for (int i = 0; i < 100; i++) {
          TSRecord tsRecord = new TSRecord("device_" + (i % 4), i);
          DataPoint dPoint1 = new LongDataPoint("sensor_1", i);
          DataPoint dPoint2 = new LongDataPoint("sensor_2", i);
          DataPoint dPoint3 = new LongDataPoint("sensor_3", i);
          tsRecord.addTuple(dPoint1);
          tsRecord.addTuple(dPoint2);
          tsRecord.addTuple(dPoint3);

          // write TSRecord
          tsFileWriter.writeRecord(tsRecord);
        }
      }

    } catch (Exception e) {
      LOGGER.error("Write {} failed. ", path, e);
    }
  }

  public static String[] readTsFile(String tsFilePath, List<Path> paths) throws IOException {
    QueryExpression expression = QueryExpression.create(paths, null);
    TsFileSequenceReader reader = new TsFileSequenceReader(tsFilePath);
    try (TsFileReader readTsFile = new TsFileReader(reader)) {
      QueryDataSet queryDataSet = readTsFile.query(expression);
      List<String> result = new ArrayList<>();
      while (queryDataSet.hasNext()) {
        RowRecord rowRecord = queryDataSet.next();
        String row =
            rowRecord.getFields().stream()
                .map(f -> f == null ? "null" : f.getStringValue())
                .collect(Collectors.joining(","));
        result.add(rowRecord.getTimestamp() + "," + row);
      }
      return result.toArray(new String[0]);
    }
  }
}
