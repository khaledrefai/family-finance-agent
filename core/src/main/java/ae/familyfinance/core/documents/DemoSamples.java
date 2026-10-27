package ae.familyfinance.core.documents;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Two demo files for the Haddad family, dated relative to today so they always line up with the seed:
 * a Carrefour receipt paid on Omar's Visa, and that Visa's statement for the last month. Together they show
 * every week 3 moment: a receipt read and confirmed, the same purchase on the statement not counted twice,
 * payments typed in by hand recognised, a double charge at Spinneys, and a Shahid subscription nobody listed
 * (its two earlier months are in the seed). Every line is dated before the Visa's typed balance, so confirming
 * them does not change the card warning in the demo.
 */
@Component
public class DemoSamples {

    public record Sample(String fileName, String contentType, String text) {
    }

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** The receipt's items; they add up to the total printed under them (checked by a test). */
    static final List<String[]> RECEIPT_ITEMS = List.of(
            new String[]{"Whole chicken x2", "44.00"}, new String[]{"Basmati rice 5kg", "39.00"},
            new String[]{"Hammour fillet 1kg", "71.00"}, new String[]{"Laban 2L x2", "17.00"},
            new String[]{"Eggs 30 pack", "22.50"}, new String[]{"Tomatoes 2kg", "9.50"},
            new String[]{"Cucumbers 1kg", "6.75"}, new String[]{"Arabic bread x4", "8.00"},
            new String[]{"Olive oil 1L", "42.00"}, new String[]{"Khalas dates 1kg", "55.00"},
            new String[]{"Chocolate biscuits x3", "18.75"}, new String[]{"Ice cream 2L", "22.50"},
            new String[]{"Washing powder 6kg", "64.00"}, new String[]{"Chicken breast 2kg", "58.00"},
            new String[]{"Lamb mince 1kg", "49.00"}, new String[]{"Apples 2kg", "16.50"},
            new String[]{"Milk 2L x3", "27.00"}, new String[]{"Cheese slices", "19.25"},
            new String[]{"Dishwasher tablets", "51.75"});
    static final String RECEIPT_TOTAL = "641.50";

    private final Clock clock;

    public DemoSamples(Clock clock) {
        this.clock = clock;
    }

    public Sample get(String name) {
        LocalDate today = LocalDate.now(clock);
        return switch (name) {
            case "receipt" -> new Sample("carrefour-receipt.txt", "text/plain", receipt(today));
            case "statement" -> new Sample("visa-statement.csv", "text/csv", statement(today));
            default -> null;
        };
    }

    static String receipt(LocalDate today) {
        StringBuilder r = new StringBuilder();
        r.append("CARREFOUR\nMIRDIF CITY CENTRE, DUBAI\nTRN 100012345600003\n\n");
        r.append("Date: ").append(today.minusDays(6).format(DMY)).append("  18:42\n\n");
        for (String[] item : RECEIPT_ITEMS) {
            r.append(String.format("%-28s %8s%n", item[0], item[1]));
        }
        r.append("\n").append(String.format("%-28s %8s%n", "TOTAL AED", RECEIPT_TOTAL));
        r.append(String.format("%-28s %8s%n", "VAT 5% INCLUDED", "30.55"));
        r.append("\nPAID BY VISA ************7710\nTHANK YOU FOR SHOPPING AT CARREFOUR\n");
        return r.toString();
    }

    static String statement(LocalDate today) {
        Object[][] lines = {
                {29, "NOON.COM DUBAI", "249.00"},
                {26, "TALABAT DUBAI", "86.50"},
                {23, "SALIK TOP UP", "50.00"},
                {15, "PAYMENT RECEIVED - THANK YOU", "-3200.00"},
                {14, "SPINNEYS JUMEIRAH", "189.00"},
                {14, "SPINNEYS JUMEIRAH", "189.00"},
                {12, "DU TELECOM", "450.00"},
                {11, "BOOTS PHARMACY DUBAI MALL", "63.25"},
                {9, "SHAHID VIP", "35.00"},
                {7, "CAREEM RIDE", "42.00"},
                {5, "CARREFOUR MIRDIF CITY CENTRE", RECEIPT_TOTAL},
                {3, "ENOC 1023 AL BARSHA", "180.00"},
                {2, "LOCAL RESTAURANT JLT", "235.00"},
        };
        StringBuilder s = new StringBuilder();
        s.append("Card statement,Omar's Visa\n");
        s.append("Card number,**** **** **** 7710\n");
        s.append("Statement date,").append(today.minusDays(1)).append('\n');
        s.append("Closing balance,").append(new BigDecimal("5950.00").toPlainString()).append('\n');
        s.append('\n').append("Date,Description,Amount\n");
        for (Object[] l : lines) {
            s.append(today.minusDays((Integer) l[0])).append(',').append(l[1]).append(',').append(l[2]).append('\n');
        }
        return s.toString();
    }
}
