package ink.sora.ioc

import java.io.Closeable
import java.lang.IllegalArgumentException
import kotlin.random.Random
import kotlin.reflect.*
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

internal fun <T : Any> KClass<T>.parameterlessConstructorOrNull(): KFunction<T>? {
    return constructors.firstOrNull { !it.parameters.any() }
}

/**
 * Get a constructor of [KClass] whose parameters are exactly the same as [types]
 */
internal fun <T : Any> KClass<T>.getConstructor(vararg types: KClass<*>): KFunction<T> {
    return (if (!types.any()) {
        parameterlessConstructorOrNull()
    } else constructors.firstOrNull {
        it.parameters.foldIndexed(true) { index, acc, param -> acc && types[index] == param.type.classifier as KClass<*> }
    }) ?: throw IllegalArgumentException("cannot find a constructor that is compatible with the specified types")
}

/**
 * Finds an appropriate constructor of [KClass] from [types], whereby "appropriate" means that
 * every argument of the constructor of [KClass] can be obtained from [types]
 */
internal fun <T : Any> KClass<T>.findAppropriateConstructor(types: Array<ComponentRegistrar<*>>): KFunction<T> {
    return (if (!types.any()) {
        parameterlessConstructorOrNull()
    } else constructors.firstOrNull {
        val copy = mutableListOf(*types)
        it.parameters.fold(true) { acc, param ->
            acc && copy.firstOrNull { t -> t.isAssignableFrom((param.type.classifier as KClass<*>)) }.let(copy::remove)
        }
    }) ?: throw IllegalArgumentException("cannot find a constructor that is compatible with the specified types")
}

/**
 * Find all the public properties of [KClass] that are marked with [Autowired]
 */
internal fun <T : Any> KClass<T>.findAutowiredProperties(): Collection<KMutableProperty1<T, *>> {
    return declaredMemberProperties.filterIsInstance<KMutableProperty1<T, *>>()
        .filter { it.visibility == KVisibility.PUBLIC }
        .filter { it.hasAnnotation<Autowired>() }
}

internal fun <T, V> Iterable<T>.toMap(valueSelector: (T) -> V): Map<T, V> {
    return mutableMapOf<T, V>().apply {
        this@toMap.forEach { this[it] = valueSelector(it) }
    }
}

@Target(AnnotationTarget.PROPERTY)
annotation class Autowired

enum class ResolutionStrategy {
    TRANSIENT, SINGLETON
}

class ComponentRegistrar<T : Any>(private val container: IContainer, private val type: KClass<out T>, private var value: T? = null) {
    companion object {
        internal val singletons: MutableMap<KClass<*>, Any> = mutableMapOf()
    }

    internal var resolutionStrategy: ResolutionStrategy = ResolutionStrategy.TRANSIENT
    private var base: KClass<*>? = null
    private val autowireProperties: MutableList<KMutableProperty1<out T, *>> = mutableListOf()
    private lateinit var constructor: KFunction<T>

    init {
        if (value == null) {
            useDefaultConstructor()
            autowireProperties.addAll(type.findAutowiredProperties())
        } else {
            putSingleton()
        }
    }

    private fun putSingleton() {
        resolutionStrategy = ResolutionStrategy.SINGLETON
        singletons[type] = value!!
    }

    private fun useDefaultConstructor() {
        constructor = type.primaryConstructor ?: type.parameterlessConstructorOrNull() ?: type.constructors.first()
        if (constructor.visibility != KVisibility.PUBLIC) {
            throw IllegalStateException("Cannot find a public, parameterless constructor for type $type")
        }
    }

    fun useConstructor(vararg types: KClass<*>): ComponentRegistrar<T> {
        constructor = type.getConstructor(*types)
        return this
    }

    fun autowired(propertySelector: (KProperty<*>) -> Boolean): ComponentRegistrar<T> {
        autowireProperties.addAll(type.declaredMemberProperties
            .filter { autowireProperties.all { prop -> prop.name != it.name } }
            .filterIsInstance<KMutableProperty1<T, *>>()
            .filter { it.visibility == KVisibility.PUBLIC }
            .filter(propertySelector))
        return this
    }

    fun asSingleton(): ComponentRegistrar<T> {
        resolutionStrategy = ResolutionStrategy.SINGLETON
        if (value == null) {
            value = container.resolve(type)
            putSingleton()
        }
        return this
    }

    fun <S : Any> asBase(base: KClass<S>) {
        if (type.isSubclassOf(base)) {
            this.base = base
        } else {
            throw ClassCastException("type $base is not a base class of $type")
        }
    }

