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

package org.apache.iotdb.collector.plugin.api.customizer;

import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static org.apache.iotdb.collector.plugin.builtin.source.constant.SourceConstant.BOOLEAN_SET;

public class CollectorParameters {

  public static void validateStringRequiredParam(
      final PipeParameterValidator validator, final String paramKey) {
    validator.validate(
        o -> Objects.nonNull(validator.getParameters().getString((String) o)),
        String.format("%s is required, but git null.", paramKey),
        paramKey);
  }

  public static void validateBooleanParam(
      final PipeParameterValidator validator, final String paramKey, final boolean defaultValue) {
    validateSetParam(validator, paramKey, BOOLEAN_SET, String.valueOf(defaultValue));
  }

  public static void validateSetParam(
      final PipeParameterValidator validator,
      final String paramKey,
      final Set<String> valueSet,
      final String defaultValue) {
    final String paramValue = validator.getParameters().getStringOrDefault(paramKey, defaultValue);

    validator.validate(
        o -> valueSet.contains(o.toString()),
        String.format("%s must be one of %s, but got %s.", paramKey, valueSet, paramValue),
        paramValue);
  }

  public static void validateIntegerParam(
      final PipeParameterValidator validator,
      final String paramKey,
      final Integer paramDefaultValue,
      final Predicate<Integer> validationCondition) {
    final int paramValue = validator.getParameters().getIntOrDefault(paramKey, paramDefaultValue);

    validator.validate(
        value -> validationCondition.test((Integer) value),
        String.format("%s must be > 0, but got %d.", paramKey, paramValue),
        paramValue);
  }

  public static void validateLongParam(
      final PipeParameterValidator validator,
      final String paramKey,
      final Long paramDefaultValue,
      final Predicate<Long> validationCondition) {
    final long paramValue = validator.getParameters().getLongOrDefault(paramKey, paramDefaultValue);

    validator.validate(
        value -> validationCondition.test((Long) value),
        String.format("%s must be > 0, but got %d.", paramKey, paramValue),
        paramValue);
  }
}
