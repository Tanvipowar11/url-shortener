package com.example.urlshortener.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class Base62Test {
    @Test
    void roundTrip() {
        for (long v : new long[] {0, 1, 61, 62, 1_000_000_000L, Long.MAX_VALUE / 2}) {
            assertEquals(v, Base62.decode(Base62.encode(v)));
        }
    }

    @Test
    void knownValues() {
        assertEquals("0", Base62.encode(0));
        assertEquals("z", Base62.encode(61));
        assertEquals("10", Base62.encode(62));
    }

    @Test
    void rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> Base62.encode(-1));
        assertThrows(IllegalArgumentException.class, () -> Base62.decode("ab$"));
    }
}
