#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/sync-machines.sh

Bring the WeaselPlex Android TV checkout up to date with GitHub on this machine
and on the other T3 machine (VPS <-> ThinkCentre). Run it when you finish a task
that pushed or merged code. Push your branch first: GitHub is the only transfer.

On each machine it fetches every remote and fast-forwards the main checkout and
the local weaselfin branch to origin/weaselfin when that is safe. It never
discards changes, rebases, or touches another thread's worktree; anything it
cannot update safely is reported instead.
EOF
}

if (($#)); then
    usage
    [[ "$1" == -h || "$1" == --help ]] && exit 0
    exit 2
fi

checkout='$HOME/work/weaselfin/Wholphin'

case "$(hostname)" in
    srv1160496) this=VPS peer=thinkcentre peer_name=ThinkCentre ;;
    justin-ThinkCentre-M73) this=ThinkCentre peer=srv1160496 peer_name=VPS ;;
    *)
        echo "Unknown machine $(hostname); expected the VPS or the ThinkCentre." >&2
        exit 1
        ;;
esac

# Runs on each machine. Prints one status line per step.
refresh='
set -uo pipefail
cd '"$checkout"' || { echo "  no checkout at '"$checkout"'"; exit 1; }
git fetch --all --prune --quiet || { echo "  fetch failed"; exit 1; }
target=$(git rev-parse --short origin/weaselfin)
if [[ -n "$(git status --porcelain)" ]]; then
    echo "  main checkout has uncommitted changes; left as is (fetched only)"
else
    branch=$(git symbolic-ref --quiet --short HEAD || true)
    if [[ -z "$branch" ]]; then
        git switch --quiet --detach origin/weaselfin && echo "  main checkout -> origin/weaselfin ($target)"
    elif [[ "$branch" == weaselfin ]]; then
        if git merge --ff-only --quiet origin/weaselfin; then
            echo "  main checkout weaselfin -> $target"
        else
            echo "  main checkout weaselfin has local commits; not fast-forwarded"
        fi
    elif git switch --quiet weaselfin 2>/dev/null; then
        if git merge --ff-only --quiet origin/weaselfin; then
            echo "  main checkout $branch -> weaselfin ($target)"
        else
            echo "  main checkout switched to weaselfin, which has local commits; not fast-forwarded"
        fi
    else
        # weaselfin is checked out in another worktree
        git switch --quiet --detach origin/weaselfin && echo "  main checkout $branch -> origin/weaselfin ($target)"
    fi
fi
if [[ "$(git symbolic-ref --quiet --short HEAD || true)" != weaselfin ]]; then
    if git fetch --quiet origin weaselfin:weaselfin 2>/dev/null; then
        echo "  local weaselfin branch -> $target"
    else
        echo "  local weaselfin branch not updated (checked out in another worktree or has local commits); branch from origin/weaselfin"
    fi
fi
echo "  latest: $(git log -1 --format="%h %cd %s" --date=short origin/weaselfin)"
'

status=0
echo "$this (this machine):"
bash -c "$refresh" || status=1
echo "$peer_name:"
if ! ssh -o BatchMode=yes -o ConnectTimeout=10 "$peer" "bash -s" <<<"$refresh"; then
    echo "  could not refresh the $peer_name; report this to the owner" >&2
    status=1
fi
exit "$status"
