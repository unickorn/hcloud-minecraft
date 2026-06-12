# Hetzner Cloud Minecraft Plugin

This plugin allows you to deploy Hetzner Cloud servers directly from Minecraft with an immersive technician NPC experience.

## Features
- Interactive Chest GUIs for configuration.
- Supports live fetching of Server Types and Locations from Hetzner.
- Immersive deployment: Place a block, watch a randomly named technician (Villager) work, and get notified when the server is ready.
- **Redstone Networking**: Connect multiple server blocks with redstone wire to automatically form HCloud Private Networks.
- Automatic Paper Minecraft installation via snapshots (any snapshot with label `minecraft-ready=true` will be used) or cloud-init.
- Default SSH keys from your HCloud account are automatically provisioned.

## Setup
1. Build the plugin: `gradle shadowJar` (Requires Java 25+).
2. Place the JAR in your Paper 26 server's `plugins` folder.
3. Start the server.
4. In-game, set your Hetzner API token: `/hcloud token <your_api_token>`.

## Usage
1. Run `/hcloud`.
2. Follow the GUI prompts to select:
   - Server Type (e.g., CX22, CPX11)
   - Location (e.g., Helsinki, Falkenstein, Ashburn)
   - Minecraft Installation (Yes/No)
3. Receive the **Deploy HCloud Server** block.
4. Place the block anywhere in the world.
5. Wait for the technician to arrive and complete the provisioning.
6. Once pingable, you'll receive the IP in chat!

## Redstone Networking
You can link your cloud infrastructure together using standard Minecraft redstone wire:
- **Automatic Private Networks**: Connecting 2 or more servers with redstone wire creates a private HCloud Network (`minecraft-net-X`) and attaches all members automatically.
- **Dynamic Merging**: Connecting two separate redstone circuits will merge their HCloud networks into one.
- **Visual Feedback**: Redstone wires "snap" to server blocks and **light up** once the network connection is successfully established.
- **Live Status**: Server titles update in real-time to show their private IP and network name.
- **Auto-Cleanup**: When a connection is broken and a network becomes empty, it is automatically deleted from your account.

---
*This project is almost completely **vibecoded**, with a huge hats off to **Gemini 3 Flash Preview**!* :)
