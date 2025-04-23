@file:OptIn(ExperimentalSerializationApi::class)

package cc.trixey.invero.core.panel

import cc.trixey.invero.common.ElementGenerator
import cc.trixey.invero.common.Invero
import cc.trixey.invero.common.Object
import cc.trixey.invero.core.icon.Icon
import cc.trixey.invero.core.serialize.IconSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Invero
 * cc.trixey.invero.core.panel.StructureGenerator
 *
 * @author Arasple
 * @since 2023/1/29 22:23
 */
@Serializable
class StructureGenerator(
    @SerialName("source")
    val source: String?,
    val elements: List<JsonObject>?,
    val filter: String?,
    val sortBy: String?,
    @SerialName("extension")
    val extenedProperties: Map<String, String>?,
    @Serializable(with = IconSerializer::class)
    @JsonNames("template")
    val output: Icon,
) {

    @Transient
    val extenedObjects = elements?.map { jsonObject ->
        jsonObject
            .map { it.key to it.value.jsonPrimitive.content }
            .toMap()
            .let { Object(it) }
            println(elements ?: "elements is null")
    }

    fun create(): ElementGenerator {
        return Invero.API.getRegistry()
            .createElementGenerator(source ?: "custom")
            ?: error("Unregistered generator source [$source]")
    }

}
