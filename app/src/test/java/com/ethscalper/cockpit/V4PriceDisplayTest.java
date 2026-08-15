package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class V4PriceDisplayTest {
    @Test public void compactUiValueUsesBoundedPrecisionWithoutEllipsis() {
        assertEquals("0,295171", V4PriceDisplay.compact(0.29517143));
        assertEquals("142,3579", V4PriceDisplay.compact(142.35789123));
        assertEquals("2366,2", V4PriceDisplay.compact(2366.2));
        assertEquals("1,23E-8", V4PriceDisplay.compact(0.0000000123));
    }

    @Test public void clipboardValueRetainsCompleteEngineDecimal() {
        assertEquals("0.29517143", V4PriceDisplay.exact(0.29517143));
        assertEquals("142.35789123", V4PriceDisplay.exact(142.35789123));
        assertNotEquals(V4PriceDisplay.compact(0.29517143), V4PriceDisplay.exact(0.29517143));
    }

    @Test(expected=IllegalArgumentException.class) public void nonFinitePriceIsRejected() {
        V4PriceDisplay.exact(Double.NaN);
    }
}
