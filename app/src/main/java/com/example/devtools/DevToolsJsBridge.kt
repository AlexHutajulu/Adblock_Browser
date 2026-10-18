package com.example.devtools

import android.webkit.JavascriptInterface
import android.util.Log
import org.json.JSONObject

class DevToolsJsBridge(
    private val onElementSelected: (SelectedElement) -> Unit,
    private val onConsoleBridgeMessage: (ConsoleEntry) -> Unit
) {
    private val TAG = "DevToolsJsBridge"

    @JavascriptInterface
    fun onElementSelected(json: String) {
        try {
            val root = JSONObject(json)
            val rectObj = root.optJSONObject("rect") ?: JSONObject()
            val rect = ElementRect(
                x = rectObj.optDouble("x", 0.0).toFloat(),
                y = rectObj.optDouble("y", 0.0).toFloat(),
                width = rectObj.optDouble("width", 0.0).toFloat(),
                height = rectObj.optDouble("height", 0.0).toFloat()
            )

            val classesList = mutableListOf<String>()
            val classesArray = root.optJSONArray("classes")
            if (classesArray != null) {
                for (i in 0 until classesArray.length()) {
                    classesList.add(classesArray.optString(i))
                }
            }

            val attributesMap = mutableMapOf<String, String>()
            val attrsObj = root.optJSONObject("attributes")
            if (attrsObj != null) {
                val keys = attrsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    attributesMap[key] = attrsObj.optString(key)
                }
            }

            val stylesMap = mutableMapOf<String, String>()
            val stylesObj = root.optJSONObject("computedStyles")
            if (stylesObj != null) {
                val keys = stylesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    stylesMap[key] = stylesObj.optString(key)
                }
            }

            val elementId = if (root.isNull("id")) null else root.optString("id").takeIf { it.isNotEmpty() && it != "null" }
            val element = SelectedElement(
                tagName = root.optString("tagName", "unknown").lowercase(),
                id = elementId,
                classes = classesList,
                textPreview = root.optString("textPreview", ""),
                attributes = attributesMap,
                computedStyles = stylesMap,
                selector = root.optString("selector", ""),
                rect = rect
            )

            onElementSelected(element)

        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing selected element: ${e.message}")
        }
    }

    @JavascriptInterface
    fun onConsoleMessage(json: String) {
        try {
            val root = JSONObject(json)
            val levelStr = root.optString("level", "LOG")
            val level = runCatching { ConsoleLevel.valueOf(levelStr) }.getOrDefault(ConsoleLevel.LOG)
            
            val sourceIdVal = if (root.isNull("sourceId")) null else root.optString("sourceId").takeIf { it.isNotEmpty() && it != "null" }
            val entry = ConsoleEntry(
                id = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                level = level,
                message = root.optString("message", ""),
                sourceId = sourceIdVal,
                lineNumber = if (root.has("lineNumber") && !root.isNull("lineNumber")) root.optInt("lineNumber") else null
            )

            onConsoleBridgeMessage(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing console message: ${e.message}")
        }
    }
}
