/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Compile-only ThingsBoard stub (Strategy F). Verified against ThingsBoard v4.3.1.2
// (commit c37fb509).
package org.thingsboard.server.common.data.util;

import java.util.Objects;

/**
 * Compile-only ThingsBoard surface stub (Strategy F): mirrors {@code
 * org.thingsboard.server.common.data.util.TbPair} so the IoTDB Table Mode DAO can return the value
 * shape expected by the real {@code AttributesDao.removeAllWithVersions} contract without depending
 * on the upstream ThingsBoard artifact. Excluded from the built jar.
 */
public class TbPair<S, T> {
  private S first;
  private T second;

  public TbPair(S first, T second) {
    this.first = first;
    this.second = second;
  }

  public static <S, T> TbPair<S, T> of(S first, T second) {
    return new TbPair<>(first, second);
  }

  public S getFirst() {
    return first;
  }

  public void setFirst(S first) {
    this.first = first;
  }

  public T getSecond() {
    return second;
  }

  public void setSecond(T second) {
    this.second = second;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TbPair)) {
      return false;
    }
    TbPair<?, ?> tbPair = (TbPair<?, ?>) o;
    return Objects.equals(first, tbPair.first) && Objects.equals(second, tbPair.second);
  }

  @Override
  public int hashCode() {
    return Objects.hash(first, second);
  }

  @Override
  public String toString() {
    return "TbPair(first=" + first + ", second=" + second + ")";
  }
}
