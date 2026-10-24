package dev.kinesis.core.expr

/**
 * What the rest of the app needs from a JavaScript engine for expressions. [ExpressionEngine] (Rhino) is the only
 * implementation; the interface keeps callers independent of it so another engine can be tried behind it
 * (see docs/expressions-plan.md, section 1.2).
 */
interface ExpressionBackend {
    /** Returns the error of [source] (syntax, or a failing run against [sample]), or null when it is valid. */
    fun check(source: String, sample: ExprInput? = null): ExpressionError?

    /** Evaluates [source]; the result is the value of the last evaluated statement. Throws [ExpressionError]. */
    fun evaluate(source: String, input: ExprInput): ExprResult
}
