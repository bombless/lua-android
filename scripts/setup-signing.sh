#!/usr/bin/env bash
# One-time setup of the release signing key.
#
# Generates a keystore and stores it as the GitHub Actions secrets the release workflow looks for:
#   ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD
# Without them, release builds are signed with a throw-away key (installable, but every release then has a
# different signature and Android refuses to update one over the other).
#
# Requires: keytool (any JDK), openssl and the GitHub CLI (gh) logged in with access to the repository.
#   usage: scripts/setup-signing.sh [owner/repo]
set -euo pipefail
repo=${1:-bombless/lua-android}
for tool in keytool openssl gh; do
  command -v "$tool" > /dev/null || { echo "setup-signing: '$tool' is required" >&2; exit 1; }
done
ks=${KEYSTORE_OUT:-lua-android-release.jks}
[ -e "$ks" ] && { echo "setup-signing: $ks already exists; refusing to overwrite a key" >&2; exit 1; }
alias=lua-android
pass=$(openssl rand -base64 33 | tr -d '/+=\n' | cut -c1-28)

keytool -genkeypair -keystore "$ks" -alias "$alias" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$pass" -keypass "$pass" -dname "CN=Lua Android, O=bombless"

base64 < "$ks" | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$repo"
printf '%s' "$pass"  | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$repo"
printf '%s' "$alias" | gh secret set ANDROID_KEY_ALIAS --repo "$repo"
printf '%s' "$pass"  | gh secret set ANDROID_KEY_PASSWORD --repo "$repo"

cat <<MSG

Secrets stored in $repo.
BACK UP NOW (GitHub cannot show secrets again, and losing the key means users must uninstall to update):
  keystore: $(pwd)/$ks
  password: $pass   (store password = key password, alias: $alias)
Never commit the keystore (.gitignore already excludes *.jks).
MSG
