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

/**
 * Proveedor para https://www.compraonline.alcampo.es/
 *
 * Igual que Ahorramas (y a diferencia de Mercadona), Alcampo no expone ninguna API JSON
 * pública desde la que sacar los productos: la tienda corre sobre Ocado Smart Platform y
 * renderiza los listados de categoría y las fichas de producto en HTML server-side, así que
 * aquí también se hace scraping con Jsoup en vez de leer JSON.
 *
 * El árbol de categorías se ve en el menú desplegable de la cabecera
 * (<div class="sc-1hfavqh-0 iWNGPT">), pero como esas clases son de styled-components y
 * cambian con cada despliegue, el árbol se ha extraído a mano navegando
 * "https://www.compraonline.alcampo.es/categories" (que lista las 28 categorías grandes) y
 * la página propia de cada una (que lista sus categorías pequeñas bajo "Categorías"), y se
 * ha volcado aquí igual que en MercadonaProvider/AhorramasProvider. El "id" de cada
 * categoría es la ruta relativa de su página de listado (p.ej.
 * "categories/frescos/carne/OC13"), porque los productos se sacan haciendo scraping de esa
 * misma página.
 *
 * OJO: al recopilar el árbol, la web empezó a devolver bloqueos intermitentes de tipo
 * "robots disallowed" (parece un límite de peticiones más que un robots.txt real, porque
 * afectaba a categorías concretas de forma inconsistente). Por eso unas ~15 categorías
 * grandes (Desayuno y Merienda, Congelados, Droguería, Tecnología, Juguetes, Papelería,
 * Textil...) se han dejado con una única "categoría pequeña" que es la propia categoría
 * grande, en vez de con su desglose real: el proveedor funciona igual (se listan sus
 * productos), solo que sin la subdivisión fina. Se pueden completar más adelante repitiendo
 * el mismo proceso con esas categorías cuando la web dé una tregua.
 *
 * El parseo de cada ficha de producto tampoco depende de nombres de clase CSS concretos:
 * se localizan los enlaces "a[href*=/products/]" (cada producto aparece dos veces, una
 * envolviendo la miniatura y otra envolviendo el nombre, con el mismo href, por lo que se
 * queda solo con la primera ocurrencia que se pueda leer) y, desde ahí, se sube por el
 * árbol del DOM hasta encontrar el contenedor de la ficha completa (el primer antecesor
 * cuyo texto ya incluya tanto un precio en euros como el botón "Añadir"). El precio
 * mostrado en la ficha (no el precio por litro/kilo, que aparece antes) es siempre el
 * último importe en euros encontrado justo antes de ese botón.
 *
 * No se ha podido verificar con certeza la URL exacta del buscador interno de Alcampo (no
 * es indexable ni aparece en el HTML server-renderizado de la portada), así que search() usa
 * el endpoint más habitual en tiendas Ocado Smart Platform ("/search?keywords="); si algún
 * día deja de encajar, conviene revisarlo contra el HTML real de un resultado de búsqueda.
 *
 * Igual que Mercadona y Ahorramas, la ficha de producto intenta completarse con la lista
 * real de ingredientes de Open Food Facts (ver [OpenFoodFactsApi] y [fetchIngredientsText]),
 * cayendo a la descripción propia de Alcampo si no se encuentra nada allí.
 *
 * Comprobado contra el HTML real de varias páginas de categoría y ficha de producto
 * (septiembre 2026).
 */
class AlcampoProvider : MainAPI() {
    override val name = "Alcampo"
    override val mainUrl = "https://www.compraonline.alcampo.es"
    override val lang = "es"
    override val hasMainPage = true
    override val iconId = R.drawable.ic_alcampo
    override val iconBackgroundId = R.color.white

