# How the Sonim XP3 Messages app does scrolling

Reverse-engineering notes on the stock messaging app, written up because our own
chat scroll is a mess and this one isn't.

## Provenance

| | |
|---|---|
| Device | Sonim XP3800 (`XP3832`), Android 8.1.0, API 27, stock `framework-res.apk` |
| APK | `/system/priv-app/BMms/BMms.apk` → package `com.android.mms`, 3,478,783 bytes |
| Lineage | AOSP `packages/apps/Mms` (the pre-Bugle app) + CodeAurora RCS patches |
| Tools | `apktool 2.11.0` (resources + smali), `jadx 1.5.6` (Java) |

The chat screen is `com.android.mms.ui.ComposeMessageActivity` with layout
`res/layout/compose_message_activity.xml`. Every claim below is from the
decompiled app or from stock AOSP framework code, and I've noted which.

The headline: **the app contains no scrolling algorithm.** There is no
`OnScrollListener` anywhere in `com.android.mms.ui` (the only `onScrollChanged`
in the entire package is in `SlideView`, which is the MMS slideshow player and
unrelated). There is no arrow-scroll code, no focus-search override, no
`scrollListBy`, no scroll animator, no velocity handling. The whole thing is
XML configuration plus one 60-line method that decides whether to jump to the
bottom.

---

## 1. The widget choice

```
MessageListView  extends  ZoomMessageListView  extends  android.widget.ListView
```

`ZoomMessageListView` (`ui/zoom/ZoomMessageListView.java`) adds exactly one
method, `handleZoomWithScale()`, which walks `getChildAt()` and forwards a text
scale factor to each child. It overrides nothing about scrolling or touch.

`MessageListView` (144 lines of smali) overrides exactly two things:

- `onKeyShortcut(0x1f /* KEYCODE_C */)` — copy the selected SMS body to the
  clipboard, using `getSelectedView()`.
- `onSizeChanged()` — forwards to an `OnSizeChangedListener` the activity sets.

That's it. **`ListView`, not `RecyclerView`, and not a `ScrollView` of bubbles.**
That matters more than anything else here: `ListView` has a *selection* model
(`mSelectedPosition`, `getSelectedView()`, `getSelectedItem()`) and a built-in
d-pad scrolling implementation that `RecyclerView` simply does not have. On a
keypad phone you get that behaviour for free by picking the older widget.

The activity also relies on the selection model for features, which is a good
proof that it's real and not vestigial — `onKeyDown` uses
`mMsgListView.isFocused()` + `getSelectedItem()` to implement "press Clear/Del
to delete the highlighted message" (`ComposeMessageActivity.java:2897`), and
`ConversationList` does the same for threads via `getSelectedItemId()`.

## 2. The XML is the configuration

From `compose_message_activity.xml`, the message list:

```xml
<view class="com.android.mms.ui.MessageListView"
      android:id="@id/history"
      android:layout_width="fill_parent"
      android:layout_height="0dip"
      android:layout_weight="1.0"
      android:layout_marginBottom="1.0dip"
      android:paddingBottom="@dimen/input_text_height_adjusted"
      android:stackFromBottom="true"
      android:transcriptMode="disabled"
      android:smoothScrollbar="false"
      android:fadingEdge="none"
      android:scrollbarStyle="insideOverlay"
      android:scrollbarAlwaysDrawVerticalTrack="false"
      android:cacheColorHint="@android:color/white"
      style="?android:attr/listViewWhiteStyle" />
```

Each attribute is doing a specific job:

- **`stackFromBottom="true"`** — content piles up from the bottom edge. When
  there are fewer messages than fit the screen they sit at the bottom against
  the composer instead of floating at the top. This is the single attribute
  that makes a list *look* like a chat transcript, and it's free.
- **`transcriptMode="disabled"`** — explicitly opted out of auto-scroll. The
  framework will *never* move the list on its own when the adapter changes.
  All bottom-pinning is done manually in `smoothScrollToEnd()` (§5) so there is
  exactly one thing deciding scroll position rather than two fighting.
