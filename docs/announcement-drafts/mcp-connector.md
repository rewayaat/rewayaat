# Announcement copy: the MCP connector

Held back until the connector has been deployed and tested. Nothing here is served: the
files under `src/main/resources/static/` are public the moment they deploy, so an
`active: false` flag would still leave the wording readable at `/announcement.json`.

## To publish

**1. `src/main/resources/static/announcement.json`** — replace the whole file with:

```json
{
  "id": "mcp-connector-2026-09",
  "active": true,
  "icon": "fa-plug",
  "text": "New: use the hadith database directly inside Claude and ChatGPT.",
  "linkText": "See how",
  "linkUrl": "/updates.html",
  "videoText": "Watch the demo",
  "videoUrl": ""
}
```

Fill `videoUrl` with the Loom link when it exists; leaving it empty omits the link and
publishes the rest. Keep the `id` as it is - `js/site-announcement.js` remembers dismissals
against it, so changing it re-shows the bar to everyone who has already dismissed it.

**2. `src/main/resources/static/recent_updates.json`** — put this back at the top of the
array, and update `date` if it is no longer accurate:

```json
{
  "date": "2026-09-07",
  "title": "Use the Database Inside Claude and ChatGPT",
  "summary": "Rewayaat is now available as a connector, so you can search and read the collection without leaving your AI assistant. Ask a question in your own words and it queries all 32,519 narrations directly — in Arabic or English — and cites what it finds back to this site.",
  "highlights": [
    "Search the full corpus by field, in Arabic or English, with the true number of matches reported so an answer can be complete rather than partial",
    "Read a whole chapter in order, with its real size — the difference between \"these appear to be the main narrations\" and all seven of them",
    "Follow the 47,522 judged similarity links between narrations, each with the reason it was judged similar",
    "Move in both directions between narrations and the Qur'anic verses connected to them",
    "Works with Claude and ChatGPT today, and powers the assistant on this site"
  ]
}
```

Both files are static: no rebuild of markup, no code change.
