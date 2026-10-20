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

## Hız göstergesi ve widget'lar

- **DURUM ekranı → // HIZ:** Speedtest by Ookla gibi ibreli gösterge, iki modda:
  - **CANLI KULLANIM:** telefonun o anki toplam indirme / yükleme hızı (bir şey indirmiyorsan 0'a yakın).
  - **SÜREKLİ ÖLÇ:** her 5 / 10 / 30 saniyede küçük bir gerçek ölçüm (ping + indirme + yükleme). Telefon boştayken de
    hattın hızını gösterir.
  - **HIZ TESTİ ▶:** tam test (ping → indirme → yükleme, `speed.cloudflare.com`).
- **Ana ekran widget'ları (2x2):** ana ekrana uzun bas → Widget'lar → ULL-DPI. Beş çeşit:

  | Widget | İçerik |
  | --- | --- |
  | ULL-DPI · Hız + düğme | ↓ indirme, ↑ yükleme, ping, BAŞLAT/DURDUR |
  | ULL-DPI · İndirme | büyük ↓ indirme, ping, BAŞLAT/DURDUR |
  | ULL-DPI · Yükleme | büyük ↑ yükleme, ping, BAŞLAT/DURDUR |
  | ULL-DPI · Hız | ↓ ve ↑, düğmesiz |
  | ULL-DPI · Metre | Ookla tarzı ibreli gösterge |

  Widget'lar da SÜREKLİ ÖLÇ gibi ölçer (aralık: AYAR → ÖLÇÜM). Ölçüm yalnızca ULL-DPI açıkken ya da uygulama
  ekrandayken, ekran açıkken yapılır.
- **Mobil veride ölçme** (AYAR → ÖLÇÜM, varsayılan açık): Wi-Fi yokken widget'lar ve SÜREKLİ ÖLÇ durur.
- **Veri kullanımı:** her ölçüm en fazla ~3.8 MB. 10 saniyede bir, ekran açık geçen her saat için en fazla ~1.3 GB eder;
  mobil veride 30 SN ya da KAPALI seçmek isteyebilirsin. Harcanan miktar AYAR → ÖLÇÜM'de görünür.
- Bildirimde de canlı hız görünür: `ULL::DPI aktif ↓ 12.4 ↑ 1.2 Mbps`.

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
**İstisna siteler** (bankalar, e-Devlet, ödeme siteleri hazır gelir) parçalanmaz, operatör DNS'iyle doğrudan geçer.
Parçalı bağlantıyı reddeden bir site görülürse kendiliğinden düz bağlanır ve **otomatik eklenenler** listesine girer.
Aynı yerdeki **Bu uygulamalarda kullanma** ile seçtiğin uygulamalar VPN'in tamamen dışında kalır (ne DNS ne
parçalama); banka / operatör uygulaması açılmıyorsa onu buraya ekle.

## Güncelleme

AYAR'ın en altındaki **GÜNCELLEME** bölümü GitHub Releases'a bakar; yeni sürüm varsa **İNDİR VE KUR** ile indirip
Android'in kurulum penceresini açar (ilk seferde "bu kaynaktan yüklemeye izin ver" istenir). Güncelleme aynı imzayla
mevcut uygulamanın üstüne kurulur: ayarlar, istisnalar ve widget'lar yerinde kalır.

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
Releases'a ekler. Sabit imza için depo sırlarına tek bir değer ekle: `ULL_KEYSTORE_B64` (anahtar dosyasının base64 hâli,
şifresi `ull-dpi`). Yoksa her sürüm farklı anahtarla imzalanır ve güncellerken eskisini kaldırman gerekir.

Yazı tipi: JetBrains Mono (SIL OFL 1.1, `assets/web/fonts/OFL.txt`).
