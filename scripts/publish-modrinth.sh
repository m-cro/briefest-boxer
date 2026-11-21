#!/usr/bin/env bash
# Uploads releases/<version>/ to Modrinth as a single version holding every jar,
# tagged with every Minecraft version the jars cover and with Fabric and Forge.
#
# Modrinth checks each jar against the version's game versions and loaders at
# the moment it is uploaded, and refuses Fabric next to pre-1.14 game versions.
# So the version is created with one jar, each further jar is added after the
# version is retagged to suit that jar, and the full tags are set at the end.
#
# Required environment:
#   MODRINTH_TOKEN        personal access token with the "Create versions",
#                         "Read versions" and "Write versions" scopes
#   MODRINTH_PROJECT_ID   project ID or slug
#   BRIEFEST_BOXER_VERSION  release version, e.g. 0.2.0
# Optional:
#   MODRINTH_CHANGELOG_FILE  markdown changelog for the version
#   MODRINTH_DRY_RUN=1       print the plan without calling the API
#
# Re-running resumes: if the version exists, only jars it lacks are added.
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
release_version="${BRIEFEST_BOXER_VERSION:?BRIEFEST_BOXER_VERSION is required}"
release_dir="$root_dir/releases/$release_version"
api="${MODRINTH_API:-https://api.modrinth.com/v2}"
dry_run="${MODRINTH_DRY_RUN:-0}"
user_agent="briefest-boxer-release-ci${GITHUB_REPOSITORY:+ (github.com/$GITHUB_REPOSITORY)}"
FABRIC_API_ID=P7dR8mSH
# The jar the version is created with; Modrinth shows it as the main download.
primary_pattern="briefest-boxer-26.2-fabric-*.jar"
# Loaders shown on the finished version.
final_loaders='["fabric","forge"]'

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
all_jars=("$release_dir"/briefest-boxer-*.jar)
if (( ${#all_jars[@]} == 0 )); then
    printf 'No jars found in %s\n' "$release_dir" >&2
    exit 1
fi

# Primary jar first, then the rest in name order.
jars=()
for jar in "${all_jars[@]}"; do
    # shellcheck disable=SC2053
    if [[ "$(basename "$jar")" == $primary_pattern ]]; then jars+=("$jar"); fi
done
if (( ${#jars[@]} != 1 )); then
    printf 'Expected exactly one jar matching %s\n' "$primary_pattern" >&2
    exit 1
fi
for jar in "${all_jars[@]}"; do
    if [[ "$jar" != "${jars[0]}" ]]; then jars+=("$jar"); fi
done

# Prints "<minecraft> <loader tag>" for a jar, where the loader tag is what
# Modrinth accepts for that jar on its own.
jar_tags() {
    local file rest minecraft loader
    file="$(basename "$1")"
    rest="${file#briefest-boxer-}"
    minecraft="${rest%%-*}"
    rest="${rest#*-}"
    loader="${rest%%-*}"
    case "$loader" in
        fabric)
            case "$minecraft" in
                1.7.10|1.8.9|1.9.4|1.12.2) loader=legacy-fabric ;;
            esac
            ;;
        forge) ;;
        *)
            printf 'Cannot tell the loader of %s\n' "$file" >&2
            return 1
            ;;
    esac
    printf '%s %s\n' "$minecraft" "$loader"
}

game_versions=()
for jar in "${jars[@]}"; do
    read -r minecraft _ < <(jar_tags "$jar")
    game_versions+=("$minecraft")
done
all_game_versions="$(printf '%s\n' "${game_versions[@]}" | jq -Rsc 'split("\n")[:-1] | unique')"

if [[ "$dry_run" == 1 ]]; then
    for jar in "${jars[@]}"; do
        read -r minecraft loader < <(jar_tags "$jar")
        printf '%-42s tagged %s / %s while uploading\n' "$(basename "$jar")" "$minecraft" "$loader"
    done
    printf 'Final tags: game versions %s, loaders %s\n' "$all_game_versions" "$final_loaders"
    exit 0
fi

# Sends a request; prints the body, or reports the error and fails.
modrinth() {
    local what="$1"
    shift
    local response status
    response="$(curl -sS -A "$user_agent" -H "Authorization: $MODRINTH_TOKEN" -w '\n%{http_code}' "$@")"
    status="${response##*$'\n'}"
    if [[ "$status" != 2* ]]; then
        printf 'Modrinth rejected %s (HTTP %s): %s\n' "$what" "$status" "${response%$'\n'*}" >&2
        return 1
    fi
    printf '%s' "${response%$'\n'*}"
}

retag() {
    modrinth "retagging version $release_version" -X PATCH -H 'Content-Type: application/json' \
        --data "$(jq -nc --argjson g "$1" --argjson l "$2" '{game_versions: $g, loaders: $l}')" \
        "$api/version/$version_id" >/dev/null
}

versions="$(modrinth "the version list" "$api/project/$project/version")"
version_id="$(jq -r --arg v "$release_version" 'first(.[] | select(.version_number == $v) | .id) // empty' <<<"$versions")"

if [[ -z "$version_id" ]]; then
    read -r minecraft loader < <(jar_tags "${jars[0]}")
    printf 'Creating Modrinth version %s with %s\n' "$release_version" "$(basename "${jars[0]}")"
    data="$(jq -nc \
        --arg project "$project" --arg number "$release_version" --arg changelog "$changelog" \
        --arg type "$version_type" --arg minecraft "$minecraft" --arg loader "$loader" \
        --arg fabric_api "$FABRIC_API_ID" \
        '{project_id: $project, name: $number, version_number: $number, changelog: $changelog,
          game_versions: [$minecraft], loaders: [$loader], version_type: $type,
          dependencies: [{project_id: $fabric_api, dependency_type: "optional"}],
          featured: true, status: "listed", file_parts: ["file"], primary_file: "file"}')"
    created="$(modrinth "creating version $release_version" -X POST \
        --form-string "data=$data" -F "file=@${jars[0]};type=application/java-archive" \
        "$api/version")"
    version_id="$(jq -r .id <<<"$created")"
    present="$(jq -nc --arg f "$(basename "${jars[0]}")" '[$f]')"
else
    printf 'Modrinth version %s exists; adding any missing jars\n' "$release_version"
    present="$(jq -c --arg id "$version_id" 'first(.[] | select(.id == $id)) | [.files[].filename]' <<<"$versions")"
fi

added=0
for jar in "${jars[@]}"; do
    file="$(basename "$jar")"
    if jq -e --arg f "$file" 'index($f) != null' <<<"$present" >/dev/null; then
        continue
    fi
    read -r minecraft loader < <(jar_tags "$jar")
    printf 'Adding %s (as %s / %s)\n' "$file" "$minecraft" "$loader"
    retag "$(jq -nc --arg m "$minecraft" '[$m]')" "$(jq -nc --arg l "$loader" '[$l]')"
    modrinth "adding $file" -X POST --form-string 'data={}' \
        -F "file=@$jar;type=application/java-archive" \
        "$api/version/$version_id/file" >/dev/null
    added=$((added + 1))
done

retag "$all_game_versions" "$final_loaders"
printf '\nModrinth: version %s has all %d jars (%d added this run), tagged %s on %s.\n' \
    "$release_version" "${#jars[@]}" "$added" "$final_loaders" "$all_game_versions"
