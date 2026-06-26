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

package org.apache.commons.lang3.tuple;

import java.util.Objects;

/**
 * Compile-only stub of Apache Commons Lang3 {@code org.apache.commons.lang3.tuple.Pair}, provided
 * so the IoTDB Table Mode DAO can bind the real ThingsBoard {@code AttributesDao.removeAllByEntityId}
 * signature (which returns {@code List<Pair<AttributeScope, String>>}). commons-lang3 is not on this
 * module's compile classpath; the real artifact is supplied by the host ThingsBoard runtime. This
 * stub is excluded from the built jar (Strategy F) and only exposes the {@code of}/{@code getLeft}/
 * {@code getRight} surface the DAO uses.
 */
public final class Pair<L, R> {

  private final L left;
  private final R right;

  private Pair(L left, R right) {
    this.left = left;
    this.right = right;
  }

  public static <L, R> Pair<L, R> of(L left, R right) {
    return new Pair<>(left, right);
  }

  public L getLeft() {
    return left;
  }

  public R getRight() {
    return right;
  }

  // Real commons-lang3 Pair implements Map.Entry, so TB service code reads pairs via
  // getKey()/getValue(); mirror that surface (getKey == left, getValue == right).
  public L getKey() {
    return left;
  }

  public R getValue() {
    return right;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Pair)) {
      return false;
    }
    Pair<?, ?> pair = (Pair<?, ?>) o;
    return Objects.equals(left, pair.left) && Objects.equals(right, pair.right);
  }

  @Override
  public int hashCode() {
    return Objects.hash(left, right);
  }

  @Override
  public String toString() {
    return "(" + left + "," + right + ")";
  }
}
