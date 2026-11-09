# EggVyapar — Lifetime licenses (owner's guide)

EggVyapar is activated once per phone with a **lifetime license key**. There is no licensing server:
keys are made on **your** PC with the private `license-tool`, and the app checks them offline.

## How it works

1. On first start the app shows the **Activate EggVyapar** screen with this phone's **Device code**
   (e.g. `7K2Q-M9XP-4RTD-H8WN`) and **Copy / Share** buttons.
2. The customer pays you and sends you the Device code (WhatsApp, SMS…).
3. You run `license-tool sell …` (or `issue …`) — it prints a **license key** (`EV1-…`).
4. You send the key; the customer pastes it and taps **Activate**. Done for life: the app works offline
   and never asks again. **Settings → License** shows *Activated · Lifetime License*.

Technically:

- The Device code is derived from an **EC P-256 key pair created inside the phone's Android Keystore**
  (the private half never leaves the phone). It is the same for as long as the app's data is on that phone.
- A license key = license id + device limit + Device code, **signed with ECDSA P-256 / SHA-256** using
  your private key. The app contains **only your public key** and verifies the signature and the device
  locally. No key list is in the app; changing any character of a key makes it invalid.
- A key works **only on the phone whose Device code it was made for**.
- The **device limit** is enforced by your tool: it refuses to make more keys for a license than allowed.

## Your files — back them up now

`C:\Users\<you>\.eggvyapar-license\`

| File | What | Share? |
|---|---|---|
| `signing-key.pk8` | Your **private** signing key | **Never.** Not by WhatsApp, email, or in the code repository |
| `public-key.txt` | Public key (also in `app/license-public-key.txt`) | Public, harmless |
| `registry.json` | Every license, customer, shop, device limit, device and key | Private (customer data) |

> If `signing-key.pk8` is lost, you can never make keys that the installed apps accept. Back the whole
> folder up to two offline places (e.g. an encrypted USB drive and a password manager), like the release
> signing key (`docs/RELEASE.md`). Back up `registry.json` again after selling licenses.

## Using the tool

Build it once (from the project folder):

```powershell
.\gradlew :license-tool:installDist
$lt = ".\scripts\license.bat"     # the launcher: always runs the tool with the project's Java 17
```

(Run the commands below in PowerShell from the project folder. The launcher is needed because this PC's
default Java is 1.8, which cannot run the tool.)

The signing key was already created on 2026-11-09 with `& $lt init` (it refuses to run twice).

| Task | Command |
|---|---|
| Sell to a customer (one step) | `& $lt sell --customer "Ramesh Patil" --shop "Patil Eggs" --phone 9876543210 --device 7K2Q-M9XP-4RTD-H8WN` |
| …allowing 2 phones | add `--max-devices 2` |
| Register first, key later | `& $lt new --customer "…" --shop "…" [--max-devices N]` → prints `L-XXXXXXXX` |
| Key for a phone of an existing license (2nd phone, reinstall) | `& $lt issue --license L-XXXXXXXX --device XXXX-XXXX-XXXX-XXXX` |
| Change the device limit | `& $lt set-limit L-XXXXXXXX 3` |
| See all licenses | `& $lt list` |
| One license with its phones and keys | `& $lt show L-XXXXXXXX` |
| Export to Excel | `& $lt export --file licenses.csv` |

- The same phone always gets its **same key** again (sending it twice is harmless).
- A new phone beyond the limit is **refused**, with a hint to raise the limit if you want to.
- `registry.json` records customer, shop, phone, device limit, **issued** (yes/no), every device and key.

## What customers need to know

- A **reinstall** (or clearing the app's data) or a **new phone** gives a new Device code, which needs a
  new key — use `issue` with the same license id (counts against the device limit; raise it if fair).
- Backups restore normally on the new phone after activating it. **Business data is never deleted** when
  a license check fails; the app only shows the Activation screen until a valid key is entered.

## Honest limitations

- There is no server, so you **cannot remotely deactivate** a phone that was already activated.
- A determined person could modify the app to skip the check. The design stops casual copying (a key
  shared with someone else does not work on their phone), not a skilled attacker.
- The device limit is enforced by **your registry**, not by the phones; keep `registry.json` safe.

## For development

Debug builds trust a throwaway **test** key (`core-testing/…/TestLicenseKeys.kt`), so tests can activate
themselves; keys made with it never work in the release app, which trusts only `app/license-public-key.txt`.
