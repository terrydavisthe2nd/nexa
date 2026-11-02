# Deploying Nexa

```
make build                     # pull, deploy server, then build clients
sudo bash deploy/install.sh    # the service (systemd on Linux, a LaunchDaemon on macOS) + tunnel ingress
```

`make build` first runs `git pull --ff-only`, then compiles and installs the Go
server. If `xyz.evilion.chat.service` exists in systemd, it restarts the service
(using sudo when needed) and checks that it is active before building the web
client, Android, and desktop in that order. Without that service, the restart
step is skipped. Android and desktop keep their existing change/version checks.
A failed pull, compile, or restart stops the build before clients are published.
`make build TEST=1` also runs the Go and web test suites before deploying.
`make server` still builds just the server without pulling or restarting.

`install.sh` is idempotent. It creates `chatd.env` (from the example), `data/`
and `logs/`, renders `deploy/xyz.evilion.chat.service.in` into
`/etc/systemd/system/` (Linux) or `deploy/xyz.evilion.chat.plist.in` into
`/Library/LaunchDaemons/` (macOS), starts the service, waits for
`/api/health`, adds `chat.evilion.xyz -> http://127.0.0.1:8090` to
`../server/tunnel/config.yml` (before the catch-all) and restarts the site
service so cloudflared reloads it.

## DNS (once, needs your Cloudflare account)

The tunnel credentials on this box can run the tunnel but cannot create DNS
records. One of:

- `cloudflared tunnel login` (opens a browser, select your domain), then
  `bash deploy/route-dns.sh`; or
- Cloudflare dashboard → DNS → add a proxied **CNAME** for your chat hostname
  pointing to `<YOUR_TUNNEL_ID>.cfargotunnel.com`. Use your own tunnel ID.

## Day to day

On Linux (systemd):

```
journalctl -u xyz.evilion.chat -f                       # the log
sudo systemctl restart xyz.evilion.chat                 # restart
sudo systemctl stop xyz.evilion.chat                    # stop (start brings it back)
make build                                             # pull and update server, then clients
bash backup.sh                                          # all of data/, keeping only the newest backup
```

On macOS (launchd):

```
tail -f logs/chatd.log
sudo launchctl kickstart -k system/xyz.evilion.chat    # restart
sudo launchctl bootout system/xyz.evilion.chat         # stop until next boot / bootstrap
sudo launchctl bootstrap system /Library/LaunchDaemons/xyz.evilion.chat.plist
make build && sudo launchctl kickstart -k system/xyz.evilion.chat   # update
bash backup.sh
```

`bin/chatd` is the file the service starts, so anything that writes it decides
what runs after the next restart — including a reboot nobody planned. `make
server` builds to a scratch name and moves it into place in one step, keeping
what it replaced as `bin/chatd.prev`; a bare `go build -o bin/chatd` does not,
and will deploy on the next restart without saying so. To check that the server
compiles, `go build ./...` writes nothing; `make run` runs it from source. `make
clean` leaves `bin/` and `web/dist` alone; `make clean-deploy DEPLOY=1` is the
only target that removes them.

To roll back, `cp bin/chatd.prev bin/chatd` and restart. A binary older than
the database it is pointed at (one from before a release that changed the
schema) refuses to start and says so, rather than serving a schema it does not
know: roll back to a build from that release or later.

Settings live in `chatd.env` (documented in `chatd.env.example`); restart after
editing. The database is `data/chat.db` (SQLite, WAL mode) and uploads live in
`data/media/`.

## The WARP proxy

