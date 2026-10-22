package ae.familyfinance.core.spend;

/**
 * What a child's purchase was for. The agent picks one of these; it can't invent new ones.
 * Some names match {@code family_rule.category} on purpose (IN_GAME_PURCHASES, SUGARY_DRINKS),
 * so a logged purchase can be checked against the family's rules.
 */
public enum SpendCategory {
    SCHOOL_CANTEEN("the school canteen"),
    SNACKS("snacks and treats"),
    SUGARY_DRINKS("fizzy and sugary drinks"),
    EATING_OUT("eating out"),
    IN_GAME_PURCHASES("in-game purchases"),
    TOYS_AND_HOBBIES("toys and hobbies"),
    BOOKS_AND_STATIONERY("books and stationery"),
    CLOTHES_AND_ACCESSORIES("clothes and accessories"),
    TRANSPORT("transport"),
    GIFTS_AND_CHARITY("gifts and giving"),
    OTHER("other things");

    private final String label;

    SpendCategory(String label) {
        this.label = label;
    }

    /** Plain words for the parent's story, e.g. "the school canteen". */
    public String label() {
        return label;
    }
}
