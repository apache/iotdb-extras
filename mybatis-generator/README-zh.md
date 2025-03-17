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
# mybatis-generator-plugin

* 把该项目 `clone` 下来之后，在本地执行 `mvn clean install` 或者 `mvn clean deploy` (`deploy` 需要修改 `pom` 中的 `distributionManagement`)【已经上传 `Maven` 中央仓库，所以此步骤不在需要】

* 在要生成的项目的 `pom` 文件中添加如下配置：

```java
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
						<version>1.3.2-SNAPSHOT</version>
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

* `configurationFile` 配置 `generatorConfig.xml` 文件的位置，其内容在本项目的 `src/main/resources` 有一个模板供参考，`copy` 其内容放到相应的位置

* 修改 `generatorConfig.xml` 中 想用的内容，主要是：`jdbcConnection`、`javaModelGenerator`、`sqlMapGenerator`、`javaClientGenerator`、`table`

* 在项目的 `pom` 所在的地方执行命令：`mvn mybatis-generator:generate` 生成相应的 `Java` 类和 `mapper` 文件