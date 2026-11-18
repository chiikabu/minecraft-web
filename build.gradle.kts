// minecraft-web by chiikabu (https://github.com/chiikabu)

plugins {
	id("java")
}

allprojects {
	apply(plugin = "eclipse")

	repositories {
		mavenCentral()
	}

	if (name != "game263") {
		plugins.withId("java") {
			java {
				toolchain {
					languageVersion = JavaLanguageVersion.of(25)
				}
			}
		}

		tasks.withType<JavaCompile>().configureEach {
			sourceCompatibility = JavaVersion.VERSION_17.toString()
			targetCompatibility = JavaVersion.VERSION_17.toString()
			options.release.set(17)
		}
	}
}
