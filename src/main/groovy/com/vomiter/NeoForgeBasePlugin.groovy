package com.vomiter

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.plugins.ide.idea.model.IdeaModel

class NeoForgeBasePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def ext = project.extensions.create("vomiterNeoForge", NeoForgeModExtension, project)

        // defaults
        ext.javaLanguageVersion = 21
        ext.enableUtf8 = true
        ext.enableCommonRepositories = true

        ext.enableProcessResourcesExpand = true
        ext.expandTargets = ["META-INF/neoforge.mods.toml", "pack.mcmeta"]

        ext.excludes = [
                "**/.cache/**",
                "**/*.pdn",
                "logo.pdn",
                "**/.DS_Store",
                "**/Thumbs.db"
        ]

        // NeoForge template style metadata generation
        ext.enableGenerateModMetadata = true
        ext.templatesDir = "src/main/templates"
        ext.generatedModMetadataRelPath = "generated/sources/modMetadata"
        ext.enableIdeSyncHook = true

        // jars
        ext.enableDevJar = true
        ext.devJarClassifier = "dev"

        ext.enableSharedManifest = false
        ext.enableJarTimestamp = true
        ext.enableMixinConfigsManifest = false // NeoForge 自帶 mixin，你說不需要；預設關

        if (ext.enableCommonRepositories) {
            configureRepositoriesForAllProjects(project, ext)
        }

        ext.enableIdeaDownload = true

        project.afterEvaluate {
            configureJava(project, ext)

            if (ext.enableIdeaDownload) {
                configureIdeaDownloads(project)
            }

            if (ext.enableProcessResourcesExpand) {
                configureProcessResourcesExpand(project, ext)
            }

            if (ext.enableGenerateModMetadata) {
                configureGenerateModMetadata(project, ext)
            }

            if (ext.enableSharedManifest) {
                configureJarManifests(project, ext)
            }

            if (ext.enableDevJar) {
                configureDevJar(project, ext)
            }
        }
    }

    private static Map<String, Object> toSerializableProps(Project project, Map<String, Object> modProps) {
        Map<String, Object> out = new LinkedHashMap<>()

        // 先把 modProps 全部轉成 String（避免 Provider/File/其他不可序列化物）
        modProps.each { k, v ->
            out.put(k.toString(), v == null ? "" : v.toString())
        }

        // 額外提供常用 project 資訊（用你想要的命名即可）
        out.put("project_name", project.name)
        out.put("project_path", project.path)
        out.put("project_group", project.group?.toString() ?: "")
        out.put("project_version", project.version?.toString() ?: "")

        return out
    }

    private static void configureIdeaDownloads(Project project) {
        project.pluginManager.withPlugin("idea") {
            def idea = project.extensions.findByType(IdeaModel)
            if (idea?.module != null) {
                idea.module.downloadSources = true
                idea.module.downloadJavadoc = true
            }
        }
    }

    private static void configureRepositoriesForAllProjects(Project project, NeoForgeModExtension ext) {
        project.allprojects { p ->
            p.repositories { repos ->
                // CurseMaven
                repos.maven { m ->
                    m.url = p.uri("https://cursemaven.com")
                    m.content { c -> c.includeGroup("curse.maven") }
                }
                // BlameJared
                repos.maven { m -> m.url = p.uri("https://maven.blamejared.com") }
            }
        }
    }

    private static void configureJava(Project project, NeoForgeModExtension ext) {
        def javaExt = project.extensions.findByType(JavaPluginExtension)
        if (javaExt != null) {
            javaExt.toolchain.languageVersion = org.gradle.jvm.toolchain.JavaLanguageVersion.of(ext.javaLanguageVersion)
        }

        if (ext.enableUtf8) {
            project.tasks.withType(JavaCompile).configureEach {
                options.encoding = "UTF-8"
            }
        }
    }

    private static Map<String, Object> computeModProps(Project project, NeoForgeModExtension ext) {
        Map<String, Object> props = new LinkedHashMap<>()

        if (ext.modProps != null) props.putAll(ext.modProps)

        // 常用 key：存在才放
        def keys = [
                "minecraft_version",
                "minecraft_version_range",
                "neo_version",
                "loader_version_range",
                "mod_id",
                "mod_name",
                "mod_license",
                "mod_version",
                "mod_authors",
                "mod_description"
        ]
        keys.each { k ->
            if (project.hasProperty(k)) props.put(k, project.property(k))
        }

        // modId 以 extension 為準（若有）
        if (ext.modId?.trim()) props.put("mod_id", ext.modId.trim())

        // version fallback
        if (!props.containsKey("mod_version") && project.version != null) {
            props.put("mod_version", project.version.toString())
        }

        return props
    }

    private static void configureProcessResourcesExpand(Project project, NeoForgeModExtension ext) {
        def raw = computeModProps(project, ext)
        def props = toSerializableProps(project, raw)

        project.tasks.withType(ProcessResources).configureEach { ProcessResources pr ->
            pr.inputs.properties(props)

            if (ext.expandTargets != null && !ext.expandTargets.isEmpty()) {
                pr.filesMatching(ext.expandTargets) {
                    expand(props)
                }
            }

            (ext.excludes ?: []).each { ex -> pr.exclude(ex) }
        }
    }

    private static void configureGenerateModMetadata(Project project, NeoForgeModExtension ext) {
        def raw = computeModProps(project, ext)
        def props = toSerializableProps(project, raw)

        def templatesDir = project.file(ext.templatesDir ?: "src/main/templates")
        if (!templatesDir.exists()) {
            project.logger.lifecycle("[vomiterNeoForge] templatesDir not found: ${templatesDir}, skip generateModMetadata.")
            return
        }

        def outDirProvider = project.layout.buildDirectory.dir(ext.generatedModMetadataRelPath ?: "generated/sources/modMetadata")

        def tProvider = project.tasks.register("generateModMetadata", ProcessResources) { ProcessResources pr ->
            pr.inputs.properties(props)
            pr.from(templatesDir)
            pr.into(outDirProvider)
            pr.expand(props)
        }

        project.sourceSets.main.resources.srcDir(tProvider)

        if (ext.enableIdeSyncHook) {
            tryHookNeoForgeIdeSyncTask(project, tProvider)
        }
    }

    private static void tryHookNeoForgeIdeSyncTask(Project project, def taskProvider) {
        def neoForgeExt = project.extensions.findByName("neoForge")
        if (neoForgeExt == null) return

        try {
            // neoForge.ideSyncTask generateModMetadata
            if (neoForgeExt.metaClass.respondsTo(neoForgeExt, "ideSyncTask", Object)) {
                neoForgeExt.ideSyncTask(taskProvider)
                return
            }
            // 有些版本是 ideSyncTask(Task) / ideSyncTask(TaskProvider)
            if (neoForgeExt.metaClass.respondsTo(neoForgeExt, "ideSyncTask", org.gradle.api.Task)) {
                neoForgeExt.ideSyncTask(taskProvider.get())
                return
            }
        } catch (Throwable t) {
            project.logger.lifecycle("[vomiterNeoForge] neoForge.ideSyncTask hook skipped: ${t.class.simpleName}: ${t.message}")
        }
    }

    private static Map<String, Object> computeSharedManifestAttrs(Project project, NeoForgeModExtension ext) {
        def modProps = computeModProps(project, ext)
        def modId = (modProps.get("mod_id") ?: "").toString()
        def authors = (modProps.get("mod_authors") ?: "").toString()

        Map<String, Object> attrs = new LinkedHashMap<>()
        attrs.put("Specification-Title", modId)
        attrs.put("Implementation-Title", project.name)
        attrs.put("Implementation-Version", project.jar.archiveVersion.get().toString())
        if (authors) attrs.put("Implementation-Vendor", authors)

        if (ext.enableJarTimestamp) {
            attrs.put("Implementation-Timestamp", new Date().format("yyyy-MM-dd'T'HH:mm:ssZ"))
        }

        if (ext.enableMixinConfigsManifest && ext.mixinConfigs != null && !ext.mixinConfigs.isEmpty()) {
            attrs.put("MixinConfigs", ext.mixinConfigs.join(","))
        }

        return attrs
    }

    private static void configureJarManifests(Project project, NeoForgeModExtension ext) {
        def attrs = computeSharedManifestAttrs(project, ext)
        project.tasks.withType(Jar).configureEach { Jar jar ->
            if (jar.name == "jar") {
                jar.manifest { it.attributes(attrs) }
            }
        }
    }

    private static void configureDevJar(Project project, NeoForgeModExtension ext) {
        def attrs = ext.enableSharedManifest ? computeSharedManifestAttrs(project, ext) : [:]

        def existing = project.tasks.findByName("devJar")
        if (existing == null) {
            project.tasks.register("devJar", Jar) { Jar jar ->
                jar.group = "build"
                jar.archiveClassifier.set(ext.devJarClassifier ?: "dev")
                jar.from(project.sourceSets.main.output)
                if (!attrs.isEmpty()) jar.manifest { it.attributes(attrs) }
                jar.dependsOn("classes")
            }
        } else if (existing instanceof Jar) {
            if (!attrs.isEmpty()) existing.manifest { it.attributes(attrs) }
            existing.dependsOn("classes")
        }
    }

    static class NeoForgeModExtension {
        final Project project

        // Required-ish
        String modId

        // Java
        int javaLanguageVersion = 21
        boolean enableUtf8 = true

        // Repos
        boolean enableCommonRepositories = true

        // processResources expand
        boolean enableProcessResourcesExpand = true
        List<String> expandTargets = ["META-INF/neoforge.mods.toml", "pack.mcmeta"]
        List<String> excludes = []

        // metadata generation (NeoForge template style)
        boolean enableGenerateModMetadata = true
        String templatesDir = "src/main/templates"
        String generatedModMetadataRelPath = "generated/sources/modMetadata"
        boolean enableIdeSyncHook = true

        // jars
        boolean enableDevJar = true
        String devJarClassifier = "dev"

        boolean enableSharedManifest = false
        boolean enableJarTimestamp = true

        // keep off by default (你說不需要)
        boolean enableMixinConfigsManifest = false
        List<String> mixinConfigs = []

        // extra modProps override
        Map<String, Object> modProps = [:]
        boolean enableIdeaDownload = true


        NeoForgeModExtension(Project project) { this.project = project }
    }
}