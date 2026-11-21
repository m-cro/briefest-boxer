#!/usr/bin/env bash
# Uploads every jar in releases/<version>/ to Modrinth as its own version.
#
# Required environment:
#   MODRINTH_TOKEN        personal access token with the "Create versions" scope
#   MODRINTH_PROJECT_ID   project ID or slug
#   BRIEFEST_BOXER_VERSION  release version, e.g. 0.2.0
# Optional:
#   MODRINTH_CHANGELOG_FILE  markdown changelog used for every uploaded version
#   MODRINTH_DRY_RUN=1       print what would be uploaded without calling the API
#
# Versions whose version number already exists on the project are skipped, so a
# failed run can be retried.
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
release_version="${BRIEFEST_BOXER_VERSION:?BRIEFEST_BOXER_VERSION is required}"
release_dir="$root_dir/releases/$release_version"
api="${MODRINTH_API:-https://api.modrinth.com/v2}"
dry_run="${MODRINTH_DRY_RUN:-0}"
user_agent="briefest-boxer-release-ci${GITHUB_REPOSITORY:+ (github.com/$GITHUB_REPOSITORY)}"

FABRIC_API_ID=P7dR8mSH
MODMENU_ID=mOgUt4GM

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

existing='[]'
if [[ "$dry_run" != 1 ]]; then
    # Reading a draft project's versions needs the token's "Read versions" scope.
    # Without it Modrinth answers 404; carry on and let the upload report errors.
    lookup="$(curl -sS -A "$user_agent" -w '\n%{http_code}' -H "Authorization: $MODRINTH_TOKEN" \
        "$api/project/$project/version")"
    if [[ "${lookup##*$'\n'}" == 200 ]]; then
        existing="$(jq '[.[].version_number]' <<<"${lookup%$'\n'*}")"
    else
        printf '::warning::Could not list existing Modrinth versions (HTTP %s); already-uploaded versions will not be skipped. Give the token the "Read versions" scope.\n' \
            "${lookup##*$'\n'}"
    fi
fi

shopt -s nullglob
jars=("$release_dir"/briefest-boxer-*.jar)
if (( ${#jars[@]} == 0 )); then
    printf 'No jars found in %s\n' "$release_dir" >&2
    exit 1
fi

uploaded=0
skipped=0
for jar in "${jars[@]}"; do
    file="$(basename "$jar")"
    # briefest-boxer-<minecraft>-<loader>-<version>.jar
    rest="${file#briefest-boxer-}"
    minecraft="${rest%%-*}"
    rest="${rest#*-}"
    loader_dir="${rest%%-*}"

    dependencies='[]'
    case "$loader_dir" in
        fabric)
            case "$minecraft" in
                1.7.10|1.8.9|1.9.4|1.12.2) loader=legacy-fabric; loader_name="Legacy Fabric" ;;
                *)
                    loader=fabric; loader_name=Fabric
                    dependencies="$(jq -nc --arg id "$FABRIC_API_ID" '[{project_id: $id, dependency_type: "required"}]')"
                    ;;
            esac
            if [[ "$minecraft" == 26.2 ]]; then
                dependencies="$(jq -c --arg id "$MODMENU_ID" '. + [{project_id: $id, dependency_type: "required"}]' <<<"$dependencies")"
            fi
            ;;
        forge) loader=forge; loader_name=Forge ;;
        *)
            printf 'Cannot tell the loader of %s\n' "$file" >&2
            exit 1
            ;;
    esac

    version_number="$release_version+$minecraft-$loader_dir"
    if jq -e --arg v "$version_number" 'index($v) != null' <<<"$existing" >/dev/null; then
        printf 'Skipping %s: %s already exists on Modrinth\n' "$file" "$version_number"
        skipped=$((skipped + 1))
        continue
    fi

    data="$(jq -nc \
        --arg project "$project" \
        --arg name "Briefest Boxer $release_version ($loader_name $minecraft)" \
        --arg number "$version_number" \
        --arg changelog "$changelog" \
        --arg minecraft "$minecraft" \
        --arg loader "$loader" \
        --arg type "$version_type" \
        --argjson dependencies "$dependencies" \
        '{project_id: $project, name: $name, version_number: $number, changelog: $changelog,
          game_versions: [$minecraft], loaders: [$loader], version_type: $type,
          dependencies: $dependencies, featured: false, status: "listed",
          file_parts: ["file"], primary_file: "file"}')"

    if [[ "$dry_run" == 1 ]]; then
        printf '%s -> %s\n' "$file" "$(jq -c 'del(.changelog)' <<<"$data")"
        continue
    fi

    printf 'Uploading %s as %s\n' "$file" "$version_number"
    response="$(curl -sS -A "$user_agent" -w '\n%{http_code}' \
        -H "Authorization: $MODRINTH_TOKEN" \
        --form-string "data=$data" \
        -F "file=@$jar;type=application/java-archive" \
        "$api/version")"
    status="${response##*$'\n'}"
    body="${response%$'\n'*}"
    if [[ "$status" != 200 ]]; then
        printf 'Modrinth rejected %s (HTTP %s): %s\n' "$file" "$status" "$body" >&2
        exit 1
    fi
    uploaded=$((uploaded + 1))
done

printf '\nModrinth: %d uploaded, %d already present.\n' "$uploaded" "$skipped"
