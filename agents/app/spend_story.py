"""Puts the money engine's numbers into plain words.

Every number in these sentences is copied from a field the core sent (a MoneyView.text, a count, a percent).
Nothing here adds, subtracts or divides money. The child is always called by name, never "he" or "she",
because the app doesn't store anyone's pronouns.
"""

from datetime import date

from .core_client import LogResult, LoggingStatus, SpendSummary

_RECENT_DAYS = 3  # if nothing was logged for longer than this, say the picture may be missing things


_NUMBER_WORDS = {2: "two", 3: "three", 4: "four", 5: "five", 6: "six"}
_KEEP_CAPITALS = {"robux", "pokemon", "pokémon", "lego", "roblox", "fortnite", "minecraft"}


def _day(d: date) -> str:
    return f"{d.day} {d.strftime('%b')}"


def _times(n: int) -> str:
    return "once" if n == 1 else "twice" if n == 2 else f"{n} times"


def _join(parts: list[str]) -> str:
    return parts[0] if len(parts) == 1 else ", ".join(parts[:-1]) + " and " + parts[-1]


def _thing(description: str) -> str:
    """'Chicken wrap' -> 'chicken wrap', but 'Robux gift card' stays as it is."""
    first = description.split(" ", 1)[0].lower()
    return description if first in _KEEP_CAPITALS or description[:2].isupper() else description[:1].lower() + description[1:]


def parent_story(s: SpendSummary, today: date) -> str:
    """'Where did Layla's money go this month?' -> a short story, numbers straight from the summary."""
    name = s.displayName
    when = f"So far in {s.periodLabel.split()[0]}" if s.monthToDate else f"In {s.periodLabel}"

    if s.purchaseCount == 0:
        last = f" The last thing {name} logged was on {_day(s.lastLoggedOn)}." if s.lastLoggedOn else ""
        return f"{when}, {name} hasn't logged any spending yet, so I can't tell where the money went.{last}"

    story: list[str] = [f"{when}, {name} has logged {s.total.text} across {s.purchaseCount} purchases, "
                        f"on {s.daysLogged} of {s.daysInPeriod} days."]

    top = s.categories[0]
    lead = "Most of it went on" if top.percent > 50 else "The biggest share went on"
    favourites = [i for i in s.topItems if i.category is top.category and i.count > 1][:2]
    usual = (f", usually {_join([f'{_thing(i.description)} ({_times(i.count)})' for i in favourites])}"
             if favourites else "")
    streak = ""
    if s.topCategoryWeeksRunning >= 2:
        weeks = _NUMBER_WORDS.get(s.topCategoryWeeksRunning, str(s.topCategoryWeeksRunning))
        streak = f" That's been the top spend {weeks} weeks running."
    story.append(f"{lead} {top.label}: {top.total.text} ({top.percent}%), about {top.averagePerPurchase.text} "
                 f"a time{usual}.{streak}")

    if len(s.categories) > 1:
        rest = []
        for c in s.categories[1:3]:
            single = next((i for i in s.topItems if i.category is c.category), None) if c.count == 1 else None
            rest.append(f"{c.label} ({c.total.text}{', one ' + _thing(single.description) if single else ''})")
        story.append(f"After that came {_join(rest)}.")

    money: list[str] = []
    if s.pocketMoney.minor > 0:
        money.append(f"{s.spentPercentOfPocketMoney}% of the {s.pocketMoney.text} pocket money "
                     f"{'paid so far' if s.monthToDate else 'paid that month'}")
    p = s.previous
    # Only compare when last month has a similar amount of logging, otherwise the comparison is misleading
    if p.daysLogged > 0 and p.daysLogged * 2 >= s.daysLogged:
        point = f"by this point in {p.periodLabel.split()[0]}" if s.monthToDate else f"in {p.periodLabel}"
        money.append(f"the same as {point}" if p.direction == "SAME"
                     else f"{p.difference.text} {'more' if p.direction == 'MORE' else 'less'} than {point}")
    if money:
        story.append(f"In total, that's {', and '.join(money)}.")

    extra: list[str] = []
    if s.againstFamilyRules:
        flagged = [f"{_thing(e.description)} ({e.amount.text}, {_day(e.occurredOn)})" for e in s.againstFamilyRules[:3]]
        notes = sorted({f"\u201c{e.familyRuleNote}\u201d" for e in s.againstFamilyRules[:3]})
        extra.append(f"Worth a chat: {name} logged {_join(flagged)}, which goes against "
                     f"{_join(notes)}. {name} was honest about it, which is a good sign.")
    for g in s.goals:
        extra.append(f"{name} has {g.saved.text} of {g.target.text} saved for {_thing(g.name)}, "
                     f"{g.remaining.text} to go.")
    if s.lastLoggedOn and (today - s.lastLoggedOn).days > _RECENT_DAYS:
        extra.append(f"Nothing logged since {_day(s.lastLoggedOn)}, so recent spending may be missing.")

    return " ".join(story) + ("\n\n" + " ".join(extra) if extra else "")


def child_confirmation(result: LogResult) -> str:
    """What the child sees after logging."""
    parts = [f"{_thing(e.description)} {e.amount.text} ({e.categoryLabel})" for e in result.logged]
    reply = [f"Got it: {_join(parts)}. Today so far: {result.today.text}. This month: {result.thisMonth.text}."]
    dupes = [e for e in result.logged if e.possibleDuplicate]
    if dupes:
        reply.append(f"You'd already logged {_thing(dupes[0].description)} for {dupes[0].amount.text} that day. "
                     "I saved it again in case you bought two. If it was a mistake, tell a parent.")
    rules = [e for e in result.logged if e.familyRuleNote]
    if rules:
        reply.append(f"Thanks for being honest. Your family rule says “{rules[0].familyRuleNote}”, "
                     "so a parent will see this one.")
    return " ".join(reply)


def ask_for_amount(what: str | None) -> str:
    if what:
        return f"How much was the {what.lower()}? You can say it like “12 dirhams {what.lower()}”."
    return "Tell me what you bought and how much it was, like “12 dirhams shawarma”."


def nudge(status: LoggingStatus) -> str | None:
    """A gentle reminder for a child who hasn't logged anything today."""
    if status.loggedToday:
        return None
    if status.schoolDay:
        return "Did you buy lunch or a snack at school today? Tell me like “8 dirhams chicken wrap”."
    return "Bought anything today? Tell me like “16 dirhams bubble tea” and I'll keep track."

