#!/usr/bin/env python3
"""Build the app's Spanish deck and initial FSRS state from the live Anki profile."""

from __future__ import annotations

import argparse
import html
import math
import re
import sqlite3
import tempfile
import time
from pathlib import Path

from anki.collection import Collection


DEFAULT_COLLECTION = Path.home() / ".local/share/Anki2/User 1/collection.anki2"
DEFAULT_DECK = "Spanish Top 3000 (other cards after that suspended)"
BR_RE = re.compile(r"<br\s*/?>", re.IGNORECASE)
TAG_RE = re.compile(r"<[^>]*>")


def plain(value: str) -> str:
    value = BR_RE.sub("\n", value)
    value = TAG_RE.sub("", value)
    value = html.unescape(value).replace("\u00ad", "")
    return "\n".join(line.strip() for line in value.splitlines() if line.strip())


def escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


def rank(value: str) -> int:
    try:
        return int(value.replace(",", "").strip())
    except ValueError:
        return 2**31 - 1


def snapshot(source: Path, target: Path) -> None:
    source_db = sqlite3.connect(f"file:{source}?mode=ro", uri=True)
    target_db = sqlite3.connect(target)
    try:
        source_db.backup(target_db)
    finally:
        target_db.close()
        source_db.close()


def retrievability(item: dict, now: int) -> float:
    memory = item["memory"]
    elapsed_days = max(0, (now - item["last_review"]) // 86400)
    decay = -memory.decay
    factor = math.pow(0.9, 1.0 / decay) - 1.0
    return math.pow(1.0 + factor * elapsed_days / memory.stability, decay)


def merge_duplicates(cards: list[dict]) -> list[dict]:
    now = int(time.time())
    grouped: dict[tuple[str, str], list[dict]] = {}
    for item in cards:
        grouped.setdefault(item["dedupe_key"], []).append(item)

    merged = []
    for group in grouped.values():
        canonical = min(group, key=lambda item: item["id"]).copy()
        word_types = []
        for item in group:
            if item["word_type"] and item["word_type"] not in word_types:
                word_types.append(item["word_type"])
        spanish_side = canonical["spanish"]
        if word_types:
            spanish_side += f" ({' / '.join(word_types)})"
        canonical["prompt"], canonical["answer"] = (
            (spanish_side, canonical["english"])
            if canonical["ord"] == 0
            else (canonical["english"], spanish_side)
        )
        seen_aux = set()
        aux_parts = []
        for item in group:
            if item["aux"] and item["aux"] not in seen_aux:
                seen_aux.add(item["aux"])
                aux_parts.append(item["aux"])
        canonical["aux"] = "\n".join(aux_parts)

        reviewed = [
            item
            for item in group
            if item["memory"] is not None
            and item["memory"].stability is not None
            and item["memory"].difficulty is not None
        ]
        if reviewed:
            # Avoid overestimating a merged card: retain the reviewed source
            # that is currently most likely to be forgotten.
            selected = min(reviewed, key=lambda item: retrievability(item, now))
            for field in (
                "queue",
                "type",
                "due",
                "due_epoch",
                "left",
                "last_review",
                "memory",
            ):
                canonical[field] = selected[field]
        else:
            canonical["memory"] = None
        merged.append(canonical)
    return merged


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--collection", type=Path, default=DEFAULT_COLLECTION)
    parser.add_argument("--deck", default=DEFAULT_DECK)
    parser.add_argument("--deck-output", type=Path, required=True)
    parser.add_argument("--state-output", type=Path, required=True)
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="flashcards-anki-") as temp_dir:
        copied = Path(temp_dir) / "collection.anki2"
        snapshot(args.collection, copied)
        col = Collection(str(copied))
        try:
            all_ids = col.find_cards(f'deck:"{args.deck}"')
            active_ids = col.find_cards(
                f'deck:"{args.deck}" -is:suspended -is:buried'
            )
            active_count = len(active_ids)
            cards = []
            for card_id in active_ids:
                card = col.get_card(card_id)
                note = card.note()
                spanish = plain(note["Spanish"])
                english = plain(note["English"])
                if not spanish or not english:
                    # The source deck contains an HTML abbreviation legend,
                    # not a vocabulary card; it has no English field.
                    continue
                word_type = plain(note["Word Type"])
                examples_es = plain(note["Spanish Examples"])
                examples_en = plain(note["English Examples"])
                spanish_side = f"{spanish} ({word_type})" if word_type else spanish
                prompt, answer = (
                    (spanish_side, english)
                    if card.ord == 0
                    else (english, spanish_side)
                )
                aux = "\n".join(part for part in (examples_es, examples_en) if part)
                cards.append(
                    {
                        "id": int(card_id),
                        "rank": rank(note["Ranking"]),
                        "ord": int(card.ord),
                        "prompt": prompt,
                        "answer": answer,
                        "spanish": spanish,
                        "english": english,
                        "word_type": word_type,
                        "dedupe_key": (
                            (spanish, english) if card.ord == 0 else (english, spanish)
                        ),
                        "aux": aux,
                        "queue": int(card.queue),
                        "type": int(card.type),
                        "due": int(card.due),
                        "left": int(card.left),
                        "last_review": card.last_review_time,
                    }
                )

            timing = col.sched._timing_today()
            for item in cards:
                if item["last_review"] is None:
                    item["memory"] = None
                    continue
                item["memory"] = col.compute_memory_state(item["id"])
                if item["queue"] in (1, 3):
                    item["due_epoch"] = item["due"]
                else:
                    item["due_epoch"] = (
                        timing.next_day_at
                        + (item["due"] - timing.days_elapsed - 1) * 86400
                    )
            source_count = len(cards)
            cards = merge_duplicates(cards)
            cards.sort(key=lambda item: (item["rank"], item["ord"], item["id"]))
        finally:
            col.close()

    args.deck_output.parent.mkdir(parents=True, exist_ok=True)
    args.state_output.parent.mkdir(parents=True, exist_ok=True)
    with args.deck_output.open("w", encoding="utf-8", newline="\n") as deck_file:
        deck_file.write("# Generated from Anki; suspended and buried cards are excluded.\n")
        for item in cards:
            fields = (
                item["prompt"],
                item["answer"],
                item["aux"],
                f"anki:{item['id']}",
            )
            deck_file.write("\t".join(escape(field) for field in fields) + "\n")

    state_count = 0
    with args.state_output.open("w", encoding="utf-8", newline="\n") as state_file:
        state_file.write("# Initial FSRS-6 state computed from Anki review history.\n")
        for item in cards:
            memory = item["memory"]
            if memory is None or memory.stability is None or memory.difficulty is None:
                continue
            if item["type"] == 2:
                state, step = "2", ""
            elif item["type"] == 3:
                state, step = "3", "0"
            else:
                state = "1"
                step = "0" if item["left"] >= 2 else "1"
            fields = (
                item["prompt"],
                item["answer"],
                state,
                step,
                repr(memory.stability),
                repr(memory.difficulty),
                str(item["due_epoch"]),
                str(item["last_review"]),
                f"anki:{item['id']}",
            )
            state_file.write("\t".join(escape(field) for field in fields) + "\n")
            state_count += 1

    print(
        f"Imported {len(cards)} cards ({source_count - len(cards)} duplicates merged) "
        f"with {state_count} FSRS states; "
        f"excluded {len(all_ids) - active_count} suspended/buried cards and "
        f"{active_count - source_count} non-vocabulary cards."
    )


if __name__ == "__main__":
    main()
