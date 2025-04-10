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
    ```
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
    <dependencies>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>3.5.10</version>
        </dependency>

        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-generator</artifactId>
            <version>3.5.10</version>
        </dependency>

        <dependency>
            <groupId>org.apache.velocity</groupId>
            <artifactId>velocity-engine-core</artifactId>
            <version>2.0</version>
        </dependency>

        <dependency>
            <groupId>org.apache.iotdb</groupId>
            <artifactId>iotdb-jdbc</artifactId>
            <version>2.0.2</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
            <version>3.4.3</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <version>3.4.3</version>
        </dependency>
        <dependency>
            <groupId>io.springfox</groupId>
            <artifactId>springfox-swagger2</artifactId>
            <version>3.0.0</version>
        </dependency>
        <dependency>
            <groupId>io.springfox</groupId>
            <artifactId>springfox-swagger-ui</artifactId>
            <version>3.0.0</version>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>1.18.36</version>
        </dependency>
    </dependencies>
    ```

### 5. Start the Main.java

### 6、the target file location

    You can see the target file in your Project
    ```
    org/apache/iotdb/controller/MixController.java
    org/apache/iotdb/entity/Mix.java
    org/apache/iotdb/mapper/MixMapper.xml
    org/apache/iotdb/service/MixService.java  
    org/apache/iotdb/service/MixServiceImpl.java
    org/apache/iotdb/MixMapper.xml

    ```
