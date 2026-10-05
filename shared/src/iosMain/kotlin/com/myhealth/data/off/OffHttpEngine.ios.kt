package com.myhealth.data.off

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

actual fun offHttpEngine(): HttpClientEngineFactory<*> = Darwin
