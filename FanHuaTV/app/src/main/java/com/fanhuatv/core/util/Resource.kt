package com.fanhuatv.core.util

/** 统一结果封装，全项目错误处理只用这一个类型。 */
sealed class Resource<out T> {
    data class Success<out T>(val data: T) : Resource<T>()
    data class Error(val message: String, val code: Int = -1) : Resource<Nothing>()
    data object Loading : Resource<Nothing>()
}

inline fun <T, R> Resource<T>.map(transform: (T) -> R): Resource<R> = when (this) {
    is Resource.Success -> Resource.Success(transform(data))
    is Resource.Error -> this
    Resource.Loading -> Resource.Loading
}

fun <T> Resource<T>.getOrNull(): T? = if (this is Resource.Success) data else null
