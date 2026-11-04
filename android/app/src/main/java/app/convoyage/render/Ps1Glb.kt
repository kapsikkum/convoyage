package app.convoyage.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A vehicle body modelled in Blender (design-tools/cars/build_car.py) and shipped as assets/cars/<id>.glb: one
 * primitive per named material, metres, x forward, y up, z right, ground at y = 0, centred on its length.
 */
class Ps1CarModel(
    val parts: List<Part>,
    /** Overall length in metres (the body is centred on it). */
    val len: Float,
    /** Metres back from the nose to the front axle, wheelbase, tyre diameter and width. */
    val fo: Float, val wb: Float, val tyre: Float, val tyreW: Float,
    /** Wheel centre to the car's middle line, front then rear. */
    val halfTrack: FloatArray,
) {
    class Part(val material: String, val positions: FloatArray, val normals: FloatArray, val indices: IntArray)

    val triangles: Int get() = parts.sumOf { it.indices.size / 3 }
}

/** The smallest glTF 2.0 binary reader that reads what build_car.py writes. */
object Ps1Glb {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(bytes: ByteArray): Ps1CarModel {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.getInt(0) == 0x46546C67) { "not a GLB" }
        val jsonLen = bb.getInt(12)
        require(bb.getInt(16) == 0x4E4F534A) { "first chunk is not JSON" }
        val doc = json.parseToJsonElement(String(bytes, 20, jsonLen, Charsets.UTF_8)).jsonObject
        val binStart = 20 + jsonLen + 8
        val accessors = doc["accessors"]!!.jsonArray
        val views = doc["bufferViews"]!!.jsonArray
        val materials = doc["materials"]?.jsonArray?.map { it.jsonObject["name"]?.jsonPrimitive?.content ?: "paint" } ?: emptyList()

        fun view(acc: JsonObject): Pair<Int, Int> {
            val v = views[acc["bufferView"]!!.jsonPrimitive.int].jsonObject
            val off = binStart + (v["byteOffset"]?.jsonPrimitive?.int ?: 0) + (acc["byteOffset"]?.jsonPrimitive?.int ?: 0)
            return Pair(off, acc["count"]!!.jsonPrimitive.int)
        }

        fun floats(i: Int, comps: Int): FloatArray {
            val acc = accessors[i].jsonObject
            val (off, count) = view(acc)
            return FloatArray(count * comps) { bb.getFloat(off + it * 4) }
        }

        fun ints(i: Int): IntArray {
            val acc = accessors[i].jsonObject
            val (off, count) = view(acc)
            return when (val ct = acc["componentType"]!!.jsonPrimitive.int) {
                5121 -> IntArray(count) { bytes[off + it].toInt() and 0xFF }
                5123 -> IntArray(count) { bb.getShort(off + it * 2).toInt() and 0xFFFF }
                5125 -> IntArray(count) { bb.getInt(off + it * 4) }
                else -> error("index type $ct")
            }
        }

        val parts = ArrayList<Ps1CarModel.Part>()
        for (mesh in doc["meshes"]!!.jsonArray) for (prim in mesh.jsonObject["primitives"]!!.jsonArray) {
            val p = prim.jsonObject
            val attrs = p["attributes"]!!.jsonObject
            val pos = floats(attrs["POSITION"]!!.jsonPrimitive.int, 3)
            val nrm = attrs["NORMAL"]?.let { floats(it.jsonPrimitive.int, 3) } ?: FloatArray(pos.size)
            val idx = p["indices"]?.let { ints(it.jsonPrimitive.int) } ?: IntArray(pos.size / 3) { it }
            val mat = p["material"]?.jsonPrimitive?.int?.let { materials.getOrNull(it) } ?: "paint"
            parts += Ps1CarModel.Part(mat.substringBefore('.'), pos, nrm, idx)
        }
        val extras = (doc["nodes"] as? JsonArray)?.firstNotNullOfOrNull { n ->
            n.jsonObject["extras"]?.jsonObject?.get("convoyage")?.jsonPrimitive?.content
        }?.let { json.parseToJsonElement(it).jsonObject }
        fun f(k: String, d: Float) = extras?.get(k)?.jsonPrimitive?.content?.toFloatOrNull() ?: d
        val ht = extras?.get("halfTrack")?.jsonArray?.map { it.jsonPrimitive.content.toFloat() }?.toFloatArray() ?: floatArrayOf(0.75f, 0.75f)
        return Ps1CarModel(parts, f("len", 4.5f), f("fo", 0.9f), f("wb", 2.7f), f("tyre", 0.65f), f("tyreW", 0.235f), ht)
    }
}

/**
 * Where Blender models come from: the app points [source] at its assets at start-up; tests point it at the source
 * folder. Parsed models are kept for the life of the process.
 */
object Ps1CarModels {
    @Volatile var source: ((String) -> ByteArray?)? = null
    private val cache = HashMap<String, Ps1CarModel?>()

    fun of(id: String): Ps1CarModel? = synchronized(cache) {
        cache.getOrPut(id) {
            try {
                source?.invoke("cars/$id.glb")?.let { Ps1Glb.parse(it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun clear() = synchronized(cache) { cache.clear() }
}
