# Icon sources

Transparent 96 px PNGs rendered from **Material Symbols Rounded** (Apache-2.0, see `LICENSE-MaterialSymbols.txt`).

- The hub embeds them (downscaled to 72 px) as data URIs in `app/src/main/assets/app.css` (`.ico-*` classes),
  used as CSS masks so they take the current text color.
- `app/src/main/res/drawable-nodpi/ic_bar_*.png` are copies used by the native game-player bar.

| File | Material Symbol |
|---|---|
| gamepad | sports_esports (filled) |
| palette | palette |
| schedule | schedule |
| bell / bell_off | notifications (filled) / notifications_off |
| save | save |
| solved / missed | check_circle / cancel (filled) |
| paste | content_paste |
| check / close | check / close |
| verified | verified (filled) |
| info | info |
| arrow_back / refresh / open_in_new | same names |
| ic_bar_shield / ic_bar_shield_off | shield (filled) / remove_moderator (or shield off) — native player bar |

## Icon previews & generators

- `app-icon-styles.png`, `app-icon-gold.png`: preview sheets for the selectable app icon styles.
- `gold-icon.js`: renders the Gold adaptive-icon layers (`mipmap-*/ic_gold_fg.png`, `ic_gold_bg.png`), legacy PNGs, and `goldIconSvg` in `app/src/main/assets/index.html`.
- `ruby-icon.js`: renders the step-cut Ruby adaptive-icon layers (`mipmap-*/ic_ruby_fg.png`, `ic_ruby_bg.png`), legacy PNGs, and `rubyIconSvg` in `app/src/main/assets/index.html`.
- `ruby-css.js`: generates the Ruby accent CSS block in `app/src/main/assets/app.css` from the Gold accent rules.
