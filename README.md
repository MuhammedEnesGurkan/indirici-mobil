# indirici — Android

Tarayıcılı indirme yöneticisi: sayfadaki videoları bulur (HLS, DASH, MP4/WebM/MKV, ses, altyazı),
çok bağlantılı indirir, TS → MP4 birleştirir; dosya kütüphanesi ve dahili oynatıcı (Media3 + FFmpeg ses çözücü) içerir.

Bu kod daha önce `movie-track-` reposunun `mobil-android` dalındaydı (`mobile/indirici` + paylaşılan
`mobile/app` motoru); geçmişiyle birlikte buraya taşındı.

## Yapı

| Klasör | İçerik |
|---|---|
| `indirici/src/main/java/com/indirici` | Arayüz (Compose), widget, bildirimler |
| `indirici/src/main/java/com/movietrack/app` | İndirme motoru: `download`, `data`, `browser`, `util`, `player` |
| `ffmpeg-audio/java` | Media3 FFmpeg ses çözücü yardımcıları |
| `indirici/src/test`, `indirici/src/androidTest` | Birim ve emülatör (indirme, birleştirme, oynatıcı) testleri |

## APK

`main`'e her gönderimde GitHub Actions APK'yı derler ve **Releases → indirici-son** altına `indirici.apk` olarak koyar.
Telefonda dosyaya dokunup kurulur. Pull request'ler de derlenir ve testten geçer (APK, çalıştırmanın Artifacts kısmında).

## Yerel derleme

```bash
./gradlew assembleDebug            # indirici/build/outputs/apk/debug/indirici-debug.apk
./gradlew testDebugUnitTest        # birim testleri
```

DRM korumalı içerikler (Widevine vb.) indirilemez.
