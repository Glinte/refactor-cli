package io.github.glinte.refactor.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.glinte.refactor.server.RefactorAgentServer

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        service<RefactorAgentServer>().register(project)
    }
}
