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

## 简介

Apache IoTDB Extras 是 [Apache IoTDB](https://iotdb.apache.org) 主项目的配套仓库。它包含示例、集成模块、连接器和其他扩展 IoTDB 功能的附加工具。

IoTDB (物联网数据库) 是一个专为物联网 (IoT) 场景设计的时序数据库，具有高性能的数据摄取、存储和查询时序数据的能力。

本仓库提供了与流行的数据处理框架、可视化工具和其他系统的集成，以增强 IoTDB 的生态系统。

## 主要组件

本仓库包括：

1. **连接器**：用于各种数据处理框架和系统的集成模块

   - Flink IoTDB 连接器
   - Flink SQL IoTDB 连接器
   - Flink TsFile 连接器
   - Grafana 连接器和插件
   - Hadoop 连接器
   - Hive 连接器
   - Spark IoTDB 连接器
   - Spark TsFile
   - Zeppelin 解释器

2. **IoTDB Collector**：IoTDB 数据收集框架

3. **IoTDB Spring Boot Starter**：IoTDB 的 Spring Boot 集成

4. **Kubernetes 支持**

   - Helm Charts
   - IoTDB Operator

5. **MyBatis 生成器**：数据库访问代码生成工具

6. **示例**：展示 IoTDB 与各种技术结合使用的示例应用程序和代码示例

## 环境要求

- Java 8+ (JDK 1.8 或更高版本，springboot 需要 JDK 17+，推荐使用 JDK 17+)
- Maven 3.6 或更高版本
- Git

## 从源代码构建

按照以下步骤从源代码构建项目：

1. 克隆仓库：

   ```bash
   git clone https://github.com/apache/iotdb-extras.git
   cd iotdb-extras
   ```

2. 使用 Maven 构建项目：

   ```bash
   # 构建整个项目（包括 distributions、iotdb-collector、mybatis-generator）
   mvn clean package -DskipTests

   # 或者构建所有组件
   mvn clean package -DskipTests -Pwith-all-connectors,with-examples,with-springboot,with-grafana
   ```

3. 构建产物将位于每个模块的 `target` 目录中。

### Maven 配置文件（Profiles）

IoTDB-Extras 使用 Maven profiles 配置不同的构建选项。您可以组合各种 profiles 来构建特定组件：

#### 更多 Profiles

- **with-springboot**：构建 Spring Boot 集成

  ```bash
  mvn clean package -Pwith-springboot -DskipTests
  ```

- **with-examples**：构建示例应用程序（建议与连接器 Profiles 一起使用）

  ```bash
  mvn clean package -Pwith-examples -DskipTests
  ```

#### 连接器 Profiles

- **with-all-connectors**：构建所有可用的连接器（包括 Flink、Hadoop、Hive、Spark、Zeppelin；不包括 Grafana）

  ```bash
  mvn clean package -Pwith-all-connectors -DskipTests
  ```

- **with-spark**：构建 Spark 连接器（包括 spark-iotdb-connector、spark-iotdb-table-connector）

  ```bash
  mvn clean package -Pwith-spark -DskipTests
  ```

- **with-flink**：构建 Flink 连接器（包括 flink-iotdb-connector、flink-sql-iotdb-connector、flink-tsfile-connector）

  ```bash
  mvn clean package -Pwith-flink -DskipTests
  ```

- **with-grafana**：构建 Grafana 连接器和插件

  ```bash
  mvn clean package -Pwith-grafana -DskipTests

  # （推荐）如果您想构建 Grafana 插件，使用：
  mvn clean package -Pwith-grafana-plugin -DskipTests

  # 要构建 Grafana 连接器，使用：
  mvn clean package -Pwith-grafana-connector -DskipTests
  ```

- **with-hadoop**：构建 Hadoop 连接器

  ```bash
  mvn clean package -Pwith-hadoop -DskipTests
  ```

- **with-hive**：构建 Hive 连接器

  ```bash
  mvn clean package -Pwith-hive -DskipTests
  ```

- **with-spark-tsfile**：构建 Spark TsFile 连接器（包括 spark-tsfile、hadoop）

  ```bash
  mvn clean package -Pwith-spark-tsfile -DskipTests
  ```

- **with-zeppelin**：构建 Zeppelin 解释器
  ```bash
  mvn clean package -Pwith-zeppelin -DskipTests
  ```

#### 示例 Profiles

构建示例模块时，您可以使用这些 profiles：

- **with-springboot**：构建 Spring Boot 示例

  ```bash
  mvn clean package -Pwith-examples,with-springboot -DskipTests
  ```

- **with-flink**：构建 Flink 示例

  ```bash
  mvn clean package -Pwith-examples,with-flink -DskipTests
  ```

- **with-hadoop**：构建 Hadoop 示例

  ```bash
  mvn clean package -Pwith-examples,with-hadoop -DskipTests
  ```

- **with-spark**：构建 Spark 示例
  ```bash
  mvn clean package -Pwith-examples,with-spark -DskipTests
  ```

#### Profile 组合

您可以组合多个 profiles 来构建特定组件：

```bash
# 构建 Flink 和 Spark 连接器以及示例
mvn clean package -Pwith-flink,with-spark,with-examples -DskipTests

# 构建包含所有组件的完整发行版
mvn clean package -Pwith-all-connectors,with-examples,with-springboot -DskipTests
```

## 使用示例

本仓库包含各种展示如何将 IoTDB 与不同技术结合使用的示例：

### Flink 集成示例

#### IoTDB-Flink-Connector 示例

- **功能**：演示如何从 Flink 作业向 IoTDB 服务器发送数据
- **用法**：
  - 启动 IoTDB 服务器
  - 运行 `org.apache.iotdb.flink.FlinkIoTDBSink.java` 在本地 mini 集群上执行 Flink 作业

#### TsFile-Flink-Connector 示例

- **用法**：
  - 运行 `org.apache.iotdb.flink.FlinkTsFileBatchSource.java` 创建 TsFile 并通过 Flink DataSet 作业读取
  - 运行 `org.apache.iotdb.flink.FlinkTsFileStreamSource.java` 创建 TsFile 并通过 Flink DataStream 作业读取
  - 运行 `org.apache.iotdb.flink.FlinkTsFileBatchSink.java` 通过 Flink DataSet 作业写入 TsFile
  - 运行 `org.apache.iotdb.flink.FlinkTsFileStreamSink.java` 通过 Flink DataStream 作业写入 TsFile

#### Flink SQL 示例

- 示例包括：
  - `BatchSinkExample.java`：演示批量写入 IoTDB
  - `BoundedScanExample.java`：展示有界数据扫描
  - `CDCExample.java`：说明变更数据捕获
  - `LookupExample.java`：展示查找功能
  - `StreamingSinkExample.java`：演示流数据写入

### Spark 集成示例

#### IoTDB-Table-Spark-Connector 示例

- **简介**：演示如何在 Spark 中使用 IoTDB-Table-Spark-Connector 读写 IoTDB 中的数据
- **版本**：
  - Scala 2.12
  - Spark 3.3 或更高版本
- **使用方法**：
  ```xml
  <dependency>
      <groupId>org.apache.iotdb</groupId>
      <artifactId>spark-iotdb-table-connector-3.5</artifactId>
  </dependency>
  ```
- **配置选项**：
  | 键 | 默认值 | 描述 | 必填 |
  |-----|---------|-------------|----------|
  | iotdb.database | -- | IoTDB 中的数据库名称 | 是 |
  | iotdb.table | -- | IoTDB 中的表名 | 是 |
  | iotdb.username | root | 访问 IoTDB 的用户名 | 否 |
  | iotdb.password | root | 访问 IoTDB 的密码 | 否 |
  | iotdb.urls | 127.0.0.1:6667 | 连接 URL | 否 |

### Spring Boot 集成示例

#### IoTDB-Spring-Boot-Starter 示例

- **简介**：展示如何使用 iotdb-spring-boot-starter
- **版本**：
  - IoTDB: 2.0.3
  - iotdb-spring-boot-starter: 2.0.3
- **设置**：
  1. 安装 IoTDB
  2. 创建必要的数据库和表
  3. 配置 Spring Boot 应用程序

### 消息队列集成示例

#### Kafka-IoTDB 示例

- **功能**：展示如何通过 Kafka 从本地主机向 IoTDB 发送数据
- **版本**：
  - IoTDB: 1.0.0
  - Kafka: 2.8.0

#### 其他消息队列示例

- RocketMQ 集成
- RabbitMQ 集成
- Pulsar 集成

### MyBatis 生成器示例

- **简介**：展示如何使用 IoTDB-Mybatis-Generator
- **版本**：
  - IoTDB: 2.0.2
  - mybatis-generator-plugin: 1.3.2
- **设置**：
  1. 安装并启动 IoTDB
  2. 创建必要的数据库和表
  3. 运行生成器

有关详细使用说明，请参阅特定示例目录中的 README 文件：

- [Flink 示例](/examples/flink/README.md)
- [Spark 表示例](/examples/spark-table/README.md)
- [MyBatis 生成器示例](/examples/mybatis-generator/README.md)
- [IoTDB Spring Boot Starter 示例](/examples/iotdb-spring-boot-start/README.md)
- [Kafka 示例](/examples/kafka/readme.md)

您还可以参考模块特定的文档：

- [Flink IoTDB 连接器](/connectors/flink-iotdb-connector/README.md)
- [Grafana 插件](/connectors/grafana-plugin/README.md)
- [IoTDB Spring Boot Starter](/iotdb-spring-boot-starter/README.md)
- [Kubernetes Helm Charts](/helm/README.md)
- [IoTDB Operator](/iotdb-operator/README.md)

## Docker 支持

IoTDB 组件可以使用 Docker 运行。有关更多信息，请参阅 [Docker 文档](/docker/ReadMe.md)。

## 许可证

Apache IoTDB Extras 是在 [Apache 2.0 许可证](LICENSE) 下发布的。

## 联系我们

### QQ 群

- Apache IoTDB 交流群：659990460

### Wechat Group

- 添加好友 `apache_iotdb`，我们会邀请您进群

### Slack

- https://join.slack.com/t/apacheiotdb/shared_invite/zt-qvso1nj8-7715TpySZtZqmyG5qXQwpg

获取更多内容，请查看 [加入社区](https://github.com/apache/iotdb/issues/1995)
