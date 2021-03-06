import java.io.*
//Порядок include имеет значение для IDE при импорте проекта и sync gradle
include("service-jvm")
include("client-jvm")
include("lib-jvm")
project(":lib-jvm").projectDir = File("submodule-server/lib-jvm")
include("server-common")
project(":server-common").projectDir = File("submodule-server/server-common")
include("heroku-jvm")
project(":heroku-jvm").projectDir = File("submodule-server/heroku-jvm")
//include("client-common")
include("submodule-html")
include("desktop")
include("core")
include("android")

if(false)includeBuild("submodule-server") {
  if(false)dependencySubstitution {
    substitute(module("com.n8cats:core:1.0")).with(project(":"))
  }
}

pluginManagement({
  repositories {
    gradlePluginPortal()
    maven("https://kotlin.bintray.com/kotlinx")
  }
  resolutionStrategy {
    eachPlugin {
      when (requested.id.id) {
        "kotlinx-serialization"->{
          useModule("org.jetbrains.kotlinx:kotlinx-gradle-serialization-plugin:${requested.version}")
        }
      }
    }
  }
})