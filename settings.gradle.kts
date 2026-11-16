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
include(":phone")  // 스마트폰 앱 (C1·C2, 실험 관리)
include(":wear")   // 갤럭시 워치(Wear OS) 앱
include(":shared")  // 공통: 실험 설계·측정 규칙·로그·오디오·통신·시행 UI
