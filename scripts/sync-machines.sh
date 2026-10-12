#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/sync-machines.sh

Bring this repository's main checkout up to date with GitHub on this machine
and on the other T3 machine (VPS <-> ThinkCentre). Run it from any checkout or
worktree of the repo when you finish a task that pushed or merged code. Push
your branch first: GitHub is the only transfer between machines.

On each machine it fetches every remote and fast-forwards the main checkout and
the local default branch (GitHub's default, e.g. main or weaselfin) when that is
safe. It never discards changes, rebases, or touches another thread's worktree;
anything it cannot update safely is reported instead.

The same script is kept in every WeaselTV repo; keep the copies identical.
EOF
}

if (($#)); then
    usage
    [[ "$1" == -h || "$1" == --help ]] && exit 0
    exit 2
fi

case "$(hostname)" in
    srv1160496) this=VPS peer=thinkcentre peer_name=ThinkCentre ;;
    justin-ThinkCentre-M73) this=ThinkCentre peer=srv1160496 peer_name=VPS ;;
    *)
        echo "Unknown machine $(hostname); expected the VPS or the ThinkCentre." >&2
        exit 1
        ;;
esac

# The main checkout owns the shared .git directory. Both machines use the same
# path under $HOME, so the peer's checkout is found the same way.
common_dir=$(git rev-parse --path-format=absolute --git-common-dir)
main_checkout=$(dirname "$common_dir")
case "$main_checkout" in
    "$HOME"/*) rel_checkout=${main_checkout#"$HOME"/} ;;
    *)
        echo "Main checkout $main_checkout is not under \$HOME." >&2
        exit 1
        ;;
esac
branch=$(git ls-remote --symref origin HEAD | sed -n 's|^ref: refs/heads/\(.*\)\tHEAD$|\1|p')
if [[ -z "$branch" ]]; then
    echo "Could not read the default branch from origin." >&2
    exit 1
fi

# Runs on each machine with the checkout path and branch as arguments.
# shellcheck disable=SC2016
refresh='
set -uo pipefail
checkout=$HOME/$1 branch=$2
cd "$checkout" 2>/dev/null || { echo "  no checkout at $checkout"; exit 1; }
git fetch --all --prune --quiet || { echo "  fetch failed"; exit 1; }
target=$(git rev-parse --short "origin/$branch")
if [[ -n "$(git status --porcelain)" ]]; then
    echo "  main checkout has uncommitted changes; left as is (fetched only)"
else
    current=$(git symbolic-ref --quiet --short HEAD || true)
    if [[ "$current" == "$branch" ]] || { [[ -n "$current" ]] && git switch --quiet "$branch" 2>/dev/null; }; then
        if git merge --ff-only --quiet "origin/$branch"; then
            [[ "$current" == "$branch" ]] && from= || from="$current -> "
            echo "  main checkout $from$branch at $target"
        else
            echo "  main checkout $branch has local commits; not fast-forwarded"
        fi
    else
        # Detached, or the branch is checked out in another worktree
        git switch --quiet --detach "origin/$branch" && echo "  main checkout ${current:+$current -> }origin/$branch at $target"
    fi
fi
if [[ "$(git symbolic-ref --quiet --short HEAD || true)" != "$branch" ]]; then
    if git fetch --quiet origin "$branch:$branch" 2>/dev/null; then
        echo "  local $branch branch at $target"
    else
        holder=$(git worktree list --porcelain | awk -v b="refs/heads/$branch" '\''/^worktree /{w=$2} $0=="branch "b{print w}'\'')
        echo "  local $branch branch not updated (${holder:+checked out in $holder, or }has local commits); branch from origin/$branch"
    fi
fi
echo "  latest: $(git log -1 --format="%h %cd %s" --date=short "origin/$branch")"
'

status=0
echo "$(basename "$main_checkout") on $this (this machine):"
bash -c "$refresh" refresh "$rel_checkout" "$branch" || status=1
echo "$(basename "$main_checkout") on $peer_name:"
if ! ssh -o BatchMode=yes -o ConnectTimeout=10 "$peer" bash -s -- "$(printf %q "$rel_checkout")" "$(printf %q "$branch")" <<<"$refresh"; then
    echo "  could not refresh the $peer_name; report this to the owner" >&2
    status=1
fi
exit "$status"
