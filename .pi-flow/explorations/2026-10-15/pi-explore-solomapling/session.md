# SoloMapling implementation exploration

Request: dissect https://github.com/MadaraGameDev/SoloMapling and explain navigation, attacking, and other actions.

Source: /tmp/solomapling-analysis at commit 47b61b5381118ebdc1064ddbd0b1c32f75778871.

Scope: server-side artificial-player lifecycle and scheduling; recorded and procedural navigation; attack decisions, state mutation and packets; representative social, trade, party and scripted actions. Distinguish source implementation from README claims and legacy alternatives.

Exclusions: no production code changes, no client automation implementation, no live server deployment. Full gameplay verification requires MapleStory client/server configuration and database; isolate an executable algorithm for a smoke probe if feasible.

Open questions: active movement selection; combat animation versus damage; action scheduling and recovery. Findings follow in architecture.md, data-flow.md, call-graph.md, synthesis.md.
