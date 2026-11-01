# Backup format

A backup is a zip named `*.nibtexa.zip`, written and checked by `core/export/Backup.kt`:

- `manifest.json`: `format` (`nibtexa-backup`), `version`, each booklet's id and library details, the folders, and the names of the pictures included and of any that were missing when it was made.
- `booklets/<id>.json`: the booklet as `BookletJson`: pages, ink and regions, including graphs and diagrams.
- `pictures/<name>`: each picture and PDF page once, as stored.
- The clipboard list, in a whole-library backup only.

## Versions

Anything that adds stored data must extend the format and raise `VERSION`. A reader keeps every earlier version, and refuses a newer one.

## What restore checks, before changing anything

- The format and version.
- Every entry's path: no `..`, absolute paths, backslashes or colons.
- Ids and picture names, against the patterns the app writes.
- Every booklet document.
- That every picture a booklet uses is included or listed as missing.
- Each file's checksum, reading all of it, with a size limit while reading, whatever size an entry claims.
- That there is room for it on the tablet.

Any failure refuses the whole file. A test flips a byte through the whole file, which must be refused cleanly or change nothing.

## What restore does

Pictures are copied in, then all booklets are inserted in one database transaction, so a failure leaves the library as it was. Pictures from a failed restore are unused, and are removed the next time the app opens.

- Page ids already in use get new ones, since a page row must never be taken from another booklet.
- A folder already in the library keeps its own name. A booklet whose folder the backup doesn't list comes back at the top level.
- Clipboard entries not already here are added.

## Not included

The Git settings and token are never in a backup, and a backup isn't encrypted. Android's own backup is turned off for the app.
