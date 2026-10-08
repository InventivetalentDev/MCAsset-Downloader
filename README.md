# MCAsset-Downloader

Downloads & extracts asset files from Minecraft.  

Currently used for the [minecraft-assets repository](https://github.com/InventivetalentDev/minecraft-assets)

Each extracted version has these metadata files at its root:

| File | Source |
| --- | --- |
| `version.json` | Download metadata generated from Mojang's version index, including the download timestamp. |
| `<version>.json` | Mojang's launcher manifest for the selected version. |
| `game-version.json` | The official client JAR's root `version.json`, copied without changing its bytes. Includes the game's pack formats when supplied by that version. |

Older client JARs without a root `version.json` do not produce `game-version.json`.
