#!/usr/bin/env bash
# Makes a new GPG key for signing the Maven Central releases, publishes its public half and hands the
# private half and its passphrase to the deploy workflow. Use it when the old key is lost or expired.
#
#   scripts/new-signing-key.sh                 make the key, publish it, set the two secrets
#   scripts/new-signing-key.sh 0.0.8 0.0.9     the same, then publish the Maven plugin of those versions
#
# It needs gpg, curl and a logged-in gh. The key is made in a throwaway keyring with a random
# passphrase and never touches your own keyring; afterwards it exists only in the repository's
# secrets. Losing it is harmless: run this again.
#
# From the environment: NAME and EMAIL (default: your git identity), EXPIRY (default: never),
# REPO, and DRY_RUN=1 to make the key and stop before anything is uploaded.
set -euo pipefail

REPO="${REPO:-flock-community/byterails}"
EXPIRY="${EXPIRY:-never}"
NAME="${NAME:-$(git config --global user.name 2>/dev/null || true)}"
EMAIL="${EMAIL:-$(git config --global user.email 2>/dev/null || true)}"
DRY_RUN="${DRY_RUN:-}"

fail() { echo "error: $*" >&2; exit 1; }

command -v gpg >/dev/null || fail "gpg is not installed (macOS: brew install gnupg)"
command -v curl >/dev/null || fail "curl is not installed"
[ -n "$NAME" ] && [ -n "$EMAIL" ] || fail "no name or email; run: NAME='Your Name' EMAIL=you@example.com $0"
if [ -z "$DRY_RUN" ]; then
  command -v gh >/dev/null || fail "gh is not installed (macOS: brew install gh)"
  gh auth status >/dev/null 2>&1 || fail "gh is not logged in; run: gh auth login"
fi

# A short path: gpg-agent's socket lives in it, and a socket path has a length limit.
export GNUPGHOME="$(mktemp -d /tmp/bkey.XXXXXX)"
chmod 700 "$GNUPGHOME"
cleanup() { gpgconf --kill all >/dev/null 2>&1 || true; rm -rf "$GNUPGHOME"; }
trap cleanup EXIT

PASSPHRASE="$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 40 || true)"
[ "${#PASSPHRASE}" -eq 40 ] || fail "could not make a passphrase"

# The passphrase goes in on a file descriptor, so no pinentry window is needed.
gpg_batch() { gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 3 "$@" 3<<<"$PASSPHRASE"; }

echo "Making a 4096-bit RSA signing key for $NAME <$EMAIL>, expiry: $EXPIRY"
gpg_batch --quick-generate-key "$NAME <$EMAIL>" rsa4096 sign "$EXPIRY"
FINGERPRINT="$(gpg --list-secret-keys --with-colons | awk -F: '$1 == "fpr" { print $10; exit }')"
[ -n "$FINGERPRINT" ] || fail "the key was not made"
echo "Fingerprint: $FINGERPRINT"

gpg --armor --export "$FINGERPRINT" > "$GNUPGHOME/public.asc"
gpg_batch --armor --export-secret-keys "$FINGERPRINT" > "$GNUPGHOME/private.asc"
grep -q "BEGIN PGP PRIVATE KEY BLOCK" "$GNUPGHOME/private.asc" || fail "the private key could not be exported"

if [ -n "$DRY_RUN" ]; then
  echo "DRY_RUN: the key works; nothing was uploaded and the key is thrown away."
  exit 0
fi

# Maven Central looks a signing key up on keyserver.ubuntu.com, among others.
echo "Publishing the public key to keyserver.ubuntu.com"
curl -fsS --data-urlencode "keytext@$GNUPGHOME/public.asc" https://keyserver.ubuntu.com/pks/add >/dev/null \
  || fail "could not publish the public key; nothing was changed in $REPO, so run this again"
# keys.openpgp.org also wants the address confirmed by e-mail; it is a second source, so not fatal.
curl -fsS -T "$GNUPGHOME/public.asc" https://keys.openpgp.org >/dev/null 2>&1 \
  || echo "  keys.openpgp.org did not take the key; keyserver.ubuntu.com is enough" >&2

echo "Setting the secrets GPG_PRIVATE_KEY and GPG_PASSPHRASE of $REPO"
gh secret set GPG_PRIVATE_KEY --repo "$REPO" < "$GNUPGHOME/private.asc"
printf '%s' "$PASSPHRASE" | gh secret set GPG_PASSPHRASE --repo "$REPO"

if [ "$#" -gt 0 ]; then
  # A key server needs a moment before Central can find a new key.
  echo "Waiting a minute for the key server before starting the deploys"
  sleep 60
fi
# The workflow builds main under the given version, so only name versions whose tag is what main holds.
for version in "$@"; do
  echo "Starting Deploy for the Maven plugin of $version"
  gh workflow run deploy.yml --repo "$REPO" -f version="$version" -f components=maven
done

echo "Done. New signing key: $FINGERPRINT"
[ "$#" -eq 0 ] || echo "Follow the runs with: gh run list --repo $REPO --workflow deploy.yml"
