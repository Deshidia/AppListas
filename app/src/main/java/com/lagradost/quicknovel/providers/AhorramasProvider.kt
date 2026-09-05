package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.*
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.util.OpenFoodFactsApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.math.roundToInt

/**
 * Proveedor para https://www.ahorramas.com/
 *
 * A diferencia de Mercadona, Ahorramas no expone ninguna API JSON pública: es una tienda
 * Salesforce Commerce Cloud (Demandware) que renderiza los listados de producto en HTML
 * server-side (se reconoce por los controladores "Search-Show", "Product-Show",
 * "Wishlist-AddProduct" que aparecen en las URLs internas de la web). Por eso aquí se hace
 * scraping con Jsoup en vez de leer JSON.
 *
 * El parseo de cada ficha de producto se apoya en señales robustas que no dependen de
 * nombres de clase CSS concretos (que Ahorramas podría cambiar en cualquier momento): el
 * atributo alt de la imagen del producto para el nombre, el propio enlace del producto
 * (siempre termina en "-<id>.html") para la URL, y una detección de precio tachado (a
 * través de <s>/<del>/.strike-through) para diferenciar precio actual y precio anterior.
 *
 * Comprobado contra el HTML real de varias páginas de categoría, buscador y ficha de
 * producto (septiembre 2026). Un detalle importante encontrado entonces: en los productos
 * que se venden a peso variable, la ficha antepone a la foto real un icono decorativo
 * "PESO VARIABLE" que también es una etiqueta <img>; por eso la imagen y el nombre del
 * producto se buscan dentro del propio enlace al producto y no con el primer <img> de toda
 * la ficha (ver comentario en parseProductTile). Si en algún momento algo deja de encajar,
 * conviene revisar estas heurísticas contra el HTML real de la web.
 */
class AhorramasProvider : MainAPI() {
    override val name = "Ahorramas"
    override val mainUrl = "https://www.ahorramas.com"
    override val lang = "es"
    override val hasMainPage = true
    override val iconId = R.drawable.ic_ahorramas
    override val iconBackgroundId = R.color.ahorramasColor

