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

package org.apache.iotdb.collector.plugin.api;

import org.apache.iotdb.collector.plugin.api.customizer.CollectorParameters;
import org.apache.iotdb.collector.runtime.progress.ProgressIndex;
import org.apache.iotdb.pipe.api.PipeSource;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeExtractorRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeSourceRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

import java.util.Optional;

import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_DEVICE_ID_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_DEVICE_ID_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_IS_ALIGNED_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_IS_ALIGNED_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_REPORT_TIME_INTERVAL_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_REPORT_TIME_INTERVAL_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_SQL_DIALECT_DEFAULT_VALUE;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_SQL_DIALECT_KEY;
import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.SOURCE_SQL_DIALECT_VALUE_SET;

public abstract class BaseSource implements PipeSource {

  protected ProgressIndex startIndex;
  protected int instanceIndex;

  protected String deviceId;
  protected Boolean isAligned;
  protected String sqlDialect;
  protected int reportTimeInterval;

  @Override
  public void validate(final PipeParameterValidator validator) throws Exception {
    CollectorParameters.validateBooleanParam(
        validator, SOURCE_IS_ALIGNED_KEY, SOURCE_IS_ALIGNED_DEFAULT_VALUE);

    CollectorParameters.validateSetParam(
        validator,
        SOURCE_SQL_DIALECT_KEY,
        SOURCE_SQL_DIALECT_VALUE_SET,
        SOURCE_SQL_DIALECT_DEFAULT_VALUE);

    CollectorParameters.validateIntegerParam(
        validator,
        SOURCE_REPORT_TIME_INTERVAL_KEY,
        SOURCE_REPORT_TIME_INTERVAL_DEFAULT_VALUE,
        value -> value > 0);
  }

  @Override
  public void customize(
      final PipeParameters pipeParameters,
      final PipeSourceRuntimeConfiguration pipeSourceRuntimeConfiguration)
      throws Exception {
    deviceId =
        pipeParameters.getStringOrDefault(SOURCE_DEVICE_ID_KEY, SOURCE_DEVICE_ID_DEFAULT_VALUE);
    isAligned =
        pipeParameters.getBooleanOrDefault(SOURCE_IS_ALIGNED_KEY, SOURCE_IS_ALIGNED_DEFAULT_VALUE);
    sqlDialect =
        pipeParameters.getStringOrDefault(SOURCE_SQL_DIALECT_KEY, SOURCE_SQL_DIALECT_DEFAULT_VALUE);
    reportTimeInterval =
        pipeParameters.getIntOrDefault(
            SOURCE_REPORT_TIME_INTERVAL_KEY, SOURCE_REPORT_TIME_INTERVAL_DEFAULT_VALUE);
  }

  @Override
  public final void customize(
      PipeParameters pipeParameters,
      PipeExtractorRuntimeConfiguration pipeExtractorRuntimeConfiguration)
      throws Exception {
    throw new UnsupportedOperationException();
  }

  public abstract Optional<ProgressIndex> report();
}
