# How Nexa's encryption works

Nexa encrypts personal messages, attachments, shared notes and call media on the
sender's device and decrypts them on recipients' devices. This is end-to-end
encryption (E2EE). The server delivers and stores encrypted content, along with
the metadata needed to run the service.

The server also stores encrypted copies of account and conversation keys. Those
copies normally cannot be used by the server: opening them requires a secret
held by a user's client. There are explicit exceptions for content shared with
bots and apps, described below.

This document explains the implementation in this repository and its trust
boundaries. It is not an independent security audit or proof that a particular
hosted instance is running the same code. The [privacy policy](docs/legal/PRIVACY_POLICY.txt)
describes the service's broader data collection and retention practices.

## What is encrypted, and what is visible

| Data | Protection and visibility |
| --- | --- |
| Personal message text, attachment details and poll questions/options | Encrypted by the sending client. Recipients with the conversation keys can read them. |
| Personal attachments and their previews | Encrypted before upload, with separate file keys carried inside the encrypted message. |
| Shared notes | Note content and collaborative updates are encrypted with a note key shared with its members. |
| Call audio, video and screen sharing | Protected between clients; relayed calls also encrypt media frames above the WebRTC transport layer. |
| Account settings and local message search | Settings are encrypted by the client. Message search runs locally over decrypted messages, with an encrypted persistent index on the web client. |
| Delivery metadata | Visible to the server, including account/channel identifiers, message authors, timestamps, mentions, reactions, file sizes and poll voting data. |
| Public profiles and community information | Visible to the server, including avatars, server names, membership, roles, custom emoji and stickers. |
| Webhook/app messages, app interactions, bug reports and moderation conversations | Not end-to-end encrypted. Server-generated notices are also readable by the server. |

E2EE protects content; it does not make accounts or conversations anonymous.
Transport encryption through HTTPS is still needed to protect credentials,
metadata and the delivery of the web client.

## Account keys and passwords

When an account is created, the client generates a random X25519 identity key
pair. The public key can be shared. The private key opens conversation keys
addressed to that account and must remain secret.

The client stretches the password with Argon2id and an account-specific random
salt. New accounts use 64 MiB of memory, three iterations and one lane. HKDF-SHA-256
then derives two separate values from the result:

1. An **authentication key**, sent to the server to sign in. The server stores a
   further Argon2id hash of it.
2. An **encryption key**, used locally to encrypt, or “wrap,” the identity private
   key with AES-256-GCM. This encryption key is not sent to the server.

The password itself is not sent by the account sign-in flow. Knowing the
authentication key does not directly reveal the encryption key: HKDF uses
different labels for the two derivations. The client also checks password KDF
parameters before using them, rejecting parameters outside its accepted range.

The server stores the public key, salt, KDF parameters and wrapped private key.
On another device, the client downloads the wrapped private key and uses the
password to open it locally. Devices therefore share the account's identity;
they do not each have an independent identity key pair.

**Can the server use its stored private-key copy?** The wrapped copy alone does
not let it decrypt messages. It would need the password-derived encryption key,
the recovery secret, an exposed private key, or another route to the content.
A guessed password can be checked against stored data, so password strength
still matters. Argon2id makes guesses more expensive; it does not make a weak
password impossible to guess.

Implementation: [password derivation](web/src/crypto/kdf.ts),
[identity generation and wrapping](web/src/crypto/keys.ts),
[client registration and sign-in](web/src/stores/auth.ts),
[server authentication](internal/api/auth_handlers.go) and
[server credential hashing](internal/auth/password.go).

## Where usable keys live, including on the web

On the web, cryptography runs in the browser. AES-GCM operations use WebCrypto;
X25519 and HKDF use the Noble libraries, and Argon2id uses `hash-wasm`.

Without an app passcode, the web client persists the unwrapped identity private
key and conversation root keys in IndexedDB, in a database named `evilion-chat`
inside the browser profile. This allows a reload to restore the session without
asking for the password again. These key records are **not encrypted by Nexa at
rest**. Browser isolation and operating-system security protect access to them;
they are not hardware-backed private keys. The app also holds usable keys in
memory while unlocked.

