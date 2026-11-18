// minecraft-web by chiikabu (https://github.com/chiikabu)

import groovy.json.JsonSlurper

plugins {
	id("java")
}

@Suppress("UNCHECKED_CAST")
val portConfig = JsonSlurper().parse(rootProject.file("port_version.json")) as Map<String, Any?>
fun portString(name: String) = portConfig[name]?.toString()
	?: throw GradleException("port_version.json is missing $name")
val minecraftVersion = portString("version")
val clientJar = rootProject.file(portString("client_jar"))
val assetIndexId = portString("asset_index_id")
val javaRelease = portString("java_release").toInt()
val sourceClassMajor = portString("source_class_major").toInt()
val targetClassMajor = portString("target_class_major").toInt()

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(javaRelease)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(javaRelease)
	options.encoding = "UTF-8"
}

val reconstructedJar = providers.gradleProperty("game263.reconstructedJar").orNull?.let(rootProject::file)
val flippedClasses = layout.buildDirectory.dir(if (reconstructedJar == null) "flipped-classes" else "reconstructed-flipped-classes")

val defaultMethodTool: Configuration by configurations.creating
dependencies {
	defaultMethodTool("org.ow2.asm:asm:9.10.1")
	defaultMethodTool("org.ow2.asm:asm-tree:9.10.1")
}
val compileDefaultMethodTool = tasks.register<JavaCompile>("compileDefaultMethodTool") {
	javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(javaRelease)) })
	source(rootProject.file("scripts/fabric_bake/BakeDefaultMethods.java"))
	classpath = defaultMethodTool
	destinationDirectory.set(layout.buildDirectory.dir("default-method-tool/classes"))
}
val defaultMethodLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(javaRelease)) }

val flipClassVersions = tasks.register("flipClassVersions") {
	if (reconstructedJar == null) dependsOn(tasks.named("compileJava"))
	val outDir = flippedClasses
	if (reconstructedJar == null) inputs.dir(layout.buildDirectory.dir("classes/java/main"))
	else inputs.file(reconstructedJar)
	inputs.property("sourceClassMajor", sourceClassMajor)
	inputs.property("targetClassMajor", targetClassMajor)
	inputs.files(compileDefaultMethodTool)
	val toolClasspath = files(compileDefaultMethodTool) + defaultMethodTool
	outputs.dir(outDir)
	doLast {
		val src = layout.buildDirectory.dir("classes/java/main").get().asFile
		val dst = outDir.get().asFile
		dst.deleteRecursively()
		val sourceTree = if (reconstructedJar == null) fileTree(src)
			else zipTree(reconstructedJar).matching { include("**/*.class") }
		sourceTree.visit {
			if (!isDirectory) {
				val f = file
				val rel = relativePath.pathString
				val out = dst.resolve(rel)
				out.parentFile.mkdirs()
				val bytes = f.readBytes()
				if (f.extension == "class" && bytes.size > 8) {
					val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
					if (major == sourceClassMajor) {
						bytes[6] = (targetClassMajor ushr 8).toByte()
						bytes[7] = targetClassMajor.toByte()
					} else if (major > targetClassMajor) {
						throw GradleException("unexpected class major $major in $rel; expected $sourceClassMajor")
					}
				}
				out.writeBytes(bytes)
			}
		}
		val bridged = providers.javaexec {
			executable = defaultMethodLauncher.get().executablePath.asFile.absolutePath
			classpath = toolClasspath
			mainClass.set("BakeDefaultMethods")
			args(dst.absolutePath)
		}
		logger.lifecycle(bridged.standardOutput.asText.get().lineSequence().filter { it.startsWith("DEFAULT_METHODS") }.joinToString("\n"))
		bridged.result.get().assertNormalExitValue()
	}
}

