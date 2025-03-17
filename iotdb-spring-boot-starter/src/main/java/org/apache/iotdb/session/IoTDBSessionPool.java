package org.apache.iotdb.session;

import org.apache.iotdb.config.IoTDBSessionProperties;
import org.apache.iotdb.isession.pool.ITableSessionPool;
import org.apache.iotdb.session.pool.SessionPool;
import org.apache.iotdb.session.pool.TableSessionPoolBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
@ConditionalOnClass({IoTDBSessionProperties.class}) // 确保相关类存在
@EnableConfigurationProperties(IoTDBSessionProperties.class)
public class IoTDBSessionPool {

    private final IoTDBSessionProperties properties;
    private ITableSessionPool tableSessionPool;
    private SessionPool treeSessionPool;

    public IoTDBSessionPool(IoTDBSessionProperties properties) {
        this.properties = properties;
    }

    @Bean
    public ITableSessionPool tableSessionPool() {
        if(tableSessionPool == null) {
            synchronized (IoTDBSessionPool.class) {
                if(tableSessionPool == null) {
                    tableSessionPool = new TableSessionPoolBuilder().
                            nodeUrls(Arrays.asList(properties.getUrl().split(";"))).
                            user(properties.getUsername()).
                            password(properties.getPassword()).
                            database(properties.getDatabase()).
                            maxSize(properties.getMax_size()).
                            fetchSize(properties.getFetch_size()).
                            enableAutoFetch(properties.getEnable_auto_fetch()).
                            useSSL(properties.getUse_ssl()).
                            queryTimeoutInMs(properties.getQuery_timeout_in_ms()).
                            maxRetryCount(properties.getMax_retry_count()).
                            waitToGetSessionTimeoutInMs(properties.getQuery_timeout_in_ms()).
                            enableCompression(properties.isEnable_compression()).
                            retryIntervalInMs(properties.getRetry_interval_in_ms()).
                            build();
                }
            }
        }
        return tableSessionPool;
    }

    @Bean
    public SessionPool treeSessionPool() {
        if(treeSessionPool == null) {
            synchronized (IoTDBSessionPool.class) {
                if(treeSessionPool == null) {
                    treeSessionPool = new SessionPool.Builder().
                            nodeUrls(Arrays.asList(properties.getUrl().split(";"))).
                            user(properties.getUsername()).
                            password(properties.getPassword()).
                            maxSize(properties.getMax_size()).
                            fetchSize(properties.getFetch_size()).
                            enableAutoFetch(properties.getEnable_auto_fetch()).
                            useSSL(properties.getUse_ssl()).
                            queryTimeoutInMs(properties.getQuery_timeout_in_ms()).
                            maxRetryCount(properties.getMax_retry_count()).
                            waitToGetSessionTimeoutInMs(properties.getQuery_timeout_in_ms()).
                            enableCompression(properties.isEnable_compression()).
                            retryIntervalInMs(properties.getRetry_interval_in_ms()).
                            build();
                }
            }
        }
        return treeSessionPool;
    }
}
