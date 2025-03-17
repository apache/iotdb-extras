# mybatis-generator-plugin

* After 'clone' the project, execute 'mvn clean install' or 'mvn clean deploy' locally ('deploy' needs to modify 'distributionManagement' in 'pom'). This step is not necessary as it has already been uploaded to the Maven central repository

* Add the following configuration to the 'pom' file of the project to be generated:

```
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.iotdb</groupId>
            <artifactId>iotdb-spring-boot-starter</artifactId>
            <version>2.0.2-SNAPSHOT</version>
        </dependency>
    </dependencies>
```

* Use The target Bean with @Autowired like:
```java
        @Autowired
        private ITableSessionPool ioTDBSessionPool;

        public void queryTableSessionPool() throws IoTDBConnectionException, StatementExecutionException {
            ITableSession tableSession = ioTDBSessionPool.getSession();
            final SessionDataSet sessionDataSet = tableSession.executeQueryStatement("select * from power_data_set limit 10");
            while (sessionDataSet.hasNext()) {
                final RowRecord rowRecord = sessionDataSet.next();
                final List<Field> fields = rowRecord.getFields();
                for (Field field : fields) {
                    System.out.print(field.getStringValue());
                }
                System.out.println();
            }
        }
```