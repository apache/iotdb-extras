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
# MybatisPlus-Generator Demo
## Introduction

This demo shows how to use IoTDB-MybatisPlus-Generator

### Version usage

IoTDB: 2.0.1-beta
mybatisPlus: 3.5.10

### 1. Install IoTDB

please refer to [https://iotdb.apache.org/#/Download](https://iotdb.apache.org/#/Download)

### 2. Startup IoTDB

please refer to [Quick Start](http://iotdb.apache.org/UserGuide/Master/Get%20Started/QuickStart.html)

Then we need to create a database 'test' by cli in table model
```
create database test;
use test;
```
Then we need to create a database 'table'
```sql
CREATE TABLE mix (
    time TIMESTAMP TIME,
    region STRING TAG,
    plant_id STRING TAG,
    device_id STRING TAG,
    model_id STRING ATTRIBUTE,
    maintenance STRING ATTRIBUTE,
    temperature FLOAT FIELD,
    humidity FLOAT FIELD,
    status Boolean FIELD,
    arrival_time TIMESTAMP FIELD
) WITH (TTL=31536000000);
```

### 3. Build Dependencies with Maven in your Project

```
<properties>
    <mybatisplus.version>3.5.10</mybatisplus.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <spring-boot.version>3.4.5</spring-boot.version>
    <spring.version>6.2.6</spring.version>
    <iotdb-jdbc.version>2.0.4-SNAPSHOT</iotdb-jdbc.version>
    <io-springfox.version>3.0.0</io-springfox.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        <version>${mybatisplus.version}</version>
    </dependency>
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-generator</artifactId>
        <version>${mybatisplus.version}</version>
    </dependency>

    <dependency>
        <groupId>org.apache.iotdb</groupId>
        <artifactId>iotdb-jdbc</artifactId>
        <version>${iotdb-jdbc.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
        <version>${spring-boot.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
        <version>${spring-boot.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <version>${spring-boot.version}</version>
        <scope>test</scope>
    </dependency>

    <dependency>
        <groupId>io.springfox</groupId>
        <artifactId>springfox-swagger2</artifactId>
        <version>${io-springfox.version}</version>
    </dependency>
    <dependency>
        <groupId>io.springfox</groupId>
        <artifactId>springfox-swagger-ui</artifactId>
        <version>${io-springfox.version}</version>
    </dependency>

    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>1.18.36</version>
    </dependency>
    <dependency>
        <groupId>com.github.jeffreyning</groupId>
        <artifactId>mybatisplus-plus</artifactId>
        <version>1.7.5-RELEASE</version>
    </dependency>
</dependencies>
```

### 4. Start the Main.java

### 5. the target file location

You can see the target file in your Project
```
org/apache/iotdb/controller/MixController.java
org/apache/iotdb/entity/Mix.java
org/apache/iotdb/mapper/MixMapper.xml
org/apache/iotdb/service/MixService.java  
org/apache/iotdb/service/MixServiceImpl.java
org/apache/iotdb/MixMapper.xml

```

### 6. add & alter Annotations

The generated code files `entity/Table1.java` and `entity/Table2.java` need to be manually adjusted to support multi-primary key queries.

```java
// add import
import com.github.jeffreyning.mybatisplus.anno.MppMultiId;

// add @MppMultiId
@MppMultiId
// alter @TableId() -->> @TableField()
@TableField("time")
private Date time;

// add @MppMultiId
@MppMultiId
// alter @TableId() -->> @TableField()
@TableField("region")
private String region;

// add @MppMultiId
@MppMultiId
// alter @TableId() -->> @TableField()
@TableField("plant_id")
private String plantId;

// add @MppMultiId
@MppMultiId
// alter @TableId() -->> @TableField()
@TableField("device_id")
private String deviceId;
//

```