- **`layout_height="0dip"` + `layout_weight="1"`** — the list absorbs whatever
  vertical space the composer doesn't use, inside a plain vertical
  `LinearLayout`. No `RelativeLayout` constraint dance, no `CoordinatorLayout`.
- **`paddingBottom="8dp"` with `setClipToPadding(false)`** (set in code at
  `ComposeMessageActivity.java:4951`) — the last bubble can scroll into the
  padding strip so it isn't jammed against the composer, but the padding still
  counts as part of the scroll range. `smoothScrollToEnd()` reads
  `getPaddingBottom()` back out when deciding whether the bottom is reached,
  so the two agree by construction.
- **`smoothScrollbar="false"`** — the scrollbar thumb is sized by item *count*,
  not by summed pixel height. With wildly variable row heights (a one-word SMS
  vs. a long MMS), the "smooth" mode makes the thumb jitter and resize as rows
  are measured. Count-based sizing is stable.
- **`fadingEdge="none"`** — no fade gradients. On a 240px-wide screen a fade
  eats real content, and it costs an offscreen layer.

The row heights themselves are constrained rather than free: bubbles are capped
at `message_item_bubble_max_width = 200dp` and use a plain 16dp-corner shape
(`message_item_bubble_recv.xml`), so rows stay in a predictable size band.

The thread list (`conversation_list_screen.xml`) is the same idea minus
`stackFromBottom`, plus `android:drawSelectorOnTop="false"`.

## 3. The focus model: rows are selectable, never focusable

This is the part that's easy to get wrong, and the app is unusually disciplined
about it. Three independent mechanisms all push in the same direction:

1. **`mMsgListView.setItemsCanFocus(false)`** (`ComposeMessageActivity.java:5118`).
   The `ListView` itself is the single focusable view. D-pad up/down inside it
   moves *selection*, not focus.
2. **`android:descendantFocusability="blocksDescendants"`** on the row root in
   both `message_list_item_recv.xml` and `message_list_item_send.xml`. Even if
   a child were focusable, it can't take focus.
3. **`android:autoLink="all"` paired with `android:linksClickable="false"`** on
   every body `TextView`. Phone numbers and URLs are *styled* as links but are
   not clickable — so `TextView` never installs a `LinkMovementMethod`, which
   would otherwise hijack d-pad up/down to walk between links inside a single
   message. Link handling instead happens on row *click*, in
   `MessageListItem.onMessageListItemClick()`, which collects `getUrls()` and
   pops a chooser dialog. Related: `mSlideShowButton.setFocusable(false)`
   (`MessageListItem.java:1677`).

Consequence: **the d-pad never enters a message.** There is exactly one focus
stop for the entire transcript. Pressing down through the list can never get
"stuck" inside a bubble with three links in it.

The selection highlight is entirely stock. There is no `setSelector()`,
no `android:listSelector`, and no `state_selected`/`state_focused` drawable
anywhere in the app — `res/drawable/` has selectors only for buttons. The
highlight is whatever `?android:attr/listViewWhiteStyle` provides, drawn
*behind* the row (`drawSelectorOnTop=false`), so it reads as a full-width band
behind the bubble.

### Getting into and out of the list

Focus movement between the composer and the transcript is pure XML:

```xml
<!-- the composer -->
<EditText android:id="@id/embedded_text_editor"
          android:nextFocusUp="@id/history"
          android:nextFocusRight="@id/send_button" ... />
```

Pressing **up** from the text box hands focus to the list. What happens then is
stock `AbsListView.onFocusChanged()` (AOSP framework, not app code): when the
list gains focus and a `previouslyFocusedRect` is supplied, it walks its
children to find the one geometrically closest to that rect and calls
`setSelectionFromTop(closestChildIndex + mFirstPosition, closestChildTop)`.
Because the composer is below the list, the closest child is the **bottom-most
visible message** — and because it uses `setSelectionFromTop` with the child's
existing top, *the list does not scroll at all* when you enter it. The last
message just lights up. That "it highlights exactly where I was looking"
feeling is a framework behaviour the app gets by doing nothing.

