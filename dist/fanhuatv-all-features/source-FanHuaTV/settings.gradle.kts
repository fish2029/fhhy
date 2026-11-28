pluginManagement {
    repositories {
        // 官方仓库在前：海外 runner 直连最稳；aliyun 镜像放后面当备用
        // （它在插件解析上出过 502，直接顶在前面的仓库会害整个解析失败）
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 官方仓库在前、国内镜像在后：runner 直连快，镜像只做国内依赖的补充
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        mavenLocal()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "FanHuaTV"
include(":app")
