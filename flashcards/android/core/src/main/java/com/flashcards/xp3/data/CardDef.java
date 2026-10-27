package com.flashcards.xp3.data;

/**
 * One flashcard: a key (the prompt), a value (the answer), and optional
 * auxiliary text such as an example sentence.
 *
 * This is the whole data model. A deck is an ordered list of these. There is
 * no editor and no two-sided template language (Menu > Import reads FKV,
 * key and value, see {@link Fkv}): a card is three strings, and that is all
 * a flashcard needs to be on a flip phone.
 */
public final class CardDef {

    public final String key;
    public final String value;
    public final String aux;
    /** Stable storage identity; unlike the visible text, this need not be unique to the learner. */
    public final String identity;

    public CardDef(String key, String value, String aux, String identity) {
        this.key = key;
        this.value = value;
        this.aux = aux;
        this.identity = identity;
    }

    public CardDef(String key, String value, String aux) {
        this(key, value, aux, key + "\n" + value);
    }

    public CardDef(String key, String value) {
        this(key, value, "");
    }
}