Note also `android:nextFocusUp="@id/history"` on the multi-attachment
`ScrollView`, so the same entry point works when an attachment editor is open.

## 4. D-pad scrolling is 100% framework

Once focus is in the list, every up/down press is handled by stock
`android.widget.ListView` on API 27. The app overrides none of it. For
reference, the relevant AOSP path is:

```
ListView.onKeyDown → commonKey()
    KEYCODE_DPAD_UP/DOWN     → arrowScroll(FOCUS_UP / FOCUS_DOWN)
    KEYCODE_PAGE_UP/DOWN     → pageScroll(...)
    KEYCODE_MOVE_HOME/END    → fullScroll(...)

arrowScroll → arrowScrollImpl(direction):
    nextSelectedPosition = lookForSelectablePositionOnScreen(direction)
    amountToScroll       = amountToScroll(direction, nextSelectedPosition)
    if (nextSelectedPosition != INVALID_POSITION) setSelectionInt(...)
    if (amountToScroll > 0) scrollListItemsBy(±amountToScroll)
```

The behaviour that emerges, and why it feels right on a flip phone:

- **Scroll the minimum.** `amountToScroll()` computes only enough movement to
  bring the newly selected row fully inside `getHeight() - mListPadding.bottom`.
  If the next row is already fully visible, the list doesn't move at all —
  the highlight just steps down. You get several "free" presses per screen,
  which is what makes stepping through a transcript feel light instead of
  lurching a full row every time.
- **Clamped to a third of a screen.** `mMaxScrollAmount = 0.33f * (bottom - top)`,
  recomputed in `ListView.onSizeChanged()`. A single press can never scroll
  more than a third of the viewport, so you never lose your place.
