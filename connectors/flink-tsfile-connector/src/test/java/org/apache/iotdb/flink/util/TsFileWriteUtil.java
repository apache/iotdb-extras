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

package org.apache.iotdb.flink.util;

import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.file.metadata.enums.TSEncoding;
import org.apache.tsfile.write.TsFileWriter;
import org.apache.tsfile.write.record.TSRecord;
import org.apache.tsfile.write.record.datapoint.DataPoint;
import org.apache.tsfile.write.record.datapoint.FloatDataPoint;
import org.apache.tsfile.write.record.datapoint.IntDataPoint;
import org.apache.tsfile.write.schema.MeasurementSchema;
import org.apache.tsfile.write.schema.Schema;

import java.io.File;

/** Utils used to prepare TsFiles for testing. */
public class TsFileWriteUtil {

  public static final String TMP_DIR = "target";
  public static final String DEFAULT_TEMPLATE = "template";

  public static void create1(String tsfilePath) throws Exception {
    File f = new File(tsfilePath);
    if (f.exists()) {
      f.delete();
    }
    Schema schema = new Schema();
    schema.extendTemplate(
        DEFAULT_TEMPLATE, new MeasurementSchema("sensor_1", TSDataType.FLOAT, TSEncoding.RLE));
    schema.extendTemplate(
        DEFAULT_TEMPLATE, new MeasurementSchema("sensor_2", TSDataType.INT32, TSEncoding.TS_2DIFF));
    schema.extendTemplate(
        DEFAULT_TEMPLATE, new MeasurementSchema("sensor_3", TSDataType.INT32, TSEncoding.TS_2DIFF));

    TsFileWriter tsFileWriter = new TsFileWriter(f, schema);
    tsFileWriter.registerDevice("device_1", DEFAULT_TEMPLATE);
    tsFileWriter.registerDevice("device_2", DEFAULT_TEMPLATE);
    // construct TSRecord
    TSRecord tsRecord = new TSRecord("device_1", 1);
    DataPoint dPoint1 = new FloatDataPoint("sensor_1", 1.2f);
    DataPoint dPoint2 = new IntDataPoint("sensor_2", 20);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);

    // write a TSRecord to TsFile
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_1", 2);
    dPoint2 = new IntDataPoint("sensor_2", 20);
    DataPoint dPoint3 = new IntDataPoint("sensor_3", 50);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_1", 3);
    dPoint1 = new FloatDataPoint("sensor_1", 1.4f);
    dPoint2 = new IntDataPoint("sensor_2", 21);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_1", 4);
    dPoint1 = new FloatDataPoint("sensor_1", 1.2f);
    dPoint2 = new IntDataPoint("sensor_2", 20);
    dPoint3 = new IntDataPoint("sensor_3", 51);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_1", 6);
    dPoint1 = new FloatDataPoint("sensor_1", 7.2f);
    dPoint2 = new IntDataPoint("sensor_2", 10);
    dPoint3 = new IntDataPoint("sensor_3", 11);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_1", 7);
    dPoint1 = new FloatDataPoint("sensor_1", 6.2f);
    dPoint2 = new IntDataPoint("sensor_2", 20);
    dPoint3 = new IntDataPoint("sensor_3", 21);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_1", 8);
    dPoint1 = new FloatDataPoint("sensor_1", 9.2f);
    dPoint2 = new IntDataPoint("sensor_2", 30);
    dPoint3 = new IntDataPoint("sensor_3", 31);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_2", 1);
    dPoint1 = new FloatDataPoint("sensor_1", 2.3f);
    dPoint2 = new IntDataPoint("sensor_2", 11);
    dPoint3 = new IntDataPoint("sensor_3", 19);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_2", 2);
    dPoint1 = new FloatDataPoint("sensor_1", 25.4f);
    dPoint2 = new IntDataPoint("sensor_2", 10);
    dPoint3 = new IntDataPoint("sensor_3", 21);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    // close TsFile
    tsFileWriter.close();
  }

  public static void create2(String tsfilePath) throws Exception {
    File f = new File(tsfilePath);
    if (f.exists()) {
      f.delete();
    }
    Schema schema = new Schema();
    schema.extendTemplate(
        DEFAULT_TEMPLATE, new MeasurementSchema("sensor_1", TSDataType.FLOAT, TSEncoding.RLE));
    schema.extendTemplate(
        DEFAULT_TEMPLATE, new MeasurementSchema("sensor_2", TSDataType.INT32, TSEncoding.TS_2DIFF));
    schema.extendTemplate(
        DEFAULT_TEMPLATE, new MeasurementSchema("sensor_3", TSDataType.INT32, TSEncoding.TS_2DIFF));

    TsFileWriter tsFileWriter = new TsFileWriter(f, schema);

    tsFileWriter.registerDevice("device_1", DEFAULT_TEMPLATE);
    tsFileWriter.registerDevice("device_2", DEFAULT_TEMPLATE);

    // construct TSRecord
    TSRecord tsRecord = new TSRecord("device_1", 9);
    DataPoint dPoint1 = new FloatDataPoint("sensor_1", 1.2f);
    DataPoint dPoint2 = new IntDataPoint("sensor_2", 20);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);

    // write a TSRecord to TsFile
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_1", 10);
    dPoint2 = new IntDataPoint("sensor_2", 20);
    DataPoint dPoint3 = new IntDataPoint("sensor_3", 50);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_1", 11);
    dPoint1 = new FloatDataPoint("sensor_1", 1.4f);
    dPoint2 = new IntDataPoint("sensor_2", 21);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_1", 12);
    dPoint1 = new FloatDataPoint("sensor_1", 1.2f);
    dPoint2 = new IntDataPoint("sensor_2", 20);
    dPoint3 = new IntDataPoint("sensor_3", 51);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_1", 14);
    dPoint1 = new FloatDataPoint("sensor_1", 7.2f);
    dPoint2 = new IntDataPoint("sensor_2", 10);
    dPoint3 = new IntDataPoint("sensor_3", 11);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_1", 15);
    dPoint1 = new FloatDataPoint("sensor_1", 6.2f);
    dPoint2 = new IntDataPoint("sensor_2", 20);
    dPoint3 = new IntDataPoint("sensor_3", 21);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_1", 16);
    dPoint1 = new FloatDataPoint("sensor_1", 9.2f);
    dPoint2 = new IntDataPoint("sensor_2", 30);
    dPoint3 = new IntDataPoint("sensor_3", 31);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_2", 9);
    dPoint1 = new FloatDataPoint("sensor_1", 2.3f);
    dPoint2 = new IntDataPoint("sensor_2", 11);
    dPoint3 = new IntDataPoint("sensor_3", 19);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    tsRecord = new TSRecord("device_2", 10);
    dPoint1 = new FloatDataPoint("sensor_1", 25.4f);
    dPoint2 = new IntDataPoint("sensor_2", 10);
    dPoint3 = new IntDataPoint("sensor_3", 21);
    tsRecord.addTuple(dPoint1);
    tsRecord.addTuple(dPoint2);
    tsRecord.addTuple(dPoint3);
    tsFileWriter.writeRecord(tsRecord);

    // close TsFile
    tsFileWriter.close();
  }
}
