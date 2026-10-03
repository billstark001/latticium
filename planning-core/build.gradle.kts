plugins { `java-library` }
dependencies {
    api(project(":dsl-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
}
