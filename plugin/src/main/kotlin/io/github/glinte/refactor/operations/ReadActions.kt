package io.github.glinte.refactor.operations

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable

internal fun <T> blockingReadAction(action: () -> T): T =
    ApplicationManager.getApplication().runReadAction(Computable(action))