tasks.register<Jar>("flippedJar") {
	dependsOn(flipClassVersions)
	from(flippedClasses) {
		exclude("net/minecraft/server/gui/MinecraftServerGui*")
		exclude("net/minecraft/server/network/EventLoopGroupHolder*")
		exclude("net/minecraft/server/network/PlayerSafetyServiceTextFilter*")
		exclude("net/minecraft/server/jsonrpc/security/AuthenticationHandler*")
		exclude("com/mojang/renderpearl/backend/opengl/GlBackend*")
		exclude("net/minecraft/client/PreferredGraphicsApi.class")
		exclude("net/minecraft/client/PreferredGraphicsApi$*.class")
		exclude("com/mojang/renderpearl/api/pipeline/ShaderSource.class")
		exclude("com/mojang/renderpearl/api/pipeline/ShaderSource$*.class")
		exclude("com/mojang/renderpearl/frontend/shaders/GlslCompiler.class")
		exclude("com/mojang/renderpearl/frontend/shaders/GlslCompiler$*.class")
		exclude("com/mojang/renderpearl/frontend/shaders/SPIRVModule.class")
		exclude("com/mojang/renderpearl/frontend/shaders/SPIRVModule$*.class")
		exclude("com/mojang/renderpearl/frontend/shaders/SpvUtil.class")
		exclude("com/mojang/renderpearl/frontend/shaders/SpvUtil$*.class")
		exclude("com/mojang/renderpearl/backend/opengl/GlPipelineRecompiler.class")
		exclude("com/mojang/renderpearl/backend/opengl/GlPipelineRecompiler$*.class")
		exclude("com/mojang/renderpearl/backend/opengl/GlDebug.class")
		exclude("com/mojang/renderpearl/backend/opengl/GlDebug$*.class")
		exclude("com/mojang/renderpearl/backend/opengl/GlApi.class")
		exclude("com/mojang/renderpearl/backend/opengl/GlApi$*.class")
		exclude("com/mojang/renderpearl/backend/opengl/GlCompat.class")
		exclude("com/mojang/renderpearl/backend/opengl/GlCompat$*.class")
		exclude("com/mojang/blaze3d/platform/NativeImage.class")
		exclude("com/mojang/blaze3d/platform/NativeImage$*.class")
		exclude("net/minecraft/server/packs/VanillaPackResources.class")
		exclude("net/minecraft/server/packs/VanillaPackResources$*.class")
		exclude("net/minecraft/server/packs/VanillaPackResourcesBuilder.class")
		exclude("net/minecraft/server/packs/VanillaPackResourcesBuilder$*.class")
		exclude("net/minecraft/client/gui/font/providers/TrueTypeGlyphProviderDefinition*")
		exclude("net/minecraft/client/gui/font/providers/FreeTypeUtil.class")
		exclude("com/mojang/blaze3d/platform/MacosUtil.class")
		exclude("com/mojang/blaze3d/platform/MacosUtil$*.class")
		exclude("com/mojang/blaze3d/audio/Library.class")
		exclude("com/mojang/blaze3d/audio/Library$*.class")
		exclude("com/mojang/blaze3d/audio/Channel.class")
		exclude("com/mojang/blaze3d/audio/Listener.class")
		exclude("com/mojang/blaze3d/audio/SoundBuffer.class")
		exclude("com/mojang/blaze3d/audio/DeviceList.class")
		exclude("com/mojang/blaze3d/audio/OpenAlUtil.class")
		exclude("com/mojang/blaze3d/audio/CallbackDeviceTracker.class")
		exclude("com/mojang/blaze3d/audio/CallbackDeviceTracker$*.class")
		exclude("com/mojang/blaze3d/platform/MessageBox.class")
		exclude("net/minecraft/client/sounds/AudioStream.class")
		exclude("net/minecraft/client/sounds/SoundBufferLibrary.class")
		exclude("net/minecraft/client/sounds/SoundBufferLibrary$*.class")
		exclude("net/minecraft/client/sounds/SoundEngineExecutor.class")
		exclude("net/minecraft/client/sounds/JOrbisAudioStream*.class")
		exclude("net/minecraft/client/sounds/LoopingAudioStream*.class")
		exclude("net/minecraft/client/sounds/FloatSampleSource.class")
		exclude("net/minecraft/client/sounds/ChunkedSampleByteBuf.class")
		exclude("net/minecraft/client/OptionsStorage.class")
		exclude("net/minecraft/client/renderer/LightmapRenderStateExtractor.class")
		exclude("net/minecraft/client/resources/SplashManager.class")
		exclude("net/minecraft/client/PeriodicNotificationManager.class")
		exclude("net/minecraft/client/PeriodicNotificationManager$*.class")
		exclude("net/minecraft/client/telemetry/TelemetryLogManager.class")
		exclude("com/mojang/renderpearl/backend/vulkan/VulkanBackend.class")
		exclude("com/mojang/renderpearl/backend/vulkan/VulkanBackend$*.class")
		exclude("com/mojang/renderpearl/backend/vulkan/VulkanFeatureSets.class")
		exclude("com/mojang/renderpearl/backend/vulkan/VulkanFeatureSets$*.class")
		exclude("net/minecraft/server/packs/DownloadQueue.class")
		exclude("net/minecraft/server/packs/DownloadQueue$*.class")
		exclude("net/minecraft/server/packs/FilePackResources.class")
		exclude("net/minecraft/server/packs/FilePackResources$*.class")
		exclude("net/minecraft/client/renderer/PlayerSkinRenderCache.class")
		exclude("net/minecraft/client/renderer/PlayerSkinRenderCache$*.class")
		exclude("net/minecraft/client/renderer/GameRenderer.class")
		exclude("net/minecraft/client/renderer/GameRenderer$*.class")
		exclude("net/minecraft/client/renderer/LevelRenderer.class")
		exclude("net/minecraft/client/renderer/LevelRenderer$*.class")
		exclude("net/minecraft/client/gui/screens/TitleScreen.class")
		exclude("net/minecraft/client/gui/screens/TitleScreen$*.class")
		exclude("com/mojang/renderpearl/backend/opengl/GlCommandEncoder.class")
		exclude("com/mojang/renderpearl/backend/opengl/GlCommandEncoder$*.class")
		exclude("net/minecraft/client/renderer/chunk/ChunkSectionsToRender.class")
		exclude("net/minecraft/client/renderer/chunk/ChunkSectionsToRender$*.class")
		exclude("net/minecraft/client/renderer/chunk/SectionCompiler.class")
		exclude("net/minecraft/client/renderer/chunk/SectionCompiler$*.class")
		exclude("com/mojang/blaze3d/vertex/BufferBuilder.class")
		exclude("com/mojang/blaze3d/vertex/BufferBuilder$*.class")
		exclude("com/mojang/renderpearl/backend/opengl/VertexArray.class")
		exclude("com/mojang/renderpearl/backend/opengl/VertexArray$*.class")
		exclude("net/minecraft/client/multiplayer/PlayerInfo.class")
		exclude("net/minecraft/client/multiplayer/PlayerInfo$*.class")
		exclude("net/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen.class")
		exclude("net/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen$*.class")
		exclude("net/minecraft/client/gui/screens/multiplayer/ServerSelectionList.class")
		exclude("net/minecraft/client/gui/screens/multiplayer/ServerSelectionList$*.class")
		exclude("net/minecraft/client/gui/components/PlayerTabOverlay.class")
		exclude("net/minecraft/client/gui/components/PlayerTabOverlay$*.class")
		exclude("net/minecraft/world/level/NaturalSpawner.class")
		exclude("net/minecraft/world/level/NaturalSpawner$*.class")
		exclude("net/minecraft/client/renderer/SectionOcclusionGraph.class")
		exclude("net/minecraft/client/renderer/SectionOcclusionGraph$*.class")
		exclude("net/minecraft/client/renderer/extract/LevelExtractor.class")
		exclude("net/minecraft/client/renderer/extract/LevelExtractor$*.class")
		exclude("net/minecraft/server/network/PlayerChunkSender.class")
		exclude("net/minecraft/server/network/PlayerChunkSender$*.class")
		exclude("net/minecraft/world/level/BlockCollisions.class")
		exclude("net/minecraft/world/level/BlockCollisions$*.class")
		exclude("net/minecraft/server/level/ServerChunkCache.class")
		exclude("net/minecraft/server/level/ServerChunkCache$*.class")
		exclude("net/minecraft/server/level/DistanceManager.class")
		exclude("net/minecraft/server/level/DistanceManager$*.class")
		exclude("net/minecraft/server/level/ThrottlingChunkTaskDispatcher.class")
		exclude("net/minecraft/server/level/ThrottlingChunkTaskDispatcher$*.class")
		exclude("net/minecraft/server/packs/repository/FolderRepositorySource.class")
		exclude("net/minecraft/server/packs/repository/FolderRepositorySource$*.class")
		exclude("net/minecraft/world/level/validation/DirectoryValidator.class")
		exclude("net/minecraft/world/level/validation/DirectoryValidator$*.class")
		exclude("net/minecraft/world/level/validation/PathAllowList.class")
		exclude("net/minecraft/world/level/validation/PathAllowList$*.class")
		exclude("net/minecraft/util/NativeModuleLister.class")
		exclude("net/minecraft/util/NativeModuleLister$*.class")
		exclude("net/minecraft/client/multiplayer/resolver/ServerRedirectHandler.class")
		exclude("net/minecraft/client/multiplayer/resolver/ServerRedirectHandler$*.class")
		exclude("net/minecraft/util/profiling/jfr/JvmProfiler.class")
		exclude("net/minecraft/util/profiling/jfr/JvmProfiler$*.class")
		exclude("com/mojang/blaze3d/vertex/Web263VertexWrite.class")
		exclude("net/minecraft/util/datafix/DataFixers.class")
		exclude("net/minecraft/util/datafix/DataFixers$*.class")
		exclude("com/mojang/realmsclient/RealmsMainScreen.class")
		exclude("com/mojang/realmsclient/RealmsMainScreen$*.class")
		exclude("com/mojang/realmsclient/client/RealmsClient.class")
		exclude("com/mojang/realmsclient/client/RealmsClient$*.class")
		exclude("com/mojang/realmsclient/gui/RealmsDataFetcher.class")
		exclude("com/mojang/realmsclient/gui/RealmsDataFetcher$*.class")
		exclude("com/mojang/realmsclient/gui/screens/AddRealmPopupScreen.class")
		exclude("net/minecraft/client/quickplay/QuickPlay.class")
		exclude("net/minecraft/client/quickplay/QuickPlay$*.class")
		exclude("net/minecraft/client/gui/screens/social/SocialInteractionsScreen.class")
		exclude("net/minecraft/client/gui/screens/social/SocialInteractionsScreen$*.class")
		exclude("net/minecraft/client/multiplayer/chat/report/ReportingContext.class")
		exclude("net/minecraft/client/telemetry/ClientTelemetryManager.class")
		exclude("net/minecraft/client/resources/DefaultPlayerSkin.class")
		exclude("net/minecraft/client/resources/DefaultPlayerSkin$*.class")
		exclude("net/minecraft/client/gui/screens/options/OptionsSubScreen.class")
		exclude("net/minecraft/client/gui/screens/options/OptionsSubScreen$*.class")
		exclude("net/minecraft/util/Crypt.class")
		exclude("net/minecraft/util/Crypt$*.class")
		exclude("net/minecraft/util/Signer.class")
		exclude("net/minecraft/util/Signer$*.class")
		exclude("net/minecraft/client/multiplayer/WebLoginHooks.class")
		exclude("net/minecraft/client/multiplayer/WebLoginHooks$*.class")
		exclude("net/minecraft/world/level/chunk/storage/RegionFileVersion.class")
		exclude("net/minecraft/world/level/chunk/storage/RegionFileVersion$*.class")
		exclude("net/minecraft/network/CipherBase.class")
		exclude("net/minecraft/util/worldupdate/WorldUpgrader.class")
		exclude("net/minecraft/util/worldupdate/WorldUpgrader$*.class")
	}
	archiveBaseName.set(if (reconstructedJar == null) "game263-flipped" else "game263-reconstructed-flipped")
	entryCompression = ZipEntryCompression.STORED
}

