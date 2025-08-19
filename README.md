# BedrockLeaderboardFinal

A work-in-progress Paper/Spigot plugin that renders multiple bedrock-based leaderboards (Diamonds, Elytra distance, etc.) with unique effects and features.

## Current Leaderboards
-'Diamond Killer' leaderboard where players can flex how many diamonds they are wasting. (Players right click the bedrock with the diamond(s) in hand to sacrifice them)
-'Distance By Elytra' leaderboard where players can submit their current flight distance. (Players must right click the bedrock with a phantom membrane in hand)

## Future Updates
-'Loot boxes Opened' leaderboard that will be for a custom lootbox plugin we made for our server. This will just basically be a "Specific block placed" leaderboard so it can easily be edited to any block.
-'Blocks Mined' Leaderboard tracking the player stat for blocks mined with a pickaxe.
-'Distance on Foot' leaderboard tracking the distance players have traveled on the ground.
-'Seeds Planted" leaderboard for the amount of seeds players have used.
-Add custom rotation amounts for the text.

## Commands (OP Only)
- `/setleaderboard <type>` — Places 'type' leaderboard at a block of bedrock the user is targeting
- `/refreshloot` — reload `data.yml` and refresh displays.
- `/refreshleaderboard` — rebuild text at the targeted bedrock. (Use /refreshloot & /refreshleaderboard in that order to manually change the leaderboard data without restarting the server)
- `/rotateleaderboard [degrees]` — rotate the text 90 degrees
- `/clearleaderboard` — remove leaderboard & effects from targeted bedrock

## License
Apache-2.0
