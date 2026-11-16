pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HCI-TeamP"
include(":mobile") // 갤럭시 폰 앱
include(":wear")   // 갤럭시 워치(Wear OS) 앱
include(":shared")  // 폰·워치 공용 코드 (볼륨 감지, 오버레이)
