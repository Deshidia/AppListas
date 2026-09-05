package com.lagradost.quicknovel.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.*
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.util.OpenFoodFactsApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.roundToInt

class MercadonaProvider : MainAPI() {
    override val name = "Mercadona"
    override val mainUrl = "https://tienda.mercadona.es"
    override val lang = "es"
    override val hasMainPage = true
    override val usesCloudFlareKiller = true
    override val iconId = R.drawable.ic_mercadona
    override val iconBackgroundId = R.color.white

    private val headers = mapOf(
        "x-customer-warehouse" to "wh1",
        "Accept" to "application/json",
        "Origin" to "https://tienda.mercadona.es",
        "Referer" to "https://tienda.mercadona.es/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    /**
     * Árbol de categorías tal cual lo expone Mercadona: cada entrada "--- NOMBRE ---"
     * abre una categoría grande, y las entradas siguientes (hasta la próxima cabecera)
     * son sus categorías pequeñas. Se mantiene en este formato "plano" porque es fácil
     * de mantener/ampliar; la agrupación real se calcula en [categoryGroups].
     */
    private val allCategoriesRaw = listOf(
        // Secciones especiales del home de Mercadona (no son categorías reales, sino
        // carruseles de la portada). Se resuelven aparte en fetchCategoryProducts.
        "--- NOVEDADES ---" to "new-arrivals",
        "--- DESCUENTOS ---" to "price-drops",

        "--- ACEITE, ESPECIAS Y SALSAS ---" to "12",
        "Aceite, vinagre y sal" to "112",
        "Especias" to "115",
        "Mayonesa, ketchup y mostaza" to "116",
        "Otras salsas" to "117",

        "--- AGUA Y REFRESCOS ---" to "18",
        "Agua" to "156",
        "Isotónico y energético" to "163",
        "Refresco de cola" to "158",
        "Refresco de naranja y limón" to "159",
        "Tónica y bitter" to "161",
        "Refresco de té y sin gas" to "162",

        "--- APERITIVOS ---" to "15",
        "Aceitunas y encurtidos" to "135",
        "Frutos secos y fruta desecada" to "133",
        "Patatas fritas y snacks" to "132",

        "--- ARROZ, LEGUMBRES Y PASTA ---" to "13",
        "Arroz" to "118",
        "Legumbres" to "121",
        "Pasta y fideos" to "120",

        "--- AZÚCAR, CARAMELOS Y CHOCOLATE ---" to "9",
        "Azúcar y edulcorante" to "89",
        "Chicles y caramelos" to "95",
        "Chocolate" to "92",
        "Golosinas" to "97",
        "Mermelada y miel" to "90",

        "--- BEBÉ ---" to "24",
        "Alimentación infantil" to "216",
        "Biberón y chupete" to "219",
        "Higiene y cuidado bebé" to "218",
        "Toallitas y pañales" to "217",

        "--- BODEGA ---" to "19",
        "Cerveza" to "164",
        "Cerveza sin alcohol" to "166",
        "Licores" to "181",
        "Sidra y cava" to "174",
        "Tinto de verano y sangría" to "168",
        "Vino blanco" to "170",
        "Vino lambrusco y espumoso" to "173",
        "Vino rosado" to "171",
        "Vino tinto" to "169",

        "--- CACAO, CAFÉ E INFUSIONES ---" to "8",
        "Cacao y chocolate taza" to "86",
        "Café cápsula y monodosis" to "81",
        "Café molido y en grano" to "83",
        "Café soluble y otras" to "84",
        "Té e infusiones" to "88",

        "--- CARNE ---" to "3",
        "Arreglos" to "46",
        "Aves y pollo" to "38",
        "Carne congelada" to "47",
        "Cerdo" to "37",
        "Conejo y cordero" to "42",
        "Embutido" to "43",
        "Hamburguesas y picadas" to "44",
        "Vacuno" to "40",
        "Empanados y elaborados" to "45",

        "--- CEREALES Y GALLETAS ---" to "7",
        "Cereales" to "78",
        "Galletas" to "80",
        "Tortitas" to "79",

        "--- CHARCUTERÍA Y QUESOS ---" to "4",
        "Aves y jamón cocido" to "48",
        "Bacón y salchichas" to "52",
        "Chopped y mortadela" to "49",
        "Embutido curado" to "51",
        "Jamón serrano" to "50",
        "Paté y sobrasada" to "58",
        "Queso curado y tierno" to "54",
        "Queso lonchas y rallado" to "56",
        "Queso untable y fresco" to "53",

        "--- CONGELADOS ---" to "17",
        "Arroz y pasta (Cong)" to "147",
        "Carne (Cong)" to "148",
        "Fruta y verdura (Cong)" to "145",
        "Helados" to "154",
        "Hielo" to "155",
        "Marisco (Cong)" to "150",
        "Pescado (Cong)" to "149",
        "Pizzas (Cong)" to "151",
        "Rebozados" to "884",
        "Tartas y churros" to "152",

        "--- CONSERVAS, CALDOS Y CREMAS ---" to "14",
        "Conservas de pescado" to "122",
        "Berberechos y mejillones" to "123",
        "Conservas verdura y fruta" to "127",
        "Gazpacho y cremas" to "130",
        "Sopa y caldo" to "129",
        "Tomate" to "126",

        "--- CUIDADO DEL CABELLO ---" to "21",
        "Acondicionador y mascarilla" to "201",
        "Champú" to "199",
        "Coloración cabello" to "203",
        "Fijación cabello" to "202",

        "--- CUIDADO FACIAL Y CORPORAL ---" to "20",
        "Afeitado y hombre" to "192",
        "Cuidado corporal" to "189",
        "Cuidado facial" to "185",
        "Depilación" to "191",
        "Desodorante" to "188",
        "Gel y jabón de manos" to "187",
        "Higiene bucal" to "186",
        "Higiene íntima" to "190",
        "Manicura y pedicura" to "194",
        "Perfume y colonia" to "196",
        "Protector solar" to "198",

        "--- FITOTERAPIA ---" to "23",
        "Fitoterapia" to "213",
        "Parafarmacia" to "214",

        "--- FRUTA Y VERDURA ---" to "1",
        "Fruta" to "27",
        "Lechuga y ensalada" to "28",
        "Verdura" to "29",

        "--- HUEVOS, LECHE Y MANTEQUILLA ---" to "6",
        "Huevos" to "77",
        "Leche y bebidas vegetales" to "72",
        "Mantequilla y margarina" to "75",

        "--- LIMPIEZA Y HOGAR ---" to "26",
        "Detergente y suavizante" to "226",
        "Estropajos y bayetas" to "237",
        "Insecticida y ambientador" to "241",
        "Lejía y líquidos fuertes" to "234",
        "Limpiacristales" to "235",
        "Limpiahogar y friegasuelos" to "233",
        "Limpieza baño y WC" to "231",
        "Limpieza cocina" to "230",
        "Limpieza muebles" to "232",
        "Limpieza vajilla" to "229",
        "Menaje y conservación" to "243",
        "Papel higiénico" to "238",
        "Pilas y bolsas basura" to "239",
        "Utensilios de limpieza" to "244",

        "--- MAQUILLAJE ---" to "22",
        "Bases y corrector" to "206",
        "Colorete y polvos" to "207",
        "Labios" to "208",
        "Ojos" to "210",
        "Pinceles y brochas" to "212",

        "--- MARISCO Y PESCADO ---" to "2",
        "Marisco" to "32",
        "Pescado congelado" to "34",
        "Pescado fresco" to "31",
        "Salazones y ahumados" to "36",

        "--- MASCOTAS ---" to "25",
        "Gato" to "222",
        "Perro" to "221",
        "Otros (Mascotas)" to "225",

        "--- PANADERÍA Y PASTELERÍA ---" to "5",
        "Bollería de horno" to "65",
        "Bollería envasada" to "66",
        "Harina y preparado" to "69",
        "Pan de horno" to "59",
        "Pan de molde" to "60",
        "Pan tostado y rallado" to "62",
        "Picos y rosquilletas" to "64",
        "Tartas y pasteles" to "68",
        "Velas y decoración" to "71",

        "--- PIZZAS Y PLATOS PREPARADOS ---" to "16",
        "Listo para Comer" to "897",
        "Pizzas" to "138",
        "Platos calientes" to "140",
        "Platos fríos" to "142",

        "--- POSTRES Y YOGURES ---" to "11",
        "Bífidus" to "105",
        "Flan y natillas" to "110",
        "Gelatina y otros" to "111",
        "Postres de soja" to "106",
        "Yogures desnatados" to "103",
        "Yogures griegos" to "109",
        "Yogures líquidos" to "108",
        "Yogures naturales y sabores" to "104",
        "Yogures infantiles" to "107",

        "--- ZUMOS ---" to "10",
        "Fruta variada" to "99",
        "Melocotón y piña" to "100",
        "Naranja" to "143",
        "Tomate y otros" to "98"
    )

    private data class CategoryGroup(
        val name: String,
        val id: String,
        // (nombre, id) de cada categoría pequeña, en el mismo orden que en allCategoriesRaw.
        val subCategories: List<Pair<String, String>>
    )

    /** Agrupa [allCategoriesRaw] en categorías grandes, cada una con sus categorías pequeñas. */
    private val categoryGroups: List<CategoryGroup> by lazy {
        val groups = mutableListOf<CategoryGroup>()
        var currentName: String? = null
        var currentId: String? = null
        var currentSubCategories = mutableListOf<Pair<String, String>>()

        fun flushCurrentGroup() {
            val name = currentName ?: return
            val id = currentId ?: return
            groups.add(CategoryGroup(name, id, currentSubCategories.toList()))
        }

        allCategoriesRaw.forEach { (rawName, id) ->
            if (rawName.startsWith("---")) {
                flushCurrentGroup()
                currentName = rawName.removePrefix("---").removeSuffix("---").trim()
                    .lowercase().replaceFirstChar { it.uppercase() }
                currentId = id
                currentSubCategories = mutableListOf()
            } else {
                currentSubCategories.add(rawName to id)
            }
        }
        flushCurrentGroup()
        groups
    }

    /** ID de categoría grande -> (nombre, id) de todas sus categorías pequeñas. */
    private val categorySubCategories: Map<String, List<Pair<String, String>>> by lazy {
        categoryGroups.associate { it.id to it.subCategories }
    }

    // En el selector solo se muestran las categorías grandes.
    override val mainCategories: List<Pair<String, String>> =
        categoryGroups.map { it.name to it.id }

    override val tags = emptyList<Pair<String, String>>()
    override val orderBys = emptyList<Pair<String, String>>()

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://7-kn73.mercadona.es/rest/v1/search/"
        return try {
            val res = app.get(
                url,
                params = mapOf("query" to query, "limit" to "40"),
                headers = headers
            )
            if (res.code != 200) return emptyList()

            val root = res.parsed<JsonNode>()
            val list = mutableListOf<SearchResponse>()
            val seenIds = mutableSetOf<String>()

            val hits = root.path("results").takeIf { !it.isMissingNode } ?: root.path("hits")
            hits.forEach { hit ->
                parseProductNode(hit, seenIds)?.let { list.add(it) }
            }
            list
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    private fun parseProductNode(node: JsonNode, seenIds: MutableSet<String>): SearchResponse? {
        val id = node.path("id").asText("").takeIf { it.isNotEmpty() } ?: return null
        if (!seenIds.add(id)) return null

        val name = node.path("display_name").asText(node.path("name").asText("Producto"))
        val thumb = node.path("thumbnail").asText("")

        // En /api/categories/<id>/ los precios vienen anidados en "price_instructions".
        // En /api/home/... no está confirmado que sea igual, así que si no existe ese
        // objeto, se buscan los mismos campos directamente en la raíz del nodo.
        val priceInstructions = node.path("price_instructions")
            .takeIf { !it.isMissingNode && !it.isNull } ?: node

        val rawPrice = priceInstructions.path("unit_price").asText("")
            .ifEmpty { priceInstructions.path("bulk_price").asText("") }
            .ifEmpty { priceInstructions.path("price").asText("") }
            .ifEmpty { node.path("price").asText("") }

        val priceDecreasedFlag = priceInstructions.path("price_decreased").asBoolean(false) ||
                node.path("price_decreased").asBoolean(false)

        // Distintos nombres posibles para el precio anterior según el endpoint.
        val previousPriceCandidates = listOf(
            priceInstructions.path("previous_unit_price"),
            priceInstructions.path("previous_bulk_price"),
            priceInstructions.path("previous_price"),
            priceInstructions.path("old_price"),
            priceInstructions.path("original_price"),
            node.path("previous_unit_price"),
            node.path("previous_price"),
            node.path("old_price"),
            node.path("original_price"),
        )
        val rawPreviousPrice = previousPriceCandidates
            .firstOrNull { !it.isMissingNode && !it.isNull && it.asText("").isNotEmpty() }
            ?.asText("") ?: ""

        return newSearchResponse(name, "/product/$id") {
            posterUrl = thumb
            if (rawPrice.isNotEmpty()) price = "$rawPrice €"

            if (rawPreviousPrice.isNotEmpty()) {
                val previous = rawPreviousPrice.replace(",", ".").toDoubleOrNull()
                val current = rawPrice.replace(",", ".").toDoubleOrNull()
                // Si la API no manda el flag "price_decreased" explícito, se asume bajada
                // de precio igualmente cuando el precio anterior es mayor que el actual.
                if (previous != null && current != null &&
                    (priceDecreasedFlag || previous > current)
                ) {
                    originalPrice = "$rawPreviousPrice €"
                    if (previous > 0) {
                        discountPercent = (((previous - current) / previous) * 100).roundToInt()
                    }
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.split("/").lastOrNull() ?: return null
        val apiUrl = "https://tienda.mercadona.es/api/products/$id/"
        return try {
            val res = app.get(apiUrl, headers = headers)
            if (res.code != 200) return null

            val root = res.parsed<JsonNode>()
            val name = root.path("display_name").asText(root.path("name").asText("Producto"))
            val thumb = root.path("thumbnail").asText("")
            val price = root.path("price_instructions").path("unit_price").asText("N/A")
            val desc = root.path("description").asText("")

            // La "descripción" que se muestra en la ficha pasa a ser la lista de
            // ingredientes de Open Food Facts (ver fetchOffData); si no se encuentra
            // nada allí, se cae a la descripción propia de Mercadona.
            val off = fetchOffData(root, name)
            val descriptionBlock = if (off != null) {
                "🧾 INGREDIENTES (Open Food Facts):\n${off.ingredientsText}"
            } else {
                desc
            }

            newStreamResponse(name, url, listOf(newChapterData("Ficha del producto", url))) {
                posterUrl = thumb
                synopsis = "💰 PRECIO: $price €\n\n$descriptionBlock"
                // Permite mostrar un botón "OpenFoodFacts" en la ficha que abra la
                // página exacta de la que se han sacado los ingredientes.
                offUrl = off?.productUrl
            }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    /**
     * Busca en Open Food Facts los ingredientes de este mismo producto y devuelve solo el
     * texto de ingredientes (sin formatear), o null si no se ha encontrado nada
     * aprovechable (para poder caer a la descripción propia de Mercadona en ese caso).
     *
     * El código de barras (EAN) es, con diferencia, la forma más fiable de acertar con la
     * misma ficha exacta en Open Food Facts (mismo tamaño/variante); pero como no está
     * garantizado en qué parte del JSON de Mercadona viene según el producto, se prueban
     * varias rutas candidatas (mismo patrón que ya se usa más abajo con el precio
     * anterior). Si no aparece en ninguna, [OpenFoodFactsApi.findIngredients] cae solo a
     * buscar por nombre + marca.
     */
    private suspend fun fetchOffData(root: JsonNode, name: String): OpenFoodFactsApi.OffIngredients? {
        val eanCandidates = listOf(
            root.path("ean"),
            root.path("details").path("ean"),
            root.path("product_information").path("ean"),
        )
        val ean = eanCandidates
            .firstOrNull { !it.isMissingNode && !it.isNull && it.asText("").isNotEmpty() }
            ?.asText("")

        val brand = root.path("details").path("brand").asText("").takeIf { it.isNotEmpty() }

        return OpenFoodFactsApi.findIngredients(name = name, brand = brand, ean = ean)
    }

    override suspend fun loadHtml(url: String): String? {
        val id = url.split("/").lastOrNull() ?: return null
        val apiUrl = "https://tienda.mercadona.es/api/products/$id/"
        return try {
            val res = app.get(apiUrl, headers = headers)
            if (res.code != 200) return null

            val root = res.parsed<JsonNode>()
            val name = root.path("display_name").asText(root.path("name").asText("Producto"))
            val price = root.path("price_instructions").path("unit_price").asText("N/A")
            val image = root.path("thumbnail").asText("")
            val description = root.path("description").asText("")
            val brand = root.path("details").path("brand").asText("")

            // Igual que en load(): se prioriza mostrar los ingredientes de Open Food
            // Facts en vez de la descripción propia de Mercadona, cayendo a esta última
            // solo si no se encuentra nada en Open Food Facts.
            val ingredients = fetchOffData(root, name)?.ingredientsText
            val descriptionTitle = if (ingredients != null) "Ingredientes (Open Food Facts)" else "Descripción"
            val descriptionBody = ingredients ?: description

            """
            <div style="text-align: center; font-family: sans-serif; padding: 20px; background-color: #fff;">
                <img src="$image" style="width: 100%; max-width: 400px; border-radius: 15px; box-shadow: 0 4px 12px rgba(0,0,0,0.15);" />
                <h1 style="color: #008448; margin-top: 25px; font-size: 26px; font-weight: bold;">$name</h1>
                <div style="background-color: #008448; color: white; display: inline-block; padding: 12px 35px; border-radius: 40px; font-size: 28px; font-weight: bold; margin: 20px 0;">
                    $price €
                </div>
                ${if (brand.isNotEmpty()) "<p style=\"color: #666; font-size: 18px;\"><b>Marca:</b> $brand</p>" else ""}
                <div style="text-align: left; margin-top: 30px; border-top: 2px solid #eee; padding-top: 25px;">
                    <h3 style="color: #333; font-size: 20px; border-bottom: 1px solid #008448; display: inline-block; padding-bottom: 5px;">$descriptionTitle</h3>
                    <p style="line-height: 1.6; color: #444; font-size: 16px; margin-top: 15px; white-space: pre-line;">$descriptionBody</p>
                </div>
            </div>
            """.trimIndent()
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        // Si no hay categoría seleccionada, usamos la primera ("Novedades") por defecto.
        val targetCategoryId = mainCategory ?: categoryGroups.firstOrNull()?.id

        // Mercadona no pagina estos listados: cada petición trae siempre el catálogo
        // completo de la categoría/sección. Sin este corte, el scroll infinito seguiría
        // pidiendo "más páginas" y recibiría una y otra vez los mismos productos.
        if (page > 1) {
            return HeadMainPageResponse(targetCategoryId ?: "", emptyList())
        }

        return coroutineScope {
            val subCats = categorySubCategories[targetCategoryId] ?: emptyList()

            // Si es una de las secciones especiales (Novedades/Descuentos), no tiene subcategorías reales.
            val isSpecial = targetCategoryId == "new-arrivals" || targetCategoryId == "price-drops"

            val results = if (isSpecial) {
                // Estas no tienen subcategorías, pero para que se vean igual que el resto
                // (con su título antes de la cuadrícula) usamos el propio nombre de la
                // categoría grande ("Novedades" / "Descuentos") como divisor.
                val catId = targetCategoryId!!
                val products = fetchCategoryProducts(catId)
                if (products.isNotEmpty()) {
                    val sectionName = categoryGroups.find { it.id == catId }?.name ?: catId
                    listOf(
                        newSearchResponse(sectionName, "#divider-$catId") { isSectionDivider = true }
                    ) + products
                } else {
                    products
                }
            } else {
                // Lanzamos peticiones en paralelo para todas las subcategorías de esta sección
                // grande, e intercalamos un título divisorio con el nombre de cada una que
                // tenga productos (así se ve igual que en la web/app de Mercadona).
                val productsPerSubCategory = subCats.map { (_, subId) ->
                    async { fetchCategoryProducts(subId) }
                }.awaitAll()

                val list = mutableListOf<SearchResponse>()
                val seenIds = mutableSetOf<String>()
                subCats.forEachIndexed { index, (subName, subId) ->
                    val newProducts = productsPerSubCategory[index].filter { seenIds.add(it.url) }
                    if (newProducts.isNotEmpty()) {
                        list.add(
                            newSearchResponse(subName, "#divider-$subId") { isSectionDivider = true }
                        )
                        list.addAll(newProducts)
                    }
                }
                list
            }

            HeadMainPageResponse(targetCategoryId ?: "", results)
        }
    }

    private suspend fun fetchCategoryProducts(categoryId: String): List<SearchResponse> {
        // NOVEDADES y DESCUENTOS son módulos de la portada (home), no categorías reales,
        // y viven bajo /api/home/<slug>/ en vez de /api/categories/<id>/.
        val isSpecial = categoryId == "new-arrivals" || categoryId == "price-drops"
        val url = if (isSpecial) {
            "https://tienda.mercadona.es/api/home/$categoryId/"
        } else {
            "https://tienda.mercadona.es/api/categories/$categoryId/"
        }

        return try {
            val res = app.get(url, headers = headers)
            if (res.code != 200) return emptyList()

            val root = res.parsed<JsonNode>()
            val list = mutableListOf<SearchResponse>()
            val seenIds = mutableSetOf<String>()

            if (isSpecial) {
                // La respuesta de /api/home/<slug>/ trae los productos en "items".
                root.path("items").forEach { product ->
                    parseProductNode(product, seenIds)?.let { list.add(it) }
                }
            } else {
                // La estructura de Mercadona tiene "categories" -> "products"
                root.path("categories").forEach { subCat ->
                    subCat.path("products").forEach { product ->
                        parseProductNode(product, seenIds)?.let { list.add(it) }
                    }
                }
                // Si no hay el nesting anterior, los productos pueden estar directos.
                if (list.isEmpty()) {
                    root.path("products").forEach { product ->
                        parseProductNode(product, seenIds)?.let { list.add(it) }
                    }
                }
            }

            list
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }
}