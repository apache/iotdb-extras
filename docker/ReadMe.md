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

# How to run IoTDB-grafana-connector

1. First way: use config file:

```
docker run -it -v /your_application.properties_folder:/iotdb-grafana-connector/config -p 8888:8888 apache/iotdb:<version>-grafana
```

2. Second way: use environment(take `SPRING_DATASOURCE_URL` for example)

```
docker run -it -p 8888:8888 apache/iotdb:<version>-grafana -e SPRING_DATASOURCE_URL=jdbc:iotdb://iotdb:6667/
```

3. All related environment are as follows(more details in `grafana/src/main/resources/application.properties`)

| name                                | default value                     |
| ----------------------------------- | --------------------------------- |
| SPRING_DATASOURCE_URL               | jdbc:iotdb://127.0.0.1:6667/      |
| SPRING_DATASOURCE_USERNAME          | root                              |
| SPRING_DATASOURCE_PASSWORD          | root                              |
| SPRING_DATASOURCE_DRIVER_CLASS_NAME | org.apache.iotdb.jdbc.IoTDBDriver |
| SERVER_PORT                         | 8888                              |
| TIMESTAMP_PRECISION                 | ms                                |
| ISDOWNSAMPLING                      | true                              |
| INTERVAL                            | 1m                                |
| CONTINUOUS_DATA_FUNCTION            | AVG                               |
| DISCRETE_DATA_FUNCTION              | LAST_VALUE                        |

# How to run IoTDB-grafana-connector by docker compose
> Using docker compose, it contains three services: iotdb, grafana and grafana-connector

1. The location of docker compose file: `/docker/src/main/DockerCompose/docker-compose-grafana.yml`
2. Use `docker-compose up` can start all three services
    1. you can use `docker-compose up -d` to start in the background
    2. you can modify `docker-compose-grafana.yml` to implement your requirements.
        1. you can modify environment of grafana-connector
        2. If you want to **SAVE ALL DATA**, please use `volumes` keyword to mount the data volume or file of the host into the container.
3. After all services are start, you can visit `{ip}:3000` to visit grafana
    1. In `Configuration`, search `SimpleJson`
    2. Fill in url: `grafana-connector:8888`, then click `save and test`. if `Data source is working` is shown, the configuration is finished.
    3. Then you can create dashboards.
4. if you want to stop services, just run `docker-compose down`

Enjoy it!
