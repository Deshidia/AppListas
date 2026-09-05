package com.lagradost.quicknovel.util

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.mvvm.logError

/**
 * Cliente mínimo para la API pública y gratuita de Open Food Facts
 * (https://world.openfoodfacts.org), usado por los proveedores de supermercado
 * (Mercadona, Ahorramas...) para completar la ficha de un producto con su lista real de
 * ingredientes, tal y como la tiene registrada Open Food Facts para ese mismo producto.
 *
 * No hace falta ninguna API key para leer datos. Open Food Facts sí pide en sus
 * condiciones de uso identificar la app que consume la API con un User-Agent
 * descriptivo (para poder contactar en caso de uso abusivo), así que aquí se manda uno
 * propio en vez de reutilizar el user-agent de navegador que usan los demás proveedores.
 * Ver: https://openfoodfacts.github.io/openfoodfacts-server/api/
 */
object OpenFoodFactsApi {
    private const val BASE_URL = "https://world.openfoodfacts.org"

    private val headers = mapOf(
        "User-Agent" to "AppListas/1.0 (Android) - https://github.com/Deshidia/AppListas"
    )

    // No hace falta traer la ficha entera (fotos, aditivos, tablas nutricionales...)
    // para sacar solo el texto de ingredientes.
    private const val FIELDS = "code,product_name,brands,ingredients_text,ingredients_text_es"

    data class OffIngredients(
        val ingredientsText: String,
        val productName: String?,
        val brands: String?
    )

    /**
     * Intenta encontrar en Open Food Facts la misma ficha que se está viendo en la tienda
     * y devuelve su lista de ingredientes, en dos pasos:
     *
     * 1. Búsqueda por código de barras (EAN/GTIN), cuando se conoce: es la forma fiable de
     *    encontrar exactamente el mismo producto (misma variante/tamaño) en vez de "uno
     *    parecido" localizado solo por el nombre.
     * 2. Si no hay código de barras, o esa ficha existe pero todavía no tiene ingredientes
     *    cargados (Open Food Facts es una base de datos colaborativa y no todas las fichas
     *    están completas), se cae a una búsqueda de texto por nombre + marca y se toma el
     *    primer resultado que sí traiga ingredientes.
     *
     * Devuelve null si no se encuentra nada aprovechable por ninguna de las dos vías.
     */
    suspend fun findIngredients(
        name: String,
        brand: String? = null,
        ean: String? = null
    ): OffIngredients? {
        val cleanEan = ean?.trim()?.takeIf { it.length in 8..14 && it.all(Char::isDigit) }
        if (cleanEan != null) {
            fetchByBarcode(cleanEan)?.let { return it }
        }
        return searchByText(name, brand)
    }

    private suspend fun fetchByBarcode(ean: String): OffIngredients? {
        return try {
            val res = MainActivity.app.get(
                "$BASE_URL/api/v2/product/$ean.json",
                params = mapOf("fields" to FIELDS),
                headers = headers
            )
            if (res.code != 200) return null

            val root = res.parsed<JsonNode>()
            // La API devuelve status=1 cuando el código de barras existe en la base de
            // datos; status=0 significa "no encontrado" (no es un error HTTP).
            if (root.path("status").asInt(0) != 1) return null

            root.path("product").toIngredientsOrNull()
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private suspend fun searchByText(name: String, brand: String?): OffIngredients? {
        val query = listOfNotNull(brand?.trim()?.takeIf { it.isNotEmpty() }, name.trim())
            .joinToString(" ")
            .trim()
        if (query.isEmpty()) return null

        return try {
            val res = MainActivity.app.get(
                "$BASE_URL/cgi/search.pl",
                params = mapOf(
                    "search_terms" to query,
                    "search_simple" to "1",
                    "action" to "process",
                    "json" to "1",
                    "page_size" to "5",
                    "fields" to FIELDS
                ),
                headers = headers
            )
            if (res.code != 200) return null

            val root = res.parsed<JsonNode>()
            // Nos quedamos con el primer resultado de la búsqueda que realmente tenga
            // ingredientes cargados; los demás se descartan.
            root.path("products").firstNotNullOfOrNull { it.toIngredientsOrNull() }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    // Se prioriza el texto en español (el que le serviría a un usuario en España) y se cae
    // al genérico "ingredients_text" (normalmente en el idioma del envase original) si esa
    // ficha todavía no tiene traducción cargada.
    private fun JsonNode.toIngredientsOrNull(): OffIngredients? {
        val text = path("ingredients_text_es").asText("").trim().takeIf { it.isNotEmpty() }
            ?: path("ingredients_text").asText("").trim().takeIf { it.isNotEmpty() }
            ?: return null

        return OffIngredients(
            ingredientsText = text,
            productName = path("product_name").asText("").takeIf { it.isNotEmpty() },
            brands = path("brands").asText("").takeIf { it.isNotEmpty() }
        )
    }
}