Enabling an app passcode wraps the local identity private key with a
passcode-derived key and removes the unwrapped identity and cached roots from
IndexedDB. The wrapped identity record is kept in browser local storage. After
unlocking, the client can open the encrypted conversation grants again. The
passcode KDF uses Argon2id with 64 MiB, six iterations and one lane.

The lock screen counts wrong attempts, but someone who copies browser storage
can guess offline without that counter. A longer, less predictable passcode
provides more protection. An app lock also does not protect against malicious
code running while the account is unlocked. Existing calls can continue while
the app is locked, so locking is not a promise that every media key has been
erased from memory.

Signing out clears the account's cached keys. Clearing browser data removes the
local copy, but signing in again can restore it from the server's encrypted
backup. Neither action revokes keys or content already copied elsewhere.

The desktop app runs this same web client in a Tauri window, so its keys and
lock are the web client's. Native Android implements the same cryptographic
formats, with its own device storage and app-lock implementation; the IndexedDB
description above applies to the web client.

Implementation: [web keystore](web/src/crypto/keystore.ts),
[web app lock](web/src/lib/appLock.ts),
[authentication lifecycle](web/src/stores/auth.ts),
[conversation key caching](web/src/stores/keys.ts) and
[Android app lock](android/app/src/main/java/fish/nexa/data/AppLock.kt).

## How a message reaches its recipients

Each conversation has a random 256-bit **root key** for each numbered key period,
called an **epoch**. A conversation may represent a DM, group chat, a server's
shared channels or a private channel with its own keys. HKDF derives each
channel's message key from the root and the channel ID.

To give a member the root, a client encrypts it for that member's identity public
key. This **key grant** uses a fresh ephemeral X25519 key pair, HKDF-SHA-256 and
AES-256-GCM. The server stores and delivers the sealed grant. The member's client
uses its identity private key to open it.

The sender encrypts the message payload with AES-256-GCM and a fresh random
12-byte nonce. The conversation, epoch, channel and author identifiers are bound
into the authentication data. Changing that context or altering the ciphertext
causes authentication to fail. Recipients derive the same channel key and decrypt
locally.

This authentication detects tampering by someone without the key. Because
conversation members share symmetric keys, it is not an independent digital
signature proving which member authored a message.

Implementation: [identity and sealed boxes](web/src/crypto/keys.ts),
[conversation/channel derivation](web/src/crypto/conv.ts),
[message envelopes](web/src/crypto/message.ts) and
[grant delivery](internal/api/key_handlers.go).

## History, new members and key rotation

An online member's client can fulfill requests for missing key grants. This
includes previous epochs, so new members can receive earlier conversation
history. If no available client holds the needed key, the recipient waits for a
member to share it. The server cannot reconstruct a missing root from ciphertext.

Some invite links carry a root key in the URL fragment, after `#`. Browsers do
not send that fragment in the HTTP request to the server. The client reads it
locally and can create its own sealed grant. The complete link is therefore a
secret: anyone who obtains it obtains the key it carries, even though access to
the service still involves membership checks.

When membership shrinks, the server requests rotation and an online client
generates a new root for a new epoch. New messages and edits must use the current
epoch; the server rejects stale ones. Rotation protects later content once the
new epoch is established. It cannot take back old keys, downloads, exports or
screenshots from a former member.

Nexa uses epoch keys rather than a per-message ratchet. It does **not** provide
ratchet-based forward secrecy: exposure of an identity private key and its stored
grants can expose the historical roots those grants contain. Changing a password
rewraps the existing identity key; it does not replace that identity or revoke a
copy an attacker already obtained.

Implementation: [client key lifecycle](web/src/stores/keys.ts),
[key API](internal/api/key_handlers.go) and
[password changes](web/src/stores/auth.ts).

## Files, notes, calls and search

Personal attachments are encrypted with a random key per file. The filename,
type and decryption key travel inside the encrypted message. Large files use
authenticated chunks so clients can decrypt downloads and video progressively.
The reader checks chunk authentication and the expected plaintext size to reject
tampering and truncated files. The server still sees upload sizes and delivery
metadata.

Shared notes have their own keys, sealed to each member's identity. Share links
carry a separate secret in the URL fragment that opens a wrapped copy of the note
key. Treat the complete link as access-bearing information.

