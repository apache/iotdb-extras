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

package org.apache.iotdb.spark.table.db.read

import org.apache.spark.sql.connector.expressions.Expressions
import org.apache.spark.sql.connector.expressions.filter.{AlwaysFalse, AlwaysTrue, And, Not, Or, Predicate}
import org.apache.spark.sql.sources.EqualTo
import org.junit.Assert
import org.scalatest.FunSuite

import java.sql.Date

class PushDownPredicateSQLBuilderTest extends FunSuite {
  private val builder = new IoTDBExpressionSQLBuilder
	test("testBuildIoTDBSQL") {
    Assert.assertEquals("(\"s1\" IS NULL)", builder.build(new Predicate("IS_NULL", Array(Expressions.column("s1")))))
    Assert.assertEquals("(\"s`1\" IS NULL)", builder.build(new Predicate("IS_NULL", Array(Expressions.column("`s``1`")))))
    Assert.assertEquals("(\"s\"\"1\" IS NULL)", builder.build(new Predicate("IS_NULL", Array(Expressions.column("`s\"1`")))))

    Assert.assertEquals("(\"s1\" IS NOT NULL)", builder.build(new Predicate("IS_NOT_NULL", Array(Expressions.column("s1")))))

    Assert.assertEquals("(ends_with(\"s1\", \"s2\"))", builder.build(new Predicate("ENDS_WITH", Array(Expressions.column("s1"), Expressions.column("s2")))))
    Assert.assertEquals("(ends_with(\"s1\", 'value1'))", builder.build(new Predicate("ENDS_WITH", Array(Expressions.column("s1"), Expressions.literal("value1")))))
    Assert.assertEquals("(ends_with(\"s1\", 'va''lue1'))", builder.build(new Predicate("ENDS_WITH", Array(Expressions.column("s1"), Expressions.literal("va'lue1")))))

    Assert.assertEquals("(starts_with(\"s1\", \"s2\"))", builder.build(new Predicate("STARTS_WITH", Array(Expressions.column("s1"), Expressions.column("s2")))))
    Assert.assertEquals("(starts_with(\"s1\", 'value1'))", builder.build(new Predicate("STARTS_WITH", Array(Expressions.column("s1"), Expressions.literal("value1")))))
    Assert.assertEquals("(starts_with(\"s1\", 'va''lue1'))", builder.build(new Predicate("STARTS_WITH", Array(Expressions.column("s1"), Expressions.literal("va'lue1")))))

    Assert.assertThrows(classOf[UnsupportedOperationException], () => builder.build(new Predicate("CONTAINS", Array(Expressions.column("s1"), Expressions.column("s2")))))
    Assert.assertEquals("(\"s1\" LIKE '%value1%')", builder.build(new Predicate("CONTAINS", Array(Expressions.column("s1"), Expressions.literal("value1")))))
    Assert.assertEquals("(\"s1\" LIKE '%va''lue1%')", builder.build(new Predicate("CONTAINS", Array(Expressions.column("s1"), Expressions.literal("va'lue1")))))

    Assert.assertEquals("(\"s1\" IN (1,2,3))", builder.build(new Predicate("IN", Array(Expressions.column("s1"), Expressions.literal(1), Expressions.literal(2), Expressions.literal(3)))))
    Assert.assertEquals("(\"s1\" IN ('value1','value2','val''ue3'))", builder.build(new Predicate("IN", Array(Expressions.column("s1"), Expressions.literal("value1"), Expressions.literal("value2"), Expressions.literal("val\'ue3")))))

    Assert.assertEquals("(\"s1\" = 1)", builder.build(new Predicate("=", Array(Expressions.column("s1"), Expressions.literal(1)))))
    Assert.assertEquals("(\"s1\" = 1)", builder.build(new Predicate("=", Array(Expressions.column("s1"), Expressions.literal(1.toShort)))))
    Assert.assertEquals("(\"s1\" = 1)", builder.build(new Predicate("=", Array(Expressions.column("s1"), Expressions.literal(1.toByte)))))
    Assert.assertEquals("(\"s1\" = 'val''ue1')", builder.build(new Predicate("=", Array(Expressions.column("s1"), Expressions.literal("val'ue1")))))
    Assert.assertEquals("(\"s1\" = X'010101')", builder.build(new Predicate("=", Array(Expressions.column("s1"), Expressions.literal(Array(1.toByte, 1.toByte, 1.toByte))))))
    // If you meet error on jdk17, add '--add-opens=java.base/sun.util.calendar=ALL-UNNAMED' to VM options
    Assert.assertEquals("(\"s1\" = CAST('2025-01-01' as DATE))", builder.build(EqualTo("s1", Date.valueOf("2025-01-01")).toV2))

    Assert.assertEquals("(\"s1\" != 1)", builder.build(new Predicate("<>", Array(Expressions.column("s1"), Expressions.literal(1)))))
    Assert.assertEquals("(\"s1\" < 1)", builder.build(new Predicate("<", Array(Expressions.column("s1"), Expressions.literal(1)))))
    Assert.assertEquals("(\"s1\" <= 1)", builder.build(new Predicate("<=", Array(Expressions.column("s1"), Expressions.literal(1)))))
    Assert.assertEquals("(\"s1\" > 1)", builder.build(new Predicate(">", Array(Expressions.column("s1"), Expressions.literal(1)))))
    Assert.assertEquals("(\"s1\" >= 1)", builder.build(new Predicate(">=", Array(Expressions.column("s1"), Expressions.literal(1)))))
    Assert.assertThrows(classOf[UnsupportedOperationException], () => builder.build(new Predicate("<=>", Array(Expressions.column("s1"), Expressions.literal(1)))))

    Assert.assertEquals("((\"time\" = 1) AND (\"s1\" = 1))", builder.build(new And(new Predicate("=", Array(Expressions.column("time"), Expressions.literal(1L))), new Predicate("=", Array(Expressions.column("s1"), Expressions.literal(1))))))
    Assert.assertEquals("((\"time\" = 1) OR (\"s1\" = 1))", builder.build(new Or(new Predicate("=", Array(Expressions.column("time"), Expressions.literal(1L))), new Predicate("=", Array(Expressions.column("s1"), Expressions.literal(1))))))
    Assert.assertEquals("(NOT (\"s1\" = 1))", builder.build(new Not(new Predicate("=", Array(Expressions.column("s1"), Expressions.literal(1))))))
    Assert.assertEquals("(true)", builder.build(new AlwaysTrue))
    Assert.assertEquals("(false)", builder.build(new AlwaysFalse))
  }

}
