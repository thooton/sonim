# AGENTS.md

A dependency-free two-factor authenticator (TOTP) for the Sonim XP3, styled
after Authy's list view. A sibling of `../places`, `../hbsa` and the rest,
under the same constraints: 240x320 mdpi, d-pad driven, no AndroidX or
third-party libraries, `:core` plain Java `--release 8` running on API 27.

The user asked for it to be extremely simple: the list of accounts and
their codes, and Menu's Add by camera, Add by image and Add manually (plus
Edit and Delete). Keep it that way; no search, sorting, grid view, PIN or
backups. The services' logos, and Edit, were asked for later.

## Where the accounts live

On the Delta command center (thooton/delta, `cmd/delta-cc/otp.go`), behind
the delta token: `GET /otp` lists them, `POST /otp` adds one or a list (an
account whose secret is already there is skipped), `DELETE /otp/{id}`
removes one, `PUT /otp/{id}` changes one (the secret another account has
is refused); each answers with the whole list. `Vault` is the client. The
app keeps the last list it got in `files/accounts.json` (owner-only), shows
it at once, and syncs on launch and when it comes back after ten minutes.
Adding and deleting need the command center; the codes don't. Codes are
worked out on the phone (`Account.code`, RFC 6238), so the phone's clock
must be right.

## Reading QR codes

There is no library for it, so `core/.../qr` is a QR decoder of its own,
following ZXing's approach: `Binarizer` (local 8x8-block thresholds, with a
histogram threshold as the fallback), `Finder` (the three finder patterns,
by their 1:1:3:1:1 runs, checked across rows, columns and the diagonal),
`Detector` (module size, dimension, the bottom right alignment pattern, and
a projective transform solved directly), `Decoder` (format and version
information, unmasking, the codeword zigzag, block de-interleaving and the
numeric, alphanumeric, byte, kanji and ECI segments) over `ReedSolomon`.
`Version`'s block and alignment tables were generated from python-qrcode's.
`QrReader.read` tries local then global thresholds, a quick then a thorough
scan, the symbol as printed then mirrored, and last the picture inverted.

`Pictures` decodes a photo at most 1600 pixels on its longer side, then
800, then 400, and turns it into luminance for the reader.

- **Add by camera** asks the phone's camera app (`ACTION_IMAGE_CAPTURE`)
  to write its photo to `PhotoProvider`, a provider of one file in the
  cache, lent for that one request; the app declares no camera permission,
  which would make Android refuse the request. A camera app that ignores
  the address and sends only a thumbnail is read from that.
- **Add by image** is `ACTION_GET_CONTENT` for `image/*`, then
  `ACTION_PICK` from the gallery if nothing handles that.
- **Add manually** is `EntryActivity`: service, account, secret key (or a
  whole `otpauth://` link), and digits, seconds and algorithm (6, 30, SHA1).
- **Edit** is `EntryActivity` given the account's id and fields, but never
  its secret: the key field is empty with dots for a hint, and kept unless
  a new key (or link) is typed. `Vault.update` sends it all.

## Logos

`app/src/main/assets/services.tsv` is about 2,600 services: name, domains
and the address of the logo the service's own site links (its
apple-touch-icon or icon, checked to be a PNG, JPEG, GIF, WebP or ICO,
which Android 8.1 decodes; never SVG), or nothing where none was found.
`services/crawl.py` finds them from 2fa.directory's list (MIT,
`services/2fa-directory-LICENSE.md`) and `services/build.py` writes the
file, with the few services 2fa.directory lacks (Google, Microsoft,
Anthropic...) and fixed logos in its EXTRA and OVERRIDE. Rerun both to
refresh it.

Three logos come with the app instead, in `app/src/main/assets/logos/`,
the list naming them `asset:<file>`: Twitter's bird (x.com links the X, and
Google's copy of it is the X too), Tuta's icon (an account called Tutanota
is Tuta, by its old domain) and SpigotMC's (its server gives the picture
only to its own pages). A bundled logo is the only source the app tries:
there is nothing to fetch and no favicon to fall back on.

