plugins {
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	kotlin("plugin.jpa") version "2.2.21"
}

group = "com.studentstorefront"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Spring Boot Starters
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-mail")

	// Swagger / OpenAPI
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

	// Kotlin support
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// Database
	implementation("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

	// JWT Authentication
    implementation("io.jsonwebtoken:jjwt-api:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.3")

	// HTTP Client for external APIs (Gemini, WhatsApp)
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	
	// Cloudinary (Image Uploads)
	implementation("com.cloudinary:cloudinary-http45:1.39.0")

	// Rate limiting
	implementation("com.bucket4j:bucket4j-core:8.10.1")
	implementation("com.github.ben-manes.caffeine:caffeine")

	// Development tools
	developmentOnly("org.springframework.boot:spring-boot-devtools")

	// Testing
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
	testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
	testImplementation("io.mockk:mockk:1.13.8")
}

// Timestamps are stored as LocalDateTime converted through the JVM zone
// (hibernate.jdbc.time_zone=UTC), so every JVM that touches the database must
// run in the same zone. Keep in sync with APP_TIMEZONE in scripts/stack.sh.
val appTimeZone = System.getenv("APP_TIMEZONE") ?: "Europe/Berlin"

tasks.withType<Test> {
	useJUnitPlatform()
	jvmArgs("-Duser.timezone=$appTimeZone")
	// Tests run without a JWT_SECRET; the test profile allows the per-process key.
	systemProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE") ?: "test")
}

tasks.bootRun {
	environment("SPRING_PROFILES_ACTIVE", System.getenv("SPRING_PROFILES_ACTIVE") ?: "local")
	jvmArgs("-Duser.timezone=$appTimeZone")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property", "-Xemit-jvm-type-annotations")
		jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}
