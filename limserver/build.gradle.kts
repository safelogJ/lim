plugins {
    id("java-library")
    alias(libs.plugins.shadow)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.getByName("shadowJar", {
    val shadowTask = this as com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
    shadowTask.archiveBaseName.set("limcontroller")
    shadowTask.archiveClassifier.set("fat")
    shadowTask.archiveVersion.set("chr")
    shadowTask.manifest {
        attributes("Main-Class" to "com.safelogj.limserver.LimController")
    }
})

// Создаем конфигурацию для запуска ProGuard без плагинов
val proguardConfig: Configuration = configurations.create("proguardConfig")

dependencies {
    implementation(libs.logback)
    implementation(libs.slf4j)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.sqlite)
    implementation(libs.hikari)
    proguardConfig(libs.proguardbase)
}

// Сама задача сжатия (Tree Shaking)
tasks.register<JavaExec>("minifyJar") {
    dependsOn("shadowJar")

    // Добавляем группу и описание для красоты в Gradle-панели
    group = "build"
    description = "Вырезает неиспользуемый код с помощью ProGuard и сжимает JAR."

    classpath = proguardConfig
    mainClass.set("proguard.ProGuard")

    // ... все остальные твои args ...
    args("-injars", "build/libs/limcontroller-chr-fat.jar")
    args("-outjars", "build/libs/limcontroller-chr.jar")

    val javaHome = System.getProperty("java.home")
    args("-libraryjars", "$javaHome/jmods")

    args("-include", "proguard-rules.pro")
}