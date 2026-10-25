# Authenticator, for the Sonim XP3

Two-factor codes (TOTP), as Authy shows them. One list: each account is a
white pill with the service's logo inside a ring that runs down as the code's
time passes, the service and the account, and the six-digit code. Nothing
else is on the screen.

The accounts are kept on the Delta command center, not only on the phone,
so a lost or reset phone loses none of them. The phone keeps a copy, so the
codes show straight away and without a connection.

| Key | |
| --- | --- |
| Up / Down | Move one account at a time |
| OK | Copy the code |
| Menu | Add by camera, Add by image, Add manually; Delete the account under the d-pad |

- **Add by camera** opens the phone's camera. Take a photo of the QR code a
  service shows when two-factor is turned on.
- **Add by image** reads the QR code in a picture already on the phone, such
  as a screenshot.
- **Add manually** takes the service, the account and the secret key the
  service shows beside its code ("Can't scan it?"). A whole `otpauth://`
  link works as the key too.

Google Authenticator's "Transfer accounts" codes work, and add every account
in them at once. Codes are SHA-1, SHA-256 or SHA-512, 6 to 8 digits, any
period. Counter-based (HOTP) accounts aren't supported.