`Services.find` (in `:core`, tested) says which service an account is:
the service's name whole or squashed, a domain, a domain's first label
("AWS"), a name in or before brackets, before a colon, or its first word;
a name that is a domain is that domain. An account with only an email is
none. `Logos` fetches the logo on three workers, the site's address first
and then Google's favicon service (`t1.gstatic.com/faviconV2`, which
answers 404 when it has none), or reads a bundled one out of the app's
assets where the list says so, following https redirects by hand, scaling
it to at most 96 pixels, and keeping it in `files/logos/<hash>.png`.
Every time the list changes from the command center (sync, add, edit,
delete) `keep` deletes the logos no account is any more. Until a logo is
there, or when there is none, the monogram shows as before.

`OtpLink` reads `otpauth://totp/...` (Google's Key Uri Format) and
`otpauth-migration://offline?data=...` (Google Authenticator's export, a
protocol buffer); HOTP is refused.

## Layout

- `android/core`: `Account` (one account, its codes and the command
  center's JSON), `Base32`, `OtpLink`, `Services`, `Vault`, and `qr/`. Tests in
  `src/test` (`./gradlew :authenticator:core:selfTest`): RFC 6238's test
  vectors, the links, the export, the services list and matching, the command
  center against a loopback
  server, Reed-Solomon, and every picture in `src/test/resources/qr`, which
  `src/test/fixtures.py` makes (clean codes of versions 1 to 40 at every
  level, and photo-like ones: at an angle, rotated, blurred, unevenly lit,
  noisy, JPEG, inverted, mirrored, small, in a screenshot, and none at all).
- `android/app`: `MainActivity` (the list, its keys, the menu, adding,
  deleting, syncing, the login dialog), `EntryActivity` (adding and
  editing), `Logo` (the badge and its countdown ring), `Logos` (fetching
  and keeping the services' logos), `Look` (Inter, the cards, the services' colours),
  `Pictures`, `PhotoProvider`.
- `mockups/index.html`: the list drawn at 240x320 with the app's sizes.

## Look: Authy's list view

Taken from Authy 27.7.1 (its APK and its Play Store screenshots): the page
`#F4F4F6`, white cards as round at the ends as they are tall over a faint
shadow, Twilio's navy `#121C2D` for names, codes and the round add button,
`#606B85` for the account after the name, Inter (bundled from Authy's own
APK, subset to Latin, OFL; licence in `assets/Inter-OFL.txt`). Each logo is the service's own
(`Logos`): cut round when it fills its square, otherwise on a white disc
in the square inside it. Without one, it is the service's initial on its
brand colour (`Look.BRANDS`, matched on whole
words of the service's name; otherwise one of eight tones chosen by the
name), inside a ring of that colour (for a logo whose service isn't in
`BRANDS`, the average of the logo's coloured pixels) over a `#E1E3EA` track that runs
down with the code's time. Codes are grouped in halves ("482 963") with even
figures. Authy has no d-pad focus, so the card under the d-pad is outlined
in the navy. No header: the list starts at the top. With no accounts, "No
accounts" and Authy's round navy + button, which OK opens the menu from.

Scrolling is at once, never eased, and one account per press: Up and Down
choose the next account where it is if it is wholly on screen, and
otherwise bring it just into view at the edge it comes from (`move`), as
New Yorker's lists move a small, steady step.

## Credentials

`app/build.gradle` bakes the login into a generated `Bundled.java` under
`build/`, from the same places as Places (`deltaCenter` in
`../gradle/bundled.gradle`); CI's APKs carry none, so the app asks for the
command center's address and token on first launch (Menu's Command center
later). Never print, commit or attach the config files, the generated
source, the APK, or any account's secret.
