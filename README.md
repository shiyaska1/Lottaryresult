# Lottery Result — One-Page Print

A native **Kotlin + Jetpack Compose** Android app that takes the official Kerala State
Lotteries result PDF (the standard 4-page layout published at
`statelottery.kerala.gov.in`) and reflows it into a **single dense, bold, printable page**
with your own shop/company name as the header.

## What it does

1. On launch, the app fetches the results listing from `result.keralalotteries.com` and fills
   a **dropdown of lottery names** (SAMRUDHI, KARUNYA, KARUNYA PLUS, STHREE-SAKTHI,
   DHANALEKSHMI, SUVARNA KERALAM, BHAGYATHARA, and whatever bumper is currently running) —
   each entry is that lottery's most recent draw.
2. Type the header you want printed at the top (your company/shop name).
3. Pick a lottery from the dropdown and tap **Fetch latest & generate one-page result** — the
   app:
   - Downloads that draw's official-style result PDF directly from the site
     (`viewlotisresult.php?drawserial=<id>`).
   - Extracts the text layer from all 4 pages.
   - Parses it into the lottery name, draw number/date/venue, and every prize tier (1st/2nd/3rd
     bumper prizes with ticket + place, the consolation prize, and the 4th–9th prize number
     lists).
   - Lays everything out on **one page**: your header up top, then every prize tier packed
     into a bold, tight multi-column grid with minimal margins. The number size is chosen
     automatically (shrinking from a large starting size) so the whole result fits on a
     single A4 page; if a draw has an unusually large number of winners, it automatically
     escalates to A3 rather than shrinking the text past legibility or spilling onto a
     second page.
4. **Print** it directly (Android's print dialog — any Wi-Fi/USB/cloud printer) or
   **Share/Save** the generated PDF to any other app.

A **manual import** option is also available (pick any result PDF from device storage) as a
fallback for when the site is unreachable or for a draw the listing doesn't show.

## Project layout

```
app/src/main/java/com/keralalottery/print/
├─ MainActivity.kt          # Compose UI: lottery dropdown, header input, preview, print/share
├─ model/LotteryResult.kt   # Parsed data model (header, prize tiers, winners, numbers)
├─ network/KeralaLotteryResultsClient.kt # Fetches the listing + latest-draw PDF over HTTP
├─ parse/LotteryPdfParser.kt# PDF text extraction (PdfBox-Android) + regex structural parser
└─ pdf/
   ├─ CompactPdfGenerator.kt# Single-page layout engine (auto-fit font size, grid columns)
   └─ PdfPrinter.kt         # Android PrintManager + share-sheet integration
```

## Build & run

1. Install **Android Studio** (Ladybug 2024.2 or newer).
2. **File → Open…** and select this folder. Gradle sync will generate the wrapper and fetch
   dependencies automatically the first time.
3. Run on a device/emulator with **Android 8.0 (API 26)** or newer.

## Data source

Results are fetched from `result.keralalotteries.com`, a third-party site that republishes
the government's draw results (it is not `kerala.gov.in` itself). Every generated page carries
an "Unofficial reprint — please verify against the Kerala Government Gazette" disclaimer for
that reason. If that site changes its page layout, `KeralaLotteryResultsClient`'s `ROW_REGEX`
is the one place that needs updating.

## Parser scope

The parser targets the **standard weekly-draw result format** (as published for lotteries
like SAMRUDHI, KARUNYA PLUS, etc.): a 1st/2nd/3rd bumper prize with ticket + place, a
consolation prize, and 4th–9th prize tiers listing the last 3–4 digits of winning tickets.
Bumper-draw PDFs (which use a different, more elaborate layout) may need parser tweaks —
open an issue with a sample PDF if you hit one that doesn't parse cleanly.

## Customizing

- **Page margins / grid gutter / starting-to-floor font size**: constants at the top of
  `CompactPdfGenerator.kt` (`MARGIN`, `GUTTER`, `START_FONT`, `FLOOR_FONT`).
- **Disclaimer text**: `MainActivity`/`CompactPdfGenerator.draw()` — the line under the header
  advising to verify against the Government Gazette.
- **Colors**: consolation-prize numbers and the 1st-prize winner are drawn in dark red to
  match the official printed style; change `Color.rgb(150, 0, 0)` in
  `CompactPdfGenerator.kt` to adjust.
