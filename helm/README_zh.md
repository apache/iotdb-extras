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

# IoTDB CLuster on Kubernetes

## 部署方法

### 部署环境准备

确保当前环境安装了以下工具并能成功连接到对应的 Kubernetes 集群

* kubectl
* helm (version >= v3.0.0)

### 参数配置

根据对应的需求修改 `helm/values.yaml` 里的配置参数。具体为：

1. 确认当前集群的存储介质，并指定相应的`storageClass`。IoTDB 将会根据`storageClass` 在对应的数据存储介质上创建`pv`

    ```
    storage:
        className: local-storage # 将该值指定为当前Kubernetes 中可用的存储介质
    ```

2. 确认集群规模
    ```
    datanode:
        # datanode 的节点数量
        nodeCount: 3 
        # 每个 datanode 对应的数据目录的大小，需要根据设计的数据量指定，且指定后不可修改，生产环境中，建议根据数据量的大小给定充足的空间
        storageCapacity: 20Gi 

    confignode:
        # confignode 的节点数量
        nodeCount: 3
        # confignode 使用的pvc的大小，一般不会超过10Gi，可直接使用默认值
        storageCapacity: 10Gi 
    ```

3. 确认数据副本数
    
    IoTDB 集群可以提供数据的多副本存储能力。可根据自身使用的存储介质进行灵活的选择。例如，storageClass对应的数据存储介质本身不提供数据的副本能力，则可以将元数据的副本设置为3、数据的副本也设置为3的方式来保证数据的可用性；如果storageClass对应的数据存储介质做了数据冗余，那无需在IoTDB 集群层面进行数据的多副本存储，可将元数据副本数置为1，数据副本数置为1或2。

    ```
    confignode:
        # 元数据副本数
        schemaReplicationFactor: 1
        # 数据副本数
        dataReplicationFactor: 2
    ```


### 启动部署

进入 ./helm/ 目录，执行install 命令，将 IoTDB Cluster 安装到 Kubernetes 环境中

```
helm install iotdb-cluster .
```

### 等待部署完毕

可以通过`kubectl` 命令来查看部署的进展，当所有pod 为 Running 状态时，系统就处于可以状态了, 如下图所示
<img src='../images/getpods.png'>


## 连接到集群

### 在 Kubernetes 环境中来连接集群

DataNode 的地址为 `datanode-<N>.datanode-svc.iotdb-cluster`, 端口号为`6667`；需要根据想要连接的datanode的序号，修改地址中\<N\> 的值; 例如，`datanode-0.datanode-svc.iotdb-cluster` 表示datanode-0 的地址。

### 在集群外部连接到集群

如果希望通过集群外部的程序连接到集群，则需要将集群中的 datanode-svc 映射到集群外可以访问的地址；这可能取决于当前 Kubernetes 集群的云特性。

例如，如果您的 Kubernetes 集群具有`LoadBalancer` 的功能，则您可以将 datanode 的6667端口映射到集群外部；

如果仅仅是为了进行本地测试，则可以通过`nodePort` 的方式将节点datanode的6667端口映射到Kubernetes集群节点的某一端口，并通过映射后的端口和节点的ip地址对datanode进行连接。可参考

```
# node-port.yaml
apiVersion: v1
kind: Service
metadata:
  namespace: iotdb-cluster
  name: datanode-nodeport
spec:
  type: NodePort
  ports:
  - port: 6667
    targetPort: 6667
    nodePort: 32667
  selector:
    app: datanode
```


## 演示效果

### 集群部署及运行状态

下面，以3个ConfigNode、3个DataNodes的部署方案为例，展示一下集群部署后各组件的运行状况

1. Pod运行情况

    <img src='../images/getpods.png'>

2. pvc 使用情况

    <img src='../images/getpvc.png'>

    每个confignode\datanode 都会独立使用一个pvc，并将其数据持久化到对应的pvc中


### 在集群内部连接并访问集群

1. 使用cli通过datanode的集群内部地址建立连接

    <img src='../images/logininner.png'>


2. 展示集群中的节点情况

    <img src='../images/showcluster.png'>

    可以看到，集群中的节点地址均为Kubernetes 中内部FQDN

3. 展示集群中的前十条时间序列（已经进行了测试数据写入）

    <img src='../images/showtimeseries.png'>
    
3. 进行数据查询

    <img src='../images/select10.png'>