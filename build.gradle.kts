val modId: String by project
val modName: String by project
val modDescription: String by project
val modAuthor: String by project
val modVersion: String by project

val minecraftVersion: String by project
val fabricVersion: String by project
val fabricApiVersion: String by project
val mixinVersion: String by project

val modNameStripped = modName.replace(" ", "")

plugins {
	id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
	id("maven-publish")
}

repositories {
	mavenCentral()
}

base {
	archivesName.set(modNameStripped)
}

version = "$minecraftVersion+v$modVersion"
group = "dev.whisperlyric"

dependencies {
	minecraft("com.mojang:minecraft:$minecraftVersion")
	implementation("net.fabricmc:fabric-loader:$fabricVersion")
	implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
	compileOnly("net.fabricmc:sponge-mixin:$mixinVersion")

	// 编译期引用 voxy 类（RenderDistanceTracker 等），运行时由玩家自行安装 voxy。
	// voxy 的类名与方法名（add）不经 remap，直接以本地 jar 作 compileOnly 即可。
	compileOnly(files("libs/voxy-0.2.19-beta.jar"))
}

tasks.processResources {
	inputs.property("version", project.version)

	filesMatching("fabric.mod.json") {
		expand("version" to project.version)
	}
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
	options.release.set(25)
}

java {
	withSourcesJar()

	toolchain {
		languageVersion.set(JavaLanguageVersion.of(25))
	}
}

publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	repositories {
	}
}
