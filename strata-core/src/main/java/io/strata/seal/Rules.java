package io.strata.seal;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Class and member patterns from strata.seal: {@code pkg.Class}, {@code pkg.*} (classes directly
 * in pkg), {@code pkg.**} (pkg and subpackages), each optionally {@code #member} where {@code *}
 * matches anything. Class names use dots; nested classes use {@code $}.
 */
final class Rules {
    private record Rule(Pattern owner, Pattern member, String text) {}

    private final List<Rule> rules;

    Rules(List<String> patterns) {
        this.rules = patterns.stream().map(Rules::compile).toList();
    }

    private static Rule compile(String p) {
        String text = p.trim();
        int hash = text.indexOf('#');
        String owner = hash < 0 ? text : text.substring(0, hash);
        String member = hash < 0 ? null : text.substring(hash + 1);
        String re;
        if (owner.endsWith(".**")) re = Pattern.quote(owner.substring(0, owner.length() - 3)) + "\\..+";
        else if (owner.endsWith(".*")) re = Pattern.quote(owner.substring(0, owner.length() - 2)) + "\\.[^.]+";
        else re = glob(owner);
        return new Rule(Pattern.compile(re), member == null ? null : Pattern.compile(glob(member)), text);
    }

    /** A pattern where * matches any run of characters and everything else is literal. */
    private static String glob(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '*') b.append(".*");
            else b.append(Pattern.quote(String.valueOf(ch)));
        }
        return b.toString();
    }

    /** The first rule matching this class (and member, when the rule names one), or null. */
    String match(String owner, String member) {
        for (Rule r : rules) {
            if (!r.owner().matcher(owner).matches()) continue;
            if (r.member() == null || (member != null && r.member().matcher(member).matches())) return r.text();
        }
        return null;
    }
}
