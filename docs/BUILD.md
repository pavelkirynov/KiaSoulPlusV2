# Збірка та підпис

APK збирається в GitHub Actions: локальна збірка неможлива, бо Google Maven
(`dl.google.com`) недоступний, а AndroidX у Maven Central немає.

- **Build APK** (`.github/workflows/build-apk.yml`) — на кожен push, плюс вручну.
  Готовий APK лежить в артефакті `kia-soul-ev-plus-v2-debug-apk`.
- **Unit tests** (`.github/workflows/tests.yml`) — тести окремо.

## Ключ підпису debug-збірки

Android відмовляється оновлювати застосунок, підписаний іншим ключем
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Gradle на чистому раннері щоразу створює
новий ключ, тому без постійного ключа кожну збірку доводилося ставити після
видалення попередньої.

Ключа немає в репозиторії навмисно: маючи його, стороння людина може зібрати APK,
який телефон прийме як оновлення саме цього застосунка.

### Як це влаштовано

| Де | Що |
|---|---|
| GitHub Secrets | `DEBUG_KEYSTORE_BASE64` — сховище PKCS12, закодоване base64 |
| CI | крок «Restore the debug signing key» розкодовує його в `app/debug.keystore` |
| Gradle | `signingConfigs.debug` бере цей файл, **якщо він існує** |
| `.gitignore` | `app/debug.keystore` — щоб ключ не потрапив у коміт випадково |

Пароль сховища і алias — стандартні (`android` / `androiddebugkey`), такі самі, як
у ключа, що його створює Android Studio. Секретом тут є сам файл, а не пароль.

Крок CI перевіряє розкодоване сховище справжнім `keytool`. Це не формальність:
неповний секрет лишається валідним base64, але дає обрізане сховище, і тоді збірка
падає аж на підписі з голим `EOFException`, з якого причину не видно. Якщо перевірка
не пройшла, файл видаляється і збірка йде за запасним варіантом, а в лог іде
попередження з найімовірнішою причиною.

### Якщо секрета немає

Збірка **не падає**: `signingConfigs.debug` лишається типовим, і Gradle підписує
власним згенерованим ключем. Так збірка з чистого клону працює в будь-кого. Ціна —
таку збірку доведеться ставити після видалення попередньої версії. У логу кроку
«Restore the debug signing key» написано, який із двох варіантів спрацював.

### Як створити ключ заново

Знадобиться, якщо секрет втрачено. Після цього застосунок доведеться один раз
видалити з телефона: ключ інший.

```sh
keytool -genkeypair -v -keystore app/debug.keystore -storetype PKCS12 \
  -alias androiddebugkey -storepass android -keypass android \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Kia Soul EV Plus V2 Debug, OU=Debug, O=KiaSoulPlusV2, C=UA"

base64 -w0 app/debug.keystore
```

Отриманий рядок покласти в Settings → Secrets and variables → Actions →
`DEBUG_KEYSTORE_BASE64`.

## Локальна перевірка без Android SDK

Android SDK у цьому середовищі недоступний, але чисті Kotlin-класи (усе, крім
`Interface/`, `MainActivity`, `App` і андроїдних сервісів) компілюються
`kotlin-compiler-embeddable` з Maven Central і проганяються через `JUnitCore`.
Саме тому логіка винесена з андроїдних класів у чисті об'єкти: `BroadcastDecoder`,
`MonitorLineParser`, `CalculationEngine`, `CarPaneModel`, `CarMediaModel` — усі
перевіряються без емулятора й без авто.
