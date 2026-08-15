package com.ethscalper.cockpit;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** UI-only price formatting: compact on screen, lossless round-trip text on copy. */
public final class V4PriceDisplay {
    private V4PriceDisplay() {}

    public static String exact(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("price");
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    public static String compact(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("price");
        if (value != 0 && Math.abs(value) < 0.000001) {
            return new DecimalFormat("0.###E0", DecimalFormatSymbols.getInstance(Locale.FRANCE)).format(value);
        }
        int decimals = Math.abs(value) >= 1_000 ? 2 : Math.abs(value) >= 1 ? 4 : 6;
        StringBuilder pattern = new StringBuilder("0");
        if (decimals > 0) {
            pattern.append('.');
            for (int i = 0; i < decimals; i++) pattern.append('#');
        }
        return new DecimalFormat(pattern.toString(), DecimalFormatSymbols.getInstance(Locale.FRANCE)).format(value);
    }
}
