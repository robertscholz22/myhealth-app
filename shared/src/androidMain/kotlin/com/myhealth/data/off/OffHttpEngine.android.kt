package com.myhealth.data.off

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

actual fun offHttpEngine(): HttpClientEngineFactory<*> = OkHttp
