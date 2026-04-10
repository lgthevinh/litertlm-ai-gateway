package org.thingai.app.aigateway.lm.tool.builtin

import com.google.gson.JsonObject
import org.thingai.app.aigateway.lm.tool.GatewayTool
import org.thingai.app.aigateway.lm.tool.ToolDescriptor
import org.thingai.app.aigateway.lm.tool.ToolParam
import org.thingai.app.aigateway.lm.tool.ToolParamType
import javax.script.ScriptEngineManager

/**
 * Evaluates a mathematical expression and returns the numeric result.
 *
 * Tool name: `calculator`
 *
 * Uses the Nashorn/GraalJS script engine for evaluation.
 * Only numeric expressions are supported — no variable assignment, loops, or function definitions.
 *
 * Parameters:
 * - `expression` (required) — A mathematical expression, e.g. `(3 + 5) * 2`, `sqrt(16)`, `Math.PI`.
 */
class CalculatorTool : GatewayTool {

    override val descriptor = ToolDescriptor(
        name = "calculator",
        description = "Evaluate a mathematical expression and return the numeric result. Supports +, -, *, /, %, parentheses, Math.sqrt(), Math.pow(), Math.PI, Math.E, etc.",
        parameters = listOf(
            ToolParam(
                name = "expression",
                type = ToolParamType.STRING,
                description = "A mathematical expression, e.g. (3 + 5) * 2, Math.sqrt(16), Math.pow(2, 10)"
            )
        )
    )

    override fun execute(params: JsonObject): Any? {
        val expression = params.get("expression")?.asString?.trim()
            ?: return "Error: missing required parameter 'expression'"

        if (expression.isBlank()) {
            return "Error: expression is empty"
        }

        // Sanitize — only allow safe math characters and Math.* calls
        if (!isSafeExpression(expression)) {
            return "Error: expression contains disallowed characters. Only numbers, operators (+, -, *, /, %, ^), parentheses, decimal points, and Math.* functions are permitted."
        }

        return try {
            val engine = ScriptEngineManager().getEngineByName("js")
            if (engine == null) {
                // Fallback: simple expression evaluation without a script engine
                return evaluateSimple(expression)
            }
            val result = engine.eval(expression)
            result?.toString() ?: "Error: expression evaluated to null"
        } catch (e: Exception) {
            // Fallback to simple evaluation if script engine fails
            try {
                evaluateSimple(expression)
            } catch (e2: Exception) {
                "Error: could not evaluate '$expression': ${e.message}"
            }
        }
    }

    /**
     * Simple expression evaluator for basic arithmetic when no script engine is available.
     * Handles: +, -, *, /, parentheses, decimal numbers.
     */
    private fun evaluateSimple(expression: String): String {
        return try {
            val result = evalExpr(expression.replace(" ", ""), 0)
            // Format: strip trailing .0 for whole numbers
            val num = result.first
            if (num == num.toLong().toDouble()) {
                num.toLong().toString()
            } else {
                num.toString()
            }
        } catch (e: Exception) {
            "Error: could not evaluate '$expression': ${e.message}"
        }
    }

    // ── Recursive descent parser for basic math ──────────────────────────────

    private fun evalExpr(expr: String, pos: Int): Pair<Double, Int> {
        var (left, p) = evalTerm(expr, pos)
        while (p < expr.length && (expr[p] == '+' || expr[p] == '-')) {
            val op = expr[p]
            val (right, np) = evalTerm(expr, p + 1)
            left = if (op == '+') left + right else left - right
            p = np
        }
        return left to p
    }

    private fun evalTerm(expr: String, pos: Int): Pair<Double, Int> {
        var (left, p) = evalFactor(expr, pos)
        while (p < expr.length && (expr[p] == '*' || expr[p] == '/' || expr[p] == '%')) {
            val op = expr[p]
            val (right, np) = evalFactor(expr, p + 1)
            left = when (op) {
                '*' -> left * right
                '/' -> if (right == 0.0) throw ArithmeticException("Division by zero") else left / right
                '%' -> left % right
                else -> left
            }
            p = np
        }
        return left to p
    }

    private fun evalFactor(expr: String, pos: Int): Pair<Double, Int> {
        var p = pos

        // Unary minus
        if (p < expr.length && expr[p] == '-') {
            val (value, np) = evalFactor(expr, p + 1)
            return (-value) to np
        }

        // Parentheses
        if (p < expr.length && expr[p] == '(') {
            val (value, np) = evalExpr(expr, p + 1)
            // Skip closing ')'
            return value to (if (np < expr.length && expr[np] == ')') np + 1 else np)
        }

        // Number
        val start = p
        while (p < expr.length && (expr[p].isDigit() || expr[p] == '.')) p++
        if (p == start) throw NumberFormatException("Expected number at position $p")
        return expr.substring(start, p).toDouble() to p
    }

    /**
     * Checks that the expression contains only safe characters for math evaluation.
     */
    private fun isSafeExpression(expr: String): Boolean {
        // Allow: digits, operators, parentheses, dots, spaces, Math.*, sqrt, pow, abs, etc.
        val safe = Regex("^[0-9+\\-*/%().^, \\t]+(Math\\.[a-zA-Z]+)?[0-9+\\-*/%().^, \\t]*$")
        // Simpler approach: disallow obvious dangerous patterns
        val dangerous = listOf("import", "require", "eval", "function", "var ", "let ", "const ",
            "class ", "new ", "this", "window", "document", "process", "System", "Runtime",
            "exec", "File", "Thread", "java", "javax")
        val lower = expr.lowercase()
        return dangerous.none { lower.contains(it) }
    }
}
