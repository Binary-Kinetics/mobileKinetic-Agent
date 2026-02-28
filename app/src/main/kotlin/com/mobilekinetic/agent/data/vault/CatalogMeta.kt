package com.mobilekinetic.agent.data.vault

import com.google.gson.Gson

data class CatalogMeta(
    val desc: String = "",
    val inject: String = "bearer_header",
    val service: String = "",
    val contexts: List<String> = emptyList(),
    val hint: String = ""
) {
    companion object {
        private val gson = Gson()

        fun parse(description: String?): CatalogMeta {
            if (description.isNullOrBlank() || !description.trimStart().startsWith("{")) {
                return CatalogMeta(desc = description ?: "")
            }
            return try {
                gson.fromJson(description, CatalogMeta::class.java)
            } catch (e: Exception) {
                CatalogMeta(desc = description)
            }
        }

        fun toJson(meta: CatalogMeta): String = gson.toJson(meta)
    }
}