For calls through a selective forwarding unit (SFU), ordinary WebRTC transport
encryption ends at the relay. Nexa additionally encrypts the media frames with a
conversation-derived call key, separate from the message key. The relay can route
frames without opening their media payload. Routing information and some codec
headers remain visible. Guest call links carry call-key material in their
fragment. Peer-to-peer connections can reveal participants' IP addresses to each
other; E2EE does not hide network addresses.

Message search runs on the device. On the web, the persistent search index is
encrypted with a random local key, wrapped under a key derived from the account
identity. Without an app passcode, its practical protection is limited by the
unwrapped identity stored alongside it. Data exports and downloaded decrypted
files are readable copies, and need protection outside Nexa.

Implementation: [file chunks](web/src/crypto/fileChunks.ts),
[streaming decryption](web/src/crypto/streamDecrypt.ts),
[notes](web/src/crypto/notes.ts),
[call frames](web/src/voice/frameCrypto.ts),
[call-key derivation](web/src/crypto/conv.ts) and
[search keys](web/src/search/keys.ts).

## Bots and apps change who can read content

A message directed to a bot, such as a DM, mention, reply or message-command
target, can include a copy sealed for that bot. Nexa's server holds the bot's
private key encrypted with a server-controlled secret. It can open that copy and
hand its content and files to the bot. Encryption with a server-controlled secret
does not keep that content secret from the server.

A bot with the **Message Content** intent receives conversation keys for the
servers it belongs to, and the server decrypts messages for it in channels the
bot can see, including accessible history. The consent flow describes this
access, and the start of affected channels names reading apps. Such a channel's
content cannot be described as hidden from the server while it has usable bot
keys for it.

Webhook and app messages, commands, button interactions and form submissions are
also readable by the server and the receiving app. Consider an app's permissions
and its operator before sharing sensitive content with it.

Implementation: [bot access documentation](docs/bots.md#what-your-bot-can-read),
[sender-created bot copies](web/src/lib/botCopies.ts) and
[server bot decryption](internal/api/bot_crypto.go).

## Checking identities and understanding server trust

Clients remember identity public keys when first seen and flag later changes.
The web client withholds new key grants to a changed key until the change is
accepted. Safety numbers let people compare identity fingerprints through an
independent trusted channel, such as in person. A first-seen key is not already
verified, and a fresh browser profile has no previous local pins to compare.

Clients also compare proposed key recipients against known membership. These
checks have limits: membership and permissions ultimately come from the server.
When the web client lacks a complete member list, it permits the requested grants
and records that they could not be checked. The checks are not a cryptographic
guarantee against a malicious server changing membership or permissions.

The web client is delivered by the server. A compromised operator or delivery
chain could serve modified JavaScript that reads passwords, keys or plaintext
while the app is used. Browser extensions, malware and compromised recipients
can also expose content. Reviewing source helps assess the design, but does not
by itself prove that a hosted client or installed update matches that source.

These boundaries distinguish protection against reading stored ciphertext from
protection against an actively malicious service. Nexa's stored encrypted keys
are not a universal server decryption key, but E2EE still depends on trustworthy
client software, secret credentials and the people or apps receiving keys.

Implementation: [key pins](web/src/lib/keyPins.ts),
[safety numbers](web/src/crypto/fingerprint.ts),
[recipient membership checks](web/src/lib/convMembers.ts) and
[grant handling](web/src/stores/keys.ts).

## Recovery and implementation review

Registration provides a random 256-bit recovery secret. Separate authentication
and encryption values are derived from it, and the server stores a second copy
of the identity private key wrapped under the recovery encryption key. Recovery
opens that copy on the client, wraps the same identity under the new password and
issues a fresh recovery secret. Keep the recovery secret private: it can recover
access to the account and its encrypted history.

If you lose the password, recovery secret and every usable local key copy, the
server's encrypted backups alone cannot restore readable history.

The repository includes crypto unit tests, malformed/tampered ciphertext checks,
web/Android compatibility vectors and native/web call-frame checks. They are
useful review material, but passing tests is not an independent audit of the
protocol or deployed service. Review the code links above and the
[original encryption design](docs/superpowers/specs/2026-09-11-chat-design.md#6-end-to-end-encryption)
alongside this explanation; where they differ, the running implementation
determines actual behavior.
