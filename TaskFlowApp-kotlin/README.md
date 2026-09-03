# TaskFlow — Android client

Jetpack Compose + Material 3 client for the Go/SQLite TaskFlow backend.
Retrofit + OkHttp + Gson for networking, EncryptedSharedPreferences for the JWT.

- minSdk 26, targetSdk/compileSdk 34
- Kotlin 2.0, AGP 8.4, Java 17
> **What the app actually does** is documented in
> [`../FEATURES.md`](../FEATURES.md). This file is about how the Android side is
> put together.

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

## Producing an APK

### Day to day: the debug APK

```bash
./gradlew assembleDebug
```

Lands at `app/build/outputs/apk/debug/app-debug.apk` (~18 MB). Signed with the
shared debug key, so it installs on any device — this is the one to send a
teammate for testing.

> **⚠️ If you changed `taskflow.baseUrl`, run `clean` first:**
>
> ```bash
> ./gradlew clean assembleDebug
> ```
>
> `BASE_URL` is a `buildConfigField`, which the compiler **inlines** into
> `RetrofitClient`. An incremental build regenerates `BuildConfig.java` with the
> new URL while leaving the compiled Kotlin alone, so the APK keeps calling the
> old server. Reading `BuildConfig.java` will *not* reveal this — verify by
> watching Logcat's `okhttp` output for the URL a real request goes to.

In Android Studio: **Build → Build Bundle(s) / APK(s) → Build APK(s)**, then
"locate" in the notification. Same caveat — **Build → Clean Project** first if
the URL changed.

Debug builds log full request bodies to Logcat, including the plaintext
password on login. Fine locally; don't share a debug Logcat.

### For handing in: a signed release APK

`assembleRelease` alone produces `app-release-unsigned.apk`, which **Android
refuses to install**. It needs a keystore. Create one once:

```bash
keytool -genkeypair -v -keystore taskflow-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias taskflow
```

`keytool` ships with the JDK. It asks for a password and a few name fields —
answer them yourself; the password is never typed into a build file.

Keep the `.jks` **outside the repo** (it's gitignored, but don't rely on that),
then point `local.properties` at it:

```properties
taskflow.keystore=../taskflow-release.jks
taskflow.keystorePassword=your-password
taskflow.keyAlias=taskflow
taskflow.keyPassword=your-password
```

`local.properties` is gitignored, so the path and passwords stay off GitHub.

```bash
./gradlew clean assembleRelease
```

Now `app/build/outputs/apk/release/app-release.apk` — signed, installable, ~12 MB
(smaller than debug: no debug symbols, and `HttpLoggingInterceptor` is at `NONE`).

**Keep that keystore and its password.** Signing a later version with a
different key makes it a different app to Android — users have to uninstall
first, losing their data.

Without the keystore configured the release build still succeeds; it just emits
the unsigned APK and the signing step is skipped.

## Screens

| Screen | What it does |
|---|---|
| `LoginScreen` / `RegisterScreen` | Auth, with a text-button toggle between them. Register returns a token, so it signs you straight in. |
| `DashboardScreen` | Your groups; create new ones; **join existing ones** — a badged inbox for pending invites (accept/decline) and "Join with a code" in the overflow menu. |
| `GroupDetailScreen` | Three tabs — **Board**, **Activity**, **Members** — plus an overflow menu for invites, the household's record, going away, and leaving. |
| `CreateChoreFlow` | Adding or editing a chore, as two full-screen steps. Also the delete. |
| `FirstRunStarters` | The five starter chores a brand-new household is offered. |

`GroupDetailScreen` covers the whole group-scoped API:

- **Board** — the app's main screen. Rows grouped **YOURS / OTHERS / DONE THIS
  CYCLE**, three lines each, with a fourth only when the turn rule has something
  to explain. One tap on the checkbox marks done, by anybody, undoable for ten
  minutes by whoever ticked it. Swiping your own row passes it on, and the
  snackbar offers Undo. Overdue is amber, never red, and the amber comes from
  the theme so it survives the appearance setting.
- **Activity** — polled every 5s with `?since=`, so a housemate's change appears
  without a manual refresh. This is the CSCW awareness/feedback loop. A busy
  pass deliberately writes nothing here.
- **Members** — the roster, showing who is away, with invite-by-username and
  shareable invite codes in the overflow menu, plus leave-group.

Loading is concurrent: opening a group fires the group, occurrences, chores,
tasks, members and activity requests together rather than in sequence.

**What used to be here and is not any more**, since this file described it for a
month after it went: the **Tasks** tab is now **Board**; the progress header was
removed because a percentage on every board is a constraint-4 violation;
multi-select and the bulk endpoint are gone; and a card no longer cycles
`todo → in_progress → done`, because the status chips went with the pivot and
the two states that remain are open and done.

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
