<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->

# Apache IoTDB Extras

[![GitHub release](https://img.shields.io/github/release/apache/iotdb.svg)](https://github.com/apache/iotdb/releases)
[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)

[English](README.md) | [中文](README-zh.md)

## Introduction

Apache IoTDB Extras is a companion repository to the main [Apache IoTDB](https://iotdb.apache.org) project. It contains examples, integration modules, connectors, and additional tools that extend the functionality of IoTDB.

IoTDB (Internet of Things Database) is a time series database designed specifically for the Internet of Things (IoT) scenarios, with high performance for data ingestion, storage, and querying for time series data.

This repository provides integrations with popular data processing frameworks, visualization tools, and other systems to enhance IoTDB's ecosystem.

## Main Components

This repository includes:

1. **Connectors**: Integration modules for various data processing frameworks and systems

   - Flink IoTDB Connector
   - Flink SQL IoTDB Connector
   - Flink TsFile Connector
   - Grafana Connector & Plugin
   - Hadoop Connector
   - Hive Connector
   - Spark IoTDB Connector
   - Spark TsFile
   - Zeppelin Interpreter

2. **IoTDB Collector**: Data collection framework for IoTDB

3. **IoTDB Spring Boot Starter**: Spring Boot integration for IoTDB

4. **Kubernetes Support**

   - Helm Charts
   - IoTDB Operator

5. **MyBatis Generator**: Code generation tools for database access

6. **Examples**: Sample applications and code examples demonstrating the use of IoTDB with various technologies

## Prerequisites

- Java 8+ (JDK 1.8 or later versions, springboot requires JDK 17+, Recommended JDK 17+)
- Maven 3.6 or later
- Git

## Building from Source

To build the project from source, follow these steps:

1. Clone the repository:

   ```bash
   git clone https://github.com/apache/iotdb-extras.git
   cd iotdb-extras
   ```

2. Build the project with Maven:

   ```bash
   # Build the entire project (includes distributions,iotdb-collector,mybatis-generator)
   mvn clean package -DskipTests

   # Or build all
   mvn clean package -DskipTests -Pwith-all-connectors,with-examples,with-springboot,with-grafana

   ```

3. The build artifacts will be located in the `target` directory of each module.

### Maven Profiles

IoTDB-Extras uses Maven profiles to configure different build options. You can combine various profiles to build specific components:

#### More Profiles

- **with-springboot**: Build Spring Boot integration

  ```bash
  mvn clean package -Pwith-springboot -DskipTests
  ```

- **with-examples**: Build example applications(Recommended for use in conjunction with Connector Profiles)

  ```bash
  mvn clean package -Pwith-examples -DskipTests
  ```

#### Connector Profiles

- **with-all-connectors**: Build all available connectors (includes Flink, Hadoop, Hive, Spark, Zeppelin. excludes Grafana)

  ```bash
  mvn clean package -Pwith-all-connectors -DskipTests
  ```

- **with-spark**: Build Spark connectors (includes spark-iotdb-connector, spark-iotdb-table-connector)

  ```bash
  mvn clean package -Pwith-spark -DskipTests
  ```

- **with-flink**: Build Flink connectors (includes flink-iotdb-connector, flink-sql-iotdb-connector, flink-tsfile-connector)

  ```bash
  mvn clean package -Pwith-flink -DskipTests
  ```

- **with-grafana**: Build Grafana connectors and plugins

  ```bash
  mvn clean package -Pwith-grafana -DskipTests

  # (Recommend) If you want to build the Grafana plugin, use:
  mvn clean package -Pwith-grafana-plugin -DskipTests

  # To build the Grafana connector, use:
  mvn clean package -Pwith-grafana-connector -DskipTests

  ```

- **with-hadoop**: Build Hadoop connector

  ```bash
  mvn clean package -Pwith-hadoop -DskipTests
  ```

- **with-hive**: Build Hive connector

  ```bash
  mvn clean package -Pwith-hive -DskipTests
  ```

- **with-spark-tsfile**: Build Spark TsFile connector (includes spark-tsfile, hadoop)

  ```bash
  mvn clean package -Pwith-spark-tsfile -DskipTests
  ```

- **with-zeppelin**: Build Zeppelin interpreter
  ```bash
  mvn clean package -Pwith-zeppelin -DskipTests
  ```

#### Example Profiles

When building the examples module, you can use these profiles:

- **with-springboot**: Build Spring Boot examples

  ```bash
  mvn clean package -Pwith-examples,with-springboot -DskipTests
  ```

- **with-flink**: Build Flink examples

  ```bash
  mvn clean package -Pwith-examples,with-flink -DskipTests
  ```

- **with-hadoop**: Build Hadoop examples

  ```bash
  mvn clean package -Pwith-examples,with-hadoop -DskipTests
  ```

- **with-spark**: Build Spark examples
  ```bash
  mvn clean package -Pwith-examples,with-spark -DskipTests
  ```

#### Profile Combinations

You can combine multiple profiles to build specific components:

```bash
# Build Flink and Spark connectors with examples
mvn clean package -Pwith-flink,with-spark,with-examples -DskipTests

# Build a complete distribution with all components
mvn clean package -Pwith-all-connectors,with-examples,with-springboot -DskipTests
```

## Usage Examples

This repository includes a variety of examples demonstrating how to use IoTDB with different technologies:

### Flink Integration Examples

#### IoTDB-Flink-Connector Example

- **Function**: Demonstrates how to send data to an IoTDB server from a Flink job
- **Usage**:
  - Launch the IoTDB server
  - Run `org.apache.iotdb.flink.FlinkIoTDBSink.java` to run the Flink job on local mini cluster

#### TsFile-Flink-Connector Example

- **Usage**:
  - Run `org.apache.iotdb.flink.FlinkTsFileBatchSource.java` to create a TsFile and read it via a Flink DataSet job
  - Run `org.apache.iotdb.flink.FlinkTsFileStreamSource.java` to create a TsFile and read it via a Flink DataStream job
  - Run `org.apache.iotdb.flink.FlinkTsFileBatchSink.java` to write a TsFile via a Flink DataSet job
  - Run `org.apache.iotdb.flink.FlinkTsFileStreamSink.java` to write a TsFile via a Flink DataStream job

#### Flink SQL Examples

- Examples include:
  - `BatchSinkExample.java`: Demonstrates batch writing to IoTDB
  - `BoundedScanExample.java`: Shows bounded data scanning
  - `CDCExample.java`: Illustrates Change Data Capture
  - `LookupExample.java`: Shows lookup functionality
  - `StreamingSinkExample.java`: Demonstrates streaming data writing

### Spark Integration Examples

#### IoTDB-Table-Spark-Connector Example

- **Introduction**: Demonstrates how to use the IoTDB-Table-Spark-Connector to read and write data from/to IoTDB in Spark
- **Version**:
  - Scala 2.12
  - Spark 3.3 or later
- **Usage**:
  ```xml
  <dependency>
      <groupId>org.apache.iotdb</groupId>
      <artifactId>spark-iotdb-table-connector-3.5</artifactId>
  </dependency>
  ```
- **Configuration Options**:
  | Key | Default | Description | Required |
  |-----|---------|-------------|----------|
  | iotdb.database | -- | Database name in IoTDB | true |
  | iotdb.table | -- | Table name in IoTDB | true |
  | iotdb.username | root | Username to access IoTDB | false |
  | iotdb.password | root | Password to access IoTDB | false |
  | iotdb.urls | 127.0.0.1:6667 | Connection URLs | false |

### Spring Boot Integration Examples

#### IoTDB-Spring-Boot-Starter Example

- **Introduction**: Shows how to use iotdb-spring-boot-starter
- **Version**:
  - IoTDB: 2.0.3
  - iotdb-spring-boot-starter: 2.0.3
- **Setup**:
  1. Install IoTDB
  2. Create necessary database and tables
  3. Configure Spring Boot application

### Message Queue Integration Examples

#### Kafka-IoTDB Example

- **Function**: Shows how to send data from localhost to IoTDB through Kafka
- **Version**:
  - IoTDB: 1.0.0
  - Kafka: 2.8.0

#### Additional Message Queue Examples

- RocketMQ Integration
- RabbitMQ Integration
- Pulsar Integration

### MyBatis Generator Example

- **Introduction**: Shows how to use IoTDB-Mybatis-Generator
- **Version**:
  - IoTDB: 2.0.2
  - mybatis-generator-plugin: 1.3.2
- **Setup**:
  1. Install and start IoTDB
  2. Create necessary database and tables
  3. Run the generator

For detailed usage instructions, please refer to the README files in the specific example directories:

- [Flink Examples](/examples/flink/README.md)
- [Spark Table Examples](/examples/spark-table/README.md)
- [MyBatis Generator Examples](/examples/mybatis-generator/README.md)
- [IoTDB Spring Boot Starter Examples](/examples/iotdb-spring-boot-start/readme.md)
- [Kafka Examples](/examples/kafka/readme.md)

You can also refer to module-specific documentation:

- [Flink IoTDB Connector](/connectors/flink-iotdb-connector/README.md)
- [Grafana Plugin](/connectors/grafana-plugin/README.md)
- [IoTDB Spring Boot Starter](/iotdb-spring-boot-starter/README.md)
- [Kubernetes Helm Charts](/helm/README.md)
- [IoTDB Operator](/iotdb-operator/README.md)

## Docker Support

IoTDB components can be run using Docker. For more information, see the [Docker documentation](/docker/ReadMe.md).

## License

Apache IoTDB Extras is released under the [Apache 2.0 License](LICENSE).

## Contact Us

### QQ Group

- Apache IoTDB User Group: 659990460

### Wechat Group

- Add friend: `apache_iotdb`, and then we'll invite you to the group.

### Slack

- [Slack channel](https://join.slack.com/t/apacheiotdb/shared_invite/zt-qvso1nj8-7715TpySZtZqmyG5qXQwpg)

see [Join the community](https://github.com/apache/iotdb/issues/1995) for more!
