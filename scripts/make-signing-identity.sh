#!/usr/bin/env bash
# One-time: creates the "ZenTask Self-Signed" code-signing identity in the login keychain
# and backs it up to ~/.config/zentask-signing/. Never commit that folder.
source "$(dirname "$0")/lib/env.sh"

NAME="ZenTask Self-Signed"
DIR="$HOME/.config/zentask-signing"
OPENSSL=/usr/bin/openssl   # LibreSSL: its .p12 (3DES/SHA1) is what `security import` accepts

security find-identity -p codesigning | grep -q "\"$NAME\"" && die "'$NAME' already exists in the keychain"
[[ -e "$DIR/zentask-signing.p12" ]] && die "$DIR/zentask-signing.p12 exists; import it instead of creating a new identity"

mkdir -p "$DIR" && chmod 700 "$DIR"
cat > "$DIR/cert.cnf" <<CNF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $NAME
[ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
CNF

step "Creating certificate"
"$OPENSSL" req -x509 -newkey rsa:2048 -days 3650 -nodes -config "$DIR/cert.cnf" \
    -keyout "$DIR/key.pem" -out "$DIR/cert.pem" 2>/dev/null
PASS="$("$OPENSSL" rand -base64 24)"
printf '%s' "$PASS" > "$DIR/p12-password.txt"
"$OPENSSL" pkcs12 -export -inkey "$DIR/key.pem" -in "$DIR/cert.pem" -name "$NAME" \
    -out "$DIR/zentask-signing.p12" -passout "pass:$PASS"
rm -f "$DIR/key.pem"
chmod 600 "$DIR"/*

step "Importing into the login keychain"
security import "$DIR/zentask-signing.p12" -k "$HOME/Library/Keychains/login.keychain-db" \
    -P "$PASS" -T /usr/bin/codesign >/dev/null

SHA1="$("$OPENSSL" x509 -in "$DIR/cert.pem" -noout -fingerprint -sha1 | sed 's/.*=//; s/://g' | tr 'A-F' 'a-f')"
echo "    identity: $NAME"
echo "    sha1:     $SHA1   (put this in RELEASE_CERT in scripts/release.sh)"
