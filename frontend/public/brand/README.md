# Фирменные ресурсы ФСП

Ресурсы скопированы из официальной публичной папки [«Фирменный стиль ФСП»](https://disk.yandex.ru/d/nxMN6oQTZ25wZQ), исходные файлы сохранены в `local/fsp-brand/assets/`. Файлы SVG не перерисовывались и не оптимизировались.

## Логотипы

Источник — `ЛОГО ФСП/Кирилица`:

| Файл приложения | Исходный файл | viewBox | SHA-256 исходника |
|---|---|---:|---|
| `fsp-logo-full.svg` | `Полноцвет/Для светлого фона/SVG/Лого полный цвет.svg` | `0 0 1239.07 155.12` | `7a310afe7ac59e2d9a00cba82584c589cc434b574576de05cca56c8f5c5cf384` |
| `fsp-logo-compact.svg` | `Полноцвет/Для светлого фона/SVG/Лого цвет сокращенный.svg` | `0 0 281.63 94.28` | `05cf5fff4586589508bce2923e83d60a294512b2c9fa528b58320c249f9e3914` |
| `fsp-logo-full-white.svg` | `Монохром/Для темного фона/Полный белый.svg` | `0 0 659.01 83.3` | `aac5a6c369f9f4b35eddea3429b06b0add27026ccdd12313296e2389495f7545` |
| `fsp-logo-compact-white.svg` | `Монохром/Для темного фона/Сокращенный белый.svg` | `0 0 260.32 83.3` | `f9d0552099b8be5e9cb1987f113504a4a7d5fba4414816d80dad044b6d376cb2` |

Полноцветные логотипы предназначены для светлого фона; белые монохромные — для тёмного. Цвета из исходного SVG: синий `#402FFF`, красный `#EC1D35`, графит `#1B1C21`, серый `#C8C9CA`; белые версии используют `#EDEDED`.

## Шрифты

Основная гарнитура — JetBrains Mono, семейство `JetBrains Mono`, начертания Regular 400, Medium 500 и Bold 700. В исходном архиве предоставлены TTF, поэтому приложение хранит TTF без преобразования. Папка и архив лицензированы SIL Open Font License 1.1; копия лицензии — `jetbrains-mono-OFL.txt`.

Акцентная гарнитура — TDA Text, семейство `TDAText`, начертания Regular и Condensed. В таблице языков Fontconfig есть `ru` (кириллица). Лицензия архива — SIL Open Font License 1.1; копии лицензии и атрибуции — `tda-text-OFL.txt` и `tda-text-COPYRIGHT.txt`.

Файлы шрифтов — это оригинальные TTF из архивов, без преобразования:

| Файл приложения | Внутреннее семейство / начертание | SHA-256 TTF | Архив-источник (SHA-256 из Yandex Disk) |
|---|---|---|---|
| `jetbrains-mono-regular.ttf` | JetBrains Mono / Regular | `b6b1ff4ddefe36d7f2a6174e1d001cab374e594519ee9049af028d577b64c5f5` | `ОСНОВНОЙ JetBrains_Mono.zip` — `2959d6cf172951593babb5fc0282d6a49bfcd4738f14e6c8c5f292683539e1f9` |
| `jetbrains-mono-medium.ttf` | JetBrains Mono / Medium | `23d57b8a635d023c3717a9c7ac1721199d0f8c6fe4b06d4d5ba3af2170fcbaa2` | same archive |
| `jetbrains-mono-bold.ttf` | JetBrains Mono / Bold | `a2349098b9e45419e7bf0e2958d6c4937a049dded37387b08be725be4c7615f3` | same archive |
| `tda-text.ttf` | TDAText / Regular | `555e8554d2a97c02dfbde4758f4901592ae2b2cf7c42d1abb317a7b29ce50e3e` | `АКЦЕНТНЫЙ TDAText.zip` — `e1624633b7f90cc06c9efc778b1c1a7134afb4c0c60b99fa7c8e301201f4bc7e` |
| `tda-text-condensed.ttf` | TDAText Condensed / Condensed | `4ff6107566e5b08121351c5936952b13ca8a6dfef476cd3f3363bfd64d996bba` | same accent archive |

Источники и распакованные оригиналы архивов находятся в `local/fsp-brand/assets/`.
