/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.collector.plugin.builtin.source.iotdb;

import org.apache.iotdb.collector.plugin.api.PullSource;
import org.apache.iotdb.collector.plugin.api.customizer.CollectorParameters;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.session.subscription.consumer.base.AbstractSubscriptionPullConsumerBuilder;

import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_MIN_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.iotdb.IoTDBSubscriptionSourceConstant.IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_KEY;

public abstract class IoTDBSubscriptionPullSource extends PullSource {

  protected final IoTDBSubscription subscription = new IoTDBSubscription();

  private Boolean autoCommit;
  private Long autoCommitIntervalMs;

  @Override
  public void validate(final PipeParameterValidator validator) throws Exception {
    super.validate(validator);
    subscription.validate(validator);

    CollectorParameters.validateBooleanParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_DEFAULT_VALUE);

    CollectorParameters.validateLongParam(
        validator,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_KEY,
        IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_DEFAULT_VALUE,
        value -> value >= IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_MIN_VALUE);
  }

  @Override
  public void customize(
      PipeParameters pipeParameters, PipeSourceRuntimeConfiguration pipeSourceRuntimeConfiguration)
      throws Exception {
    super.customize(pipeParameters, pipeSourceRuntimeConfiguration);
    subscription.customize(pipeParameters, pipeSourceRuntimeConfiguration);

    autoCommit =
        pipeParameters.getBooleanOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_KEY,
            IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_DEFAULT_VALUE);
    autoCommitIntervalMs =
        pipeParameters.getLongOrDefault(
            IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_KEY,
            IOTDB_SUBSCRIPTION_SOURCE_AUTO_COMMIT_INTERVAL_MS_DEFAULT_VALUE);
  }

  protected AbstractSubscriptionPullConsumerBuilder getSubscriptionPullConsumerBuilder() {
    return ((AbstractSubscriptionPullConsumerBuilder) subscription.getSubscriptionConsumerBuilder())
        .autoCommit(autoCommit)
        .autoCommitIntervalMs(autoCommitIntervalMs);
  }
}
