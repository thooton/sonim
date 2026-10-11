package com.canvas.xp3.net;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * No scripting engine: decimal literals, parentheses and arithmetic, and that
 * is the whole of it.
 *
 * The agent needs to do sums out loud ("what do I need on the final to reach
 * 90") and a language model doing them in its head is the one part of an answer
 * that is confidently wrong. Handing it a calculator is cheap; handing it
 * anything that can evaluate code is not.
 */
public final class Calculator {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final String input;
    private int pos;
    private int depth;

    private Calculator(String input) {
        this.input = input;
    }

    public static String evaluate(String expression) {
        if (expression.length() > 1000) {
            throw new IllegalArgumentException("Expression limit: 1000 characters");
        }
        Calculator parser = new Calculator(expression);
        BigDecimal result = parser.sum();
        parser.space();
        if (parser.pos != expression.length()) {
            throw new IllegalArgumentException("Unexpected character at " + parser.pos);
        }
        return result.stripTrailingZeros().toPlainString();
    }

    private void space() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    }

    private boolean take(char c) {
        space();
        if (pos < input.length() && input.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private BigDecimal sum() {
        BigDecimal a = product();
        while (true) {
            if (take('+')) a = checked(a.add(product(), MC));
            else if (take('-')) a = checked(a.subtract(product(), MC));
            else return a;
        }
    }

    private BigDecimal product() {
        BigDecimal a = unary();
        while (true) {
            if (take('*')) a = checked(a.multiply(unary(), MC));
            else if (take('/')) a = checked(a.divide(unary(), MC));
            else return a;
        }
    }

    private BigDecimal unary() {
        if (++depth > 64) throw new IllegalArgumentException("Expression nesting limit: 64");
        try {
            if (take('+')) return unary();
            if (take('-')) return unary().negate();
            return power();
        } finally {
            depth--;
        }
    }

    private BigDecimal power() {
        BigDecimal a = primary();
        if (take('^')) {
            int n = unary().intValueExact();
            if (n < -100 || n > 100) {
                throw new IllegalArgumentException("Exponent must be an integer from -100 to 100");
            }
            a = n < 0 ? BigDecimal.ONE.divide(a.pow(-n, MC), MC) : a.pow(n, MC);
        }
        return checked(a);
    }

    private BigDecimal primary() {
        if (take('(')) {
            BigDecimal a = sum();
            if (!take(')')) throw new IllegalArgumentException("Missing closing parenthesis");
            return a;
        }
        space();
        int start = pos;
        while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
            pos++;
        }
        if (start == pos) throw new IllegalArgumentException("Expected a number at " + pos);
        return checked(new BigDecimal(input.substring(start, pos), MC));
    }

    private static BigDecimal checked(BigDecimal n) {
        if (Math.abs((long) n.scale()) > 10000 || n.precision() > 10000) {
            throw new IllegalArgumentException("Calculation exceeds numeric limits");
        }
        return n;
    }
}
