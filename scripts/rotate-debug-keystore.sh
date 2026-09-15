#!/usr/bin/env sh
# Ротація ключа підпису debug-збірки (секрет DEBUG_KEYSTORE_BASE64 у GitHub Actions).
#
# Що робить:
#   1. Генерує нове сховище PKCS12 тими самими параметрами, що й docs/BUILD.md,
#      у тимчасовій теці — не в репозиторії, щоб ключ не потрапив у коміт.
#   2. Друкує відбиток SHA256 сертифіката. За ним потім звіряється крок CI
#      «Restore the debug signing key»: якщо відбитки збігаються, секрет оновлено.
#   3. Якщо є `gh` і він залогінений — одразу записує секрет через
#      `gh secret set` (base64 іде через stdin, без буфера обміну, який
#      обрізає довгий рядок). Інакше лишає base64 у файлі, щоб вставити руками.
#
# Наслідок ротації: усі телефони з debug-збіркою, підписаною старим ключем,
# НЕ приймуть нову збірку як оновлення (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
# Застосунок доведеться один раз видалити й поставити заново. Старий секрет
# з GitHub прочитати неможливо, тож після запису він втрачений остаточно.
#
# Використання:
#   scripts/rotate-debug-keystore.sh            # згенерувати й записати секрет
#   scripts/rotate-debug-keystore.sh --keep app/debug.keystore
#       # додатково лишити копію сховища за вказаним шляхом (app/debug.keystore
#       # у .gitignore) — для локальної збірки тим самим ключем.
#   scripts/rotate-debug-keystore.sh --no-upload
#       # не чіпати GitHub, лише згенерувати й лишити base64 у файлі.

set -eu

KEEP_PATH=""
UPLOAD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP_PATH="$2"; shift 2 ;;
    --no-upload) UPLOAD=0; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "Невідомий аргумент: $1" >&2; exit 2 ;;
  esac
done

command -v keytool >/dev/null 2>&1 || {
  echo "Потрібен keytool (входить до JDK). Встановіть JDK 17+ і повторіть." >&2
  exit 1
}

WORK="$(mktemp -d)"
KS="$WORK/debug.keystore"
B64="$WORK/debug.keystore.b64"

echo "Генерую нове сховище…"
keytool -genkeypair -keystore "$KS" -storetype PKCS12 \
  -alias androiddebugkey -storepass android -keypass android \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Kia Soul EV Plus V2 Debug, OU=Debug, O=KiaSoulPlusV2, C=UA" >/dev/null 2>&1

# `base64 -w0` є лише в GNU coreutils; tr робить те саме й на macOS.
base64 < "$KS" | tr -d '\n' > "$B64"

echo
echo "Відбиток НОВОГО сертифіката (не таємниця — саме його друкує крок CI):"
keytool -list -v -keystore "$KS" -storepass android 2>/dev/null | grep -i 'SHA256:' | head -1
echo "Розмір: $(wc -c < "$KS" | tr -d ' ') байт, $(wc -c < "$B64" | tr -d ' ') символів base64 (CI очікує 3664)."
echo

if [ -n "$KEEP_PATH" ]; then
  cp "$KS" "$KEEP_PATH"
  echo "Копію сховища лишено в $KEEP_PATH. Переконайтеся, що шлях у .gitignore."
fi

if [ "$UPLOAD" -eq 1 ] && command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  echo "Записую секрет DEBUG_KEYSTORE_BASE64 через gh…"
  gh secret set DEBUG_KEYSTORE_BASE64 < "$B64"
  rm -rf "$WORK"
  echo
  echo "Готово. Далі:"
  echo "  1. Запустіть Build APK (Actions → Build APK → Run workflow) або зробіть push."
  echo "  2. У логу кроку «Restore the debug signing key» звірте SHA256 з відбитком вище."
  echo "  3. На телефонах видаліть стару debug-збірку й поставте нову."
else
  echo "gh недоступний або не залогінений — секрет треба вставити руками:"
  echo "  1. Відкрийте Settings → Secrets and variables → Actions → DEBUG_KEYSTORE_BASE64 → Update."
  echo "  2. Вставте ВЕСЬ вміст файлу (один рядок, без переносів):"
  echo "       $B64"
  echo "  3. Після збереження видаліть теку $WORK."
  echo "  4. Запустіть Build APK і звірте SHA256 у логу з відбитком вище."
  echo "  5. На телефонах видаліть стару debug-збірку й поставте нову."
fi
