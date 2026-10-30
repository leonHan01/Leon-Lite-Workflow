package com.leon.workflow.handler.impl;

/** One parser per expression; no state or locks are shared between requests. */
final class ArithmeticExpression {
    private int pos;
    private final String expr;

    ArithmeticExpression(String expression) {
        this.expr = expression;
    }

    double evaluate() {
        double result = parseExpression();
        if (pos < expr.length()) {
            throw new IllegalArgumentException("Unexpected character at position " + pos);
        }
        return result;
    }

    // 加减
    private double parseExpression() {
        double result = parseTerm();
        while (pos < expr.length()) {
            char op = expr.charAt(pos);
            if (op == '+') {
                pos++;
                result += parseTerm();
            } else if (op == '-') {
                pos++;
                result -= parseTerm();
            } else {
                break;
            }
        }
        return result;
    }

    // 乘除
    private double parseTerm() {
        double result = parseFactor();
        while (pos < expr.length()) {
            char op = expr.charAt(pos);
            if (op == '*') {
                pos++;
                result *= parseFactor();
            } else if (op == '/') {
                pos++;
                result /= parseFactor();
            } else {
                break;
            }
        }
        return result;
    }

    // 数字和括号
    private double parseFactor() {
        if (pos < expr.length() && expr.charAt(pos) == '(') {
            pos++; // skip '('
            double result = parseExpression();
            if (pos < expr.length() && expr.charAt(pos) == ')') {
                pos++; // skip ')'
            }
            else { throw new IllegalArgumentException("Missing closing parenthesis"); }
            return result;
        }
        // 支持负号
        if (pos < expr.length() && expr.charAt(pos) == '-') {
            pos++;
            return -parseFactor();
        }
        return parseNumber();
    }

    private double parseNumber() {
        int start = pos;
        while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("Expected number at position " + pos);
        }
        return Double.parseDouble(expr.substring(start, pos));
    }

}
