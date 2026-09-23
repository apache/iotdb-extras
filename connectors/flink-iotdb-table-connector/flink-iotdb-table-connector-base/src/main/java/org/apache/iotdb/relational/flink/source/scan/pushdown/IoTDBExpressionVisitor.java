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

package org.apache.iotdb.relational.flink.source.scan.pushdown;

import org.apache.iotdb.relational.flink.utils.IoTDBUtils;

import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.expressions.ExpressionVisitor;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.TypeLiteralExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinition;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.functions.FunctionDefinition;
import org.apache.flink.table.functions.FunctionKind;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts supported Flink scalar expressions into IoTDB SQL expressions. */
public class IoTDBExpressionVisitor implements ExpressionVisitor<String> {

  private static final Map<String, String> FLINK_TO_IOTDB_FUNCTION_NAMES;

  static {
    Map<String, String> functionNames = new HashMap<>();
    functionNames.put("abs", "abs");
    functionNames.put("acos", "acos");
    functionNames.put("asin", "asin");
    functionNames.put("atan", "atan");
    functionNames.put("ceil", "ceil");
    functionNames.put("charlength", "length");
    functionNames.put("concat", "concat");
    functionNames.put("cos", "cos");
    functionNames.put("cosh", "cosh");
    functionNames.put("degrees", "degrees");
    functionNames.put("e", "e");
    functionNames.put("exp", "exp");
    functionNames.put("floor", "floor");
    functionNames.put("greatest", "greatest");
    functionNames.put("least", "least");
    functionNames.put("length", "length");
    functionNames.put("ln", "ln");
    functionNames.put("log10", "log10");
    functionNames.put("lower", "lower");
    functionNames.put("lowercase", "lower");
    functionNames.put("pi", "pi");
    functionNames.put("radians", "radians");
    functionNames.put("regexp", "regexp_like");
    functionNames.put("replace", "replace");
    functionNames.put("round", "round");
    functionNames.put("sign", "sign");
    functionNames.put("sin", "sin");
    functionNames.put("sinh", "sinh");
    functionNames.put("sqrt", "sqrt");
    functionNames.put("substr", "substring");
    functionNames.put("substring", "substring");
    functionNames.put("tan", "tan");
    functionNames.put("tanh", "tanh");
    functionNames.put("trim", "trim");
    functionNames.put("upper", "upper");
    functionNames.put("uppercase", "upper");
    functionNames.put("bitand", "bitwise_and");
    functionNames.put("bitor", "bitwise_or");
    functionNames.put("bitxor", "bitwise_xor");
    functionNames.put("bitnot", "bitwise_not");
    functionNames.put("bitshiftleft", "bitwise_left_shift");
    functionNames.put("bitshiftright", "bitwise_right_shift");
    functionNames.put("to_base64", "to_base64");
    functionNames.put("from_base64", "from_base64");
    functionNames.put("to_hex", "to_hex");
    functionNames.put("from_hex", "from_hex");
    functionNames.put("md5", "md5");
    functionNames.put("sha1", "sha1");
    functionNames.put("sha256", "sha256");
    functionNames.put("sha512", "sha512");
    FLINK_TO_IOTDB_FUNCTION_NAMES = Collections.unmodifiableMap(functionNames);
  }

