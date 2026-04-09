package org.thingai.app.aigateway.utils

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class JsonUtils {
    companion object {
        private val gson = Gson()

        fun toJson(obj: Any): String {
            return gson.toJson(obj)
        }

        fun <T> fromJson(jsonStr: String): T {
            return gson.fromJson(jsonStr, object : TypeToken<T>() {}.type)
        }

        fun <T> fromJson(jsonStr: String, clazz: Class<T>): T {
            return gson.fromJson(jsonStr, clazz)
        }
    }
}