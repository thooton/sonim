# List

## Purpose

A single ordered checklist for the Sonim XP3 (240x320, d-pad, soft keys, T9
keypad), driven one-handed with keys, never touch. It is for jotting down
tasks, ticking them off and clearing them away, in a few key presses, with
the app open and ready the moment it launches.

It must stay one list on one screen, offline, private and instant. It must
never become a task manager: no several lists, due dates, reminders,
priorities, stars, subtasks, sync, accounts or network of any kind. A new
feature is judged by whether it makes jotting and ticking quicker on a flip
phone; anything that adds a screen, a setting or a login should be refused.

## Not wanted

- No network. The app has no INTERNET permission; the Claude chat tab and
  its login were removed (1.x) and must not come back, incidentally or
  otherwise.
- No title or app-name bar, no tab strip (no "My Tasks"), no add button,
  no stars on tasks, no icons beside the To-do heading (removed in 1.2.1).
- No count on the Completed heading (it was "Completed (n)"; now just
  "Completed").
- No text telling the user how to use the app: no "Press Menu to…", no keys
  named, no example input in the field (DESIGN.md rule 7).
- No touch-first controls; everything must work from keys.
- No third-party libraries, AndroidX or Material components.

## Screens

### The list (the only screen)

Under the status bar, on a pale blue-grey page, there are up to two white
rounded cards, with no header above them:

1. **To-do** card: the heading "To-do", then every open task in list order,
   each a row with an empty ring and the task's text.
   - With no open tasks and nothing completed, the card shows
     "No tasks yet" with a smaller grey line under it, "Add your to-dos and
     keep track of them here".
   - With no open tasks but some completed, it shows "All tasks complete"
     and "Nice work!".
2. **Completed** card, present only when at least one task is done: a
   heading row "Completed" with a chevron (down when folded, up when open).
   When open, the finished tasks follow it in list order, each with a blue
   tick and grey struck-through text. The card starts **folded** on every
   launch.

Task text wraps to at most three lines; longer text ends with "…". A task
is always one line of text (line breaks typed in become spaces).

Selectable rows are tasks, finished tasks and the Completed heading. The
"To-do" heading, the empty note and the gap between cards are never
highlighted. The highlighted row shows a pale blue rounded pill inside the
card.

On launch the highlight is on the first selectable row (the first open
task, else the Completed heading, else nothing).

Keys:

- **Up / Down**: move the highlight between selectable rows, skipping the
  heading and the gap. Up on the first task stays there (the "To-do"
  heading stays visible above it). Down on the last row stays there. The
  list scrolls to keep the highlight on screen.
- **OK** (on its release; DPAD_CENTER or ENTER) on an open task: ticks it.
  It moves to the Completed card (which appears if it was absent, and stays
  folded or open as it was). The highlight stays at the same place, now on
  the row that slid up into it (next task, else the nearest selectable row).
- **OK** on a finished task: unticks it. It returns to the To-do card at its
  original place in list order, and the highlight stays at the same place.
- **OK** on the Completed heading: folds or opens the card. The highlight
  stays on the heading.
- **OK** with nothing selectable (empty list): nothing happens.
- **Menu** (on its release), or **holding OK**: opens the popup menu (below).
  A hold's release does not also tick the task.
- **Back**: leaves the app. The list is already saved.
- Number keys on the list: do nothing.

### The popup menu

A small rounded blue-grey popup opens beside the highlighted row (just
below it, near the left edge), not as a centred dialog. With nothing
highlighted it opens over the list card. Its rows depend on what is
highlighted, in this order:

| Highlighted | Rows |
| --- | --- |
| Nothing (empty list) | Add a task |
| Open task | Add a task, Insert above, Insert below, Edit, Move up, Move down, Delete |
| Finished task | Add a task, Edit, Delete, Delete all completed |
| Completed heading | Add a task, Delete all completed |

- **Move up** is missing on the first open task, **Move down** on the last.
- Up/Down move within the menu, OK chooses, Back or Menu closes it without
  doing anything.

Actions:

- **Add a task**: opens the task sheet empty; the saved task goes to the
  **top** of the To-do card and is highlighted.
- **Insert above / Insert below**: opens the sheet empty; the new task goes
  directly above / below the chosen task and is highlighted.
