package org.apache.iotdb.collector.plugin.api.customizer;

import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;

import java.util.Map;

public class CollectorParameters extends PipeParameters {
  public CollectorParameters(final Map<String, String> attributes) {
    super(attributes);
    this.attributes.forEach(
        (key, value) -> {
          if (!"taskId".equals(key)) {
            attributes.put(key, value.replace("_", "-"));
          }
        });
  }
}
