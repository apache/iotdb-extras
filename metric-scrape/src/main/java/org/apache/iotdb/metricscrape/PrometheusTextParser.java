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

package org.apache.iotdb.metricscrape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PrometheusTextParser {

  private static final String[] METRIC_FAMILY_SAMPLE_SUFFIXES = {
    "_sum", "_count", "_bucket", "_created"
  };

  public List<PrometheusSample> parse(String text, long defaultTimestamp) {
    if (text == null) {
      throw new IllegalArgumentException("Prometheus text should not be null");
    }
    List<PrometheusSample> samples = new ArrayList<>();
    String[] lines = text.split("\\r\\n|\\n|\\r");
    Set<String> helpMetricNames = collectHelpMetricNames(lines);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      samples.add(parseSample(line, i + 1, defaultTimestamp, helpMetricNames));
    }
    return samples;
  }

  private PrometheusSample parseSample(
      String line, int lineNumber, long defaultTimestamp, Set<String> helpMetricNames) {
    Parser parser = new Parser(line, lineNumber);
    String metricName = parser.parseMetricName();
    Map<String, String> labels = parser.parseLabels();
    parser.skipWhitespace();
    double value = parser.parseValue();
    parser.skipWhitespace();
    long timestamp = parser.parseOptionalTimestamp(defaultTimestamp);
    parser.skipWhitespace();
    parser.expectEnd();
    return new PrometheusSample(
        resolveMetricFamilyName(metricName, helpMetricNames), metricName, labels, value, timestamp);
  }

  private static Set<String> collectHelpMetricNames(String[] lines) {
    Set<String> helpMetricNames = new LinkedHashSet<>();
    for (String rawLine : lines) {
      String line = rawLine.trim();
      if (!line.startsWith("# HELP")) {
        continue;
      }
      int position = "# HELP".length();
      while (position < line.length() && Character.isWhitespace(line.charAt(position))) {
        position++;
      }
      int start = position;
      while (position < line.length() && !Character.isWhitespace(line.charAt(position))) {
        position++;
      }
      if (start < position) {
        helpMetricNames.add(line.substring(start, position));
      }
    }
    return helpMetricNames;
  }

  private static String resolveMetricFamilyName(String metricName, Set<String> helpMetricNames) {
    if (helpMetricNames.contains(metricName)) {
      return metricName;
    }

    String result = null;
    for (String helpMetricName : helpMetricNames) {
      if (isMetricFamilyComponent(metricName, helpMetricName)
          && (result == null || helpMetricName.length() > result.length())) {
        result = helpMetricName;
      }
    }
    return result == null ? metricName : result;
  }

  private static boolean isMetricFamilyComponent(String metricName, String helpMetricName) {
    for (String suffix : METRIC_FAMILY_SAMPLE_SUFFIXES) {
      if (metricName.equals(helpMetricName + suffix)) {
        return true;
      }
    }
    return false;
  }

  private static double parsePrometheusDouble(String token, int lineNumber) {
    String lowerToken = token.toLowerCase(Locale.ROOT);
    switch (lowerToken) {
      case "inf":
      case "+inf":
      case "infinity":
      case "+infinity":
        return Double.POSITIVE_INFINITY;
      case "-inf":
      case "-infinity":
        return Double.NEGATIVE_INFINITY;
      case "nan":
      case "+nan":
      case "-nan":
        return Double.NaN;
      default:
        break;
    }
    if (token.endsWith("d") || token.endsWith("D") || token.endsWith("f") || token.endsWith("F")) {
      throw new IllegalArgumentException(
          "Illegal Prometheus sample value " + token + " at line " + lineNumber);
    }
    try {
      return Double.parseDouble(removeValidNumericUnderscores(token, lineNumber));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Illegal Prometheus sample value " + token + " at line " + lineNumber, e);
    }
  }

  private static String removeValidNumericUnderscores(String token, int lineNumber) {
    if (token.indexOf('_') < 0) {
      return token;
    }
    for (int i = 0; i < token.length(); i++) {
      if (token.charAt(i) == '_') {
        if (i == 0
            || i == token.length() - 1
            || !Character.isDigit(token.charAt(i - 1))
            || !Character.isDigit(token.charAt(i + 1))) {
          throw new IllegalArgumentException(
              "Illegal Prometheus sample value " + token + " at line " + lineNumber);
        }
      }
    }
    return token.replace("_", "");
  }

  private static class Parser {
    private final String line;
    private final int lineNumber;
    private int position;

    private Parser(String line, int lineNumber) {
      this.line = line;
      this.lineNumber = lineNumber;
    }

    private String parseMetricName() {
      if (position >= line.length() || Character.isWhitespace(line.charAt(position))) {
        throw new IllegalArgumentException("Illegal Prometheus metric name at line " + lineNumber);
      }
      int start = position;
      while (position < line.length()
          && !Character.isWhitespace(line.charAt(position))
          && line.charAt(position) != '{') {
        position++;
      }
      if (start == position) {
        throw new IllegalArgumentException("Illegal Prometheus metric name at line " + lineNumber);
      }
      return line.substring(start, position);
    }

    private Map<String, String> parseLabels() {
      Map<String, String> labels = new LinkedHashMap<>();
      if (position >= line.length() || line.charAt(position) != '{') {
        return labels;
      }
      position++;
      skipWhitespace();
      if (position < line.length() && line.charAt(position) == '}') {
        position++;
        return labels;
      }

      while (position < line.length()) {
        String name = parseLabelName();
        skipWhitespace();
        expect('=');
        skipWhitespace();
        String value = parseLabelValue();
        if (labels.put(name, value) != null) {
          throw new IllegalArgumentException(
              "Duplicate Prometheus label " + name + " at line " + lineNumber);
        }
        skipWhitespace();
        if (position < line.length() && line.charAt(position) == ',') {
          position++;
          skipWhitespace();
          if (position < line.length() && line.charAt(position) == '}') {
            position++;
            return labels;
          }
          continue;
        }
        expect('}');
        return labels;
      }
      throw new IllegalArgumentException("Unclosed Prometheus label set at line " + lineNumber);
    }

    private String parseLabelName() {
      int start = position;
      while (position < line.length() && line.charAt(position) != '=') {
        if (line.charAt(position) == ',' || line.charAt(position) == '}') {
          break;
        }
        position++;
      }
      String name = line.substring(start, position).trim();
      if (name.isEmpty()) {
        throw new IllegalArgumentException("Illegal Prometheus label name at line " + lineNumber);
      }
      return name;
    }

    private String parseLabelValue() {
      expect('"');
      StringBuilder builder = new StringBuilder();
      int delimiterDepth = 0;
      while (position < line.length()) {
        char c = line.charAt(position++);
        if (c == '"') {
          if (delimiterDepth == 0 && isLabelValueTerminator()) {
            return builder.toString();
          }
          builder.append(c);
          continue;
        }
        if (c == '\\') {
          if (position >= line.length()) {
            throw new IllegalArgumentException(
                "Illegal Prometheus label escape at line " + lineNumber);
          }
          char escaped = line.charAt(position++);
          switch (escaped) {
            case 'n':
              builder.append('\n');
              break;
            case '\\':
            case '"':
              builder.append(escaped);
              break;
            default:
              throw new IllegalArgumentException(
                  "Illegal Prometheus label escape at line " + lineNumber);
          }
        } else {
          builder.append(c);
          delimiterDepth = updateDelimiterDepth(delimiterDepth, c);
        }
      }
      throw new IllegalArgumentException("Unclosed Prometheus label value at line " + lineNumber);
    }

    private int updateDelimiterDepth(int delimiterDepth, char c) {
      switch (c) {
        case '{':
        case '[':
        case '(':
          return delimiterDepth + 1;
        case '}':
        case ']':
        case ')':
          return Math.max(0, delimiterDepth - 1);
        default:
          return delimiterDepth;
      }
    }

    private boolean isLabelValueTerminator() {
      int next = skipWhitespace(position);
      if (next >= line.length()) {
        return true;
      }
      if (line.charAt(next) == '}') {
        return next + 1 >= line.length() || Character.isWhitespace(line.charAt(next + 1));
      }
      if (line.charAt(next) != ',') {
        return false;
      }
      int afterComma = skipWhitespace(next + 1);
      return afterComma >= line.length()
          || line.charAt(afterComma) == '}'
          || isLabelAssignmentStart(afterComma);
    }

    private boolean isLabelAssignmentStart(int start) {
      if (start >= line.length() || line.charAt(start) == '"') {
        return false;
      }
      int scan = start;
      while (scan < line.length()) {
        char c = line.charAt(scan);
        if (c == '=') {
          String labelName = line.substring(start, scan).trim();
          int valueStart = skipWhitespace(scan + 1);
          return !labelName.isEmpty()
              && valueStart < line.length()
              && line.charAt(valueStart) == '"';
        }
        if (c == ',' || c == '}') {
          return false;
        }
        scan++;
      }
      return false;
    }

    private double parseValue() {
      return parsePrometheusDouble(parseToken("sample value"), lineNumber);
    }

    private long parseOptionalTimestamp(long defaultTimestamp) {
      if (position >= line.length()) {
        return defaultTimestamp;
      }
      String token = parseToken("sample timestamp");
      try {
        return Long.parseLong(token);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "Illegal Prometheus sample timestamp " + token + " at line " + lineNumber, e);
      }
    }

    private String parseToken(String name) {
      if (position >= line.length() || Character.isWhitespace(line.charAt(position))) {
        throw new IllegalArgumentException("Missing Prometheus " + name + " at line " + lineNumber);
      }
      int start = position;
      while (position < line.length() && !Character.isWhitespace(line.charAt(position))) {
        position++;
      }
      return line.substring(start, position);
    }

    private void skipWhitespace() {
      while (position < line.length() && Character.isWhitespace(line.charAt(position))) {
        position++;
      }
    }

    private int skipWhitespace(int from) {
      while (from < line.length() && Character.isWhitespace(line.charAt(from))) {
        from++;
      }
      return from;
    }

    private void expect(char expected) {
      if (position >= line.length() || line.charAt(position) != expected) {
        throw new IllegalArgumentException(
            "Expected '" + expected + "' in Prometheus sample at line " + lineNumber);
      }
      position++;
    }

    private void expectEnd() {
      if (position != line.length()) {
        throw new IllegalArgumentException(
            "Unexpected Prometheus sample content at line " + lineNumber);
      }
    }
  }
}
