GO ?= go
NODE_BIN ?= /usr/local/lib/nodejs/current/bin
VERSION ?= $(shell git describe --always --dirty 2>/dev/null || echo dev)
export PATH := $(NODE_BIN):$(PATH)

.PHONY: build build-current restart-service web server android desktop-release test test-go test-web test-desktop desktop publish-android licenses run clean clean-deploy

# Re-read the Makefile after pulling so this build uses the fetched instructions.
# Separate recursive makes enforce deployment order even with make -j.
build:
	git pull --ff-only
	$(MAKE) build-current

# A build runs the typechecker (inside the web build) but not the test
# suites unless asked with `make build TEST=1`. Run those before deploying.
build-current:
ifeq ($(TEST),1)
	$(MAKE) test-go test-web
endif
	$(MAKE) server
	$(MAKE) restart-service
	$(MAKE) web
	$(MAKE) android
	$(MAKE) desktop-release

# Only systemd installations restart automatically. Missing services and hosts
# without systemd skip this step; a failed restart stops the client builds.
restart-service:
	@if command -v systemctl >/dev/null 2>&1; then \
		state=$$(systemctl show xyz.evilion.chat.service -p LoadState --value 2>/dev/null) || state=; \
	else state=; fi; \
	if [ -z "$$state" ] || [ "$$state" = not-found ]; then \
		echo "  systemd service xyz.evilion.chat not found; skipping restart"; \
	else \
		if [ "$$(id -u)" = 0 ]; then \
			systemctl restart xyz.evilion.chat.service; \
		else \
			sudo systemctl restart xyz.evilion.chat.service; \
		fi && systemctl is-active --quiet xyz.evilion.chat.service; \
	fi

# The Android app, published into downloads/android, which chatd serves: the
# app's updater offers it to every phone at once. Built only when something the
# app is made from changed since the published one, numbered after it, signed
# with the key kept in android/signing.
android:
	python3 tools/android_release.py --if-changed

# The desktop app is a Tauri app now. Its installers for Windows, Linux and
# both Macs are built and published by .github/workflows/desktop.yml when a
# `desktop-v*` tag is pushed; nothing here writes a live download.
desktop-release:
	@echo "  desktop releases are built by GitHub Actions (.github/workflows/desktop.yml)"

# `web/dist` is what chatd serves, so this target *is* the deploy. DEPLOY=1 is
# how the build says it knows that; see web/scripts/deploy-guard.mjs.
web:
	cd web && npm ci --no-audit --no-fund && DEPLOY=1 npm run build

# `bin/chatd` is the file launchd starts, so writing it is the deploy — a
# reboot after any `go build -o bin/chatd` starts whatever was left there. The
# build goes to a scratch name and is moved into place in one step, keeping the
# one it replaces as bin/chatd.prev to roll back to.
server:
	$(GO) build -trimpath -ldflags "-s -w -X main.version=$(VERSION)" -o bin/chatd.next ./cmd/chatd
	@if [ -f bin/chatd ]; then cp -f bin/chatd bin/chatd.prev; fi
	@mv -f bin/chatd.next bin/chatd
	@echo "  bin/chatd is now $(VERSION); the one it replaced is bin/chatd.prev"

# The dev frontend. The prod build writes web/dist, which prod serves live —
# every build gets a fresh build id (vite.config.ts), so building it for dev
# would show every prod tab an update prompt. This builds the same app into
# web/dist-dev instead, which only the dev server serves.
web-dev:
	cd web && npm ci --no-audit --no-fund && npm run typecheck && npx vite build --outDir dist-dev --emptyOutDir

test: test-go test-web test-desktop

# -race makes the api package take about three minutes on an idle machine and
# rather more on a busy one; Go's default is ten per package, which this has
# hit while other things were building.
test-go:
	$(GO) vet ./... && $(GO) test -race -timeout 20m ./...

test-web:
	cd web && npm run typecheck && npm test -- --run

# The desktop app's source is not in every checkout; the rest of the suite
# still runs without it.
test-desktop:
	@if [ -d desktop/src-tauri ]; then cd desktop/src-tauri && cargo test; else echo "  desktop/ is not here; skipping its tests"; fi

# A local desktop build into desktop/src-tauri/target. It signs no updater
# artifacts; releases are made by the workflow above.
desktop: test-desktop
	cd desktop/src-tauri && npx --yes @tauri-apps/cli@2.12.4 build --config tauri.nosign.conf.json

# builds and publishes the Android app's release whether or not it changed
# (see downloads/android/README.md)
publish-android:
	python3 tools/android_release.py

# what Settings → Licenses shows, read back out of the lockfiles. Run it after any
# dependency changes, in any of the three builds.
licenses:
	python3 tools/licenses.py

# Runs the server from source. It used to build bin/chatd first, which is the
# deploy (see server above): trying the server out replaced the live binary.
# WARP's local proxy carries link previews and the media proxy; the script
# installs the WARP client first on a machine that has never had it
run:
	bash deploy/warp-proxy.sh
	$(GO) run ./cmd/chatd

# Dev server binary. Unlike `server` above this never touches bin/chatd
# (what prod runs), so building for dev can't change what prod restarts into.
server-dev:
	$(GO) build -trimpath -ldflags "-s -w -X main.version=$(VERSION)" -o bin/chatd_dev ./cmd/chatd
	@echo "  bin/chatd_dev is now $(VERSION)"

# Dev site behind dev.nexa.fish (tunnel ingress points it at :6969): builds
# both the web app (into web/dist-dev, leaving prod's web/dist alone) and the
# dev server binary, then runs it against a separate DATA_DIR so it never
# touches the live database on :8090.
run-dev: web-dev server-dev
	LISTEN=127.0.0.1:6969 PUBLIC_URL=https://dev.nexa.fish DATA_DIR=./data-dev WEB_DIST=./web/dist-dev ./bin/chatd_dev -env chatd.env

# What builds leave lying around, and nothing else. bin/ and web/dist are not
# leftovers: they are what is running (and bin/chatd.prev the way back), so one
# reflexive `make clean` used to take the site down with no way back short of
# a full rebuild. `make clean-deploy DEPLOY=1` still removes them, on purpose.
clean:
	rm -rf chatmigrate tools/out web/node_modules/.vite

clean-deploy:
	@echo "  this removes the running deploy: bin/ (chatd and the chatd.prev rollback copy) and web/dist"
	@if [ "$(DEPLOY)" != 1 ]; then echo "  nothing removed; run it as: make clean-deploy DEPLOY=1"; exit 1; fi
	rm -rf bin web/dist
