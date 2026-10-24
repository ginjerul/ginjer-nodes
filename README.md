# Minecraft Nodes

** In this fork, I integrated a lot of the modern uses of nodes, such as Mining Towers, Mining boosts, Warzones, Refineries etc..

** For any requests to add on to this fork please dm me in discord, I would be happy to consider your features. my dc is: mententen

![Nodes map screenshot](docs/nodes_map_example.jpg)

## Description

Nodes is a Minecraft plugin that adds a territory-based geopolitics system to your server. Players form towns and nations, claim territories, manage resources, form alliances, and wage war. The plugin also ships a Dynmap viewer/editor extension for visualising the world map.

## Installation

### First Time Installation

1. Download the latest release JAR from the [Releases page](https://github.com/gingercoot/ginjer-nodes/releases).
2. Place `nodes-VERSION.jar` in the `plugins` folder of your Paper/Spigot server.
3. Restart your server.

### Companion Plugins

- **Kotlin runtime** – Nodes requires a Kotlin runtime plugin on the server. See [minecraft-kotlin](https://github.com/d-z4/minecraft-kotlin) for a compatible runtime.
- **NodesMap** (recommended) – Lightweight live web map built for Nodes, included in `nodesmap/`. Replaces Dynmap with live war, warzones, raids, supply lines and more. See `nodesmap/README.md`.
- **Dynmap** (optional, legacy) – Install [Dynmap](https://www.spigotmc.org/resources/dynmap.274/) and the included Dynmap extension for an interactive live map. See `dynmap/README.md` for details.

## Usage

### Documentation (all below are not relevant to my fork currently unless I update them)

- [User Guide](USER_GUIDE.md) – Getting started and common scenarios
- [Commands Reference](COMMANDS.md) – Complete list of all commands
- [Configuration Guide](CONFIG.md) – Detailed configuration options
- [Changelog](CHANGELOG.md) – Release-by-release summary of changes

### Wiki & Additional Resources

- [Wiki Guide (luna's)](https://github.com/d-z4/minecraft-nodes/wiki)
- [Dynmap Editor](https://editor.nodes.soy/earth.html)

### Experiencing a bug?

Please fill out a bug report [here](https://github.com/gingercoot/ginjer-nodes/issues/new).

## Contributing

- [CONTRIBUTING.md](CONTRIBUTING.md)
- [Notes for Developers](https://github.com/d-z4/minecraft-nodes/wiki) ((luna's))

## Testing

### Unit Tests

Linux:

```
cd nodes
./gradlew clean test
```

Windows:

```
cd nodes
.\gradlew.bat clean test
```

If you see `BUILD SUCCESSFUL`, the tests have passed.

## Development

### Repository Structure

```
minecraft-nodes/
 ├─ nodes/      – Main nodes server plugin (Kotlin + Paper)
 └─ dynmap/     – Dynmap viewer/editor (Node.js + Rust/WASM)
```

### Building the Main Plugin

Requirements: Java JDK 21

```
cd nodes
./gradlew build
```

The built JAR appears in `nodes/build/libs/`.

### Building the Dynmap Viewer/Editor

See `dynmap/README.md` for full instructions. Requirements: Node.js and Rust.

## Authors and Acknowledgement

### Developers

| Name | Main Contributions |
|------|--------------------|
| phonon | Original plugin author |
| Jonathan | Coding + map painting |
| Doneions | Coding + testing |
| dmccoystephenson | Maintenance + development |
| ginjer | development as documented above |

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE.md) (GPL-3.0).

You are free to use, modify, and distribute this software, provided that:

- Source code is made available under the same license when distributed.
- Changes are documented and attributed.
- No additional restrictions are applied.

See the [LICENSE.md](LICENSE.md) file for the full text of the GPL-3.0 license.

## Project Status

This project is in active development.

I will try to add as many features into this plugin that I can, provided you guys let me know if you want me to add anything, right now I'm pretty much done with the plugin.
