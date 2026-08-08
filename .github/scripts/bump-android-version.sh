#!/usr/bin/env bash
# Bump android/app/build.gradle.kts versionCode (+1) and appVersionName.
# - 1.2.3-rcN  -> 1.2.3-rc(N+1)
# - 1.2.3      -> 1.2.(3+1)
# - otherwise  -> append .<versionCode>
set -euo pipefail

FILE="${1:-android/app/build.gradle.kts}"

if [[ ! -f "$FILE" ]]; then
  echo "Missing $FILE" >&2
  exit 1
fi

CODE=$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$FILE" | head -1 | sed -E 's/.*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/')
NAME=$(grep -E '^[[:space:]]*val appVersionName[[:space:]]*=' "$FILE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')

if [[ -z "$CODE" || -z "$NAME" ]]; then
  echo "Could not parse version from $FILE" >&2
  exit 1
fi

NEW_CODE=$((CODE + 1))

if [[ "$NAME" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)-rc([0-9]+)$ ]]; then
  NEW_NAME="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}-rc$((BASH_REMATCH[4] + 1))"
elif [[ "$NAME" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  NEW_NAME="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))"
else
  NEW_NAME="${NAME}.${NEW_CODE}"
fi

# GNU sed (CI) accepts `sed -i`; BSD sed needs `sed -i ''`.
if sed --version >/dev/null 2>&1; then
  SED_INPLACE=(sed -i)
else
  SED_INPLACE=(sed -i '')
fi

"${SED_INPLACE[@]}" "s/val appVersionName = \"${NAME}\"/val appVersionName = \"${NEW_NAME}\"/" "$FILE"
"${SED_INPLACE[@]}" "s/versionCode = ${CODE}/versionCode = ${NEW_CODE}/" "$FILE"

echo "Bumped ${NAME} (${CODE}) -> ${NEW_NAME} (${NEW_CODE})"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "app_version=${NEW_NAME}"
    echo "version_code=${NEW_CODE}"
    echo "previous_version=${NAME}"
    echo "previous_code=${CODE}"
  } >> "$GITHUB_OUTPUT"
fi
