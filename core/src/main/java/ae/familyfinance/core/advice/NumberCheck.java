package ae.familyfinance.core.advice;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The core's copy of the agents' number check: every number in words an LLM wrote must appear in the facts the
 * engine gave it. The agent checks first; the core checks again before it stores the words, so a bug in the agent
 * service can't put a made-up number in front of a parent.
 */
public final class NumberCheck {

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private NumberCheck() {
    }

    /** "AED 6,500 by 14 Nov" -> {"6500", "14"}. "06" and "6" are the same number; "12.50" and "12.5" too. */
    static Set<String> numbersIn(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            String n = m.group().replace(",", "");
            if (n.contains(".")) {
                n = n.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            int dot = n.indexOf('.');
            String whole = (dot < 0 ? n : n.substring(0, dot)).replaceFirst("^0+(?=.)", "");
            out.add(whole.isEmpty() ? "0" : whole + (dot < 0 ? "" : n.substring(dot)));
        }
        return out;
    }

    public static boolean ok(String text, List<String> facts) {
        Set<String> allowed = new HashSet<>();
        facts.forEach(f -> allowed.addAll(numbersIn(f)));
        return allowed.containsAll(numbersIn(text));
    }
}
