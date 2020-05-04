#!/bin/sh

set -eo pipefail

info() {
    echo "$*" >&2
}

if [ "$RADIX_BOOTSTRAP_TRUSTED_NODE" ]; then
    info "Waiting for $RADIX_BOOTSTRAP_TRUSTED_NODE to become ready ..."
    while ! wget -qO /dev/null "$RADIX_BOOTSTRAP_TRUSTED_NODE/api/universe"; do
        sleep 1
    done
fi

if [ "$RADIX_IDENTITY_UNENCRYPTED_KEY" ]; then
    if [ -z "$RADIX_IDENTITY_UNENCRYPTED_KEY_FILE" ]; then
        export RADIX_IDENTITY_UNENCRYPTED_KEY_FILE=/opt/faucet-service/etc/faucet.key
        info "export RADIX_IDENTITY_UNENCRYPTED_KEY_FILE=$RADIX_IDENTITY_UNENCRYPTED_KEY_FILE"
    fi
    echo "$RADIX_IDENTITY_UNENCRYPTED_KEY" | base64 -d > "$RADIX_IDENTITY_UNENCRYPTED_KEY_FILE"
    info "created $RADIX_IDENTITY_UNENCRYPTED_KEY_FILE from RADIX_IDENTITY_UNENCRYPTED_KEY env var"
fi

exec "$@"
