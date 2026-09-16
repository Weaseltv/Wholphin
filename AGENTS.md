# Agent standing rules (Wholphin / WeaselPlex Android TV)

- This is the WeaselTV fork of Wholphin (WeaselPlex Android TV). Keep customer-facing naming WeaselPlex.
- Update WeaselTV Command Center / Products in Notion as part of finishing work.
- Notion MCP: Claude is Connected on T3 workers. Codex needs a one-time `codex mcp login notion` on that machine (OAuth is machine-local).
- Do not invent new Products from chat folders. Link work to existing Products rows.
- No production secrets in the repo.

## Android ship workflow

Official publish = merge to `weaselfin` -> tag that merge commit -> signed APK from the tag. No pre-merge device check required.

Ship branch is **`weaselfin`** (not upstream `main`). All WeaselPlex release tags (`v1.1.x`, etc.) are on `weaselfin`. Do not publish from `main` unless the owner explicitly changes that policy.

Release signing: release builds need the WeaselFin signing keystore (same key as prior installs). Unsigned APKs will not upgrade over existing installs. Prefer building the signed release on a host that already has the keystore; do not commit keystore files or passwords.


## Distribution (WeaselPlex Android TV)

- **In-app updates:** GitHub Releases on `Weaseltv/Wholphin` (not `theweasel.tv` direct-distribution).
- **New installs (Downloader `9216225`):** `aftv.news/9216225` → `https://theweasel.tv/fin` → GitHub `.../releases/latest/download/WeaselFin-release.apk`.
  There is **no separate VPS APK copy** to refresh. Publishing the GitHub release (correct asset names + mark as Latest) updates both in-app updates and new Downloader installs.
- After tagging on `weaselfin`: upload signed assets named exactly `WeaselFin-release.apk` / `WeaselFin-release-arm64-v8a.apk` (and other ABI splits if built). Release **title/name** must be exactly `vX.Y.Z` (no `— WeaselPlex` suffix) or the in-app updater reports no update available.
- Ship is not done until all of these are true:
  1. GitHub release `vX.Y.Z` exists with those asset names and is the repo **Latest** release.
  2. `curl -sI https://theweasel.tv/fin` redirects to that release's `WeaselFin-release.apk` (via `.../releases/latest/download/...`).
  3. In-app updater can see `vX.Y.Z` (plain title).
- Do not use the removed GitHub Actions Create release workflow; build/sign on VPS or ThinkCentre (with WholphinExtensions / ffmpeg packages wired).
- Do not tell the owner a "VPS website APK still needs refreshing" after a correct Latest GitHub publish — `/fin` already tracks Latest.
