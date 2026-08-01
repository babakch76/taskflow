# TaskFlow — Android client

Jetpack Compose + Material 3 client for the Go/SQLite TaskFlow backend.
Retrofit + OkHttp + Gson for networking, EncryptedSharedPreferences for the JWT.

- minSdk 26, targetSdk/compileSdk 34
- Kotlin 2.0, AGP 8.4, Java 17
- No DI framework — the two long-lived singletons (`TokenManager`,
  `RetrofitClient`) are created once in `TaskFlowApp.onCreate()`

## Running it

Start the Go backend first (`go run ./cmd/server` in `task-manager-backend-GO`,
listens on `:8080`), then run the app.

### On the emulator — no configuration needed

The default base URL is `http://10.0.2.2:8080/`, the emulator's alias for the
host machine's localhost, and that host is already allow-listed for cleartext
HTTP in `res/xml/network_security_config.xml`. Just hit Run.

### On a physical phone — two edits (do this before the demo)

The phone has to reach your dev machine over the LAN, so `10.0.2.2` is useless
and the real IP has to be allow-listed. Both the phone and the dev machine must
be on the same Wi-Fi network.

**1. Find your dev machine's LAN IP**

```bash
ipconfig
```

(Windows — look for "IPv4 Address" under your Wi-Fi adapter. On macOS/Linux:
`ifconfig | grep "inet "`.) It will look like `192.168.1.42` or `10.0.0.7`.

**2. Point the app at it** — in `local.properties` (per-developer, not
committed):

```properties
taskflow.baseUrl=http://192.168.1.42:8080/
```

This feeds `BuildConfig.BASE_URL` via `buildConfigField` in
`app/build.gradle.kts`. Omit the key and the emulator default is used.

> **Always Clean after changing `taskflow.baseUrl`.** `buildConfigField`
> generates a `public static final String`, and the compiler inlines constants
> like that directly into `RetrofitClient`. An incremental build regenerates
> `BuildConfig.java` with the new value but can leave `compileDebugKotlin`
> up-to-date, so the *old* URL stays compiled into the APK and the app keeps
> talking to the old server.
>
> Consequence: **reading `BuildConfig.java` proves nothing** — it shows the new
> URL while the APK holds the old one. Run `./gradlew clean assembleDebug`, and
> verify by watching Logcat (`okhttp` filter) for the URL an actual request
> goes to.

**3. Allow cleartext to that host** — in
`app/src/main/res/xml/network_security_config.xml`, replace the placeholder IP
in the second `<domain>` entry with the same address:

```xml
<domain includeSubdomains="false">192.168.1.42</domain>
```

Then **Build → Clean Project** and rerun (the network security config is baked
into the APK).

> Do **not** "fix" a connection problem by setting
> `cleartextTrafficPermitted="true"` on `<base-config>`. That permits plaintext
> traffic to every host on the internet, not just your laptop.

**Also check:** the backend must be reachable from outside localhost, and your
OS firewall must allow inbound TCP 8080. On Windows, Defender blocks new
listening ports by default — allow it when prompted, or add the rule manually.

### Quick connectivity check

From the phone's browser, open `http://<your-ip>:8080/groups`. A
`{"error":"missing or invalid authorization header"}` response means the network
path works and only auth is missing — that is the result you want.

## Screens

| Screen | What it does |
|---|---|
| `LoginScreen` / `RegisterScreen` | Auth, with a text-button toggle between them. Register returns a token, so it signs you straight in. |
| `DashboardScreen` | Your groups; create new ones; **join existing ones** — a badged inbox for pending invites (accept/decline) and "Join with a code" in the overflow menu. |
| `GroupDetailScreen` | Three tabs — **Tasks**, **Activity**, **Members** — plus a progress header, an overflow menu for invites and leaving, and multi-select on tasks. |

`GroupDetailScreen` covers the whole group-scoped API:

- **Tasks** — tap a card to cycle `todo → in_progress → done`; the checkboxes
  enter multi-select, which uses the single-transaction bulk endpoint rather
  than N round trips. The `✕` on an assignee chip unassigns via
  `Patchable.SetNull`, sending an explicit `{"assigned_to":null}`.
- **Activity** — polled every 5s with `?since=`, so a teammate's change appears
  without a manual refresh. This is the CSCW awareness/feedback loop.
- **Members** — the roster, with invite-by-username and shareable invite codes
  in the overflow menu, plus leave-group.

Loading is concurrent: opening a group fires the group, tasks, members and
activity requests together, so it costs one round trip rather than four.

### Both ways of joining a group

The backend supports two invite paths, and both have UI:

1. **Direct invite** — a member picks *Invite by username* in a group's overflow
   menu. The invitee sees a badge on the Dashboard and accepts or declines.
2. **Shareable code** — a member picks *Create invite code* and sends the
   12-character code by any means. The recipient uses *Join with a code*. Codes
   are multi-use and expire after 48 hours.

Both write a `member_joined` activity event, so the group's feed reads the same
regardless of which route someone took.

## Build & test from the command line

The project uses the Gradle wrapper, so no local Gradle install is needed — but
it does need **JDK 17** (AGP 8.4 does not accept the JDK 21+/25 that recent
Android Studio versions bundle) and **SDK platform 34**:

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

Plain JVM tests, no emulator needed. `UpdateTaskRequestTypeAdapterTest` pins the
exact JSON bytes of a PATCH task body — see below.

If Gradle picks the wrong JDK, point it at 17 explicitly:

```bash
./gradlew -Dorg.gradle.java.home="C:/Program Files/Java/jdk-17" assembleDebug
```

## Notes on two things that are easy to break

### PATCH tri-state fields

`UpdateTaskRequest.assignedTo` / `.dueDate` are `Patchable<String>`, with three
states that map onto the backend's `models.NullableField`:

| Kotlin              | JSON            | Backend does      |
|---------------------|-----------------|-------------------|
| `Patchable.Absent`  | key omitted     | leaves it alone   |
| `Patchable.SetNull` | `"key": null`   | clears the field  |
| `Patchable.Value`   | `"key": "..."`  | sets the field    |

Serialization is a hand-written `UpdateTaskRequestTypeAdapter`, **not** a
reflective adapter, because omitting a key has to be explicit. An earlier
implementation wrote `null` for `Absent` and relied on `JsonWriter` dropping the
pending key when `serializeNulls` is false — which is not true of
`JsonTreeWriter`, so `toJsonTree` and unit tests turned every absent field into
an explicit `null` and silently cleared assignees and deadlines.

Build the request from the fields the user actually edited. An all-absent
request serializes to `{}` and the backend answers
`400 {"error":"no fields to update"}` — skip the call instead.

### Logging

`HttpLoggingInterceptor` is at `BODY` in debug builds and `NONE` in release, and
`Authorization` is redacted in both. Debug `BODY` logs still contain the
plaintext password in login/register request bodies — drop the level to `BASIC`
before sharing a logcat.
