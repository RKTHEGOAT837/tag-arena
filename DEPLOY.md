# Hosting Tag Arena for free

Goal: a permanent link that works with your PC switched off.

The repo is already prepared and committed. The game has **no npm
dependencies** (Node standard library only), so there is nothing to build.

---

## Step 1 - Put the code on GitHub

You need a GitHub account (free, no card). Create an empty repo called
`tag-arena` - **do not** tick "Add a README", it must be empty.

Then, in this folder (`C:\Users\Rishabh Khara\games\tag`):

```bash
git remote add origin https://github.com/YOUR-USERNAME/tag-arena.git
git branch -M main
git push -u origin main
```

Git will ask you to sign in to GitHub the first time.

---

## Step 2 - Deploy on Render

1. Sign up at <https://render.com> with "Sign in with GitHub". No card needed.
2. **New +** -> **Web Service** -> connect your `tag-arena` repo.
3. Render reads `render.yaml` and fills everything in. Confirm it shows:
   - Runtime: **Node**
   - Start command: `node server.js`
   - Instance type: **Free**
4. Click **Deploy**. First build takes 1-2 minutes.

You get a permanent URL like `https://tag-arena.onrender.com`.
That link is the whole game - anyone opens it, types a name, and plays.
Your PC is not involved.

### After the first deploy (optional)

In Render -> your service -> **Environment**, add:

```
PUBLIC_URL = https://tag-arena.onrender.com
```

This only affects the downloadable `/tag.html` file. The main link works
without it.

---

## Step 3 - Stop it falling asleep

Render's free tier spins a service down after **15 minutes** with no traffic.
The next visitor then waits **30-60 seconds** while it wakes up. The game is
fine after that, but that first wait is the one annoying part.

You can defeat this for free, because of how the quota works:

- Render gives **750 instance hours per month** per workspace.
- A calendar month is about **730 hours**.
- So one service can stay awake **24/7 and still fit**, with ~20 hours spare.

Set up a free uptime pinger to hit the health endpoint every 10 minutes:

- <https://uptimerobot.com> or <https://cron-job.org> (both free, no card)
- URL to ping: `https://tag-arena.onrender.com/health`
- Interval: 10 minutes

That endpoint returns `{"ok":true,"rooms":N}` and is cheap to serve.

**The catch, stated plainly:** this consumes nearly your entire monthly
allowance. It works only if `tag-arena` is the *only* free service in your
Render workspace. Add a second free service and you will run out of hours
before month end, and everything suspends until the 1st.

If you would rather not risk that, skip the pinger and accept the 30-60s
wake-up on the first visit of the day.

---

## What to expect

- **Free forever**, within the limits above.
- **In-progress games are lost if the service restarts.** Rooms live in
  memory only. Render restarts on deploys and occasionally on its own. For a
  party game this is fine - you just make a new room.
- Free instances are small (0.1 CPU / 512 MB). Plenty for this: the server
  sends about 30 updates/sec per room and there are no dependencies.
- Room codes are 4 letters, max 8 players per room.

---

## Updating the game later

```bash
git add -A
git commit -m "what changed"
git push
```

Render redeploys automatically on push.

---

## Playing on your own PC instead

Nothing here breaks the local setup. `start.bat` (same Wi-Fi) and
`play-online.bat` (temporary public tunnel) still work exactly as before.
