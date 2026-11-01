# A throwaway Git smart-HTTP server for NibTeXa's sync tests. Never expose it beyond the
# developer machine: the credentials are fixed test values. See README.md.
FROM alpine@sha256:2e0b81d60597f449baa56dc25d355367869d2dca4cd57e866f1e2b52af3eb55f

RUN apk add --no-cache git=2.49.1-r0 git-daemon=2.49.1-r0 lighttpd=1.4.85-r0 lighttpd-mod_auth=1.4.85-r0 \
    && git config --system safe.directory '*' \
    && git config --system init.defaultBranch main

COPY lighttpd.conf /etc/lighttpd/lighttpd.conf
COPY users /etc/lighttpd/users
COPY entrypoint.sh /entrypoint.sh
RUN chmod 0755 /entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
