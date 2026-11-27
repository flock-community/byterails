#!/usr/bin/env bash
# Renews the GPG key that signs the Maven Central releases and hands it to the deploy workflow.
#
#   scripts/renew-signing-key.sh                 extend the key by 2 years, publish it, update the secret
#   scripts/renew-signing-key.sh 0.0.8 0.0.9     the same, then publish the Maven plugin of those versions
#
# Run it on the machine that holds the private key. It needs gpg and a logged-in gh.
# KEY, EXPIRY and REPO can be overridden from the environment.
set -euo pipefail

KEY="${KEY:-600E010387CEEB55}"
EXPIRY="${EXPIRY:-2y}"
REPO="${REPO:-flock-community/byterails}"

command -v gpg >/dev/null || { echo "gpg is not installed" >&2; exit 1; }
command -v gh >/dev/null || { echo "gh is not installed" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh is not logged in; run: gh auth login" >&2; exit 1; }
gpg --list-secret-keys "$KEY" >/dev/null 2>&1 || { echo "no secret key $KEY in this keyring" >&2; exit 1; }

FINGERPRINT="$(gpg --list-secret-keys --with-colons "$KEY" | awk -F: '$1 == "fpr" { print $10; exit }')"

echo "Extending $FINGERPRINT and its subkeys by $EXPIRY (gpg may ask for the passphrase)"
gpg --quick-set-expire "$FINGERPRINT" "$EXPIRY"
gpg --quick-set-expire "$FINGERPRINT" "$EXPIRY" '*'
gpg --list-keys "$FINGERPRINT"

# Maven Central checks signatures against these servers; one that is down is not fatal.
for server in keyserver.ubuntu.com keys.openpgp.org; do
  echo "Publishing the public key to $server"
  gpg --keyserver "$server" --send-keys "$FINGERPRINT" || echo "  could not reach $server, continuing" >&2
done

echo "Updating the GPG_PRIVATE_KEY secret of $REPO (gpg may ask for the passphrase)"
gpg --armor --export-secret-keys "$FINGERPRINT" | gh secret set GPG_PRIVATE_KEY --repo "$REPO"

# The workflow builds main under the given version, so only name versions whose tag is what main holds.
for version in "$@"; do
  echo "Starting Deploy for the Maven plugin of $version"
  gh workflow run deploy.yml --repo "$REPO" -f version="$version" -f components=maven
done

echo "Done. The passphrase secret GPG_PASSPHRASE is unchanged."
[ "$#" -eq 0 ] || echo "Follow the runs with: gh run list --repo $REPO --workflow deploy.yml"
