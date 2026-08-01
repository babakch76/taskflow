# Deploying TaskFlow to AWS EC2

End result: the backend on a public HTTPS URL, and the Android app reaching it
from **both** the emulator and a physical phone with the *same* configuration —
no LAN IP, no `10.0.2.2`, no cleartext exemptions.

Budget about 30–40 minutes the first time.

---

## Read this first: two things that will waste your afternoon

### 1. You cannot cross-compile this from Windows the obvious way

The old README said:

```bash
GOOS=linux GOARCH=amd64 go build -o server ./cmd/server   # ← produces a broken binary
```

Cross-compiling **implicitly sets `CGO_ENABLED=0`**, and `mattn/go-sqlite3` is a
CGO package. With CGO off it still compiles — into a stub. The binary starts,
calls `db.Ping()`, and dies:

```
failed to init database: ping db: Binary was compiled with 'CGO_ENABLED=0',
go-sqlite3 requires cgo to work. This is a stub
```

You need a Linux C toolchain. The simplest reliable answer, and the one below,
is **build on the EC2 instance** — no cross-toolchain to install or debug.

### 2. HTTP alone will not work, and that's the app protecting you

`network_security_config.xml` sets `cleartextTrafficPermitted="false"` globally
and allow-lists only the emulator/LAN hosts. Point the app at
`http://<ec2-public-ip>:8080/` and every request fails with
`CLEARTEXT communication not permitted`.

Do **not** fix that by allow-listing your EC2 address. On a LAN, cleartext is a
contained risk. On a public server it means every password and every JWT crosses
the open internet in plaintext, readable by anything between the phone and AWS.
Set up TLS instead — with Caddy it's about four commands and it's free.

---

## Step 1 — Launch the instance

- **AMI:** Ubuntu Server 24.04 LTS
- **Type:** `t3.micro` is plenty (SQLite + a Go binary)
- **Key pair:** create/download one, you'll need it for `ssh`
- **Storage:** default 8 GB is fine

**Elastic IP — do this.** A stopped instance gets a *new* public IP on restart,
which breaks your DNS and your demo. Allocate an Elastic IP and associate it:
EC2 → Network & Security → Elastic IPs → Allocate → Actions → Associate.

### Security group

| Type  | Port | Source    | Why |
|-------|------|-----------|-----|
| SSH   | 22   | My IP     | admin |
| HTTP  | 80   | 0.0.0.0/0 | Let's Encrypt's ACME challenge |
| HTTPS | 443  | 0.0.0.0/0 | the app |

**Do not open 8080.** The Go server binds to `127.0.0.1` and Caddy proxies to
it; 8080 open to the world would be exactly the cleartext hole TLS is there to
close.

---

## Step 2 — A hostname

Let's Encrypt will not issue a certificate for a bare IP address, so you need a
DNS name pointing at your Elastic IP.

- **Own a domain?** Add an `A` record → your Elastic IP.
- **Don't?** [duckdns.org](https://duckdns.org) is free, takes a minute, and
  gives you `something.duckdns.org`. Sign in with GitHub/Google, pick a
  subdomain, paste your Elastic IP, hit update.

Check it resolves before continuing:

```bash
nslookup taskflow-yourname.duckdns.org
```

---

## Step 3 — Install Go and a compiler on the instance

SSH in (`ssh -i your-key.pem ubuntu@<elastic-ip>`), then:

```bash
sudo apt-get update && sudo apt-get install -y build-essential git
```

Ubuntu's packaged Go is often too old — this project needs 1.22+. Install the
official tarball:

```bash
curl -fsSL https://go.dev/dl/go1.22.5.linux-amd64.tar.gz | sudo tar -C /usr/local -xz
```

```bash
echo 'export PATH=$PATH:/usr/local/go/bin' >> ~/.bashrc && export PATH=$PATH:/usr/local/go/bin
```

```bash
go version && gcc --version | head -1
```

---

## Step 4 — Get the source up and build it

From your **Windows machine**, copy the backend up (adjust the path):

```bash
scp -i your-key.pem -r "D:\0A-study-A0\0-ACSAI-0\sem 6\HCI\Antigravity workspace\task-manager-backend-GO" ubuntu@<elastic-ip>:~/taskflow-src
```

Then **on the instance**:

```bash
cd ~/taskflow-src && CGO_ENABLED=1 go build -o taskflow-server ./cmd/server
```

Confirm CGO actually made it in — this is the check that catches the stub:

```bash
go version -m taskflow-server | grep CGO_ENABLED
```

It must print `CGO_ENABLED=1`. If it says `0`, `gcc` wasn't found; fix that
before going further.

---

## Step 5 — Install it as a service

