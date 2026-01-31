# OSRS FlipHub RuneLite Plugin (Phase 1)

This module captures Grand Exchange offer lifecycle events (placed/updated/completed/aborted)
and sends signed, batched payloads to the FlipHub API.

Key constraints:
- Read-only (no automation)
- No OSRS credentials
- Event-driven (no tick polling)
- Deterministic logic

## Backend interfaces

### Link
`POST /api/plugin/link`
Request:
```json
{
  "code": "LINKCODE",
  "device_id": "device-uuid",
  "device_name": "PC-Name",
  "plugin_version": "1.0.0"
}
```
Response:
```json
{
  "session_token": "PST",
  "session_expires_at": "2026-01-23T00:00:00Z",
  "signing_secret": "BASE64URL"
}
```

### Events
`POST /api/plugin/events` with HMAC headers:
- `X-Plugin-Token`
- `X-Nonce`
- `X-Timestamp`
- `X-Signature`

Body:
```json
{
  "schema_version": 1,
  "sent_at_ms": 1730000000000,
  "events": [ ... ]
}
```

## Build

This module uses the Gradle wrapper. The wrapper JAR is not included in repo.

Steps:
1) Install a local Gradle distribution (once).
2) From `runelite-plugin`, run:
```
gradle wrapper
```
3) Then build:
```
./gradlew build
```

## Run (dev client)

This follows the official plugin-hub workflow. It launches the RuneLite dev client
with the plugin on the classpath.

```
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot"
./gradlew run --no-daemon --console=plain
```

If you use a Jagex account, follow the RuneLite guide for dev logins.

If you get init-script errors from an IDE, run with a clean Gradle home:
```
$env:GRADLE_USER_HOME="$env:TEMP\gradle-clean"
$env:GRADLE_OPTS=""
./gradlew run --no-daemon --console=plain
```

## Load in RuneLite (local dev)

1) Build the jar:
```
./gradlew build
```
2) The jar will be located at:
```
build/libs
```
3) In RuneLite: enable developer mode and load the plugin jar from `build/libs`.

## Plugin Hub alignment

This project includes `runelite-plugin.properties` and `pluginMainClass` in `build.gradle`
to match the official Plugin Hub expectations.
