package com.vomiter

import org.gradle.api.Plugin
import org.gradle.api.Project

class DevRuntimePlugin implements Plugin<Project> {

    void apply(Project project) {

        project.extensions.create("devRuntime", DevRuntimeExtension, project)
    }

    static class DevRuntimeExtension {
        private final Project project

        DevRuntimeExtension(Project project) {
            this.project = project
        }

        void project(String path, boolean inRuntime = true) {

            project.evaluationDependsOn(path)

            project.dependencies {
                if (inRuntime) {
                    runtimeOnly project.files(
                            project.project(path)
                                    .tasks.named("devJar")
                                    .flatMap { it.archiveFile }
                    )
                }
                compileOnly project.project(path)
            }

            project.tasks.matching {
                it.name in ["runClient", "runServer", "runGameTestServer"]
            }.configureEach {
                dependsOn("${path}:devJar")
            }
        }
    }
}