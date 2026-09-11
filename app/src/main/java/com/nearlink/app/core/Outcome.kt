package com.nearlink.app.core

/**
 * Resultado tipado para operaciones que pueden fallar.
 *
 * Se usa en lugar de lanzar excepciones hacia la capa de UI: obliga a tratar
 * el error y evita try/catch desperdigados por los ViewModels.
 */
sealed interface Outcome<out T> {

    data class Success<out T>(val data: T) : Outcome<T>

    data class Failure(val cause: Throwable? = null, val message: String) : Outcome<Nothing>

    companion object {
        fun <T> of(block: () -> T): Outcome<T> = try {
            Success(block())
        } catch (t: Throwable) {
            Failure(t, t.message ?: "Error inesperado")
        }
    }
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(data))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(data)
    return this
}

inline fun <T> Outcome<T>.onFailure(action: (Outcome.Failure) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(this)
    return this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.data

/** Version suspendida de [Outcome.of] para bloques que llaman a corrutinas. */
suspend fun <T> outcomeOf(block: suspend () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (t: Throwable) {
    Outcome.Failure(t, t.message ?: "Error inesperado")
}
