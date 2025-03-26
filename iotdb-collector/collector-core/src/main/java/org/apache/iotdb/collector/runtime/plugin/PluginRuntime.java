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

package org.apache.iotdb.collector.runtime.plugin;

import org.apache.iotdb.collector.runtime.plugin.constructor.ProcessorConstructor;
import org.apache.iotdb.collector.runtime.plugin.constructor.SinkConstructor;
import org.apache.iotdb.collector.runtime.plugin.constructor.SourceConstructor;
import org.apache.iotdb.collector.runtime.plugin.load.PluginClassLoader;
import org.apache.iotdb.collector.runtime.plugin.load.PluginClassLoaderManager;
import org.apache.iotdb.collector.runtime.plugin.meta.PluginMeta;
import org.apache.iotdb.collector.runtime.plugin.meta.PluginMetaKeeper;
import org.apache.iotdb.collector.runtime.plugin.utils.PluginFileUtils;
import org.apache.iotdb.collector.service.PersistenceService;
import org.apache.iotdb.pipe.api.PipePlugin;
import org.apache.iotdb.pipe.api.PipeProcessor;
import org.apache.iotdb.pipe.api.PipeSink;
import org.apache.iotdb.pipe.api.PipeSource;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;

public class PluginRuntime implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(PluginRuntime.class);

  private final PluginMetaKeeper metaKeeper;
  private final SourceConstructor sourceConstructor;
  private final ProcessorConstructor processorConstructor;
  private final SinkConstructor sinkConstructor;
  private final PluginClassLoaderManager classLoaderManager;

  public PluginRuntime() {
    this.metaKeeper = new PluginMetaKeeper();
    this.sourceConstructor = new SourceConstructor(metaKeeper);
    this.processorConstructor = new ProcessorConstructor(metaKeeper);
    this.sinkConstructor = new SinkConstructor(metaKeeper);
    this.classLoaderManager = new PluginClassLoaderManager();
  }

  public PipeSource constructSource(final PipeParameters sourceParameters) {
    return sourceConstructor.reflectPlugin(sourceParameters);
  }

  public boolean isPullSource(final PipeParameters sourceParameters) throws Exception {
    try (final PipeSource source = constructSource(sourceParameters)) {
      return sourceConstructor.isPullSource(source);
    }
  }

  public boolean isPushSource(final PipeParameters sourceParameters) throws Exception {
    try (final PipeSource source = constructSource(sourceParameters)) {
      return sourceConstructor.isPushSource(source);
    }
  }

  public PipeProcessor constructProcessor(final PipeParameters processorParameters) {
    return processorConstructor.reflectPlugin(processorParameters);
  }

  public PipeSink constructSink(final PipeParameters sinkParameters) {
    return sinkConstructor.reflectPlugin(sinkParameters);
  }

  public PluginClassLoader getClassLoader(final String pluginName) throws IOException {
    return classLoaderManager.getPluginClassLoader(pluginName);
  }

  public synchronized Response createPlugin(
      final String pluginName,
      final String className,
      final String jarName,
      final String jarMD5FromDB,
      final boolean isRestRequest) {
    try {
      // validate whether the plugin jar file exists
      if (isRestRequest && !PluginFileUtils.isPluginJarFileExist(jarName)) {
        final String errorMessage =
            String.format(
                "Failed to register Plugin %s, because the plugin jar file %s is not found",
                pluginName, jarName);
        LOGGER.warn(errorMessage);
        return Response.serverError().entity(errorMessage).build();
      }

      // validate whether the plugin has been loaded
      final PluginMeta information = metaKeeper.getPipePluginMeta(pluginName);
      if (Objects.nonNull(information)) {
        // validate whether the plugin is builtin plugin
        if (information.isBuiltin()) {
          final String errorMessage =
              String.format(
                  "Failed to register Plugin %s, because the given Plugin name is the same as a built-in Plugin name.",
                  pluginName);
          LOGGER.warn(errorMessage);
          return Response.serverError().entity(errorMessage).build();
        }

        // otherwise the plugin has been registered
        final String errorMessage =
            String.format(
                "Failed to register Plugin %s, because the Plugin has been registered.",
                pluginName);
        LOGGER.warn(errorMessage);
        return Response.serverError().entity(errorMessage).build();
      }

      // get the plugin jar md5
      final String jarMD5 =
          jarMD5FromDB == null
              ? DigestUtils.md5Hex(
                  Files.newInputStream(Paths.get(PluginFileUtils.getPluginJarFilePath(jarName))))
              : jarMD5FromDB;

      // If the {pluginName} directory already exists, delete the directory and the files under it,
      // recreate the directory, and move the files to the new directory. If an exception occurs in
      // the middle, delete the created directory.
      final String pluginJarFileNameWithMD5 =
          PluginFileUtils.getPluginJarFileNameWithMD5(jarName, jarMD5);
      PluginFileUtils.savePluginToInstallDir(pluginName, jarName, pluginJarFileNameWithMD5);

      // create and save plugin class loader
      final PluginClassLoader classLoader =
          classLoaderManager.createPluginClassLoader(
              PluginFileUtils.getPluginInstallDirPath(pluginName));

      final Class<?> pluginClass = Class.forName(className, true, classLoader);
      @SuppressWarnings("unused") // ensure that it is a PipePlugin class
      final PipePlugin ignored = (PipePlugin) pluginClass.getDeclaredConstructor().newInstance();

      classLoaderManager.addPluginClassLoader(pluginName, classLoader);
      metaKeeper.addPipePluginMeta(
          pluginName, new PluginMeta(pluginName, className, false, jarName, jarMD5));
      metaKeeper.addJarNameAndMd5(jarName, jarMD5);

      // storage registered plugin info
      if (isRestRequest) {
        PersistenceService.plugin()
            .ifPresent(
                pluginPersistence ->
                    pluginPersistence.tryPersistencePlugin(pluginName, className, jarName, jarMD5));
      }

      final String successMessage = String.format("Successfully register Plugin %s", pluginName);
      LOGGER.info(successMessage);
      return Response.ok().entity(successMessage).build();
    } catch (final Exception e) {
      final String errorMessage =
          String.format("Failed to register Plugin %s, because %s", pluginName, e);
      LOGGER.warn(errorMessage);
      return Response.serverError().entity(errorMessage).build();
    }
  }

  public synchronized Response dropPlugin(final String pluginName) {
    try {
      final PluginMeta information = metaKeeper.getPipePluginMeta(pluginName);
      if (Objects.nonNull(information) && information.isBuiltin()) {
        final String errorMessage =
            String.format("Failed to deregister builtin Plugin %s.", pluginName);
        LOGGER.warn(errorMessage);
        return Response.serverError().entity(errorMessage).build();
      }

      // if it is needed to delete jar file of the plugin, delete both jar file and md5
      final String installedFileName =
          FilenameUtils.getBaseName(information.getJarName())
              + "-"
              + information.getJarMD5()
              + "."
              + FilenameUtils.getExtension(information.getJarName());
      PluginFileUtils.removePluginFileUnderLibRoot(information.getPluginName(), installedFileName);

      // remove anyway
      metaKeeper.removeJarNameAndMd5IfPossible(pluginName);
      metaKeeper.removePipePluginMeta(pluginName);
      classLoaderManager.removePluginClassLoader(pluginName);

      // remove plugin info from sqlite
      PersistenceService.plugin()
          .ifPresent(pluginPersistence -> pluginPersistence.tryDeletePlugin(pluginName));

      final String successMessage = String.format("Successfully deregister Plugin %s", pluginName);
      LOGGER.info(successMessage);
      return Response.ok().entity(successMessage).build();
    } catch (final IOException e) {
      final String errorMessage =
          String.format(
              "Failed to deregister builtin Plugin %s, because %s", pluginName, e.getMessage());
      LOGGER.warn(errorMessage);
      return Response.serverError().entity(errorMessage).build();
    }
  }

  public Response showPlugin() {
    final Iterable<PluginMeta> pluginMetas = metaKeeper.getAllPipePluginMeta();
    return Response.ok().entity(pluginMetas).build();
  }

  @Override
  public void close() throws Exception {}
}
