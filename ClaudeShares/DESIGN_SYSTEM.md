# mK:a Design System

The operator's preferred design language for all projects. Follow this specification exactly.

---

## Brand Identity

- **Brand**: Dark Matter Laboratory
- **Reference**: (project website)
- **Parent Companies**: Mental Kinetics (MK), Stellar Kinetics (SK), Binary Kinetics, Crown & Uraeus
- **Brand Colors**: Primary navy #1c3e6b, Accent orange #f89839

---

## Color Palette

| Color | Hex | Usage |
|-------|-----|-------|
| **Orange** | `#FCB45B` | Primary accent, titles, completed states, active elements |
| **Deep Purple** | `#3723C9` | Secondary accent |
| **Light Blue** | `#58C6FE` | Progress bars, current step indicators, links |
| **Bright Pink** | `#F98CFF` | Tertiary accent (use sparingly) |
| **Cyan** | `#45BFFF` | Alternative blue accent |
| **Black** | `#000000` | Primary background |
| **Near Black** | `#0A0A0A` | Card backgrounds |
| **Dark Surface** | `#1A1A1A` | Surface/panel backgrounds |
| **Panel Dark** | `#202020` | Panel backgrounds (inner panes #1A1A1A) |
| **White** | `#FFFFFF` | Primary text on dark backgrounds |
| **Text Primary** | `#DEDEDE` | 87% white — main body text |
| **Text Body** | `#A4A4A4` | 64% white — body/secondary text |
| **Text Secondary** | `#4C4C4C` | 30% white — tertiary/disabled text |
| **Taupe** | `#827668` | Meta text, source tags, age timestamps, cancel buttons |
| **Dark Gray** | `#454545` | Borders, inactive elements, dividers |
| **Red** | `#FF4444` | Errors, destructive actions |
| **Error Red** | `#FF6B6B` | Error states |
| **Darker Orange** | `#E89838` | Connecting/transitional states |
| **Brand Orange** | `#F89839` | Panel borders (1px), brand accent |

### Status Colors
| State | Color | Hex |
|-------|-------|-----|
| Connected / Success | Orange | `#FCB45B` |
| Connecting / In Progress | Darker Orange | `#E89838` |
| Disconnected / Inactive | Grey | `#A4A4A4` |
| Error / Failed | Red | `#FF6B6B` |

---

## Typography

### Typeface: Myriad Pro

Myriad Pro is the **only** typeface to use. Do not substitute Roboto, Source Sans Pro, or system fonts unless as a web fallback.

**Fallback stack**: `'Myriad Pro', 'Source Sans Pro', sans-serif`

### Font Weights

| Weight | File | Usage |
|--------|------|-------|
| **Light 300** | `MyriadPro-Light.otf` | Subtle text, captions |
| **Regular 400** | `MyriadPro-Regular.otf` | Body text, secondary headings |
| **Semibold 600** | `MyriadPro-Semibold.otf` | Emphasis, subheadings |
| **Bold 700** | `MyriadPro-Bold.otf` | Primary headings, important text |
| **Black SemiExt 900** | `MyriadPro-BlackSemiExt.otf` | Titles, hero text, app name |

### Typography Hierarchy

| Level | Weight | Size | Usage |
|-------|--------|------|-------|
| **Main / 100%** | Bold 700 | Base size | Primary content, headings |
| **Secondary / 75%** | Regular 400 | 75% of base | Secondary content, descriptions |
| **Small / 50%** | Regular 400 | 50% of base | Timestamps, meta info, captions |
| **Labels** | Black ALL CAPS | 2pt smaller than context | Tags, labels, category indicators |
| **Titles** | BlackSemiExt 900 | Largest | App title, section headers |

### Important Rules

- **DO NOT use Condensed variants** (Cond, SemiCn) unless specifically requested — avoid them
- Italic variants are available for each weight — use for emphasis, not decoration
- SemiExtended variants available but reserved for titles (BlackSemiExt only by default)

### Font Files Provided

All 40 OTF files are in the `assets/` folder adjacent to this document:

**Core weights (use these):**
- `MyriadPro-Light.otf` — 300
- `MyriadPro-Regular.otf` — 400
- `MyriadPro-Semibold.otf` — 600
- `MyriadPro-Bold.otf` — 700
- `MyriadPro-BlackSemiExt.otf` — 900 (titles)
- `MyriadPro-It.otf` — 400 italic
- `MyriadPro-BoldIt.otf` — 700 italic

**Full set also includes:** Black, Cond, SemiCn, SemiExt variants with italic pairs (40 files total). Use only when the design specifically calls for width variation.

---

## Layout & Spacing

### Border Radius
| Element | Radius |
|---------|--------|
| Buttons, toggles, input fields | `4px` |
| Cards, containers, panels | `8px` |
| Circular elements (avatars, spinners) | `50%` |

### Panel Structure
- Outer panel: `#202020` background
- Inner pane: `#1A1A1A` background
- Panel border: `1px solid #F89839` (brand orange)
- Card background: `#0A0A0A`

### General Principles
- **Dark theme always** — black backgrounds, high contrast text
- **Clean and minimal** — card-based layouts, no clutter
- **No emojis** — ever, in any UI element
- **High contrast** — white/orange text on black/dark backgrounds
- **Consistent spacing** — maintain visual rhythm

---

## Android-Specific (Material3 Mapping)

For Android Compose / Material3 theming:

```
colorScheme = darkColorScheme(
    primary = Color(0xFFFCB45B),         // Orange
    secondary = Color(0xFF3723C9),        // Purple
    tertiary = Color(0xFF58C6FE),         // Blue
    background = Color(0xFF000000),       // Black
    surface = Color(0xFF1A1A1A),          // Dark surface
    onPrimary = Color(0xFF000000),        // Black on orange
    onBackground = Color(0xFFDEDEDE),     // 87% white
    onSurface = Color(0xFFDEDEDE),        // 87% white
    error = Color(0xFFFF6B6B),            // Error red
    outline = Color(0xFF454545),          // Gray borders
)
```

### Android Font Setup
Place font files in `res/font/`:
- `myriad_pro_regular.otf`
- `myriad_pro_bold.otf`
- `myriad_pro_light.otf`
- `myriad_pro_black_semi_ext.otf`

Create `res/font/myriad_pro.xml` font family, then reference via `FontFamily(Font(R.font.myriad_pro_regular))` in Compose.

---

## Web CSS Reference

```css
@font-face {
    font-family: 'Myriad Pro';
    src: url('/fonts/MyriadPro-Light.otf') format('opentype');
    font-weight: 300;
    font-style: normal;
}
@font-face {
    font-family: 'Myriad Pro';
    src: url('/fonts/MyriadPro-Regular.otf') format('opentype');
    font-weight: 400;
    font-style: normal;
}
@font-face {
    font-family: 'Myriad Pro';
    src: url('/fonts/MyriadPro-Bold.otf') format('opentype');
    font-weight: 700;
    font-style: normal;
}
@font-face {
    font-family: 'Myriad Pro';
    src: url('/fonts/MyriadPro-BlackSemiExt.otf') format('opentype');
    font-weight: 900;
    font-style: normal;
}

:root {
    --color-orange: #FCB45B;
    --color-purple: #3723C9;
    --color-blue: #58C6FE;
    --color-pink: #F98CFF;
    --color-cyan: #45BFFF;
    --color-black: #000000;
    --color-card: #0A0A0A;
    --color-surface: #1A1A1A;
    --color-panel: #202020;
    --color-white: #FFFFFF;
    --color-text-primary: #DEDEDE;
    --color-text-body: #A4A4A4;
    --color-text-secondary: #4C4C4C;
    --color-taupe: #827668;
    --color-gray: #454545;
    --color-red: #FF4444;
    --color-error: #FF6B6B;
    --color-brand-orange: #F89839;
    --radius-small: 4px;
    --radius-card: 8px;
    --font-main: 'Myriad Pro', 'Source Sans Pro', sans-serif;
}

body {
    background: var(--color-black);
    color: var(--color-text-primary);
    font-family: var(--font-main);
    font-weight: 400;
}
```

---

## Quick Reference

- **Background**: Always black (#000000)
- **Primary accent**: Orange (#FCB45B)
- **Font**: Myriad Pro only
- **Titles**: BlackSemiExt weight
- **Body**: Regular 400 at 75% hierarchy
- **Borders**: 4px small, 8px cards
- **Panel borders**: 1px #F89839
- **No emojis. Ever.**
- **No condensed fonts unless specifically asked.**
