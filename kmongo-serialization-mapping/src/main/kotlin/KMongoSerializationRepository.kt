/*
 * Copyright (C) 2017/2019 Litote
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.litote.kmongo.serialization

import com.github.jershell.kbson.BigDecimalSerializer
import com.github.jershell.kbson.ByteArraySerializer
import com.github.jershell.kbson.DateSerializer
import com.github.jershell.kbson.ObjectIdSerializer
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.internal.PairSerializer
import kotlinx.serialization.internal.ReferenceArraySerializer
import kotlinx.serialization.internal.StringSerializer
import kotlinx.serialization.internal.TripleSerializer
import kotlinx.serialization.json.JsonNullSerializer
import kotlinx.serialization.modules.SerialModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.serializersModuleOf
import kotlinx.serialization.serializer
import org.bson.BsonTimestamp
import org.bson.types.Binary
import org.bson.types.ObjectId
import org.litote.kmongo.Id
import org.litote.kmongo.id.StringId
import org.litote.kmongo.id.WrappedObjectId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

@PublishedApi
internal val customSerializersMap: MutableMap<KClass<*>, KSerializer<*>> = ConcurrentHashMap()
private val customModules = CopyOnWriteArraySet<SerialModule>()

/**
 * Add a custom [SerialModule] to KMongo kotlinx.serialization mapping.
 */
fun registerModule(module: SerialModule) {
    customModules.add(module)
}

/**
 * Add a custom serializer to KMongo kotlinx.serialization mapping
 */
inline fun <reified T> registerSerializer(serializer: KSerializer<T>) {
    customSerializersMap[T::class] = serializer
}

/**
 *
 */
internal object KMongoSerializationRepository {

    private val serializersMap: Map<KClass<*>, KSerializer<*>> = mapOf(
        ObjectId::class to ObjectIdSerializer,
        BigDecimal::class to BigDecimalSerializer,
        ByteArray::class to ByteArraySerializer,
        Date::class to DateSerializer,
        Calendar::class to CalendarSerializer,
        GregorianCalendar::class to CalendarSerializer,
        Instant::class to InstantSerializer,
        ZonedDateTime::class to ZonedDateTimeSerializer,
        OffsetDateTime::class to OffsetDateTimeSerializer,
        LocalDate::class to LocalDateSerializer,
        LocalDateTime::class to LocalDateTimeSerializer,
        LocalTime::class to LocalTimeSerializer,
        OffsetTime::class to OffsetTimeSerializer,
        BsonTimestamp::class to BsonTimestampSerializer,
        Locale::class to LocaleSerializer,
        Binary::class to BinarySerializer,
        Id::class to IdSerializer(false),
        StringId::class to IdSerializer(true),
        WrappedObjectId::class to IdSerializer(false)
    )

    @ImplicitReflectionSerializer
    private fun getBaseSerializer(obj: Any): KSerializer<*>? {
        @Suppress("UNCHECKED_CAST")
        return when (obj) {
            is KProperty<*> -> KPropertySerializer
            is Pair<*, *> -> PairSerializer(getSerializer(obj.first), getSerializer(obj.second))
            is Triple<*, *, *> -> TripleSerializer(
                getSerializer(obj.first),
                getSerializer(obj.second),
                getSerializer(obj.third)
            )
            is Array<*> -> ReferenceArraySerializer(
                obj.javaClass.kotlin as KClass<Any>,
                obj.filterNotNull().let {
                    if (it.isEmpty()) StringSerializer else getSerializer(it.first())
                } as KSerializer<Any>
            )
            else -> module.getContextual(obj.javaClass.kotlin)
                    ?: module.getPolymorphic(obj.javaClass.kotlin, obj)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @ImplicitReflectionSerializer
    fun <T : Any> getSerializer(obj: T?): KSerializer<T> =
        if (obj == null) {
            JsonNullSerializer as? KSerializer<T> ?: error("no serializer for null")
        } else {
            (serializersMap[obj.javaClass.kotlin]
                    ?: getBaseSerializer(obj)
                    ?: obj.javaClass.kotlin.serializer()) as? KSerializer<T>
                    ?: error("no serializer for $obj of class ${obj.javaClass.kotlin}")
        }

    @Suppress("UNCHECKED_CAST")
    @ImplicitReflectionSerializer
    fun <T : Any> getSerializer(kClass: KClass<T>): KSerializer<T> =
        (serializersMap[kClass]
                ?: module.getContextual(kClass)
                ?: kClass.serializer()) as? KSerializer<T>
                ?: error("no serializer for $kClass of class $kClass")

    @Volatile
    private var baseModule: SerialModule = SerializersModule {
        include(serializersModuleOf(serializersMap))
        include(serializersModuleOf(customSerializersMap))
        customModules.forEach { include(it) }
    }
    @Volatile
    private var customModulesSize: Int = customModules.size
    @Volatile
    private var customSerializersSize: Int = customSerializersMap.size

    val module: SerialModule
        get() {
            if (customSerializersSize != customSerializersMap.size || customModulesSize != customModules.size) {
                customSerializersSize = customSerializersMap.size
                customModulesSize = customModules.size
                baseModule = SerializersModule {
                    include(serializersModuleOf(serializersMap))
                    include(serializersModuleOf(customSerializersMap))
                    customModules.forEach { include(it) }
                }
            }
            return baseModule
        }
}