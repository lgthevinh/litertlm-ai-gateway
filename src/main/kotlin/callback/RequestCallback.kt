package org.thingai.app.aigateway.callback

interface RequestCallback<T> {
    fun onSuccess(result: T)
    fun onError(error: String)
}