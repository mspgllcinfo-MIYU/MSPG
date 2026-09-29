package com.mspg.poicat

import android.content.Context
import java.io.File

object LocalJsonStore {
    fun write(context: Context, fileName: String, content: String) {
        File(context.filesDir, fileName).writeText(content)
    }

    fun read(context: Context, fileName: String): String? {
        val file = File(context.filesDir, fileName)
        return if (file.exists()) file.readText() else null
    }
}
