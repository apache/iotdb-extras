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

import org.apache.iotdb.spark.table.db.IoTDBUtils
import org.apache.spark.sql.connector.expressions.filter.{AlwaysFalse, AlwaysTrue, And, Not, Or, Predicate}
import org.apache.spark.sql.connector.expressions.{Expression, GeneralScalarExpression, Literal, NamedReference}
import org.apache.spark.sql.types.{ArrayType, CalendarIntervalType, MapType, NullType, ObjectType, StringType, StructType, UserDefinedType}

class IoTDBExpressionSQLBuilder {

  def build(predicate: Predicate): String = {
    s"(${buildIoTDBExpressionSQL(predicate)})"
  }

  private def buildIoTDBExpressionSQL(expression: Expression): String = {
    expression match {
      case literal: Literal[_] => visitLiteral(literal)
      case namedReference: NamedReference => visitNamedReference(namedReference)
      case _: AlwaysFalse => visitAlwaysFalse()
      case _: AlwaysTrue => visitAlwaysTrue()
      case or: Or => visitOr(or)
      case and: And => visitAnd(and)
      case not: Not => visitNot(not)
      case expr: GeneralScalarExpression => visitGeneralScalarExpression(expr)
      case _ => throw new UnsupportedOperationException("Unexpected V2 expression: " + expression)
    }
  }

  private def visitLiteral(literal: Literal[_]): String = {
    literal.dataType() match {
      case StringType => s"'${literal.value().toString}'"
      case _ => literal.value().toString
    }
  }

  private def visitNamedReference(namedRef: NamedReference): String = {
    IoTDBUtils.getIoTDBColumnIdentifierInSQL(namedRef.toString, true)
  }

  private def visitAlwaysFalse(): String = {
    "FALSE"
  }

  private def visitAlwaysTrue(): String = {
    "TRUE"
  }

  private def visitOr(or: Or): String = {
    s"(${buildIoTDBExpressionSQL(or.left())}) OR (${buildIoTDBExpressionSQL(or.right())})"
  }

  private def visitAnd(and: And): String = {
    s"(${buildIoTDBExpressionSQL(and.left())}) AND (${buildIoTDBExpressionSQL(and.right())})"
  }

  private def visitNot(not: Not): String = {
    s"NOT (${buildIoTDBExpressionSQL(not.child())})"
  }

  private def visitGeneralScalarExpression(expr: GeneralScalarExpression): String = {
    // <=> is unsupported
    expr.name() match {
      case "IS_NULL" => visitIsNull(expr)
      case "IS_NOT_NULL" => visitIsNotNull(expr)
      case "STARTS_WITH" => visitStartsWith(expr)
      case "ENDS_WITH" => visitEndsWith(expr)
      case "CONTAINS" => visitContains(expr)
      case "IN" => visitIn(expr)
      case "=" => visitEqualTo(expr)
      case "<>" => visitNotEqualTo(expr)
      case "<" => visitLess(expr)
      case "<=" => visitLessOrEqual(expr)
      case ">" => visitGreater(expr)
      case ">=" => visitGreaterOrEqual(expr)
      case _ => throw new UnsupportedOperationException("Unsupported push down expression: " + expr)
    }
  }

  private def visitIsNull(expr: Expression): String = {
    s"${buildIoTDBExpressionSQL(expr.children()(0))} IS NULL"
  }

  private def visitIsNotNull(expr: Expression): String = {
    s"${buildIoTDBExpressionSQL(expr.children()(0))} IS NOT NULL"
  }

  private def visitStartsWith(expr: Expression): String = {
    val leftExpr = buildIoTDBExpressionSQL(expr.children()(0))
    val rightExpr = buildIoTDBExpressionSQL(expr.children()(1))
    s"$leftExpr LIKE '${rightExpr.substring(1, rightExpr.length - 1)}%'"
  }

  private def visitEndsWith(expr: Expression): String = {
    val leftExpr = buildIoTDBExpressionSQL(expr.children()(0))
    val rightExpr = buildIoTDBExpressionSQL(expr.children()(1))
    s"$leftExpr LIKE '%${rightExpr.substring(1, rightExpr.length - 1)}'"
  }

  private def visitContains(expr: Expression): String = {
    val leftExpr = buildIoTDBExpressionSQL(expr.children()(0))
    val rightExpr = buildIoTDBExpressionSQL(expr.children()(1))
    s"$leftExpr LIKE '%${rightExpr.substring(1, rightExpr.length - 1)}%'"
  }

  private def visitIn(expr: Expression): String = {
    val expressions = expr.children()
    val leftExpr = buildIoTDBExpressionSQL(expressions(0))
    val rightExpr = expressions.slice(1, expressions.length).map(buildIoTDBExpressionSQL).mkString(",")
    s"$leftExpr IN ($rightExpr)"
  }

  private def visitEqualTo(expr: Expression): String = {
    s"${buildIoTDBExpressionSQL(expr.children()(0))} = ${buildIoTDBExpressionSQL(expr.children()(1))}"
  }

  private def visitNotEqualTo(expr: Expression): String = {
    s"${buildIoTDBExpressionSQL(expr.children()(0))} != ${buildIoTDBExpressionSQL(expr.children()(1))}"
  }

  private def visitLess(expr: Expression): String = {
    s"${buildIoTDBExpressionSQL(expr.children()(0))} < ${buildIoTDBExpressionSQL(expr.children()(1))}"
  }

  private def visitLessOrEqual(expr: Expression): String = {
    s"${buildIoTDBExpressionSQL(expr.children()(0))} <= ${buildIoTDBExpressionSQL(expr.children()(1))}"
  }

  private def visitGreater(expr: Expression): String = {
    s"${buildIoTDBExpressionSQL(expr.children()(0))} > ${buildIoTDBExpressionSQL(expr.children()(1))}"
  }

  private def visitGreaterOrEqual(expr: Expression): String = {
    s"${buildIoTDBExpressionSQL(expr.children()(0))} >= ${buildIoTDBExpressionSQL(expr.children()(1))}"
  }

}