- **Edit**: opens the sheet with the task's text, cursor at the end. Saving
  replaces the text in place; a finished task stays finished. The edited
  task is highlighted.
- **Move up / Move down**: swaps the task with the open task above / below
  it (finished tasks in between are skipped). The highlight follows it.
- **Delete**: removes the task at once, with no confirmation. The highlight
  goes to the row now in its place, else the nearest selectable row.
- **Delete all completed**: removes every finished task at once, with no
  confirmation. The Completed card disappears; the highlight goes to the
  nearest selectable row.
- If the chosen task was changed or removed while the menu was open, the
  action shows the toast "That task has changed" (Insert still inserts near
  where it was).

### The task sheet

A sheet rises from the bottom over the dimmed list: a borderless text field
with the grey hint "New task", and "Save" at the right below it. T9
prediction is on. The field shows up to four lines and wraps long text.

- Typing: T9 text as usual. Save is grey and does nothing while the field
  is empty or only spaces; blue otherwise.
- **OK** (on its press) or the keyboard's Done: saves, if the text is not
  blank, and closes the sheet. With blank text nothing happens and the
  sheet stays. The OK release does not tick the task underneath.
- **Down** from the field reaches Save; OK on Save saves.
- **Back**: closes the sheet without saving; the list is unchanged.
- Surrounding spaces are trimmed and runs of whitespace become one space.
- Text over 500 characters is refused with the toast "An item can be at
  most 500 characters", and the sheet closes without saving.
- When the list already holds 500 items, adding or inserting is refused
  with the toast "The list is full".

## Behaviour

- **Data**: one file, `list.json`, in the app's private storage
  (`{"items":[{"text":…,"done":…}]}`), in list order with open and finished
  tasks interleaved. Nothing goes on the SD card; backups are off.
- **Saving**: every change (tick, add, insert, edit, move, delete) is saved
  at once, in order, on a background thread, by writing a temporary file and
  renaming it. Killing the app or pulling the battery right after a change
  keeps either the new list or the previous one, never a broken one.
- **Restart**: the tasks, their order and their ticks survive. Whether
  Completed is open does not (it starts folded). The highlight starts on
  the first selectable row.
- **Bad data**: a missing, truncated or unreadable `list.json` opens as an
  empty list without an error. A single unreadable or empty entry is
  skipped; the rest load. At most 500 items load.
- **Errors**: a failed save is silent; the list stays on screen and the
  next change tries again.
- **Leftovers**: on every launch the app deletes the chat-era files
  (`settings.json`, `bundled-setup.txt`, `chat.json` and their `.tmp`).
- **Network and permissions**: none. No timers, no notifications.
- **Orientation**: portrait only; the activity is not recreated by keyboard
  or orientation changes, so an open sheet keeps its text.

## Look

List deliberately copies Google Tasks rather than the family look in
DESIGN.md (see list/AGENTS.md):

- Page and status bar #F0F4F9 with dark status icons; no header.
- Cards white, 12dp corners, 6dp from the screen edges, 6dp gap between.
- Text #1F1F1F; secondary #444746; finished text #5E5E5E struck through.
- Open task: grey ring; finished: blue (#0B57D0) tick.
- Highlight: pale blue #D3E3FD pill inset in the card.
- Save blue #0B57D0, grey #A0A3A6 when off. Menu #E9EEF6; sheet #F0F4F9.
- About six one-line tasks fit under the To-do heading on the 240x320
  screen.

## Open questions

1. The empty To-do card says "Add your to-dos and keep track of them here"
   and "Nice work!", copied from Google Tasks. DESIGN.md rule 7 forbids a
   sentence explaining what a screen is for. Keep them (Google Tasks copy
   wins), or show only "No tasks yet" / "All tasks complete"?
2. Delete and Delete all completed act at once with no confirmation or
   undo. Is that wanted on a phone where a slip of the key is easy?
3. Should Completed remember being open across restarts? Today it always
   starts folded.
4. A task longer than three lines is cut with "…" and can only be read in
   full through Edit. Acceptable, or should the highlighted task show
   whole?
5. Text over 500 characters closes the sheet and loses what was typed (only
   a toast remains). Should the sheet stay open instead?
6. A failed save is silent. Should the user be told?
