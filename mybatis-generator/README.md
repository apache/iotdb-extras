
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

* After 'clone' the project, execute 'mvn clean install' or 'mvn clean deploy' locally ('deploy' needs to modify 'distributionManagement' in 'pom'). This step is not necessary as it has already been uploaded to the Maven central repository

* Add the following configuration to the 'pom' file of the project to be generated:

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
						<version>2.0.2-SNAPSHOT</version>
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
* The location of the ` configurationFile ` configuration ` generatorConfig. xml ` file can be found in the ` src/main/resources ` template of this project for reference` Copy its content and place it in the corresponding location

* Modify the content you want to use in 'generatorConfig. xml', mainly by:` jdbcConnection`、`javaModelGenerator`、`sqlMapGenerator`、`javaClientGenerator`、`table`

* Execute the command at the location of the 'pom' in the project:` Mvn mybatis generator: generate generates corresponding Java classes and mapper files