Link previews, the media proxy and the YouTube player fetch whatever people link,
so they go out through Cloudflare WARP's local SOCKS5 proxy (`EGRESS_PROXY=warp`)
and the sites see Cloudflare's address instead of this machine's. WARP runs in
proxy mode, so the rest of the machine (the tunnel, SSH, updates) goes out as
usual. `deploy/warp-proxy.sh` installs the WARP client when it is missing (apt or
dnf on Linux, Homebrew or Cloudflare's package on macOS) and sets it up; the
installer and `make run` run it. chatd puts WARP back in proxy mode and reconnects
it at start and whenever its port stops answering, so nothing else needs to keep
it up. While it is down those requests fail rather than going direct; the log
says so.

```
bash deploy/warp-proxy.sh --check     # what WARP is doing now
warp-cli --accept-tos status          # connected?
warp-cli --accept-tos mode warp       # a laptop that wants its whole-machine WARP back
```

## Logs

Under systemd chatd writes to the journal, which rotates and caps itself. Under
launchd, which never rotates the file it appends a daemon's output to, chatd
writes `logs/chatd.log` itself (`LOG_FILE`, set in the plist): past 10 MB it
moves the log to `logs/chatd.log.1` (the older ones shift up to `.5`, and the
oldest goes) and starts a new one. What launchd itself still catches — anything
printed before the log is open, and a crash's stack trace — goes to
`logs/chatd.stderr.log`.

## Backups

`bash backup.sh` writes three files into `backup/`, named by date and time:

- `chat-STAMP.db`, a consistent copy of the database (SQLite's online backup,
  safe while chatd runs), checked before it counts;
- `secret-STAMP`, a copy of `data/server.secret`. **This is the file whose loss
  cannot be undone.** Every two-factor secret is sealed with it: a database
  restored without it signs nobody with two-factor sign-in in again, backup codes
  included, and chatd refuses to start on such a database instead of making a
  new secret that could never open theirs;
- `files-STAMP.tgz`, the rest of `data/`: every uploaded file, the desktop
  app's downloads, and the game and music pictures (not `media/tmp`, uploads
  still arriving, or `media/variants`, resized copies chatd makes again).

It fails loudly, and keeps nothing from the run, when any part could not be
written. Only the newest backup is kept: after a whole backup it deletes every
older one in `backup/` (including `media-STAMP.tgz` files from before the
archive held all of `data/`), and leaves anything else there alone. `backup/`
is on the same disk as the data, so it covers a bad deploy or a mistake, not a
dead disk: copy it somewhere else as well (`BACKUP_DIR=/mnt/elsewhere bash
backup.sh` writes there directly).
`chatd.env` holds Nexa's settings and keys (Cloudflare, Klipy,
Turnstile, hCaptcha); keep a copy of it somewhere safe too.

Never copy the live `data/chat.db` by hand: without its `-wal` file the copy
can be missing the newest writes, or be torn.

To restore, with chatd stopped:

```
cp backup/chat-STAMP.db data/chat.db && rm -f data/chat.db-wal data/chat.db-shm
cp backup/secret-STAMP data/server.secret && chmod 600 data/server.secret
tar xzf backup/files-STAMP.tgz -C data
```

then start chatd. If `server.secret` is really gone for good, the accounts that
had two-factor sign-in on cannot be signed into with it any more; chatd will
only start once their second factor is turned off (`DELETE FROM user_mfa;
DELETE FROM mfa_backup_codes;` in `sqlite3 data/chat.db`), after which they sign
in with their password alone and can set it up again.

## Desktop app

The desktop app (`desktop/`, Electron) is a window on the live site plus a
Discord-compatible Rich Presence server and an updater. Its installers are built
on a Mac and served by chatd from `DESKTOP_DIR` (default `data/desktop`) at
`/desktop/`; the download page (`/download`) and the banner read
`/api/v1/desktop/latest`.

```
# on the Mac: bump "version" in desktop/package.json first, then
make desktop            # desktop/dist: Windows .exe, macOS .dmg/.zip (Intel and Apple Silicon), Linux .AppImage/.deb
# copy desktop/dist into the server's checkout (rsync -a desktop/dist/ server:chat/desktop/dist/), then there
make publish-desktop    # copies them and the update manifests into data/desktop; no restart needed
```

Running apps find the new version within a minute and show "A new version is here" across the top (or at once from Settings →
Desktop App → Check for updates). Windows and the AppImage install it on restart;
the Mac build swaps itself in on restart (it is not signed, so macOS's own
updater cannot); a .deb only says a new version is out. Builds are unsigned:
Windows shows "Windows protected your PC" (More info → Run anyway), and macOS
wants System Settings → Privacy & Security → Open Anyway the first time; the
download page says so.

