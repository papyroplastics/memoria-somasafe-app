package org.example

import kotlin.test.Test
import kotlin.test.assertEquals

class AppTest {
    @Test fun greetReturnsCorrectString() {
        assertEquals("Hello, Android!", greet("Android"))
    }
}