    fun asTransient(): ComponentRegistrar<T> {
        resolutionStrategy = ResolutionStrategy.TRANSIENT
        return this
    }

    fun createInstance(): T {
        return constructor.callBy(constructor.parameters.toMap { container.resolve(it.type.classifier as KClass<*>) }).apply(::autowire)
    }

    @Suppress("UNCHECKED_CAST")
    private fun autowire(obj: T) {
        for (prop in autowireProperties) {
            (prop as KMutableProperty1<T, Any>).set(obj, container.resolve(prop.returnType.classifier as KClass<*>))
        }
    }

    fun isAssignableFrom(t: KClass<*>): Boolean {
        return base?.equals(t) ?: (type == t)
    }
}

data class ComponentEntry<T : Any>(val type: KClass<out T>, val registrar: ComponentRegistrar<T>)

interface Resolvable {
    fun <T : Any> resolve(type: KClass<T>): T
}

interface IContainer : Resolvable {
    fun <T : Any> register(type: KClass<T>): ComponentRegistrar<T>

    fun <T : Any> register(instance: T): ComponentRegistrar<T>
}

class ContainerScope(private val container: Container) : Resolvable, Closeable {
    private var disposed = false
    private val values: MutableMap<KClass<*>, Any> = mutableMapOf()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> resolve(type: KClass<T>): T {
        if (disposed) {
            throw ObjectClosedException()
        }
        return values.getOrPut(type) { container.resolve(type) } as T
    }

    override fun close() {
        disposed = true
        values.values.mapNotNull { it as? Closeable }.forEach(Closeable::close)
        values.clear()
    }
}

class Container : IContainer {
    private val entries: MutableList<ComponentEntry<*>> = mutableListOf()

    override fun <T : Any> register(type: KClass<T>): ComponentRegistrar<T> {
        return ComponentRegistrar(this, type).apply {
            entries.add(ComponentEntry(type, this))
        }
    }

    override fun <T : Any> register(instance: T): ComponentRegistrar<T> {
        return ComponentRegistrar(this, instance::class, instance).asSingleton().apply {
            entries.add(ComponentEntry(instance::class, this))
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> resolve(type: KClass<T>): T {
        val entry = entries.firstOrNull { it.registrar.isAssignableFrom(type) } as? ComponentEntry<T>
        return if (entry != null) {
            when (entry.registrar.resolutionStrategy) {
                ResolutionStrategy.TRANSIENT -> entry.registrar.createInstance()
                ResolutionStrategy.SINGLETON -> ComponentRegistrar.singletons[entry.type] as T
            }
        } else {
            val constructor = type.findAppropriateConstructor(entries.map(ComponentEntry<*>::registrar).toTypedArray())
            val obj = constructor.callBy(constructor.parameters.toMap { resolve(it.type.classifier as KClass<T>) })
            for (prop in type.findAutowiredProperties()) {
                (prop as KMutableProperty1<T, Any>).set(obj, resolve(prop.returnType.classifier as KClass<*>))
            }
            obj
        }
    }

    fun resolveScope(): ContainerScope {
        return ContainerScope(this)
    }
}

class ObjectClosedException : Exception()

interface SayHello {
    fun hello()
}

class SayHelloImpl : SayHello {
    override fun hello() {
        println("Hello, World")
    }
}

data class Test1(val i: Int, val j: String, val k: Char) {
    val randomNumber: Int = Random.nextInt()

    constructor(sayHello: SayHello) : this(100, "99", '8') {
        sayHello.hello()
    }
}

class Test2 {
    @Autowired
    lateinit var sayHello: SayHello
}

class Test3 {
    @Autowired
    lateinit var test2: Test2
}

fun main() {
    val container = Container()
    container.register(1)
    container.register("2")
    container.register('3')
    container.register(SayHelloImpl())
        .asBase(SayHello::class)
    container.register(Test1::class)
        .asTransient()
        .useConstructor(SayHello::class)
    println("scope begin")
    container.resolveScope().use {
        for (i in 0..10) {
            println(it.resolve(Test1::class).randomNumber) // all the resolution requests in the same scope results in the same object
        }
    }
    println("scope end")
    for (i in 0..10) {
        println(container.resolve(Test1::class).randomNumber)
    }
    // container.resolve(Test2::class).sayHello.hello()
    // container.register(Test3::class).autowired {
    //     it.returnType.classifier == SayHello::class
    // }
    container.resolve(Test3::class).test2.sayHello.hello()
}