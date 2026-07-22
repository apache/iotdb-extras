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

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PrometheusTextParserTest {

  @Test
  public void parseSummarySamplesIntoHelpFamily() {
    String text =
        "# HELP request_duration_seconds Request duration.\n"
            + "# TYPE request_duration_seconds summary\n"
            + "request_duration_seconds_sum{method=\"query\",status=\"ok\"} 10.5\n"
            + "request_duration_seconds_count{method=\"query\",status=\"ok\"} 3 1000\n";

    List<PrometheusSample> samples = new PrometheusTextParser().parse(text, 123);

    assertEquals(2, samples.size());
    assertEquals("request_duration_seconds", samples.get(0).getMetricFamilyName());
    assertEquals("request_duration_seconds_sum", samples.get(0).getMetricName());
    assertEquals(10.5, samples.get(0).getValue(), 0.0);
    assertEquals(123, samples.get(0).getTimestamp());
    assertEquals(1000, samples.get(1).getTimestamp());
  }

  @Test
  public void parseLabelValueContainingQueryText() {
    String text = "statement_cost{type=\"select * from root where a=\\\"b\\\", x=1\"} 1.0";

    List<PrometheusSample> samples = new PrometheusTextParser().parse(text, 123);

    assertEquals(1, samples.size());
    assertEquals("select * from root where a=\"b\", x=1", samples.get(0).getLabels().get("type"));
  }

  @Test
  public void parseUnescapedQuoteInNestedLabelValueWithoutGhostLabels() {
    String text = "m{type=\"Filter{predicate=\"x\", op=\"AND\"}\",quantile=\"0.5\"} 1";

    List<PrometheusSample> samples = new PrometheusTextParser().parse(text, 123);

    assertEquals(1, samples.size());
    assertEquals("Filter{predicate=\"x\", op=\"AND\"}", samples.get(0).getLabels().get("type"));
    assertEquals("0.5", samples.get(0).getLabels().get("quantile"));
    assertFalse(samples.get(0).getLabels().containsKey("op"));
  }

  @Test
  public void onlyRouteKnownFamilyComponentSuffixesToHelpFamily() {
    String text =
        "# HELP http_requests Total requests.\n"
            + "http_requests 5\n"
            + "http_requests_duration_seconds 0.3\n"
            + "http_requests_sum 7\n";

    List<PrometheusSample> samples = new PrometheusTextParser().parse(text, 123);

    assertEquals("http_requests", samples.get(0).getMetricFamilyName());
    assertEquals("http_requests_duration_seconds", samples.get(1).getMetricFamilyName());
    assertEquals("http_requests", samples.get(2).getMetricFamilyName());
  }

  @Test
  public void parsePrometheusFloatTokens() {
    List<PrometheusSample> samples =
        new PrometheusTextParser().parse("a inf\nb -inf\nc nan\nd 1_000\n", 123);

    assertTrue(Double.isInfinite(samples.get(0).getValue()));
    assertTrue(Double.isInfinite(samples.get(1).getValue()));
    assertTrue(Double.isNaN(samples.get(2).getValue()));
    assertEquals(1000.0, samples.get(3).getValue(), 0.0);
  }

  @Test
  public void rejectJavaSpecificFloatSuffixes() {
    assertThrows(
        IllegalArgumentException.class, () -> new PrometheusTextParser().parse("a 1d\n", 123));
    assertThrows(
        IllegalArgumentException.class, () -> new PrometheusTextParser().parse("a 2f\n", 123));
  }

  @Test
  public void rejectUnknownLabelEscapes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PrometheusTextParser().parse("a{label=\"\\t\"} 1\n", 123));
  }
}
