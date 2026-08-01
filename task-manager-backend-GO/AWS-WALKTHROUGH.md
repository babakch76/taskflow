# TaskFlow on AWS — click-by-click

Every click and every command, in order, from an empty AWS account to the
Android app talking to your server over HTTPS.

Written for **Windows**. Commands marked *(on your PC)* run in PowerShell;
commands marked *(on the server)* run in the SSH session.

**Time:** 30–45 min, most of it waiting for downloads.
**Cost:** see [What this costs](#what-this-costs) before you start — it is not
zero.

Companion docs: [DEPLOYMENT.md](DEPLOYMENT.md) is the same thing as a terse
command reference. This file is the one to follow the first time.

---

## Part 0 — What this costs

Be aware of this before clicking:

- **The instance.** `t3.micro`/`t2.micro` is covered by the AWS Free Tier
  allowance for new accounts (a fixed number of hours per month for the first
  12 months). If your account is older than 12 months, or you already use those
  hours, you pay — roughly $7–9/month for a `t3.micro` left running.
- **The public IPv4 address.** Since February 2024 AWS charges for *every*
  public IPv4 address, about **$0.005/hour ≈ $3.60/month**, whether or not it's
  an Elastic IP and whether or not the instance is busy.
- **Free-tier terms have changed more than once recently.** Check the pricing
  page for your account rather than trusting any guide, including this one.

**When your demo is done, terminate the instance and release the Elastic IP** —
see [Part 9](#part-9--shut-it-down-when-youre-done). An idle instance keeps
billing. Set a billing alarm now if you haven't: search **Billing** in the
console → **Budgets** → **Create budget** → Zero-spend or a $5 monthly budget.

---

## Part 1 — Launch the EC2 instance

1. Sign in at **console.aws.amazon.com**.
2. **Top-right corner: check your region.** Pick one near you (e.g.
   `eu-south-1` Milan, or `eu-central-1` Frankfurt) and *keep it the same for
   everything that follows* — EC2 resources are region-scoped, and instances
   you create in one region are invisible from another. This is the single most
   common way people lose track of a running, billing instance.
3. In the top search bar type **EC2** → click **EC2** under Services.
4. Left sidebar → **Instances** → orange **Launch instances** button.

Now fill the form:

**Name and tags**
- **Name:** `taskflow-server`

**Application and OS Images**
- Click the **Ubuntu** tile in the Quick Start row.
- In the AMI dropdown pick **Ubuntu Server 24.04 LTS (HVM), SSD Volume Type**.
- Architecture: **64-bit (x86)**. *Not* Arm — the guide's Go download is amd64.
- Look for the **"Free tier eligible"** label.

**Instance type**
- **t3.micro** (or **t2.micro** if that's the one labelled free-tier eligible
  in your region).

**Key pair (login)**
- Click **Create new key pair**.
- Name: `taskflow-key`
- Type: **RSA**, Format: **.pem**
- Click **Create key pair** — the browser downloads `taskflow-key.pem`.
- **Move it somewhere you'll find again**, e.g. `C:\Users\babak\.ssh\`. You
  cannot download it a second time; lose it and you lose access to the
  instance.

**Network settings** — click **Edit** on the right.
- **Auto-assign public IP:** Enable
- **Firewall (security groups):** Create security group
- Security group name: `taskflow-sg`
- Now add three rules with **Add security group rule**:

| # | Type | Protocol | Port range | Source type | Source | Description |
|---|------|----------|-----------|-------------|--------|-------------|
| 1 | SSH | TCP | 22 | **My IP** | (auto-fills) | admin access |
| 2 | HTTP | TCP | 80 | Anywhere | `0.0.0.0/0` | Let's Encrypt challenge |
| 3 | HTTPS | TCP | 443 | Anywhere | `0.0.0.0/0` | the app |

> **Do not add a rule for port 8080.** The Go server will be bound to
> `127.0.0.1` and Caddy proxies to it. Opening 8080 would expose the
> unencrypted API to the internet — the exact hole the TLS setup exists to
> close. If you later can't connect, the fix is never "open 8080".

> **Rule 1 uses "My IP"** — your home IP. If it changes, or you demo from
> campus wifi, SSH stops working: come back here, **Security Groups** → edit
> the rule → **My IP** again.

**Configure storage**
- 8 GiB, **gp3**. Default is fine.

5. Right panel → **Launch instance**.
6. Click **View all instances**. Wait for **Instance state: Running** and
   **Status check: 2/2 checks passed** (~60 seconds).

---

## Part 2 — Elastic IP (do not skip)

Without this, stopping and starting the instance gives you a **new** IP, which
silently breaks your DNS record, your certificate, and your app config.

1. EC2 sidebar → under **Network & Security** → **Elastic IPs**.
2. **Allocate Elastic IP address** → leave defaults → **Allocate**.
3. Select the new address → **Actions** → **Associate Elastic IP address**.
4. Resource type: **Instance** → pick `taskflow-server` → **Associate**.
5. **Write the IP down.** Everything below calls it `<ELASTIC-IP>`.

---

## Part 3 — A hostname

Let's Encrypt will not issue a certificate for a bare IP address, and without a
certificate the Android app refuses to talk to the server at all. You need a
DNS name.

**If you own a domain:** add an **A record** pointing to `<ELASTIC-IP>`, then
skip to Part 4.

**If you don't — DuckDNS, free:**

1. Go to **duckdns.org**.
2. Sign in with GitHub/Google (you'll have to do this bit yourself).
3. In the **domains** box type a name, e.g. `taskflow-yourname` → **add domain**.
4. In its **current ip** field paste `<ELASTIC-IP>` → **update ip**.
5. Your hostname is now `taskflow-yourname.duckdns.org`. Everything below calls it
   `<YOUR-HOST>`.

Verify it resolves *(on your PC)* — do not continue until this returns your
Elastic IP:

```bash
nslookup taskflow-yourname.duckdns.org
```

---

## Part 4 — Connect over SSH

Windows 10/11 has OpenSSH built in; no PuTTY needed.

Open **PowerShell** and lock down the key file — OpenSSH refuses to use a key
that other Windows accounts can read, and this is the #1 first-time error
*(on your PC)*:

```bash
icacls "$env:USERPROFILE\.ssh\taskflow-key.pem" /inheritance:r /grant:r "$($env:USERNAME):(R)"
```

Then connect (substitute your IP) *(on your PC)*:

```bash
ssh -i "$env:USERPROFILE\.ssh\taskflow-key.pem" ubuntu@<ELASTIC-IP>
```

Type `yes` at the authenticity prompt. You should land at
`ubuntu@ip-172-…:~$`.

> **Stuck at "Connection timed out"?** Security group rule 1 doesn't match your
> current IP. EC2 → Security Groups → `taskflow-sg` → Inbound rules → Edit →
> set SSH source to **My IP** again.
>
> **"Permission denied (publickey)"?** Wrong username — Ubuntu AMIs use
> `ubuntu`, not `ec2-user` or `root`.

---

## Part 5 — Install Go and a C compiler

`go-sqlite3` is a CGO package, so the server genuinely needs a C compiler. This
is why we build here rather than cross-compiling from Windows — see the warning
in [DEPLOYMENT.md](DEPLOYMENT.md).

*(on the server)*

```bash
sudo apt-get update && sudo apt-get install -y build-essential
```

Ubuntu's packaged Go is usually too old (this needs 1.22+), so take the
official tarball:

```bash
curl -fsSL https://go.dev/dl/go1.22.5.linux-amd64.tar.gz | sudo tar -C /usr/local -xz
```

```bash
echo 'export PATH=$PATH:/usr/local/go/bin' >> ~/.bashrc && export PATH=$PATH:/usr/local/go/bin
```

Both of these must print a version:

```bash
go version && gcc --version | head -1
```

---

## Part 6 — Upload and build

Open a **second PowerShell window** on your PC (leave the SSH one open).

*(on your PC)* — the quotes matter, the path has spaces:

```bash
scp -i "$env:USERPROFILE\.ssh\taskflow-key.pem" -r "D:\0A-study-A0\0-ACSAI-0\sem 6\HCI\Antigravity workspace\task-manager-backend-GO" ubuntu@<ELASTIC-IP>:~/taskflow-src
```

Back in the **SSH window** *(on the server)*:

```bash
cd ~/taskflow-src && CGO_ENABLED=1 go build -o taskflow-server ./cmd/server
```

First build pulls dependencies and takes a minute or two. Now the check that
catches the classic mistake:

```bash
go version -m taskflow-server | grep CGO_ENABLED
```

It **must** print `CGO_ENABLED=1`. If it prints `0`, `gcc` wasn't found — go
back to Part 5. A `CGO_ENABLED=0` binary looks fine and then dies at startup
with `go-sqlite3 requires cgo to work`.

---

## Part 7 — Run it as a service

*(on the server)*

Dedicated unprivileged user:

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin taskflow
```

Directories — binary in `/opt`, database in `/var/lib` so redeploying can never
delete your data:

```bash
sudo mkdir -p /opt/taskflow /var/lib/taskflow /etc/taskflow && sudo install -o taskflow -g taskflow -m 755 ~/taskflow-src/taskflow-server /opt/taskflow/ && sudo chown taskflow:taskflow /var/lib/taskflow
```

Generate a real signing secret. The built-in default is in the source code —
leave it and anyone who has read this repo can forge a login token for any
account on your server:

```bash
printf 'JWT_SECRET=%s\n' "$(openssl rand -base64 48)" | sudo tee /etc/taskflow/taskflow.env > /dev/null && sudo chmod 600 /etc/taskflow/taskflow.env
```

Install and start the service:

```bash
sudo cp ~/taskflow-src/deploy/taskflow.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now taskflow
```

```bash
systemctl status taskflow --no-pager
```

Look for **`active (running)`**. Then smoke-test locally — this proves the API
works before TLS is involved:

```bash
curl -i http://127.0.0.1:8080/groups
```

Expect **`HTTP/1.1 401 Unauthorized`** and
`{"error":"missing or invalid authorization header"}`. That is the correct
answer: the route is alive and refusing unauthenticated access.

> Anything else? `sudo journalctl -u taskflow -n 50 --no-pager` will say why.

---

## Part 8 — HTTPS with Caddy

Caddy fetches and renews a Let's Encrypt certificate by itself. Android trusts
Let's Encrypt out of the box, so the app needs no certificate configuration at
all.

*(on the server)* — four commands to install:

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

Copy the config in:

```bash
sudo cp ~/taskflow-src/deploy/Caddyfile /etc/caddy/Caddyfile && sudo nano /etc/caddy/Caddyfile
```

In the `nano` editor, change the **first line** from
`taskflow-CHANGE-ME.duckdns.org {` to your real hostname, e.g.
`taskflow-yourname.duckdns.org {`. Leave the rest alone.
Save with **Ctrl+O**, **Enter**, then exit with **Ctrl+X**.

```bash
sudo mkdir -p /var/log/caddy && sudo chown caddy:caddy /var/log/caddy && sudo systemctl reload caddy
```

Wait ~15 seconds for the certificate, then test from **your PC** *(on your PC)*:

```bash
curl.exe -i https://taskflow-yourname.duckdns.org/groups
```

> **The `.exe` is required.** PowerShell aliases bare `curl` to its own
> `Invoke-WebRequest`, which does not understand `-i` and will sit there
> prompting `Supply values for the following parameters: Uri:`. Press Ctrl+C
> and use `curl.exe`. Commands run *on the server* use plain `curl` — that's
> the real thing.

**A `401` over `https://` with no certificate warning means the server is
done.** Also open that URL in your browser — you should see a padlock.

> **Certificate not issued?** `sudo journalctl -u caddy -n 50 --no-pager`
> usually names it: DNS not propagated yet (wait, re-check `nslookup`), or
> port 80 blocked in the security group (it's needed for the ACME challenge,
> even though your traffic is on 443).

---

## Part 9 — Point the app at it

One line, in `TaskFlowApp-kotlin/local.properties` on your PC:

```properties
taskflow.baseUrl=https://taskflow-yourname.duckdns.org/
```

Then in Android Studio: **File → Sync Project with Gradle Files**, then
**Build → Clean Project**, then **Run ▶**.

> **The Clean is not optional, and this one is genuinely nasty.**
> `buildConfigField` generates `public static final String BASE_URL`, and the
> compiler **inlines** constants like that straight into `RetrofitClient` at the
> call site. An incremental build happily regenerates `BuildConfig.java` with
> the new URL while leaving `compileDebugKotlin` up-to-date — so the *old* URL
> stays burned into the compiled code and the app keeps calling the old server.
>
> This means **inspecting `BuildConfig.java` does not prove anything.** It will
> show the new URL while the APK still contains the old one. Verify at runtime
> instead (below).

Verify what the app is *actually* calling — filter Logcat by `okhttp` and watch
a request go out. From the command line *(on your PC)*:

```bash
C:\Users\babak\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -d | findstr "okhttp"
```

You want lines like
`--> POST https://taskflow-yourname.duckdns.org/auth/register` and `<-- 201`.
If you see `10.0.2.2` there, the Clean didn't happen — do it and rebuild.

**No `network_security_config.xml` change is needed.** That file's `<domain>`
entries exist only to carve out *cleartext* exceptions; `base-config` already
permits HTTPS to any host. The `192.168.1.42` placeholder is now dead weight —
leave it or delete it, it makes no difference.

**Now register an account in the app.** It hits AWS. The same APK works on a
physical phone over mobile data with no further changes — that's the payoff.

---

## Part 10 — Shut it down when you're done

Billing continues until you do this.

**Pausing between demos** (keeps data, still pays ~$3.60/mo for the IP):
EC2 → Instances → select → **Instance state** → **Stop instance**.

**Finished with the project** (deletes everything):

1. EC2 → Instances → select → **Instance state** → **Terminate instance**.
2. EC2 → **Elastic IPs** → select → **Actions** → **Release Elastic IP
   addresses**. *An allocated-but-unassociated Elastic IP still bills.*
3. Check the region selector and repeat if you created things in more than one.

---

## Redeploying after a code change

*(on your PC)*

```bash
scp -i "$env:USERPROFILE\.ssh\taskflow-key.pem" -r "D:\0A-study-A0\0-ACSAI-0\sem 6\HCI\Antigravity workspace\task-manager-backend-GO" ubuntu@<ELASTIC-IP>:~/taskflow-src
```

*(on the server)*

```bash
cd ~/taskflow-src && CGO_ENABLED=1 go build -o taskflow-server ./cmd/server && sudo systemctl stop taskflow && sudo install -o taskflow -g taskflow -m 755 taskflow-server /opt/taskflow/ && sudo systemctl start taskflow
```

Your database is untouched, and `migrate()` runs on every start — including the
idempotent `ALTER TABLE` for `tasks.updated_at` — so an older database on the
server upgrades itself.

---

## Quick reference

| What | Where |
|---|---|
| Server logs | `sudo journalctl -u taskflow -f` |
| Caddy logs | `sudo journalctl -u caddy -f` |
| Restart API | `sudo systemctl restart taskflow` |
| Database file | `/var/lib/taskflow/taskmanager.db` |
| Secret | `/etc/taskflow/taskflow.env` |
| Back up the DB | `sudo sqlite3 /var/lib/taskflow/taskmanager.db ".backup '/tmp/backup.db'"` |
| Wipe and start fresh | `sudo systemctl stop taskflow && sudo rm -f /var/lib/taskflow/taskmanager.db* && sudo systemctl start taskflow` |
