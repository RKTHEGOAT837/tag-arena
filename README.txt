TAG ARENA
=========

A small multiplayer tag game. One player is IT and chases everyone else.
Touch someone and they become IT.

Whoever is STILL IT when the clock hits zero loses the round. Everyone else
is ranked by how little time they spent as IT, so the winner is whoever
managed to dump the tag fastest and never take it back.

Everything here is plain Node.js - no npm install, no internet needed.


WHAT IS IN A ROUND
------------------
Three maps, picked at random each round: two platform courses and a stepped
pyramid with a chimney straight up the middle.

Bounce pads
   Fixed to the map, at the edges (and up the pyramid's chimney). They throw
   you about twice as high as a jump - the only way up a really big step.

Portals
   These are NOT part of the map. A pair tears open somewhere random, works
   for nine to sixteen seconds, then collapses, and another pair opens
   somewhere else. Step into one, come out of its twin across the arena.
   - while a portal is still swirling open it does nothing, so you cannot
     dive into one the instant it appears
   - in its last two seconds a white ring counts down around it. Diving for
     a portal that shuts in your face is a great way to get tagged
   - because they never stay put, no one can camp an escape route

Pickups
   Also dropped at random, up to three on the map at once. Run through one
   to take it:
      SPEED     45% faster for 6s
      FEATHER   low gravity, huge floaty jumps, for 8s
      SHIELD    cannot be tagged for 4s - runners only. If the chaser grabs
                one it turns into SPEED instead
      SWAP      instantly trade places with a random player

Final 20 seconds
   The screen edges go red and IT gets noticeably faster. Being IT at the
   whistle is what loses it for you, so the end of a round is a scramble.


CHEAT CODES
-----------
Press TAB during a game (or the CHEATS button, top left) for the full menu -
twenty codes, grouped, with what each one does. Tap one to fire it.

You can also just type the word while you play, without opening anything.
Both do exactly the same thing.

A few are marked IT ONLY and are refused unless you are the chaser. The host
can switch cheats off entirely in the lobby ("Cheat codes: Off") for a
clean race.


HOW TO RUN
----------
1. Double-click  start.bat
2. Leave that black window open - that IS the server.
3. It prints two addresses:

      You (this PC) :  http://localhost:3000
      Same Wi-Fi    :  http://10.246.244.173:3000

4. Open the first one in your browser, type a name, press "Create a room".
   You get a 4-letter room code.

To stop the server: press Ctrl+C in the black window, or just close it.


HOW FRIENDS JOIN
----------------
Everyone must be on the SAME WI-FI as this PC.

Easiest way:
   Send them the "Same Wi-Fi" link (http://10.246.244.173:3000).
   They open it, type their name, type your room code, press Join.

Send-a-file way (what you asked for):
   Send them the single file:   share\tag.html
   (created automatically every time the server starts)
   They open that one file in any browser - it already knows this PC's
   address. They just type a name and the room code.

Up to 8 players. Phones, tablets and laptops all work.


CONTROLS
--------
   Arrow keys or WASD      run
   Up / Space              jump
   Tab                     cheat code menu (Esc or Tab closes it)
   On phones               drag anywhere to run, JUMP button to jump,
                           CHEATS button for the menu


THINGS THAT CAN GO WRONG
------------------------
"Windows Defender Firewall" popup on first run
   Click "Allow access" (tick Private networks). Without this, other
   devices cannot reach the server.

Friend gets "Could not reach the game server"
   - Are they on the same Wi-Fi? Guest networks usually block this.
   - Your IP can change when you reconnect to Wi-Fi. Restart the server,
     read the new address, and re-send share\tag.html.
   - They can also fix it themselves: on the start screen open
     "Connection settings" and paste the address the server printed.

"Port 3000 is already in use"
   Run it on a different port:   set PORT=3001 && node server.js


PLAYING WITH FRIENDS ANYWHERE (not just your Wi-Fi)
---------------------------------------------------
Double-click  play-online.bat  instead of start.bat.

It does everything by itself:
   - opens a public tunnel to this PC (no router setup, nothing to sign up for)
   - starts the game server pointed at that tunnel
   - rebuilds share\tag.html with the public address baked in
   - refreshes the copy on your Desktop:
        "Tag Arena - send to friends.html"

Then send friends EITHER the link it prints OR that Desktop file. Both work
from anywhere in the world. They install nothing.

Three things to know:
   1. Keep the black window open. Close it and the link dies mid-game.
   2. The link is DIFFERENT every time you run it. Re-send it each session.
      (The Desktop file is re-baked automatically, so re-send that too.)
   3. While it runs, this PC is reachable from the public internet. Only
      people with the link and a room code can do anything, but close the
      window when you are done playing.


FILES
-----
   server.js     the game server (rooms, physics, tag logic)
   client.html   the game itself - menus, rendering, controls
   share\tag.html   generated on startup: client.html with this PC's
                    address baked in. This is the file you send to friends.
   start.bat     double-click launcher
