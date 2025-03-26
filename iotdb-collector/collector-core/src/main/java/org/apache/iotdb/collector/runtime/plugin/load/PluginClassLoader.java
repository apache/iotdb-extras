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

package org.apache.iotdb.collector.runtime.plugin.load;

import javax.annotation.concurrent.ThreadSafe;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ThreadSafe
public class PluginClassLoader extends URLClassLoader {

  private final String libRoot;
  private volatile boolean deprecated;

  public PluginClassLoader(final String libRoot) throws IOException {
    super(new URL[0]);
    this.libRoot = libRoot;
    this.deprecated = false;
    addUrls();
  }

  private void addUrls() throws IOException {
    try (final Stream<Path> pathStream = Files.walk(new File(libRoot).toPath())) {
      for (final Path path :
          pathStream.filter(path -> !path.toFile().isDirectory()).collect(Collectors.toList())) {
        super.addURL(path.toUri().toURL());
      }
    }
  }

  public synchronized void markAsDeprecated() throws IOException {
    deprecated = true;
    closeIfPossible();
  }

  public void closeIfPossible() throws IOException {
    if (deprecated) {
      close();
    }
  }
}
