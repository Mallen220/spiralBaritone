pluginManagement {
    repositories {
        mavenLocal()
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        maven {
            name = "Sonatype Snapshots"
            url = uri("https://oss.sonatype.org/content/repositories/snapshots")
        }
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "net.fabricmc.loom", "fabric-loom" -> {
                    useModule("net.fabricmc:fabric-loom:${requested.version}")
                }
            }
        }
    }
}

rootProject.name = "addon-template"
