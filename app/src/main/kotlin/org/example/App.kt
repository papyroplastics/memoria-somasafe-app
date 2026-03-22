package org.example

import android.app.Activity
import android.os.Bundle

class App : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        greet("Android")
    }
}
