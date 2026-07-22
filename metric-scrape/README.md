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

# Metric Scrape

Metric Scrape is a standalone service that periodically scrapes Prometheus text exposition
endpoints and writes metrics into the Apache IoTDB table model through the
`org.apache.iotdb:iotdb-session` client.

It is a pull scraper, not a Prometheus remote write receiver.

## Build

```bash
mvn -pl metric-scrape -am clean package -DskipTests
```

The runnable jar is generated at:

```text
metric-scrape/target/metric-scrape-2.0.4-SNAPSHOT.jar
```

## Run

```bash
java -jar metric-scrape/target/metric-scrape-2.0.4-SNAPSHOT.jar \
  -c metric-scrape/conf/metric-scrape.yml
```

If `-c` is omitted, the service reads `conf/metric-scrape.yml` from the current working
directory.

## Configuration

```yaml
global:
  scrape_interval: 15s
  scrape_timeout: 10s
  iotdb_database: 'metrics'
  iotdb_username: 'root'
  iotdb_password: 'root'
  iotdb_urls: ['127.0.0.1:6667']

scrape_configs:
  - job_name: 'iotdbModel'
    static_configs:
      - targets: ['localhost:9090']
    metrics_path: /metrics
    relabel_configs:
      - target_label: k8s_cluster
        replacement: example-cluster
      - target_label: k8s_namespace
        replacement: default
      - target_label: instance_id
        replacement: model-service-local
```

Supported fields:

| Field | Description |
| --- | --- |
| `global.scrape_interval` | Default scrape interval. Supports `ms`, `s`, `m`, `h`; no suffix means seconds. |
| `global.scrape_timeout` | Default HTTP timeout. Supports the same duration format. |
| `global.iotdb_database` | Target IoTDB table-model database. |
| `global.iotdb_username` | Session username. |
| `global.iotdb_password` | Session password. |
| `global.iotdb_urls` | IoTDB node URLs, for example `127.0.0.1:6667`. |
| `global.write_batch_size` | Optional tablet batch size. Default is `1000`. |
| `scrape_configs[].job_name` | Scrape job name. It is written as tag column `job_name`. |
| `scrape_configs[].scheme` | Optional target scheme. Default is `http`. |
| `scrape_configs[].metrics_path` | Metrics path. Default is `/metrics`. |
| `scrape_configs[].scrape_interval` | Optional interval override for this job. |
| `scrape_configs[].scrape_timeout` | Optional timeout override for this job. |
| `scrape_configs[].static_configs[].targets` | Target host list. |
| `scrape_configs[].static_configs[].labels` | Extra labels for targets. They are written as tag columns. |
| `scrape_configs[].relabel_configs[].target_label` | Constant target label name. |
| `scrape_configs[].relabel_configs[].replacement` | Constant target label value. |

Only constant relabeling with `target_label` and `replacement` is supported for now.

## Data Model

Metric Scrape writes into the IoTDB table model:

| Prometheus content | Table model mapping |
| --- | --- |
| `global.iotdb_database` | Database name. |
| `# HELP` metric family name | Table name. |
| Sample metric name | Field column name. |
| Sample value | Field column value, `DOUBLE`. |
| `job_name` | Tag column. |
| Target address | Tag column `instance`. |
| Sample labels, static labels, relabel replacement labels | Tag columns, `STRING`. |
| Sample timestamp | Row time. If missing, the current scrape time in milliseconds is used. |

For histogram and summary samples, the longest matching `# HELP` metric family is used as the
table name. For example, `request_duration_seconds_sum` and
`request_duration_seconds_count` are written into table `request_duration_seconds` if the text
contains `# HELP request_duration_seconds ...`.

Metric names and label names are normalized before being used as table or column identifiers:
characters other than letters, digits, and `_` are replaced by `_`, and identifiers starting with
a digit get a leading `_`.

## Docker And Kubernetes

The module includes a `Dockerfile` and example Kubernetes manifests under `k8s/`.

```bash
docker build -t metric-scrape:latest metric-scrape
kubectl apply -f metric-scrape/k8s/configmap.yaml
kubectl apply -f metric-scrape/k8s/deployment.yaml
```

Update the image name, IoTDB URLs, credentials, and scrape targets before deploying to a real
cluster.

## Example Query

```sql
USE metrics;

SELECT time, job_name, instance, env, http_server_requests_seconds_count
FROM http_server_requests_seconds
ORDER BY time DESC
LIMIT 10;
```
