# Bedrock Leaderboard

A Minecraft Paper plugin that transforms bedrock blocks into player leaderboards with unique effects and statistics. Each leaderboard shows the top 5 people that have submitted and have their own different passive/reactive effects.

## Leaderboards
- **Blocks Mined** — Tracks the vanilla stats of # of times the player has used any pickaxe. 
  _(Players must right-click the bedrock with a Gold Pickaxe in hand to submit.)_

- **Diamond Killers** — Players show off how many diamonds they sacrificed to the leaderboard. 
  _(Players right-click the bedrock with the Diamond(s) in hand to sacrifice them.)_

- **Distance By Elytra** — Tracks vanilla stat of distance by elytra in km. 
  _(Players must right-click the bedrock with a Phantom Membrane in hand.)_

- **Trades With Vilagers** — Tracks the # of times a player has traded with a vilager. 
  _(Players must right-click the bedrock with an Emerald in hand.)_

- **Loot Boxes Opened** — Leaderboard that tracks number of times reinforced deepslate has been used. (Lootbox mod is seperate)
  _(Players must right-click the bedrock with a Gold Ingot in hand.)_

- **Distance on Foot** — leaderboard for a custom lootbox plugin made for our server.  
  _(Players must right-click the bedrock with Leather in hand.)_


## Future Updates 
- Ability to modify in-game certain leaderboards. So "Diamond Killers" and the lootbox leaderboard can be changed to any item.
- Update FX (passive and reactive)

## Commands (OP Only)
- `/setleaderboard <type>` — places a leaderboard of the given type at the targeted bedrock block.  
- `/refreshloot` — reloads `data.yml` and refreshes displays.  
- `/refreshleaderboard` — rebuilds text at the targeted bedrock.  
  _(Use `/refreshloot` & `/refreshleaderboard` in that order to manually change the leaderboard data without restarting the server.)_
  
- `/rotateleaderboard` — rotates the leaderboard text (any ° increment).  
- `/clearleaderboard` — removes leaderboard & effects from targeted bedrock.  

## License
Apache-2.0
