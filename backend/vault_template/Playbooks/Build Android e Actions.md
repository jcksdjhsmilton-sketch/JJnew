# Build Android com GitHub Actions
- Projeto Gradle Kotlin DSL; workflow roda `gradle assembleDebug` e envia o APK como artifact.
- Erro de SDK ou licença: o runner ubuntu já tem Android SDK, não instale outro.
- Erro de versão do AGP: confira a compatibilidade AGP, Gradle e JDK 17.
- APK debug instala direto; para release é preciso keystore.
