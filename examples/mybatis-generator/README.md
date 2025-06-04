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

# Mybatis-Generator Demo

## Introduction

This demo shows how to use IoTDB-Mybatis-Generator

### Version usage

IoTDB: 2.0.2
mybatis-generator-plugin: 1.3.2

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
);
```

### 3. Build Dependencies with Maven in your Project

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.mybatis.generator</groupId>
            <artifactId>mybatis-generator-maven-plugin</artifactId>
            <version>1.4.2</version>
            <dependencies>
                <dependency>
                    <groupId>org.apache.iotdb</groupId>
                    <artifactId>mybatis-generator-plugin</artifactId>
                    <version>1.3.2</version>
                </dependency>
            </dependencies>
            <configuration>
                <verbose>true</verbose>
                <overwrite>true</overwrite>
                <configurationFile>src/main/resources/generatorConfig.xml</configurationFile>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### 5. put The generatorConfig.xml in your project

- `src/main/resources/generatorConfig.xml`

each table generates an entity object

- `src/main/resources/generatorConfigByExample.xml`

The generated object will contain many "by Example" methods. If you do not want to generate these, you can configure to cancel them in the subsequent table elements

### 6. generate generates corresponding Java classes and mapper files

exec `mvn mybatis-generator:generate`

Execute the command at the location of the 'pom' in the project: Mvn mybatis generator: generate generates corresponding Java classes and mapper files

### 7、the target file location

You can see the target file in your Project  

```
org/apache/iotdb/mybatis/plugin/model/Mix.java
org/apache/iotdb/mybatis/plugin/mapper/MixMapper.java
org/apache/iotdb/mybatis/plugin/xml/MixMapper.xml
```

if you are using the 'src/main/resources/generatorConfiguraByExample. xml' file`, You can see the target file in your Project  
```
org/apache/iotdb/mybatis/plugin/model/Mix.java
org/apache/iotdb/mybatis/plugin/model/MixExample.java
org/apache/iotdb/mybatis/plugin/mapper/MixMapper.java
org/apache/iotdb/mybatis/plugin/xml/MixMapper.xml
```
