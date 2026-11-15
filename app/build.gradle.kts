plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
dependencies {
 implementation("ai.pleos.playground:NaviHelper:3.0.5")
 implementation("ai.pleos.playground:SpeechToText:3.3.1")
 implementation("ai.pleos.playground:TextToSpeech:3.3.1")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
}
android {
 namespace = "com.hopon.car"
 compileSdk = 35
 useLibrary("android.test.runner", false)
 useLibrary("android.test.base", false)
 useLibrary("android.test.mock", false)
 defaultConfig { applicationId = "com.hopon.car"; minSdk = 28; targetSdk = 34; versionCode = 4; versionName = "0.3.1"; testInstrumentationRunner = "android.test.InstrumentationTestRunner" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
