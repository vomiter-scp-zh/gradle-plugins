package com.vomiter

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources

class ForgeBasePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        // Extension（提供少量開關）
        def ext = project.extensions.create("vomiterMod", VomiterModExtension, project)

        // 預設值：對齊你目前 template
        ext.javaLanguageVersion = 17
        ext.enableUtf8 = true

        ext.enableCommonRepositories = true

        ext.enableMixinTemplate = true
        ext.mixinTemplateFileName = "mixins.template.json"
        ext.generatedResourcesRelPath = "generated/resources"

        ext.enableProcessResourcesExpand = true
        ext.modsTomlPath = "META-INF/mods.toml"
        ext.expandTargets = ["META-INF/mods.toml", "pack.mcmeta"]
        ext.excludes = [
                "mixins.template.json",
                "**/.cache/**",
                "**/*.pdn",
                "logo.pdn",
                "**/.DS_Store",
                "**/Thumbs.db"
        ]

        ext.enableSharedManifest = true
        ext.enableJarTimestamp = true
        ext.enableDevJar = true
        ext.devJarClassifier = "dev"

        ext.enableJarJarPlumbing = true

        ext.enableMixinExtras = true
        ext.mixinExtrasVersion = "0.4.1"

        if (ext.enableCommonRepositories) configureRepositoriesForAllProjects(project, ext)

        // 在專案完成評估後再做配置（避免使用者稍後覆寫 extension）
        project.afterEvaluate {
            configureJava(project, ext)
            if (ext.enableMixinExtras) configureMixinExtrasDeps(project, ext)

            if (ext.enableMixinTemplate) configureMixinTemplateGeneration(project, ext)
            if (ext.enableProcessResourcesExpand) configureProcessResources(project, ext)
            if (ext.enableSharedManifest) configureJarManifests(project, ext)
            if (ext.enableDevJar) configureDevJar(project, ext)

            if (ext.enableJarJarPlumbing) configureJarJarPlumbing(project, ext)
        }
    }

    private static void configureRepositoriesForAllProjects(Project project, VomiterModExtension ext) {
        // 早加 + 全域覆蓋，避免只吃到 forgeGradle bundled repo
        project.allprojects { p ->
            p.repositories { repos ->
                repos.maven { m ->
                    m.url = p.uri("https://cursemaven.com")
                    m.content { c -> c.includeGroup("curse.maven") }
                }
                repos.maven { m -> m.url = p.uri("https://maven.blamejared.com") }
            }
        }
    }

    private static void configureJava(Project project, VomiterModExtension ext) {
        // toolchain
        def javaExt = project.extensions.findByType(JavaPluginExtension)
        if (javaExt != null) {
            javaExt.toolchain.languageVersion = org.gradle.jvm.toolchain.JavaLanguageVersion.of(ext.javaLanguageVersion)
        }

        // encoding
        if (ext.enableUtf8) {
            project.tasks.withType(JavaCompile).configureEach {
                options.encoding = "UTF-8"
            }
        }
    }

    private static void configureMixinExtrasDeps(Project project, VomiterModExtension ext) {
        // 只做「共用依賴」；minecraft/forge 依賴仍建議留在各專案 dependencies 裡（因為版本差異較大）
        project.dependencies { deps ->
            def v = ext.mixinExtrasVersion
            deps.annotationProcessor("io.github.llamalad7:mixinextras-common:${v}")
            deps.compileOnly("io.github.llamalad7:mixinextras-common:${v}")
            deps.implementation("io.github.llamalad7:mixinextras-forge:${v}")
            deps.add("jarJar", "io.github.llamalad7:mixinextras-forge:[${v},)")
        }
    }

    private static void configureMixinTemplateGeneration(Project project, VomiterModExtension ext) {
        // 需要 modId 才能產生 ${modId}.mixins.json
        def modId = ext.modId?.trim()
        if (!modId) {
            // 不硬 fail，避免某些非 mod 子專案也套 plugin；但你會少了 mixin config 生成
            project.logger.lifecycle("[vomiterMod] modId is empty, skip mixin template generation.")
            return
        }

        def mixinConfigName = "${modId}.mixins.json"
        ext.computedMixinConfigName = mixinConfigName

        Directory genDirProvider = project.layout.buildDirectory.dir(ext.generatedResourcesRelPath).get()
        def genDir = genDirProvider.asFile
        ext.computedGeneratedResourcesDir = genDir

        // task: generateMixinConfig
        def t = project.tasks.findByName("generateMixinConfig")
        if (t == null) {
            t = project.tasks.register("generateMixinConfig", Copy) { Copy c ->
                c.from(project.file("src/main/resources"))
                c.include(ext.mixinTemplateFileName)
                c.into(genDir)
                c.rename { mixinConfigName }
            }.get()
        }

        // sourceSets.main.resources += generatedResourcesDir
        def sourceSets = project.extensions.findByType(SourceSetContainer)
        if (sourceSets != null) {
            sourceSets.named("main") { main ->
                main.resources.srcDir(genDir)
            }
        } else {
            // 沒有 java plugin 的情況，至少不 crash
            project.logger.lifecycle("[vomiterMod] SourceSets not found, skip resources srcDir injection.")
        }

        // compileJava / processResources 依賴 generateMixinConfig（兩者擇一或都做都可）
        project.tasks.matching { it.name == "compileJava" }.configureEach {
            dependsOn("generateMixinConfig")
        }
        project.tasks.withType(ProcessResources).configureEach {
            dependsOn("generateMixinConfig")
        }
    }

    private static Map<String, Object> computeModProps(Project project, VomiterModExtension ext) {
        // 你現在的模式是 gradle.properties 供一堆變數，這裡直接從 project properties 取
        // 同時允許在 extension 內覆寫/補充
        Map<String, Object> base = new LinkedHashMap<>()

        // 如果使用者有傳 ext.modProps，就先放
        if (ext.modProps != null) base.putAll(ext.modProps)

        // 常用 key（存在才放）
        def keys = [
                "minecraft_version",
                "minecraft_version_range",
                "forge_version",
                "forge_version_range",
                "loader_version_range",
                "mod_id",
                "mod_name",
                "mod_license",
                "mod_version",
                "mod_authors",
                "mod_description"
        ]

        keys.each { k ->
            if (project.hasProperty(k)) base.put(k, project.property(k))
        }

        // mod_id 以 ext.modId 為準（若有）
        if (ext.modId?.trim()) base.put("mod_id", ext.modId.trim())

        // mod_version 若未提供，通常你也會用 project.version；不強制覆蓋
        if (!base.containsKey("mod_version") && project.version != null) {
            base.put("mod_version", project.version.toString())
        }

        return base
    }

    private static void configureProcessResources(Project project, VomiterModExtension ext) {
        def modProps = computeModProps(project, ext)

        project.tasks.withType(ProcessResources).configureEach { ProcessResources pr ->
            pr.duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
            pr.inputs.properties(modProps)

            pr.filesMatching(ext.expandTargets) {
                // 跟你原本一致：expand modProps + [project: project]
                expand(modProps + [project: project])
            }

            ext.excludes.each { ex ->
                pr.exclude(ex)
            }
        }
    }

    private static Map<String, Object> computeSharedManifestAttrs(Project project, VomiterModExtension ext) {
        def modProps = computeModProps(project, ext)
        def modId = (modProps.get("mod_id") ?: "").toString()
        def authors = (modProps.get("mod_authors") ?: "").toString()

        def mixinCfg = ext.computedMixinConfigName
        if (!mixinCfg && ext.modId?.trim()) mixinCfg = "${ext.modId.trim()}.mixins.json"

        Map<String, Object> attrs = new LinkedHashMap<>()
        attrs.put("Specification-Title", modId)
        attrs.put("Implementation-Title", project.name)
        attrs.put("Implementation-Version", project.jar.archiveVersion.get().toString())
        attrs.put("Implementation-Vendor", authors)

        if (ext.enableJarTimestamp) {
            attrs.put("Implementation-Timestamp", new Date().format("yyyy-MM-dd'T'HH:mm:ssZ"))
        }
        if (mixinCfg) {
            attrs.put("MixinConfigs", mixinCfg)
        }
        return attrs
    }

    private static void configureJarManifests(Project project, VomiterModExtension ext) {
        def attrs = computeSharedManifestAttrs(project, ext)

        project.tasks.withType(Jar).configureEach { Jar jar ->
            // 只針對主要 jar 任務（避免 devJar 後面又覆寫；devJar 會另外設）
            if (jar.name == "jar") {
                jar.manifest { it.attributes(attrs) }
                // 你原本：finalizedBy reobfJar（存在才掛）
                jar.doLast {
                    // no-op
                }
                if (project.tasks.findByName("reobfJar") != null) {
                    jar.finalizedBy("reobfJar")
                }
            }
        }
    }

    private static void configureDevJar(Project project, VomiterModExtension ext) {
        def attrs = computeSharedManifestAttrs(project, ext)

        // 若已存在 devJar（有些專案自己定義），就只做輕量補強
        def existing = project.tasks.findByName("devJar")
        if (existing == null) {
            project.tasks.register("devJar", Jar) { Jar jar ->
                jar.group = "build"
                jar.archiveClassifier.set(ext.devJarClassifier)
                jar.from(project.sourceSets.main.output)
                jar.manifest { it.attributes(attrs) }
                jar.dependsOn("classes")
            }
        } else {
            if (existing instanceof Jar) {
                existing.manifest { it.attributes(attrs) }
                existing.dependsOn("classes")
            }
        }
    }

    private static void configureJarJarPlumbing(Project project, VomiterModExtension ext) {
        // 這些 API 是 ForgeGradle + jarJar plugin 提供；不存在就跳過
        // 你原本的行為：
        // jarJar.enable()
        // reobf { create("jarJar") }
        // jarJar.finalizedBy reobfJarJar
        try {
            def jarJarTask = project.tasks.findByName("jarJar")
            if (jarJarTask != null) {
                // 讓 jarJar 任務結束後 reobfJarJar（如果存在）
                if (project.tasks.findByName("reobfJarJar") != null) {
                    jarJarTask.finalizedBy("reobfJarJar")
                }
            }

            // 這段需要 ForgeGradle 的 reobf extension；用動態呼叫避免 classpath 依賴
            def reobfExt = project.extensions.findByName("reobf")
            if (reobfExt != null) {
                // 等價於：reobf { create("jarJar") }
                reobfExt.create("jarJar")
            }

            // jarJar.enable()：對應 extension 名通常是 jarJar；同樣用動態方式
            def jarJarExt = project.extensions.findByName("jarJar")
            if (jarJarExt != null) {
                // 部分環境是方法 enable()，部分是 property enable；兩種都試
                if (jarJarExt.metaClass.respondsTo(jarJarExt, "enable")) {
                    jarJarExt.enable()
                } else if (jarJarExt.hasProperty("enabled")) {
                    jarJarExt.enabled = true
                }
            }
        } catch (Throwable t) {
            project.logger.lifecycle("[vomiterMod] jarJar/reobf plumbing skipped: ${t.class.simpleName}: ${t.message}")
        }
    }

    static class VomiterModExtension {
        final Project project

        // Required-ish
        String modId

        // Java
        int javaLanguageVersion = 17
        boolean enableUtf8 = true

        // Repos
        boolean enableCommonRepositories = true

        // Mixin template generation
        boolean enableMixinTemplate = true
        String mixinTemplateFileName = "mixins.template.json"
        String generatedResourcesRelPath = "generated/resources"

        // processResources
        boolean enableProcessResourcesExpand = true
        String modsTomlPath = "META-INF/mods.toml"
        List<String> expandTargets = ["META-INF/mods.toml", "pack.mcmeta"]
        List<String> excludes = []

        // Manifest / jars
        boolean enableSharedManifest = true
        boolean enableJarTimestamp = true
        boolean enableDevJar = true
        String devJarClassifier = "dev"

        // jarJar / reobf
        boolean enableJarJarPlumbing = true

        // mixinextras
        boolean enableMixinExtras = true
        String mixinExtrasVersion = "0.4.1"

        // extra modProps override
        Map<String, Object> modProps = [:]

        // computed (read-only)
        String computedMixinConfigName
        File computedGeneratedResourcesDir

        VomiterModExtension(Project project) {
            this.project = project
        }
    }
}