    private val headers = mapOf(
        "Accept-Language" to "es-ES,es;q=0.9",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    /**
     * Árbol de categorías tal cual se ve en el menú desplegable de la cabecera de Ahorramas
     * (<div class="header-component navigation-menu text-center">): cada "--- NOMBRE ---"
     * abre una categoría grande y las entradas siguientes (hasta la próxima cabecera) son
     * sus categorías pequeñas, igual que en MercadonaProvider.
     *
     * A diferencia de Mercadona (que usa ids numéricos de su API), aquí el "id" de cada
     * categoría es la ruta relativa de su página de listado en la web (p.ej.
     * "frescos/carniceria/pollo/"), porque los productos se sacan haciendo scraping de esa
     * misma página.
     */
    private val allCategoriesRaw = listOf(
        "--- FRESCOS ---" to "frescos/",
        "Carnicería" to "frescos/carniceria/",
        "Charcutería" to "frescos/charcuteria/",
        "Frutas" to "frescos/frutas/",
        "Verduras y Hortalizas" to "frescos/verduras-y-hortalizas/",
        "Pescado y Mariscos" to "frescos/pescado-y-mariscos/",
        "Quesos" to "frescos/quesos/",
        "Huevos" to "frescos/huevos/",

        "--- ALIMENTACIÓN ---" to "alimentacion/",
        "Aceite, Vinagre y sal" to "alimentacion/aceite-vinagre-y-sal/",
        "Aperitivos y Frutos Secos" to "alimentacion/aperitivos-y-frutos-secos/",
        "Arroces, Pastas y Legumbres" to "alimentacion/arroces-pastas-y-legumbres/",
        "Azúcar y Edulcorantes" to "alimentacion/azucar-y-edulcorantes/",
        "Bollería y Repostería" to "alimentacion/bolleria-y-reposteria/",
        "Cacao, Cafés e Infusiones" to "alimentacion/cacao-cafes-e-infusiones/",
        "Caldos, purés y sopas" to "alimentacion/caldos-pures-y-sopas/",
        "Chocolates, Golosinas y Turrones" to "alimentacion/chocolates-golosinas-y-turrones/",
        "Conservas de Frutas" to "alimentacion/conservas-de-frutas/",
        "Conservas de Pescado" to "alimentacion/conservas-de-pescado/",
        "Conservas Vegetales" to "alimentacion/conservas-vegetales/",
        "Galletas, Cereales y Barritas" to "alimentacion/galletas-cereales-y-barritas/",
        "Gazpacho y salmorejo" to "alimentacion/gazpacho-y-salmorejo/",
        "Harina, Levadura y Preparados" to "alimentacion/harina-levadura-y-preparados/",
        "Miel y Mermeladas" to "alimentacion/miel-y-mermeladas/",
        "Panadería" to "alimentacion/panaderia/",
        "Platos Preparados" to "alimentacion/platos-preparados/",
        "Tomate Frito, Salsas y Especias" to "alimentacion/tomate-frito-salsas-y-especias/",
        "Productos Dietéticos" to "alimentacion/productos-dieteticos/",
        "Productos Ecológicos" to "alimentacion/productos-ecologicos/",

        "--- BEBIDAS ---" to "bebidas/",
        "Refrescos" to "bebidas/refrescos/",
        "Cerveza" to "bebidas/cerveza/",
        "Zumos" to "bebidas/zumos/",
        "Agua" to "bebidas/agua/",
        "Bebidas Alcohólicas" to "bebidas/bebidas-alcoholicas/",
        "Vinos" to "bebidas/vinos/",
        "Cava, Champagne y Sidra" to "bebidas/cava-champagne-y-sidra/",

        "--- LÁCTEOS ---" to "lacteos/",
        "Leche" to "lacteos/leche/",
        "Bebidas vegetales" to "lacteos/bebidas-vegetales/",
        "Yogures y kéfir" to "lacteos/yogures-y-kefir/",
        "Postres" to "lacteos/postres/",
        "Mantequilla, margarina y nata" to "lacteos/mantequilla-margarina-y-nata/",
        "Batidos y bebidas frías" to "lacteos/batidos-y-bebidas-frias/",
        "Horchatas" to "lacteos/horchatas/",

        "--- LIMPIEZA ---" to "limpieza/",
        "Papel y celulosa" to "limpieza/papel-y-celulosa/",
        "Detergentes y suavizantes" to "limpieza/detergentes-y-suavizantes/",
        "Limpieza de hogar" to "limpieza/limpieza-de-hogar/",
        "Lavavajillas" to "limpieza/lavavajillas/",
        "Utensilios de limpieza" to "limpieza/utensilios-de-limpieza/",
        "Ambientadores" to "limpieza/ambientadores/",
        "Desinfectantes" to "limpieza/desinfectantes/",
        "Cuidado de la ropa" to "limpieza/cuidado-de-la-ropa/",
        "Productos para calzado" to "limpieza/productos-para-calzado/",
        "Insecticidas" to "limpieza/insecticidas/",

        "--- CUIDADO PERSONAL ---" to "cuidado-personal/",
        "Cuidado del cabello" to "cuidado-personal/cuidado-del-cabello/",
        "Higiene corporal" to "cuidado-personal/higiene-corporal/",
        "Higiene bucal" to "cuidado-personal/higiene-bucal/",
        "Cuidado facial" to "cuidado-personal/cuidado-facial/",
        "Desodorantes" to "cuidado-personal/desodorantes/",
        "Higiene íntima" to "cuidado-personal/higiene-intima/",
        "Parafarmacia" to "cuidado-personal/parafarmacia/",
        "Depilación" to "cuidado-personal/depilacion/",
        "Afeitado" to "cuidado-personal/afeitado/",
        "Pies y manos" to "cuidado-personal/pies-y-manos/",
        "Cremas solares y autobronceadores" to "cuidado-personal/cremas-solares-y-autobronceadores/",
        "Colonias" to "cuidado-personal/colonias/",
        "Maquillaje" to "cuidado-personal/maquillaje/",

        "--- CONGELADOS ---" to "congelados/",
        "Pizzas y baguettes" to "congelados/pizzas-y-baguettes/",
        "Helados" to "congelados/helados/",
        "Rebozados" to "congelados/rebozados/",
        "Pescado y Marisco Congelado" to "congelados/pescado-y-marisco-congelado/",
        "Platos Preparados Congelados" to "congelados/platos-preparados-congelados/",
        "Repostería y Panadería Congelada" to "congelados/reposteria-y-panaderia-congelada/",
        "Verduras, Hortalizas y Frutas Congeladas" to "congelados/verduras-hortalizas-y-frutas-congeladas/",
        "Hielo" to "congelados/hielo/",

        "--- HOGAR ---" to "hogar/",
        "Insecticidas (Hogar)" to "hogar/insecticidas/",
        "Ambientadores (Hogar)" to "hogar/ambientadores/",
        "Textil" to "hogar/textil/",
        "Menaje de Cocina" to "hogar/menaje-de-cocina/",
        "Jardinería y exterior" to "hogar/jardineria-y-exterior/",
        "Orden y decoración" to "hogar/orden-y-decoracion/",
        "Juguetes y vajilla infantil" to "hogar/juguetes-y-vajilla-infantil/",
        "Pilas" to "hogar/pilas/",
        "Bombillas e iluminación" to "hogar/bombillas-e-iluminacion/",
        "Papelería" to "hogar/papeleria/",

        "--- BEBÉ ---" to "bebe/",
        "Alimentación infantil" to "bebe/alimentacion-infantil/",
        "Pañales" to "bebe/panales/",
        "Cuidados del bebé" to "bebe/cuidados-del-bebe/",
        "Puericultura" to "bebe/puericultura/",

        "--- MASCOTAS ---" to "mascotas/",
        "Perros" to "mascotas/perros/",
        "Gatos" to "mascotas/gatos/",
        "Otros animales" to "mascotas/otros-animales/"
    )

    private data class CategoryGroup(
        val name: String,
        val id: String,
        // (nombre, ruta relativa) de cada categoría pequeña, en el mismo orden que en allCategoriesRaw.
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

    /** Ruta de categoría grande -> (nombre, ruta) de todas sus categorías pequeñas. */
    private val categorySubCategories: Map<String, List<Pair<String, String>>> by lazy {
        categoryGroups.associate { it.id to it.subCategories }
    }

    // En el selector solo se muestran las categorías grandes.
    override val mainCategories: List<Pair<String, String>> =
        categoryGroups.map { it.name to it.id }

    override val tags = emptyList<Pair<String, String>>()
    override val orderBys = emptyList<Pair<String, String>>()

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val res = app.get("$mainUrl/buscador", params = mapOf("q" to query), headers = headers)
            if (res.code != 200) return emptyList()
            parseProductTiles(res.document)
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val res = app.get(url, headers = headers)
            if (res.code != 200) return null
            val document = res.document

            val name = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.substringBefore("|")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null

            val thumb = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""

            val priceScope = document.selectFirst(".product-detail, .prices, main") ?: document.body()
            val (current, _) = extractPrices(priceScope)

            val description = document.selectFirst("meta[name=description]")?.attr("content") ?: ""

            val infoRows = document.select("#collapseInfo li, .product-attributes li, .attribute")
                .mapNotNull { it.text().trim().takeIf { t -> t.isNotEmpty() } }
                .distinct()

            // Igual que en MercadonaProvider: se intenta sustituir la descripción propia
            // de la tienda por la lista de ingredientes de ese mismo producto en Open
            // Food Facts, cayendo a la descripción original si no se encuentra nada allí.
            val ingredients = fetchIngredientsText(document, name)
            val descriptionBlock = if (ingredients != null) {
                "🧾 INGREDIENTES (Open Food Facts):\n$ingredients"
            } else {
                description
            }

            val synopsisParts = mutableListOf<String>()
            if (current != null) synopsisParts.add("💰 PRECIO: $current €")
            if (descriptionBlock.isNotEmpty()) synopsisParts.add(descriptionBlock)
            if (infoRows.isNotEmpty()) synopsisParts.add(infoRows.joinToString("\n"))

            newStreamResponse(name, url, listOf(newChapterData("Ficha del producto", url))) {
                posterUrl = thumb
                synopsis = synopsisParts.joinToString("\n\n")
            }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    /**
     * Busca en Open Food Facts los ingredientes de este mismo producto y devuelve solo el
     * texto de ingredientes, o null si no se ha encontrado nada aprovechable.
     *
     * A diferencia de Mercadona, Ahorramas no expone el código de barras (EAN) en ningún
     * endpoint JSON propio; se intenta sacarlo de forma heurística del bloque de datos
     * estructurados schema.org/Product (JSON-LD) que suelen incluir las tiendas
     * Salesforce Commerce Cloud, y si no aparece ahí se cae directamente a que
     * [OpenFoodFactsApi.findIngredients] busque por nombre + marca (esta última, si se
     * encuentra, entre las filas de atributos de la ficha).
     */
    private suspend fun fetchIngredientsText(document: Document, name: String): String? {
        val ean = extractEan(document)
        val brand = extractBrand(document)
        return OpenFoodFactsApi.findIngredients(name = name, brand = brand, ean = ean)
            ?.ingredientsText
    }

    private val gtinRegex = Regex(""""gtin(?:13|12|8)?"\s*:\s*"?(\d{8,14})"?""")

    private fun extractEan(document: Document): String? {
        document.select("script[type=application/ld+json]").forEach { script ->
            gtinRegex.find(script.data())?.groupValues?.get(1)?.let { return it }
        }
        return document.selectFirst(
            "meta[itemprop=gtin13], meta[itemprop=gtin], meta[property=product:ean]"
        )?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractBrand(document: Document): String? {
        document.selectFirst("meta[itemprop=brand], meta[property=product:brand]")
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return document.select("#collapseInfo li, .product-attributes li, .attribute")
            .map { it.text().trim() }
            .firstOrNull { it.contains("marca", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override suspend fun loadHtml(url: String): String? {
        return try {
            val res = app.get(url, headers = headers)
            if (res.code != 200) return null
            val document = res.document

            val name = document.selectFirst("h1")?.text()?.trim() ?: "Producto"
            val image = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
            val description = document.selectFirst("meta[name=description]")?.attr("content") ?: ""

            val priceScope = document.selectFirst(".product-detail, .prices, main") ?: document.body()
            val (current, _) = extractPrices(priceScope)
            val price = current ?: "N/A"

            // Igual que en load(): se prioriza mostrar los ingredientes de Open Food
            // Facts en vez de la descripción propia de la tienda, cayendo a esta última
            // solo si no se encuentra nada en Open Food Facts.
            val ingredients = fetchIngredientsText(document, name)
            val descriptionTitle = if (ingredients != null) "Ingredientes (Open Food Facts)" else "Descripción"
            val descriptionBody = ingredients ?: description

            """
            <div style="text-align: center; font-family: sans-serif; padding: 20px; background-color: #fff;">
                <img src="$image" style="width: 100%; max-width: 400px; border-radius: 15px; box-shadow: 0 4px 12px rgba(0,0,0,0.15);" />
                <h1 style="color: #E30613; margin-top: 25px; font-size: 26px; font-weight: bold;">$name</h1>
                <div style="background-color: #E30613; color: white; display: inline-block; padding: 12px 35px; border-radius: 40px; font-size: 28px; font-weight: bold; margin: 20px 0;">
                    $price €
                </div>
                <div style="text-align: left; margin-top: 30px; border-top: 2px solid #eee; padding-top: 25px;">
                    <h3 style="color: #333; font-size: 20px; border-bottom: 1px solid #E30613; display: inline-block; padding-bottom: 5px;">$descriptionTitle</h3>
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
        val targetCategoryId = mainCategory ?: categoryGroups.firstOrNull()?.id

        // La página de categoría de Ahorramas solo trae, en el HTML inicial, la primera
        // tanda de productos; el resto se carga con el botón "Más resultados" mediante una
        // llamada AJAX cuyo endpoint no se ha podido verificar. Igual que en
        // MercadonaProvider (por otro motivo: ahí la API no pagina), aquí se simplifica y no
        // se pide más allá de esa primera página.
        if (page > 1) {
            return HeadMainPageResponse(targetCategoryId ?: "", emptyList())
        }

        return coroutineScope {
            val subCats = categorySubCategories[targetCategoryId] ?: emptyList()

            // Lanzamos peticiones en paralelo para todas las subcategorías de esta sección
            // grande, e intercalamos un título divisorio con el nombre de cada una que tenga
            // productos (así se ve igual que en la web de Ahorramas).
            val productsPerSubCategory = subCats.map { (_, subPath) ->
                async { fetchCategoryProducts(subPath) }
            }.awaitAll()

            val list = mutableListOf<SearchResponse>()
            val seenIds = mutableSetOf<String>()
            subCats.forEachIndexed { index, (subName, subPath) ->
                val newProducts = productsPerSubCategory[index].filter { seenIds.add(it.url) }
                if (newProducts.isNotEmpty()) {
                    list.add(
                        newSearchResponse(subName, "#divider-$subPath") { isSectionDivider = true }
                    )
                    list.addAll(newProducts)
                }
            }

            HeadMainPageResponse(targetCategoryId ?: "", list)
        }
    }

    private suspend fun fetchCategoryProducts(path: String): List<SearchResponse> {
        return try {
            val res = app.get(fixUrl(path), headers = headers)
            if (res.code != 200) return emptyList()
            parseProductTiles(res.document)
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    // Cada ficha de producto termina siempre en "-<id>.html", p.ej.
    // ".../pechuga-de-pollo-10634.html"
    private val productUrlRegex = Regex("""-\d+\.html(?:[?#].*)?$""")

    private fun parseProductTiles(document: Document): List<SearchResponse> {
        // Se prueban varios selectores típicos de una tienda Salesforce Commerce Cloud /
        // SFRA (de más a menos específico) y se usa el primero que encuentre algo, para no
        // duplicar fichas si varios coincidiesen a la vez (p.ej. uno anidado dentro de otro).
        val candidateSelectors = listOf(
            "div.product-tile",
            "li.product-tile",
            "div.product[data-pid]",
            "div[data-pid]",
            "div.product"
        )
        val tiles = candidateSelectors
            .asSequence()
            .map { document.select(it) }
            .firstOrNull { it.isNotEmpty() }
            ?: return emptyList()

        val list = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()
        for (tile in tiles) {
            parseProductTile(tile, seenIds)?.let { list.add(it) }
        }
        return list
    }

    private fun parseProductTile(tile: Element, seenIds: MutableSet<String>): SearchResponse? {
        val link = tile.select("a[href]")
            .firstOrNull { productUrlRegex.containsMatchIn(it.attr("href")) }
            ?: return null

        val href = fixUrl(link.attr("href"))
        if (!seenIds.add(href)) return null

        // En las fichas de productos que se venden a peso variable (carne, pescado, fruta...),
        // Ahorramas antepone a la foto real un icono decorativo "PESO VARIABLE"
        // (.../images/weight.svg) que también es un <img>. Si se coge sin más el primer <img>
        // de toda la ficha (tile.selectFirst("img")), ese icono se cuela como si fuera la foto
        // del producto y su alt ("PESO VARIABLE") como si fuera el nombre: de ahí que algunas
        // fichas aparecieran con una imagen y un texto que no se correspondían con el producto.
        // La foto real, en cambio, siempre está anidada dentro del propio enlace al producto
        // (el mismo <a> ya localizado arriba por su URL), así que se busca ahí primero. Solo si
        // ese enlace no tuviera ninguna imagen se cae a buscar en toda la ficha, descartando
        // explícitamente ese icono por si acaso.
        val img = link.selectFirst("img")
            ?: tile.select("img").firstOrNull { candidate ->
                val alt = candidate.attr("alt").trim()
                !alt.equals("PESO VARIABLE", ignoreCase = true) &&
                        !candidate.attr("src").contains("weight.svg", ignoreCase = true)
            }

        val name = img?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: tile.selectFirst("h2, h3, .pdp-link, .link")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.text().trim().takeIf { it.isNotEmpty() }
            ?: return null

        val thumb = img?.attr("abs:src")?.takeIf { it.isNotEmpty() }
            ?: img?.attr("abs:data-src")?.takeIf { it.isNotEmpty() }
            ?: ""

        val (current, previous) = extractPrices(tile)

        return newSearchResponse(name, href, fix = false) {
            posterUrl = thumb
            if (current != null) price = "$current €"

            // Se vuelven a vincular a variables locales dentro del lambda para que el
            // compilador pueda hacer smart-cast a String (no String?) de forma fiable;
            // capturar "current"/"previous" directamente aquí no siempre permite el
            // smart-cast al tratarse de variables capturadas desde fuera del lambda.
            val prev = previous
            val curr = current
            if (prev != null && prev != curr) {
                originalPrice = "$prev €"
                val prevValue = prev.replace(".", "").replace(",", ".").toDoubleOrNull()
                val currValue = curr?.replace(".", "")?.replace(",", ".")?.toDoubleOrNull()
                if (prevValue != null && currValue != null && prevValue > 0) {
                    discountPercent = (((prevValue - currValue) / prevValue) * 100).roundToInt()
                }
            }
        }
    }

    private val priceRegex = Regex("""(\d{1,4}(?:\.\d{3})*,\d{2})\s*€""")

    private fun firstPrice(text: String): String? = priceRegex.find(text)?.groupValues?.get(1)

    /**
     * Devuelve (precioActual, precioAnterior) para el nodo dado (una ficha de producto o la
     * zona de precio de una ficha completa). Ahorramas tacha el precio anterior con un
     * elemento de tipo "strike-through" cuando hay una bajada de precio; si no se encuentra
     * ninguno se asume que no hay descuento y el primer precio encontrado es el actual.
     * No basta con coger "los dos primeros precios distintos del texto", porque muchas
     * fichas muestran el mismo precio en dos unidades distintas (p.ej. "9,24€" y
     * "2,33€/LITRO") sin que eso sea una bajada de precio.
     */
    private fun extractPrices(root: Element): Pair<String?, String?> {
        val strikeSelector = "s, del, strike, .strike-through, [class*=strike]"
        val strikeEl = root.selectFirst(strikeSelector)
        val previous = strikeEl?.let { firstPrice(it.text()) }

        val clone = root.clone()
        clone.select(strikeSelector).remove()
        val current = firstPrice(clone.text())

        return current to previous
    }
}