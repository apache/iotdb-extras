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

# IoTDB MyBatis Generator 插件

为 IoTDB **表模型**生成 Model、Mapper 和 XML，提供批量插入、Lombok、序列化、Swagger 注释和 JDBC 类型映射。要求 **JDK 17+、IoTDB/JDBC 2.0.11、MBG 1.4.2**。插件版本属于 Extras，本仓库仍为 `2.0.4-SNAPSHOT`。

## 安装与配置

在仓库根目录安装生成插件和运行时适配：

```sh
mvn -pl mybatis-generator,mybatis-support -am clean install
```

`mvn package -Pwith-mybatis` 还会生成 `distributions/target/apache-iotdb-<version>-mybatis-generator-plugin-bin.zip`，其中同时打包生成插件和 `mybatis-support` 运行时 jar。

生成时，`mybatis-generator-maven-plugin:1.4.2` 的 `dependencies` 中必须包含：

| 依赖 | 版本 |
|---|---|
| `org.apache.iotdb:mybatis-generator-plugin` | `2.0.4-SNAPSHOT` |
| `org.apache.iotdb:iotdb-jdbc` | `2.0.11` |

无需配置机器相关的 `classPathEntry`。应用运行时还要依赖 `org.apache.iotdb:mybatis-support:2.0.4-SNAPSHOT` 并[注册查询拦截器](../mybatis-support/README.md)。2.0.11 表模型驱动的 prepared query 路径需要这一适配。

参考[完整生成配置](../examples/mybatis-generator/src/main/resources/generatorConfig.xml)，设置 JDBC URL、凭据、表名及输出路径。先创建表，再从应用项目目录执行：

```sh
mvn mybatis-generator:generate
```

生成会读取真实元数据并可能覆盖已有文件；检查差异后再使用。

## 键、类型与 SQL 语义

```xml
<plugin type="org.apache.iotdb.mybatis.plugin.BatchInsertPlugin">
    <property name="batchSize" value="500"/>
</plugin>
<plugin type="org.mybatis.generator.plugins.VirtualPrimaryKeyPlugin"/>
<plugin type="org.apache.iotdb.mybatis.plugin.IoTDBKeyPlugin"/>
```

- 行键由 **TIME 与全部 TAG** 组成，`virtualKeyColumns` 必须与表结构一致。`IoTDBKeyPlugin` 在查询、删除的键谓词中为 NULL TAG 生成 `IS NULL`。
- `IoTDBJavaTypeResolver` 将 TIMESTAMP 映射为 **Long**；数值单位跟随服务端 ms/us/ns，适配层不转换单位。FLOAT 配置为 `java.lang.Float`。不要用 `Date` 承接高精度时间戳。
- DATE 使用 `LocalDate + IoTDBLocalDateTypeHandler`，BLOB 使用 `byte[] + IoTDBBlobTypeHandler`。通过 `columnOverride` 同时生成参数及结果映射，详见[运行时适配文档](../mybatis-support/README.md)。
- 设置 `enableUpdateByPrimaryKey=false` 和 `enableUpdateByExample=false`。2.0.11 的 UPDATE 只支持 ATTRIBUTE，且谓词中不能出现 `time`，MBG 按主键生成的 UPDATE 无法执行；若仍开启，`IoTDBKeyPlugin` 会跳过这些语句并输出生成警告。修改 FIELD 要按相同行键执行 INSERT。省略或为 NULL 的 FIELD 不会清除已有值。
- ATTRIBUTE 属于设备，更新会影响该设备所有时间点。普通关系数据库的通用 UPDATE 不能直接套用。
- 批量 SQL 保留 MBG 的标识符转义和 TypeHandler。保留字使用 `delimitIdentifiers` / `delimitAllColumns`。
- 示例使用 `ignoreQualifiersAtRuntime=true`：生成读取配置中的 schema，运行时由 JDBC URL 选择数据库。
- Lombok/Swagger 注解依赖需要放在应用 classpath 中；Lombok 同时覆盖主键类、普通模型和 BLOB 子类。

## 批量行为与迁移

调用 `mapper.batchInsert(records)`。默认方法先校验整份列表，再按 **500 行**拆分 SQL；插件属性 `batchSize` 必须为正整数。宽表或大 BLOB 应减小批次，它限制行数，不限制字节数。

| 输入/配置 | 行为 |
|---|---|
| 空列表 | 返回 0，不访问数据库 |
| NULL 列表或 NULL 元素 | 写入前抛出 `IllegalArgumentException` |
| identity/autoincrement/generated-always 列 | 不参与批量插入 |
| `incrementField` | 保持旧版排除列兼容 |
| 关闭 INSERT | 不生成批量方法/XML |
| 所有列都被排除 | 不生成批量方法/XML，并报告警告 |
| BLOB 分层模型 | 使用包含全部字段的模型类型 |

`batchInsertRows(@Param("records") List<T>)` 是内部单批映射方法，直接调用会绕过校验和拆批。**从旧版升级时，必须同时重新生成 Mapper 接口和 XML**，不能只替换其中一个。

示例启用 `UnmergeableXmlMappersPlugin`，在关闭注释时也直接替换生成 XML，避免重复生成累积同名语句。手写 SQL 应单独保存，或在重新生成前检查并保留。

2.0.11 JDBC 的影响行数为 -1（未知）。任一批次返回未知时，总返回值保持 -1，否则累加已知数量；不要用 `result > 0` 判断成功。SQL 失败会抛异常，但之前的批次可能已写入。IoTDB JDBC 不提供事务回滚，`@Transactional` 不能把多次写入变成原子操作。

实际查询应限制时间/设备范围和返回条数。检入的简单示例将 `selectAll` 限制为 1000 条；MBG 标准模板不会自动添加该限制，重新生成后应检查查询范围。

集成测试和已知服务端限制参见[可运行示例](../examples/mybatis-generator/README.md)和[MyBatis-Plus 示例](../examples/mybatisplus-generator/README.md)。本插件与 MyBatis-Plus 的生成器是两套独立集成。
