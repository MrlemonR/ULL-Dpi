# ULL-DPI

### [⬇ ULL-DPI.apk — son sürümü indir](https://github.com/MrlemonR/ULL-Dpi/releases/latest/download/ULL-DPI.apk)

Tüm sürümler: [Releases](https://github.com/MrlemonR/ULL-Dpi/releases). Her güncellemede yeni APK otomatik olarak
buraya gelir; yukarıdaki bağlantı hep en yenisini indirir.

Android için root gerektirmeyen DPI atlatıcı. [GoodbyeDPI-Turkey](https://github.com/cagritaskn/GoodbyeDPI-Turkey)'nin
yaptığını telefonda yapar: Discord gibi erişimi kısıtlanmış sitelere **internet hızını bozmadan** girmeni sağlar.
Arayüz [ULL-Bot](https://github.com/MrlemonR/ULL-Bot)'la aynı dilde: siyah-beyaz + biraz kırmızı, kare köşeler,
terminal yazı tipi, arkada matrix yağmuru.

```
█  █ █    █        █▀▀▄ █▀▀█ ▀█▀
█  █ █    █    ▀▀▀ █  █ █▀▀▀  █
▀▀▀▀ ▀▀▀▀ ▀▀▀▀     ▀▀▀  ▀    ▀▀▀
```

## Kurulum

1. Yukarıdaki **⬇ ULL-DPI.apk** bağlantısından indir.
2. Telefonda APK'yı aç; "bilinmeyen kaynaklar" izni istenirse ver.
3. Uygulamayı aç → **[ BAŞLAT ]** → VPN izni penceresinde **Tamam**.
4. Discord açıksa tamamen kapat (son uygulamalardan kaydır) ve yeniden aç.

Android 10 ve üstü önerilir. Bildirim panelindeki hızlı ayarlara **ULL-DPI** kutucuğunu ekleyip tek dokunuşla
açıp kapatabilirsin.

## Nasıl çalışır

```
 uygulama ──DNS──▶ [ tünel: sadece 10.111.222.2 ] ──▶ Yandex 77.88.8.8:1253 / DoH
 uygulama ──CONNECT discord.com:443──▶ [ 127.0.0.1 proxy ] ──(parçalanmış ilk paket)──▶ sunucu
                                                          ◀──────── geri kalan her şey olduğu gibi ────────▶
```

Türkiye'deki engel iki katmanlı:

| Engel | GoodbyeDPI-Turkey | ULL-DPI |
| --- | --- | --- |
| DNS zehirleme | `--dns-addr 77.88.8.8 --dns-port 1253` | Tünel yalnızca DNS'i alır, Yandex :1253'e (ya da Cloudflare / Google / Quad9 DoH'a) sorar |
| SNI'ye bakan DPI | `-5`, `-9`: `-f 2 -e 2`, sahte paketler | İlk paketi bozar: **TLS kayıt bölme** + **TCP parçalama** |
| HTTP Host'a bakan DPI | `-r -s -m` | `Host: x` → `hoSt:X` |

- **Gerçek bir VPN değil.** Trafik hiçbir sunucuya taşınmaz. VPN tüneli yalnızca sahte DNS adresini kapsar; internet
  her zamanki gibi operatörden akar.
- Sistem HTTP proxy'si `127.0.0.1`'deki yerel proxy'ye çevrilir. Her HTTPS bağlantısında sadece **ilk paket**
  (ClientHello) değiştirilir, sonra iki yön birbirine bağlanır. Bu yüzden hız düşmez; ek gecikme bağlantı başına
  birkaç milisaniyedir.
- Root yok. GoodbyeDPI'ın sahte paket / TTL hileleri ham soket ister; onların yerine TLS kayıt bölme kullanılıyor
  (Türkiye'de en etkili yöntemlerden biri).

### Profiller

| Profil | Karşılığı | İçerik |
| --- | --- | --- |
| TURKEY (varsayılan) | `turkey_dnsredir.cmd` | Yandex :1253 · tlsrec · split 2+sni · host |
| ALTERNATİF 1 | `alternative_superonline` | Yandex :1253 · split 1+sni · host |
| ALTERNATİF 2 | — | Cloudflare DoH · tlsrec · host |
| AGRESİF | `alternative5` (`-9`) | her şey açık, 15 ms |
| SADECE DNS | — | parçalama yok |

Biri çalışmazsa sıradakini dene. Ayarlar → Yöntem'den her şeyi tek tek değiştirebilirsin; profil o zaman **ÖZEL** olur.
**Kapsam**'tan parçalamayı sadece listedeki sitelere (varsayılan: Discord alan adları) uygulatabilirsin.

## Sorun giderme

- **Discord hâlâ açılmıyor:** Discord'u tamamen kapatıp aç; o da olmazsa ALTERNATİF 1 / AGRESİF'i dene.
- **Özel DNS:** Ayarlar → Ağ → Özel DNS "Kapalı" ya da "Otomatik" olmalı; elle bir sunucu girilmişse DNS bizden geçmez
  (genelde sorun olmaz, o sunucu zaten zehirlenmemiştir).
- **Bazı uygulamalar çalışmıyor:** Sistem proxy'sine uymayan uygulamalarda (bazı oyunlar) sadece DNS düzeltmesi geçerli.
  Discord, Chrome, Firefox, WebView kullanan uygulamalar proxy'ye uyar.
- **Android 9 ve altı:** Sistem proxy'si VPN'den verilemiyor; Wi-Fi ayarlarında proxy'yi elle `127.0.0.1:18881` yap.
- **LOG** sekmesinde hangi bağlantının kaç parçaya bölündüğünü, DNS hatalarını görebilirsin.

## Geliştirme

```bash
./gradlew testDebugUnitTest   # çekirdek testleri (TLS ayrıştırma, parçalama, DNS, proxy)
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

```
app/src/main/java/com/ull/dpi/
  core/Desync.kt       ClientHello/SNI ayrıştırma, TLS kayıt bölme, TCP parçalama, Host hilesi
  core/LocalProxy.kt   127.0.0.1 HTTP CONNECT proxy'si
  core/Dns.kt          DNS mesajları, IPv4/UDP paketleri
  core/DnsTunnel.kt    tünelden DNS okuyup cevaplama
  core/Resolver.kt     Yandex :1253 (UDP) / DoH, önbellek
  DpiVpnService.kt     VpnService: tünel + sistem proxy'si
  MainActivity.kt      WebView + JS köprüsü (window.DPI)
  DpiTileService.kt    hızlı ayar kutucuğu
app/src/main/assets/web/   arayüz (ULL-Bot tasarımı): index.html, style.css, app.js, rain.js
```

Arayüzü tarayıcıda açarsan (`app/src/main/assets/web/index.html`) sahte bir köprüyle çalışır; tasarım denemek için
telefona yüklemek gerekmez.

APK GitHub Actions'ta derlenir. Ana dala her push `v0.1.<derleme no>` adıyla yeni bir sürüm yayınlar ve APK'yı
Releases'a ekler. Sabit imza için depo sırlarına `ULL_KEYSTORE_B64` (base64 `.jks`), `ULL_KEYSTORE_PASSWORD`, `ULL_KEY_ALIAS`,
`ULL_KEY_PASSWORD` ekleyebilirsin; yoksa debug anahtarıyla imzalanır (o durumda güncellerken eskisini kaldırman
gerekebilir).

Yazı tipi: JetBrains Mono (SIL OFL 1.1, `assets/web/fonts/OFL.txt`).