Dedicated user, no shell, no home:

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin taskflow
```

Directories — binary in `/opt`, database in `/var/lib` so a redeploy that
overwrites the binary can never delete your data:

```bash
sudo mkdir -p /opt/taskflow /var/lib/taskflow /etc/taskflow && sudo install -o taskflow -g taskflow -m 755 ~/taskflow-src/taskflow-server /opt/taskflow/ && sudo chown taskflow:taskflow /var/lib/taskflow
```

**Generate a real JWT secret.** The built-in default is
`change-me-in-production`, and it's in the source — anyone who has read this
repo could mint a valid token for any account on your server. The service now
logs a warning at startup if you leave it:

```bash
printf 'JWT_SECRET=%s\n' "$(openssl rand -base64 48)" | sudo tee /etc/taskflow/taskflow.env > /dev/null && sudo chmod 600 /etc/taskflow/taskflow.env && sudo chown root:root /etc/taskflow/taskflow.env
```

Install and start the unit:

```bash
sudo cp ~/taskflow-src/deploy/taskflow.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now taskflow
```

```bash
systemctl status taskflow --no-pager
```

It should be `active (running)`. Smoke test it locally on the box — this proves
the Go server works before Caddy enters the picture:

```bash
curl -i http://127.0.0.1:8080/groups
```

Expect `401` with `{"error":"missing or invalid authorization header"}`. That's
success: the route is alive and auth is enforced.

---

## Step 6 — TLS with Caddy

```bash
sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl
```

```bash
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
```

```bash
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
```

```bash
sudo apt-get update && sudo apt-get install -y caddy
```

Install the config and **put your hostname on the first line**:

```bash
sudo cp ~/taskflow-src/deploy/Caddyfile /etc/caddy/Caddyfile && sudo nano /etc/caddy/Caddyfile
```

```bash
sudo mkdir -p /var/log/caddy && sudo chown caddy:caddy /var/log/caddy && sudo systemctl reload caddy
```

Caddy fetches a certificate on first request — give it a few seconds, then from
**your laptop** (use `curl.exe`, not `curl`, in PowerShell — the bare name is an
alias for `Invoke-WebRequest` and will just prompt you for a URI):

```bash
curl.exe -i https://taskflow-yourname.duckdns.org/groups
```

A `401` over `https://` with no certificate warning means you're done. If Caddy
struggles, `sudo journalctl -u caddy -n 50 --no-pager` usually names the cause
(DNS not propagated, or port 80 blocked in the security group).

---

## Step 7 — Point the app at it

This is the whole client-side change. In `TaskFlowApp-kotlin/local.properties`:

```properties
taskflow.baseUrl=https://taskflow-yourname.duckdns.org/
```

Then **Sync Project with Gradle Files** and **Clean Project**, then rebuild.
A plain rebuild is not enough: `buildConfigField` emits a `static final`
constant, the compiler inlines it into `RetrofitClient`, and an incremental
build can regenerate `BuildConfig.java` while leaving the compiled Kotlin
untouched — the APK then keeps the old URL. Reading `BuildConfig.java` will
not reveal this; check Logcat's `okhttp` output for the URL actually requested.

**No `network_security_config.xml` change.** `base-config` already permits
HTTPS to any host; the domain entries in that file exist only to carve out
*cleartext* exceptions. Nothing to add, nothing to weaken. The LAN placeholder
entry becomes dead weight — leave it or delete it, it changes nothing.

This same URL now works on the emulator and on a physical phone over mobile
data. The whole LAN-IP dance in the Android README becomes unnecessary.

Verify what got baked in:

```bash
grep BASE_URL app/build/generated/source/buildConfig/debug/com/taskflow/app/BuildConfig.java
```

---

## Redeploying after a code change

```bash
scp -i your-key.pem -r "…/task-manager-backend-GO" ubuntu@<elastic-ip>:~/taskflow-src
```

```bash
cd ~/taskflow-src && CGO_ENABLED=1 go build -o taskflow-server ./cmd/server && sudo systemctl stop taskflow && sudo install -o taskflow -g taskflow -m 755 taskflow-server /opt/taskflow/ && sudo systemctl start taskflow
```

The database in `/var/lib/taskflow` is untouched, and `migrate()` runs on every
start — including the idempotent `ALTER TABLE` for `tasks.updated_at`, so an
older database on the server is upgraded automatically.

---

## Backup / reset

```bash
sudo sqlite3 /var/lib/taskflow/taskmanager.db ".backup '/tmp/taskflow-backup.db'"
```

Start clean (destroys all data):

```bash
sudo systemctl stop taskflow && sudo rm -f /var/lib/taskflow/taskmanager.db* && sudo systemctl start taskflow
```

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `requires cgo to work` in `journalctl -u taskflow` | Built with `CGO_ENABLED=0`. Rebuild on the instance with `gcc` installed. |
| App: `CLEARTEXT communication not permitted` | `taskflow.baseUrl` is still `http://`. It must be `https://`. |
| App: `Network error: … CertPathValidatorException` | Caddy hasn't got a certificate yet, or you used a self-signed one. Check `journalctl -u caddy`. |
| Caddy can't issue a certificate | Port 80 closed in the security group, or DNS doesn't point at the Elastic IP yet. |
| Everything worked, now the IP changed | You skipped the Elastic IP. Allocate one and update DNS. |
| App still hits the old URL | `BASE_URL` is compile-time. Re-sync Gradle and rebuild. |

## Scope note

This is a course-project deployment: one instance, SQLite on local disk, no
load balancer, no automated backups, no log rotation beyond the defaults. That
is proportionate to the assignment. It is *not* what you'd run for real users —
SQLite on a single EBS volume has no redundancy, and there's no story for
restoring if the instance is terminated.
