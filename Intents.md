# Intents

> [!IMPORTANT]
> This feature is experimental and the exact behaviors and/or parameters may change at any time

Wholphin accepts [intents](https://developer.android.com/guide/components/intents-filters) to load or play specified media.

Parameters can be specified as "extras" (eg `--es query value`) or as query parameters to the data URI (eg `-d wholphin://search?query=value`).

UUID parameters can be specified with or without hyphens.

If using data URI query parameters on the command line, you need to escape ampersands: `itemId=...\&userId=...`

## Server/user switching

You can choose a server and user to switch to with the `serverId` and `userId` UUID parameters. If the profile is not protected, the app will switch to it.

If not specified, the app will use the current user if auto sign in is enabled.

## Search

Action: `android.intent.action.SEARCH`

Shorthand action: `search`

Parameters:
- `query` - Optional, prepopulate the search field


### Examples

Full format
```bash
adb shell am start \
  -a android.intent.action.SEARCH \
  -n 'com.github.damontecres.wholphin/.MainActivity' \
  --es query "Avengers"
```

URI based
```bash
adb shell am start -d 'wholphin://search?query=Avengers'
```

Multi-word query:
```bash
# Whole shell command is wrapped in double quotes
adb shell "am start \
  -a android.intent.action.SEARCH \
  -n 'com.github.damontecres.wholphin/.MainActivity' \
  --es query 'Avengers Endgame'"

adb shell am start -d 'wholphin://search?query=Avengers+Endgame'
adb shell am start -d 'wholphin://search?query=Avengers%20Endgame'
```

## View

This opens the app to the specified item such as an episode or movie

Action: `android.intent.action.VIEW`

Shorthand action: `view`

Parameters:
- `itemId` - Required, the UUID of the media item


### Examples

Full format
```bash
adb shell am start \
  -a android.intent.action.VIEW \
  -n 'com.github.damontecres.wholphin/.MainActivity' \
  --es itemId "5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d"
```

URI based
```bash
adb shell am start -d 'wholphin://view?itemId=5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d'
```

## Playback

Action: `com.github.damontecres.wholphin.PLAYBACK`

Shorthand action: `play`

Parameters:
- `itemId` - Required unless `itemIds` is given, the UUID of the media item
- `position` - Optional, the start position for playback in milliseconds
- `shuffle` - Optional, whether to shuffle the resulting items
- `mediaSourceId` - Optional, the ID of a specific media source (version) to play
- `audioStreamIndex` - Optional, the index of the audio stream to use
- `subtitleStreamIndex` - Optional, the index of the subtitle stream to use, or `-1` for none
- `itemIds` - Optional, a comma separated list of UUIDs to play in order instead of the contextual queue (this is what a remote "Play On" request sends)
- `startIndex` - Optional, the index in `itemIds` to start from (default `0`)

These are the same parameters a Jellyfin "Play On" request carries, so this intent is a convenient way to test that feature without a second device.

### Examples

Full format
```bash
adb shell am start \
  -a com.github.damontecres.wholphin.PLAYBACK \
  -n 'com.github.damontecres.wholphin/.MainActivity' \
  --es itemId "5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d"
```

URI based
```bash
adb shell am start -d 'wholphin://play?itemId=5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d'
```

Start at 4m30s:
```bash
adb shell am start \
  -a com.github.damontecres.wholphin.PLAYBACK \
  -n 'com.github.damontecres.wholphin/.MainActivity' \
  --es itemId "5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d" \
  --el position 270000

adb shell am start -d 'wholphin://play?itemId=5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d\&position=270000'
```

Shuffle a series (`itemId` is the Series ID):
```bash
adb shell am start \
  -a com.github.damontecres.wholphin.PLAYBACK \
  -n 'com.github.damontecres.wholphin/.MainActivity' \
  --es itemId "5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d" \
  --ez shuffle true

adb shell am start -d 'wholphin://play?itemId=5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d\&shuffle=true'
```

Play a specific version with a chosen audio track and no subtitles:
```bash
adb shell am start \
  -a com.github.damontecres.wholphin.PLAYBACK \
  -n 'com.github.damontecres.wholphin/.MainActivity' \
  --es itemId "5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d" \
  --es mediaSourceId "5cf8f8e72a5f4aa98c12ddf63d42ee6d" \
  --ei audioStreamIndex 2 \
  --ei subtitleStreamIndex -1

adb shell am start -d 'wholphin://play?itemId=5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d\&audioStreamIndex=2\&subtitleStreamIndex=-1'
```

Play an explicit queue, starting with the second item:
```bash
adb shell am start -d 'wholphin://play?itemIds=5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d,66c80aaac9c17d3761782d2205520229\&startIndex=1'
```

## More examples

### Switch user

Full format
```bash
adb shell am start \
  -a android.intent.action.VIEW \
  -n 'com.github.damontecres.wholphin/.MainActivity' \
  --es itemId "5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d" \
  --es serverId "341880f9-ca88-4038-8718-f6b2407950b1" \
  --es userId "5c8575e0-468d-44fb-8c70-00aa79160587"
```

URI based
```bash
adb shell am start -d 'wholphin://view?itemId=66c80aaac9c17d3761782d2205520229\&serverId=341880f9-ca88-4038-8718-f6b2407950b1\&userId=5c8575e0-468d-44fb-8c70-00aa79160587'
```

### Debug build

Debug builds have a different package, so you must specify the component by its full name:

```bash
adb shell am start \
  -a android.intent.action.VIEW \
  -n 'com.github.damontecres.wholphin.debug/com.github.damontecres.wholphin.MainActivity' \
  --es itemId "5cf8f8e7-2a5f-4aa9-8c12-ddf63d42ee6d"
```