    private val headers = mapOf(
        "Accept-Language" to "es-ES,es;q=0.9",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    /**
     * Árbol de categorías tal cual se ve en "Todo el catálogo" de Alcampo: cada
     * "--- NOMBRE ---" abre una categoría grande y las entradas siguientes (hasta la
     * próxima cabecera) son sus categorías pequeñas, igual que en
     * MercadonaProvider/AhorramasProvider. Ver comentario de cabecera para las categorías
     * que se han quedado sin desglosar.
     */
    private val allCategoriesRaw = listOf(
        "--- FOLLETOS Y PROMOCIONES ---" to "categories/folletos-y-promociones/OCFYP",
        "Folletos y Promociones" to "categories/folletos-y-promociones/OCFYP",

        "--- FRESCOS ---" to "categories/frescos/OC2112",
        "Frutas" to "categories/frescos/frutas/OC1701",
        "Verduras y hortalizas" to "categories/frescos/verduras-y-hortalizas/OC1702",
        "Carne" to "categories/frescos/carne/OC13",
        "Pescados, mariscos y moluscos" to "categories/frescos/pescados-mariscos-y-moluscos/OC14",
        "Ahumados, surimis, anchoas, pulpos y otros" to "categories/frescos/ahumados-surimis-anchoas-pulpos-y-otros/OC184",
        "Charcutería" to "categories/frescos/charcutería/OC15",
        "Jamones y paletas" to "categories/frescos/jamones-y-paletas/OC151001",
        "Quesos" to "categories/frescos/quesos/OCQuesos",
        "Panadería" to "categories/frescos/panadería/OC1281",
        "Pastelería" to "categories/frescos/pastelería/OC1282",

        "--- LECHE, HUEVOS, LÁCTEOS, YOGURES Y BEBIDAS VEGETALES ---" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/OC16",
        "Leche" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/leche/OC1603",
        "Bebidas vegetales" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/bebidas-vegetales/OC1609",
        "Preparado lácteo" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/preparado-lácteo/OCPreparadolacteo",
        "Huevos" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/huevos/OC1608",
        "Yogures, Bífidus, L-Casei y productos vegetales y fermentados" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/yogures-bífidus-l-casei-y-productos-vegetales-y-fermentados/OC1601",
        "Postres" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/postres/OC1602",
        "Mantequilla" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/mantequilla/OC1606",
        "Margarinas y otros untables" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/margarinas-y-otros-untables/OC1607",
        "Nata" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/nata/OC1605",
        "Batidos, horchatas y bebidas frías de café" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/batidos-horchatas-y-bebidas-frías-de-café/OC1604",
        "Zumos con leche" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/zumos-con-leche/OC160403",
        "Leche condensada, polvo y evaporada" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/leche-condensada-polvo-y-evaporada/OC160316",
        "Productos proteicos" to "categories/leche-huevos-lácteos-yogures-y-bebidas-vegetales/productos-proteicos/OC1612",

        "--- ALIMENTACIÓN ---" to "categories/alimentación/OCC10",
        "Conservas de pescado" to "categories/alimentación/conservas-de-pescado/OC100402",
        "Conservas vegetales" to "categories/alimentación/conservas-vegetales/OC100401",
        "Conservas cárnicas, platos preparados y almíbares" to "categories/alimentación/conservas-cárnicas-platos-preparados-y-almíbares/OC1004",
        "Aceite y Vinagre" to "categories/alimentación/aceite-y-vinagre/OC18",
        "Sal, Especias y Sazonadores" to "categories/alimentación/sal-especias-y-sazonadores/OC611",
        "Aperitivos, aceitunas y frutos secos" to "categories/alimentación/aperitivos-aceitunas-y-frutos-secos/OC120",
        "Tomate Frito y Salsas" to "categories/alimentación/tomate-frito-y-salsas/OCTomateySalsas",
        "Arroz y Legumbres" to "categories/alimentación/arroz-y-legumbres/OC140",
        "Pasta alimenticia" to "categories/alimentación/pasta-alimenticia/OC100501",
        "Sopas, Caldos y Cremas" to "categories/alimentación/sopas-caldos-y-cremas/OCCaldosycremas",
        "Panadería, Harina y Masas" to "categories/alimentación/panadería-harina-y-masas/OC1009",
        "Comida Internacional" to "categories/alimentación/comida-internacional/OC9410",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- DESAYUNO Y MERIENDA ---" to "categories/desayuno-y-merienda/OC10",
        "Desayuno y Merienda" to "categories/desayuno-y-merienda/OC10",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- CONGELADOS ---" to "categories/congelados/OC200220183",
        "Congelados" to "categories/congelados/OC200220183",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- COMIDA PREPARADA ---" to "categories/comida-preparada/OC20022018",
        "Comida Preparada" to "categories/comida-preparada/OC20022018",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- SUPERMERCADO ECOLÓGICO ---" to "categories/supermercado-ecológico/OC26112021",
        "Supermercado Ecológico" to "categories/supermercado-ecológico/OC26112021",

        "--- BEBIDAS ---" to "categories/bebidas/OCC11",
        "Refrescos" to "categories/bebidas/refrescos/OC1103",
        "Bebidas energéticas" to "categories/bebidas/bebidas-energéticas/OC110311",
        "Agua, Soda y Gaseosas" to "categories/bebidas/agua-soda-y-gaseosas/OC1101",
        "Tintos de verano y sangrías" to "categories/bebidas/tintos-de-verano-y-sangrías/OC11534",
        "Zumos de Frutas" to "categories/bebidas/zumos-de-frutas/OC1102",
        "Cervezas" to "categories/bebidas/cervezas/OC1107",
        "Vino Tinto" to "categories/bebidas/vino-tinto/OC1151",
        "Vino Blanco" to "categories/bebidas/vino-blanco/OC1152",
        "Vino rosados, frizzantes, dulces y olorosos" to "categories/bebidas/vino-rosados-frizzantes-dulces-y-olorosos/OC1153",
        "Champagne, Cavas y Sidras" to "categories/bebidas/champagne-cavas-y-sidras/OC1156",
        "Bebidas Alcohólicas" to "categories/bebidas/bebidas-alcohólicas/OC1154",
        "Licores" to "categories/bebidas/licores/OC1155",
        "Vinos y bebidas sin alcohol" to "categories/bebidas/vinos-y-bebidas-sin-alcohol/OC25042023",
        "Bebidas ecológicas" to "categories/bebidas/bebidas-ecológicas/OC101303",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- SIN GLUTEN / SIN LACTOSA, NUTRICIÓN DEPORTIVA Y FUNCIONAL ---" to "categories/sin-gluten-sin-lactosa-nutrición-deportiva-y-funcional/OCSINGSINL",
        "Sin Gluten / Sin Lactosa, Nutrición deportiva y Funcional" to "categories/sin-gluten-sin-lactosa-nutrición-deportiva-y-funcional/OCSINGSINL",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- VEGANOS ---" to "categories/veganos/OC09112021",
        "Veganos" to "categories/veganos/OC09112021",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- DROGUERÍA ---" to "categories/droguería/OCC14",
        "Droguería" to "categories/droguería/OCC14",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- PERFUMERIA ---" to "categories/perfumeria/OC70",
        "Perfumeria" to "categories/perfumeria/OC70",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- BEBÉ ---" to "categories/bebé/OCC13",
        "Bebé" to "categories/bebé/OCC13",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- PARAFARMACIA ---" to "categories/parafarmacia/OC69",
        "Parafarmacia" to "categories/parafarmacia/OC69",

        "--- ELECTRODOMÉSTICOS ---" to "categories/electrodomésticos/OC555",
        "Lavadoras" to "categories/electrodomésticos/lavadoras/OC25",
        "Frigoríficos" to "categories/electrodomésticos/frigoríficos/OC28",
        "Lavavajillas" to "categories/electrodomésticos/lavavajillas/OC27",
        "Secadoras" to "categories/electrodomésticos/secadoras/OC26",
        "Hornos, placas y campanas" to "categories/electrodomésticos/hornos-placas-y-campanas/OC500",
        "Electrodomésticos integrables" to "categories/electrodomésticos/electrodomésticos-integrables/OCEINT",
        "Microondas" to "categories/electrodomésticos/microondas/OC2409",
        "Cafeteras" to "categories/electrodomésticos/cafeteras/OC23093",
        "Freidoras de aire" to "categories/electrodomésticos/freidoras-de-aire/OC040301",
        "Preparación de alimentos" to "categories/electrodomésticos/preparación-de-alimentos/OC23092",
        "Conservación de alimentos" to "categories/electrodomésticos/conservación-de-alimentos/OC23091",
        "Aspiración y limpieza" to "categories/electrodomésticos/aspiración-y-limpieza/OC540",
        "Planchado y costura" to "categories/electrodomésticos/planchado-y-costura/OC560",
        "Belleza" to "categories/electrodomésticos/belleza/OC2411",
        "Cepillos de dientes eléctricos" to "categories/electrodomésticos/cepillos-de-dientes-eléctricos/OC2311",
        "Cuidado de la salud" to "categories/electrodomésticos/cuidado-de-la-salud/OC2405",
        "Electrodomésticos Qilive" to "categories/electrodomésticos/electrodomésticos-qilive/OC30220",
        "Ventilación" to "categories/electrodomésticos/ventilación/OC10090701",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- TECNOLOGÍA ---" to "categories/tecnología/OC679",
        "Tecnología" to "categories/tecnología/OC679",

        "--- HOGAR Y DECORACIÓN ---" to "categories/hogar-y-decoración/OCC115",
        "Textil hogar" to "categories/hogar-y-decoración/textil-hogar/OCC130",
        "Menaje de cocina" to "categories/hogar-y-decoración/menaje-de-cocina/OC59",
        "Vajilla desechable" to "categories/hogar-y-decoración/vajilla-desechable/OC1062",
        "Utensilios de cocina" to "categories/hogar-y-decoración/utensilios-de-cocina/OC5991",
        "Cubiertos, vasos, copas y botellas" to "categories/hogar-y-decoración/cubiertos-vasos-copas-y-botellas/OC599",
        "Tazas, fuentes y bols" to "categories/hogar-y-decoración/tazas-fuentes-y-bols/OC599016",
        "Vajillas y complementos" to "categories/hogar-y-decoración/vajillas-y-complementos/OC5901",
        "Orden en la cocina" to "categories/hogar-y-decoración/orden-en-la-cocina/OC5992",
        "Filtración de agua" to "categories/hogar-y-decoración/filtración-de-agua/OC5906",
        "Cuidado de la ropa" to "categories/hogar-y-decoración/cuidado-de-la-ropa/OC106",
        "Salón comedor" to "categories/hogar-y-decoración/salón-comedor/OC60",
        "Dormitorio" to "categories/hogar-y-decoración/dormitorio/OC61",
        "Baño" to "categories/hogar-y-decoración/baño/OC62",
        "Fiestas y cumpleaños" to "categories/hogar-y-decoración/fiestas-y-cumpleaños/OC10625",
        "Colchones" to "categories/hogar-y-decoración/colchones/OCCOL",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- JARDÍN Y TERRAZA ---" to "categories/jardín-y-terraza/OC6014",
        "Jardín y terraza" to "categories/jardín-y-terraza/OC6014",

        "--- MASCOTAS ---" to "categories/mascotas/OC062",
        "Comida gatos" to "categories/mascotas/comida-gatos/OC0624",
        "Higiene gatos" to "categories/mascotas/higiene-gatos/OC0625",
        "Accesorios gatos" to "categories/mascotas/accesorios-gatos/OC0626",
        "Comida perros" to "categories/mascotas/comida-perros/OC0621",
        "Accesorios perros" to "categories/mascotas/accesorios-perros/OC0623",
        "Higiene perros" to "categories/mascotas/higiene-perros/OC0622",
        "Conejos y roedores" to "categories/mascotas/conejos-y-roedores/OC0627",
        "Pájaros" to "categories/mascotas/pájaros/OC0628",
        "Peces y tortugas" to "categories/mascotas/peces-y-tortugas/OC0629",
        "Hogar limpio y fresco" to "categories/mascotas/hogar-limpio-y-fresco/OC6210",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- JUGUETES ---" to "categories/juguetes/OC1021",
        "Juguetes" to "categories/juguetes/OC1021",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- PAPELERÍA ---" to "categories/papelería/OC191102",
        "Papelería" to "categories/papelería/OC191102",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- BRICOLAJE ---" to "categories/bricolaje/OCC153",
        "Bricolaje" to "categories/bricolaje/OCC153",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- AUTOMÓVIL ---" to "categories/automóvil/OCAUTM",
        "Automóvil" to "categories/automóvil/OCAUTM",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- LIBROS ---" to "categories/libros/OC19082019",
        "Libros" to "categories/libros/OC19082019",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- DEPORTES Y MALETAS ---" to "categories/deportes-y-maletas/OCC112",
        "Deportes y Maletas" to "categories/deportes-y-maletas/OCC112",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- TEXTIL ---" to "categories/textil/OC0301",
        "Textil" to "categories/textil/OC0301",

        // Sin desglosar por bloqueos de la web durante la recopilación (ver cabecera).
        "--- CAMPAÑAS ---" to "categories/campañas/OCC",
        "Campañas" to "categories/campañas/OCC"
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
            val res = app.get(
                "$mainUrl/search",
                params = mapOf("keywords" to query),
                headers = headers
            )
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
            val price = lastPrice(document.text())
            val description = productDescription(document)

            // Igual que en MercadonaProvider/AhorramasProvider: se intenta sustituir la
            // descripción propia de la tienda por la lista de ingredientes de ese mismo
            // producto en Open Food Facts, cayendo a la descripción original si no se
            // encuentra nada allí.
            val ingredients = fetchIngredientsText(document, name)
            val descriptionBlock = if (ingredients != null) {
                "🧾 INGREDIENTES (Open Food Facts):\n$ingredients"
            } else {
                description
            }

            val synopsisParts = mutableListOf<String>()
            if (price != null) synopsisParts.add("💰 PRECIO: $price €")
            if (descriptionBlock.isNotEmpty()) synopsisParts.add(descriptionBlock)

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
     * Alcampo tampoco expone el código de barras (EAN) en ningún endpoint JSON propio; se
     * intenta sacarlo de forma heurística del bloque de datos estructurados
     * schema.org/Product (JSON-LD) que suelen incluir las tiendas de comercio electrónico
     * (Ocado Smart Platform incluido) para SEO, y si no aparece ahí se cae directamente a
     * que [OpenFoodFactsApi.findIngredients] busque por nombre + marca (esta última, si se
     * encuentra, en la propia meta de marca de la página).
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
        return document.selectFirst("meta[itemprop=brand], meta[property=product:brand]")
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun loadHtml(url: String): String? {
        return try {
            val res = app.get(url, headers = headers)
            if (res.code != 200) return null
            val document = res.document

            val name = document.selectFirst("h1")?.text()?.trim() ?: "Producto"
            val image = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
            val description = productDescription(document)
            val price = lastPrice(document.text()) ?: "N/A"

            // Igual que en load(): se prioriza mostrar los ingredientes de Open Food
            // Facts en vez de la descripción propia de la tienda, cayendo a esta última
            // solo si no se encuentra nada en Open Food Facts.
            val ingredients = fetchIngredientsText(document, name)
            val descriptionTitle = if (ingredients != null) "Ingredientes (Open Food Facts)" else "Descripción"
            val descriptionBody = ingredients ?: description

            """
            <div style="text-align: center; font-family: sans-serif; padding: 20px; background-color: #fff;">
                <img src="$image" style="width: 100%; max-width: 400px; border-radius: 15px; box-shadow: 0 4px 12px rgba(0,0,0,0.15);" />
                <h1 style="color: #E5007E; margin-top: 25px; font-size: 26px; font-weight: bold;">$name</h1>
                <div style="background-color: #E5007E; color: white; display: inline-block; padding: 12px 35px; border-radius: 40px; font-size: 28px; font-weight: bold; margin: 20px 0;">
                    $price €
                </div>
                <div style="text-align: left; margin-top: 30px; border-top: 2px solid #eee; padding-top: 25px;">
                    <h3 style="color: #333; font-size: 20px; border-bottom: 1px solid #E5007E; display: inline-block; padding-bottom: 5px;">$descriptionTitle</h3>
                    <p style="line-height: 1.6; color: #444; font-size: 16px; margin-top: 15px; white-space: pre-line;">$descriptionBody</p>
                </div>
            </div>
            """.trimIndent()
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    // La descripción viene en la meta og:description con saltos de línea codificados como
    // "<br>" literal (no son etiquetas reales, es texto plano), así que se convierten en
    // saltos de línea de verdad.
    private fun productDescription(document: Document): String {
        val raw = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: ""
        return raw.replace(Regex("(?i)<br\\s*/?>"), "\n").trim()
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val targetCategoryId = mainCategory ?: categoryGroups.firstOrNull()?.id

        // Al igual que en MercadonaProvider/AhorramasProvider, cada página de categoría de
        // Alcampo trae ya en el HTML todo su catálogo (no hay paginación real por "page"; el
        // propio buscador de Alcampo la ignora, según se pudo comprobar), así que se
        // simplifica y no se pide más allá de esa primera página.
        if (page > 1) {
            return HeadMainPageResponse(targetCategoryId ?: "", emptyList())
        }

        return coroutineScope {
            val subCats = categorySubCategories[targetCategoryId] ?: emptyList()

            // Lanzamos peticiones en paralelo para todas las subcategorías de esta sección
            // grande, e intercalamos un título divisorio con el nombre de cada una que tenga
            // productos (así se ve igual que en la web de Alcampo).
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

    private fun parseProductTiles(document: Document): List<SearchResponse> {
        val links = document.select("a[href*=\"/products/\"]")
        val list = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()
        for (link in links) {
            val href = fixUrl(link.attr("href"))
            // Cada producto aparece dos veces en la parrilla (miniatura + nombre) con el
            // mismo enlace; solo si la primera ocurrencia no se pudo leer (por ejemplo, si no
            // tuviera imagen con "alt") se deja pasar la segunda.
            if (seenIds.contains(href)) continue
            val product = parseProductLink(link, href) ?: continue
            seenIds.add(href)
            list.add(product)
        }
        return list
    }

    private fun parseProductLink(link: Element, href: String): SearchResponse? {
        val img = link.selectFirst("img")
        val name = img?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.text().trim().takeIf { it.isNotEmpty() }
            ?: return null

        val thumb = img?.attr("abs:src")?.takeIf { it.isNotEmpty() }
            ?: img?.attr("abs:data-src")?.takeIf { it.isNotEmpty() }
            ?: ""

        // La ficha de Alcampo no tiene un contenedor con clase fija por producto (o al menos
        // no una que se pueda dar por estable), así que se sube por el árbol del DOM desde el
        // propio enlace hasta encontrar el primer antecesor cuyo texto ya incluya un precio en
        // euros y el botón "Añadir": ese antecesor es, con fiabilidad razonable, el límite de
        // la ficha de este producto en concreto (y no de toda la parrilla), porque cada ficha
        // está en su propio contenedor y el precio/botón de un producto siempre son
        // descendientes de ese mismo contenedor.
        val card = climbToCard(link) ?: link
        val price = lastPrice(card.text())

        return newSearchResponse(name, href, fix = false) {
            posterUrl = thumb
            if (price != null) this.price = "$price €"
        }
    }

    private fun climbToCard(start: Element): Element? {
        var el: Element? = start
        var hops = 0
        while (el != null && hops < 8) {
            val text = el.text()
            if (text.contains("€") && text.contains("Añadir")) return el
            el = el.parent()
            hops++
        }
        return null
    }

    private val priceRegex = Regex("""(\d{1,4}(?:\.\d{3})*,\d{2})\s*€""")

    // Alcampo muestra primero el precio por litro/kilogramo (entre paréntesis) y, justo antes
    // del botón "Añadir", el precio real del producto: por eso se coge el último importe
    // encontrado antes de ese botón, no el primero.
    private fun lastPrice(text: String): String? {
        val addButtonIndex = text.indexOf("Añadir")
        val scope = if (addButtonIndex >= 0) text.substring(0, addButtonIndex) else text
        return priceRegex.findAll(scope).lastOrNull()?.groupValues?.get(1)
    }
}
