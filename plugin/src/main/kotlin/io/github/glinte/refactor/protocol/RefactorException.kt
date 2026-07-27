package io.github.glinte.refactor.protocol

import kotlinx.serialization.json.JsonObject

class RefactorException(
    val symbolicCode: String,
    val exitCode: Int,
    override val message: String,
    val details: JsonObject = JsonObject(emptyMap()),
) : RuntimeException(message)