The Mac app reads `latest-mac-2.yml` from 1.1.0 on. Copies before that read
`latest-mac.yml`, which stays at their last release (1.0.5) because they cannot
update themselves to 1.1.0; the publish script leaves it and the files it names
alone. The page inside those copies offers the new version's disk image
(`web/src/lib/macReplacement.ts`); opened from there, the new app closes the old
one and moves into Applications in its place (`desktop/src/main/succession.ts`).

## Moving to Postgres

SQLite is the default and stays the right answer for one machine. Move when you
want the database kept by something else — a managed Postgres, a replica, a
database server you already back up.

```
sudo systemctl stop xyz.evilion.chat                   # Linux; on macOS: sudo launchctl bootout system/xyz.evilion.chat
go run ./cmd/chatmigrate --from data/chat.db --to 'postgres://chat@host/chat?sslmode=require'
```

`chatmigrate` warns if the SQLite database still looks in use — chatd has to be
stopped first, or the copy misses what it writes meanwhile. It copies every
table in one transaction and counts the rows on both sides before it says it is
done; nothing lands if anything fails. Then set `DATABASE_URL` to the same URL
in `chatd.env` and start chatd again (`sudo systemctl start xyz.evilion.chat`,
or `sudo launchctl bootstrap system /Library/LaunchDaemons/xyz.evilion.chat.plist`).
The SQLite file is left alone, so unsetting `DATABASE_URL` puts things back
exactly as they were. `backup.sh` copies SQLite only; back Postgres up with
`pg_dump`, and keep backing up `data/server.secret` with it.

Run one chatd, whichever database is behind it. Gateway events,
presence and calls live in the memory of the process holding the sockets, so
two processes behind one address would each see only their own half: a message
sent through one would never reach the people connected to the other.
`REDIS_URL` only moves the rate limits into Redis, where they survive a
restart; chatd warns at startup when `NODE_ID` is set, which is only ever
needed by a second process.

## Optional host maintenance notices

Instances have no reboot schedule or banner by default. `MAINTENANCE_FILE` opts a
chatd process into reading a host-written JSON file with `reboot_at` in Unix
milliseconds. `GET /api/v1/maintenance` returns that deadline, server time and the
150-minute warning window with `Cache-Control: no-store`. Missing or invalid files
return no deadline. Web, desktop and Android show a non-dismissible countdown in
the signed-in shell during that window, followed by a restart-pending notice if
the host is delayed. A network failure keeps the last known notice; a successful
response with no deadline clears it. Other instances are unaffected.

The optional `nexa-maintenance-*` systemd templates reboot the **whole host**, not
just chatd, 48 hours after boot. Manual reboots start a new 48-hour interval.
They wait up to an hour for apt/dpkg activity to finish; if still busy, skip that
reboot and try again 48 hours later. Do not enable these alongside another reboot
timer. Installation requires root and intentionally does not start the reboot
service directly:

```sh
sudo install -d /usr/local/lib/nexa
sudo install -m 755 deploy/maintenance-schedule.py deploy/safe-maintenance-reboot.sh /usr/local/lib/nexa/
sudo install -m 644 deploy/nexa-maintenance-schedule.service deploy/nexa-maintenance-reboot.service deploy/nexa-maintenance-reboot.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now nexa-maintenance-reboot.timer
```

Set `MAINTENANCE_FILE=/var/lib/nexa-maintenance/schedule.json` only in the intended
instance's private `chatd.env` before deploying it. The file is public schedule
metadata; chatd needs read access only. The boot publisher and the timer share the
same monotonic boot clock. After a skipped reboot the service publishes the next
48-hour deadline. To disable, stop and disable the timer and clear
`MAINTENANCE_FILE`; remove the schedule file to clear notices immediately.
`DRY_RUN=1 deploy/safe-maintenance-reboot.sh` exercises update checks without
rebooting. No SSH credentials belong in these files.
