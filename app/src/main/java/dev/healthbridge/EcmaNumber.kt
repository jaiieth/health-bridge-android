package dev.healthbridge

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

internal fun ecmaNumber(value: Double): String {
    if (!value.isFinite()) return "null"
    if (value == 0.0) return "0"
    if (abs(value) <= 9_007_199_254_740_991.0 && value == value.toLong().toDouble()) return value.toLong().toString()
    val positive = abs(value)
    val exact = BigDecimal(positive)
    var shortest: BigDecimal? = null
    for (precision in 1..17) {
        val rounded = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
        val step = BigDecimal.ONE.scaleByPowerOfTen(exact.precision() - exact.scale() - precision)
        val candidates = listOf(rounded, rounded - step, rounded + step)
            .filter { it.signum() > 0 && it.toDouble().toRawBits() == positive.toRawBits() }
        if (candidates.isNotEmpty()) {
            shortest = candidates.minWith(compareBy<BigDecimal> { (it - exact).abs() }
                .thenBy { if (it.stripTrailingZeros().unscaledValue().testBit(0)) 1 else 0 })
            break
        }
    }
    val decimal = checkNotNull(shortest).stripTrailingZeros()
    val digits = decimal.unscaledValue().toString()
    val exponent = digits.length - decimal.scale() - 1
    val unsigned = if (exponent in -6..20) decimal.toPlainString() else buildString {
        append(digits[0])
        if (digits.length > 1) { append('.'); append(digits.substring(1)) }
        append('e')
        if (exponent >= 0) append('+')
        append(exponent)
    }
    return if (value < 0) "-$unsigned" else unsigned
}
