package ae.familyfinance.core.live;

import ae.familyfinance.core.live.LivePolicy.Delivery;
import ae.familyfinance.core.live.LivePolicy.Event;
import ae.familyfinance.core.live.LivePolicy.Member;
import ae.familyfinance.core.live.LivePolicy.Note;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LivePolicyTest {

    static final Member SARA = new Member(UUID.randomUUID(), "Sara", true, "sara@example.com");
    static final Member OMAR = new Member(UUID.randomUUID(), "Omar", true, null);
    static final Member LAYLA = new Member(UUID.randomUUID(), "Layla", false, null);
    static final Member ADAM = new Member(UUID.randomUUID(), "Adam", false, null);
    static final List<Member> FAMILY = List.of(SARA, OMAR, LAYLA, ADAM);

    static Delivery plan(LiveKind kind, Member subject, Member actor, Map<String, String> payload) {
        return LivePolicy.plan(new Event(kind, subject == null ? null : subject.id(), actor == null ? null : actor.id(), payload), FAMILY);
    }

    static List<UUID> told(Delivery d) {
        return d.notes().stream().map(Note::memberId).toList();
    }

    @Test
    void waiting_request_tells_parents_and_refreshes_the_asker_but_never_a_sibling() {
        Delivery d = plan(LiveKind.REQUEST_WAITING, LAYLA, LAYLA, Map.of("title", "Headphones", "amount", "AED 300"));
        assertThat(told(d)).containsExactlyInAnyOrder(SARA.id(), OMAR.id());
        assertThat(d.liveTo()).containsExactlyInAnyOrder(SARA.id(), OMAR.id(), LAYLA.id());
        assertThat(d.notes().get(0).title()).isEqualTo("Layla asks for Headphones");
        assertThat(d.notes()).noneMatch(Note::email);
    }

    @Test
    void a_parent_is_not_told_about_their_own_request_or_decision() {
        assertThat(told(plan(LiveKind.REQUEST_WAITING, SARA, SARA, Map.of("title", "Kettle", "amount", "AED 150"))))
                .containsExactly(OMAR.id());
        // Omar decides Sara's request: Sara is told, Omar is not
        assertThat(told(plan(LiveKind.REQUEST_DECIDED, SARA, OMAR, Map.of("title", "Kettle", "decision", "APPROVED", "decidedBy", "Omar"))))
                .containsExactly(SARA.id());
    }

    @Test
    void the_child_hears_the_answer_with_the_wallet_note() {
        Delivery d = plan(LiveKind.REQUEST_DECIDED, ADAM, SARA, Map.of("title", "Stickers", "amount", "AED 15",
                "decision", "APPROVED", "decidedBy", "Sara", "paidNote", "AED 15 came out of your wallet. You have AED 40 left."));
        assertThat(told(d)).containsExactly(ADAM.id());
        assertThat(d.notes().get(0).title()).isEqualTo("Sara said yes to Stickers");
        assertThat(d.notes().get(0).body()).isEqualTo("AED 15 came out of your wallet. You have AED 40 left.");
        assertThat(d.notes().get(0).link()).isEqualTo("/child");
        assertThat(d.liveTo()).doesNotContain(LAYLA.id());
    }

    @Test
    void a_wallet_change_only_pings_screens() {
        Delivery d = plan(LiveKind.WALLET_CHANGED, LAYLA, null, Map.of());
        assertThat(d.notes()).isEmpty();
        assertThat(d.liveTo()).containsExactlyInAnyOrder(SARA.id(), OMAR.id(), LAYLA.id());
    }

    @Test
    void money_alerts_go_to_parents_only_and_by_email() {
        for (LiveKind k : List.of(LiveKind.BILL_DUE, LiveKind.CARD_DUE, LiveKind.OVERSPEND, LiveKind.WEEKLY_PLAN)) {
            Delivery d = plan(k, null, null, Map.of("title", "DEWA due tomorrow", "body", "AED 900."));
            assertThat(told(d)).containsExactlyInAnyOrder(SARA.id(), OMAR.id());
            assertThat(d.liveTo()).doesNotContain(LAYLA.id(), ADAM.id());
            assertThat(d.notes()).allMatch(Note::email);
        }
    }
}
