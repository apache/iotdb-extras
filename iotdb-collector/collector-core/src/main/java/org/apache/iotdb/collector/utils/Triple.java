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

package org.apache.iotdb.collector.utils;

public class Triple<L, M, R> {
  public L left;
  public M middle;
  public R right;

  public Triple(final L left, final M middle, final R right) {
    this.left = left;
    this.middle = middle;
    this.right = right;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((left == null) ? 0 : left.hashCode());
    result = prime * result + ((middle == null) ? 0 : middle.hashCode());
    result = prime * result + ((right == null) ? 0 : right.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    Triple<?, ?, ?> other = (Triple<?, ?, ?>) obj;
    if (left == null) {
      return other.left == null;
    } else if (!left.equals(other.left)) {
      return false;
    }
    if (middle == null) {
      return other.middle == null;
    } else if (!middle.equals(other.middle)) {
      return false;
    }
    if (right == null) {
      return other.right == null;
    } else return right.equals(other.right);
  }

  @Override
  public String toString() {
    return "<" + left + ", " + middle + ", " + right + ">";
  }
}
