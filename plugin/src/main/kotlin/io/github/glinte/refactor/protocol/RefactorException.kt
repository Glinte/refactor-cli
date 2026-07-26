package io.github.glinte.refactor.protocol

class RefactorException(
    val symbolicCode: String,
    val exitCode: Int,
    override val message: String,
) : RuntimeException(message)
