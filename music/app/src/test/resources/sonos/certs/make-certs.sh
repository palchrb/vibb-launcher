#!/bin/sh
# Generates the socket trust test certificates into $1 (absolute); the keys go to $2 (absolute) and are thrown away.
set -eu
OUT=$1
K=$2
UDN=RINCON_00000000000001400
key() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$K/$1.key" 2>/dev/null; }
ca_ext() { printf 'basicConstraints=critical,CA:TRUE%s\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid\n' "$1"; }
leaf_ext() { printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth,clientAuth\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid\n%s\n' "$1"; }
san() { printf 'subjectAltName=URI:urn:sonos:hhid:Sonos_TEST,URI:urn:sonos:udn:%s,DNS:sonos-000000000000.local' "$1"; }
selfsigned() { # name cn ext
  key "$1"
  printf '[req]\ndistinguished_name=dn\n[dn]\n[v3]\n%s\n' "$3" > "$K/$1.cnf"
  openssl req -x509 -new -key "$K/$1.key" -subj "/O=Vibb Test/OU=Test Devices/CN=$2" -not_before 20200101000000Z \
    -not_after 21200101000000Z -extensions v3 -config "$K/$1.cnf" -out "$K/$1.raw"
  openssl x509 -in "$K/$1.raw" -out "$OUT/$1.pem"
}
sign() { # name cn issuer ext notBefore notAfter
  key "$1"
  openssl req -new -key "$K/$1.key" -subj "/O=Vibb Test/OU=Test Devices/CN=$2" -out "$K/$1.csr"
  printf '%s\n' "$4" > "$K/$1.ext"
  openssl x509 -req -in "$K/$1.csr" -CA "$OUT/$3.pem" -CAkey "$K/$3.key" -set_serial "0x$(openssl rand -hex 8)" \
    -not_before "$5" -not_after "$6" -extfile "$K/$1.ext" -out "$K/$1.raw" 2>/dev/null
  openssl x509 -in "$K/$1.raw" -out "$OUT/$1.pem"
}
selfsigned test_root "Vibb Test Root" "$(ca_ext '')"
selfsigned other_root "Vibb Other Test Root" "$(ca_ext '')"
sign test_intermediate "Vibb Test Intermediate" test_root "$(ca_ext ',pathlen:0')" 20200101000000Z 21200101000000Z
sign leaf_good "TEST00000000" test_root "$(leaf_ext "$(san $UDN)")" 20200101000000Z 21200101000000Z
sign leaf_wrong_udn "TEST00000000" test_root "$(leaf_ext "$(san RINCON_00000000000001401)")" 20200101000000Z 21200101000000Z
sign leaf_no_san "TEST00000000" test_root "$(leaf_ext '')" 20200101000000Z 21200101000000Z
sign leaf_dns_only "TEST00000000" test_root "$(leaf_ext 'subjectAltName=DNS:sonos-000000000000.local')" 20200101000000Z 21200101000000Z
sign leaf_expired "TEST00000000" test_root "$(leaf_ext "$(san $UDN)")" 20200101000000Z 20210101000000Z
sign leaf_other_root "TEST00000000" other_root "$(leaf_ext "$(san $UDN)")" 20200101000000Z 21200101000000Z
sign leaf_via_intermediate "TEST00000000" test_intermediate "$(leaf_ext "$(san $UDN)")" 20200101000000Z 21200101000000Z
selfsigned leaf_self_signed "TEST00000000" "basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=serverAuth,clientAuth
$(san $UDN)"
sign leaf_two_udns "TEST00000000" test_root "$(leaf_ext 'subjectAltName=URI:urn:sonos:udn:RINCON_00000000000001401,URI:urn:sonos:udn:RINCON_00000000000001400')" 20200101000000Z 21200101000000Z
sign leaf_not_yet_valid "TEST00000000" test_root "$(leaf_ext "$(san $UDN)")" 21000101000000Z 21200101000000Z
# a leaf (CA:FALSE) used as an issuer: a speaker's own key minting a certificate that names our udn
sign leaf_under_leaf "TEST00000001" leaf_good "$(leaf_ext "$(san $UDN)")" 20200101000000Z 21200101000000Z
