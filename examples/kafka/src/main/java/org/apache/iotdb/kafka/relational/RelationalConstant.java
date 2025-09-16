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

package org.example.relational;

public class RelationalConstant {

    public static final String KAFKA_SERVICE_URL = "172.20.31.71:9094";
    public static final String TOPIC = "Kafka-Relational-Test";
    public static final String[] IOTDB_URLS = {
        "127.0.0.1:6667"
    };
    public static final String IOTDB_USERNAME = "root";
    public static final String IOTDB_PASSWORD = "root";
    public static final int SESSION_SIZE = 3;
    public static final int CONSUMER_THREAD_NUM = 5;
    public static final String[] DATABASES = {"kafka_db1", "kafka_db2"};
    public static final String[][] TABLES = {
        // database, tableName, columnNames, columnTypes, columnCategories
        {"kafka_db1", "tb1", "time,region,model_id,temperature,status", "TIMESTAMP,STRING,STRING,FLOAT,BOOLEAN", "TIME,TAG,ATTRIBUTE,FIELD,FIELD"},
        {"kafka_db2", "tb2", "time,plant_id,humidity,status", "TIMESTAMP,STRING,FLOAT,BOOLEAN", "TIME,TAG,FIELD,FIELD"}
    };
    public static final String[] ALL_DATA = {
            // database;tableName;columnName[,columnName]*;value[,value]*[,value[:value]*]*
            "kafka_db1;tb1;time,temperature,status;17,3.26,true;18,3.27,false;19,3.28,true",
            "kafka_db1;tb1;time,region,model_id,temperature;20,'rgn1','id1',3.31",
            "kafka_db2;tb2;time,plant_id,humidity,status;50,'id1',68.7,true",
            "kafka_db2;tb2;time,plant_id,humidity,status;51,'id2',68.5,false",
            "kafka_db2;tb2;time,plant_id,humidity,status;52,'id3',68.3,true",
            "kafka_db2;tb2;time,plant_id,humidity,status;53,'id4',68.8,true",
            "kafka_db2;tb2;time,plant_id,humidity,status;54,'id5',68.9,true"
    };
}