- **A too-tall row scrolls without changing selection.** When
  `lookForSelectablePositionOnScreen()` returns `INVALID_POSITION` (the next row
  exists but can't be fully brought on screen) but `amountToScroll > 0`, the
  list scrolls and *keeps the current selection*. That's how you read a message
  taller than the screen: repeated downs scroll through it, then the next press
  advances the highlight. No special case in the app.
- **It's instant.** `scrollListItemsBy()` → `offsetChildrenTopAndBottom()`. No
  animator, no interpolator, no fling. Key repeat gives you the acceleration.

The app's contribution to all of this is `setItemsCanFocus(false)` and not
overriding `dispatchKeyEvent`, `onKeyDown`, or `focusSearch` on the list.

`ComposeMessageActivity.onKeyDown()` does exist but is careful: it handles only
`KEYCODE_BACK`, `KEYCODE_DPAD_CENTER`/`ENTER` (send, but only
`if (isPreparedForSending())`), and `KEYCODE_CLEAR`/`DEL` (delete highlighted
message, but only `if (mMsgListView.isFocused())`). Every path that doesn't
match falls through to `super.onKeyDown()`. **The arrow keycodes are never
mentioned.**

## 5. Auto-scroll-to-bottom: one method, two callers

The only code in the app that moves the transcript on its own is
`smoothScrollToEnd(boolean force, int listSizeChange)`
(`ComposeMessageActivity.java:5548`). It has exactly two call sites.

**Caller A — the list changed size** (`ComposeMessageActivity.java:4952`):

```java
mMsgListView.setOnSizeChangedListener((w, h, oldw, oldh) -> {
    if (!mMessagesAndDraftLoaded && oldh - h > 200) loadMessagesAndDraft(3);
    smoothScrollToEnd(false, h - oldh);
});
```

This is the keyboard/composer-growth case. `listSizeChange` is signed: negative
when the list shrank (keyboard opened, composer grew to 3 lines), positive when
it grew back.

**Caller B — the message cursor came back**
(`ComposeMessageActivity.java:5693`), inside `BackgroundQueryHandler.onQueryComplete`
for `MESSAGE_LIST_QUERY_TOKEN`:

```java
mMsgListAdapter.changeCursor(cursor);
if (position != -1) {
    mMsgListView.setSelection(position);        // restore, see §6
} else {
    long lastId = (cursor != null && count > 0)
                ? (cursor.moveToLast(), cursor.getLong(1)) : 0;
    boolean force = mScrollOnSend || lastId != mLastMessageId;
    smoothScrollToEnd(force, 0);
    mLastMessageId = lastId;
    mScrollOnSend = false;
}
```

`force` is the key policy decision, and it's a good one:

- `mScrollOnSend` is set to `true` in exactly one place — `sendMessage()`
  (`ComposeMessageActivity.java:5353`), right after `mWorkingMessage.send()`.
  **You sent it, so you get jumped to the bottom, unconditionally.**
- `lastId != mLastMessageId` means the newest row is a message we haven't seen
  before, i.e. one genuinely arrived.
- Otherwise (a requery caused by a delivery-report update, a read-state change,
  an MMS finishing download) `force` is `false` and the list only follows if it
  was already at the bottom. **Reading history is never interrupted by
  background churn.**

### The decision table

```java
int last      = mMsgListView.getLastVisiblePosition();
int target    = mMsgListAdapter.getCount() - 1;
if (last < 0 || target < 0) return;                  // list not laid out yet

View lastChild = mMsgListView.getChildAt(last - mMsgListView.getFirstVisiblePosition());
int  bottom    = lastChild != null ? lastChild.getBottom() : 0;
int  rowHeight = lastChild != null ? lastChild.getHeight() : 0;

int     listHeight = mMsgListView.getHeight();
boolean tooTall    = rowHeight > listHeight;

boolean go = false;
if (force) {
    go = true;
} else if ((listSizeChange != 0 || target != mLastSmoothScrollPosition)
        && bottom + listSizeChange <= listHeight - mMsgListView.getPaddingBottom()) {
    go = true;                                       // we were already at the end
}

if (go || (tooTall && target == last)) {
    if (Math.abs(listSizeChange) > 200) {            // (1) keyboard toggled
        if (tooTall) setSelectionFromTop(target, listHeight - rowHeight);
        else         setSelection(target);
        return;
    }
    if (target - last > -1) {                        // (2) the normal case
        setSelection(target);
    } else {                                         // (3) effectively unreachable
        if (tooTall) setSelectionFromTop(target, listHeight - rowHeight);
        else         smoothScrollToPosition(target);
        mLastSmoothScrollPosition = target;
    }
}
```

Four things worth pulling out:

- **The "am I at the bottom?" test is `bottom + listSizeChange <= listHeight -
  getPaddingBottom()`.** It's a *predictive* test: it adds the size delta before
  comparing, so it asks "will the last row still be fully visible after this
  resize?" rather than "was it visible before?". That's why opening the keyboard
  doesn't produce a one-frame flash of the wrong position.
- **`Math.abs(listSizeChange) > 200` is an explicit "this was a keyboard, not a
  layout wobble" threshold**, and it deliberately picks the *hard* jump
  (`setSelection`) over the animated one. A big geometry change gets a cut, not
  a pan. Small changes fall through to the normal path.
- **`setSelectionFromTop(target, listHeight - rowHeight)` is the too-tall
  case.** When the newest message is taller than the viewport, you can't
  "scroll to it" meaningfully — so it positions the row's top at a negative
  offset such that its *bottom* lands at the bottom of the list. You land on
  the end of the long message, which is where the new text is.
- **Branch (3) is dead in practice.** `target - last > -1` is
  `target >= last`, and `getLastVisiblePosition()` can't normally exceed
  `getCount() - 1`. (Verified against the smali: `sub-int v1, v6, v5` /
  `const/4 v2, -0x1` / `if-le v1, v2, :cond_e`.) It can only fire in the window
  where the `ListView` still has children from a longer previous cursor. So
  despite the method's name, **the app almost always does an instant
  `setSelection()` and almost never animates.** `smoothScrollToPosition` and
  `mLastSmoothScrollPosition` are effectively vestigial. Whether that was
  intended or is an off-by-one that happens to feel good on a 320×240 screen,
  the result is that arrival at the bottom is always a cut.

## 6. Position restore across pause/resume

`onPause()` (`ComposeMessageActivity.java:2731`) records intent, not pixels:

```java
if (mMsgListAdapter != null
        && mMsgListView.getLastVisiblePosition() >= mMsgListAdapter.getCount() - 1) {
    mSavedScrollPosition = Integer.MAX_VALUE;        // sentinel: "I was at the end"
} else {
    mSavedScrollPosition = mMsgListView.getFirstVisiblePosition();
}
```

`Integer.MAX_VALUE` means *"pin to the end"*, not *"row 2147483647"*. On the
way back in, `onQueryComplete` resolves it against the **new** count:

```java
if (selectId != -1)                     position = <index of that message>;   // deep link
else if (mSavedScrollPosition == -1)    position = -1;                        // no restore
else if (mSavedScrollPosition == MAX)   position = count - 1;                 // was at end
else                                    position = mSavedScrollPosition;      // was mid-list
mSavedScrollPosition = -1;                                                    // one-shot
```

So if three messages arrived while you were away and you'd been at the bottom,
you come back to the *new* bottom. If you were reading history, you come back to
the row you were on and the new messages wait below. Saving a raw scroll offset
could not express that distinction.

`ConversationList` uses the finer-grained variant, since threads reorder
underneath you (`ConversationList.java:320`, `:1045`):

```java
// onPause
mSavedFirstVisiblePosition = listView.getFirstVisiblePosition();
View child = listView.getChildAt(0);
mSavedFirstItemOffset = child != null ? child.getTop() : 0;

// after the requery lands
if (mSavedFirstVisiblePosition != -1) {
    getListView().setSelectionFromTop(mSavedFirstVisiblePosition, mSavedFirstItemOffset);
    mSavedFirstVisiblePosition = -1;
}
```

`(position, offset-of-first-child)` is the *anchor pair* pattern. Both are also
round-tripped through `onSaveInstanceState` as `last_list_pos` /
`last_list_offset`.

**The restore always happens after `changeCursor()`, in the same synchronous
block, before the next layout pass.** That ordering is why there's no visible
jump — never `post()`ed, never in `onResume`.

## 7. Surviving data churn

- **`MessageListAdapter extends CursorAdapter`** with flags `= 2`
  (`FLAG_REGISTER_CONTENT_OBSERVER`). Content changes route to
  `onContentChanged()` → `mOnDataSetChangedListener.onContentChanged()` →
  `ComposeMessageActivity.startMsgListQuery()`. So a provider change kicks a
  fresh async query, and the result lands in the single `onQueryComplete` path
  above. **There is exactly one place where the list's contents and its scroll
  position are updated, and they're updated together.**
- **`onDataSetChanged()` is deliberately empty.** All the policy lives in the
  query callback.
- **`getItemId(int i)` returns `i`, the position — not the row `_id`.**
  `CursorAdapter.hasStableIds()` returns `true`, so `AbsListView.handleDataChanged()`
  will try to keep the same *id* selected across a data change. With id ==
  position, that resolves to keeping the same *index* selected. Combined with
  `stackFromBottom` and append-only growth, the highlight stays put when
  messages arrive rather than sliding around.
- **`AbsListView.RecyclerListener`** is installed in the adapter constructor and
  calls `MessageListItem.unbind()` on scrap, so recycled rows drop their PDU /
  bitmap references instead of holding them.
- **`MessageItemCache extends LruCache<Long, MessageItem>(50)`**, with
  `entryRemoved()` → `cancelPduLoading()`. Parsed message models are cached
  separately from views, so a fast scroll through 50 rows doesn't re-parse, and
  scrolling *past* something cancels its in-flight load. Note
  `notifyDataSetChanged()` calls `mMessageItemCache.evictAll()` — correctness
  over cache retention.
- **`getViewTypeCount() = 5`** (sms-in, sms-out, mms-in, mms-out, rcs-notice),
  with `newView()` inflating the matching layout and `bindView()` filling it.
  Correct view types mean the recycler never hands a send-bubble view to a
  receive row, which is a classic source of visible height thrash during scroll.

## 8. What's deliberately absent

The negative space is the actual lesson:

| Not present | Consequence |
|---|---|
| Any `OnScrollListener` in `com.android.mms.ui` | Nothing reacts to scroll, so nothing can feed back into it |
| Any override of `arrowScroll` / `focusSearch` / `dispatchKeyEvent` on the list | Stock d-pad behaviour, which is already good |
| Any handling of `KEYCODE_DPAD_UP` / `DOWN` in the activity | Same |
| `transcriptMode` auto-scroll | One authority on scroll position, not two |
| Focusable rows or row children | One focus stop for the whole transcript |
| A custom list selector or `state_focused` drawable | Framework highlight, correct by default |
| Scroll animation on new messages | Arrival is a cut; nothing to interrupt or queue |
| `RecyclerView` | Keeps the selection model that d-pad navigation needs |
| Nested scrolling containers around the list | No parent/child scroll arbitration |
| Any `post()`/`postDelayed()` around scroll positioning | No frame where position is wrong |

The app's scroll behaviour is roughly: **pick `ListView`, set six XML
attributes, make rows unfocusable, and write one method that answers a single
question — "should I jump to the bottom right now?"**

---

## 9. What this means for our chat

Comparing against `android/app/src/main/java/com/canvas/xp3/MainActivity.java`:

1. **`scrollToShow()` (`MainActivity.java:874`) and the `scrollListBy` block at
   `:803`–`:852` reimplement `ListView.arrowScrollImpl` + `amountToScroll`.**
   The comment at `:859` even says so ("the platform's own arrow scrolling").
   The Messages app deletes this entire category of code. Every behaviour those
   ~80 lines are reaching for — scroll the minimum, cap the step, scroll a tall
   row without moving the highlight — is what stock `arrowScroll` already does,
   and it's what the framework will keep doing correctly at edge cases we
   haven't hit yet. The first thing to try is deleting our key handling for
   up/down and letting `super` have it.

2. **Check what's focusable in our rows.** The Messages app enforces
   "one focus stop for the transcript" three ways over
   (`setItemsCanFocus(false)`, `blocksDescendants`, `linksClickable="false"`).
   If our rows contain anything focusable — or a `TextView` with autoLink that
   installs a `LinkMovementMethod` — the d-pad is being contested and no amount
   of scroll code will make it feel right. `Markdown.java` is worth auditing
   here specifically, since rendered links are exactly the trap.

3. **`SelectableAdapter.java:10` notes ListView's selector draws under the
   rows** and works around it. Messages sidesteps the whole issue by keeping the
   bubble narrower than the row, so the stock selector reads as a band behind
   it. Worth considering before adding per-row selected-state drawables.

4. **We have no equivalent of the `force` policy.** Messages distinguishes three
   causes of "the list got longer": you sent something (always jump), something
   new arrived (jump only if already at the bottom), and background churn
   (never jump). For a streaming agent transcript that distinction matters more
   than it does for SMS — tokens arriving into the last row will fire
   `notifyDataSetChanged()` constantly, and only the "was already at the bottom"
   test keeps that from yanking the user out of scrollback. `ChatAdapter.java:76`
   already notes the row is rebound as tokens stream in.

5. **Adopt the `Integer.MAX_VALUE` sentinel for saved position.** Storing "I was
   at the end" rather than an index is what makes returning to a grown
   transcript land correctly, and it's three lines.

6. **Do the scroll positioning in the same synchronous block as the data
   change**, never in a `post()`. That ordering is doing a lot of the work.

### Reference config

```xml
<ListView
    android:layout_width="match_parent"
    android:layout_height="0dp"
    android:layout_weight="1"
    android:stackFromBottom="true"
    android:transcriptMode="disabled"
    android:smoothScrollbar="false"
    android:fadingEdge="none"
    android:divider="@null"
    android:paddingBottom="8dp" />
```

```java
list.setItemsCanFocus(false);
list.setClipToPadding(false);
// and then: do not touch the arrow keys
```
