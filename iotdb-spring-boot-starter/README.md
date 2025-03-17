# mybatis-generator-plugin

* After 'clone' the project, execute 'mvn clean install' or 'mvn clean deploy' locally ('deploy' needs to modify 'distributionManagement' in 'pom'). This step is not necessary as it has already been uploaded to the Maven central repository

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
                        <artifactId>iotdb-mybatis-generator</artifactId>
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

* The location of the ` configurationFile ` configuration ` generatorConfig. xml ` file can be found in the ` src/main/resources ` template of this project for reference` Copy its content and place it in the corresponding location

* Modify the content you want to use in 'generatorConfig. xml', mainly by:` jdbcConnection`、`javaModelGenerator`、`sqlMapGenerator`、`javaClientGenerator`、`table`

* Execute the command at the location of the 'pom' in the project:` Mvn mybatis generator: generate generates corresponding Java classes and mapper files