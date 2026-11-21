#!/usr/bin/env bash
# Uploads releases/<version>/ to Modrinth as a single version holding every jar,
# tagged with every Minecraft version and loader those jars cover.
#
# Required environment:
#   MODRINTH_TOKEN        personal access token with the "Create versions" and
#                         "Read versions" scopes
#   MODRINTH_PROJECT_ID   project ID or slug
#   BRIEFEST_BOXER_VERSION  release version, e.g. 0.2.0
# Optional:
#   MODRINTH_CHANGELOG_FILE  markdown changelog for the version
#   MODRINTH_DRY_RUN=1       print what would be uploaded without calling the API
#
# If the project already has this version number, nothing is uploaded, so a
# release run can be retried.
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
release_version="${BRIEFEST_BOXER_VERSION:?BRIEFEST_BOXER_VERSION is required}"
release_dir="$root_dir/releases/$release_version"
api="${MODRINTH_API:-https://api.modrinth.com/v2}"
dry_run="${MODRINTH_DRY_RUN:-0}"
user_agent="briefest-boxer-release-ci${GITHUB_REPOSITORY:+ (github.com/$GITHUB_REPOSITORY)}"
# The jar shown as the version's main download.
primary_pattern="briefest-boxer-26.2-fabric-*.jar"

if [[ "$dry_run" != 1 ]]; then
    : "${MODRINTH_TOKEN:?MODRINTH_TOKEN is required}"
fi
project="${MODRINTH_PROJECT_ID:?MODRINTH_PROJECT_ID is required}"

changelog=""
if [[ -n "${MODRINTH_CHANGELOG_FILE:-}" && -s "$MODRINTH_CHANGELOG_FILE" ]]; then
    changelog="$(cat "$MODRINTH_CHANGELOG_FILE")"
fi

case "$release_version" in
    *alpha*) version_type=alpha ;;
    *-*) version_type=beta ;;
    *) version_type=release ;;
esac

shopt -s nullglob
jars=("$release_dir"/briefest-boxer-*.jar)
if (( ${#jars[@]} == 0 )); then
    printf 'No jars found in %s\n' "$release_dir" >&2
    exit 1
fi

game_versions=()
loaders=()
file_parts=()
form=()
primary=""
index=0
for jar in "${jars[@]}"; do
    file="$(basename "$jar")"
    # briefest-boxer-<minecraft>-<loader>-<version>.jar
    rest="${file#briefest-boxer-}"
    minecraft="${rest%%-*}"
    rest="${rest#*-}"
    loader="${rest%%-*}"
    case "$loader" in
        fabric|forge) ;;
        *)
            printf 'Cannot tell the loader of %s\n' "$file" >&2
            exit 1
            ;;
    esac
    game_versions+=("$minecraft")
    loaders+=("$loader")

    part="file$index"
    index=$((index + 1))
    file_parts+=("$part")
    form+=(-F "$part=@$jar;type=application/java-archive")
    # shellcheck disable=SC2053
    if [[ "$file" == $primary_pattern ]]; then
        primary="$part"
    fi
done
[[ -n "$primary" ]] || primary="${file_parts[0]}"

json_array() { printf '%s\n' "$@" | jq -Rsc 'split("\n")[:-1]'; }
data="$(jq -nc \
    --arg project "$project" \
    --arg number "$release_version" \
    --arg changelog "$changelog" \
    --arg type "$version_type" \
    --arg primary "$primary" \
    --argjson game_versions "$(json_array "${game_versions[@]}")" \
    --argjson loaders "$(json_array "${loaders[@]}")" \
    --argjson parts "$(json_array "${file_parts[@]}")" \
    '{project_id: $project, name: $number, version_number: $number, changelog: $changelog,
      game_versions: ($game_versions | unique), loaders: ($loaders | unique),
      version_type: $type, dependencies: [{project_id: "P7dR8mSH", dependency_type: "optional"}], featured: true, status: "listed",
      file_parts: $parts, primary_file: $primary}')"

if [[ "$dry_run" == 1 ]]; then
    jq 'del(.changelog)' <<<"$data"
    for jar in "${jars[@]}"; do printf '  %s\n' "$(basename "$jar")"; done
    exit 0
fi

lookup="$(curl -sS -A "$user_agent" -w '\n%{http_code}' -H "Authorization: $MODRINTH_TOKEN" \
    "$api/project/$project/version")"
if [[ "${lookup##*$'\n'}" == 200 ]]; then
    if jq -e --arg v "$release_version" 'any(.[]; .version_number == $v)' <<<"${lookup%$'\n'*}" >/dev/null; then
        printf 'Modrinth already has version %s; nothing to upload.\n' "$release_version"
        exit 0
    fi
else
    printf '::warning::Could not list existing Modrinth versions (HTTP %s). Give the token the "Read versions" scope.\n' \
        "${lookup##*$'\n'}"
fi

printf 'Uploading %d jars to Modrinth as version %s\n' "${#jars[@]}" "$release_version"
response="$(curl -sS -A "$user_agent" -w '\n%{http_code}' \
    -H "Authorization: $MODRINTH_TOKEN" \
    --form-string "data=$data" \
    "${form[@]}" \
    "$api/version")"
status="${response##*$'\n'}"
body="${response%$'\n'*}"
if [[ "$status" != 200 ]]; then
    printf 'Modrinth rejected the upload (HTTP %s): %s\n' "$status" "$body" >&2
    exit 1
fi
printf 'Modrinth: uploaded version %s with %d files.\n' "$release_version" "${#jars[@]}"
