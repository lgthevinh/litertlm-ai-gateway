package org.thingai.app.aigateway.lm.tool.builtin

import com.google.gson.JsonParser
import org.thingai.app.aigateway.lm.tool.GatewayTool
import javax.script.ScriptEngineManager

/**
 * Evaluates a mathematical expression and returns the numeric result.
 *
 * Tool name: `calculator`
 */
class CalculatorTool : GatewayTool {

    override val name = "calculator"

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "calculator",
          "description": "Evaluate a mathematical expression and return the numeric result. Supports +, -, *, /, %, parentheses, Math.sqrt(), Math.pow(), Math.PI, Math.E, etc.",
          "parameters": {
            "type": "object",
            "properties": {
              "expression": {
                "type": "string",
                "description": "A mathematical expression, e.g. (3 + 5) * 2, Math.sqrt(16), Math.pow(2, 10)"
              }
            },
            "required": ["expression"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params     = JsonParser.parseString(paramsJsonString).asJsonObject
            val expression = params.get("expression")?.asString?.trim()
                ?: return """{"error": "missing required parameter 'expression'"}"""

            if (expression.isBlank()) return """{"error": "expression is empty"}"""

            if (!isSafeExpression(expression)) {
                return """{"error": "expression contains disallowed characters or keywords"}"""
            }

            val engine = ScriptEngineManager().getEngineByName("js")
            val result = if (engine != null) {
                engine.eval(expression)?.toString()
                    ?: return """{"error": "expression evaluated to null"}"""
            } else {
                evalExpr(expression.replace(" ", ""), 0).first.let { num ->
                    if (num == num.toLong().toDouble()) num.toLong().toString() else num.toString()
                }
            }
            """{"result": $result}"""
        } catch (e: Exception) {
            """{"error": "${e.message?.replace("\"", "'")}"}"""
        }
    }

    private fun isSafeExpression(expr: String): Boolean {
        val dangerous = listOf("import", "require", "eval", "function", "var ", "let ",
            "const ", "class ", "new ", "this", "window", "document", "process",
            "System", "Runtime", "exec", "File", "Thread", "java", "javax")
        return dangerous.none { expr.lowercase().contains(it) }
    }

    private fun evalExpr(expr: String, pos: Int): Pair<Double, Int> {
        var (left, p) = evalTerm(expr, pos)
        while (p < expr.length && (expr[p] == '+' || expr[p] == '-')) {
            val op = expr[p]; val (right, np) = evalTerm(expr, p + 1)
            left = if (op == '+') left + right else left - right; p = np
        }
        return left to p
    }

    private fun evalTerm(expr: String, pos: Int): Pair<Double, Int> {
        var (left, p) = evalFactor(expr, pos)
        while (p < expr.length && (expr[p] == '*' || expr[p] == '/' || expr[p] == '%')) {
            val op = expr[p]; val (right, np) = evalFactor(expr, p + 1)
            left = when (op) {
                '*'  -> left * right
                '/'  -> if (right == 0.0) throw ArithmeticException("Division by zero") else left / right
                else -> left % right
            }; p = np
        }
        return left to p
    }

    private fun evalFactor(expr: String, pos: Int): Pair<Double, Int> {
        var p = pos
        if (p < expr.length && expr[p] == '-') { val (v, np) = evalFactor(expr, p + 1); return (-v) to np }
        if (p < expr.length && expr[p] == '(') {
            val (v, np) = evalExpr(expr, p + 1)
            return v to (if (np < expr.length && expr[np] == ')') np + 1 else np)
        }
        val start = p
        while (p < expr.length && (expr[p].isDigit() || expr[p] == '.')) p++
        if (p == start) throw NumberFormatException("Expected number at pos $p")
        return expr.substring(start, p).toDouble() to p
    }
}
