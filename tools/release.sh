#!/usr/bin/env bash
# Legt das Versions-Tag aus app/build.gradle.kts an (v<versionName>) und lädt es auf Wunsch hoch.
# Aufruf:  tools/release.sh           prüfen und Tag lokal anlegen
#          tools/release.sh --push    zusätzlich main und das Tag hochladen
# F-Droid baut aus diesem Tag (siehe docs/fdroid/*.yml: commit: v<versionName>).
set -euo pipefail
cd "$(dirname "$0")/.."

gradle=app/build.gradle.kts
name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$gradle" | head -1)
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$gradle" | head -1)
[ -n "$name" ] && [ -n "$code" ] || { echo "versionName/versionCode nicht gefunden in $gradle" >&2; exit 1; }
tag="v$name"

branch=$(git rev-parse --abbrev-ref HEAD)
[ "$branch" = "main" ] || { echo "Bitte auf main wechseln (aktuell: $branch)." >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Es gibt nicht eingecheckte Änderungen. Erst committen." >&2; exit 1; }
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && { echo "Tag $tag gibt es schon (Version in $gradle erhöhen?)." >&2; exit 1; }
for l in en-US de-DE; do
  [ -f "fastlane/metadata/android/$l/changelogs/$code.txt" ] || { echo "Änderungsliste fehlt: fastlane/metadata/android/$l/changelogs/$code.txt" >&2; exit 1; }
done

git tag -a "$tag" -m "RideVault $name"
echo "Tag $tag (versionCode $code) angelegt auf $(git rev-parse --short HEAD)."

if [ "${1:-}" = "--push" ]; then
  git push origin main
  git push origin "$tag"
  echo "main und $tag hochgeladen."
else
  echo "Hochladen mit: git push origin main && git push origin $tag   (oder tools/release.sh --push beim nächsten Mal)"
fi
