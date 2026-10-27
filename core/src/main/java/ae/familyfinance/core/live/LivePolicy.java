package ae.familyfinance.core.live;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who hears about an event, and in what words. Pure Java, no database, so every case is unit-tested.
 *
 * <ul>
 *   <li>{@code liveTo}: open screens that should refresh now (the SSE ping). It carries no money, only "look again".</li>
 *   <li>{@code notes}: the members who are told, with a title and a sentence. These go to the bell, to web push,
 *       and (parents only, for the core's own alerts) to email.</li>
 * </ul>
 *
 * Children only ever hear about their own requests and their own wallet; never a sibling's, never the family's money.
 * A parent is not told about something they did themselves.
 */
public final class LivePolicy {

    public record Member(UUID id, String name, boolean parent, String email) {
    }

    public record Event(LiveKind kind, UUID subject, UUID actor, Map<String, String> payload) {

        String get(String key) {
            String v = payload.get(key);
            return v == null ? "" : v;
        }
    }

    public record Note(UUID memberId, String title, String body, String link, boolean email) {
    }

    public record Delivery(Set<UUID> liveTo, List<Note> notes) {
    }

    private LivePolicy() {
    }

    public static Delivery plan(Event e, List<Member> family) {
        Set<UUID> live = new LinkedHashSet<>();
        List<Note> notes = new ArrayList<>();
        List<Member> parents = family.stream().filter(Member::parent).toList();
        Member subject = family.stream().filter(m -> m.id().equals(e.subject())).findFirst().orElse(null);
        boolean subjectIsChild = subject != null && !subject.parent();
        String who = subject == null ? "Someone" : subject.name();
        String title = e.get("title");
        String amount = e.get("amount");

        switch (e.kind()) {
            case REQUEST_WAITING -> {
                parents.forEach(p -> live.add(p.id()));
                if (subject != null) {
                    live.add(subject.id());
                }
                // A parent's own request goes to the other parent only
                for (Member p : parents) {
                    if (!p.id().equals(e.subject())) {
                        notes.add(new Note(p.id(), who + " asks for " + title,
                                amount + ". " + orDefault(e.get("summary"), "It is waiting for you on Today."), "/parent", false));
                    }
                }
            }
            case REQUEST_BLOCKED -> {
                parents.forEach(p -> live.add(p.id()));
                if (subject != null) {
                    live.add(subject.id());
                }
                // The child already sees the reason on screen; the parents get a quiet heads-up
                for (Member p : parents) {
                    if (!p.id().equals(e.subject())) {
                        notes.add(new Note(p.id(), "Blocked by a family rule: " + title,
                                who + " asked for " + title + " (" + amount + "). " + e.get("reason"), "/parent/family", false));
                    }
                }
            }
            case REQUEST_DECIDED -> {
                parents.forEach(p -> live.add(p.id()));
                if (subject != null) {
                    live.add(subject.id());
                }
                boolean yes = "APPROVED".equals(e.get("decision"));
                String parent = orDefault(e.get("decidedBy"), "A parent");
                if (subject != null && !subject.id().equals(e.actor())) {
                    String body;
                    if (subjectIsChild) {
                        body = yes ? orDefault(e.get("paidNote"), "") : "Talk to your parents if you want to know why.";
                    } else {
                        body = yes ? "Approved." : "Declined.";
                    }
                    notes.add(new Note(subject.id(), parent + (yes ? " said yes to " : " said no to ") + title,
                            body.isBlank() ? amount + "." : body, subjectIsChild ? "/child" : "/parent", false));
                }
                // The other parent sees it move from Waiting to Decided; no need to buzz their phone
            }
            case WALLET_CHANGED -> {
                parents.forEach(p -> live.add(p.id()));
                if (subjectIsChild) {
                    live.add(subject.id());
                }
            }
            case BILL_DUE, CARD_DUE, OVERSPEND, WEEKLY_PLAN -> {
                String link = switch (e.kind()) {
                    case CARD_DUE -> "/parent/money/accounts";
                    case OVERSPEND -> "/parent/money/budgets";
                    case BILL_DUE -> "/parent/money#bills";
                    default -> "/parent";
                };
                for (Member p : parents) {
                    live.add(p.id());
                    notes.add(new Note(p.id(), title, e.get("body"), link, true));
                }
            }
            case OUTINGS_PLANNED -> {
                // Everyone hears the plan; the kids only get the places, never what they cost
                for (Member m : family) {
                    live.add(m.id());
                    if (!m.id().equals(e.actor())) {
                        notes.add(new Note(m.id(), "Family fun in " + e.get("month"), e.get("places"),
                                m.parent() ? "/parent/plan/outings" : "/child", false));
                    }
                }
            }
            case MONTH_SUMMARY -> {
                String month = e.get("month");
                String label = orDefault(e.get("monthLabel"), "The month");
                for (Member m : family) {
                    live.add(m.id());
                    notes.add(m.parent()
                            ? new Note(m.id(), title, e.get("body"), "/parent/summary?month=" + month, true)
                            : new Note(m.id(), label + " with the camel",
                                    "See your stars, your goal and how your month went.", "/child/month?month=" + month, false));
                }
            }
            case MEALS_PLANNED -> {
                for (Member m : family) {
                    live.add(m.id());
                    if (!m.id().equals(e.actor())) {
                        notes.add(new Note(m.id(), "Dinners this week", e.get("dinners"),
                                m.parent() ? "/parent/plan/meals" : "/child", false));
                    }
                }
            }
            case TEST -> {
                if (subject != null) {
                    live.add(subject.id());
                    notes.add(new Note(subject.id(), "Notifications are on",
                            "This is how a request or a bill reminder will look on this phone.",
                            subject.parent() ? "/parent" : "/child", subject.parent()));
                }
            }
        }
        return new Delivery(live, notes);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