  @Override
  public String visit(CallExpression call) {
    if (call == null || call.getFunctionDefinition().getKind() != FunctionKind.SCALAR) {
      return null;
    }

    try {
      return visitGeneralScalarExpression(call);
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public String visit(ValueLiteralExpression valueLiteral) {
    return IoTDBUtils.renderLiteral(valueLiteral);
  }

  @Override
  public String visit(FieldReferenceExpression fieldReference) {
    if (fieldReference == null || fieldReference.getInputIndex() != 0) {
      return null;
    }
    return IoTDBUtils.quoteIdentifier(fieldReference.getName());
  }

  @Override
  public String visit(TypeLiteralExpression typeLiteral) {
    return renderType(typeLiteral);
  }

  @Override
  public String visit(Expression expression) {
    return null;
  }

  private String visitGeneralScalarExpression(CallExpression call) {
    if (!(call.getFunctionDefinition() instanceof BuiltInFunctionDefinition)) {
      return null;
    }

    List<ResolvedExpression> children = call.getResolvedChildren();
    String expressionName = getExpressionName(call);

    switch (expressionName) {
      case "=":
        return visitEqualTo(children);
      case "<>":
        return visitNotEqualTo(children);
      case "<":
        return visitLess(children);
      case "<=":
        return visitLessOrEqual(children);
      case ">":
        return visitGreater(children);
      case ">=":
        return visitGreaterOrEqual(children);
      case "+":
      case "-":
      case "*":
      case "/":
      case "%":
        return visitArithmeticBinary(expressionName, children);
      case "-u":
        return visitArithmeticUnary(children);
      case "and":
        return visitAnd(children);
      case "or":
        return visitOr(children);
      case "not":
        return visitNot(children);
      case "is_null":
        return visitIsNull(children);
      case "is_not_null":
        return visitIsNotNull(children);
      case "like":
        return visitLike(children);
      case "in":
        return visitIn(children);
      case "between":
        return visitBetween(children);
      case "not_between":
        return visitNotBetween(children);
      case "cast":
        return visitCast("CAST", children);
      case "try_cast":
        return visitCast("TRY_CAST", children);
      case "if":
        return visitIf(children);
      case "coalesce":
        return visitCoalesce(children);
      case "position":
        return visitPosition(children);
      case "locate":
        return visitLocate(children);
      case "instr":
        return visitInstr(children);
      case "ltrim":
        return visitTrim("LEADING", children);
      case "rtrim":
        return visitTrim("TRAILING", children);
      case "current_date":
        return visitCurrentValue("CAST(now() AS DATE)", children);
      case "current_timestamp":
      case "localtimestamp":
        return visitCurrentValue("now()", children);
      case "current_database":
      case "current_time":
      case "localtime":
        return null;
      default:
        return visitScalarFunction(expressionName, children);
    }
  }

  private String visitEqualTo(List<ResolvedExpression> children) {
    return visitBinary("=", children);
  }

  private String visitNotEqualTo(List<ResolvedExpression> children) {
    return visitBinary("<>", children);
  }

  private String visitLess(List<ResolvedExpression> children) {
    return visitBinary("<", children);
  }

  private String visitLessOrEqual(List<ResolvedExpression> children) {
    return visitBinary("<=", children);
  }

  private String visitGreater(List<ResolvedExpression> children) {
    return visitBinary(">", children);
  }

  private String visitGreaterOrEqual(List<ResolvedExpression> children) {
    return visitBinary(">=", children);
  }

  private String visitArithmeticBinary(String operator, List<ResolvedExpression> children) {
    return visitBinary(operator, children);
  }

  private String visitArithmeticUnary(List<ResolvedExpression> children) {
    return visitPrefix("-", children);
  }

  private String visitAnd(List<ResolvedExpression> children) {
    return visitVariadic("AND", children);
  }

  private String visitOr(List<ResolvedExpression> children) {
    return visitVariadic("OR", children);
  }

  private String visitNot(List<ResolvedExpression> children) {
    return visitPrefix("NOT", children);
  }

  private String visitIsNull(List<ResolvedExpression> children) {
    return visitSuffix("IS NULL", children);
  }

  private String visitIsNotNull(List<ResolvedExpression> children) {
    return visitSuffix("IS NOT NULL", children);
  }

  private String visitLike(List<ResolvedExpression> children) {
    return visitBinary("LIKE", children);
  }

  private String visitIn(List<ResolvedExpression> children) {
    if (children == null || children.size() < 2) {
      return null;
    }

    String value = buildIoTDBExpressionSQL(children.get(0));
    if (value == null) {
      return null;
    }

    StringBuilder builder = new StringBuilder("(").append(value).append(" IN (");
    for (int i = 1; i < children.size(); i++) {
      String child = buildIoTDBExpressionSQL(children.get(i));
      if (child == null) {
        return null;
      }
      if (i > 1) {
        builder.append(", ");
      }
      builder.append(child);
    }
    return builder.append("))").toString();
  }

  private String visitBetween(List<ResolvedExpression> children) {
    return visitBetween("BETWEEN", children);
  }

  private String visitNotBetween(List<ResolvedExpression> children) {
    return visitBetween("NOT BETWEEN", children);
  }

  private String visitBetween(String operator, List<ResolvedExpression> children) {
    if (children == null || children.size() != 3) {
      return null;
    }

    String value = buildIoTDBExpressionSQL(children.get(0));
    String lowerBound = buildIoTDBExpressionSQL(children.get(1));
    String upperBound = buildIoTDBExpressionSQL(children.get(2));
    if (value == null || lowerBound == null || upperBound == null) {
      return null;
    }
    return "(" + value + " " + operator + " " + lowerBound + " AND " + upperBound + ")";
  }

  private String visitCast(String keyword, List<ResolvedExpression> children) {
    if (children == null || children.size() != 2) {
      return null;
    }

    String value = buildIoTDBExpressionSQL(children.get(0));
    String type = renderType(children.get(1));
    if (value == null || type == null) {
      return null;
    }
    return keyword + "(" + value + " AS " + type + ")";
  }

  private String visitIf(List<ResolvedExpression> children) {
    return visitFunction("if", children);
  }

  private String visitCoalesce(List<ResolvedExpression> children) {
    return visitFunction("coalesce", children);
  }

  private String visitPosition(List<ResolvedExpression> children) {
    if (children == null || children.size() != 2) {
      return null;
    }
    return visitStringPosition(children.get(1), children.get(0));
  }

  private String visitLocate(List<ResolvedExpression> children) {
    if (children == null || children.size() != 2) {
      return null;
    }
    return visitStringPosition(children.get(1), children.get(0));
  }

  private String visitInstr(List<ResolvedExpression> children) {
    if (children == null || children.size() != 2) {
      return null;
    }
    return visitStringPosition(children.get(0), children.get(1));
  }

  private String visitStringPosition(
      ResolvedExpression valueExpression, ResolvedExpression searchExpression) {
    String value = buildIoTDBExpressionSQL(valueExpression);
    String search = buildIoTDBExpressionSQL(searchExpression);
    if (value == null || search == null) {
      return null;
    }
    return "strpos(" + value + ", " + search + ")";
  }

  private String visitScalarFunction(String functionName, List<ResolvedExpression> children) {
    String iotdbFunctionName = FLINK_TO_IOTDB_FUNCTION_NAMES.get(functionName);
    return iotdbFunctionName == null ? null : visitFunction(iotdbFunctionName, children);
  }

  private String visitFunction(String functionName, List<ResolvedExpression> children) {
    StringBuilder builder = new StringBuilder(functionName).append('(');
    if (children != null) {
      for (int i = 0; i < children.size(); i++) {
        String child = buildIoTDBExpressionSQL(children.get(i));
        if (child == null) {
          return null;
        }
        if (i > 0) {
          builder.append(", ");
        }
        builder.append(child);
      }
    }
    return builder.append(')').toString();
  }

  private String visitCurrentValue(String sql, List<ResolvedExpression> children) {
    return children == null || children.isEmpty() ? sql : null;
  }

  private String visitTrim(String specification, List<ResolvedExpression> children) {
    if (children == null || (children.size() != 1 && children.size() != 2)) {
      return null;
    }
    String value = buildIoTDBExpressionSQL(children.get(0));
    if (value == null) {
      return null;
    }
    if (children.size() == 1) {
      return "trim(" + specification + " FROM " + value + ")";
    }
    String trimCharacter = buildIoTDBExpressionSQL(children.get(1));
    if (trimCharacter == null) {
      return null;
    }
    return "trim(" + specification + " " + trimCharacter + " FROM " + value + ")";
  }

  private String visitBinary(String operator, List<ResolvedExpression> children) {
    if (children == null || children.size() != 2) {
      return null;
    }

    String left = buildIoTDBExpressionSQL(children.get(0));
    String right = buildIoTDBExpressionSQL(children.get(1));
    if (left == null || right == null) {
      return null;
    }
    return "(" + left + " " + operator + " " + right + ")";
  }

  private String visitVariadic(String operator, List<ResolvedExpression> children) {
    if (children == null || children.size() < 2) {
      return null;
    }

    StringBuilder builder = new StringBuilder("(");
    for (int i = 0; i < children.size(); i++) {
      String child = buildIoTDBExpressionSQL(children.get(i));
      if (child == null) {
        return null;
      }
      if (i > 0) {
        builder.append(' ').append(operator).append(' ');
      }
      builder.append(child);
    }
    return builder.append(')').toString();
  }

  private String visitPrefix(String operator, List<ResolvedExpression> children) {
    if (children == null || children.size() != 1) {
      return null;
    }

    String child = buildIoTDBExpressionSQL(children.get(0));
    return child == null ? null : "(" + operator + " " + child + ")";
  }

  private String visitSuffix(String operator, List<ResolvedExpression> children) {
    if (children == null || children.size() != 1) {
      return null;
    }

    String child = buildIoTDBExpressionSQL(children.get(0));
    return child == null ? null : "(" + child + " " + operator + ")";
  }

  private String buildIoTDBExpressionSQL(ResolvedExpression expression) {
    return expression == null ? null : expression.accept(this);
  }

  private String renderType(ResolvedExpression expression) {
    if (!(expression instanceof TypeLiteralExpression)) {
      return null;
    }
    try {
      return IoTDBUtils.toIoTDBDataType(expression.getOutputDataType()).name();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String getExpressionName(CallExpression call) {
    FunctionDefinition functionDefinition = call.getFunctionDefinition();

    if (BuiltInFunctionDefinitions.EQUALS.equals(functionDefinition)) {
      return "=";
    }
    if (BuiltInFunctionDefinitions.NOT_EQUALS.equals(functionDefinition)) {
      return "<>";
    }
    if (BuiltInFunctionDefinitions.LESS_THAN.equals(functionDefinition)) {
      return "<";
    }
    if (BuiltInFunctionDefinitions.LESS_THAN_OR_EQUAL.equals(functionDefinition)) {
      return "<=";
    }
    if (BuiltInFunctionDefinitions.GREATER_THAN.equals(functionDefinition)) {
      return ">";
    }
    if (BuiltInFunctionDefinitions.GREATER_THAN_OR_EQUAL.equals(functionDefinition)) {
      return ">=";
    }
    if (BuiltInFunctionDefinitions.PLUS.equals(functionDefinition)) {
      return "+";
    }
    if (BuiltInFunctionDefinitions.MINUS.equals(functionDefinition)) {
      return "-";
    }
    if (BuiltInFunctionDefinitions.TIMES.equals(functionDefinition)) {
      return "*";
    }
    if (BuiltInFunctionDefinitions.DIVIDE.equals(functionDefinition)) {
      return "/";
    }
    if (BuiltInFunctionDefinitions.MOD.equals(functionDefinition)) {
      return "%";
    }
    if (BuiltInFunctionDefinitions.MINUS_PREFIX.equals(functionDefinition)) {
      return "-u";
    }
    if (BuiltInFunctionDefinitions.AND.equals(functionDefinition)) {
      return "and";
    }
    if (BuiltInFunctionDefinitions.OR.equals(functionDefinition)) {
      return "or";
    }
    if (BuiltInFunctionDefinitions.NOT.equals(functionDefinition)) {
      return "not";
    }
    if (BuiltInFunctionDefinitions.IS_NULL.equals(functionDefinition)) {
      return "is_null";
    }
    if (BuiltInFunctionDefinitions.IS_NOT_NULL.equals(functionDefinition)) {
      return "is_not_null";
    }
    if (BuiltInFunctionDefinitions.LIKE.equals(functionDefinition)) {
      return "like";
    }
    if (BuiltInFunctionDefinitions.IN.equals(functionDefinition)) {
      return "in";
    }
    if (BuiltInFunctionDefinitions.BETWEEN.equals(functionDefinition)) {
      return "between";
    }
    if (BuiltInFunctionDefinitions.NOT_BETWEEN.equals(functionDefinition)) {
      return "not_between";
    }
    if (BuiltInFunctionDefinitions.CAST.equals(functionDefinition)) {
      return "cast";
    }
    if (BuiltInFunctionDefinitions.TRY_CAST.equals(functionDefinition)) {
      return "try_cast";
    }
    if (BuiltInFunctionDefinitions.IF_NULL.equals(functionDefinition)) {
      return "coalesce";
    }
    if (BuiltInFunctionDefinitions.IF.equals(functionDefinition)) {
      return "if";
    }
    if (BuiltInFunctionDefinitions.COALESCE.equals(functionDefinition)) {
      return "coalesce";
    }
    if (BuiltInFunctionDefinitions.CURRENT_DATABASE.equals(functionDefinition)) {
      return "current_database";
    }
    if (BuiltInFunctionDefinitions.CURRENT_DATE.equals(functionDefinition)) {
      return "current_date";
    }
    if (BuiltInFunctionDefinitions.CURRENT_TIME.equals(functionDefinition)) {
      return "current_time";
    }
    if (BuiltInFunctionDefinitions.CURRENT_TIMESTAMP.equals(functionDefinition)) {
      return "current_timestamp";
    }
    if (BuiltInFunctionDefinitions.LOCAL_TIME.equals(functionDefinition)) {
      return "localtime";
    }
    if (BuiltInFunctionDefinitions.LOCAL_TIMESTAMP.equals(functionDefinition)) {
      return "localtimestamp";
    }
    if (BuiltInFunctionDefinitions.NOW.equals(functionDefinition)) {
      return "current_timestamp";
    }

    String functionName = normalizeFunctionName(call.getFunctionName());
    return functionName;
  }

  private static String normalizeFunctionName(String functionName) {
    if (functionName == null) {
      return "";
    }
    int separatorIndex = functionName.lastIndexOf('.');
    if (separatorIndex >= 0) {
      functionName = functionName.substring(separatorIndex + 1);
    }
    return functionName.replace("`", "").toLowerCase(Locale.ROOT);
  }
}
