#!/bin/sh
# Creates the test repository on first start, then serves it.
set -eu
mkdir -p /srv/www /srv/git
if [ ! -d /srv/git/notes.git ]; then
    git init --bare --initial-branch=main /srv/git/notes.git
    git -C /srv/git/notes.git config http.receivepack true
fi
exec lighttpd -D -f /etc/lighttpd/lighttpd.conf
