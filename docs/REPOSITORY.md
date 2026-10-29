# Maintaining the public repository

`terrydavisthe2nd/nexa` is the canonical source repository. Use one source history
for development and production; instance configuration, data, builds and signing
material belong to each installation and are ignored by Git. There is no need to
maintain a second private version of the source.

## Pulling updates on an existing installation

After the one-time migration to the cleaned history, use `git pull --ff-only` as
usual. Pulling source does not restart the service. Build and deploy explicitly,
following [the deployment guide](../deploy/README.md). `make web server` builds
the web app and server; `make build` also publishes Android releases, while the
desktop app is built and released by GitHub Actions from a `desktop-v*` tag.

Keep `chatd.env`, `data/`, `bin/`, `web/dist`, `downloads/android` and local Android
signing files in place. Never use `git clean -fdx` on a deployment: it removes
ignored configuration and data. Back up the database, `data/server.secret`,
uploads, configuration and Android signing material privately.

History rewrites change commit IDs. An old clone must migrate once or be replaced
with a fresh clone; merging its old history into the cleaned repository would
bring removed material back. Keep any recovery bundle outside the repository,
private and offline. Do not push old branches or bundles back to GitHub.

## Contributions and privacy checks

Set a public handle and the noreply address shown in your GitHub email settings
before committing. Preserve contributor attribution and third-party license
notices. Public Nexa domains, branding and published support contacts are
intentional; private keys, personal screenshots and local machine paths are not.

Before committing, stage the intended changes and check them:

```sh
python3 tools/privacy_check.py
git diff --cached --check
```

Before publishing, scan every reachable branch and tag with Gitleaks 8.30.1 or
later, as CI does:

```sh
python3 tools/privacy_check.py --history
gitleaks git --redact --log-opts="--all --full-history" .
```

The Gitleaks configuration exempts only reviewed synthetic cryptographic test
data. Do not add real credentials to an allowlist. If a credential is published,
revoke or rotate it and remove it from history. GitHub cached commit views and
other people's clones may also need cleanup; see
[GitHub's sensitive-data removal guide](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository).

Agent guidance and plans stay in the repository when they contain project
information. Keep private handoffs, chat transcripts, production logs and personal
reference captures outside the checkout. Android release signing is documented
in [android/signing/README.md](../android/signing/README.md).
