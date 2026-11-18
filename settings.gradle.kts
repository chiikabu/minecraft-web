pluginManagement {
	repositories {
		mavenLocal() // the patched TeaVM that build.py publishes into build/teavm/maven
		gradlePluginPortal()
		mavenCentral()
	}
}

rootProject.name = "minecraft-web"

include("game263")
include("target_teavm_263")
