# Özet (Android)

Kotlin + Jetpack Compose. Yapay zeka / API anahtarı yok; özetleme cihazda çalışır.

## Açmak için
1. Android Studio Koala (2024.1.1) veya üstü ile klasörü aç (File > Open).
2. Gradle senkronizasyonu bitince telefonu bağla ve Run.

## Sürümler
Gradle 8.7, Android Gradle Plugin 8.5.2, Kotlin 1.9.24, Compose BOM 2024.06.00, JDK 17.
minSdk 26, targetSdk 34.

## Reklamlar
Şu an Google'ın TEST reklam kimlikleri var (app/src/main/res/values/strings.xml).
Yayınlamadan önce AdMob'dan kendi uygulama ve banner kimliklerini alıp değiştir.
Kendi gerçek reklamlarına test cihazında tıklama.

## Dosya yapısı
- engine/Extractor.kt  : PDF, DOCX, TXT okuma
- engine/Summarizer.kt : özetleme algoritması
- MainViewModel.kt     : 10 sn bekleme + arka planda özetleme
- ui/App.kt            : ekranlar
