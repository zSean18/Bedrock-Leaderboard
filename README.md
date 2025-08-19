# BedrockLeaderboardFinal

A work-in-progress Paper/Spigot plugin that renders multiple bedrock-based leaderboards (Diamonds, Elytra distance, etc.) with unique effects and features.

## Current Leaderboards
- **Diamond Killer** — players can flex how many diamonds they are wasting.  
  _(Players right-click the bedrock with the diamond(s) in hand to sacrifice them.)_

- **Distance By Elytra** — players can submit their current flight distance.  
  _(Players must right-click the bedrock with a phantom membrane in hand.)_

## Future Updates
- **Loot Boxes Opened** — for a custom lootbox plugin made for our server.  
  _(This will basically be a "specific block placed" leaderboard so it can easily be adapted to any block.)_

- **Blocks Mined** — tracking the player stat for blocks mined with a pickaxe.  
- **Distance on Foot** — tracking the distance players have traveled on the ground.  
- **Seeds Planted** — counting the number of seeds players have used.  
- Add custom rotation amounts for the text.  

## Commands (OP Only)
- `/setleaderboard <type>` — places a leaderboard of the given type at the targeted bedrock block.  
- `/refreshloot` — reloads `data.yml` and refreshes displays.  
- `/refreshleaderboard` — rebuilds text at the targeted bedrock.  
  _(Use `/refreshloot` & `/refreshleaderboard` in that order to manually change the leaderboard data without restarting the server.)_

- `/rotateleaderboard [degrees]` — rotates the leaderboard text (default 90° increments).  
- `/clearleaderboard` — removes leaderboard & effects from targeted bedrock.  

## License
Apache-2.0
